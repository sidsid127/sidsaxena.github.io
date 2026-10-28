// Groceries: several lists, shops, quantities with units (− / + and a unit picker, remembered per product), urgent
// items first, aisle order, prices per unit on tick-off with a price overview, "buy again", share & import.
import * as S from './store.js';
import * as L from './logic.js';
import { app } from './ctx.js';
import * as SP from './spaces.js';
import { h, chip, header, card, row, empty, sheet, field, input, select, menu, prompt, confirm, toast, shareText, textarea, fmtDay } from './ui.js';

let lastRemoved = null;

const st = S.settings;
const lists = () => S.all('grocery_lists').sort((a, b) => (a.uid.startsWith('seed-') === b.uid.startsWith('seed-') ? a.name.localeCompare(b.name) : a.uid.startsWith('seed-') ? -1 : 1));
const shops = () => S.all('shops').sort((a, b) => a.name.localeCompare(b.name));
const shopName = (uid) => { const s = S.get('shops', uid); return s ? s.name : ''; };
const key = (name) => name.trim().toLowerCase();
function curList() {
  if (!S.get('grocery_lists', st.selList)) { st.selList = (lists()[0] || {}).uid || ''; S.saveSettings(); }
  return st.selList;
}
const listItems = () => S.where('grocery', (i) => !i.done && (i.list || 'seed-list-groceries') === curList());

function latestPrices() {
  const out = {};
  for (const p of S.all('prices').sort((a, b) => (a.day < b.day ? -1 : a.day > b.day ? 1 : 0))) {
    const k = key(p.name);
    (out[k] || (out[k] = {}))[p.shop] = p;
  }
  return out;
}
const cur = () => st.homeCurrency || 'EUR';
const unitLabel = (u) => (u === 'pcs' ? 'piece' : u);
const history = (name) => S.get('grocery_history', 'gh:' + S.asciiLower(name.trim()));

/** What was typed, else the quantity used last time, else the usual one for the product ("1 l" milk, "10 pcs" eggs). */
export function quantityFor(name, typed) {
  const h0 = history(name);
  const usual = (h0 && h0.qty) || L.defaultQty(name);
  if (!typed) return usual;
  return L.parseQty(typed).unit ? typed : L.withUnit(typed, L.parseQty(usual).unit);
}

export function addItem(name, qty, shop, list) {
  const now = Date.now();
  S.put('grocery', { name, qty, shop, list, aisle: L.aisle(name), created: now });
  const gh = 'gh:' + S.asciiLower(name);
  const prev = S.get('grocery_history', gh);
  S.put('grocery_history', { uid: gh, name: prev ? prev.name : name, shop, list, uses: (prev ? prev.uses : 0) + 1, last_used: now, qty });
}

/** New quantity from − / + or the unit picker; remembered for the product. */
function setQty(it, qty) {
  S.put('grocery', { uid: it.uid, qty });
  const h0 = history(it.name);
  if (h0) S.put('grocery_history', { uid: h0.uid, qty });
}

export function importText(text) {
  const names = shops().map((s) => s.name);
  const on = new Set(listItems().map((i) => key(i.name)));
  let added = 0;
  for (const l of L.groceryParse(text, names)) {
    if (!l.name || on.has(key(l.name))) continue;
    const shop = shops().find((s) => s.name.toLowerCase() === l.shop.toLowerCase());
    addItem(l.name, quantityFor(l.name, l.qty), shop ? shop.uid : '', curList());
    on.add(key(l.name));
    added++;
  }
  return added;
}

function shareList() {
  const items = listItems();
  if (!items.length) { toast('The list is empty'); return; }
  const lines = items.map((i) => ({ name: i.name, qty: i.qty, shop: shopName(i.shop) }))
    .sort((a, b) => (a.shop || '~').localeCompare(b.shop || '~') || L.aisleIndex(L.aisle(a.name)) - L.aisleIndex(L.aisle(b.name)));
  const listName = (S.get('grocery_lists', curList()) || {}).name || 'Groceries';
  shareText(listName, L.groceryFormat(listName, lines));
}

