// My Trips: business trips (expenses in any currency, receipts, per diem, mileage, CSV report) and private
// trips (places to go, bookings, journal, budget). Packing lists come from checklist templates.
import * as S from './store.js';
import * as L from './logic.js';
import * as A from './attach.js';
import { app } from './ctx.js';
import { h, chip, header, card, row, empty, pill, sheet, field, input, select, textarea, menu, prompt, confirm, toast, fmtDay, download, shareText } from './ui.js';
import { copyList } from './m-checklists.js';

const BUSINESS = 0, PRIVATE = 1;
export const EXPENSE_CATS = ['Taxi / Uber', 'Hotel', 'Breakfast', 'Lunch', 'Dinner', 'Client meal', 'Flight', 'Train', 'Public transport',
  'Car rental / fuel', 'Parking', 'Phone / internet', 'Food & drinks', 'Sightseeing', 'Shopping', 'Mileage (own car)', 'Other'];
const EXPENSE_EMOJI = ['🚕', '🏨', '🥐', '🥪', '🍽', '🤝', '✈', '🚆', '🚇', '⛽', '🅿', '📶', '🍹', '🎟', '🛍', '🚗', '🧾'];
const MILEAGE = 'Mileage (own car)';
const PAYMENTS = ['Company card', 'Personal card', 'Cash', 'Other'];
const BOOKING_TYPES = ['Stay', 'Flight', 'Train', 'Bus', 'Car', 'Pass', 'Ticket', 'Other'];
const BOOKING_EMOJI = ['🏨', '✈', '🚆', '🚌', '🚗', '🎫', '🎟', '📄'];
const TABS = { [BUSINESS]: ['Expenses', 'Bookings & passes', 'Packing list', 'Notes'], [PRIVATE]: ['Places to go', 'Bookings & passes', 'Journal', 'Expenses', 'Packing list'] };

let openUid = null;
let tab = null;

const trips = () => S.all('trips');
const expenses = (trip) => S.where('expenses', (e) => e.trip === trip).sort((a, b) => (a.day < b.day ? -1 : a.day > b.day ? 1 : a.created - b.created));
const tripItems = (trip, kind) => S.where('trip_items', (i) => i.trip === trip && i.kind === kind).sort((a, b) => (a.day < b.day ? -1 : a.day > b.day ? 1 : a.created - b.created));
const homeOf = (e) => { const t = S.get('trips', e.trip); return t ? t.currency : e.currency; };
const home = (e) => { const t = S.get('trips', e.trip); return t ? L.toHome(e.cents, e.currency, t.currency, L.parseRates(t.rates)) : e.cents; };
const homeTotal = (list) => list.reduce((n, e) => n + Math.max(0, home(e)), 0);
const catEmoji = (c) => EXPENSE_EMOJI[EXPENSE_CATS.indexOf(c)] || '🧾';

export function currentTrip() {
  const today = L.today();
  let next = null, latest = null;
  for (const t of trips()) {
    if (t.start && t.start <= today && (!t.finish || t.finish >= today)) return t;
    if (t.start > today && (!next || t.start < next.start)) next = t;
    if (!latest || t.start > latest.start) latest = t;
  }
  return next || latest;
}

function range(t) {
  if (!t.start) return '';
  const f = (d) => fmtDay(d, { day: 'numeric', month: 'short', year: 'numeric' });
  return !t.finish || t.finish === t.start ? f(t.start) : f(t.start) + ' – ' + f(t.finish);
}
function perDiem(t) {
  const [full, partial] = L.perDiemDays(t.start, t.finish);
  return { full, partial, total: full * t.pd_full + partial * t.pd_partial };
}

// ------------------------------------------------------------------ list

function renderList(page) {
  const today = L.today();
  const all = trips();
  if (!all.length) page.appendChild(empty('No trips yet.\nTap + for a business trip (expenses for the report) or a private trip (plan, bookings, journal).'));
  const upcoming = all.filter((t) => !t.finish || t.finish >= today || !t.start).sort((a, b) => (a.start || '9') < (b.start || '9') ? -1 : 1);
  const past = all.filter((t) => !upcoming.includes(t)).sort((a, b) => (a.start < b.start ? 1 : -1));
  const show = (title, list) => {
    if (!list.length) return;
    page.appendChild(header(title));
    page.appendChild(card(list.map((t) => {
      const ex = expenses(t.uid);
      const sub = [range(t), t.destination, ex.length ? `${ex.length} expense${ex.length === 1 ? '' : 's'} · ${L.money(homeTotal(ex), t.currency)}` : ''].filter(Boolean).join(' · ');
      return row({ icon: t.kind === BUSINESS ? '💼' : '🧳', title: t.name, sub, onclick: () => openTrip(t.uid) });
    })));
  };
  show('Current & upcoming', upcoming);
  show('Past', past);
  page.appendChild(h('button.btn.block', { type: 'button', onclick: monthlyReport }, '📊 Monthly business expense report'));
}

