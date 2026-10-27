// To-do: categories, priorities, due dates, reminders, repeats, subtasks, smart views, calendar, quick add.
import * as S from './store.js';
import * as L from './logic.js';
import { app } from './ctx.js';
import { h, icon, chip, menu, sheet, prompt, confirm, toast, header, card, empty, field, input, textarea, select, fmtDay, longPress } from './ui.js';

const PRIORITY = ['None', 'Low', 'Medium', 'High'];
const DONE_VISIBLE_MS = 12 * 3600 * 1000;
let calMonth = L.today().slice(0, 7);
let calDay = L.today();
let pendingDue = '';

const cats = () => S.all('categories');
const cat = (uid) => S.get('categories', uid);
function catPath(uid) {
  const k = cat(uid);
  if (!k) return '';
  const p = k.parent && cat(k.parent);
  return p ? p.name + ' › ' + k.name : k.name;
}
const catColor = (uid) => { const k = cat(uid); return k ? S.colorCss(k.color) : ''; };

/** Ticks a task; a repeating task gets its next occurrence (with its unticked subtasks). */
export function setDone(t, done) {
  if (done && t.repeat && t.due) {
    const next = L.nextOnOrAfter(t.due, t.repeat, L.today() > t.due ? L.today() : L.addDays(t.due, 1));
    const nu = S.put('tasks', { ...t, uid: undefined, due: next, done: 0, done_at: 0, snooze: 0, created: Date.now() });
    for (const s of S.where('subtasks', (x) => x.task === t.uid)) {
      S.put('subtasks', { task: nu, text: s.text, done: 0, position: s.position });
    }
    S.put('tasks', { uid: t.uid, done: 1, done_at: Date.now(), repeat: '' });
    toast('Next: ' + fmtDay(next));
    return;
  }
  S.put('tasks', { uid: t.uid, done: done ? 1 : 0, done_at: done ? Date.now() : 0 });
}

export function dueLabel(day) {
  const d = L.daysFromToday(day);
  if (d === 0) return 'Due today';
  if (d === 1) return 'Due tomorrow';
  if (d < 0) return 'Overdue · ' + fmtDay(day);
  return 'Due ' + fmtDay(day);
}

function matches(t) {
  const st = S.settings;
  const today = L.today();
  if (st.smart === 'today' && (!t.due || t.due > today)) return false;
  if (st.smart === 'upcoming' && (!t.due || t.due <= today || L.daysFromToday(t.due) > 7)) return false;
  if (st.smart === 'calendar' && t.due !== calDay) return false;
  if (st.smart) return true;
  if (st.selSub) return t.category === st.selSub;
  if (st.selTop) { const k = cat(t.category); return t.category === st.selTop || (k && k.parent === st.selTop); }
  return true;
}

const byImportance = (x, y) => (y.priority - x.priority) || (x.position - y.position)
  || ((x.due || '9999') < (y.due || '9999') ? -1 : (x.due || '9999') > (y.due || '9999') ? 1 : 0) || (y.created - x.created);

function taskRow(t, subCounts) {
  const today = L.today();
  const meta = [];
  if (t.due) meta.push(h('span' + (t.due < today && !t.done ? '.red' : ''), dueLabel(t.due)));
  else if (!t.done) meta.push(h('span.due-add', { onclick: (e) => { e.stopPropagation(); pickDue(t); } }, '+ Add due date'));
  if (t.time >= 0) meta.push(h('span', '🔔 ' + L.clock(t.time)));
  if (t.repeat) meta.push(h('span', '🔁 ' + L.ruleLabel(t.repeat)));
  const sc = subCounts[t.uid];
  if (sc) meta.push(h('span', `☑ ${sc[0]}/${sc[1]}`));
  if (t.notes) meta.push(h('span', '📝'));
  if (t.category && (S.settings.smart || !S.settings.selTop)) {
    meta.push(h('span.tint', { style: { '--c': catColor(t.category) } }, '● ' + catPath(t.category)));
  }
  const flag = h('button.flag.p' + t.priority, { type: 'button', 'aria-label': 'Priority ' + PRIORITY[t.priority], onclick: (e) => {
    e.stopPropagation();
    S.put('tasks', { uid: t.uid, priority: (t.priority + 1) % 4 });
  } }, icon('flag'));
  longPress(flag, () => menu('Priority', PRIORITY.map((p, i) => ({ label: p, checked: t.priority === i, action: () => S.put('tasks', { uid: t.uid, priority: i }) }))));
  return h('div.task' + (t.done ? '.done' : ''),
    h('button.tick' + (t.done ? '.done' : '.p' + t.priority), { type: 'button', 'aria-label': t.done ? 'Mark not done' : 'Mark done',
      onclick: () => setDone(t, !t.done) }, t.done ? '✓' : ''),
    h('div.task-body', { onclick: () => edit(t) }, h('div.task-title', t.title), meta.length ? h('div.task-meta', meta) : null),
    flag);
}