async function tick(it) {
  const finish = (cents, per) => {
    if (cents > 0) S.put('prices', { name: it.name, shop: it.shop, cents, qty: '1 ' + per, day: L.today() });
    lastRemoved = it;
    S.del('grocery', it.uid);
    toast('✓ ' + it.name + (cents > 0 ? ' · ' + L.unitPrice(cents, '1 ' + per, cur()) : ''), 'Undo', () => {
      S.put('grocery', { ...lastRemoved, uid: undefined });
    });
  };
  const itemUnit = L.parseQty(it.qty).unit || 'pcs';
  if (!st.askPrice) { finish(0, itemUnit); return; }
  const bought = L.parseQty(it.qty).n;
  const last = (latestPrices()[key(it.name)] || {})[it.shop];
  const amount = input(last && L.parseQty(last.qty).n === 1 ? (last.cents / 100).toFixed(2) : '', { inputmode: 'decimal', placeholder: '0.00', autofocus: true });
  const units = [itemUnit, ...L.UNITS.filter((u) => u !== itemUnit && u !== 'g' && u !== 'ml')];
  const per = select([...units.map((u) => [u, 'per ' + unitLabel(u)]), ...(bought > 0 && bought !== 1 ? [['*', 'for all ' + it.qty]] : [])], itemUnit);
  const save = () => {
    const cents = L.parseAmount(amount.value);
    if (cents > 0 && per.value === '*') finish(Math.round(cents / bought), itemUnit); // total → per unit
    else finish(cents > 0 ? cents : 0, per.value === '*' ? itemUnit : per.value);
  };
  amount.addEventListener('keydown', (e) => { if (e.key === 'Enter') { save(); sh.close(); } });
  const sh = sheet({ title: '✓ ' + it.name + (it.shop ? ' at ' + shopName(it.shop) : ''),
    body: [h('p.muted', 'What did it cost? Doable keeps the price per unit to compare shops. Turn this off in ⋯.'), h('div.form-inline', amount, per)],
    buttons: [{ label: 'Cancel' }, { label: 'Skip', action: () => finish(0, itemUnit) }, { label: 'Save price', primary: true, action: save }] });
}

function priceLine(name, prices) {
  const ps = Object.values(prices[key(name)] || {});
  if (!ps.length) return '';
  ps.sort((a, b) => a.cents - b.cents);
  return ps.slice(0, 3).map((p, i) => `${i === 0 && ps.length > 1 ? 'cheapest ' : ''}${shopName(p.shop) || 'Any shop'} ${L.unitPrice(p.cents, p.qty, cur())}`).join(' · ');
}

