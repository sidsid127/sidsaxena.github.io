// Important dates: birthdays (with age), anniversaries, renewals, expiries; wallet expiries show up too.
import * as S from './store.js';
import * as L from './logic.js';
import { app } from './ctx.js';
import * as SP from './spaces.js';
import { h, header, card, row, empty, sheet, field, input, select, textarea, confirm, fmtDay, menu } from './ui.js';

export const DATE_KINDS = ['Birthday', 'Anniversary', 'Name day', 'Contract renewal', 'Expiry', 'Appointment', 'Other'];
const REMIND = [[-1, 'No reminder'], [0, 'On the day'], [1, '1 day before'], [3, '3 days before'], [7, '1 week before'], [14, '2 weeks before'], [30, '1 month before']];

/** Dates plus wallet expiries (read-only, fromWallet). */
export function allDates() {
  const out = S.all('dates');
  for (const w of S.all('wallet')) {
    if (!w.expiry) continue;
    const [y, m, d] = w.expiry.split('-').map(Number);
    out.push({ uid: 'w:' + w.uid, title: w.title + ' expires', kind: 'Expiry', year: y, month: m, day: d, yearly: 0, remind_days: 30,
      fromWallet: true, walletUid: w.uid });
  }
  return out;
}

export function nextOccurrence(d, today) {
  const y = +today.slice(0, 4);
  if (!d.yearly) return L.dateOf(d.year > 0 ? d.year : y, d.month, d.day);
  let t = L.dateOf(y, d.month, d.day);
  if (t < today) t = L.dateOf(y + 1, d.month, d.day);
  return t;
}

export function dateEmoji(d) {
  if (d.fromWallet) return '🪪';
  return { Birthday: '🎂', Anniversary: '💍', 'Name day': '🎉', 'Contract renewal': '📑', Expiry: '⏳', Appointment: '🗓' }[d.kind] || '📅';
}

export function dateWhen(d, occ) {
  const days = L.daysFromToday(occ);
  let s = days === 0 ? 'Today' : days === 1 ? 'Tomorrow' : days < 0 ? 'Was ' + fmtDay(occ, { day: 'numeric', month: 'short', year: 'numeric' })
    : fmtDay(occ) + ' · in ' + days + ' days';
  if (d.yearly && d.year > 0) {
    const n = +occ.slice(0, 4) - d.year;
    if (n > 0) s += d.kind === 'Birthday' ? ' · turns ' + n : ` · ${n} year${n === 1 ? '' : 's'}`;
  }
  return s;
}

function render(view) {
  const page = h('div.page');
  const today = L.today();
  const items = allDates().map((d) => ({ d, occ: nextOccurrence(d, today) }));
  if (!items.length) page.appendChild(empty('Birthdays, anniversaries, contract renewals, passport expiry…\nTap + to add one.'));
  items.sort((a, b) => {
    const pa = a.occ < today, pb = b.occ < today;
    if (pa !== pb) return pa ? 1 : -1;
    return pa ? (a.occ < b.occ ? 1 : -1) : (a.occ < b.occ ? -1 : a.occ > b.occ ? 1 : 0);
  });
  let section = null, c;
  for (const { d, occ } of items) {
    const days = L.daysBetween(today, occ);
    const s = days < 0 ? 'Past' : days <= 7 ? 'This week' : days <= 31 ? 'Next 30 days' : 'Later';
    if (s !== section) { section = s; page.appendChild(header(s)); c = card(); page.appendChild(c); }
    c.appendChild(row({ icon: dateEmoji(d), title: (d.fromWallet || !SP.badge('dates', d.uid) ? '' : SP.badge('dates', d.uid) + ' ') + d.title, sub: dateWhen(d, occ) + (d.fromWallet ? ' · from Wallet' : ' · ' + d.kind) + (d.remind_days >= 0 ? ' · 🔔' : ''),
      subClass: days < 0 ? 'red' : '', onclick: () => (d.fromWallet ? app.go('wallet', d.walletUid) : edit(d)) }));
  }
  page.appendChild(h('p.kbd-hint', 'Reminders come from the Android app; here they show on the Today page.'));
  view.appendChild(page);
  view.appendChild(h('button.fab', { type: 'button', 'aria-label': 'New date', onclick: () => edit({}) }, '+'));
}

function edit(d) {
  const isNew = !d.uid;
  const title = input(d.title || '', { placeholder: 'e.g. Mum\'s birthday' });
  const kind = select(DATE_KINDS, d.kind || 'Birthday');
  const now = new Date();
  const dateIn = input(isNew ? L.today() : L.dateOf(d.year > 0 ? d.year : now.getFullYear(), d.month, d.day), { type: 'date' });
  const yearly = h('input', { type: 'checkbox', checked: isNew ? true : !!d.yearly });
  const knowYear = h('input', { type: 'checkbox', checked: isNew || d.year > 0 });
  const knowRow = h('label.check-row', knowYear, 'I know the year (shows age / years)');
  const sync = () => { knowRow.hidden = !yearly.checked; };
  yearly.addEventListener('change', sync);
  kind.addEventListener('change', () => { yearly.checked = ['Birthday', 'Anniversary', 'Name day'].includes(kind.value); sync(); });
  sync();
  const remind = select(REMIND.map(([v, l]) => [String(v), l]), String(d.remind_days ?? 1));
  const notes = textarea(d.notes || '', { placeholder: 'Gift ideas, contract number…' });
  const buttons = [{ label: 'Cancel' }, { label: 'Save', primary: true, action: () => {
    if (!title.value.trim() || !dateIn.value) return false;
    const [y, m, dd] = dateIn.value.split('-').map(Number);
    S.put('dates', { uid: d.uid, title: title.value.trim(), kind: kind.value, month: m, day: dd, yearly: yearly.checked ? 1 : 0,
      year: !yearly.checked || knowYear.checked ? y : 0, remind_days: +remind.value, notes: notes.value.trim(), created: d.created || Date.now() });
  } }];
  if (!isNew) buttons.unshift({ label: 'Delete', danger: true, action: async () => { if (await confirm('Delete "' + d.title + '"?', null, 'Delete')) S.del('dates', d.uid); } });
  if (!isNew && SP.shareItems('dates', 'dates', d.uid, { people: true }).length) buttons.unshift({ label: d.space ? '👥 Shared' : 'Share…', action: () => { menu('Share "' + d.title + '"', SP.shareItems('dates', 'dates', d.uid, { people: true })); } });
  sheet({ title: isNew ? 'New date' : 'Edit date', body: [field('What', title), field('Kind', kind), field('Date', dateIn),
    h('label.check-row', yearly, 'Every year'), knowRow, field('Remind me', remind), field('Notes', notes)], buttons });
}

export default {
  id: 'dates',
  name: 'Important dates',
  icon: 'cake',
  tables: ['dates', 'wallet'],
  render,
  open(uid) { const d = S.get('dates', uid); if (d) edit(d); },
};
