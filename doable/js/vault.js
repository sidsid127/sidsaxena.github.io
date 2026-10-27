// Password vault storage. Logins are encrypted row by row with a random data key (AES-256-GCM); the data key
// is kept wrapped by your master password (PBKDF2, 600,000 rounds) and optionally by Face ID / Touch ID
// (passkey PRF). With sync on, a copy of the logins sealed with the sync key lets sync run while the vault
// is locked (the synced document itself is end-to-end encrypted).
import * as S from './store.js';
import { toB64, fromB64, sealJson, openJson } from './crypto.js';
import { createPasskey, usePasskey } from './lock.js';

const ROUNDS = 600000;
const enc = new TextEncoder();
let dataKey = null;          // CryptoKey while unlocked
let plain = new Map();       // uid → {m, d?, r} while unlocked
const listeners = new Set();
export const onVaultChange = (fn) => listeners.add(fn);
const changed = () => listeners.forEach((fn) => fn());

async function aes(raw) { return crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']); }
async function seal(key, bytes) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  return { iv: toB64(iv), ct: toB64(new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, bytes))) };
}
async function unseal(key, box) {
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: fromB64(box.iv) }, key, fromB64(box.ct)));
}
async function pwKey(password, salt) {
  const base = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', salt: fromB64(salt), iterations: ROUNDS, hash: 'SHA-256' }, base,
    { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}
async function prfKey(secret) {
  const base = await crypto.subtle.importKey('raw', secret, 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(32), info: enc.encode('doable-vault') }, base,
    { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

export const meta = () => S.kv.get('vault');
export async function exists() { return !!(await meta()); }
export const isOpen = () => !!dataKey;

export async function create(password) {
  const raw = crypto.getRandomValues(new Uint8Array(32));
  const salt = toB64(crypto.getRandomValues(new Uint8Array(16)));
  await S.kv.set('vault', { pw: { salt, ...(await seal(await pwKey(password, salt), raw)) }, face: null });
  await openWith(raw);
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

export async function unlockPassword(password) {
  const m = await meta();
  const raw = await unseal(await pwKey(password, m.pw.salt), m.pw); // throws on a wrong password
  await openWith(raw);
}

async function prfSecret(credId, salt) {
  const res = await usePasskey(credId, { prf: { eval: { first: fromB64(salt) } } });
  const out = res.getClientExtensionResults().prf;
  if (!out || !out.results || !out.results.first) throw new Error('prf-unsupported');
  return out.results.first;
}
export async function unlockFace() {
  const m = await meta();
  const raw = await unseal(await prfKey(await prfSecret(m.face.cred, m.face.salt)), m.face);
  await openWith(raw);
}
export async function enableFace(password) {
  const m = await meta();
  const raw = await unseal(await pwKey(password, m.pw.salt), m.pw);
  const salt = toB64(crypto.getRandomValues(new Uint8Array(32)));
  const cred = await createPasskey('passwords', { prf: { eval: { first: fromB64(salt) } } });
  const ext = cred.getClientExtensionResults().prf;
  if (!ext || ext.enabled === false) throw new Error('prf-unsupported');
  const credId = toB64(new Uint8Array(cred.rawId));
  const secret = ext.results && ext.results.first ? ext.results.first : await prfSecret(credId, salt);
  m.face = { cred: credId, salt, ...(await seal(await prfKey(secret), raw)) };
  await S.kv.set('vault', m);
}
export async function changePassword(oldPw, newPw) {
  const m = await meta();
  const raw = await unseal(await pwKey(oldPw, m.pw.salt), m.pw);
  const salt = toB64(crypto.getRandomValues(new Uint8Array(16)));
  m.pw = { salt, ...(await seal(await pwKey(newPw, salt), raw)) };
  await S.kv.set('vault', m);
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
  changed();
  return uid;
}
export async function deleteLogin(uid) {
  await store(uid, { m: S.stamp(), d: 1 });
  await writeMirror();
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
  try { return await openJson(key, m); } catch (e) { return null; }
}

/** Merged entries from sync. */
export async function syncApply(entries) {
  const key = syncKeyGetter && await syncKeyGetter();
  if (key) await S.kv.set('loginsMirror', await sealJson(key, entries));
  if (dataKey) {
    let any = false;
    for (const [uid, e] of Object.entries(entries)) {
      const cur = plain.get(uid);
      if (!cur || e.m > cur.m) { await store(uid, e); any = true; }
    }
    if (any) changed();
  }
}

async function writeMirror() {
  const key = syncKeyGetter && await syncKeyGetter();
  if (key && dataKey) await S.kv.set('loginsMirror', await sealJson(key, Object.fromEntries(plain)));
}

/** On unlock: take in what sync received while the vault was locked. */
async function mergeMirror() {
  const key = await syncKeyGetter();
  const m = key && await S.kv.get('loginsMirror');
  if (m) {
    try {
      const entries = await openJson(key, m);
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
