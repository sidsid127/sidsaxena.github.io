// The Doable account: sign in with Google (required to use Doable), profile, sign out, delete the account, and the
// switches the server sends (ads, Pro, announcement). Talks to api/account; keeps the session sealed on this device.
import * as S from './store.js';
import * as Sync from './sync.js';
import { app } from './ctx.js';
import { h, $, sheet, toast, confirm, alert } from './ui.js';

export const CONTACT = 'doable.by.sid@gmail.com';
let acct = null; // {session, user, config}

export const user = () => (acct && acct.user) || null;
export const config = () => (acct && acct.config) || {};
export const signedIn = () => !!(acct && acct.session);
export const isAdmin = () => !!(user() && user().admin);
/** Ads show only when switched on by the admin and the person doesn't have Pro. */
export const showAds = () => !!(config().ads_enabled && user() && user().plan !== 'pro');

class SignedOut extends Error {}
export async function api(action, body = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (acct && acct.session) headers.Authorization = 'Bearer ' + acct.session;
  const r = await fetch('api/account', { method: 'POST', headers, body: JSON.stringify({ action, ...body }) });
  const j = await r.json().catch(() => ({}));
  if (r.status === 401 && j.error === 'signed_out') throw new SignedOut('signed_out');
  if (!r.ok) throw new Error(j.error || 'HTTP ' + r.status);
  return j;
}

async function save() { await S.kv.set('account', acct); app.accountChanged && app.accountChanged(); }

function deviceName() {
  const ua = navigator.userAgent;
  return localStorage.getItem('doable.device') || (/iPhone/.test(ua) ? 'iPhone' : /iPad/.test(ua) ? 'iPad' : /Mac/.test(ua) ? 'Mac' : /Android/.test(ua) ? 'Android browser' : 'Browser');
}

async function signInWith(googleToken) {
  const j = await api('signin', { access_token: googleToken, platform: 'web', device: deviceName() });
  acct = { session: j.session, user: j.user, config: j.config };
  await save();
  return j;
}

/** Latest profile and switches from the server; a session that ended elsewhere shows the sign-in screen again. */
export async function refresh() {
  if (!signedIn() || !navigator.onLine) return;
  try {
    const j = await api('me');
    acct = { ...acct, user: j.user, config: j.config };
    await save();
  } catch (e) {
    if (e instanceof SignedOut) {
      acct = null;
      await S.kv.del('account');
      app.accountChanged && app.accountChanged();
      welcome();
    }
  }
}

/**
 * At start: resolves once this device is signed in. Signs in quietly when Google already knows this device (e.g. sync
 * was on). Never blocks when the account server can't be reached (offline) — it asks again on the next start.
 */
export async function gate() {
  acct = await S.kv.get('account');
  if (signedIn()) { refresh(); return; }
  let status = null;
  try { status = await (await fetch('api/account', { cache: 'no-store' })).json(); } catch (e) { /* offline */ }
  if (!status || !status.configured || status.error) return;
  const t = await Sync.googleToken();
  if (t) { try { await signInWith(t); return; } catch (e) { /* show the screen */ } }
  await welcome();
}

