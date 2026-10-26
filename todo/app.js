'use strict';
/* Doable for iPhone — an offline web app. All data lives in this browser's localStorage. */

const APP_NAME = 'Doable';
const STORE_KEY = 'daybook.v1';
const DONE_VISIBLE_MS = 12 * 60 * 60 * 1000;
const PRIORITY = ['None', 'Low', 'Medium', 'High'];
const PALETTE = ['#3F2CB8', '#1E9C9A', '#E5484D', '#F2A33A', '#8E4EC6', '#3D8BFD', '#2F9E44', '#D6409F'];
const FLAG = '<svg viewBox="0 0 24 24"><path d="M14.4 6 14 4H5v17h2v-7h5.6l.4 2h7V6z"/></svg>';
const FLAG_OUTLINE = '<svg viewBox="0 0 24 24"><path d="M12.36 6l.4 2H18v6h-3.36l-.4-2H7V6h5.36M14 4H5v17h2v-7h5.6l.4 2h7V6h-5.6L14 4z"/></svg>';
const TICK = '<svg viewBox="0 0 24 24"><path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"/></svg>';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

// ------------------------------------------------------------------ storage

function freshState() {
  return {
    nextId: 4,
    categories: [
      { id: 1, name: 'Private', parent: 0, color: PALETTE[1] },
      { id: 2, name: 'Work', parent: 0, color: PALETTE[0] },
      { id: 3, name: 'Accenture', parent: 2, color: PALETTE[4] },
    ],
    tasks: [],
    log: [],
    settings: { selTop: 0, selSub: 0, showDone: false, lock: null },
  };
}

function load() {
  try {
    const s = JSON.parse(localStorage.getItem(STORE_KEY));
    if (s && Array.isArray(s.tasks)) return s;
  } catch (e) { /* fall through */ }
  return freshState();
}

let state = load();
const save = () => localStorage.setItem(STORE_KEY, JSON.stringify(state));
const newId = () => state.nextId++;

if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});

// ------------------------------------------------------------------ dates

const todayStr = () => ymd(new Date());
function ymd(d) {
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
function parseYmd(s) {
  const [y, m, d] = s.split('-').map(Number);
  return new Date(y, m - 1, d);
}
function addDays(n) {
  const d = new Date();
  d.setDate(d.getDate() + n);
  return ymd(d);
}
function daysFromToday(s) {
  const t = parseYmd(todayStr());
  return Math.round((parseYmd(s) - t) / 86400000);
}
const shortDate = (d) => d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' });
const timeOf = (ms) => new Date(ms).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
function dueLabel(s) {
  const n = daysFromToday(s);
  if (n === 0) return 'Due today';
  if (n === 1) return 'Due tomorrow';
  if (n < 0) return 'Overdue · ' + shortDate(parseYmd(s));
  return 'Due ' + shortDate(parseYmd(s));
}
function dayLabel(ms) {
  const n = daysFromToday(ymd(new Date(ms)));
  if (n === 0) return 'Today';
  if (n === -1) return 'Yesterday';
  return new Date(ms).toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'short', year: 'numeric' });
}

// ------------------------------------------------------------------ categories

const cat = (id) => state.categories.find((c) => c.id === id);
const topCats = () => state.categories.filter((c) => c.parent === 0);
const subCats = (id) => state.categories.filter((c) => c.parent === id);
function catPath(id) {
  const k = cat(id);
  if (!k) return '';
  const p = cat(k.parent);
  return p ? p.name + ' › ' + k.name : k.name;
}
/** Categories in display order: each top-level one followed by its sub-categories. */
const orderedCats = () => topCats().flatMap((t) => [t, ...subCats(t.id)]);

function addCategory(name, parent) {
  const k = { id: newId(), name, parent, color: PALETTE[state.categories.length % PALETTE.length] };
  state.categories.push(k);
  return k;
}

/** Tasks are kept: a sub-category's move to its parent, a top-level one's become uncategorised. */
function deleteCategory(k) {
  const doomed = new Set([k.id, ...subCats(k.id).map((c) => c.id)]);
  state.tasks.forEach((t) => { if (doomed.has(t.category)) t.category = k.parent; });
  state.categories = state.categories.filter((c) => !doomed.has(c.id));
}

