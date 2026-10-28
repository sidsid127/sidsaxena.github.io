// Sync with your own Google Drive (hidden app folder), end-to-end encrypted with your sync passphrase.
// Format and merge rules: SYNC.md. Google sign-in: code flow with a renewable token through api/token (the site's
// server keeps the client secret); without that server part it falls back to the browser token flow (1 hour).
import * as S from './store.js';
import * as C from './crypto.js';
import * as V from './vault.js';
import { app } from './ctx.js';
import { GOOGLE_WEB_CLIENT_ID } from '../config.js';
import { h, sheet, toast, confirm, alert, prompt, field, input, card, row, fmtStamp, download, pickFiles } from './ui.js';

const DOC = 'doable.sync';
const API = 'https://www.googleapis.com/drive/v3';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3';
const SCOPE = 'https://www.googleapis.com/auth/drive.appdata email profile';
const TOMBSTONE_DAYS = 180;

const ls = {
  get: (k) => { try { return JSON.parse(localStorage.getItem(k)); } catch (e) { return null; } },
  set: (k, v) => localStorage.setItem(k, JSON.stringify(v)),
  del: (k) => localStorage.removeItem(k),
};
const cfg = () => ls.get('doable.sync') || {}; // {enabled, email, last, error, by}
const setCfg = (patch) => { ls.set('doable.sync', { ...cfg(), ...patch }); app.syncStatusChanged && app.syncStatusChanged(); };
export const clientId = () => localStorage.getItem('doable.clientId') || GOOGLE_WEB_CLIENT_ID;
const deviceName = () => localStorage.getItem('doable.device') || (/iPhone|iPad/.test(navigator.userAgent) ? 'iPhone' : /Mac/.test(navigator.userAgent) ? 'Mac' : 'Browser');

let syncKey = null;          // CryptoKey
let fileIds = new Map();     // Drive file name → id (from the last listing)
let running = null;
let dirty = false;
let timer = 0;
let synced = { stamp: -1, version: null }; // local edit stamp and Drive version after the last good round
let docVersion = null;                       // Drive version of the document from the last listing

V.setSyncKey(async () => syncKey || (syncKey = await loadKey()));
app.fetchFile = (name) => fetchFile(name);

/** The sync key: stored raw (sealed when device protection is on), or as a CryptoKey by older versions. */
export async function loadKey() {
  const k = await S.kv.get('syncKey');
  if (!k) return null;
  if (k.key) return k.key;
  return crypto.subtle.importKey('raw', C.fromB64(k.raw), 'AES-GCM', false, ['encrypt', 'decrypt']);
}

export function statusLine() {
  const c = cfg();
  if (!c.enabled) return 'Sync off — data stays on this device';
  if (c.error) return '⚠ ' + c.error;
  return (c.email ? c.email + ' · ' : '') + (c.last ? 'synced ' + fmtStamp(c.last) : 'not synced yet');
}

// ------------------------------------------------------------------ Google sign-in (token flow)

function token() {
  const t = ls.get('doable.token');
  return t && t.token && t.exp - 60000 > Date.now() ? t.token : null;
}
const saveToken = (j) => ls.set('doable.token', { token: j.access_token, exp: Date.now() + (Number(j.expires_in) || 3600) * 1000 });

/** A valid access token, renewed through api/token when this device has a renewal token; null = sign in needed. */
let renewing = null;
async function getToken() {
  const t = token();
  if (t) return t;
  if (!renewing) {
    renewing = (async () => {
      const refresh = await S.kv.get('googleRefresh');
      if (!refresh) return null;
      try {
        const r = await fetch('api/token', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refresh_token: refresh }) });
        const j = await r.json();
        if (j.access_token) { saveToken(j); return j.access_token; }
        if (j.error === 'invalid_grant') await S.kv.del('googleRefresh'); // revoked: sign in again
      } catch (e) { /* offline */ }
      return null;
    })().finally(() => { renewing = null; });
  }
  return renewing;
}