function openTrip(uid) {
  const t = S.get('trips', uid);
  if (!t) return;
  openUid = uid;
  tab = TABS[t.kind][0];
  app.refresh();
}

// ------------------------------------------------------------------ trip

function renderTrip(page, t) {
  page.appendChild(h('div.stat', [t.kind === BUSINESS ? '💼 Business' : '🧳 Private', range(t), t.destination].filter(Boolean).join(' · ')));
  page.appendChild(h('div.chips', TABS[t.kind].map((x) => chip(x, { selected: x === tab, onclick: () => { tab = x; app.refresh(); } }))));
  if (tab === 'Expenses') renderExpenses(page, t);
  else if (tab === 'Packing list') renderPacking(page, t);
  else renderItems(page, t, tab === 'Bookings & passes' ? 'booking' : tab === 'Places to go' ? 'place' : 'journal');
}

function renderExpenses(page, t) {
  const ex = expenses(t.uid);
  const total = homeTotal(ex);
  const unknown = ex.filter((e) => home(e) < 0).map((e) => e.currency);
  if (t.kind === PRIVATE && t.budget) {
    const pct = Math.min(100, Math.round((total / t.budget) * 100));
    page.appendChild(card(h('div', { style: { padding: '12px 14px' } }, h('div', `Spent ${L.money(total, t.currency)} of ${L.money(t.budget, t.currency)}`),
      h('div.progress' + (total > t.budget ? '.over' : ''), h('div', { style: { width: pct + '%' } })),
      h('div.row-sub', total > t.budget ? `Over budget by ${L.money(total - t.budget, t.currency)}` : `${L.money(t.budget - total, t.currency)} left`))));
  }
  if (t.kind === BUSINESS && t.per_diem && t.start) {
    const p = perDiem(t);
    page.appendChild(card(row({ icon: '🍽', title: 'Per diem ' + L.money(p.total, t.currency),
      sub: `${p.full} full day(s) × ${L.money(t.pd_full, t.currency)} + ${p.partial} partial day(s) × ${L.money(t.pd_partial, t.currency)}` })));
  }
  page.appendChild(h('div.stat', `${ex.length} expense${ex.length === 1 ? '' : 's'} · total ${L.money(total, t.currency)}`
    + (unknown.length ? ` · ⚠ no rate for ${[...new Set(unknown)].join(', ')} (⋯ → Edit trip)` : '')));
  let day = null, c;
  for (const e of ex) {
    if (e.day !== day) { day = e.day; page.appendChild(header(fmtDay(e.day, { weekday: 'short', day: 'numeric', month: 'short' }))); c = card(); page.appendChild(c); }
    const hm = home(e);
    const conv = e.currency !== t.currency ? (hm >= 0 ? ' ≈ ' + L.money(hm, t.currency) : ' (no rate)') : '';
    const n = A.count('expense', e.uid);
    c.appendChild(row({ icon: catEmoji(e.category), title: L.money(e.cents, e.currency) + conv + ' · ' + (e.merchant || e.category),
      sub: [e.category !== (e.merchant || e.category) ? e.category : '', e.km ? e.km + ' km' : '', e.payment, e.attendees ? 'with ' + e.attendees : '',
        n ? '🧾 receipt' : t.kind === BUSINESS ? 'no receipt' : '', e.submitted ? '✓ submitted' : ''].filter(Boolean).join(' · '),
      onclick: () => editExpense(t, e) }));
  }
  page.appendChild(h('button.fab', { type: 'button', 'aria-label': 'Add expense', onclick: () => newExpense(t) }, '+'));
}

function newExpense(t, files) {
  const today = L.today();
  const day = t.start && (today < t.start || (t.finish && today > t.finish)) ? t.start : today;
  editExpense(t, { trip: t.uid, day, currency: t.currency, category: localStorage.getItem('doable.lastCat') || EXPENSE_CATS[0],
    payment: localStorage.getItem('doable.lastPay') || (t.kind === BUSINESS ? PAYMENTS[0] : PAYMENTS[1]) }, files);
}

