// Device protection: everything Doable keeps on this device (tasks, notes, wallet, passwords, attachments, the sync
// key) is encrypted with a random device key. That key is unlocked with Face ID / Touch ID — a passkey with the
// PRF extension: the secure hardware hands out a secret only after the fingerprint/face check — or, as a fallback,
// with the passphrase. Someone who copies the browser's files (an administrator, a backup, malware without your
// fingerprint) only gets encrypted data. The fingerprint itself never leaves the device's secure hardware.
import * as S from './store.js';
import * as C from './crypto.js';
import { app } from './ctx.js';
import { createPasskey, usePasskey, faceIdAvailable } from './lock.js';
import { h, sheet, toast, confirm } from './ui.js';

const ROUNDS = 600000;
const LOCK_AFTER_MS = 60000;
const enc = new TextEncoder();
let meta = null; // kv 'protect': {v, cred, prfSalt, prf: {iv, ct}, pwSalt, pw: {iv, ct}}
let hiddenAt = 0;
let lockShown = false;

export async function load() {
  await S.openDb();
  meta = (await S.kv.get('protect')) || null;
  return meta;
}
export const isOn = () => !!meta;
export const usesFace = () => !!(meta && meta.cred);
app.protectOn = isOn;

// ------------------------------------------------------------------ keys

const aes = (raw) => crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
async function seal(key, raw) {
  const iv = C.randomBytes(12);
  return { iv: C.toB64(iv), ct: C.toB64(new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, raw))) };
}
async function unseal(key, box) {
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: C.fromB64(box.iv) }, key, C.fromB64(box.ct)));
}
async function faceKey(secret) {
  const base = await crypto.subtle.importKey('raw', secret, 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(32), info: enc.encode('doable-device') }, base,
    { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}
async function passKey(pw, salt) {
  const base = await crypto.subtle.importKey('raw', enc.encode(pw), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', hash: 'SHA-256', salt: C.fromB64(salt), iterations: ROUNDS }, base,
    { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}
async function prfSecret(cred, salt) {
  const res = await usePasskey(cred, { prf: { eval: { first: C.fromB64(salt) } } });
  const out = res.getClientExtensionResults().prf;
  if (!out || !out.results || !out.results.first) throw new Error('prf-unsupported');
  return new Uint8Array(out.results.first);
}

async function keyByFace() {
  return aes(await unseal(await faceKey(await prfSecret(meta.cred, meta.prfSalt)), meta.prf));
}
async function keyByPassphrase(pw) {
  return aes(await unseal(await passKey(pw, meta.pwSalt), meta.pw)); // throws on a wrong passphrase
}

// ------------------------------------------------------------------ lock screen

function passField() {
  return h('input.field', { type: 'password', placeholder: 'Passphrase', autocomplete: 'current-password',
    style: { maxWidth: '280px', background: 'rgba(255,255,255,.95)', color: '#222' } });
}

/**
 * Covers the app until the user proves it's them. With `needKey` (start-up) it resolves with the device key;
 * otherwise (coming back after a while) a Face ID / Touch ID check is enough.
 */
function showLock(needKey) {
  return new Promise((resolve) => {
    lockShown = true;
    document.querySelectorAll('.overlay').forEach((o) => o.remove());
    const msg = h('div#protect-msg', usesFace() ? 'Doable is locked' : 'Enter your passphrase to open Doable');
    const pw = passField();
    const form = h('form', { style: { display: usesFace() ? 'none' : 'flex', flexDirection: 'column', gap: '10px', alignItems: 'center' } },
      pw, h('button.lock-btn', { type: 'submit' }, 'Unlock'));
    const done = (key) => { el.remove(); lockShown = false; resolve(key); };
    const face = async () => {
      try {
        if (needKey) done(await keyByFace());
        else { await usePasskey(meta.cred); done(null); }
      } catch (e) {
        msg.textContent = 'Not recognised — try again or use your passphrase';
      }
    };
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      msg.textContent = 'One moment…';
      try { done(await keyByPassphrase(pw.value)); } catch (err) { msg.textContent = 'Wrong passphrase'; pw.value = ''; pw.focus(); }
    });
    const el = h('div#protect', h('img', { src: 'icons/icon-192.png', alt: '' }), msg,
      usesFace() ? h('button.lock-btn', { type: 'button', onclick: face }, '🔓 Unlock with Touch ID / Face ID') : null,
      form,
      usesFace() ? h('button.link-btn', { type: 'button', onclick: (e) => { form.style.display = 'flex'; e.target.remove(); pw.focus(); } },
        'Use passphrase instead') : null);
    document.body.appendChild(el);
    if (usesFace()) setTimeout(face, 250); else setTimeout(() => pw.focus(), 100);
  });
}

/** At start: null when protection is off, otherwise waits for Face ID / Touch ID (or the passphrase) and returns the device key. */
export async function unlockAtStart() {
  if (!await load()) return null;
  return showLock(true);
}

/** Relock after Doable was in the background for a minute. */
document.addEventListener('visibilitychange', () => {
  if (!meta) return;
  if (document.hidden) hiddenAt = Date.now();
  else if (hiddenAt && Date.now() - hiddenAt > LOCK_AFTER_MS && !lockShown) showLock(false);
});

/** Face ID / Touch ID (or the passphrase) before showing passwords, wallet or a locked note. True if confirmed. */
export async function verify() {
  if (!meta) return false;
  if (meta.cred) {
    try { await usePasskey(meta.cred); return true; } catch (e) { /* fall back to the passphrase */ }
  }
  const pw = await askPassphrase('Passphrase', 'Face ID / Touch ID didn\'t work. Enter your passphrase instead.', false);
  if (!pw) return false;
  try { await keyByPassphrase(pw); return true; } catch (e) { toast('Wrong passphrase'); return false; }
}
app.protectVerify = verify;

// ------------------------------------------------------------------ turning it on

function askPassphrase(title, message, twice) {
  return new Promise((resolve) => {
    const p1 = h('input.field', { type: 'password', placeholder: 'Passphrase', autocomplete: twice ? 'new-password' : 'current-password', autofocus: true });
    const p2 = twice ? h('input.field', { type: 'password', placeholder: 'Repeat passphrase', autocomplete: 'new-password' }) : null;
    let result = null;
    sheet({
      title, dismissable: false,
      body: [h('p.muted', message), p1, p2 ? h('div', { style: { height: '8px' } }) : null, p2],
      buttons: [{ label: 'Cancel' }, { label: 'OK', primary: true, action: () => {
        if (p1.value.length < 8) { toast('Use at least 8 characters'); return false; }
        if (p2 && p1.value !== p2.value) { toast('The passphrases don\'t match'); return false; }
        result = p1.value;
      } }],
      onClose: () => resolve(result),
    });
  });
}

/** True if `raw` is the same AES key as the stored sync key (also when that one can't be exported). */
async function sameSyncKey(raw, stored) {
  if (stored.raw) return C.toB64(raw) === stored.raw;
  const iv = new Uint8Array(12);
  const probe = new Uint8Array(16);
  const a = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await aes(raw), probe));
  const b = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, stored.key, probe));
  return C.toB64(a) === C.toB64(b);
}