function validateFilter() {
  const s = state.settings;
  if (s.selTop && !cat(s.selTop)) s.selTop = 0;
  if (s.selSub && (!cat(s.selSub) || cat(s.selSub).parent !== s.selTop)) s.selSub = 0;
}

function matchesFilter(t) {
  const { selTop, selSub } = state.settings;
  if (!selTop) return true;
  if (selSub) return t.category === selSub;
  if (t.category === selTop) return true;
  const k = cat(t.category);
  return !!k && k.parent === selTop;
}

// ------------------------------------------------------------------ rendering

let mode = 'tasks'; // or 'log'
let pendingDue = '';

function render() {
  validateFilter();
  const tasks = mode === 'tasks';
  $('title').textContent = tasks ? APP_NAME : 'Activity log';
  $('back-btn').hidden = tasks;
  $('due-btn').hidden = !tasks;
  $('chips-top').hidden = !tasks;
  $('due-dot').hidden = !pendingDue;
  if (tasks) renderChips(); else $('chips-sub').hidden = true;
  $('input').placeholder = inputHint();
  if (tasks) renderTasks(); else renderLog();
}

function inputHint() {
  if (mode === 'log') return 'What did you just do?';
  const k = cat(state.settings.selSub || state.settings.selTop);
  let h = 'Add a task' + (k ? ' to ' + k.name : '');
  if (pendingDue) h += ' · ' + dueLabel(pendingDue).toLowerCase();
  return h + '…';
}

function chipHtml(label, color, on, attrs) {
  return `<button class="chip${on ? ' on' : ''}" style="--c:${color}" ${attrs}>${esc(label)}</button>`;
}

function renderChips() {
  const { selTop, selSub } = state.settings;
  let top = chipHtml('All', 'var(--primary)', !selTop, 'data-top="0"');
  for (const k of topCats()) top += chipHtml(k.name, k.color, selTop === k.id, `data-top="${k.id}" data-cat="${k.id}"`);
  top += '<button class="chip ghost" data-new="0">+ Category</button>';
  $('chips-top').innerHTML = top;

  const sub = $('chips-sub');
  if (!selTop) { sub.hidden = true; return; }
  const t = cat(selTop);
  let html = chipHtml('All ' + t.name, t.color, !selSub, `data-top="${t.id}" data-sub="0"`);
  for (const k of subCats(t.id)) html += chipHtml(k.name, k.color, selSub === k.id, `data-top="${t.id}" data-sub="${k.id}" data-cat="${k.id}"`);
  html += `<button class="chip ghost" data-new="${t.id}">+ Sub-category</button>`;
  sub.innerHTML = html;
  sub.hidden = false;
}

function renderTasks() {
  const now = Date.now();
  const today = todayStr();
  const open = [], done = [];
  let high = 0, overdue = 0, hidden = 0;
  for (const t of state.tasks) {
    if (!matchesFilter(t)) continue;
    if (!t.done) {
      open.push(t);
      if (t.priority === 3) high++;
      if (t.due && t.due < today) overdue++;
    } else if (state.settings.showDone || now - t.doneAt < DONE_VISIBLE_MS) {
      done.push(t);
    } else {
      hidden++;
    }
  }
  open.sort((a, b) => (b.priority - a.priority)
    || ((a.due || '9999') < (b.due || '9999') ? -1 : (a.due || '9999') > (b.due || '9999') ? 1 : 0)
    || (b.created - a.created));
  done.sort((a, b) => b.doneAt - a.doneAt);

  let html = open.map(taskHtml).join('');
  if (done.length) {
    html += `<li class="section">${state.settings.showDone ? 'Completed' : 'Completed · hidden after 12 hours'}</li>`;
    html += done.map(taskHtml).join('');
  }
  $('list').innerHTML = html;

  let s = open.length + ' open';
  if (high) s += ' · ' + high + ' high priority';
  if (overdue) s += ' · ' + overdue + ' overdue';
  if (hidden) s += ' · ' + hidden + ' older completed hidden';
  $('summary').textContent = s;
  $('empty').textContent = 'Nothing to do here.\nType a task below and tap Add.';
  $('empty').hidden = open.length + done.length > 0;
}

