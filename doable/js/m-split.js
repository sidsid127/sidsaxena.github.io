// Shared expenses: who paid what in a household (or with a person you share with), balances, and the fewest payments
// that settle up. Rows live in the household's space, so they're end-to-end encrypted and on every member's devices.
import * as S from './store.js';
import * as L from './logic.js';
import * as SP from './spaces.js';
import * as A from './account.js';
import { app } from './ctx.js';
import { h, card, row, header, empty, chip, sheet, field, input, select, textarea, confirm, toast, menu, fmtDay } from './ui.js';

const CATEGORIES = ['🛒 Groceries', '🏠 Rent', '💡 Utilities', '🍽 Eating out', '🚗 Transport', '✈️ Travel', '🎉 Fun', '🧸 Kids', '🩺 Health', '📦 Other'];
let sel = '';

const spaces = () => SP.list().filter((s) => (s.kind === 'household' && s.modules.includes('split')) || s.kind === 'share');
const cur = () => S.settings.homeCurrency || 'EUR';
const money = (cents, c) => (cents / 100).toLocaleString(undefined, { style: 'currency', currency: c || 'EUR', minimumFractionDigits: 2 });
const me = () => (A.user() || {}).id;
function nameIn(sp, id) {
  if (id === me()) return 'You';
  const m = sp.members.find((x) => x.user_id === id);
  return m ? (m.name || m.email).split(' ')[0] : 'Someone';
}
const rows = (id) => S.where('shared_expenses', (r) => r.space === id).sort((a, b) => (b.day || '').localeCompare(a.day || '') || b.created - a.created);

function render(view) {
  const page = h('div.page');
  view.appendChild(page);
  const list = spaces();
  if (!A.signedIn() || !list.length) {
    page.appendChild(empty('Split costs with your household: who paid what, who owes whom, and the fewest payments to settle up.\n\n'
      + (SP.households().length ? 'Turn on "Shared expenses" for your household (Household & sharing → your household).' : 'Create a household first.')));
    page.appendChild(h('button.btn.primary', { type: 'button', style: { display: 'block', margin: '0 auto' }, onclick: () => app.go('household') }, 'Household & sharing'));
    return;
  }
  if (!list.some((s) => s.id === sel)) sel = list[0].id;
  const sp = SP.get(sel);
  if (list.length > 1) {
    page.appendChild(h('div.chips', list.map((s) => chip(SP.spaceLabel(s.id), { selected: s.id === sel, onclick: () => { sel = s.id; app.refresh(); } }))));
  }
  if (!SP.ready(sel)) page.appendChild(h('p.muted.small', '⏳ Still getting the key for this household on this device…'));

  const all = rows(sel);
  const bal = L.splitBalances(all);
  const currencies = Object.keys(bal).sort();
  const summary = h('div.split-summary');
  if (!currencies.length) summary.appendChild(h('div.split-big', 'All settled'));
  for (const c of currencies) {
    const mine = bal[c][me()] || 0;
    summary.appendChild(h('div.split-big' + (mine > 0 ? '.plus' : mine < 0 ? '.minus' : ''),
      mine > 0 ? 'You get back ' + money(mine, c) : mine < 0 ? 'You owe ' + money(-mine, c) : 'You\'re settled' + (currencies.length > 1 ? ' (' + c + ')' : '')));
    for (const [u, cents] of Object.entries(bal[c]).sort((a, b) => b[1] - a[1])) {
      if (u === me()) continue;
      summary.appendChild(h('div.split-line', h('span', nameIn(sp, u)), h('span' + (cents > 0 ? '.plus' : '.minus'), (cents > 0 ? 'gets back ' : 'owes ') + money(Math.abs(cents), c))));
    }
  }
  page.appendChild(card(summary, h('div.split-actions',
    h('button.btn.primary', { type: 'button', onclick: () => edit({}) }, '+ Add expense'),
    h('button.btn', { type: 'button', onclick: () => settle(sp, bal), disabled: !currencies.length }, 'Settle up'))));

  page.appendChild(header('History'));
  if (!all.length) page.appendChild(card(row({ icon: '🧾', title: 'No expenses yet', sub: 'Add what you paid for the household — everyone sees it and the balances update.' })));
  else {
    page.appendChild(card(all.map((r) => {
      if (r.kind === 'settle') {
        return row({ icon: '🤝', title: nameIn(sp, r.paid_by) + ' paid ' + nameIn(sp, r.to_user).replace(/^You$/, 'you'), sub: fmtDay(r.day) + (r.note ? ' · ' + r.note : ''),
          end: h('span.split-amt', money(r.cents, r.currency)), onclick: () => edit(r) });
      }
      const n = Object.keys(L.splitShares(r.shares)).length;
      const myPart = L.splitBalances([{ ...r, paid_by: '__' }])[r.currency || 'EUR'] || {};
      const mine = -(myPart[me()] || 0);
      return row({ icon: (r.category || '📦').split(' ')[0], title: r.title || 'Expense',
        sub: `${nameIn(sp, r.paid_by)} paid · ${n === sp.members.length ? 'split equally' : 'split between ' + n} · ${fmtDay(r.day)}${mine ? ' · your part ' + money(mine, r.currency) : ''}`,
        end: h('span.split-amt', money(r.cents, r.currency)), onclick: () => edit(r) });
    })));
  }
  view.appendChild(h('button.fab', { type: 'button', 'aria-label': 'Add expense', onclick: () => edit({}) }, '+'));
}

