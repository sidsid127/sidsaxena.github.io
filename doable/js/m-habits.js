// Habits to build (drink water 8×) and to quit (no sweets), streaks, a correctable 7-day history.
import * as S from './store.js';
import * as L from './logic.js';
import { app } from './ctx.js';
import { h, card, row, empty, pill, sheet, field, input, select, menu, confirm } from './ui.js';
import { habitLog, setHabitCount } from './m-today.js';

const LETTERS = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];
const IDEAS = [
  ['💧', 'Drink a glass of water', 0, 8, 'every:120:08:00-20:00'], ['🍬', 'No sweets', 1, 1, '20:00'], ['🚶', 'Walk 10,000 steps', 0, 1, '18:00'],
  ['📖', 'Read 20 minutes', 0, 1, '21:30'], ['🧘', 'Meditate', 0, 1, '07:30'], ['💊', 'Take vitamins', 0, 1, '08:00'], ['📵', 'No phone after 22:00', 1, 1, '21:45'],
];
let archived = false;

function render(view) {
  const page = h('div.page');
  const today = L.today();
  const list = S.where('habits', (x) => !!x.archived === archived);
  if (!list.length) {
    if (archived) page.appendChild(empty('No archived habits.'));
    else {
      page.appendChild(empty('Build good habits and break bad ones.\nTap + or start with an idea:'));
      page.appendChild(card(IDEAS.map(([emoji, name, quit, target, remind]) => row({ icon: emoji, title: name,
        sub: quit ? 'Quit · streak of days without' : 'Build' + (target > 1 ? ` · ${target}× a day` : ''),
        onclick: () => edit({ emoji, name, quit, target, remind, days: 127 }) }))));
    }
  }
  for (const x of list) {
    const log = habitLog(x.uid);
    const n = log[today] || 0;
    const [cur, best] = L.streaks(!!x.quit, x.target, x.days, log, today, x.created);
    const sub = x.quit ? `🔥 ${cur} day${cur === 1 ? '' : 's'} clean · best ${best}` : (x.target > 1 ? `${n} of ${x.target} today · ` : '') + `🔥 ${cur} · best ${best}`;
    let end = null;
    if (!archived) {
      end = x.quit ? pill(n ? 'Slipped' : 'Slip?', { on: !!n, onclick: () => setHabitCount(x.uid, today, n ? 0 : 1) })
        : pill(n >= x.target ? '✓' : x.target > 1 ? '+1' : 'Done', { on: n >= x.target, onclick: () => setHabitCount(x.uid, today, n + 1) });
      if (!x.quit) end.addEventListener('contextmenu', (e) => { e.preventDefault(); setHabitCount(x.uid, today, n - 1); });
    }
    const week = h('div.week');
    for (let i = 6; i >= 0; i--) {
      const d = L.addDays(today, -i);
      const sched = L.scheduled(x.days, d) && (!x.created || d >= x.created);
      const ok = L.habitSuccess(!!x.quit, x.target, log[d]);
      const mark = !sched ? '·' : ok ? '●' : d === today ? '○' : x.quit ? '✕' : '○';
      week.appendChild(h('button' + (sched && ok ? '.ok' : sched && x.quit && !ok ? '.bad' : ''), { type: 'button', disabled: !sched || archived,
        onclick: () => { const c = log[d] || 0; setHabitCount(x.uid, d, x.quit ? (c ? 0 : 1) : (c >= x.target ? 0 : x.target)); } },
      LETTERS[L.weekday(d)], h('br'), mark));
    }
    page.appendChild(card(row({ icon: x.emoji || (x.quit ? '🚫' : '✔'), title: x.name, sub: sub + (x.remind ? ' · 🔔' : ''), end,
      onclick: () => edit(x), onlongpress: () => actions(x) }), week));
  }
  view.appendChild(page);
  if (!archived) view.appendChild(h('button.fab', { type: 'button', 'aria-label': 'New habit', onclick: () => edit({ days: 127, target: 1 }) }, '+'));
}