function taskHtml(t) {
  let due;
  if (t.done) due = `<span class="due">Done ${esc(shortDate(new Date(t.doneAt)))}, ${esc(timeOf(t.doneAt))}</span>`;
  else if (t.due) due = `<button class="due${daysFromToday(t.due) < 0 ? ' overdue' : ''}" data-act="due">${esc(dueLabel(t.due))}</button>`;
  else due = '<button class="due add" data-act="due">+ Add due date</button>';
  const k = cat(t.category);
  const catHtml = k && t.category !== state.settings.selSub ? `<span class="cat" style="--c:${k.color}">${esc(catPath(k.id))}</span>` : '';
  return `<li class="task${t.done ? ' done' : ''}" data-id="${t.id}">
    <button class="check" data-act="check" aria-label="${t.done ? 'Mark not done' : 'Mark done'}"><span>${TICK}</span></button>
    <div class="body"><div class="title">${esc(t.title)}</div><div class="meta">${due}${catHtml}</div></div>
    <button class="prio" data-act="prio" style="--pc:var(--p${t.priority})" aria-label="Priority: ${PRIORITY[t.priority]}">
      ${t.priority ? FLAG : FLAG_OUTLINE}<span>${t.priority ? PRIORITY[t.priority] : ''}</span>
    </button>
  </li>`;
}

function renderLog() {
  const entries = [...state.log].sort((a, b) => b.ts - a.ts);
  let html = '', lastDay = '', todayCount = 0;
  const today = todayStr();
  for (const e of entries) {
    const day = ymd(new Date(e.ts));
    if (day !== lastDay) { html += `<li class="section">${esc(dayLabel(e.ts))}</li>`; lastDay = day; }
    if (day === today) todayCount++;
    html += `<li class="entry" data-entry="${e.id}"><time>${esc(timeOf(e.ts))}</time><div class="body">${esc(e.text)}</div></li>`;
  }
  $('list').innerHTML = html;
  $('summary').textContent = todayCount + ' logged today · ' + entries.length + ' total';
  $('empty').textContent = 'Nothing logged yet.\nNote down what you did below.';
  $('empty').hidden = entries.length > 0;
}

// ------------------------------------------------------------------ bottom sheets

function openSheet(html, mount) {
  const sheet = $('sheet');
  sheet.onclick = null;
  sheet.innerHTML = html;
  sheet.hidden = false;
  $('backdrop').hidden = false;
  if (mount) mount(sheet);
}
function closeSheet() {
  $('sheet').hidden = true;
  $('backdrop').hidden = true;
  $('sheet').innerHTML = '';
}
$('backdrop').addEventListener('click', closeSheet);