async function editExpense(t, e, files) {
  const isNew = !e.uid;
  const uid = e.uid || S.newUid();
  if (files) for (const f of files) await A.addFile('expense', uid, f);
  const day = input(e.day || L.today(), { type: 'date' });
  const cat = select(EXPENSE_CATS, e.category || EXPENSE_CATS[0]);
  const amount = input(e.cents ? (e.cents / 100).toFixed(2) : '', { inputmode: 'decimal', placeholder: '0.00' });
  const currency = input(e.currency || t.currency, { style: { maxWidth: '90px', flex: 'none' }, autocapitalize: 'characters' });
  const known = [t.currency, ...Object.keys(L.parseRates(t.rates))];
  const curChips = h('div.chips', known.map((c) => chip(c, { onclick: () => { currency.value = c; conv(); } })));
  const convLine = h('div.row-sub');
  const conv = () => {
    const c = L.parseAmount(amount.value);
    const cur = currency.value.trim().toUpperCase();
    if (c > 0 && cur && cur !== t.currency) {
      const hm = L.toHome(c, cur, t.currency, L.parseRates(t.rates));
      convLine.textContent = hm >= 0 ? '≈ ' + L.money(hm, t.currency) : 'No exchange rate for ' + cur + ' yet — you\'ll be asked after saving';
    } else convLine.textContent = '';
  };
  amount.addEventListener('input', conv);
  currency.addEventListener('input', conv);
  conv();
  const km = input(e.km ? String(e.km) : '', { inputmode: 'decimal', placeholder: 'Kilometres' });
  const kmRow = field(`Distance (× ${L.money(t.km_rate, t.currency)} per km)`, km);
  const amountRow = field('Amount and currency', h('div', h('div.form-inline', amount, currency), curChips, convLine));
  const syncCat = () => { const m = cat.value === MILEAGE; kmRow.hidden = !m; amountRow.hidden = m; };
  cat.addEventListener('change', syncCat);
  syncCat();
  const merchant = input(e.merchant || '', { placeholder: 'Restaurant, hotel, taxi company…' });
  const who = input(e.attendees || '', { placeholder: 'Names, company (client meals)' });
  const pay = select(PAYMENTS, e.payment || PAYMENTS[0]);
  const note = textarea(e.note || '', { rows: 2, placeholder: 'Business purpose, project / WBS code…' });
  const submitted = h('input', { type: 'checkbox', checked: !!e.submitted });
  let saved = false;
  const buttons = [{ label: 'Cancel' }, { label: 'Save', primary: true, action: async () => {
    let cents = L.parseAmount(amount.value);
    let cur = currency.value.trim().toUpperCase() || t.currency;
    let kmv = 0;
    if (cat.value === MILEAGE) {
      kmv = Math.max(0, parseFloat(km.value.replace(',', '.')) || 0);
      cents = Math.round(kmv * t.km_rate);
      cur = t.currency;
    }
    if (cents < 0) { toast('Please enter an amount like 12.50'); return false; }
    saved = true;
    localStorage.setItem('doable.lastCat', cat.value);
    localStorage.setItem('doable.lastPay', pay.value);
    S.put('expenses', { uid, trip: t.uid, day: day.value || L.today(), category: cat.value, cents, currency: cur, merchant: merchant.value.trim(),
      attendees: who.value.trim(), payment: pay.value, note: note.value.trim(), submitted: submitted.checked ? 1 : 0, km: kmv, created: e.created || Date.now() });
    if (cur !== t.currency && !(cur in L.parseRates(t.rates))) setTimeout(() => askRate(t, cur), 300);
  } }];
  if (!isNew) buttons.unshift({ label: 'Delete', danger: true, action: async () => { if (await confirm('Delete this expense?', null, 'Delete')) { saved = true; S.remove('expenses', uid); } } });
  sheet({ title: isNew ? 'New expense' : 'Edit expense', dismissable: false,
    body: [field('Date', day), field('Category', cat), amountRow, kmRow, field('Where / what', merchant), t.kind === BUSINESS ? field('With whom', who) : null,
      field('Paid with', pay), field('Note', note), field('Receipt', A.strip('expense', uid, { label: 'Receipt' })),
      t.kind === BUSINESS ? h('label.check-row', submitted, 'Already submitted to the expense tool') : null],
    buttons, onClose: () => { if (isNew && !saved) A.removeAll('expense', uid); } });
}

