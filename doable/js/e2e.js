// End-to-end encryption for households and sharing (format in SHARING.md, mirrored by Android's E2e.java).
//  - Each person has a sharing key pair (ECDH P-256). The public half is on Doable's server; the private half stays on
//    their devices and in their own Google Drive (hidden app folder), so all their devices share it.
//  - Each household / share has a random space key (AES-256). It is wrapped for every member's public key, and for
//    the secret in an invite link (the secret is only in the link, never sent to the server).
//  - Shared rows are encrypted with the space key; the server only ever sees ciphertext.
import * as S from './store.js';
import * as Sync from './sync.js';
import { toB64, fromB64, randomBytes } from './crypto.js';

const enc = new TextEncoder();
const dec = new TextDecoder();
const ID_FILE = 'doable.identity';
const EC = { name: 'ECDH', namedCurve: 'P-256' };

let identity = null; // {pub: base64 raw, priv: CryptoKey, pubKey: CryptoKey}

const cat = (...parts) => { const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0)); let o = 0; for (const p of parts) { out.set(p, o); o += p.length; } return out; };
const b64url = (b) => toB64(b).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const fromB64url = (s) => fromB64(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4));
export const sha256hex = async (s) => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(s))), (b) => b.toString(16).padStart(2, '0')).join('');
/** Short fingerprint of a public key: tells whether a wrap was made for the key a member has now. */
export const keyId = async (pub) => (await sha256hex('doable-key:' + pub)).slice(0, 16);

async function hkdf(ikm, salt, info) {
  const base = await crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt, info: enc.encode(info) }, base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}
const gcm = async (key, iv, data, aad) => new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: aad }, key, data));
const ungcm = async (key, iv, data, aad) => new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv, additionalData: aad }, key, data));

// ------------------------------------------------------------------ my key pair

async function fromStored(o) {
  const priv = await crypto.subtle.importKey('pkcs8', fromB64(o.priv), EC, false, ['deriveBits']);
  return { pub: o.pub, priv };
}
async function generate() {
  const kp = await crypto.subtle.generateKey(EC, true, ['deriveBits']);
  const pub = toB64(new Uint8Array(await crypto.subtle.exportKey('raw', kp.publicKey)));
  const priv = toB64(new Uint8Array(await crypto.subtle.exportKey('pkcs8', kp.privateKey)));
  return { v: 1, pub, priv };
}

/**
 * My sharing key pair: from this device, else from my Google Drive, else a new one (saved to Drive for my other
 * devices). Returns {pub} or throws when Google Drive can't be reached for the first time.
 */
export async function myKeys() {
  if (identity) return identity;
  let o = await S.kv.get('identity');
  if (!o) {
    const bytes = await Sync.readDriveFile(ID_FILE); // throws when signed out of Google / offline
    if (bytes) o = JSON.parse(dec.decode(bytes));
    else {
      o = await generate();
      await Sync.writeDriveFile(ID_FILE, enc.encode(JSON.stringify(o)));
    }
    await S.kv.set('identity', o);
  }
  identity = await fromStored(o);
  return identity;
}
export const hasKeys = async () => !!(identity || await S.kv.get('identity'));

// ------------------------------------------------------------------ space keys

export const newSpaceKey = () => randomBytes(32);
export const spaceKey = (raw) => crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);

/** Space key → only the owner of `pub` can open it: ephemeral ECDH, HKDF, AES-GCM. */
export async function wrapFor(pub, raw) {
  const their = await crypto.subtle.importKey('raw', fromB64(pub), EC, false, []);
  const eph = await crypto.subtle.generateKey(EC, true, ['deriveBits']);
  const ephPub = new Uint8Array(await crypto.subtle.exportKey('raw', eph.publicKey));
  const shared = new Uint8Array(await crypto.subtle.deriveBits({ name: 'ECDH', public: their }, eph.privateKey, 256));
  const key = await hkdf(shared, ephPub, 'doable-wrap-v1');
  const iv = randomBytes(12);
  return toB64(cat(ephPub, iv, await gcm(key, iv, raw, fromB64(pub))));
}
export async function unwrap(blob) {
  const me = await myKeys();
  const b = fromB64(blob);
  const ephPub = b.slice(0, 65);
  const eph = await crypto.subtle.importKey('raw', ephPub, EC, false, []);
  const shared = new Uint8Array(await crypto.subtle.deriveBits({ name: 'ECDH', public: eph }, me.priv, 256));
  const key = await hkdf(shared, ephPub, 'doable-wrap-v1');
  return ungcm(key, b.slice(65, 77), b.slice(77), fromB64(me.pub));
}

/** Invite link secret: {token (goes in the link), hash (for the server), encKey (space key locked with the token)}. */
export async function linkLock(raw) {
  const secret = randomBytes(16);
  const token = b64url(secret);
  const key = await hkdf(secret, new Uint8Array(32), 'doable-invite-v1');
  const iv = randomBytes(12);
  return { token, hash: await linkHash(token), encKey: toB64(cat(iv, await gcm(key, iv, raw, enc.encode('doable-invite')))) };
}
export const linkHash = (token) => sha256hex('doable-invite:' + token);
export async function linkUnlock(token, encKey) {
  const key = await hkdf(fromB64url(token), new Uint8Array(32), 'doable-invite-v1');
  const b = fromB64(encKey);
  return ungcm(key, b.slice(0, 12), b.slice(12), enc.encode('doable-invite'));
}

// ------------------------------------------------------------------ rows

const rowAad = (space, tbl, uid) => enc.encode(space + '/' + tbl + '/' + uid);
export async function sealRow(key, space, tbl, uid, entry) {
  const iv = randomBytes(12);
  return toB64(cat(iv, await gcm(key, iv, enc.encode(JSON.stringify(entry)), rowAad(space, tbl, uid))));
}
export async function openRow(key, space, tbl, uid, data) {
  const b = fromB64(data);
  return JSON.parse(dec.decode(await ungcm(key, b.slice(0, 12), b.slice(12), rowAad(space, tbl, uid))));
}

/** For tests and Android cross-checks. */
export const _test = { fromStored, generate, setIdentity: async (o) => { identity = await fromStored(o); }, b64url, fromB64url };