function toast(msg) {
  const t = $('toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.hidden = true; }, 2600);
}

function confirmSheet(title, message, okLabel, onOk) {
  openSheet(`<h3>${esc(title)}</h3>${message ? `<p class="note">${esc(message)}</p>` : ''}
    <div class="row"><button class="btn" data-x>Cancel</button><button class="btn primary" data-ok>${esc(okLabel)}</button></div>`, (s) => {
    s.querySelector('[data-x]').onclick = closeSheet;
    s.querySelector('[data-ok]').onclick = () => { closeSheet(); onOk(); };
  });
}

function textSheet(title, value, placeholder, okLabel, onOk) {
  openSheet(`<h3>${esc(title)}</h3><input type="text" id="ts-input" value="${esc(value)}" placeholder="${esc(placeholder)}" autocapitalize="sentences" enterkeyhint="done">
    <div class="row"><button class="btn" data-x>Cancel</button><button class="btn primary" data-ok>${esc(okLabel)}</button></div>`, (s) => {
    const inp = s.querySelector('#ts-input');
    const ok = () => { const v = inp.value.trim(); if (v) { closeSheet(); onOk(v); } };
    s.querySelector('[data-x]').onclick = closeSheet;
    s.querySelector('[data-ok]').onclick = ok;
    inp.onkeydown = (e) => { if (e.key === 'Enter') ok(); };
    inp.focus();
  });
}

/** Due-date picker with quick choices. Calls back with 'YYYY-MM-DD' or '' for none. */
function dueSheet(current, onPick) {
  const nextMon = (() => { const d = new Date(); d.setDate(d.getDate() + ((8 - d.getDay()) % 7 || 7)); return ymd(d); })();
  openSheet(`<h3>Due date</h3>
    <div class="quick">
      <button data-d="${todayStr()}">Today</button><button data-d="${addDays(1)}">Tomorrow</button>
      <button data-d="${nextMon}">Next Monday</button><button data-d="${addDays(7)}">In a week</button>
    </div>
    <label for="ds-date">Or pick a date</label><input type="date" id="ds-date" value="${current || ''}">
    <div class="row"><button class="btn danger" data-d="">No date</button><button class="btn primary" data-ok>Set</button></div>`, (s) => {
    s.querySelectorAll('[data-d]').forEach((b) => { b.onclick = () => { closeSheet(); onPick(b.dataset.d); }; });
    s.querySelector('[data-ok]').onclick = () => { const v = s.querySelector('#ds-date').value; closeSheet(); onPick(v || ''); };
  });
}

function editTaskSheet(t) {
  const opts = ['<option value="0">No category</option>']
    .concat(orderedCats().map((k) => `<option value="${k.id}"${k.id === t.category ? ' selected' : ''}>${k.parent ? '   ' + esc(catPath(k.id)) : esc(k.name)}</option>`))
    .join('');
  let prio = t.priority;
  openSheet(`<h3>Edit task</h3>
    <textarea id="et-title" rows="2">${esc(t.title)}</textarea>
    <label>Category</label><select id="et-cat">${opts}</select>
    <label>Priority</label><div class="seg" id="et-prio">${PRIORITY.map((p, i) => `<button data-p="${i}" style="--pc:var(--p${i})">${p}</button>`).join('')}</div>
    <label for="et-due">Due date</label><input type="date" id="et-due" value="${t.due || ''}">
    <div class="row"><button class="btn danger" data-del>Delete</button><button class="btn" data-x>Cancel</button><button class="btn primary" data-ok>Save</button></div>`, (s) => {
    const seg = s.querySelector('#et-prio');
    const paint = () => seg.querySelectorAll('button').forEach((b) => b.classList.toggle('on', +b.dataset.p === prio));
    seg.onclick = (e) => { const b = e.target.closest('button'); if (b) { prio = +b.dataset.p; paint(); } };
    paint();
    s.querySelector('[data-x]').onclick = closeSheet;
    s.querySelector('[data-del]').onclick = () => confirmSheet('Delete this task?', t.title, 'Delete', () => {
      state.tasks = state.tasks.filter((x) => x !== t); save(); render();
    });
    s.querySelector('[data-ok]').onclick = () => {
      t.title = s.querySelector('#et-title').value.trim() || t.title;
      t.category = +s.querySelector('#et-cat').value;
      t.priority = prio;
      t.due = s.querySelector('#et-due').value || '';
      save(); closeSheet(); render();
    };
  });
}

function editEntrySheet(e) {
  const d = new Date(e.ts);
  const local = ymd(d) + 'T' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  openSheet(`<h3>Edit entry</h3><textarea id="ee-text" rows="3">${esc(e.text)}</textarea>
    <label for="ee-time">When</label><input type="datetime-local" id="ee-time" value="${local}" style="width:100%;min-height:44px;border-radius:12px;border:1px solid var(--line);background:var(--bg);color:var(--text);font-size:16px;padding:10px 12px">
    <div class="row"><button class="btn danger" data-del>Delete</button><button class="btn" data-x>Cancel</button><button class="btn primary" data-ok>Save</button></div>`, (s) => {
    s.querySelector('[data-x]').onclick = closeSheet;
    s.querySelector('[data-del]').onclick = () => confirmSheet('Delete this entry?', e.text, 'Delete', () => {
      state.log = state.log.filter((x) => x !== e); save(); render();
    });
    s.querySelector('[data-ok]').onclick = () => {
      e.text = s.querySelector('#ee-text').value.trim() || e.text;
      const v = s.querySelector('#ee-time').value;
      if (v) e.ts = new Date(v).getTime();
      save(); closeSheet(); render();
    };
  });
}

function categorySheet(k) {
  openSheet(`<h3>${esc(catPath(k.id))}</h3>
    <button class="menu-item" data-a="rename">Rename</button>
    ${k.parent ? '' : '<button class="menu-item" data-a="sub">Add sub-category</button>'}
    <button class="menu-item danger" data-a="delete">Delete</button>`, (s) => {
    s.querySelector('[data-a=rename]').onclick = () => textSheet('Rename category', k.name, '', 'Save', (v) => { k.name = v; save(); render(); });
    const sub = s.querySelector('[data-a=sub]');
    if (sub) sub.onclick = () => newCategory(k.id);
    s.querySelector('[data-a=delete]').onclick = () => {
      const where = k.parent ? 'move to ' + cat(k.parent).name : 'will have no category';
      confirmSheet(`Delete “${k.name}”?`, (k.parent ? '' : 'Its sub-categories are deleted too. ') + 'Tasks are not deleted, they ' + where + '.', 'Delete', () => {
        deleteCategory(k); save(); render();
      });
    };
  });
}

function newCategory(parent) {
  textSheet(parent ? 'New sub-category in ' + cat(parent).name : 'New category', '', parent ? 'e.g. Customer A' : 'e.g. Family, Health', 'Add', (name) => {
    const k = addCategory(name, parent);
    state.settings.selTop = parent || k.id;
    state.settings.selSub = parent ? k.id : 0;
    save(); render();
  });
}

function menuSheet() {
  const s = state.settings;
  const sw = (on) => `<span class="switch${on ? ' on' : ''}"></span>`;
  const items = mode === 'tasks'
    ? `<button class="menu-item" data-a="done">Show all completed tasks ${sw(s.showDone)}</button>
       <button class="menu-item" data-a="cats">Manage categories</button>
       <button class="menu-item" data-a="log">Activity log</button>`
    : '';
  openSheet(`${items}
    <button class="menu-item" data-a="lock">Face ID / PIN lock ${sw(!!s.lock)}</button>
    <button class="menu-item" data-a="backup">Save backup file <small>.json</small></button>
    <button class="menu-item" data-a="restore">Restore from backup</button>
    <button class="menu-item" data-a="share">Share as text</button>
    ${mode === 'tasks' ? '<button class="menu-item danger" data-a="clear">Delete completed tasks</button>' : ''}
    <p class="note">Everything is stored only on this phone. Nothing is sent anywhere.</p>`, (sh) => {
    sh.onclick = (e) => {
      const b = e.target.closest('[data-a]');
      if (!b) return;
      const a = b.dataset.a;
      if (a === 'done') { s.showDone = !s.showDone; save(); closeSheet(); render(); }
      if (a === 'cats') manageCategoriesSheet();
      if (a === 'log') { closeSheet(); mode = 'log'; render(); }
      if (a === 'lock') { closeSheet(); s.lock ? disableLock() : enableLock(); }
      if (a === 'backup') { closeSheet(); saveBackup(); }
      if (a === 'restore') { closeSheet(); restoreBackup(); }
      if (a === 'share') { closeSheet(); shareText(); }
      if (a === 'clear') {
        confirmSheet('Delete all completed tasks?', 'This cannot be undone.', 'Delete', () => {
          const n = state.tasks.filter((t) => t.done).length;
          state.tasks = state.tasks.filter((t) => !t.done);
          save(); render(); toast('Deleted ' + n + ' task(s)');
        });
      }
    };
  });
}

function manageCategoriesSheet() {
  const rows = orderedCats().map((k) => `<button class="menu-item" data-k="${k.id}">
      <span style="color:${k.color};font-weight:600;${k.parent ? 'padding-left:18px' : ''}">● ${esc(k.name)}</span><small>Edit</small></button>`).join('');
  openSheet(`<h3>Categories</h3>${rows}<button class="menu-item" data-new>+ New category</button>
    <p class="note">Tip: press and hold a category chip to edit it.</p>`, (s) => {
    s.onclick = (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      if (b.dataset.k) categorySheet(cat(+b.dataset.k));
      else if ('new' in b.dataset) newCategory(0);
    };
  });
}

// ------------------------------------------------------------------ backup / share

function exportText() {
  let out = 'TO-DO\n';
  for (const g of [0, ...orderedCats().map((k) => k.id)]) {
    const part = state.tasks.filter((t) => t.category === g).map((t) => {
      let line = (t.done ? '[x] ' : '[ ] ') + t.title;
      if (t.priority) line += '  !' + PRIORITY[t.priority];
      if (t.due && !t.done) line += '  (' + dueLabel(t.due) + ')';
      return line;
    });
    if (part.length) out += '\n' + (g ? catPath(g) : 'No category') + '\n' + part.join('\n') + '\n';
  }
  if (state.log.length) {
    out += '\n\nACTIVITY LOG\n';
    let last = '';
    for (const e of [...state.log].sort((a, b) => b.ts - a.ts)) {
      const day = dayLabel(e.ts);
      if (day !== last) { out += '\n' + day + '\n'; last = day; }
      out += timeOf(e.ts) + '  ' + e.text + '\n';
    }
  }
  return out;
}

async function shareText() {
  const text = exportText();
  if (navigator.share) {
    try { await navigator.share({ title: APP_NAME + ' export', text }); return; } catch (e) { if (e.name === 'AbortError') return; }
  }
  try { await navigator.clipboard.writeText(text); toast('Copied to clipboard'); } catch (e) { toast('Sharing is not available here'); }
}

async function saveBackup() {
  const data = JSON.stringify({ app: APP_NAME, version: 1, exported: new Date().toISOString(), state: { ...state, settings: { ...state.settings, lock: null } } }, null, 1);
  const name = APP_NAME.toLowerCase() + '-backup-' + todayStr() + '.json';
  const file = new File([data], name, { type: 'application/json' });
  if (navigator.canShare && navigator.canShare({ files: [file] })) {
    try { await navigator.share({ files: [file], title: name }); return; } catch (e) { if (e.name === 'AbortError') return; }
  }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(file);
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 4000);
}