async function askRate(t, cur) {
  const v = await prompt(`1 ${cur} = ? ${t.currency}`, '', { placeholder: 'e.g. 0.92', message: 'Exchange rate for converting to ' + t.currency + '. Check your bank statement or an exchange rate site.' });
  if (!v) return;
  const r = parseFloat(v.replace(',', '.'));
  if (!(r > 0)) { toast('Not a number'); return; }
  const rates = L.parseRates(t.rates);
  rates[cur] = r;
  S.put('trips', { uid: t.uid, rates: L.ratesString(rates) });
}

function renderItems(page, t, kind) {
  const list = tripItems(t.uid, kind);
  if (!list.length) {
    page.appendChild(empty(kind === 'booking' ? 'Hotel, flights, trains, tickets — with screenshots or PDFs.' : kind === 'place' ? 'Sights, restaurants, things to do. Tick them off when you\'ve been.'
      : t.kind === BUSINESS ? 'Notes for this trip.' : 'Your travel journal: what you did, with photos.'));
  }
  const c = card();
  for (const i of list) {
    const n = A.count('trip_item', i.uid);
    const emoji = kind === 'booking' ? BOOKING_EMOJI[BOOKING_TYPES.indexOf(i.subtype)] || '📄' : kind === 'place' ? (i.done ? '✅' : '📍') : '📖';
    c.appendChild(row({ icon: emoji, title: i.title || (kind === 'booking' ? i.subtype : 'Entry'),
      sub: [kind !== 'place' && i.day ? fmtDay(i.day) : '', i.body.slice(0, 120), n ? (kind === 'booking' ? '📎 ' : '📷 ') + n : ''].filter(Boolean).join(' · '),
      end: kind === 'place' ? pill(i.done ? '✓' : 'Been', { on: !!i.done, onclick: () => S.put('trip_items', { uid: i.uid, done: i.done ? 0 : 1 }) }) : null,
      onclick: () => editItem(t, i) }));
  }
  if (list.length) page.appendChild(c);
  page.appendChild(h('button.fab', { type: 'button', 'aria-label': 'Add', onclick: () => editItem(t, { trip: t.uid, kind, subtype: kind === 'booking' ? BOOKING_TYPES[0] : '',
    day: kind === 'journal' ? L.today() : '' }) }, '+'));
}

function editItem(t, i) {
  const isNew = !i.uid;
  const uid = i.uid || S.newUid();
  const booking = i.kind === 'booking';
  const sub = select(BOOKING_TYPES, i.subtype || BOOKING_TYPES[0]);
  const title = input(i.title || '', { placeholder: booking ? 'e.g. Hotel Adlon, LH 123' : i.kind === 'place' ? 'e.g. Louvre' : 'Title (optional)' });
  const day = input(i.day || '', { type: 'date' });
  const body = textarea(i.body || '', { rows: i.kind === 'journal' ? 7 : 3, placeholder: booking ? 'Booking number, address, check-in time…' : i.kind === 'place' ? 'Address, opening hours, tips…' : 'What happened today…' });
  let saved = false;
  const buttons = [{ label: 'Cancel' }, { label: 'Save', primary: true, action: () => {
    saved = true;
    S.put('trip_items', { uid, trip: t.uid, kind: i.kind, subtype: booking ? sub.value : '', title: title.value.trim(), body: body.value.trim(), day: day.value || '',
      done: i.done || 0, created: i.created || Date.now() });
  } }];
  if (!isNew) buttons.unshift({ label: 'Delete', danger: true, action: async () => { if (await confirm('Delete this?', null, 'Delete')) { saved = true; S.remove('trip_items', uid); } } });
  sheet({ title: isNew ? 'Add' : 'Edit', dismissable: false, body: [booking ? field('Type', sub) : null, field('Title', title), i.kind !== 'place' ? field('Date', day) : null,
    field('Details', body), field(booking ? 'Screenshots / PDFs' : 'Photos', A.strip('trip_item', uid, { label: booking ? 'File' : 'Photo' }))],
  buttons, onClose: () => { if (isNew && !saved) A.removeAll('trip_item', uid); } });
}