function render(view) {
  const page = h('div.page.flush');
  const listChips = h('div.chips');
  for (const l of lists()) {
    const n = S.where('grocery', (i) => !i.done && (i.list || 'seed-list-groceries') === l.uid).length;
    listChips.appendChild(chip((SP.badge('grocery_lists', l.uid) ? SP.badge('grocery_lists', l.uid) + ' ' : '') + l.name + (n ? ` (${n} ${n === 1 ? 'item' : 'items'})` : ''), { selected: l.uid === curList(), onclick: () => { st.selList = l.uid; S.saveSettings(); app.refresh(); },
      onlongpress: () => listMenu(l) }));
  }
  listChips.appendChild(chip('+ List', { onclick: async () => {
    const n = await prompt('New list', '', { placeholder: 'e.g. Party, Pharmacy' });
    if (n) { st.selList = S.put('grocery_lists', { name: n }); S.saveSettings(); app.refresh(); }
  } }));
  page.appendChild(listChips);
  const shopChips = h('div.chips');
  shopChips.appendChild(chip('Any shop', { selected: !st.selShop, onclick: () => { st.selShop = ''; S.saveSettings(); app.refresh(); } }));
  for (const s of shops()) {
    shopChips.appendChild(chip(s.name, { selected: st.selShop === s.uid, color: S.colorCss(s.color), onclick: () => { st.selShop = st.selShop === s.uid ? '' : s.uid; S.saveSettings(); app.refresh(); },
      onlongpress: () => shopMenu(s) }));
  }
  shopChips.appendChild(chip('+ Shop', { onclick: async () => {
    const n = await prompt('New shop', '', { placeholder: 'e.g. Edeka, dm' });
    if (n) st.selShop = S.put('shops', { name: n, color: S.colorInt(S.PALETTE[shops().length % S.PALETTE.length]) });
    S.saveSettings();
  } }));
  page.appendChild(shopChips);

  const items = listItems();
  const prices = latestPrices();
  const urgent = items.filter((i) => i.urgent);
  page.appendChild(h('div.stat', items.length ? `${items.length} ${items.length === 1 ? 'item' : 'items'} to buy` + (urgent.length ? ` · ${urgent.length} urgent` : '') : ''));
  if (!items.length) page.appendChild(empty('The list is empty.\nAdd items below, or tap one under "Buy again".'));
  if (urgent.length) {
    page.appendChild(header('❗ Needed now', 'red'));
    page.appendChild(card(urgent.map((i) => itemRow(i, prices, true))));
  }
  const byShop = new Map();
  for (const i of items) { if (i.urgent) continue; const k = i.shop || ''; if (!byShop.has(k)) byShop.set(k, []); byShop.get(k).push(i); }
  const keys = [...byShop.keys()].sort((a, b) => (shopName(a) || '~').localeCompare(shopName(b) || '~'));
  for (const k of keys) {
    const s = S.get('shops', k);
    page.appendChild(header(s ? s.name : 'Any shop'));
    const group = byShop.get(k).sort((a, b) => L.aisleIndex(a.aisle || L.aisle(a.name)) - L.aisleIndex(b.aisle || L.aisle(b.name)) || a.name.localeCompare(b.name));
    page.appendChild(card(group.map((i) => itemRow(i, prices, false))));
  }
  // Buy again
  const on = new Set(items.map((i) => key(i.name)));
  const usual = S.all('grocery_history').filter((u) => !on.has(key(u.name)) && (u.list || 'seed-list-groceries') === curList())
    .sort((a, b) => (b.uses - a.uses) || (b.last_used - a.last_used)).slice(0, 60);
  if (usual.length) {
    page.appendChild(header('Buy again'));
    page.appendChild(h('div.chips.wrap', usual.map((u) => chip(u.name, { color: S.colorCss((S.get('shops', u.shop) || {}).color) || undefined,
      onclick: () => addItem(u.name, quantityFor(u.name, ''), u.shop, curList()), onlongpress: () => menu(u.name, [
        { label: '📈 Price history', action: () => priceHistory(u.name) },
        { label: 'Forget this item', danger: true, action: () => S.del('grocery_history', u.uid) }]) }))));
  }
  view.appendChild(page);
  view.appendChild(inputBar());
}

/** Name; below it − quantity + and the unit picker; ❗ on the right marks it as needed right away. */
function itemRow(i, prices, showShop) {
  const qty = i.qty || L.defaultQty(i.name);
  const { n, unit } = L.parseQty(qty);
  const unitSel = h('select.unitsel', { 'aria-label': 'Unit', onclick: (e) => e.stopPropagation(), onchange: (e) => setQty(i, L.withUnit(qty, e.target.value)) },
    [...new Set([...L.UNITS, unit || 'pcs'])].map((u) => h('option', { value: u, selected: u === (unit || 'pcs') }, u)));
  const step = (label, dir) => h('button.step', { type: 'button', 'aria-label': dir > 0 ? 'More' : 'Less',
    onclick: (e) => { e.stopPropagation(); setQty(i, L.stepQty(qty, dir)); } }, label);
  const pl = priceLine(i.name, prices);
  return h('div.row.gitem' + (i.urgent ? '.urgent' : ''), { role: 'button', tabindex: 0, onclick: () => editItem(i) },
    h('button.tick', { type: 'button', 'aria-label': 'Bought', onclick: (e) => { e.stopPropagation(); tick(i); } }),
    h('div.row-text',
      h('div.row-title', i.name, showShop && i.shop ? h('span.muted', ' · ' + shopName(i.shop)) : null),
      h('div.qtyline', step('−', -1), h('span.qtyn', L.formatQty(n, '') || '1'), step('+', 1), unitSel, pl ? h('span.row-sub', pl) : null)),
    h('button.flag' + (i.urgent ? '.on' : ''), { type: 'button', title: i.urgent ? 'Urgent — click to unmark' : 'Mark as urgent',
      'aria-label': i.urgent ? 'Urgent' : 'Mark as urgent',
      onclick: (e) => { e.stopPropagation(); S.put('grocery', { uid: i.uid, urgent: i.urgent ? 0 : 1 }); toast(i.urgent ? i.name + ': not urgent' : '❗ ' + i.name + ' is needed right away'); } }, i.urgent ? '❗' : '!'));
}