function restoreBackup() {
  const inp = document.createElement('input');
  inp.type = 'file';
  inp.accept = 'application/json,.json';
  inp.onchange = async () => {
    try {
      const parsed = JSON.parse(await inp.files[0].text());
      const s = parsed.state;
      if (!s || !Array.isArray(s.tasks) || !Array.isArray(s.categories)) throw new Error('bad file');
      confirmSheet('Replace all data with this backup?', `${s.tasks.length} tasks, ${s.categories.length} categories, ${(s.log || []).length} log entries.`, 'Restore', () => {
        const lock = state.settings.lock;
        state = { ...freshState(), ...s, log: s.log || [] };
        state.settings = { ...freshState().settings, ...s.settings, lock };
        save(); render(); toast('Backup restored');
      });
    } catch (e) {
      toast('That is not a valid backup file');
    }
  };
  inp.click();
}

// ------------------------------------------------------------------ app lock (PIN + Face ID)

const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

async function hashPin(pin, salt) {
  const data = new TextEncoder().encode(salt + ':' + pin);
  return b64(await crypto.subtle.digest('SHA-256', data));
}

async function faceIdAvailable() {
  try {
    return !!window.PublicKeyCredential && await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable();
  } catch (e) { return false; }
}

/** Registers a device passkey used purely as a local Face ID / Touch ID check. */
async function registerFaceId() {
  const cred = await navigator.credentials.create({
    publicKey: {
      challenge: crypto.getRandomValues(new Uint8Array(32)),
      rp: { name: APP_NAME },
      user: { id: crypto.getRandomValues(new Uint8Array(16)), name: APP_NAME + ' lock', displayName: APP_NAME + ' lock' },
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
      authenticatorSelection: { authenticatorAttachment: 'platform', userVerification: 'required', residentKey: 'discouraged' },
      attestation: 'none',
      timeout: 60000,
    },
  });
  return b64(cred.rawId);
}

