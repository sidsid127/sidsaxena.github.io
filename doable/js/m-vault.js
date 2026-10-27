// Passwords: logins, bank cards, secure notes, Wi-Fi — encrypted, opened with Face ID / Touch ID.
import * as V from './vault.js';
import * as L from './logic.js';
import * as P from './protect.js';
import { app } from './ctx.js';
import { h, card, row, empty, sheet, field, input, textarea, select, menu, prompt, confirm, toast, pickFiles } from './ui.js';

const TYPES = ['Login', 'Bank card', 'Secure note', 'Wi-Fi'];
const ICON = { Login: '🔑', 'Bank card': '💳', 'Secure note': '📝', 'Wi-Fi': '📶' };
let query = '';

V.onVaultChange(() => { if (app.current && app.current() && app.current().id === 'vault') app.refresh(); });

async function render(view) {
  const page = h('div.page');
  view.appendChild(page);
  if (!V.isOpen()) { await renderLocked(page); return; }
  const search = h('input.field', { type: 'search', placeholder: 'Search passwords', value: query });
  const results = h('div');
  const draw = () => {
    results.textContent = '';
    const q = query.trim().toLowerCase();
    const list = V.logins().filter((l) => !q || [l.title, l.url, l.user, l.notes].some((x) => (x || '').toLowerCase().includes(q)));
    if (!list.length) { results.appendChild(empty(q ? 'Nothing matches.' : 'No passwords saved yet.\nTap + to add one.')); return; }
    results.appendChild(card(list.map((l) => row({ icon: ICON[l.type] || '🔑', title: l.title,
      sub: l.type === 'Bank card' ? '•••• ' + (l.number || '').replace(/\s/g, '').slice(-4) : l.type === 'Wi-Fi' ? 'Wi-Fi' : l.type === 'Secure note' ? 'Secure note' : [l.user, host(l.url)].filter(Boolean).join(' · '),
      onclick: () => details(l) }))));
  };
  search.addEventListener('input', () => { query = search.value; draw(); });
  page.append(search, results);
  draw();
  view.appendChild(h('button.fab', { type: 'button', 'aria-label': 'Add', onclick: () => edit({ type: 'Login' }) }, '+'));
}

const host = (url) => { try { return new URL(/^\w+:/.test(url) ? url : 'https://' + url).hostname.replace(/^www\./, ''); } catch (e) { return url || ''; } };

async function renderLocked(page) {
  if (!P.isOn()) { page.replaceWith(P.setupCard('your passwords')); return; }
  if (await V.needsMasterPassword()) {
    // Vault from an older version: its master password one last time, then Face ID / Touch ID only.
    page.appendChild(empty('🔑\nOne last time: enter your old master password.\nFrom now on Passwords opens with Touch ID / Face ID.'));
    const pw = input('', { type: 'password', placeholder: 'Master password', autocomplete: 'current-password' });
    const go = async () => {
      try { await V.convertMasterPassword(pw.value); toast('Done — next time it\'s just Touch ID / Face ID'); } catch (e) { toast('Wrong master password'); pw.value = ''; }
    };
    pw.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
    page.append(card(h('div', { style: { padding: '12px' } }, pw, h('div', { style: { height: '8px' } }),
      h('button.btn.primary.block', { type: 'button', onclick: go }, 'Unlock'))));
    setTimeout(() => pw.focus(), 100);
    return;
  }
  const open = async () => {
    if (!await P.verify()) return;
    try { await V.openProtected(); } catch (e) { toast('Couldn\'t open Passwords: ' + e.message); }
  };
  page.appendChild(empty('🔒\nYour passwords are encrypted on this device.'));
  page.appendChild(h('button.btn.primary.block', { type: 'button', onclick: open }, P.usesFace() ? 'Unlock with Touch ID / Face ID' : 'Unlock'));
  if (!autoTried) { autoTried = true; setTimeout(open, 200); } // ask right away once each time Passwords is opened
}
let autoTried = false;

function copy(label, value) {
  if (!value) return;
  navigator.clipboard.writeText(value).then(() => toast(label + ' copied'), () => toast('Couldn\'t copy'));
}

function details(l) {
  const f = (label, value, secret) => {
    if (!value) return null;
    const shown = h('div.secret', { style: { fontSize: '17px', overflowWrap: 'anywhere' } }, secret ? '••••••••' : value);
    let visible = !secret;
    return field(label, h('div.form-inline', shown,
      secret ? h('button.btn', { type: 'button', style: { flex: 'none' }, onclick: () => { visible = !visible; shown.textContent = visible ? value : '••••••••'; } }, '👁') : null,
      h('button.btn', { type: 'button', style: { flex: 'none' }, onclick: () => copy(label, value) }, 'Copy')));
  };
  const body = l.type === 'Bank card' ? [f('Card number', l.number), f('Name on card', l.holder), f('Expiry (MM/YY)', l.expiry), f('CVV', l.cvv, true), f('PIN', l.pin, true)]
    : l.type === 'Wi-Fi' ? [f('Network name', l.user || l.title), f('Password', l.password, true)]
      : l.type === 'Secure note' ? [] : [f('Website / app', l.url), f('Username', l.user), f('Password', l.password, true)];
  body.push(f('Notes', l.notes, l.type === 'Secure note'));
  sheet({ title: (ICON[l.type] || '🔑') + ' ' + l.title, body,
    buttons: [l.url ? { label: 'Open site', action: () => window.open(/^\w+:/.test(l.url) ? l.url : 'https://' + l.url, '_blank') } : null,
      { label: 'Edit', action: () => edit(l) }, { label: 'Close', primary: true }].filter(Boolean) });
}