function actions(x) {
  menu(x.name, [
    { label: 'Edit', action: () => edit(x) },
    { label: x.archived ? 'Restore' : 'Archive (keep history)', action: () => S.put('habits', { uid: x.uid, archived: x.archived ? 0 : 1 }) },
    { label: 'Delete', danger: true, action: async () => { if (await confirm('Delete "' + x.name + '"?', 'Its history is deleted too.', 'Delete')) S.remove('habits', x.uid); } },
  ]);
}

function edit(x) {
  const isNew = !x.uid;
  const emoji = input(x.emoji || '', { placeholder: '🙂', style: { width: '64px', flex: 'none', textAlign: 'center' } });
  const name = input(x.name || '', { placeholder: 'e.g. Drink a glass of water' });
  const kind = select([['0', 'Build (do it)'], ['1', 'Quit (don\'t do it)']], String(x.quit ? 1 : 0));
  const target = input(String(x.target || 1), { type: 'number', min: 1, max: 99 });
  const targetRow = field('Times per day', target);
  kind.addEventListener('change', () => { targetRow.hidden = kind.value === '1'; });
  targetRow.hidden = !!x.quit;
  let mask = x.days || 127;
  const days = h('div.days');
  for (const i of [1, 2, 3, 4, 5, 6, 0]) {
    const b = h('button' + (mask & (1 << i) ? '.on' : ''), { type: 'button', onclick: () => { mask ^= 1 << i; b.classList.toggle('on'); } }, LETTERS[i]);
    days.appendChild(b);
  }
  const spec = x.remind || '';
  const every = /^every:(\d+):(\d\d:\d\d)-(\d\d:\d\d)$/.exec(spec);
  const mode = select([['0', 'No reminder'], ['1', 'At set times'], ['2', 'Every few hours']], every ? '2' : spec ? '1' : '0');
  const times = input(every ? '' : spec.replace(/,/g, ', '), { placeholder: 'e.g. 08:00, 20:00' });
  const hours = input(every ? String(+every[1] / 60) : '2', { type: 'number', step: '0.5', style: { width: '70px', flex: 'none' } });
  const from = input(every ? every[2] : '08:00', { type: 'time' });
  const to = input(every ? every[3] : '20:00', { type: 'time' });
  const everyRow = h('div.form-inline', 'Every', hours, 'h from', from, 'to', to);
  const sync = () => { times.hidden = mode.value !== '1'; everyRow.hidden = mode.value !== '2'; };
  mode.addEventListener('change', sync);
  sync();
  const buttons = [{ label: 'Cancel' }, { label: 'Save', primary: true, action: () => {
    if (!name.value.trim()) return false;
    S.put('habits', { uid: x.uid, name: name.value.trim(), emoji: emoji.value.trim(), quit: +kind.value, target: Math.max(1, Math.min(99, +target.value || 1)),
      days: mask || 127, remind: L.reminderSpec(+mode.value, times.value, hours.value, from.value, to.value), archived: x.archived || 0,
      created: x.created || L.today() });
  } }];
  if (!isNew) buttons.unshift({ label: 'More…', action: () => actions(x) });
  sheet({ title: isNew ? 'New habit' : 'Edit habit', body: [field('Habit', h('div.form-inline', emoji, name)), field('Type', kind), targetRow,
    field('Days', days), field('Reminder (shown by the Android app)', mode), times, everyRow], buttons });
}

export default {
  id: 'habits',
  name: 'Habits',
  icon: 'habit',
  tables: ['habits', 'habit_log'],
  title: () => (archived ? 'Archived habits' : 'Habits'),
  render,
  isDeep: () => archived,
  back() { archived = false; app.refresh(); },
  open(uid) { const x = S.get('habits', uid); if (x) edit(x); },
  menu: () => [archived ? null : { label: 'Archived habits', action: () => { archived = true; app.refresh(); } }],
};
