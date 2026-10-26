'use strict';
/* Doable for iPhone — an offline web app. All data lives in this browser's storage on the phone. */

const APP_NAME = 'Doable';
const STORE_KEY = 'daybook.v1'; // kept from the app's first name so existing data keeps loading
const PALETTE = ['#3F2CB8', '#1E9C9A', '#E5484D', '#F2A33A', '#8E4EC6', '#3D8BFD', '#2F9E44', '#D6409F'];

const ICONS = {
  menu: '<svg viewBox="0 0 24 24"><path d="M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z"/></svg>',
  back: '<svg viewBox="0 0 24 24"><path d="M15.4 7.4 14 6l-6 6 6 6 1.4-1.4L10.8 12z"/></svg>',
  add: '<svg viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>',
  todo: '<svg viewBox="0 0 24 24"><path d="M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm-9 14-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>',
  note: '<svg viewBox="0 0 24 24"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"/></svg>',
  cart: '<svg viewBox="0 0 24 24"><path d="M7 18a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM1 2v2h2l3.6 7.59-1.35 2.45A2 2 0 0 0 7 17h12v-2H7.42a.25.25 0 0 1-.25-.25l.03-.12.9-1.63h7.45c.75 0 1.41-.41 1.75-1.03l3.58-6.49A1 1 0 0 0 20 4H5.21l-.94-2H1zm16 16a2 2 0 1 0 0 4 2 2 0 0 0 0-4z"/></svg>',
  key: '<svg viewBox="0 0 24 24"><path d="M12.65 10A6 6 0 1 0 7 18a6 6 0 0 0 5.65-4H17v4h4v-4h2v-4H12.65zM7 14a2 2 0 1 1 0-4 2 2 0 0 1 0 4z"/></svg>',
  history: '<svg viewBox="0 0 24 24"><path d="M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6a7 7 0 1 1 2.06 4.94l-1.42 1.42A9 9 0 1 0 13 3zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/></svg>',
  copy: '<svg viewBox="0 0 24 24"><path d="M16 1H4a2 2 0 0 0-2 2v14h2V3h12V1zm3 4H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm0 16H8V7h11v14z"/></svg>',
  tick: '<svg viewBox="0 0 24 24"><path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"/></svg>',
  flag: '<svg viewBox="0 0 24 24"><path d="M14.4 6 14 4H5v17h2v-7h5.6l.4 2h7V6z"/></svg>',
  flagOutline: '<svg viewBox="0 0 24 24"><path d="M12.36 6l.4 2H18v6h-3.36l-.4-2H7V6h5.36M14 4H5v17h2v-7h5.6l.4 2h7V6h-5.6L14 4z"/></svg>',
};

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

// ------------------------------------------------------------------ storage

function freshState() {
  return {
    nextId: 7,
    categories: [
      { id: 1, name: 'Private', parent: 0, color: PALETTE[1] },
      { id: 2, name: 'Work', parent: 0, color: PALETTE[0] },
      { id: 3, name: 'Accenture', parent: 2, color: PALETTE[4] },
    ],
    tasks: [],
    log: [],
    notes: [],
    shops: [
      { id: 4, name: 'Rewe', color: '#CC071E' },
      { id: 5, name: 'Lidl', color: '#0050AA' },
      { id: 6, name: 'Aldi', color: '#F28C00' },
    ],
    items: [],
    usuals: [],
    settings: { selTop: 0, selSub: 0, showDone: false, lock: null, theme: 'system', module: 'tasks', selShop: 0 },
  };
}

/** Fills in anything an older saved state doesn't have yet. */
function migrate(s) {
  const fresh = freshState();
  for (const k of ['notes', 'items', 'usuals', 'log']) if (!Array.isArray(s[k])) s[k] = [];
  if (!Array.isArray(s.shops)) {
    s.shops = fresh.shops.map((shop) => ({ ...shop, id: s.nextId++ }));
  }
  s.settings = { ...fresh.settings, ...s.settings };
  return s;
}

function load() {
  try {
    const s = JSON.parse(localStorage.getItem(STORE_KEY));
    if (s && Array.isArray(s.tasks)) return migrate(s);
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
  return Math.round((parseYmd(s) - parseYmd(todayStr())) / 86400000);
}
const shortDate = (d) => d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' });
const timeOf = (ms) => new Date(ms).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
const fullStamp = (ms) => shortDate(new Date(ms)) + ', ' + timeOf(ms);
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
/** "14:05" today, "Yesterday", weekday this week, else a short date — like the Notes list. */
function relativeStamp(ms) {
  const n = daysFromToday(ymd(new Date(ms)));
  if (n === 0) return timeOf(ms);
  if (n === -1) return 'Yesterday';
  if (n > -7) return new Date(ms).toLocaleDateString(undefined, { weekday: 'long' });
  return new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'numeric', year: '2-digit' });
}

