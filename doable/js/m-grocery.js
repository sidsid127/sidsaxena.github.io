// Groceries: several lists, shops, quantities, aisle order, prices on tick-off, "buy again", share & import.
import * as S from './store.js';
import * as L from './logic.js';
import { app } from './ctx.js';
import { h, chip, header, card, row, empty, pill, sheet, field, input, select, menu, prompt, confirm, toast, shareText, textarea } from './ui.js';

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
const spentToday = () => S.where('prices', (p) => p.day === L.today()).reduce((n, p) => n + p.cents, 0);
const cur = () => st.homeCurrency || 'EUR';

export function addItem(name, qty, shop, list) {
  const now = Date.now();
  S.put('grocery', { name, qty, shop, list, aisle: L.aisle(name), created: now });
  const gh = 'gh:' + S.asciiLower(name);
  const prev = S.get('grocery_history', gh);
  S.put('grocery_history', { uid: gh, name: prev ? prev.name : name, shop, list, uses: (prev ? prev.uses : 0) + 1, last_used: now });
}

export function importText(text) {
  const names = shops().map((s) => s.name);
  const on = new Set(listItems().map((i) => key(i.name)));
  let added = 0;
  for (const l of L.groceryParse(text, names)) {
    if (!l.name || on.has(key(l.name))) continue;
    const shop = shops().find((s) => s.name.toLowerCase() === l.shop.toLowerCase());
    addItem(l.name, l.qty, shop ? shop.uid : '', curList());
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
  const finish = (cents) => {
    if (cents > 0) S.put('prices', { name: it.name, shop: it.shop, cents, qty: it.qty, day: L.today() });
    lastRemoved = it;
    S.del('grocery', it.uid);
    toast('✓ ' + it.name + (cents > 0 ? ' · ' + L.money(cents, cur()) : ''), 'Undo', () => {
      S.put('grocery', { ...lastRemoved, uid: undefined });
    });
  };
  if (!st.askPrice) { finish(0); return; }
  const prices = latestPrices()[key(it.name)] || {};
  const last = prices[it.shop];
  const p = await prompt('Price of ' + it.name + (it.shop ? ' at ' + shopName(it.shop) : '') + '?', last ? (last.cents / 100).toFixed(2) : '',
    { placeholder: 'Leave empty to skip', ok: 'Done', type: 'text', message: 'Compare prices between shops later. Turn this off in ⋯.' });
  if (p === null) return; // cancelled: keep the item
  const cents = p ? L.parseAmount(p) : 0;
  finish(cents > 0 ? cents : 0);
}

function priceLine(name, prices) {
  const ps = Object.values(prices[key(name)] || {});
  if (!ps.length) return '';
  ps.sort((a, b) => a.cents - b.cents);
  return ps.map((p) => `${shopName(p.shop) || 'Any shop'} ${L.money(p.cents, cur())}`).join(' · ');
}

function render(view) {
  const page = h('div.page.flush');
  const listChips = h('div.chips');
  for (const l of lists()) {
    listChips.appendChild(chip(l.name, { selected: l.uid === curList(), onclick: () => { st.selList = l.uid; S.saveSettings(); app.refresh(); },
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
  const spent = spentToday();
  page.appendChild(h('div.stat', `${items.length} to buy` + (spent ? ` · spent today ${L.money(spent, cur())}` : '')));
  if (!items.length) page.appendChild(empty('The list is empty.\nAdd items below, or tap one under "Buy again".'));
  const byShop = new Map();
  for (const i of items) { const k = i.shop || ''; if (!byShop.has(k)) byShop.set(k, []); byShop.get(k).push(i); }
  const keys = [...byShop.keys()].sort((a, b) => (shopName(a) || '~').localeCompare(shopName(b) || '~'));
  for (const k of keys) {
    const s = S.get('shops', k);
    page.appendChild(header(s ? s.name : 'Any shop'));
    const group = byShop.get(k).sort((a, b) => L.aisleIndex(a.aisle || L.aisle(a.name)) - L.aisleIndex(b.aisle || L.aisle(b.name)) || a.name.localeCompare(b.name));
    let aisle = null;
    const c = card();
    for (const i of group) {
      const a = i.aisle || L.aisle(i.name);
      const pl = priceLine(i.name, prices);
      c.appendChild(row({ icon: h('button.tick', { type: 'button', 'aria-label': 'Bought', onclick: (e) => { e.stopPropagation(); tick(i); } }),
        title: (i.qty ? i.qty + ' × ' : '') + i.name, sub: [a !== aisle ? a : '', pl].filter(Boolean).join(' · '), onclick: () => editItem(i) }));
      aisle = a;
    }
    page.appendChild(c);
  }
  // Buy again
  const on = new Set(items.map((i) => key(i.name)));
  const usual = S.all('grocery_history').filter((u) => !on.has(key(u.name)) && (u.list || 'seed-list-groceries') === curList())
    .sort((a, b) => (b.uses - a.uses) || (b.last_used - a.last_used)).slice(0, 60);
  if (usual.length) {
    page.appendChild(header('Buy again'));
    page.appendChild(h('div.chips.wrap', usual.map((u) => chip(u.name, { color: S.colorCss((S.get('shops', u.shop) || {}).color) || undefined,
      onclick: () => addItem(u.name, '', u.shop, curList()), onlongpress: () => menu(u.name, [{ label: 'Forget this item', danger: true, action: () => S.del('grocery_history', u.uid) }]) }))));
  }
  view.appendChild(page);
  view.appendChild(inputBar());
}

function inputBar() {
  const inp = h('input.field#grocery-input', { type: 'text', placeholder: 'Add an item… e.g. "2 milk"', enterkeyhint: 'done', autocomplete: 'off', list: 'grocery-suggest' });
  const dl = h('datalist#grocery-suggest', S.all('grocery_history').sort((a, b) => b.uses - a.uses).slice(0, 200).map((u) => h('option', { value: u.name })));
  const add = () => {
    const v = inp.value.trim();
    if (!v) return;
    const [qty, name] = L.splitQuantity(v);
    if (listItems().some((i) => key(i.name) === key(name))) { toast(name + ' is already on the list'); inp.value = ''; return; }
    const hist = S.get('grocery_history', 'gh:' + S.asciiLower(name));
    addItem(name, qty, st.selShop || (hist ? hist.shop : ''), curList());
    inp.value = '';
    setTimeout(() => { const i = document.getElementById('grocery-input'); if (i) i.focus(); }, 30);
  };
  inp.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.isComposing) add(); });
  return h('div.inputbar', dl, inp, h('button.btn.primary', { type: 'button', onclick: add }, 'Add'));
}

function editItem(i) {
  const name = input(i.name);
  const qty = input(i.qty, { placeholder: 'e.g. 2, 500 g' });
  const shop = select([['', 'Any shop'], ...shops().map((s) => [s.uid, s.name])], i.shop || '');
  const aisle = select(L.AISLES, i.aisle || L.aisle(i.name));
  const list = select(lists().map((l) => [l.uid, l.name]), i.list || curList());
  const history = S.where('prices', (p) => key(p.name) === key(i.name)).sort((a, b) => (a.day < b.day ? 1 : -1)).slice(0, 8);
  sheet({ title: 'Edit item', body: [field('Item', name), field('Quantity', qty), field('Shop', shop), field('Aisle', aisle), field('List', list),
    history.length ? field('Price history', h('div', history.map((p) => h('div.row-sub', `${p.day} · ${shopName(p.shop) || 'Any shop'} · ${L.money(p.cents, cur())}${p.qty ? ' · ' + p.qty : ''}`)))) : null],
  buttons: [{ label: 'Delete', danger: true, action: () => S.del('grocery', i.uid) }, { label: 'Cancel' }, { label: 'Save', primary: true, action: () => {
    if (!name.value.trim()) return false;
    S.put('grocery', { uid: i.uid, name: name.value.trim(), qty: qty.value.trim(), shop: shop.value, aisle: aisle.value, list: list.value });
  } }] });
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
    { label: 'Share list (WhatsApp, Messages…)', action: shareList },
    { label: 'Add items from a shared list', action: importDialog },
    { label: (st.askPrice ? '✓ ' : '') + 'Ask for the price when ticking off', action: () => { st.askPrice = !st.askPrice; S.saveSettings(); } },
    { label: 'Currency for prices: ' + cur(), action: async () => { const c = await prompt('Currency', cur()); if (c) { st.homeCurrency = c.toUpperCase(); S.saveSettings(); app.refresh(); } } },
    { label: 'Clear this list', danger: true, action: async () => {
      const its = listItems();
      if (its.length && await confirm('Remove all ' + its.length + ' items?', null, 'Clear')) its.forEach((i) => S.del('grocery', i.uid));
    } },
  ],
};