async function pickDue(t) {
  const d = await dueSheet(t.due);
  if (d !== undefined) S.put('tasks', { uid: t.uid, due: d, snooze: 0 });
}

/** Resolves with "YYYY-MM-DD", "" (no date) or undefined (cancelled). */
function dueSheet(current) {
  return new Promise((resolve) => {
    const today = L.today();
    const inp = input(current || today, { type: 'date' });
    let result;
    const pick = (d) => { result = d; s.close(); };
    const s = sheet({
      title: 'Due date',
      body: [h('div.chips.wrap', chip('Today', { onclick: () => pick(today) }), chip('Tomorrow', { onclick: () => pick(L.addDays(today, 1)) }),
        chip('Next week', { onclick: () => pick(L.nextWeekday(today, 1, false)) }), chip('No date', { onclick: () => pick('') })), inp],
      buttons: [{ label: 'Cancel' }, { label: 'Set', primary: true, action: () => { result = inp.value || ''; } }],
      onClose: () => resolve(result),
    });
  });
}

function edit(t) {
  const isNew = !t.uid;
  const uid = t.uid || S.newUid();
  const title = textarea(t.title, { rows: 2 });
  const due = input(t.due || '', { type: 'date' });
  const time = input(t.time >= 0 ? L.clock(t.time) : '', { type: 'time' });
  const repeat = select(L.RULES.map((r, i) => [r, L.RULE_LABELS[i]]).concat(L.RULES.includes(t.repeat) ? [] : [[t.repeat, L.ruleLabel(t.repeat)]]), t.repeat || '');
  const prio = select(PRIORITY.map((p, i) => [String(i), p]), String(t.priority || 0));
  const catSel = select([['', 'No category'], ...cats().filter((k) => !k.parent).flatMap((k) => [[k.uid, k.name],
    ...cats().filter((s) => s.parent === k.uid).map((s) => [s.uid, '   ' + k.name + ' › ' + s.name])])], t.category || '');
  const notes = textarea(t.notes, { rows: 3, placeholder: 'Notes, links, details…' });
  const subs = S.where('subtasks', (s) => s.task === uid).sort((a, b) => a.position - b.position).map((s) => ({ ...s }));
  const subBox = h('div');
  const renderSubs = () => {
    subBox.textContent = '';
    subs.forEach((s, i) => {
      const cb = h('input', { type: 'checkbox', checked: !!s.done, onchange: () => { s.done = cb.checked ? 1 : 0; } });
      const tx = input(s.text, { oninput: (e) => { s.text = e.target.value; } });
      subBox.appendChild(h('div.form-inline', { style: { margin: '4px 0' } }, h('span', { style: { flex: 'none' } }, cb), tx,
        h('button.btn', { type: 'button', style: { flex: 'none' }, onclick: () => { subs.splice(i, 1); renderSubs(); } }, '✕')));
    });
    const add = input('', { placeholder: '+ Add a subtask' });
    const take = (refocus) => {
      const v = add.value.trim();
      if (!v) return;
      add.value = '';
      subs.push({ text: v, done: 0 });
      renderSubs();
      if (refocus) subBox.lastChild.focus();
    };
    add.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); take(true); } });
    add.addEventListener('blur', () => take(false)); // typed but didn't press Enter
    subBox.appendChild(add);
  };
  renderSubs();
  const save = () => {
    const tt = title.value.trim();
    if (!tt) return false;
    const [hh, mm] = (time.value || '').split(':').map(Number);
    const newTime = time.value ? hh * 60 + mm : -1;
    const changedWhen = due.value !== (t.due || '') || newTime !== t.time;
    S.put('tasks', { uid, title: tt, due: due.value || '', time: newTime, repeat: due.value ? repeat.value : '', priority: +prio.value,
      category: catSel.value, notes: notes.value.trim(), snooze: changedWhen ? 0 : t.snooze || 0, created: t.created || Date.now(),
      done: t.done || 0, done_at: t.done_at || 0, position: t.position || 0 });
    const keep = new Set();
    subs.forEach((s, i) => { if (s.text.trim()) keep.add(S.put('subtasks', { uid: s.uid, task: uid, text: s.text.trim(), done: s.done, position: i })); });
    for (const s of S.where('subtasks', (x) => x.task === uid)) if (!keep.has(s.uid)) S.del('subtasks', s.uid);
    if (newTime >= 0 && 'Notification' in window && Notification.permission === 'default') Notification.requestPermission();
  };
  const buttons = [{ label: 'Cancel' }];
  if (!isNew) buttons.unshift({ label: 'Delete', danger: true, action: async () => { if (await confirm('Delete this task?', t.title, 'Delete')) S.remove('tasks', uid); } });
  buttons.push({ label: 'Save', primary: true, action: save });
  sheet({
    title: isNew ? 'New task' : 'Edit task',
    body: [field('Task', title), h('div.form-inline', field('Due', due), field('Reminder', time)), field('Repeat', repeat),
      h('div.form-inline', field('Priority', prio), field('Category', catSel)), field('Subtasks', subBox), field('Notes', notes),
      h('p.kbd-hint', 'Reminders pop up while Doable is open (on iPhone, use the Android app or set an alarm for important ones).')],
    buttons,
  });
}

