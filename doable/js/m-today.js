// Today: what's left over from earlier days, tasks due, habits, dates, diary, current trip, shopping list.
import * as S from './store.js';
import * as L from './logic.js';
import { app } from './ctx.js';
import { h, header, card, row, pill, menu, toast, fmtDay, sheet } from './ui.js';
import { setDone } from './m-tasks.js';
import { nextOccurrence, dateEmoji, dateWhen, allDates } from './m-dates.js';
import { currentTrip } from './m-trips.js';

const clockMin = (t) => (t.time >= 0 ? t.time : 9999);

function taskRows(c, list, today, max) {
  list.slice(0, max).forEach((t) => {
    const late = -L.daysFromToday(t.due);
    const bits = [];
    if (late === 1) bits.push('From yesterday');
    else if (late > 1) bits.push(late + ' days overdue · ' + fmtDay(t.due));
    if (t.time >= 0) bits.push('🔔 ' + L.clock(t.time));
    if (t.repeat) bits.push('🔁');
    c.appendChild(row({
      icon: t.priority === 3 ? '⚑' : t.priority === 2 ? '◆' : '○', title: t.title, sub: bits.join(' · '), subClass: late > 0 ? 'red' : '',
      end: pill('✓', { onclick: () => setDone(t, true) }),
      onclick: () => app.go('tasks', t.uid),
      onlongpress: late > 0 ? () => menu(t.title, [
        { label: 'Move to today', action: () => S.put('tasks', { uid: t.uid, due: today, snooze: 0 }) },
        { label: 'Move to tomorrow', action: () => S.put('tasks', { uid: t.uid, due: L.addDays(today, 1), snooze: 0 }) },
        { label: 'Move to next week', action: () => S.put('tasks', { uid: t.uid, due: L.addDays(today, 7), snooze: 0 }) },
        { label: 'Open', action: () => app.go('tasks', t.uid) },
      ]) : null,
    }));
  });
  if (list.length > max) c.appendChild(h('button.link-btn', { type: 'button', onclick: () => app.go('tasks') }, `Show all ${list.length} in To-do`));
}