function renderPacking(page, t) {
  const l = S.where('checklists', (x) => x.trip === t.uid && !x.template)[0];
  if (!l) {
    page.appendChild(empty('No packing list yet.'));
    const tpls = S.where('checklists', (x) => x.template);
    page.appendChild(card(tpls.map((tp) => row({ icon: '📋', title: 'Start from "' + tp.title + '"', onclick: () => copyList(tp.uid, 'Packing: ' + t.name, false, t.uid) }))));
    page.appendChild(h('button.btn.block', { type: 'button', onclick: () => S.put('checklists', { title: 'Packing: ' + t.name, trip: t.uid, created: Date.now() }) }, 'Start an empty list'));
    return;
  }
  page.appendChild(h('button.btn.block', { type: 'button', onclick: () => app.go('checklists', l.uid) }, '☑ Open the packing list'));
  const its = S.where('checklist_items', (i) => i.list === l.uid);
  page.appendChild(h('div.stat', `${its.filter((i) => i.done).length} of ${its.length} packed`));
}

function editTrip(t) {
  const isNew = !t.uid;
  const kind = select([[String(BUSINESS), '💼 Business'], [String(PRIVATE), '🧳 Private']], String(t.kind ?? BUSINESS));
  const name = input(t.name || '', { placeholder: 'e.g. Munich client workshop' });
  const dest = input(t.destination || '', { placeholder: 'City, country' });
  const start = input(t.start || '', { type: 'date' });
  const finish = input(t.finish || '', { type: 'date' });
  const cur = input(t.currency || S.settings.homeCurrency || 'EUR', { autocapitalize: 'characters' });
  const rates = input(t.rates ? t.rates.replace(/;/g, '; ') : '', { placeholder: 'e.g. USD=0.92; INR=0.0108' });
  const budget = input(t.budget ? (t.budget / 100).toFixed(2) : '', { inputmode: 'decimal', placeholder: 'Optional' });
  const pd = h('input', { type: 'checkbox', checked: !!t.per_diem });
  const pdFull = input(((t.pd_full ?? 2800) / 100).toFixed(2), { inputmode: 'decimal' });
  const pdPart = input(((t.pd_partial ?? 1400) / 100).toFixed(2), { inputmode: 'decimal' });
  const kmRate = input(((t.km_rate ?? 30) / 100).toFixed(2), { inputmode: 'decimal' });
  const bizRows = h('div', h('label.check-row', pd, 'Claim per-diem allowances'), h('div.form-inline', field('Full day', pdFull), field('Arrival/departure day', pdPart)),
    field('Mileage rate per km', kmRate));
  const privRows = field('Budget (home currency)', budget);
  const syncKind = () => { const b = +kind.value === BUSINESS; bizRows.hidden = !b; privRows.hidden = b; };
  kind.addEventListener('change', syncKind);
  syncKind();
  const buttons = [{ label: 'Cancel' }, { label: 'Save', primary: true, action: () => {
    if (!name.value.trim()) { toast('Give the trip a name'); return false; }
    const r = L.parseRates(rates.value);
    const uid = S.put('trips', { uid: t.uid, name: name.value.trim(), kind: +kind.value, destination: dest.value.trim(), start: start.value || '',
      finish: finish.value || '', currency: cur.value.trim().toUpperCase() || 'EUR', rates: L.ratesString(r), budget: Math.max(0, L.parseAmount(budget.value)),
      per_diem: pd.checked ? 1 : 0, pd_full: Math.max(0, L.parseAmount(pdFull.value)), pd_partial: Math.max(0, L.parseAmount(pdPart.value)),
      km_rate: Math.max(0, L.parseAmount(kmRate.value)), created: t.created || Date.now() });
    if (isNew) openTrip(uid);
  } }];
  sheet({ title: isNew ? 'New trip' : 'Edit trip', body: [field('Kind', kind), field('Name', name), field('Destination', dest),
    h('div.form-inline', field('From', start), field('To', finish)), field('Home currency (totals & reports)', cur),
    field('Exchange rates: value of 1 unit in the home currency', rates), privRows, bizRows], buttons });
}

// ------------------------------------------------------------------ reports