async function checkFaceId(credId) {
  await navigator.credentials.get({
    publicKey: {
      challenge: crypto.getRandomValues(new Uint8Array(32)),
      allowCredentials: [{ type: 'public-key', id: unb64(credId), transports: ['internal', 'hybrid'] }],
      userVerification: 'required',
      timeout: 60000,
    },
  });
}

function enableLock() {
  pinSheet('Choose a 4-digit PIN', (pin1) => {
    pinSheet('Enter the PIN again', async (pin2) => {
      if (pin1 !== pin2) { toast('PINs did not match, try again'); return; }
      const salt = b64(crypto.getRandomValues(new Uint8Array(16)));
      state.settings.lock = { salt, hash: await hashPin(pin1, salt), cred: null };
      save();
      if (await faceIdAvailable()) {
        confirmSheet('Also unlock with Face ID?', 'You can always use your PIN instead.', 'Use Face ID', async () => {
          try {
            state.settings.lock.cred = await registerFaceId();
            save();
            toast('Lock on: Face ID or PIN');
          } catch (e) {
            toast('Face ID not set up. PIN lock is on.');
          }
        });
      } else {
        toast('PIN lock is on');
      }
    });
  });
}

function disableLock() {
  confirmSheet('Turn off the lock?', 'Anyone with your phone will be able to open the app.', 'Turn off', () => {
    state.settings.lock = null;
    save();
    toast('Lock turned off');
  });
}