// Whether the site's server part can keep this device signed in (asked once per start; sign-in has to open its
// window right away on a tap, so this and the PKCE pair are prepared ahead).
let codeFlow = false;
let pkce = null;
async function prepareSignIn() {
  try { codeFlow = (await (await fetch('api/token', { cache: 'no-store' })).json()).configured === true; } catch (e) { codeFlow = false; }
  if (!codeFlow) return;
  const verifier = C.toB64(C.randomBytes(32)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
  pkce = { verifier, challenge: C.toB64(hash).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') };
}

const redirectUri = () => new URL('auth.html', location.href).href;
let handledState = null;
async function takeAuthResult(data) {
  if (!data) return false;
  ls.del('doable.authResult');
  const expected = localStorage.getItem('doable.authState');
  if (data.state && data.state === handledState) return false; // same answer by message and by storage
  handledState = data.state;
  if (data.error || !(data.token || data.code) || (expected && data.state !== expected)) {
    if (data.error && data.error !== 'access_denied') toast('Google sign-in failed: ' + data.error);
    return false;
  }
  localStorage.removeItem('doable.authState');
  if (data.code) {
    try {
      const r = await fetch('api/token', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: data.code, code_verifier: localStorage.getItem('doable.pkce') || '', redirect_uri: redirectUri() }) });
      const j = await r.json();
      if (!j.access_token) { toast('Google sign-in failed: ' + (j.error_description || j.error || r.status)); return false; }
      if (j.refresh_token) await S.kv.set('googleRefresh', j.refresh_token);
      saveToken(j);
    } catch (e) {
      toast('Google sign-in failed — are you online?');
      return false;
    } finally {
      localStorage.removeItem('doable.pkce');
      prepareSignIn();
    }
    return true;
  }
  ls.set('doable.token', { token: data.token, exp: data.exp });
  return true;
}
addEventListener('message', (e) => {
  if (e.origin === location.origin && e.data && e.data.doableAuth) takeAuthResult(e.data.doableAuth).then((ok) => ok && afterSignIn());
});
addEventListener('storage', (e) => {
  if (e.key === 'doable.authResult' && e.newValue) takeAuthResult(ls.get('doable.authResult')).then((ok) => ok && afterSignIn());
});

function authUrl() {
  const state = C.toB64(C.randomBytes(12)).replace(/[^a-z0-9]/gi, '');
  localStorage.setItem('doable.authState', state);
  const p = new URLSearchParams({ client_id: clientId(), redirect_uri: redirectUri(), scope: SCOPE, include_granted_scopes: 'true', state });
  if (codeFlow && pkce) {
    localStorage.setItem('doable.pkce', pkce.verifier);
    p.set('response_type', 'code');
    p.set('code_challenge', pkce.challenge);
    p.set('code_challenge_method', 'S256');
    p.set('access_type', 'offline');
    p.set('prompt', 'consent');
  } else {
    p.set('response_type', 'token');
  }
  if (cfg().email) p.set('login_hint', cfg().email);
  return 'https://accounts.google.com/o/oauth2/v2/auth?' + p;
}

/** Must run from a tap (pop-ups). Falls back to a full-page redirect when pop-ups are blocked. */
export function signIn() {
  if (!clientId()) { notAvailable(); return; }
  const url = authUrl();
  const w = window.open(url, 'doable-auth', 'width=480,height=640');
  if (!w) location.href = url;
}

let pendingAfterSignIn = null;
async function afterSignIn() {
  try {
    const r = await fetch('https://www.googleapis.com/oauth2/v3/userinfo', { headers: { Authorization: 'Bearer ' + await getToken() } });
    if (r.ok) { const u = await r.json(); setCfg({ email: u.email || cfg().email }); }
  } catch (e) { /* offline */ }
  if (pendingAfterSignIn) { const f = pendingAfterSignIn; pendingAfterSignIn = null; f(); } else if (cfg().enabled) syncNow(false);
}

function notAvailable() {
  alert('Sync with Google', 'Sync isn\'t switched on in this version of Doable yet. It will work after the next update — no setup needed on your side.');
}

// ------------------------------------------------------------------ Drive REST

