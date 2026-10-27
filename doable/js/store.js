// Local storage for the web app: every row lives in IndexedDB in exactly the shape the sync document uses
// (see SYNC.md): table → uid → {m, d?, r}. The whole data set is also kept in memory for instant rendering.
// With device protection on (protect.js) rows, files and the secrets in kv are stored encrypted with the device
// key, which only Face ID / Touch ID (or the passphrase) can unlock.
import { sealJson, openJson, sealBytes, openBytes, toB64 } from './crypto.js';

export const REF = (table) => ({ ref: table });
const DAY = 'day';

/** Columns and their defaults. REF() = reference to another row's uid, DAY = "YYYY-MM-DD". */
export const SCHEMA = {
  categories: { name: '', parent: REF('categories'), color: 0 },
  tasks: { title: '', done: 0, due: DAY, created: 0, done_at: 0, category: REF('categories'), priority: 0, position: 0, time: -1,
    repeat: '', notes: '', snooze: 0 },
  subtasks: { task: REF('tasks'), text: '', done: 0, position: 0 },
  log: { text: '', ts: 0 },
  notes: { body: '', pinned: 0, created: 0, modified: 0, color: 0, folder: '', locked: 0, deleted: 0 },
  shops: { name: '', color: 0 },
  grocery_lists: { name: '' },
  grocery: { name: '', shop: REF('shops'), done: 0, created: 0, qty: '', list: REF('grocery_lists'), aisle: '' },
  grocery_history: { name: '', shop: REF('shops'), uses: 0, last_used: 0, list: REF('grocery_lists') },
  prices: { name: '', shop: REF('shops'), cents: 0, qty: '', day: DAY },
  diary: { day: DAY, mood: 0, text: '', highs: '', lows: '', modified: 0, place: '', lat: 0, lon: 0, weather: '', temp: '' },
  trips: { name: '', kind: 0, destination: '', start: DAY, finish: DAY, currency: 'EUR', created: 0, budget: 0, rates: '',
    pd_full: 2800, pd_partial: 1400, km_rate: 30, per_diem: 0 },
  expenses: { trip: REF('trips'), day: DAY, category: '', cents: 0, currency: 'EUR', merchant: '', attendees: '', payment: '', note: '',
    submitted: 0, created: 0, km: 0 },
  trip_items: { trip: REF('trips'), kind: '', subtype: '', title: '', body: '', day: DAY, done: 0, created: 0 },
  attachments: { owner: '', owner_id: '', file: '', name: '', created: 0 },
  habits: { name: '', emoji: '', quit: 0, target: 1, days: 127, remind: '', color: 0, archived: 0, created: DAY },
  habit_log: { habit: REF('habits'), day: DAY, count: 0 },
  wallet: { type: 'Loyalty card', title: '', number: '', holder: '', expiry: DAY, barcode: '', notes: '', created: 0 },
  dates: { title: '', kind: 'Birthday', month: 1, day: 1, year: 0, yearly: 1, remind_days: 1, notes: '', created: 0 },
  checklists: { title: '', trip: REF('trips'), template: 0, created: 0 },
  checklist_items: { list: REF('checklists'), text: '', done: 0, position: 0 },
  logins: { type: 'Login', title: '', url: '', user: '', password: '', number: '', holder: '', expiry: '', cvv: '', pin: '', notes: '', modified: 0 },
};
export const TABLES = Object.keys(SCHEMA);
export const OWNER_TABLE = { note: 'notes', diary: 'diary', expense: 'expenses', trip_item: 'trip_items', wallet: 'wallet' };

export function defaults(table) {
  const out = {};
  for (const [k, v] of Object.entries(SCHEMA[table])) out[k] = v === DAY ? '' : v && v.ref ? '' : v;
  return out;
}

export const newUid = () => Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('');
/** SQLite's lower(): ASCII only, so uids made from names match Android. */
export const asciiLower = (s) => s.replace(/[A-Z]/g, (c) => c.toLowerCase());

/** uid for rows that are unique by content (SYNC.md). */
export function keyedUid(table, r) {
  if (table === 'diary') return 'diary:' + r.day;
  if (table === 'habit_log') return 'hl:' + r.habit + ':' + r.day;
  if (table === 'grocery_history') return 'gh:' + asciiLower(r.name);
  return null;
}

// ------------------------------------------------------------------ colours (Android ARGB ints)

