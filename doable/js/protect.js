// Device protection. Everything Doable keeps on this device (tasks, notes, wallet, attachments, the sync key) is
// encrypted with a random device key (DK). Credentials get a second key (SK) on top, which only Touch ID / Face ID
// or the passphrase can open:
//  - Touch ID / Face ID is a passkey. Where the browser supports its PRF extension, the secure hardware hands out a
//    secret only after the fingerprint check, and that secret unlocks the keys. Where it doesn't, the passkey is a
//    fingerprint check and the keys are kept wrapped by a key the browser keeps for this site.
//  - The passphrase (the sync passphrase when sync is on) always works as the way back in.
//  - "Lock Doable when it opens" is the user's choice. Off: the device key is kept wrapped by the browser's key, so
//    Doable opens straight away (data still encrypted on disk); Credentials and Wallet still ask for Touch ID.
import * as S from './store.js';
import * as C from './crypto.js';
import { app } from './ctx.js';
import { createPasskey, usePasskey, faceIdAvailable } from './lock.js';
import { h, sheet, toast, confirm, alert } from './ui.js';

const ROUNDS = 600000;
const LOCK_AFTER_MS = 60000;
const enc = new TextEncoder();
// kv 'protect': {v: 2, cred, prfSalt, prfOk, appLock, pwSalt,
//   dk: {face?, pass, dev?}, sk: {face?, pass, dev?} | null}   (boxes are {iv, ct}; sk null = not made yet)
let meta = null;
let dkRaw = null; // device key while Doable runs (needed to switch the start-up lock on / off)
let hiddenAt = 0;
let lockShown = false;

const save = () => S.kv.set('protect', meta);