class AuthError extends Error {}
async function drive(path, opts = {}, base = API) {
  const t = await getToken();
  if (!t) throw new AuthError('Sign in to Google again');
  const r = await fetch(base + path, { ...opts, headers: { Authorization: 'Bearer ' + t, ...(opts.headers || {}) } });
  if (r.status === 401) { ls.del('doable.token'); throw new AuthError('Sign in to Google again'); }
  if (!r.ok) throw new Error('Google Drive: ' + r.status + ' ' + (await r.text()).slice(0, 120));
  return r;
}
async function listFiles() {
  fileIds = new Map();
  docVersion = '';
  let pageToken = '';
  do {
    const q = new URLSearchParams({ spaces: 'appDataFolder', fields: 'nextPageToken,files(id,name,version)', pageSize: '1000' });
    if (pageToken) q.set('pageToken', pageToken);
    const j = await (await drive('/files?' + q)).json();
    for (const f of j.files || []) {
      fileIds.set(f.name, f.id);
      if (f.name === DOC) docVersion = f.version;
    }
    pageToken = j.nextPageToken || '';
  } while (pageToken);
}
async function download_(name) {
  const id = fileIds.get(name);
  if (!id) return null;
  return new Uint8Array(await (await drive('/files/' + id + '?alt=media')).arrayBuffer());
}
/** Drive version of the document — one small request to see whether another device changed it. */
async function remoteVersion() {
  const q = new URLSearchParams({ spaces: 'appDataFolder', q: `name = '${DOC}'`, fields: 'files(version)' });
  const j = await (await drive('/files?' + q)).json();
  return j.files && j.files.length ? j.files[0].version : '';
}
/** Creates or replaces a file; returns its new Drive version. */
async function upload(name, bytes) {
  const id = fileIds.get(name);
  if (id) {
    const r = await drive('/files/' + id + '?uploadType=media&fields=version', { method: 'PATCH', body: bytes, headers: { 'Content-Type': 'application/octet-stream' } }, UPLOAD);
    return (await r.json()).version;
  }
  const boundary = 'doable' + Math.random().toString(36).slice(2);
  const meta = JSON.stringify({ name, parents: ['appDataFolder'] });
  const body = new Blob([`--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${meta}\r\n--${boundary}\r\nContent-Type: application/octet-stream\r\n\r\n`,
    bytes, `\r\n--${boundary}--`]);
  const j = await (await drive('/files?uploadType=multipart&fields=id,version', { method: 'POST', body, headers: { 'Content-Type': 'multipart/related; boundary=' + boundary } }, UPLOAD)).json();
  fileIds.set(name, j.id);
  return j.version;
}
async function remove(name) {
  const id = fileIds.get(name);
  if (!id) return;
  await drive('/files/' + id, { method: 'DELETE' });
  fileIds.delete(name);
}

// ------------------------------------------------------------------ merge

/** Merges local rows with the downloaded document; writes winners locally; returns the new document tables. */
export function mergeTables(local, remote, now = Date.now()) {
  const cutoff = now - TOMBSTONE_DAYS * 86400000;
  const out = {};
  const toApply = [];
  for (const table of new Set([...Object.keys(local), ...Object.keys(remote)])) {
    const L = local[table] || {}, R = remote[table] || {};
    const merged = {};
    for (const uid of new Set([...Object.keys(L), ...Object.keys(R)])) {
      const l = L[uid], r = R[uid];
      const win = !r ? l : !l ? r : l.m > r.m ? l : r; // tie: the document's version
      if (win.d && win.m < cutoff) continue;
      merged[uid] = win;
      if (win !== l && (!l || l.m !== win.m || !!l.d !== !!win.d)) toApply.push([table, uid, win]);
    }
    out[table] = merged;
  }
  return { tables: out, toApply };
}

function localTables() {
  const out = {};
  for (const t of S.TABLES) {
    if (t === 'logins') continue;
    const o = {};
    for (const [uid, e] of S.data[t]) o[uid] = e.d ? { m: e.m, d: 1 } : { m: e.m, r: e.r };
    out[t] = o;
  }
  return out;
}

const canon = (doc) => JSON.stringify({ t: doc.tables, f: [...doc.files].sort() });

// ------------------------------------------------------------------ a sync round

export async function syncNow(fromUser) {
  if (!cfg().enabled) { if (fromUser) settingsSheet(); return; }
  if (running) { dirty = true; return running; }
  if (!token() && !fromUser) {
    if (!await getToken()) { setCfg({ error: 'Tap "Sync now" to sign in to Google again' }); return; }
  } else if (!token()) {
    // From a tap: the sign-in window must open right away; a renewal only when it can't.
    if (!codeFlow || !await S.kv.get('googleRefresh') || !await getToken()) {
      setCfg({ error: 'Tap "Sync now" to sign in to Google again' });
      pendingAfterSignIn = () => syncNow(true);
      signIn();
      return;
    }
  }
  if (running) { dirty = true; return running; }
  running = round(fromUser).finally(() => { running = null; if (dirty) { dirty = false; schedule(1000); } });
  return running;
}