function inputBar() {
  const inp = h('input.field#grocery-input', { type: 'text', placeholder: 'Add an item… e.g. "milk" or "2 kg apples"', enterkeyhint: 'done', autocomplete: 'off' });
  const suggest = h('div.chips.wrap.suggest');
  const add = (typedName) => {
    const v = (typedName || inp.value).trim();
    if (!v) return;
    const [typedQty, name] = typedName ? ['', typedName] : L.splitQuantity(v);
    if (listItems().some((i) => key(i.name) === key(name))) { toast(name + ' is already on the list'); inp.value = ''; suggest.textContent = ''; return; }
    const hist = history(name);
    addItem(hist ? hist.name : name, quantityFor(name, typedQty), st.selShop || (hist ? hist.shop : ''), curList());
    inp.value = '';
    suggest.textContent = '';
    setTimeout(() => { const i = document.getElementById('grocery-input'); if (i) i.focus(); }, 30);
  };
  // While typing: products bought before that match, one click adds them (with their last quantity).
  inp.addEventListener('input', () => {
    suggest.textContent = '';
    const q = key(L.splitQuantity(inp.value)[1] || '');
    if (q.length < 1) return;
    const on = new Set(listItems().map((i) => key(i.name)));
    const hits = S.all('grocery_history').filter((u) => key(u.name).includes(q) && !on.has(key(u.name)))
      .sort((a, b) => (key(b.name).startsWith(q) - key(a.name).startsWith(q)) || b.uses - a.uses).slice(0, 8);
    for (const u of hits) {
      suggest.appendChild(chip('+ ' + u.name + ' · ' + (u.qty || L.defaultQty(u.name)), { onclick: () => {
        const typedQty = L.splitQuantity(inp.value)[0];
        inp.value = typedQty ? typedQty + ' ' + u.name : '';
        if (typedQty) add(); else add(u.name);
      } }));
    }
  });
  inp.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.isComposing) add(); });
  return h('div.inputbar-wrap', suggest, h('div.inputbar', inp, h('button.btn.primary', { type: 'button', onclick: () => add() }, 'Add')));
}

function editItem(i) {
  const name = input(i.name);
  const q = L.parseQty(i.qty || L.defaultQty(i.name));
  const amount = input(L.formatQty(q.n, ''), { inputmode: 'decimal', placeholder: 'e.g. 2', style: { flex: 1 } });
  const unit = select([...new Set([...L.UNITS, q.unit || 'pcs'])].map((u) => [u, u]), q.unit || 'pcs');
  const urgent = h('input', { type: 'checkbox', checked: !!i.urgent });
  const shop = select([['', 'Any shop'], ...shops().map((s) => [s.uid, s.name])], i.shop || '');
  const aisle = select(L.AISLES, i.aisle || L.aisle(i.name));
  const list = select(lists().map((l) => [l.uid, l.name]), i.list || curList());
  sheet({ title: 'Edit item', body: [field('Item', name), field('Quantity', h('div.form-inline', amount, unit)),
    h('label.check-row', urgent, ' ❗ Urgent — needed right away'), field('Shop', shop), field('Aisle', aisle), field('List', list)],
  buttons: [{ label: 'Delete', danger: true, action: () => S.del('grocery', i.uid) }, { label: '📈 Prices', action: () => priceHistory(i.name) },
    { label: 'Cancel' }, { label: 'Save', primary: true, action: () => {
      if (!name.value.trim()) return false;
      const n = L.parseAmount(amount.value) / 100;
      const qty = L.formatQty(n, unit.value);
      S.put('grocery', { uid: i.uid, name: name.value.trim(), qty, shop: shop.value, aisle: aisle.value, list: list.value, urgent: urgent.checked ? 1 : 0 });
      const h0 = history(name.value);
      if (h0) S.put('grocery_history', { uid: h0.uid, qty });
    } }] });
}