export const PALETTE = ['#3F2CB8', '#1E9C9A', '#E5484D', '#F2A33A', '#8E4EC6', '#3D8BFD', '#2F9E44', '#D6409F'];
export const colorInt = (css) => (0xFF000000 | parseInt(css.slice(1), 16)) | 0;
export const colorCss = (int) => (int ? '#' + ((int >>> 0) & 0xFFFFFF).toString(16).padStart(6, '0') : '');

// ------------------------------------------------------------------ IndexedDB

let idb;
let dk = null; // device key (CryptoKey) while protection is on
export const isProtected = () => !!dk;
function open() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('doable', 1);
    req.onupgradeneeded = () => {
      const d = req.result;
      d.createObjectStore('rows');
      d.createObjectStore('files');
      d.createObjectStore('kv');
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}
const tx = (store, mode, fn) => new Promise((resolve, reject) => {
  const t = idb.transaction(store, mode);
  const result = fn(t.objectStore(store));
  t.oncomplete = () => resolve(result && 'result' in result ? result.result : result);
  t.onerror = () => reject(t.error);
  t.onabort = () => reject(t.error);
});

export async function openDb() {
  if (!idb) idb = await open();
}

// Secrets in kv (values must be plain JSON); sealed with the device key when protection is on.
const SECRET_KV = ['syncKey', 'loginsMirror', 'vault', 'googleRefresh'];
const rawKv = {
  get: (k) => tx('kv', 'readonly', (s) => s.get(k)),
  set: (k, v) => tx('kv', 'readwrite', (s) => { s.put(v, k); }),
};
export const kv = {
  async get(k) {
    const v = await rawKv.get(k);
    if (v && v.sealed) return dk ? openJson(dk, v.sealed) : null;
    return v;
  },
  async set(k, v) {
    return rawKv.set(k, dk && SECRET_KV.includes(k) ? { sealed: await sealJson(dk, v) } : v);
  },
  del: (k) => tx('kv', 'readwrite', (s) => { s.delete(k); }),
};
export const files = {
  async get(name) {
    const v = await tx('files', 'readonly', (s) => s.get(name));
    if (v && v.sealed) return dk ? new Blob([await openBytes(dk, v.sealed)], { type: v.type }) : null;
    return v;
  },
  async put(name, blob) {
    const v = dk ? { sealed: await sealBytes(dk, new Uint8Array(await blob.arrayBuffer())), type: blob.type } : blob;
    return tx('files', 'readwrite', (s) => { s.put(v, name); });
  },
  del: (name) => tx('files', 'readwrite', (s) => { s.delete(name); }),
  keys: () => tx('files', 'readonly', (s) => s.getAllKeys()),
};

// ------------------------------------------------------------------ in-memory data

/** data[table] = Map(uid → {m, d?, r}) */
export const data = {};
for (const t of TABLES) data[t] = new Map();

const listeners = new Set();
export const onChange = (fn) => listeners.add(fn);
let notifyTimer = 0;
const changedTables = new Set();
function changed(table) {
  changedTables.add(table);
  clearTimeout(notifyTimer);
  notifyTimer = setTimeout(() => {
    const t = new Set(changedTables);
    changedTables.clear();
    for (const fn of listeners) fn(t);
  }, 0);
}

let pending = new Map(); // key → value to write
let flushTimer = 0;
function persist(table, uid) {
  pending.set(table + '/' + uid, data[table].get(uid));
  clearTimeout(flushTimer);
  flushTimer = setTimeout(flush, 30);
}
let flushing = Promise.resolve();
/** Writes pending rows (in order: one write after the other, since sealing takes a moment). */
export function flush() {
  const run = flushing.then(async () => {
    if (!pending.size) return;
    const batch = pending;
    pending = new Map();
    const out = [];
    for (const [k, v] of batch) out.push([k, dk && v ? { sealed: await sealJson(dk, v) } : v]);
    await tx('rows', 'readwrite', (s) => { for (const [k, v] of out) s.put(v, k); });
  });
  flushing = run.catch(() => {});
  return run;
}
addEventListener('pagehide', () => { flush(); });

let lastM = 0;
/** The latest stamp given to a change made on this device (sync compares it to see whether there is something new). */
export const localStamp = () => lastM;
/** Strictly increasing modification time, so two quick edits never share a timestamp. */
export function stamp() {
  lastM = Math.max(Date.now(), lastM + 1);
  return lastM;
}

/** Live rows of a table as plain objects {uid, ...columns}. */
export function all(table) {
  const out = [];
  for (const [uid, e] of data[table]) if (!e.d) out.push({ uid, ...e.r });
  return out;
}
export function get(table, uid) {
  const e = uid && data[table].get(uid);
  return e && !e.d ? { uid, ...e.r } : null;
}
export function where(table, pred) {
  return all(table).filter(pred);
}