async function round(fromUser) {
  try {
    const stamp = S.localStamp();
    await S.flush();
    await listFiles();
    let version = docVersion;
    const bytes = await download_(DOC);
    let remote = { tables: {}, files: [] };
    let header = null;
    if (bytes) {
      header = C.docHeader(bytes);
      if (!header) throw new Error('The sync file on Drive is damaged');
      const stored = await S.kv.get('syncKey');
      if (!stored || stored.salt !== C.toB64(header.salt)) {
        syncKey = null;
        await S.kv.del('syncKey');
        if (!await askPassphrase(header)) { setCfg({ error: 'Enter your sync passphrase (Sync & backup)' }); return; }
      }
      syncKey = await loadKey();
      try {
        remote = JSON.parse(await C.decryptDoc(syncKey, bytes));
      } catch (e) {
        await S.kv.del('syncKey');
        syncKey = null;
        throw new Error('Wrong sync passphrase — enter it again in Sync & backup');
      }
    } else if (!await S.kv.get('syncKey')) {
      if (!await askPassphrase(null)) { setCfg({ error: 'Choose a sync passphrase (Sync & backup)' }); return; }
    }
    syncKey = await loadKey();
    const { salt, iterations } = await S.kv.get('syncKey');

    const remoteTables = remote.tables || {};
    const { tables, toApply } = mergeTables(localTables(), Object.fromEntries(Object.entries(remoteTables).filter(([k]) => k !== 'logins')));
    for (const [t, uid, e] of toApply) if (S.data[t]) S.applyRemote(t, uid, e);

    // Passwords: from memory / the sealed copy; pass the remote ones through when unknown.
    const localLogins = await V.syncEntries();
    const lm = mergeTables({ logins: localLogins || {} }, { logins: remoteTables.logins || {} });
    tables.logins = lm.tables.logins;
    await V.syncApply(tables.logins);

    // Attachments: upload what Drive doesn't have; forget files no attachment uses any more.
    const files = new Set(remote.files || []);
    const used = new Set(Object.values(tables.attachments || {}).filter((e) => !e.d).map((e) => e.r.file));
    for (const f of used) {
      if (files.has(f) && fileIds.has('att-' + f)) continue;
      const blob = await S.files.get(f);
      if (!blob) continue;
      await upload('att-' + f, await C.encryptFile(syncKey, f, new Uint8Array(await blob.arrayBuffer())));
      files.add(f);
    }
    for (const f of [...files]) {
      if (!used.has(f)) { files.delete(f); await remove('att-' + f).catch(() => {}); }
    }
    for (const f of [...files]) if (!fileIds.has('att-' + f)) files.delete(f);

    const doc = { format: 1, updated: Date.now(), by: deviceName(), tables, files: [...files] };
    if (!bytes || canon(doc) !== canon({ tables: remoteTables, files: remote.files || [] })) {
      version = await upload(DOC, await C.encryptDoc(syncKey, C.fromB64(salt), iterations, JSON.stringify(doc)));
    }
    await S.flush();
    synced = { stamp, version };
    setCfg({ last: Date.now(), error: '' });
    if (fromUser) toast('Synced ✓');
  } catch (e) {
    setCfg({ error: e.message });
    if (fromUser || !(e instanceof AuthError)) console.warn('sync', e);
    if (fromUser && e instanceof AuthError) { pendingAfterSignIn = () => syncNow(true); signIn(); }
    else if (fromUser) toast('Sync failed: ' + e.message);
  }
}

/** First device: choose a passphrase; others: enter it. Stores the derived key on this device. */
function askPassphrase(header) {
  return new Promise((resolve) => {
    const p1 = input('', { type: 'password', placeholder: 'Sync passphrase', autocomplete: 'new-password' });
    const p2 = header ? null : input('', { type: 'password', placeholder: 'Repeat passphrase', autocomplete: 'new-password' });
    let ok = false;
    sheet({
      title: header ? 'Enter your sync passphrase' : 'Choose a sync passphrase',
      body: [h('p.muted', header
        ? 'Your data on Google Drive is encrypted with the passphrase you chose on your first device.'
        : 'Everything Doable stores on your Google Drive is encrypted with this passphrase, so not even Google can read it. '
          + 'You\'ll type it once on each device. Write it down — without it the synced data can\'t be opened.'), p1, p2 ? h('div', { style: { height: '8px' } }) : null, p2],
      dismissable: false,
      buttons: [{ label: 'Cancel' }, { label: 'OK', primary: true, action: async () => {
        const pw = p1.value;
        if (pw.length < 8) { toast('Use at least 8 characters'); return false; }
        if (p2 && pw !== p2.value) { toast('The passphrases don\'t match'); return false; }
        toast('One moment…');
        const salt = header ? header.salt : C.randomBytes(16);
        const iterations = header ? header.iterations : C.SYNC_ROUNDS;
        const raw = new Uint8Array(await crypto.subtle.exportKey('raw', await C.deriveKey(pw, salt, iterations, true)));
        await S.kv.set('syncKey', { raw: C.toB64(raw), salt: C.toB64(salt), iterations });
        syncKey = await loadKey();
        ok = true;
      } }],
      onClose: () => resolve(ok),
    });
  });
}