function edit(r) {
  const sp = SP.get(sel);
  if (!sp) return;
  if (r.kind === 'settle') return editPayment(sp, r);
  const isNew = !r.uid;
  const title = input(r.title || '', { placeholder: 'e.g. Weekly shop, Electricity' });
  const amount = input(r.cents ? (r.cents / 100).toFixed(2) : '', { type: 'number', step: '0.01', min: '0', inputmode: 'decimal', placeholder: '0.00' });
  const currency = input(r.currency || cur(), { maxlength: 3, style: { maxWidth: '90px' }, autocapitalize: 'characters' });
  const paidBy = select(sp.members.map((m) => [m.user_id, m.user_id === me() ? 'Me' : (m.name || m.email)]), r.paid_by || me());
  const shares = L.splitShares(r.shares);
  const boxes = sp.members.map((m) => {
    const cb = h('input', { type: 'checkbox', checked: isNew || !!shares[m.user_id] });
    return { m, cb, el: h('label.check-row', cb, m.user_id === me() ? 'Me' : (m.name || m.email)) };
  });
  const category = select(CATEGORIES.map((c) => [c, c]), r.category || CATEGORIES[0]);
  const day = input(r.day || L.today(), { type: 'date' });
  const note = textarea(r.note || '', { rows: 2, placeholder: 'Optional' });
  const buttons = [{ label: 'Cancel' }, { label: 'Save', primary: true, action: () => {
    const cents = Math.round(parseFloat(String(amount.value).replace(',', '.')) * 100);
    const who = boxes.filter((b) => b.cb.checked).map((b) => b.m.user_id);
    if (!cents || cents < 0) { toast('Enter the amount'); return false; }
    if (!who.length) { toast('Choose who shares it'); return false; }
    S.put('shared_expenses', { uid: r.uid, space: sel, title: title.value.trim() || category.value.replace(/^\S+ /, ''), cents, currency: (currency.value || cur()).toUpperCase().slice(0, 3),
      paid_by: paidBy.value, shares: JSON.stringify(Object.fromEntries(who.map((u) => [u, 1]))), category: category.value, day: day.value || L.today(),
      note: note.value.trim(), kind: 'expense', created: r.created || Date.now() });
  } }];
  if (!isNew) buttons.unshift({ label: 'Delete', danger: true, action: async () => { if (await confirm('Delete this expense?', r.title, 'Delete')) S.del('shared_expenses', r.uid); } });
  sheet({ title: isNew ? 'Add expense' : 'Edit expense', body: [field('What', title), h('div.form-inline', field('Amount', amount), field('Currency', currency)),
    field('Paid by', paidBy), field('Split equally between', h('div', boxes.map((b) => b.el))), h('div.form-inline', field('Category', category), field('Date', day)),
    field('Note', note)], buttons });
}

/** Settle up: the fewest payments that clear the balances; tap one to record it. */
function settle(sp, bal) {
  const items = [];
  for (const c of Object.keys(bal)) {
    for (const p of L.settleUp(bal[c])) {
      items.push({ label: `🤝 ${nameIn(sp, p.from)} → ${nameIn(sp, p.to)}`, sub: money(p.cents, c) + ' · tap when it\'s paid', action: () => editPayment(sp, { paid_by: p.from, to_user: p.to, cents: p.cents, currency: c }) });
    }
  }
  items.push('-', { label: '✍️ Record another payment', action: () => editPayment(sp, {}) });
  menu('Settle up', items);
}

function editPayment(sp, r) {
  const isNew = !r.uid;
  const people = sp.members.map((m) => [m.user_id, m.user_id === me() ? 'Me' : (m.name || m.email)]);
  const from = select(people, r.paid_by || me());
  const to = select(people, r.to_user || (sp.members.find((m) => m.user_id !== me()) || {}).user_id || '');
  const amount = input(r.cents ? (r.cents / 100).toFixed(2) : '', { type: 'number', step: '0.01', min: '0', inputmode: 'decimal' });
  const currency = input(r.currency || cur(), { maxlength: 3, style: { maxWidth: '90px' } });
  const day = input(r.day || L.today(), { type: 'date' });
  const note = input(r.note || '', { placeholder: 'e.g. bank transfer, cash' });
  const buttons = [{ label: 'Cancel' }, { label: isNew ? 'Record payment' : 'Save', primary: true, action: () => {
    const cents = Math.round(parseFloat(String(amount.value).replace(',', '.')) * 100);
    if (!cents || cents < 0 || from.value === to.value) { toast(from.value === to.value ? 'Choose two different people' : 'Enter the amount'); return false; }
    S.put('shared_expenses', { uid: r.uid, space: sp.id, title: 'Payment', cents, currency: (currency.value || cur()).toUpperCase().slice(0, 3), paid_by: from.value,
      to_user: to.value, day: day.value || L.today(), note: note.value.trim(), kind: 'settle', shares: '', category: '', created: r.created || Date.now() });
    const other = [from.value, to.value].find((u) => u !== me());
    if (isNew && other) SP.tell(sp.id, other, 'settle');
    toast('Payment recorded');
  } }];
  if (!isNew) buttons.unshift({ label: 'Delete', danger: true, action: async () => { if (await confirm('Delete this payment?', null, 'Delete')) S.del('shared_expenses', r.uid); } });
  sheet({ title: isNew ? 'Record a payment' : 'Edit payment', body: [field('Who paid', from), field('To', to),
    h('div.form-inline', field('Amount', amount), field('Currency', currency)), field('Date', day), field('Note', note)], buttons });
}

export default {
  id: 'split',
  name: 'Shared expenses',
  icon: 'split',
  tables: ['shared_expenses'],
  render,
  menu: () => [
    { label: '🤝 Settle up', action: () => { const sp = SP.get(sel); if (sp) settle(sp, L.splitBalances(rows(sel))); } },
    { label: '✍️ Record a payment', action: () => { const sp = SP.get(sel); if (sp) editPayment(sp, {}); } },
  ],
};