// ------------------------------------------------------------------ categories

async function newCategory(parent) {
  const name = await prompt(parent ? 'New sub-category' : 'New category', '', { placeholder: 'e.g. Customer A' });
  if (!name) return;
  const n = cats().length;
  S.put('categories', { name, parent: parent || '', color: S.colorInt(S.PALETTE[(n + 2) % S.PALETTE.length]) });
}
function categoryMenu(k) {
  menu(k.name, [
    { label: 'Rename', action: async () => { const n = await prompt('Rename', k.name); if (n) S.put('categories', { uid: k.uid, name: n }); } },
    { label: 'Colour', action: () => menu('Colour', S.PALETTE.map((c) => ({ label: '● ' + c, action: () => S.put('categories', { uid: k.uid, color: S.colorInt(c) }) }))) },
    !k.parent ? { label: 'Add sub-category', action: () => newCategory(k.uid) } : null,
    { label: 'Delete (tasks keep, without category)', danger: true, action: async () => {
      if (!await confirm('Delete "' + k.name + '"?', 'Its tasks stay, without a category.', 'Delete')) return;
      for (const s of cats().filter((x) => x.parent === k.uid)) S.del('categories', s.uid);
      for (const t of S.where('tasks', (x) => x.category === k.uid || cats().some((s) => s.parent === k.uid && s.uid === x.category))) S.put('tasks', { uid: t.uid, category: '' });
      S.del('categories', k.uid);
      if (S.settings.selTop === k.uid) { S.settings.selTop = ''; S.settings.selSub = ''; S.saveSettings(); }
    } },
  ]);
}

// ------------------------------------------------------------------ calendar

function calendar() {
  const [y, m] = calMonth.split('-').map(Number);
  const first = new Date(y, m - 1, 1);
  const start = L.addDays(L.dayOf(first), -((first.getDay() + 6) % 7)); // Monday first
  const dueDays = new Set(S.where('tasks', (t) => !t.done && t.due).map((t) => t.due));
  const grid = h('div.cal', ['M', 'T', 'W', 'T', 'F', 'S', 'S'].map((d) => h('div.cal-head', d)));
  for (let i = 0; i < 42; i++) {
    const d = L.addDays(start, i);
    const cls = (d.slice(0, 7) !== calMonth ? '.other' : '') + (d === L.today() ? '.today' : '') + (d === calDay ? '.sel' : '');
    grid.appendChild(h('button.cal-day' + cls, { type: 'button', onclick: () => { calDay = d; pendingDue = d; app.refresh(); } },
      String(+d.slice(8)), dueDays.has(d) ? h('span.cal-dot') : null));
  }
  const shift = (n) => { calMonth = L.addMonths(calMonth + '-01', n).slice(0, 7); app.refresh(); };
  return h('div.card', h('div.cal-nav', h('button.btn', { type: 'button', onclick: () => shift(-1) }, '‹'),
    first.toLocaleDateString(undefined, { month: 'long', year: 'numeric' }), h('button.btn', { type: 'button', onclick: () => shift(1) }, '›')), grid);
}