// ------------------------------------------------------------------ prices

const pricesOf = (name) => S.where('prices', (p) => key(p.name) === key(name)).sort((a, b) => (a.day < b.day ? 1 : a.day > b.day ? -1 : 0));

/** "usually 1.29 EUR / kg · 0.99–1.49 · cheapest at Lidl · 5 prices" for the most common unit. */
export function priceSummary(list) {
  const byUnit = new Map();
  for (const p of list) { const k = L.parseQty(p.qty).n === 1 ? p.qty : ''; if (!byUnit.has(k)) byUnit.set(k, []); byUnit.get(k).push(p); }
  const [common, same] = [...byUnit.entries()].sort((a, b) => b[1].length - a[1].length)[0];
  const cents = same.map((p) => p.cents).sort((a, b) => a - b);
  const cheapest = same.reduce((m, p) => (p.cents < m.cents ? p : m), same[0]);
  return 'usually ' + (common ? L.unitPrice(cents[Math.floor(cents.length / 2)], common, cur()) : L.money(cents[Math.floor(cents.length / 2)], cur()))
    + (cents.length > 1 ? ` · ${L.money(cents[0], cur())}–${L.money(cents[cents.length - 1], cur())}` : '')
    + (shopName(cheapest.shop) ? ' · cheapest at ' + shopName(cheapest.shop) : '')
    + ` · ${list.length} ${list.length === 1 ? 'price' : 'prices'}`;
}

function priceOverview() {
  const names = [...new Set(S.all('prices').map((p) => p.name))].sort((a, b) => a.localeCompare(b));
  const search = input('', { type: 'search', placeholder: 'Search products' });
  const out = h('div');
  const draw = () => {
    out.textContent = '';
    const q = key(search.value);
    const shown = names.filter((n) => !q || key(n).includes(q));
    if (!shown.length) { out.appendChild(empty(names.length ? 'Nothing matches.' : 'No prices yet.\nWhen you tick an item off, enter what it cost.')); return; }
    out.appendChild(card(shown.map((n) => row({ icon: '🏷', title: n, sub: priceSummary(pricesOf(n)), onclick: () => { s.close(); priceHistory(n); } }))));
  };
  search.addEventListener('input', draw);
  const s = sheet({ title: '📈 Prices', wide: true, body: [h('p.muted', 'What things usually cost and where they\'re cheapest. Click a product to see, correct or delete its prices.'), search, out],
    buttons: [{ label: 'Close', primary: true }] });
  draw();
}

function priceHistory(name) {
  const list = pricesOf(name);
  const body = [];
  if (list.length) body.push(h('p.muted', priceSummary(list)));
  body.push(list.length ? card(list.map((p) => row({ icon: '🏷', title: L.unitPrice(p.cents, p.qty, cur()),
    sub: `${fmtDay(p.day, { day: 'numeric', month: 'short', year: 'numeric' })} · ${shopName(p.shop) || 'Any shop'}`, onclick: () => { s.close(); editPrice(p); } })))
    : empty('No prices yet for ' + name + '.'));
  const s = sheet({ title: '📈 ' + name, wide: true, body,
    buttons: [{ label: '+ Add price', action: () => editPrice({ name, shop: st.selShop || '', cents: 0, qty: '1 ' + L.parseQty(L.defaultQty(name)).unit, day: L.today() }) },
      { label: 'Close', primary: true }] });
}

/** Correct, delete or add (no uid) a price. */
function editPrice(p) {
  const amount = input(p.cents ? (p.cents / 100).toFixed(2) : '', { inputmode: 'decimal', placeholder: '0.00', autofocus: true });
  const pq = L.parseQty(p.qty);
  const legacy = !(pq.n === 1 && pq.unit);
  const per = select([...L.UNITS.map((u) => [u, 'per ' + unitLabel(u)]), ...(legacy ? [['*', p.qty ? 'for ' + p.qty : '(not given)']] : [])], legacy ? '*' : pq.unit);
  const shop = select([['', 'Any shop'], ...shops().map((s) => [s.uid, s.name])], p.shop || '');
  const day = input(p.day, { type: 'date' });
  const buttons = [{ label: 'Cancel' }, { label: 'Save', primary: true, action: () => {
    const cents = L.parseAmount(amount.value);
    if (!(cents > 0)) { toast('Enter a price'); return false; }
    S.put('prices', { uid: p.uid, name: p.name, cents, qty: per.value === '*' ? p.qty : '1 ' + per.value, shop: shop.value, day: day.value || L.today() });
    setTimeout(() => priceHistory(p.name), 50);
  } }];
  if (p.uid) buttons.unshift({ label: 'Delete', danger: true, action: () => { S.del('prices', p.uid); toast('Price deleted'); setTimeout(() => priceHistory(p.name), 50); } });
  sheet({ title: (p.uid ? 'Price · ' : 'Add price · ') + p.name, body: [field('Price', h('div.form-inline', amount, per)), field('Shop', shop), field('Date', day)], buttons });
}

