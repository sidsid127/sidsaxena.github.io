// Password vault storage. Logins are encrypted row by row with a random data key (AES-256-GCM). The data key
// sits in the device's protected storage (protect.js: sealed with the device key that only Face ID / Touch ID
// or the passphrase unlocks), and Passwords asks for Face ID / Touch ID each time it opens. Vaults from older
// versions were wrapped by a master password; that is asked once to move them over. With sync on, a copy of the
// logins sealed with the sync key lets sync run while the vault is closed.
import * as S from './store.js';
import { toB64, fromB64, sealJson, openJson } from './crypto.js';
import * as P from './protect.js';

const ROUNDS = 600000;
const enc = new TextEncoder();
let dataKey = null;          // CryptoKey while unlocked
let plain = new Map();       // uid → {m, d?, r} while unlocked
const listeners = new Set();
export const onVaultChange = (fn) => listeners.add(fn);
const changed = () => listeners.forEach((fn) => fn());

async function aes(raw) { return crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']); }
async function seal(key, raw) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  return { iv: toB64(iv), ct: toB64(new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, raw))) };
}
async function unseal(key, box) {
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: fromB64(box.iv) }, key, fromB64(box.ct)));
}
async function pwKey(password, salt) {
  const base = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', salt: fromB64(salt), iterations: ROUNDS, hash: 'SHA-256' }, base,
    { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

export const meta = () => S.kv.get('vault');
export async function exists() { return !!(await meta()); }
export const isOpen = () => !!dataKey;
/** An older vault that still needs its master password once. */
export async function needsMasterPassword() { const m = await meta(); return !!(m && m.pw); }

/**
 * Opens (or creates) the vault: asks for Touch ID / Face ID (or the passphrase) for the credentials key, which
 * unlocks the vault's own key. False if the user cancelled.
 */
export async function openProtected() {
  if (!S.isProtected()) throw new Error('not protected');
  const sk = await P.openSecrets();
  if (!sk) return false;
  const key = await aes(sk);
  const m = await meta();
  let raw;
  if (m && m.sk) raw = await unseal(key, m.sk);
  else {
    raw = m && m.raw ? fromB64(m.raw) : crypto.getRandomValues(new Uint8Array(32)); // first version kept it unsealed
    await S.kv.set('vault', { sk: await seal(key, raw) });
  }
  await openWith(raw);
  return true;
}

async function openWith(raw) {
  dataKey = await aes(raw);
  plain = new Map();
  for (const [uid, e] of S.data.logins) {
    if (e.d) { plain.set(uid, { m: e.m, d: 1 }); continue; }
    try { plain.set(uid, { m: e.m, r: await openJson(dataKey, fromB64(e.x)) }); } catch (err) { /* unreadable row: skip */ }
  }
  if (syncKeyGetter) await mergeMirror();
  changed();
}

/** Last use of an old master password: from now on the vault opens with Touch ID / Face ID. False if cancelled. */
export async function convertMasterPassword(password) {
  const m = await meta();
  const raw = await unseal(await pwKey(password, m.pw.salt), m.pw); // throws on a wrong password
  const sk = await P.openSecrets();
  if (!sk) return false;
  await S.kv.set('vault', { sk: await seal(await aes(sk), raw) });
  await openWith(raw);
  return true;
}

export function lock() {
  dataKey = null;
  plain = new Map();
  changed();
}

export function logins() {
  const out = [];
  for (const [uid, e] of plain) if (!e.d) out.push({ uid, ...e.r });
  return out.sort((a, b) => a.title.localeCompare(b.title));
}

async function store(uid, entry) {
  plain.set(uid, entry);
  const stored = entry.d ? { m: entry.m, d: 1 } : { m: entry.m, x: toB64(await sealJson(dataKey, entry.r)) };
  S.applyRemote('logins', uid, stored);
}

export async function putLogin(l) {
  const uid = l.uid || crypto.randomUUID();
  const r = { ...S.defaults('logins') };
  for (const k of Object.keys(r)) if (k in l) r[k] = l[k];
  r.modified = Date.now();
  await store(uid, { m: S.stamp(), r });
  await writeMirror();
  await S.flush(); // saved right away: credentials matter
  changed();
  return uid;
}
export async function deleteLogin(uid) {
  await store(uid, { m: S.stamp(), d: 1 });
  await writeMirror();
  await S.flush();
  changed();
}

// ------------------------------------------------------------------ sync bridge

let syncKeyGetter = null;  // () => Promise<CryptoKey|null>, set by sync.js
export function setSyncKey(fn) { syncKeyGetter = fn; }

/** Plain entries for the sync document: from memory when unlocked, else from the mirror. */
export async function syncEntries() {
  if (dataKey) return Object.fromEntries(plain);
  const key = syncKeyGetter && await syncKeyGetter();
  const m = key && await S.kv.get('loginsMirror');
  if (!m) return null; // unknown: keep whatever the remote has
  try { return await openJson(key, mirrorBytes(m)); } catch (e) { return null; }
}

/** Merged entries from sync. */
export async function syncApply(entries) {
  const key = syncKeyGetter && await syncKeyGetter();
  if (key) await S.kv.set('loginsMirror', toB64(await sealJson(key, entries)));
  if (dataKey) {
    let any = false;
    for (const [uid, e] of Object.entries(entries)) {
      const cur = plain.get(uid);
      if (!cur || e.m > cur.m) { await store(uid, e); any = true; }
    }
    if (any) changed();
  }
}

const mirrorBytes = (m) => (typeof m === 'string' ? fromB64(m) : m); // older versions stored the bytes

async function writeMirror() {
  const key = syncKeyGetter && await syncKeyGetter();
  if (key && dataKey) await S.kv.set('loginsMirror', toB64(await sealJson(key, Object.fromEntries(plain))));
}

/** On unlock: take in what sync received while the vault was locked. */
async function mergeMirror() {
  const key = await syncKeyGetter();
  const m = key && await S.kv.get('loginsMirror');
  if (m) {
    try {
      const entries = await openJson(key, mirrorBytes(m));
      for (const [uid, e] of Object.entries(entries)) {
        const cur = plain.get(uid);
        if (!cur || e.m > cur.m) await store(uid, e);
      }
    } catch (e) { /* sync key changed: ignore */ }
  }
  await writeMirror();
}

export function generate(len = 18) {
  const chars = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#$%&*?-_';
  return Array.from(crypto.getRandomValues(new Uint32Array(len)), (x) => chars[x % chars.length]).join('');
}