let welcomeDone = null;
/** Full-screen "Continue with Google"; resolves after a successful sign-in. */
export function welcome() {
  if ($('#welcome')) return welcomeDone;
  const msg = h('p.welcome-msg', { role: 'status' });
  const btn = h('button.google-btn', { type: 'button' }, h('span.g-logo', { html: GOOGLE_G }), 'Continue with Google');
  const el = h('div#welcome', { role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Sign in to Doable' },
    h('div.welcome-box',
      h('img.welcome-logo', { src: 'icons/icon-192.png', alt: '' }),
      h('h1', 'Doable'),
      h('p.welcome-tag', 'Your to-dos, notes, groceries, credentials and more — private, and in sync on all your devices.'),
      h('ul.welcome-points',
        h('li', '🔒 Your notes, tasks and credentials are encrypted — only you can read them'),
        h('li', '🏠 Share lists with your family or household'),
        h('li', '📱 Works on iPhone, Mac and Android, also offline')),
      btn, msg,
      h('p.welcome-legal', 'By continuing you agree to the ', h('a', { href: 'terms.html', target: '_blank' }, 'Terms'), ' and the ',
        h('a', { href: 'privacy.html', target: '_blank' }, 'Privacy policy'), '.')));
  document.body.appendChild(el);
  welcomeDone = new Promise((resolve) => {
    btn.addEventListener('click', async () => {
      if (!navigator.onLine) { msg.textContent = 'You\'re offline. Connect to the internet to sign in.'; return; }
      msg.textContent = 'Opening Google…';
      btn.disabled = true;
      setTimeout(() => { btn.disabled = false; }, 4000); // the Google window may have been closed: allow another try
      await Sync.signInGoogle();
      msg.textContent = 'Signing in…';
      try {
        const t = await Sync.googleToken();
        if (!t) throw new Error('no token');
        const j = await signInWith(t);
        el.remove();
        welcomeDone = null;
        toast(j.new_account ? 'Welcome to Doable, ' + firstName() + '!' : 'Welcome back, ' + firstName());
        resolve();
      } catch (e) {
        msg.textContent = 'Sign-in didn\'t work (' + e.message + '). Please try again.';
        btn.disabled = false;
      }
    });
  });
  return welcomeDone;
}

export const firstName = () => ((user() && (user().name || user().email)) || '').split(/[ @]/)[0];

/** Round photo, or initials when there's no photo (or it can't load). */
export function avatar(u = user(), size = 40) {
  const initials = ((u && (u.name || u.email)) || '?').split(/[ @.]/).filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join('');
  const box = h('span.avatar', { style: { width: size + 'px', height: size + 'px', fontSize: Math.round(size * 0.4) + 'px' } }, initials);
  if (u && u.picture) {
    const img = h('img', { src: u.picture, alt: '', referrerpolicy: 'no-referrer' });
    img.onerror = () => img.remove();
    box.appendChild(img);
  }
  return box;
}

export async function rename(name) {
  const j = await api('profile', { name });
  acct.user = j.user;
  await save();
}
export async function choose(choice) {
  const j = await api('choose', { choice });
  acct.user = j.user;
  await save();
}

// ------------------------------------------------------------------ sign out / delete

function syncIsOn() { try { return !!(JSON.parse(localStorage.getItem('doable.sync')) || {}).enabled; } catch (e) { return false; } }

export async function signOut() {
  const synced = syncIsOn();
  const ok = await confirm('Sign out of Doable?', synced
    ? 'Your Doable data is removed from this browser. It stays in your Google Drive sync and comes back when you sign in again.'
    : '⚠ Sync is off, so your data exists only on this device and will be removed. Download a backup first (Sync & backup) if you want to keep it.',
  'Sign out');
  if (!ok) return;
  if (synced) { toast('Syncing your latest changes…'); try { await Sync.syncNow(false); } catch (e) { /* best effort */ } }
  try { await api('signout'); } catch (e) { /* signs out here anyway */ }
  await S.wipeAll();
  location.reload();
}

export function deleteAccount() {
  const typed = h('input.field', { type: 'text', placeholder: 'Type DELETE', autocomplete: 'off', autocapitalize: 'characters' });
  sheet({
    title: 'Delete your Doable account?',
    body: [
      h('p', 'This permanently deletes:'),
      h('ul', h('li', 'your Doable account and profile'), h('li', 'your synced data in your Google Drive (Doable\'s hidden folder)'),
        h('li', 'all Doable data in this browser')),
      h('p.muted', 'Doable on your other devices keeps what is stored there until you sign out or delete the app there. This can\'t be undone.'),
      typed,
    ],
    buttons: [{ label: 'Cancel' }, { label: 'Delete forever', danger: true, action: async () => {
      if (typed.value.trim().toUpperCase() !== 'DELETE') { toast('Type DELETE to confirm'); return false; }
      if (!navigator.onLine) { toast('Connect to the internet first'); return false; }
      toast('Deleting…', null, null, 10000);
      const driveOk = await Sync.deleteRemote();
      try { await api('delete'); } catch (e) { if (!(e instanceof SignedOut)) { toast('Couldn\'t reach Doable: ' + e.message); return false; } }
      await S.wipeAll();
      await alert('Account deleted', driveOk ? 'Your Doable account and data are deleted. Thank you for using Doable.'
        : 'Your Doable account is deleted. Google Drive couldn\'t be reached, so remove the synced copy yourself: drive.google.com → Settings → Manage apps → Doable → Delete hidden app data.');
      location.reload();
    } }],
  });
}

const GOOGLE_G = '<svg viewBox="0 0 48 48" aria-hidden="true"><path fill="#EA4335" d="M24 9.5c3.5 0 6.6 1.2 9.1 3.6l6.8-6.8C35.8 2.4 30.3 0 24 0 14.6 0 6.6 5.4 2.7 13.3l7.9 6.1C12.5 13.6 17.8 9.5 24 9.5z"/><path fill="#4285F4" d="M46.5 24.5c0-1.6-.1-3.1-.4-4.5H24v9h12.7c-.6 3-2.3 5.5-4.8 7.2l7.7 6c4.5-4.2 6.9-10.3 6.9-17.7z"/><path fill="#FBBC05" d="M10.6 28.6A14.5 14.5 0 0 1 9.5 24c0-1.6.3-3.2.8-4.6l-7.9-6.1A24 24 0 0 0 0 24c0 3.9.9 7.5 2.6 10.7l8-6.1z"/><path fill="#34A853" d="M24 48c6.5 0 11.9-2.1 15.9-5.8l-7.7-6c-2.1 1.4-4.9 2.3-8.2 2.3-6.2 0-11.5-4.1-13.4-9.9l-8 6.1C6.6 42.6 14.6 48 24 48z"/></svg>';

app.account = { user, config, isAdmin, showAds };