/** A numeric PIN entry sheet (used when setting the PIN). */
function pinSheet(title, onDone) {
  openSheet(`<h3>${esc(title)}</h3><input type="password" id="pin-in" inputmode="numeric" pattern="[0-9]*" maxlength="8" autocomplete="off" placeholder="••••">
    <div class="row"><button class="btn" data-x>Cancel</button><button class="btn primary" data-ok>Next</button></div>`, (s) => {
    const inp = s.querySelector('#pin-in');
    const ok = () => {
      const v = inp.value.replace(/\D/g, '');
      if (v.length < 4) { toast('Use at least 4 digits'); return; }
      closeSheet();
      onDone(v);
    };
    s.querySelector('[data-x]').onclick = closeSheet;
    s.querySelector('[data-ok]').onclick = ok;
    inp.onkeydown = (e) => { if (e.key === 'Enter') ok(); };
    inp.focus();
  });
}

let locked = false;
let typed = '';

function lockNow() {
  if (!state.settings.lock || locked) return;
  locked = true;
  typed = '';
  closeSheet();
  document.activeElement && document.activeElement.blur && document.activeElement.blur();
  $('lock').hidden = false;
  $('faceid-btn').hidden = !state.settings.lock.cred;
  $('lock-msg').textContent = state.settings.lock.cred ? 'Use Face ID or enter your PIN' : 'Enter your PIN';
  paintDots();
}

function unlockNow() {
  locked = false;
  $('lock').hidden = true;
  render();
}

function paintDots() {
  const n = Math.max(4, typed.length);
  $('pin-dots').innerHTML = Array.from({ length: n }, (_, i) => `<span class="${i < typed.length ? 'on' : ''}"></span>`).join('');
}

function buildKeypad() {
  const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'ok', '0', 'del'];
  $('keypad').innerHTML = keys.map((k) => k === 'del'
    ? '<button class="small" data-k="del">Delete</button>'
    : k === 'ok' ? '<button class="small" data-k="ok">OK</button>' : `<button data-k="${k}">${k}</button>`).join('');
  $('keypad').onclick = async (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    const k = b.dataset.k;
    if (k === 'del') typed = typed.slice(0, -1);
    else if (k === 'ok') await tryPin(true);
    else if (typed.length < 8) { typed += k; await tryPin(false); }
    paintDots();
  };
}

/** Checks the typed PIN; auto-submits at 4+ digits when it matches, explicit OK reports a mismatch. */
async function tryPin(explicit) {
  const lock = state.settings.lock;
  if (!lock || typed.length < 4) return;
  if (await hashPin(typed, lock.salt) === lock.hash) { typed = ''; unlockNow(); return; }
  if (explicit || typed.length >= 8) {
    typed = '';
    $('lock-msg').textContent = 'Wrong PIN, try again';
    const dots = $('pin-dots');
    dots.classList.remove('shake'); void dots.offsetWidth; dots.classList.add('shake');
  }
}

$('faceid-btn').addEventListener('click', async () => {
  try {
    await checkFaceId(state.settings.lock.cred);
    unlockNow();
  } catch (e) {
    $('lock-msg').textContent = 'Face ID did not work. Enter your PIN.';
  }
});

document.addEventListener('visibilitychange', () => { if (document.hidden) lockNow(); });

// ------------------------------------------------------------------ interaction