/** Inserts or updates; returns the uid. Unknown columns are dropped, missing ones get defaults. */
export function put(table, row) {
  const base = defaults(table);
  const prev = row.uid ? data[table].get(row.uid) : null;
  const r = {};
  for (const k of Object.keys(base)) r[k] = k in row ? row[k] : prev && !prev.d && k in prev.r ? prev.r[k] : base[k];
  const uid = row.uid || keyedUid(table, r) || newUid();
  data[table].set(uid, { m: stamp(), r });
  persist(table, uid);
  changed(table);
  return uid;
}
export function del(table, uid) {
  if (!data[table].has(uid)) return;
  data[table].set(uid, { m: stamp(), d: 1 });
  persist(table, uid);
  changed(table);
}

/** Writes merged rows from sync (keeps their m). */
export function applyRemote(table, uid, entry) {
  data[table].set(uid, entry);
  persist(table, uid);
  changed(table);
}

// ------------------------------------------------------------------ settings (per device)

const SETTINGS_KEY = 'doable.settings';
export const settings = Object.assign({
  module: 'today', theme: 'system', hidden: [], smart: '', selTop: '', selSub: '', showDone: false, selList: 'seed-list-groceries',
  selShop: '', askPrice: true, noteFolder: '', lock: null, walletLock: true, homeCurrency: 'EUR',
}, safeJson(localStorage.getItem(SETTINGS_KEY)));
export const saveSettings = () => localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
function safeJson(s) { try { return JSON.parse(s) || {}; } catch (e) { return {}; } }

// ------------------------------------------------------------------ start-up

const TEMPLATES = [
  ['business', 'Business trip packing', ['Laptop + charger', 'Phone charger', 'Power bank', 'Travel adapter', 'Headphones', 'Company badge',
    'ID / passport', 'Business cards', 'Suit / blazer', 'Shirts', 'Smart shoes', 'Toiletry bag', 'Medication', 'Notebook + pen',
    'Hotel booking', 'Train / flight tickets', 'Corporate credit card']],
  ['holiday', 'Holiday packing', ['Passport / ID', 'Tickets & bookings', 'Credit card + some cash', 'Phone + charger', 'Travel adapter',
    'Sunglasses', 'Sunscreen', 'Swimwear', 'T-shirts', 'Shorts / trousers', 'Underwear & socks', 'Comfortable shoes', 'Toiletries',
    'Medication & first aid', 'Book / e-reader', 'Travel insurance details', 'House keys left with someone']],
  ['weekend', 'Weekend away', ['Phone + charger', 'Toothbrush & toiletries', 'Change of clothes', 'Pyjamas', 'Wallet & ID', 'Snacks for the road']],
  ['leaving', 'Before leaving home', ['Windows closed', 'Stove & oven off', 'Heating turned down', 'Plants watered', 'Bins emptied',
    'Fridge checked', 'Lights off', 'Door locked']],
];

/** Starting data with fixed uids and m = 1, so it merges with the same rows from other devices. */
function seed() {
  const s = (table, uid, r) => {
    if (data[table].has(uid)) return;
    data[table].set(uid, { m: 1, r: { ...defaults(table), ...r } });
    persist(table, uid);
  };
  s('categories', 'seed-cat-private', { name: 'Private', color: colorInt(PALETTE[1]) });
  s('categories', 'seed-cat-work', { name: 'Work', color: colorInt(PALETTE[0]) });
  s('categories', 'seed-cat-accenture', { name: 'Accenture', parent: 'seed-cat-work', color: colorInt(PALETTE[4]) });
  s('shops', 'seed-shop-rewe', { name: 'Rewe', color: colorInt('#CC071E') });
  s('shops', 'seed-shop-lidl', { name: 'Lidl', color: colorInt('#0050AA') });
  s('shops', 'seed-shop-aldi', { name: 'Aldi', color: colorInt('#F28C00') });
  s('grocery_lists', 'seed-list-groceries', { name: 'Groceries' });
  s('grocery_lists', 'seed-list-drugstore', { name: 'Drugstore' });
  s('grocery_lists', 'seed-list-hardware', { name: 'Hardware store' });
  for (const [key, title, items] of TEMPLATES) {
    s('checklists', 'seed-tpl-' + key, { title, template: 1 });
    items.forEach((text, i) => s('checklist_items', `seed-tpl-${key}-${i + 1}`, { list: 'seed-tpl-' + key, text, position: i + 1 }));
  }
}

