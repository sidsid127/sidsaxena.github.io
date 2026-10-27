// Encryption for sync (format in SYNC.md) and for the password vault. WebCrypto only.

const enc = new TextEncoder();
const dec = new TextDecoder();
export const DOC_MAGIC = 'DOABLESYNC1';
export const FILE_MAGIC = 'DOABLEFILE1';
export const SYNC_ROUNDS = 210000;

export const randomBytes = (n) => crypto.getRandomValues(new Uint8Array(n));

export async function deriveKey(passphrase, salt, iterations, extractable = false) {
  const base = await crypto.subtle.importKey('raw', enc.encode(passphrase), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, base,
    { name: 'AES-GCM', length: 256 }, extractable, ['encrypt', 'decrypt']);
}

function concat(...parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}
const u32 = (n) => new Uint8Array([n >>> 24, (n >>> 16) & 255, (n >>> 8) & 255, n & 255]);

/** Header of a sync document: {salt, iterations} or null if it isn't one. */
export function docHeader(bytes) {
  if (bytes.length < 11 + 16 + 4 + 12 + 16 || dec.decode(bytes.slice(0, 11)) !== DOC_MAGIC) return null;
  const v = new DataView(bytes.buffer, bytes.byteOffset);
  return { salt: bytes.slice(11, 27), iterations: v.getUint32(27) };
}

export async function encryptDoc(key, salt, iterations, text) {
  const iv = randomBytes(12);
  const magic = enc.encode(DOC_MAGIC);
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: magic }, key, enc.encode(text)));
  return concat(magic, salt, u32(iterations), iv, ct);
}

/** Throws on a wrong key (GCM tag mismatch). */
export async function decryptDoc(key, bytes) {
  const iv = bytes.slice(31, 43);
  const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv, additionalData: enc.encode(DOC_MAGIC) }, key, bytes.slice(43));
  return dec.decode(pt);
}

export async function encryptFile(key, name, bytes) {
  const iv = randomBytes(12);
  const magic = enc.encode(FILE_MAGIC);
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: concat(magic, enc.encode(name)) }, key, bytes));
  return concat(magic, iv, ct);
}
export async function decryptFile(key, name, bytes) {
  if (dec.decode(bytes.slice(0, 11)) !== FILE_MAGIC) throw new Error('not a Doable file');
  const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: bytes.slice(11, 23), additionalData: concat(enc.encode(FILE_MAGIC), enc.encode(name)) },
    key, bytes.slice(23));
  return new Uint8Array(pt);
}

// ------------------------------------------------------------------ small helpers for local secrets

export async function sealJson(key, obj) {
  const iv = randomBytes(12);
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, enc.encode(JSON.stringify(obj))));
  return concat(iv, ct);
}
export async function openJson(key, bytes) {
  const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: bytes.slice(0, 12) }, key, bytes.slice(12));
  return JSON.parse(dec.decode(pt));
}
export async function sealBytes(key, bytes) {
  const iv = randomBytes(12);
  return concat(iv, new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, bytes)));
}
export async function openBytes(key, bytes) {
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: bytes.slice(0, 12) }, key, bytes.slice(12)));
}
export const toB64 = (bytes) => btoa(Array.from(bytes, (b) => String.fromCharCode(b)).join(''));
export const fromB64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