/** Press-and-hold helper; the click that follows a long press is swallowed. */
function onLongPress(root, selector, handler) {
  let timer = null, startX = 0, startY = 0, fired = false;
  root.addEventListener('pointerdown', (e) => {
    const el = e.target.closest(selector);
    if (!el) return;
    fired = false;
    startX = e.clientX; startY = e.clientY;
    timer = setTimeout(() => { fired = true; timer = null; if (navigator.vibrate) navigator.vibrate(15); handler(el); }, 520);
  });
  const cancel = () => { clearTimeout(timer); timer = null; };
  root.addEventListener('pointerup', cancel);
  root.addEventListener('pointercancel', cancel);
  root.addEventListener('pointermove', (e) => { if (timer && Math.hypot(e.clientX - startX, e.clientY - startY) > 10) cancel(); });
  root.addEventListener('click', (e) => { if (fired && e.target.closest(selector)) { e.stopPropagation(); e.preventDefault(); fired = false; } }, true);
  root.addEventListener('contextmenu', (e) => { if (e.target.closest(selector)) e.preventDefault(); });
}

function onChipClick(e) {
  const b = e.target.closest('button');
  if (!b) return;
  if (b.dataset.new !== undefined) { newCategory(+b.dataset.new); return; }
  state.settings.selTop = +b.dataset.top;
  state.settings.selSub = b.dataset.sub ? +b.dataset.sub : 0;
  save();
  render();
}
$('chips-top').addEventListener('click', onChipClick);
$('chips-sub').addEventListener('click', onChipClick);
onLongPress($('chips-top'), '[data-cat]', (el) => categorySheet(cat(+el.dataset.cat)));
onLongPress($('chips-sub'), '[data-cat]', (el) => categorySheet(cat(+el.dataset.cat)));

$('list').addEventListener('click', (e) => {
  const entryEl = e.target.closest('[data-entry]');
  if (entryEl) { editEntrySheet(state.log.find((x) => x.id === +entryEl.dataset.entry)); return; }
  const li = e.target.closest('.task');
  if (!li) return;
  const t = state.tasks.find((x) => x.id === +li.dataset.id);
  const act = e.target.closest('[data-act]');
  const a = act && act.dataset.act;
  if (a === 'check') {
    t.done = !t.done;
    t.doneAt = t.done ? Date.now() : 0;
    save(); render();
  } else if (a === 'prio') {
    t.priority = (t.priority + 1) % 4;
    save(); render();
  } else if (a === 'due') {
    dueSheet(t.due, (d) => { t.due = d; save(); render(); });
  } else {
    editTaskSheet(t);
  }
});
onLongPress($('list'), '[data-act=prio]', (el) => {
  const t = state.tasks.find((x) => x.id === +el.closest('.task').dataset.id);
  openSheet(`<h3>Priority</h3>${PRIORITY.map((p, i) => `<button class="menu-item" data-p="${i}"><span style="color:var(--p${i});font-weight:700">${FLAG.replace('<svg', '<svg style="vertical-align:-6px;margin-right:8px"')}${p}</span>${t.priority === i ? '✓' : ''}</button>`).join('')}`, (s) => {
    s.onclick = (e) => { const b = e.target.closest('[data-p]'); if (b) { t.priority = +b.dataset.p; save(); closeSheet(); render(); } };
  });
});

$('addbar').addEventListener('submit', (e) => {
  e.preventDefault();
  const inp = $('input');
  const text = inp.value.trim();
  if (!text) return;
  if (mode === 'tasks') {
    state.tasks.push({
      id: newId(), title: text, done: false, doneAt: 0, due: pendingDue, created: Date.now(),
      category: state.settings.selSub || state.settings.selTop, priority: 0,
    });
    pendingDue = '';
  } else {
    state.log.push({ id: newId(), text, ts: Date.now() });
  }
  inp.value = '';
  save();
  render();
  document.querySelector('main').scrollTop = 0;
});

$('due-btn').addEventListener('click', () => dueSheet(pendingDue, (d) => { pendingDue = d; render(); }));
$('menu-btn').addEventListener('click', menuSheet);
$('back-btn').addEventListener('click', () => { mode = 'tasks'; render(); });

// Refresh relative dates and the 12-hour window when returning to the app.
window.addEventListener('focus', () => { if (!locked) render(); });

// ------------------------------------------------------------------ start

buildKeypad();
render();
lockNow();

if ('serviceWorker' in navigator && location.protocol === 'https:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