/** Brings over data from the first web version (stored under "daybook.v1" on the same site). */
function migrateOld() {
  let old;
  try { old = JSON.parse(localStorage.getItem('daybook.v1')); } catch (e) { return; }
  if (!old || !Array.isArray(old.tasks) || localStorage.getItem('doable.migrated')) return;
  const ids = {};
  const seedCat = { Private: 'seed-cat-private', Work: 'seed-cat-work', Accenture: 'seed-cat-accenture' };
  for (const k of old.categories || []) {
    ids['c' + k.id] = seedCat[k.name] || put('categories', { name: k.name, color: colorInt(k.color || PALETTE[0]) });
  }
  for (const k of old.categories || []) {
    if (k.parent && !seedCat[k.name]) put('categories', { uid: ids['c' + k.id], parent: ids['c' + k.parent] || '' });
  }
  for (const t of old.tasks) {
    put('tasks', { title: t.title, done: t.done ? 1 : 0, done_at: t.doneAt || 0, due: t.due || '', created: t.created || Date.now(),
      category: ids['c' + t.category] || '', priority: t.priority || 0 });
  }
  for (const n of old.notes || []) put('notes', { body: n.body, pinned: n.pinned ? 1 : 0, created: n.created, modified: n.modified });
  const seedShop = { Rewe: 'seed-shop-rewe', Lidl: 'seed-shop-lidl', Aldi: 'seed-shop-aldi' };
  for (const s of old.shops || []) ids['s' + s.id] = seedShop[s.name] || put('shops', { name: s.name, color: colorInt(s.color || PALETTE[0]) });
  for (const i of old.items || []) {
    if (!i.done) put('grocery', { name: i.name, shop: ids['s' + i.shop] || '', created: i.created, list: 'seed-list-groceries' });
  }
  for (const u of old.usuals || []) put('grocery_history', { name: u.name, shop: ids['s' + u.shop] || '', uses: u.uses, last_used: u.last, list: 'seed-list-groceries' });
  for (const e of old.log || []) put('log', { text: e.text, ts: e.ts });
  localStorage.setItem('doable.migrated', '1');
}

/** Loads everything; with device protection on, `key` is the unlocked device key. */
export async function init(key = null) {
  await openDb();
  dk = key;
  const keys = await tx('rows', 'readonly', (s) => s.getAllKeys());
  const stored = await tx('rows', 'readonly', (s) => s.getAll());
  const values = await Promise.all(stored.map((v) => (v && v.sealed ? (dk ? openJson(dk, v.sealed).catch(() => undefined) : undefined) : v)));
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i];
    const slash = k.indexOf('/');
    const table = k.slice(0, slash);
    if (!data[table] || values[i] === undefined) continue;
    if (dk && !(stored[i] && stored[i].sealed)) pending.set(k, values[i]); // written before protection was finished: seal it now
    data[table].set(k.slice(slash + 1), values[i]);
  }
  seed();
  migrateOld();
  if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
  await flush();
}

/** Turns on device protection: from now on everything is written sealed with `key`; seals what is stored. */
export async function protectAll(key) {
  dk = key;
  for (const t of TABLES) for (const uid of data[t].keys()) pending.set(t + '/' + uid, data[t].get(uid));
  await flush();
  for (const name of await files.keys()) {
    const v = await tx('files', 'readonly', (s) => s.get(name));
    if (v instanceof Blob) await files.put(name, v);
  }
  for (const k of SECRET_KV) {
    let v = await rawKv.get(k);
    if (!v || v.sealed) continue;
    if (v instanceof Uint8Array) v = toB64(v);
    if (k === 'syncKey' && v.key) continue; // older CryptoKey form: replaced by protect.js when the passphrase is known
    await kv.set(k, v);
  }
}

/** Removes rows and attachment files that belong to a deleted row (cascade, like Android). */
export function cascade(table, uid) {
  const kids = {
    tasks: [['subtasks', 'task']],
    trips: [['expenses', 'trip'], ['trip_items', 'trip'], ['checklists', 'trip']],
    checklists: [['checklist_items', 'list']],
    habits: [['habit_log', 'habit']],
  }[table] || [];
  for (const [t, col] of kids) for (const r of where(t, (x) => x[col] === uid)) { cascade(t, r.uid); del(t, r.uid); }
  const owner = Object.keys(OWNER_TABLE).find((k) => OWNER_TABLE[k] === table);
  if (owner) for (const a of where('attachments', (x) => x.owner === owner && x.owner_id === uid)) del('attachments', a.uid);
}
export function remove(table, uid) {
  cascade(table, uid);
  del(table, uid);
}