function shopMenu(s) {
  menu(s.name, [
    { label: 'Rename', action: async () => { const n = await prompt('Rename shop', s.name); if (n) S.put('shops', { uid: s.uid, name: n }); } },
    { label: 'Delete shop', danger: true, action: async () => {
      if (!await confirm('Delete "' + s.name + '"?', 'Its items move to "Any shop".', 'Delete')) return;
      for (const i of S.where('grocery', (x) => x.shop === s.uid)) S.put('grocery', { uid: i.uid, shop: '' });
      for (const u of S.where('grocery_history', (x) => x.shop === s.uid)) S.put('grocery_history', { uid: u.uid, shop: '' });
      S.del('shops', s.uid);
      if (st.selShop === s.uid) { st.selShop = ''; S.saveSettings(); }
    } },
  ]);
}
function listMenu(l) {
  menu(l.name, [
    ...SP.shareItems('grocery', 'grocery_lists', l.uid, { people: true }),
    { label: 'Rename', action: async () => { const n = await prompt('Rename list', l.name); if (n) S.put('grocery_lists', { uid: l.uid, name: n }); } },
    l.uid === 'seed-list-groceries' ? null : { label: 'Delete list', danger: true, action: async () => {
      if (!await confirm('Delete "' + l.name + '" and its items?', null, 'Delete')) return;
      for (const i of S.where('grocery', (x) => x.list === l.uid)) S.del('grocery', i.uid);
      S.del('grocery_lists', l.uid);
    } },
  ]);
}

function importDialog() {
  const t = textarea('', { rows: 8, placeholder: 'Paste a list, e.g. from WhatsApp' });
  sheet({ title: 'Add items from a list', body: [h('p.muted', 'One item per line. Items already on the list are skipped.'), t],
    buttons: [{ label: 'Cancel' }, { label: 'Add', primary: true, action: () => { const n = importText(t.value); toast(n ? n + ' item(s) added' : 'Nothing new to add'); } }] });
}

export default {
  id: 'grocery',
  name: 'Groceries',
  icon: 'cart',
  tables: ['grocery', 'grocery_lists', 'shops', 'grocery_history', 'prices'],
  get busy() { const i = document.getElementById('grocery-input'); return !!(i && i.value && document.activeElement === i); },
  render,
  quick() { setTimeout(() => { const i = document.getElementById('grocery-input'); if (i) i.focus(); }, 50); },
  importText,
  menu: () => [
    { label: '⋯ This list: share, rename, delete', action: () => { const l = S.get('grocery_lists', curList()); if (l) listMenu(l); } },
    { label: '📈 Prices & price history', action: priceOverview },
    { label: 'Share list (WhatsApp, Messages…)', action: shareList },
    { label: 'Add items from a shared list', action: importDialog },
    { label: (st.askPrice ? '✓ ' : '') + 'Ask for the price when ticking off', action: () => { st.askPrice = !st.askPrice; S.saveSettings(); } },
    { label: 'Currency for prices: ' + cur(), action: async () => { const c = await prompt('Currency', cur()); if (c) { st.homeCurrency = c.toUpperCase(); S.saveSettings(); app.refresh(); } } },
    { label: 'Remove all items from this list', danger: true, action: async () => {
      const its = listItems();
      if (its.length && await confirm('Remove all ' + its.length + ' items?', null, 'Clear')) its.forEach((i) => S.del('grocery', i.uid));
    } },
  ],
};