function csv(list, t) {
  const dec = (1.5).toLocaleString().includes(',') ? ',' : '.';
  const sep = dec === ',' ? ';' : ',';
  const num = (c) => (c / 100).toFixed(2).replace('.', dec);
  const d = (day) => day ? day.slice(8, 10) + '.' + day.slice(5, 7) + '.' + day.slice(0, 4) : '';
  let out = '﻿' + L.csvRow(sep, ['Date', 'Trip', 'Category', 'Where / what', 'With whom', 'Paid with', 'Amount', 'Currency', 'Amount (home)', 'Home currency',
    'Km', 'Receipt', 'Submitted', 'Notes']);
  for (const e of list) {
    const tr = S.get('trips', e.trip);
    const hm = home(e);
    out += L.csvRow(sep, [d(e.day), tr ? tr.name : '', e.category, e.merchant, e.attendees, e.payment, num(e.cents), e.currency, hm >= 0 ? num(hm) : '', homeOf(e),
      e.km ? String(Math.round(e.km)) : '', A.count('expense', e.uid) ? 'yes' : 'no', e.submitted ? 'yes' : 'no', e.note]);
  }
  if (t && t.kind === BUSINESS && t.per_diem && t.start) {
    const p = perDiem(t);
    out += L.csvRow(sep, [d(t.start), t.name, 'Per diem allowance', `${p.full} full + ${p.partial} partial days`, '', '', num(p.total), t.currency, num(p.total), t.currency, '', '', '', '']);
  }
  return out;
}
const fileName = (title) => title.replace(/[^A-Za-z0-9äöüÄÖÜß ._-]/g, '').trim().replace(/ /g, '-') + '.csv';

function exportOptions(title, list, t) {
  menu(title, [
    { label: '📄 Download CSV (Excel)', action: () => download(fileName(title), new Blob([csv(list, t)], { type: 'text/csv' })) },
    { label: '✉ Share as text', action: () => shareText(title, title + '\n\n' + list.map((e) => `${e.day}  ${e.category}  ${e.merchant}  ${L.money(e.cents, e.currency)}`
      + (e.currency !== homeOf(e) && home(e) >= 0 ? ' (≈ ' + L.money(home(e), homeOf(e)) + ')' : '')).join('\n')
      + `\n\nTotal: ${L.money(homeTotal(list), list[0] ? homeOf(list[0]) : 'EUR')}`) },
    { label: '✓ Mark all as submitted', action: () => list.forEach((e) => S.put('expenses', { uid: e.uid, submitted: 1 })) },
  ]);
}

async function monthlyReport() {
  const months = [...new Set(S.all('expenses').filter((e) => { const t = S.get('trips', e.trip); return t && t.kind === BUSINESS; }).map((e) => e.day.slice(0, 7)))].sort().reverse();
  if (!months.length) { toast('No business expenses yet'); return; }
  menu('Which month?', months.map((m) => ({ label: L.toDate(m + '-01').toLocaleDateString(undefined, { month: 'long', year: 'numeric' }), action: () => {
    const list = S.all('expenses').filter((e) => e.day.startsWith(m) && (S.get('trips', e.trip) || {}).kind === BUSINESS)
      .sort((a, b) => (a.day < b.day ? -1 : a.day > b.day ? 1 : 0));
    exportOptions('Business expenses ' + m, list, null);
  } })));
}

export default {
  id: 'trips',
  name: 'My Trips',
  icon: 'trip',
  tables: ['trips', 'expenses', 'trip_items', 'attachments', 'checklists', 'checklist_items'],
  title() { const t = openUid && S.get('trips', openUid); return t ? t.name : 'My Trips'; },
  render(view) {
    const page = h('div.page');
    const t = openUid && S.get('trips', openUid);
    if (t) renderTrip(page, t); else { openUid = null; renderList(page); view.appendChild(h('button.fab', { type: 'button', 'aria-label': 'New trip', onclick: () => editTrip({}) }, '+')); }
    view.appendChild(page);
  },
  isDeep: () => !!openUid,
  back() { openUid = null; app.refresh(); },
  open(uid) { openTrip(uid); },
  quick() { const t = currentTrip(); if (t) { openTrip(t.uid); tab = 'Expenses'; app.refresh(); newExpense(t); } else editTrip({}); },
  addReceipt(t, files) { openTrip(t.uid); tab = 'Expenses'; app.refresh(); newExpense(t, files); },
  menu() {
    const t = openUid && S.get('trips', openUid);
    if (!t) return [{ label: '📊 Monthly business expense report', action: monthlyReport }];
    return [
      { label: '✏️ Edit trip', sub: 'Dates, currency, rates…', action: () => editTrip(t) },
      { label: '📄 Expense report for this trip', action: () => exportOptions('Expenses ' + t.name, expenses(t.uid), t) },
      { label: '🗑 Delete trip', danger: true, action: async () => {
        if (await confirm('Delete "' + t.name + '"?', 'Its expenses, bookings, journal and packing list are deleted too.', 'Delete')) { S.remove('trips', t.uid); openUid = null; app.refresh(); }
      } },
    ];
  },
};