/** Second Face ID / Touch ID right after creating the passkey (some browsers want a fresh tap for it). */
function finishWithTap(cred, salt) {
  return new Promise((resolve) => {
    let secret = null;
    sheet({
      title: 'One more Touch ID / Face ID', dismissable: false,
      body: h('p', 'Confirm once more to finish protecting Doable.'),
      buttons: [{ label: 'Cancel' }, { label: 'Continue', primary: true, action: async () => {
        try { secret = await prfSecret(cred, salt); } catch (e) { secret = null; }
      } }],
      onClose: () => resolve(secret),
    });
  });
}

/** Sets up protection: passphrase (the sync passphrase when sync is set up) + Face ID / Touch ID, then encrypts everything. */
export async function setup() {
  if (meta) return true;
  const stored = await S.kv.get('syncKey');
  const pw = await askPassphrase(stored ? 'Your sync passphrase' : 'Choose a passphrase',
    stored ? 'Doable will open with Touch ID / Face ID. Your sync passphrase is the backup way in, e.g. if Touch ID isn\'t available.'
      : 'Doable will open with Touch ID / Face ID. This passphrase (8+ characters) is the backup way in — write it down. '
        + 'If you turn on sync later, use the same one.', !stored);
  if (!pw) return false;
  let syncRaw = null;
  if (stored) {
    syncRaw = new Uint8Array(await crypto.subtle.exportKey('raw', await C.deriveKey(pw, C.fromB64(stored.salt), stored.iterations, true)));
    if (!await sameSyncKey(syncRaw, stored)) { toast('That isn\'t your sync passphrase'); return false; }
  }

  let cred = null, prfSalt = null, secret = null;
  if (await faceIdAvailable()) {
    try {
      prfSalt = C.toB64(C.randomBytes(32));
      const c = await createPasskey('device', { prf: { eval: { first: C.fromB64(prfSalt) } } });
      const ext = c.getClientExtensionResults().prf;
      if (ext && ext.enabled !== false) {
        cred = C.toB64(new Uint8Array(c.rawId));
        if (ext.results && ext.results.first) secret = new Uint8Array(ext.results.first);
        else {
          try { secret = await prfSecret(cred, prfSalt); } catch (e) { secret = await finishWithTap(cred, prfSalt); }
        }
        if (!secret) cred = null;
      }
    } catch (e) { cred = null; }
  }
  if (!cred && !await confirm('Touch ID / Face ID can\'t be used here',
    'This browser can\'t unlock Doable with Touch ID / Face ID (on a work computer this is sometimes switched off). '
    + 'Doable can still encrypt everything — you\'d type your passphrase each time you open it.', 'Use passphrase', false)) return false;

  const raw = C.randomBytes(32);
  const pwSalt = C.toB64(C.randomBytes(16));
  const m = { v: 1, cred, prfSalt: cred ? prfSalt : null, prf: cred ? await seal(await faceKey(secret), raw) : null,
    pwSalt, pw: await seal(await passKey(pw, pwSalt), raw) };
  await S.kv.set('protect', m);
  meta = m;
  if (syncRaw) await S.kv.set('syncKey', { raw: C.toB64(syncRaw), salt: stored.salt, iterations: stored.iterations });
  await S.protectAll(await aes(raw));
  if (S.settings.lock) { S.settings.lock = null; S.saveSettings(); } // the PIN lock is replaced by this
  toast(cred ? 'Protected ✓ Doable now opens with Touch ID / Face ID' : 'Protected ✓ Doable now opens with your passphrase', null, null, 5000);
  return true;
}

/** Shown where passwords / wallet need protection first. */
export function setupCard(what) {
  return h('div.page', h('div.empty', `🔐\nProtect ${what} with Touch ID / Face ID`),
    h('p.muted', { style: { textAlign: 'center', maxWidth: '420px', margin: '0 auto 16px' } },
      'Doable encrypts everything it keeps on this device and opens with your fingerprint or face. '
      + 'Anyone who copies this computer\'s files — including a company\'s device management — only gets unreadable data.'),
    h('button.btn.primary.block', { type: 'button', onclick: async () => { if (await setup()) app.refresh(); } }, 'Turn on protection'));
}