// ------------------------------------------------------------------ screen

function render(view) {
  const st = S.settings;
  const page = h('div.page.flush');
  const chips = h('div.chips');
  const sel = (top, sub, smart) => { st.selTop = top; st.selSub = sub; st.smart = smart; S.saveSettings(); app.refresh(); };
  chips.appendChild(chip('All', { selected: !st.selTop && !st.smart, onclick: () => sel('', '', '') }));
  for (const [id, label] of [['today', 'Today'], ['upcoming', 'Upcoming'], ['calendar', '📅 Calendar']]) {
    chips.appendChild(chip(label, { selected: st.smart === id, color: '#1E9C9A', onclick: () => sel('', '', st.smart === id ? '' : id) }));
  }
  const tops = cats().filter((k) => !k.parent).sort((a, b) => a.name.localeCompare(b.name));
  for (const k of tops) {
    chips.appendChild(chip(k.name, { selected: st.selTop === k.uid && !st.smart, color: S.colorCss(k.color),
      onclick: () => sel(st.selTop === k.uid && !st.selSub ? '' : k.uid, '', ''), onlongpress: () => categoryMenu(k) }));
  }
  chips.appendChild(chip('+', { onclick: () => newCategory('') }));
  page.appendChild(chips);
  if (st.selTop && !st.smart) {
    const subsRow = h('div.chips');
    for (const k of cats().filter((x) => x.parent === st.selTop)) {
      subsRow.appendChild(chip(k.name, { selected: st.selSub === k.uid, color: S.colorCss(k.color), onclick: () => sel(st.selTop, st.selSub === k.uid ? '' : k.uid, ''),
        onlongpress: () => categoryMenu(k) }));
    }
    subsRow.appendChild(chip('+ Sub-category', { onclick: () => newCategory(st.selTop) }));
    page.appendChild(subsRow);
  }
  if (st.smart === 'calendar') page.appendChild(calendar());

  const now = Date.now();
  const today = L.today();
  const open = [], done = [];
  let hidden = 0;
  for (const t of S.all('tasks')) {
    if (!matches(t)) continue;
    if (!t.done) open.push(t);
    else if (st.showDone || now - t.done_at < DONE_VISIBLE_MS) done.push(t);
    else hidden++;
  }
  open.sort(byImportance);
  done.sort((a, b) => b.done_at - a.done_at);
  const subCounts = {};
  for (const s of S.all('subtasks')) { const c = subCounts[s.task] || (subCounts[s.task] = [0, 0]); c[1]++; if (s.done) c[0]++; }

  const overdue = open.filter((t) => t.due && t.due < today);
  const high = open.filter((t) => t.priority === 3).length;
  page.appendChild(h('div.stat', [st.smart === 'today' ? 'Today and overdue · ' : st.smart === 'upcoming' ? 'Next 7 days · ' : st.smart === 'calendar' ? fmtDay(calDay) + ' · ' : '',
    open.length + ' open', high ? ' · ' + high + ' high priority' : '', overdue.length ? ' · ' + overdue.length + ' overdue' : '',
    hidden ? ' · ' + hidden + ' older completed hidden' : ''].join('')));

  const section = (title, list, cls) => {
    if (!list.length) return;
    if (title) page.appendChild(h('div.section' + (cls ? '.' + cls : ''), title));
    page.appendChild(card(list.map((t) => taskRow(t, subCounts))));
  };
  if (!open.length && !done.length) page.appendChild(empty(st.smart === 'today' ? 'Nothing due today 🎉' : 'Nothing to do here.\nType a task below.'));
  let rest = open;
  if (st.smart === 'today' && overdue.length) {
    section('⏰ Still open from earlier days', [...overdue].sort((a, b) => (a.due < b.due ? -1 : 1)), 'red');
    rest = open.filter((t) => !overdue.includes(t));
    if (rest.length) section('Today', rest);
  } else {
    const groups = new Map();
    for (const t of rest) {
      const key = cat(t.category) ? t.category : '';
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(t);
    }
    if (groups.size > 1) {
      section('⚑ High priority', rest.filter((t) => t.priority === 3), 'red');
      const keys = [...groups.keys()].sort((a, b) => catPath(a).localeCompare(catPath(b)));
      for (const k of keys) section(k ? '● ' + catPath(k) : 'No category', groups.get(k).filter((t) => t.priority !== 3));
    } else section('', rest);
  }
  section(st.showDone ? 'Completed' : 'Completed · hidden after 12 hours', done);
  view.appendChild(page);
  view.appendChild(inputBar());
}