// ------------------------------------------------------------------ sheets & toasts

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
  openSheet(`<h3>${esc(title)}</h3>${message ? `<p class="note-text">${esc(message)}</p>` : ''}
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

/** A list of choices; each item is {label, action, danger?, toggle?}. */
function menuSheet(title, items) {
  openSheet((title ? `<h3>${esc(title)}</h3>` : '') + items.map((it, i) => it.group
    ? `<div class="menu-group">${esc(it.group)}</div>`
    : `<button class="menu-item${it.danger ? ' danger' : ''}" data-i="${i}">${esc(it.label)}${it.toggle !== undefined ? `<span class="switch${it.toggle ? ' on' : ''}"></span>` : it.hint ? `<small>${esc(it.hint)}</small>` : ''}</button>`).join('')
    + (items.footer || ''), (s) => {
    s.onclick = (e) => {
      const b = e.target.closest('[data-i]');
      if (!b) return;
      const it = items[+b.dataset.i];
      if (!it.keepOpen) closeSheet();
      it.action();
    };
  });
}

// ------------------------------------------------------------------ press-and-hold

/** Long-press helper; the click that follows a long press is swallowed. */
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

function chipHtml(label, color, on, attrs) {
  return `<button class="chip${on ? ' on' : ''}" style="--c:${color}" ${attrs || ''}>${esc(label)}</button>`;
}

// ------------------------------------------------------------------ modules & navigation

/**
 * Each module: { id, name, icon, title(), render(), onShow?(), onHide?(), menu?() → items, isNested?(), back?() }.
 * Modules register themselves from their own files.
 */
const modules = [];
let current = null;

function registerModule(m) { modules.push(m); }

function showModule(id) {
  const next = modules.find((m) => m.id === id) || modules[0];
  if (current && current !== next && current.onHide) current.onHide();
  for (const m of modules) $('mod-' + m.id).hidden = m !== next;
  current = next;
  state.settings.module = next.id;
  save();
  closeDrawer();
  refreshTopbar();
  if (next.onShow) next.onShow();
  next.render();
}

function refreshTopbar() {
  $('title').textContent = current.title();
  const nested = current.isNested && current.isNested();
  $('nav-btn').innerHTML = nested ? ICONS.back : ICONS.menu;
  $('nav-btn').setAttribute('aria-label', nested ? 'Back' : 'Menu');
}

function openDrawer() {
  const items = modules.map((m, i) => (m.id === 'log' ? '<hr>' : '')
    + `<button class="drawer-item${m === current ? ' on' : ''}" data-m="${m.id}">${ICONS[m.icon]}${esc(m.name)}</button>`).join('');
  $('drawer').innerHTML = `<div class="drawer-head"><img src="icons/icon-192.png" alt="">${APP_NAME}</div>${items}`;
  $('drawer').hidden = false;
  $('drawer-scrim').hidden = false;
  if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
}
function closeDrawer() {
  $('drawer').hidden = true;
  $('drawer-scrim').hidden = true;
}
$('drawer').addEventListener('click', (e) => {
  const b = e.target.closest('[data-m]');
  if (b) showModule(b.dataset.m);
});
$('drawer-scrim').addEventListener('click', closeDrawer);
$('nav-btn').addEventListener('click', () => {
  if (current.isNested && current.isNested()) current.back();
  else openDrawer();
});

$('more-btn').addEventListener('click', () => {
  const s = state.settings;
  const own = current.menu ? current.menu() : [];
  const items = [...own,
    { group: 'App' },
    { label: 'Theme', hint: { system: 'Same as phone', light: 'Light', dark: 'Dark' }[s.theme], action: chooseTheme },
    { label: 'Face ID / PIN app lock', toggle: !!s.lock, action: () => (s.lock ? disableLock() : enableLock()) },
    { label: 'Save backup file', hint: '.json', action: saveBackup },
    { label: 'Restore from backup', action: restoreBackup },
  ];
  items.footer = '<p class="note-text">Everything is stored only on this phone. Nothing is sent anywhere.</p>';
  menuSheet('', items);
});

// ------------------------------------------------------------------ theme

const darkQuery = matchMedia('(prefers-color-scheme: dark)');
function applyTheme() {
  const t = state.settings.theme;
  const dark = t === 'dark' || (t === 'system' && darkQuery.matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
}
darkQuery.addEventListener('change', applyTheme);

function chooseTheme() {
  const pick = (t) => () => { state.settings.theme = t; save(); applyTheme(); };
  const cur = state.settings.theme;
  menuSheet('Theme', [
    { label: 'Same as phone' + (cur === 'system' ? '  ✓' : ''), action: pick('system') },
    { label: 'Light' + (cur === 'light' ? '  ✓' : ''), action: pick('light') },
    { label: 'Dark' + (cur === 'dark' ? '  ✓' : ''), action: pick('dark') },
  ]);
}

// ------------------------------------------------------------------ backup

async function saveBackup() {
  const data = JSON.stringify({
    app: APP_NAME, version: 2, exported: new Date().toISOString(),
    state: { ...state, settings: { ...state.settings, lock: null } },
    vault: localStorage.getItem(VAULT_KEY), // still encrypted with your master password
  }, null, 1);
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
      confirmSheet('Replace all data with this backup?',
        `${s.tasks.length} tasks, ${(s.notes || []).length} notes, ${(s.items || []).length} grocery items`
        + (parsed.vault ? ', encrypted passwords' : '') + '.', 'Restore', () => {
          const lock = state.settings.lock;
          state = migrate(s);
          state.settings.lock = lock;
          if (parsed.vault) { localStorage.setItem(VAULT_KEY, parsed.vault); vaultLock(); }
          save(); applyTheme(); showModule(state.settings.module); toast('Backup restored');
        });
    } catch (e) {
      toast('That is not a valid backup file');
    }
  };
  inp.click();
}

async function shareText(title, text) {
  if (navigator.share) {
    try { await navigator.share({ title, text }); return; } catch (e) { if (e.name === 'AbortError') return; }
  }
  try { await navigator.clipboard.writeText(text); toast('Copied to clipboard'); } catch (e) { toast('Sharing is not available here'); }
}

// ------------------------------------------------------------------ crypto helpers (app lock & passwords)

const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

async function hashPin(pin, salt) {
  return b64(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(salt + ':' + pin)));
}

async function faceIdAvailable() {
  try {
    return !!window.PublicKeyCredential && await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable();
  } catch (e) { return false; }
}

/** Creates a device passkey; used only as a local Face ID / Touch ID check. */
async function createPasskey(label, extensions) {
  const cred = await navigator.credentials.create({
    publicKey: {
      challenge: crypto.getRandomValues(new Uint8Array(32)),
      rp: { name: APP_NAME },
      user: { id: crypto.getRandomValues(new Uint8Array(16)), name: APP_NAME + ' ' + label, displayName: APP_NAME + ' ' + label },
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
      authenticatorSelection: { authenticatorAttachment: 'platform', userVerification: 'required', residentKey: 'preferred' },
      attestation: 'none',
      timeout: 60000,
      extensions,
    },
  });
  return cred;
}

async function usePasskey(credId, extensions) {
  return navigator.credentials.get({
    publicKey: {
      challenge: crypto.getRandomValues(new Uint8Array(32)),
      allowCredentials: [{ type: 'public-key', id: unb64(credId), transports: ['internal', 'hybrid'] }],
      userVerification: 'required',
      timeout: 60000,
      extensions,
    },
  });
}

// ------------------------------------------------------------------ app lock (PIN + Face ID)

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
            state.settings.lock.cred = b64((await createPasskey('lock')).rawId);
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
  confirmSheet('Turn off the app lock?', 'Anyone with your phone will be able to open the app.', 'Turn off', () => {
    state.settings.lock = null;
    save();
    toast('App lock turned off');
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
  closeDrawer();
  if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
  $('lock').hidden = false;
  $('faceid-btn').hidden = !state.settings.lock.cred;
  $('lock-msg').textContent = state.settings.lock.cred ? 'Use Face ID or enter your PIN' : 'Enter your PIN';
  paintDots();
}

function unlockNow() {
  locked = false;
  $('lock').hidden = true;
  if (current.onShow) current.onShow();
  current.render();
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
    await usePasskey(state.settings.lock.cred);
    unlockNow();
  } catch (e) {
    $('lock-msg').textContent = 'Face ID did not work. Enter your PIN.';
  }
});

document.addEventListener('visibilitychange', () => {
  if (!document.hidden) return;
  if (current && current.onBackground) current.onBackground();
  lockNow();
});