/** Downloads one attachment from Drive when this device doesn't have it yet. */
const fetching = new Map();
async function fetchFile(name) {
  if (!cfg().enabled) return null;
  if (fetching.has(name)) return fetching.get(name);
  const p = (async () => {
    try {
      if (!fileIds.has('att-' + name)) await listFiles();
      const bytes = await download_('att-' + name);
      if (!bytes) return null;
      const key = syncKey || (syncKey = await loadKey());
      const plain = await C.decryptFile(key, name, bytes);
      const type = /\.pdf$/i.test(name) ? 'application/pdf' : /\.(m4a|mp4)$/i.test(name) ? 'audio/mp4' : /\.webm$/i.test(name) ? 'audio/webm' : 'image/jpeg';
      const blob = new Blob([plain], { type });
      await S.files.put(name, blob);
      return blob;
    } catch (e) { return null; } finally { fetching.delete(name); }
  })();
  fetching.set(name, p);
  return p;
}

// ------------------------------------------------------------------ scheduling

function schedule(ms) {
  clearTimeout(timer);
  timer = setTimeout(() => syncNow(false), ms);
}
export function localChanged() {
  if (cfg().enabled && !running) schedule(2000); // (changes written by a running sync don't need another round)
}
export function onHidden() {
  if (cfg().enabled) syncNow(false);
}
export function onVisible() {
  if (cfg().enabled) schedule(300);
}

/** Every few seconds while Doable is on screen: a round only if this device or Drive has something new. */
async function check() {
  if (!cfg().enabled || document.hidden || running || !navigator.onLine) return;
  if (S.localStamp() > synced.stamp) { syncNow(false); return; }
  try {
    if (await remoteVersion() !== synced.version) syncNow(false);
  } catch (e) { /* signed out or offline: the next full round reports it */ }
}

/** Before the app shows: prepares Google sign-in and takes the answer when coming back from a full-page sign-in. */
let returned = false;
export async function init() {
  await prepareSignIn();
  const back = ls.get('doable.authResult');
  returned = !!(back && await takeAuthResult(back));
}

export async function start() {
  if (returned) afterSignIn();
  else if (cfg().enabled) schedule(500);
  setInterval(check, 10000);
}

/** A Google access token if this device has one (or can renew one); null otherwise. */
export const googleToken = () => getToken();

/** Google sign-in for the Doable account (must run from a tap); resolves once Google answered. */
export function signInGoogle() {
  return new Promise((resolve) => {
    pendingAfterSignIn = () => { resolve(true); if (cfg().enabled) syncNow(false); };
    signIn();
  });
}

/** One small file in Doable's hidden Drive folder (e.g. the sharing key); null when it doesn't exist. Throws offline. */
export async function readDriveFile(name) {
  await listFiles();
  return download_(name);
}
export async function writeDriveFile(name, bytes) {
  await listFiles();
  await upload(name, bytes);
}

/** Deletes Doable's synced files from Google Drive (account deletion). False if Google couldn't be reached. */
export async function deleteRemote() {
  try {
    await listFiles();
    for (const name of [...fileIds.keys()]) await remove(name);
    return true;
  } catch (e) {
    return false;
  }
}

// ------------------------------------------------------------------ settings

