// App lock: PIN, plus Face ID / Touch ID through a device passkey used only as a local check.
import * as S from './store.js';
import { $, h, sheet, toast, confirm } from './ui.js';
import { toB64, fromB64 } from './crypto.js';

const LOCK_AFTER_MS = 60000;
let locked = false;
let typed = '';
let hiddenAt = 0;

async function hashPin(pin, salt) {
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(salt + ':' + pin)));
  return toB64(bytes);
}

export async function faceIdAvailable() {
  try {
    return !!window.PublicKeyCredential && await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable();
  } catch (e) { return false; }
}

export async function createPasskey(label, extensions) {
  return navigator.credentials.create({
    publicKey: {
      challenge: crypto.getRandomValues(new Uint8Array(32)),
      rp: { name: 'Doable' },
      user: { id: crypto.getRandomValues(new Uint8Array(16)), name: 'Doable ' + label, displayName: 'Doable ' + label },
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
      authenticatorSelection: { authenticatorAttachment: 'platform', userVerification: 'required', residentKey: 'preferred' },
      attestation: 'none',
      timeout: 60000,
      extensions,
    },
  });
}
export async function usePasskey(credId, extensions) {
  return navigator.credentials.get({
    publicKey: {
      challenge: crypto.getRandomValues(new Uint8Array(32)),
      allowCredentials: [{ type: 'public-key', id: fromB64(credId), transports: ['internal', 'hybrid'] }],
      userVerification: 'required',
      timeout: 60000,
      extensions,
    },
  });
}

function pinSheet(title) {
  return new Promise((resolve) => {
    const inp = h('input.field', { type: 'password', inputmode: 'numeric', maxlength: 8, autocomplete: 'off', placeholder: '••••', autofocus: true });
    let done = false;
    const ok = () => {
      const v = inp.value.replace(/\D/g, '');
      if (v.length < 4) { toast('Use at least 4 digits'); return false; }
      done = true;
      resolve(v);
    };
    const s = sheet({ title, body: inp, buttons: [{ label: 'Cancel' }, { label: 'Next', primary: true, action: ok }], onClose: () => { if (!done) resolve(null); } });
    inp.addEventListener('keydown', (e) => { if (e.key === 'Enter' && ok() !== false) s.close(); });
  });
}

export async function enable() {
  const p1 = await pinSheet('Choose a PIN (4–8 digits)');
  if (!p1) return;
  const p2 = await pinSheet('Enter the PIN again');
  if (p1 !== p2) { toast('PINs did not match, try again'); return; }
  const salt = toB64(crypto.getRandomValues(new Uint8Array(16)));
  S.settings.lock = { salt, hash: await hashPin(p1, salt), cred: null };
  S.saveSettings();
  if (await faceIdAvailable() && await confirm('Also unlock with Face ID / Touch ID?', 'You can always use your PIN instead.', 'Use Face ID', false)) {
    try {
      S.settings.lock.cred = toB64(new Uint8Array((await createPasskey('lock')).rawId));
      S.saveSettings();
      toast('Lock on: Face ID or PIN');
    } catch (e) {
      toast('Face ID not set up. PIN lock is on.');
    }
  } else {
    toast('PIN lock is on');
  }
}

export async function disable() {
  if (await confirm('Turn off the app lock?', 'Anyone using this device will be able to open Doable.', 'Turn off')) {
    S.settings.lock = null;
    S.saveSettings();
    toast('App lock turned off');
  }
}

function lockNow() {
  if (!S.settings.lock || locked) return;
  locked = true;
  typed = '';
  document.querySelectorAll('.overlay').forEach((o) => o.remove());
  if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
  $('#lock').hidden = false;
  $('#faceid-btn').hidden = !S.settings.lock.cred;
  $('#lock-msg').textContent = S.settings.lock.cred ? 'Use Face ID or enter your PIN' : 'Enter your PIN';
  paint();
  if (S.settings.lock.cred) setTimeout(face, 300);
}
function unlockNow() {
  locked = false;
  $('#lock').hidden = true;
}
export const isLocked = () => locked;

function paint() {
  const dots = $('#pin-dots');
  dots.textContent = '';
  for (let i = 0; i < Math.max(4, typed.length); i++) dots.appendChild(h('span' + (i < typed.length ? '.on' : '')));
}

async function tryPin(explicit) {
  const lock = S.settings.lock;
  if (!lock || typed.length < 4) return;
  if (await hashPin(typed, lock.salt) === lock.hash) { typed = ''; unlockNow(); return; }
  if (explicit || typed.length >= 8) {
    typed = '';
    $('#lock-msg').textContent = 'Wrong PIN, try again';
    const d = $('#pin-dots');
    d.classList.remove('shake'); void d.offsetWidth; d.classList.add('shake');
  }
}

async function face() {
  try {
    await usePasskey(S.settings.lock.cred);
    unlockNow();
  } catch (e) {
    $('#lock-msg').textContent = 'Enter your PIN';
  }
}

export function init() {
  const pad = $('#keypad');
  for (const k of ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'ok', '0', 'del']) {
    pad.appendChild(h('button' + (k === 'ok' || k === 'del' ? '.small' : ''), { type: 'button', 'data-k': k }, k === 'del' ? 'Delete' : k === 'ok' ? 'OK' : k));
  }
  pad.addEventListener('click', async (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    const k = b.dataset.k;
    if (k === 'del') typed = typed.slice(0, -1);
    else if (k === 'ok') await tryPin(true);
    else if (typed.length < 8) { typed += k; await tryPin(false); }
    paint();
  });
  addEventListener('keydown', (e) => {
    if (!locked) return;
    if (/^\d$/.test(e.key) && typed.length < 8) { typed += e.key; tryPin(false).then(paint); }
    else if (e.key === 'Backspace') { typed = typed.slice(0, -1); paint(); }
    else if (e.key === 'Enter') tryPin(true).then(paint);
  });
  $('#faceid-btn').addEventListener('click', face);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) hiddenAt = Date.now();
    else if (hiddenAt && Date.now() - hiddenAt > LOCK_AFTER_MS) lockNow();
  });
  lockNow();
}

/** Asks for Face ID / PIN before showing something private (locked notes, wallet). True if confirmed. */
export async function verifyUser(reason) {
  const lock = S.settings.lock;
  if (!lock) return true;
  if (lock.cred) {
    try { await usePasskey(lock.cred); return true; } catch (e) { /* fall back to the PIN */ }
  }
  const pin = await pinSheet(reason || 'Enter your PIN');
  if (!pin) return false;
  if (await hashPin(pin, lock.salt) === lock.hash) return true;
  toast('Wrong PIN');
  return false;
}