function render(view) {
  const today = L.today();
  const page = h('div.page');
  const hour = new Date().getHours();
  page.appendChild(h('div.hello', hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening',
    h('small', new Date().toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' }))));
  page.appendChild(h('div.chips',
    [['+ Task', 'tasks', 'task'], ['+ Note', 'notes', 'note'], ['+ Item', 'grocery', 'grocery'], ['+ Expense', 'trips', 'expense']]
      .filter(([, m]) => app.isVisible(m)).map(([l, m, q]) => pill(l, { onclick: () => app.go(m, null, q) }))));

  if (app.isVisible('tasks')) {
    const open = S.where('tasks', (t) => !t.done && t.due && t.due <= today);
    const earlier = open.filter((t) => t.due < today).sort((a, b) => (a.due < b.due ? -1 : a.due > b.due ? 1 : b.priority - a.priority));
    const due = open.filter((t) => t.due === today).sort((a, b) => (b.priority - a.priority) || (clockMin(a) - clockMin(b)));
    if (earlier.length) {
      page.appendChild(header('⏰ Still open from earlier days · ' + earlier.length, 'red'));
      const c = card();
      taskRows(c, earlier, today, 15);
      if (earlier.length > 1) c.appendChild(h('button.link-btn', { type: 'button', onclick: () => {
        earlier.forEach((t) => S.put('tasks', { uid: t.uid, due: today, snooze: 0 }));
        toast(earlier.length + ' tasks moved to today');
      } }, `Move all ${earlier.length} to today`));
      page.appendChild(c);
    }
    page.appendChild(header('To-do today' + (due.length ? ' · ' + due.length : '')));
    const c = card();
    if (due.length) taskRows(c, due, today, 10);
    else c.appendChild(row({ icon: '🎉', title: 'Nothing else due today', sub: 'Tap to see all tasks', onclick: () => app.go('tasks') }));
    page.appendChild(c);
  }

  if (app.isVisible('habits')) {
    const habits = S.where('habits', (x) => !x.archived);
    const todays = habits.filter((x) => L.scheduled(x.days, today));
    if (todays.length) {
      page.appendChild(header('Habits'));
      const c = card();
      for (const x of todays) {
        const log = habitLog(x.uid);
        const n = log[today] || 0;
        const [cur] = L.streaks(!!x.quit, x.target, x.days, log, today, x.created);
        const sub = (x.quit ? (n ? 'Slipped today' : 'On track today') : x.target > 1 ? `${n} of ${x.target} today` : n ? 'Done today' : 'Not done yet')
          + (cur ? ' · 🔥 ' + cur : '');
        c.appendChild(row({ icon: x.emoji || (x.quit ? '🚫' : '✔'), title: x.name, sub,
          end: x.quit ? null : pill(n >= x.target ? '✓' : '+1', { on: n >= x.target, onclick: () => setHabitCount(x.uid, today, n + 1) }),
          onclick: () => app.go('habits', x.uid) }));
      }
      const y = L.addDays(today, -1);
      const missed = habits.filter((x) => !x.quit && (!x.created || x.created <= y) && L.scheduled(x.days, y) && (habitLog(x.uid)[y] || 0) < x.target);
      if (missed.length) {
        c.appendChild(row({ icon: '↩', title: 'Yesterday not logged', sub: missed.map((x) => x.name).join(', ') + ' · tap if you did them',
          onclick: () => {
            const boxes = missed.map((x) => { const cb = h('input', { type: 'checkbox' }); return { x, cb, el: h('label.check-row', cb, x.name) }; });
            sheet({ title: 'Done yesterday?', body: boxes.map((b) => b.el), buttons: [{ label: 'Cancel' }, { label: 'Save', primary: true,
              action: () => boxes.filter((b) => b.cb.checked).forEach((b) => setHabitCount(b.x.uid, y, b.x.target)) }] });
          } }));
      }
      page.appendChild(c);
    }
  }

  if (app.isVisible('dates')) {
    const soon = allDates().map((d) => ({ d, occ: nextOccurrence(d, today) })).filter(({ d, occ }) => {
      const days = L.daysBetween(today, occ);
      return (days >= 0 && days <= 7) || (days < 0 && (d.fromWallet || days >= -7));
    }).sort((a, b) => (a.occ < b.occ ? -1 : 1));
    if (soon.length) {
      page.appendChild(header('Dates'));
      page.appendChild(card(soon.map(({ d, occ }) => row({ icon: dateEmoji(d), title: d.title, sub: dateWhen(d, occ), subClass: occ < today ? 'red' : '',
        onclick: () => (d.fromWallet ? app.go('wallet', d.walletUid) : app.go('dates', d.uid)) }))));
    }
  }

  if (app.isVisible('diary')) {
    page.appendChild(header('My Diary'));
    const c = card();
    const d = S.get('diary', 'diary:' + today);
    if (!d || !(d.text || d.highs || d.lows || d.mood)) c.appendChild(row({ icon: '📝', title: 'How was your day?', sub: 'Write today\'s entry', onclick: () => app.go('diary', null, 'today') }));
    else c.appendChild(row({ icon: ['📖', '😞', '🙁', '😐', '🙂', '😄'][d.mood || 0], title: 'Today\'s entry', sub: d.text.slice(0, 80), onclick: () => app.go('diary', d.uid) }));
    const y = L.addDays(today, -1);
    const yd = S.get('diary', 'diary:' + y);
    if ((!yd || !(yd.text || yd.highs || yd.lows || yd.mood)) && S.all('diary').length) {
      c.appendChild(row({ icon: '↩', title: 'Yesterday has no entry', sub: 'Write it while you still remember', onclick: () => app.go('diary', 'diary:' + y) }));
    }
    page.appendChild(c);
  }

  if (app.isVisible('trips')) {
    const t = currentTrip();
    if (t && t.start) {
      const days = L.daysFromToday(t.start);
      const now = t.start <= today && (!t.finish || t.finish >= today);
      if (now || (days >= 0 && days <= 30)) {
        page.appendChild(header('Trip'));
        page.appendChild(card(row({ icon: t.kind === 0 ? '💼' : '🧳', title: t.name,
          sub: (now ? 'Happening now' : days === 1 ? 'Starts tomorrow' : `Starts in ${days} days`) + (t.destination ? ' · ' + t.destination : ''),
          onclick: () => app.go('trips', t.uid) })));
      }
    }
  }

  if (app.isVisible('grocery')) {
    const n = S.where('grocery', (i) => !i.done).length;
    if (n) {
      page.appendChild(header('Shopping'));
      page.appendChild(card(row({ icon: '🛒', title: `${n} item${n === 1 ? '' : 's'} to buy`, sub: 'Open the shopping list', onclick: () => app.go('grocery') })));
    }
  }
  view.appendChild(page);
}

export function habitLog(habitUid) {
  const out = {};
  for (const l of S.where('habit_log', (x) => x.habit === habitUid)) out[l.day] = l.count;
  return out;
}
export function setHabitCount(habitUid, day, count) {
  const uid = 'hl:' + habitUid + ':' + day;
  if (count <= 0) S.del('habit_log', uid);
  else S.put('habit_log', { uid, habit: habitUid, day, count });
}

export default {
  id: 'today',
  name: 'Today',
  icon: 'today',
  render,
};