export async function load() {
  await S.openDb();
  meta = (await S.kv.get('protect')) || null;
  if (meta && meta.v !== 2) {
    // First version: one key, always locked at start, opened by Touch ID (PRF) or the passphrase.
    meta = { v: 2, cred: meta.cred, prfSalt: meta.prfSalt, prfOk: !!meta.cred, appLock: true, pwSalt: meta.pwSalt,
      dk: { face: meta.prf || undefined, pass: meta.pw }, sk: null };
    await save();
  }
  return meta;
}
export const isOn = () => !!meta;
export const usesFace = () => !!(meta && meta.cred);
export const locksAtStart = () => !!(meta && meta.appLock);
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
async function faceKey(secret, info) {
  const base = await crypto.subtle.importKey('raw', secret, 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(32), info: enc.encode(info) }, base,
    { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}
async function passKey(pw) {
  const base = await crypto.subtle.importKey('raw', enc.encode(pw), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', hash: 'SHA-256', salt: C.fromB64(meta.pwSalt), iterations: ROUNDS }, base,
    { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}
/** This site's own key, kept by the browser (it can't be read out, only used here). */
async function browserKey() {
  let k = await S.kv.get('protectBrowserKey');
  if (!k) {
    k = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    await S.kv.set('protectBrowserKey', k);
  }
  return k;
}
async function prfSecret(cred = meta.cred, salt = meta.prfSalt) {
  const res = await usePasskey(cred, { prf: { eval: { first: C.fromB64(salt) } } });
  const out = res.getClientExtensionResults().prf;
  if (!out || !out.results || !out.results.first) throw new Error('prf-unsupported');
  return new Uint8Array(out.results.first);
}

/** Device key with Touch ID / Face ID (throws if not recognised). */
async function dkByFace() {
  if (meta.prfOk && meta.dk.face) return unseal(await faceKey(await prfSecret(), 'doable-device'), meta.dk.face);
  await usePasskey(meta.cred);
  return unseal(await browserKey(), meta.dk.dev);
}
const dkByPassphrase = async (pw) => unseal(await passKey(pw), meta.dk.pass); // throws on a wrong passphrase

// ------------------------------------------------------------------ lock screen

/**
 * Covers the app until the user proves it's them. With `needKey` (start-up) it resolves with the device key's
 * bytes; otherwise (coming back after a while) a Touch ID / Face ID check is enough.
 */
function showLock(needKey) {
  return new Promise((resolve) => {
    lockShown = true;
    document.querySelectorAll('.overlay').forEach((o) => o.remove());
    const msg = h('div#protect-msg', usesFace() ? 'Doable is locked' : 'Enter your passphrase to open Doable');
    const pw = h('input.field', { type: 'password', placeholder: 'Passphrase', autocomplete: 'current-password',
      style: { maxWidth: '280px', background: 'rgba(255,255,255,.95)', color: '#222' } });
    const form = h('form', { style: { display: usesFace() ? 'none' : 'flex', flexDirection: 'column', gap: '10px', alignItems: 'center' } },
      pw, h('button.lock-btn', { type: 'submit' }, 'Unlock'));
    const done = (raw) => { el.remove(); lockShown = false; resolve(raw); };
    const face = async () => {
      try {
        if (needKey) done(await dkByFace());
        else { await usePasskey(meta.cred); done(null); }
      } catch (e) {
        msg.textContent = 'Not recognised — try again or use your passphrase';
      }
    };
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      msg.textContent = 'One moment…';
      try { done(await dkByPassphrase(pw.value)); } catch (err) { msg.textContent = 'Wrong passphrase'; pw.value = ''; pw.focus(); }
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

/** At start: null when protection is off, otherwise the device key (asking first when the start-up lock is on). */
export async function unlockAtStart() {
  if (!await load()) return null;
  let raw = null;
  if (!meta.appLock && meta.dk.dev) {
    try { raw = await unseal(await browserKey(), meta.dk.dev); } catch (e) { raw = null; }
  }
  if (!raw) raw = await showLock(true);
  dkRaw = raw;
  return aes(raw);
}

/** Relock after Doable was in the background for a minute (only with the start-up lock on). */
document.addEventListener('visibilitychange', () => {
  if (!meta || !meta.appLock) return;
  if (document.hidden) hiddenAt = Date.now();
  else if (hiddenAt && Date.now() - hiddenAt > LOCK_AFTER_MS && !lockShown) showLock(false);
});

/** Touch ID / Face ID (or the passphrase) before showing the wallet or a locked note. True if confirmed. */
export async function verify() {
  if (!meta) return false;
  if (meta.cred) {
    try { await usePasskey(meta.cred); return true; } catch (e) { /* fall back to the passphrase */ }
  }
  const pw = await askPassphrase('Passphrase', meta.cred ? 'Touch ID / Face ID didn\'t work. Enter your passphrase instead.' : 'Enter your passphrase.', false);
  if (!pw) return false;
  try { await dkByPassphrase(pw); return true; } catch (e) { toast('Wrong passphrase'); return false; }
}
app.protectVerify = verify;

/**
 * The credentials key: Touch ID / Face ID (or the passphrase) every time. Returns its bytes, or null if the
 * user cancelled. Made the first time it's needed after an update from the first version.
 */
export async function openSecrets() {
  if (!meta) return null;
  if (!meta.sk) return makeSecrets();
  if (meta.cred) {
    try {
      if (meta.prfOk && meta.sk.face) return await unseal(await faceKey(await prfSecret(), 'doable-secrets'), meta.sk.face);
      if (meta.sk.dev) { await usePasskey(meta.cred); return await unseal(await browserKey(), meta.sk.dev); }
    } catch (e) { /* fall back to the passphrase */ }
  }
  const pw = await askPassphrase('Passphrase', meta.cred ? 'Touch ID / Face ID didn\'t work. Enter your passphrase instead.'
    : 'Enter your passphrase to open Credentials.', false);
  if (!pw) return null;
  try { return await unseal(await passKey(pw), meta.sk.pass); } catch (e) { toast('Wrong passphrase'); return null; }
}

async function makeSecrets() {
  const pw = await askPassphrase('One-time step', 'Enter your passphrase once more to finish protecting Credentials with Touch ID.', false);
  if (!pw) return null;
  try { await dkByPassphrase(pw); } catch (e) { toast('Wrong passphrase'); return null; }
  let secret = null;
  if (meta.cred && meta.prfOk) {
    try { secret = await prfSecret(); } catch (e) { secret = await finishWithTap(meta.cred, meta.prfSalt); }
  }
  const sk = C.randomBytes(32);
  meta.sk = { pass: await seal(await passKey(pw), sk) };
  if (secret) meta.sk.face = await seal(await faceKey(secret, 'doable-secrets'), sk);
  else if (meta.cred) meta.sk.dev = await seal(await browserKey(), sk);
  await save();
  return sk;
}

// ------------------------------------------------------------------ settings

/** Switches the start-up lock on or off (after Touch ID / the passphrase). */
export async function setStartLock(on) {
  if (!meta || !await verify()) return false;
  if (on) {
    meta.appLock = true;
    if (meta.prfOk && meta.dk.face) delete meta.dk.dev; // then only Touch ID / the passphrase can open the data
  } else {
    meta.appLock = false;
    meta.dk.dev = await seal(await browserKey(), dkRaw);
  }
  await save();
  toast(on ? 'Doable now asks for Touch ID when it opens' : 'Doable opens without asking; Credentials and Wallet still ask');
  return true;
}

/** Turns Touch ID / Face ID on later (e.g. after it didn't work the first time). */
export async function enrollFace() {
  if (!meta) return false;
  const sk = await openSecrets(); // the passphrase (no Touch ID yet)
  if (!sk) return false;
  const r = await createFace();
  if (!r.cred) { alert('Touch ID / Face ID didn\'t work', r.why); return false; }
  meta.cred = r.cred;
  meta.prfSalt = r.prfSalt;
  meta.prfOk = !!r.secret;
  if (r.secret) {
    meta.dk.face = await seal(await faceKey(r.secret, 'doable-device'), dkRaw);
    meta.sk.face = await seal(await faceKey(r.secret, 'doable-secrets'), sk);
    delete meta.sk.dev;
  } else {
    meta.sk.dev = await seal(await browserKey(), sk);
    meta.dk.dev = await seal(await browserKey(), dkRaw);
  }
  await save();
  toast('Touch ID / Face ID is on ✓');
  return true;
}

// ------------------------------------------------------------------ turning it on

function askPassphrase(title, message, twice) {
  return new Promise((resolve) => {
    const p1 = h('input.field', { type: 'password', placeholder: 'Passphrase', autocomplete: twice ? 'new-password' : 'current-password', autofocus: true });
    const p2 = twice ? h('input.field', { type: 'password', placeholder: 'Repeat passphrase', autocomplete: 'new-password' }) : null;
    let result = null;
    const s = sheet({
      title, dismissable: false,
      body: [h('p.muted', message), p1, p2 ? h('div', { style: { height: '8px' } }) : null, p2],
      buttons: [{ label: 'Cancel' }, { label: 'OK', primary: true, action: () => {
        if (p1.value.length < 8) { toast('Use at least 8 characters'); return false; }
        if (p2 && p1.value !== p2.value) { toast('The passphrases don\'t match'); return false; }
        result = p1.value;
      } }],
      onClose: () => resolve(result),
    });
    (p2 || p1).addEventListener('keydown', (e) => { if (e.key === 'Enter') s.box.querySelector('.btn.primary').click(); });
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

/** Second Touch ID / Face ID right after creating the passkey (some browsers want a fresh tap for it). */
function finishWithTap(cred, salt) {
  return new Promise((resolve) => {
    let secret = null;
    sheet({
      title: 'One more Touch ID / Face ID', dismissable: false,
      body: h('p', 'Confirm once more to finish.'),
      buttons: [{ label: 'Skip' }, { label: 'Continue', primary: true, action: async () => {
        try { secret = await prfSecret(cred, salt); } catch (e) { secret = null; }
      } }],
      onClose: () => resolve(secret),
    });
  });
}

/** Creates the Touch ID / Face ID passkey: {cred, prfSalt, secret} (secret null without PRF), or {why} if it failed. */
async function createFace() {
  if (!window.PublicKeyCredential) return { why: 'This browser has no Touch ID / Face ID support for websites.' };
  if (!await faceIdAvailable()) {
    return { why: 'This browser reports no Touch ID / Face ID for websites. On a Mac, check System Settings → Touch ID & Password. '
      + 'On a company Mac, passkeys may be switched off — try Google Chrome instead of Safari.' };
  }
  const prfSalt = C.toB64(C.randomBytes(32));
  let c;
  try {
    c = await createPasskey('device', { prf: { eval: { first: C.fromB64(prfSalt) } } });
  } catch (e) {
    return { why: (e.name === 'NotAllowedError' ? 'Touch ID was cancelled or isn\'t allowed here'
      : e.name === 'InvalidStateError' ? 'A Doable passkey already exists on this device' : 'The browser refused: ' + e.name)
      + ' (' + (e.message || '').slice(0, 160) + '). On a company Mac, passkeys / iCloud Keychain may be switched off — Google Chrome often still works.' };
  }
  const cred = C.toB64(new Uint8Array(c.rawId));
  const ext = c.getClientExtensionResults().prf;
  let secret = null;
  if (ext && ext.enabled !== false) {
    if (ext.results && ext.results.first) secret = new Uint8Array(ext.results.first);
    else { try { secret = await prfSecret(cred, prfSalt); } catch (e) { secret = await finishWithTap(cred, prfSalt); } }
  }
  return { cred, prfSalt, secret };
}

/** Sets up protection: passphrase (the sync passphrase when sync is set up) + Touch ID / Face ID, then encrypts everything. */
export async function setup() {
  if (meta) return true;
  const stored = await S.kv.get('syncKey');
  const pw = await askPassphrase(stored ? 'Your sync passphrase' : 'Choose a passphrase',
    stored ? 'Credentials and Wallet will open with Touch ID / Face ID. Your sync passphrase is the backup way in.'
      : 'Credentials and Wallet will open with Touch ID / Face ID. This passphrase (8+ characters) is the backup way in — write it down. '
        + 'If you turn on sync later, use the same one.', !stored);
  if (!pw) return false;
  let syncRaw = null;
  if (stored) {
    syncRaw = new Uint8Array(await crypto.subtle.exportKey('raw', await C.deriveKey(pw, C.fromB64(stored.salt), stored.iterations, true)));
    if (!await sameSyncKey(syncRaw, stored)) { toast('That isn\'t your sync passphrase'); return false; }
  }
  const face = await createFace();
  if (!face.cred && !await confirm('Touch ID / Face ID isn\'t available', face.why
    + '\n\nYou can still protect Doable now with your passphrase, and turn on Touch ID later in Settings.', 'Use passphrase', false)) return false;
  const appLock = await confirm('Also lock Doable when it opens?', 'Credentials and Wallet always ask for '
    + (face.cred ? 'Touch ID' : 'the passphrase') + '. You can also lock the whole app when it opens — '
    + 'you can change this any time in Settings.', 'Lock at start', false);

  const dk = C.randomBytes(32);
  const sk = C.randomBytes(32);
  const m = { v: 2, cred: face.cred || null, prfSalt: face.cred ? face.prfSalt : null, prfOk: !!face.secret, appLock,
    pwSalt: C.toB64(C.randomBytes(16)), dk: {}, sk: {} };
  meta = m;
  m.dk.pass = await seal(await passKey(pw), dk);
  m.sk.pass = await seal(await passKey(pw), sk);
  if (face.secret) {
    m.dk.face = await seal(await faceKey(face.secret, 'doable-device'), dk);
    m.sk.face = await seal(await faceKey(face.secret, 'doable-secrets'), sk);
  } else if (face.cred) {
    m.sk.dev = await seal(await browserKey(), sk);
  }
  if (!appLock || !face.secret) m.dk.dev = await seal(await browserKey(), dk);
  await save();
  dkRaw = dk;
  if (syncRaw) await S.kv.set('syncKey', { raw: C.toB64(syncRaw), salt: stored.salt, iterations: stored.iterations });
  await S.protectAll(await aes(dk));
  if (S.settings.lock) { S.settings.lock = null; S.saveSettings(); } // the PIN lock is replaced by this
  toast(face.cred ? 'Protected ✓ Credentials and Wallet open with Touch ID / Face ID' : 'Protected ✓ (passphrase)', null, null, 5000);
  return true;
}

/** Shown where credentials / wallet need protection first. */
export function setupCard(what) {
  return h('div.page', h('div.empty', `🔐\nProtect ${what} with Touch ID / Face ID`),
    h('p.muted', { style: { textAlign: 'center', maxWidth: '420px', margin: '0 auto 16px' } },
      'Doable encrypts everything it keeps on this device; credentials and wallet open with your fingerprint or face. '
      + 'Anyone who copies this computer\'s files — including a company\'s device management — only gets unreadable data.'),
    h('button.btn.primary.block', { type: 'button', onclick: async () => { if (await setup()) app.refresh(); } }, 'Turn on protection'));
}