function inputBar() {
  const st = S.settings;
  const inp = h('input.field#task-input', { type: 'text', placeholder: 'Add a task… e.g. "Call bank tomorrow 9am !high"', enterkeyhint: 'done' });
  const hint = h('div.hint');
  const update = () => {
    const q = L.quickAdd(inp.value);
    const bits = [];
    if (q.due) bits.push('📅 ' + fmtDay(q.due));
    if (q.time >= 0) bits.push('🔔 ' + L.clock(q.time));
    if (q.repeat) bits.push('🔁 ' + L.ruleLabel(q.repeat));
    if (q.priority >= 0) bits.push('⚑ ' + PRIORITY[q.priority]);
    if (q.category) bits.push('● ' + (findCategory(q.category) ? catPath(findCategory(q.category).uid) : q.category + ' (new)'));
    hint.textContent = inp.value.trim() && bits.length ? q.title + '  ·  ' + bits.join('  ·  ') : '';
  };
  inp.addEventListener('input', update);
  const add = () => {
    const text = inp.value.trim();
    if (!text) return;
    const q = L.quickAdd(text);
    let category = st.selSub || st.selTop || '';
    if (q.category) {
      const k = findCategory(q.category);
      category = k ? k.uid : S.put('categories', { name: q.category, color: S.colorInt(S.PALETTE[cats().length % S.PALETTE.length]) });
    }
    const due = q.due || pendingDue || (st.smart === 'calendar' ? calDay : st.smart === 'today' ? L.today() : '');
    S.put('tasks', { title: q.title, due, time: q.time, repeat: q.repeat, priority: Math.max(0, q.priority), category, created: Date.now() });
    if (q.time >= 0 && 'Notification' in window && Notification.permission === 'default') Notification.requestPermission();
    pendingDue = '';
    inp.value = '';
    hint.textContent = '';
    setTimeout(() => { const i = document.getElementById('task-input'); if (i) i.focus(); }, 30);
  };
  inp.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.isComposing) add(); });
  return h('div', { style: { position: 'sticky', bottom: 0, zIndex: 5 } }, h('div', { style: { background: 'var(--card)' } }, hint),
    h('div.inputbar', inp, h('button.btn.primary', { type: 'button', onclick: add }, 'Add')));
}

function findCategory(name) {
  const n = name.toLowerCase();
  return cats().find((k) => k.name.toLowerCase() === n) || cats().find((k) => k.name.toLowerCase().startsWith(n));
}

export default {
  id: 'tasks',
  name: 'To-do',
  icon: 'todo',
  tables: ['tasks', 'subtasks', 'categories'],
  get busy() { const i = document.getElementById('task-input'); return !!(i && document.activeElement === i && i.value); },
  render,
  open(uid) { const t = S.get('tasks', uid); if (t) edit(t); },
  quick() { setTimeout(() => { const i = document.getElementById('task-input'); if (i) i.focus(); }, 50); },
  prefill(text) { const i = document.getElementById('task-input'); if (i) { i.value = text; i.dispatchEvent(new Event('input')); i.focus(); } },
  menu: () => [
    { label: S.settings.showDone ? 'Hide older completed tasks' : 'Show all completed tasks', action: () => { S.settings.showDone = !S.settings.showDone; S.saveSettings(); app.refresh(); } },
    { label: 'New task (form)…', action: () => edit({ title: '', due: S.settings.smart === 'today' ? L.today() : '', time: -1, repeat: '', priority: 0,
      category: S.settings.selSub || S.settings.selTop || '', notes: '' }) },
    { label: 'New category', action: () => newCategory('') },
    { label: 'Clear completed tasks', danger: true, action: async () => {
      const done = S.where('tasks', (t) => t.done);
      if (done.length && await confirm('Delete ' + done.length + ' completed task(s)?', null, 'Delete')) done.forEach((t) => S.remove('tasks', t.uid));
    } },
  ],
};