export function settingsSheet() {
  const c = cfg();
  const body = [];
  body.push(h('p', c.enabled ? '🔄 ' + statusLine() : 'Keep this device in sync with your other devices (iPhone, Mac, Android) through your own Google Drive.'));
  body.push(h('p.muted', 'Doable stores one encrypted file in a hidden folder of your Google Drive that only Doable can see. '
    + 'It\'s encrypted with your sync passphrase before it leaves the device. Each device keeps working offline and catches up when online.'));
  const s = sheet({
    title: 'Sync & backup',
    wide: true,
    body: [body, card(
      c.enabled ? row({ icon: '🔄', title: 'Sync now', sub: c.email ? 'Signed in as ' + c.email : 'Will ask you to sign in to Google',
        onclick: () => { s.close(); syncNow(true); } }) : row({ icon: '☁️', title: 'Turn on sync', sub: 'Sign in with Google, then choose a passphrase',
        onclick: () => { s.close(); enable(); } }),
      c.enabled ? row({ icon: '🔑', title: 'Re-enter sync passphrase', sub: 'If you changed it on another device', onclick: async () => { await S.kv.del('syncKey'); syncKey = null; s.close(); syncNow(true); } }) : null,
      row({ icon: '🏷', title: 'This device\'s name', sub: deviceName(), onclick: async () => { const n = await prompt('Device name', deviceName()); if (n) localStorage.setItem('doable.device', n); s.close(); } }),
      c.enabled ? row({ icon: '⏏️', title: 'Turn off sync on this device', sub: 'Your data stays here and on Drive', onclick: async () => {
        if (!await confirm('Turn off sync?', 'This device keeps its data; other devices keep syncing.', 'Turn off')) return;
        ls.del('doable.token'); await S.kv.del('googleRefresh'); await S.kv.del('syncKey'); syncKey = null; setCfg({ enabled: false, error: '' }); s.close();
      } }) : null,
    ), h('div.section', 'Backup file'), card(
      row({ icon: '💾', title: 'Download a backup file', sub: 'Everything in one file, encrypted with a password you choose', onclick: () => { s.close(); backup(); } }),
      row({ icon: '↩', title: 'Restore from a backup file', sub: 'Adds the backup\'s data to this device (newer versions win)', onclick: () => { s.close(); restore(); } }),
    )],
    buttons: [{ label: 'Close', primary: true }],
  });
}

function enable() {
  if (!clientId()) { notAvailable(); return; }
  setCfg({ enabled: true, error: '' });
  if (token()) { syncNow(true); return; }
  pendingAfterSignIn = () => syncNow(true);
  signIn();
}

// ------------------------------------------------------------------ backup file (web)

async function backup() {
  const pw = await prompt('Backup password', '', { type: 'password', message: 'At least 8 characters. Needed to restore — write it down.' });
  if (!pw) return;
  if (pw.length < 8) { toast('Use at least 8 characters'); return; }
  toast('Preparing backup…');
  await S.flush();
  const tables = localTables();
  const logins = await V.syncEntries();
  if (logins) tables.logins = logins;
  const blobs = {};
  for (const name of await S.files.keys()) {
    const b = await S.files.get(name);
    if (b) blobs[name] = C.toB64(new Uint8Array(await b.arrayBuffer()));
  }
  const salt = C.randomBytes(16);
  const key = await C.deriveKey(pw, salt, C.SYNC_ROUNDS);
  const bytes = await C.encryptDoc(key, salt, C.SYNC_ROUNDS, JSON.stringify({ format: 1, kind: 'backup', updated: Date.now(), tables, files: Object.keys(blobs), blobs }));
  download('Doable backup ' + new Date().toISOString().slice(0, 10) + '.doableweb', new Blob([bytes]));
  if (!logins && await V.exists()) alert('Backup saved', 'Credentials were not included because they are locked and sync is off. Open Credentials first to include them.');
}

async function restore() {
  const [file] = await pickFiles('', { multiple: false });
  if (!file) return;
  const bytes = new Uint8Array(await file.arrayBuffer());
  const header = C.docHeader(bytes);
  if (!header) { toast('Not a Doable web backup (.doableweb)'); return; }
  const pw = await prompt('Backup password', '', { type: 'password' });
  if (!pw) return;
  let doc;
  try { doc = JSON.parse(await C.decryptDoc(await C.deriveKey(pw, header.salt, header.iterations), bytes)); } catch (e) { toast('Wrong password or damaged file'); return; }
  for (const [name, b64] of Object.entries(doc.blobs || {})) {
    if (!await S.files.get(name)) await S.files.put(name, new Blob([C.fromB64(b64)], { type: /\.pdf$/.test(name) ? 'application/pdf' : /\.(m4a|mp4)$/.test(name) ? 'audio/mp4' : 'image/jpeg' }));
  }
  const tables = { ...doc.tables };
  const logins = tables.logins;
  delete tables.logins;
  const { toApply } = mergeTables(localTables(), tables);
  for (const [t, uid, e] of toApply) if (S.data[t]) S.applyRemote(t, uid, e);
  if (logins) {
    if (V.isOpen()) await V.syncApply(mergeTables({ logins: (await V.syncEntries()) || {} }, { logins }).tables.logins);
    else toast('Open Credentials and restore again to bring back the credentials');
  }
  await S.flush();
  alert('Restored', `Backup from ${fmtStamp(doc.updated)} merged into this device.`);
}