function edit(l) {
  const isNew = !l.uid;
  const type = select(TYPES, l.type || 'Login');
  const title = input(l.title || '', { placeholder: 'e.g. Amazon, Home Wi-Fi' });
  const url = input(l.url || '', { placeholder: 'amazon.de', autocapitalize: 'off' });
  const user = input(l.user || '', { autocapitalize: 'off', autocomplete: 'off' });
  const pass = input(l.password || '', { autocapitalize: 'off', autocomplete: 'off', class: 'field secret' });
  const number = input(l.number || '', { inputmode: 'numeric', autocomplete: 'off' });
  const holder = input(l.holder || '');
  const expiry = input(l.expiry || '', { placeholder: 'MM/YY' });
  const cvv = input(l.cvv || '', { inputmode: 'numeric', autocomplete: 'off' });
  const pin = input(l.pin || '', { inputmode: 'numeric', autocomplete: 'off' });
  const notes = textarea(l.notes || '', { rows: 3 });
  const gen = h('button.btn', { type: 'button', style: { flex: 'none' }, onclick: () => { pass.value = V.generate(); } }, '🎲');
  const rows = {
    url: field('Website / app', url), user: field('Username / email', user), pass: field('Password', h('div.form-inline', pass, gen)),
    number: field('Card number', number), holder: field('Name on card', holder), card2: h('div.form-inline', field('Expiry', expiry), field('CVV', cvv), field('PIN', pin)),
  };
  const sync = () => {
    const t = type.value;
    rows.url.hidden = t !== 'Login';
    rows.user.hidden = t !== 'Login' && t !== 'Wi-Fi';
    rows.user.querySelector('.form-label').textContent = t === 'Wi-Fi' ? 'Network name' : 'Username / email';
    rows.pass.hidden = t !== 'Login' && t !== 'Wi-Fi';
    rows.number.hidden = rows.holder.hidden = rows.card2.hidden = t !== 'Bank card';
  };
  type.addEventListener('change', sync);
  sync();
  const buttons = [{ label: 'Cancel' }, { label: 'Save', primary: true, action: async () => {
    if (!title.value.trim()) { toast('Give it a name'); return false; }
    await V.putLogin({ uid: l.uid, type: type.value, title: title.value.trim(), url: url.value.trim(), user: user.value.trim(), password: pass.value,
      number: number.value.trim(), holder: holder.value.trim(), expiry: expiry.value.trim(), cvv: cvv.value.trim(), pin: pin.value.trim(), notes: notes.value });
  } }];
  if (!isNew) buttons.unshift({ label: 'Delete', danger: true, action: async () => { if (await confirm('Delete "' + l.title + '"?', null, 'Delete')) await V.deleteLogin(l.uid); } });
  sheet({ title: isNew ? 'New' : 'Edit', dismissable: false, body: [field('Type', type), field('Name', title), rows.url, rows.user, rows.pass, rows.number, rows.holder, rows.card2,
    field('Notes', notes)], buttons });
}

async function importCsv() {
  const [file] = await pickFiles('.csv,text/csv', { multiple: false });
  if (!file) return;
  const rows = L.csvParse(await file.text());
  if (rows.length < 2) { toast('No logins found'); return; }
  const head = rows[0].map((x) => x.trim().toLowerCase());
  const col = (...names) => { for (const n of names) if (head.includes(n)) return head.indexOf(n); return -1; };
  const name = col('name', 'title'), url = col('url', 'login_uri', 'website', 'uri'), user = col('username', 'login_username', 'login', 'email'),
    pass = col('password', 'login_password'), note = col('note', 'notes', 'extra', 'comment');
  if (pass < 0) { toast('No password column found'); return; }
  const get = (r, i) => (i >= 0 && i < r.length ? r[i].trim() : '');
  const existing = V.logins();
  let added = 0;
  for (const r of rows.slice(1)) {
    const l = { type: 'Login', url: get(r, url), user: get(r, user), password: get(r, pass), notes: get(r, note) };
    l.title = get(r, name) || host(l.url) || 'Imported login';
    if (!l.password && !l.user) continue;
    if (existing.some((o) => o.title === l.title && o.user === l.user && o.password === l.password)) continue;
    await V.putLogin(l);
    added++;
  }
  toast(added + ' login(s) imported. Delete the CSV file now — it isn\'t encrypted.');
}

export default {
  id: 'vault',
  name: 'Passwords',
  icon: 'key',
  tables: [],
  render,
  hide() { V.lock(); query = ''; autoTried = false; },
  menu: () => (V.isOpen() ? [
    { label: '🔒 Lock now', action: () => V.lock() },
    { label: 'Import from CSV (Chrome, Bitwarden…)', action: importCsv },
  ] : []),
};

// Lock whenever Doable goes to the background.
document.addEventListener('visibilitychange', () => { if (document.hidden && V.isOpen()) V.lock(); });
