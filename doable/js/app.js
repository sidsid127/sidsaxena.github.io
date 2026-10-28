// App shell: side menu, top bar, module switching, app lock, search, settings.
import * as S from './store.js';
import { h, $, clear, icon, menu, sheet, toast, confirm, prompt, alert, row, header, card } from './ui.js';
import { app } from './ctx.js';
import * as Sync from './sync.js';
import * as Lock from './lock.js';
import * as Protect from './protect.js';
import * as Account from './account.js';
import Profile from './m-profile.js';
import Admin from './m-admin.js';
import Today from './m-today.js';
import Tasks from './m-tasks.js';
import Notes from './m-notes.js';
import Diary from './m-diary.js';
import Trips from './m-trips.js';
import Grocery from './m-grocery.js';
import Habits from './m-habits.js';
import Dates from './m-dates.js';
import Checklists from './m-checklists.js';
import Wallet from './m-wallet.js';
import Vault from './m-vault.js';
import Log from './m-log.js';
import { searchAll } from './search.js';

export const MODULES = [Today, Tasks, Notes, Diary, Trips, Grocery, Habits, Dates, Checklists, Wallet, Vault, Log];
const PAGES = [Profile, Admin]; // opened from the account row, not listed as modules
let current = null;
const view = () => $('#view');

app.modules = MODULES;
app.isVisible = (id) => !S.settings.hidden.includes(id);
app.moduleName = (id) => (MODULES.find((m) => m.id === id) || { name: id }).name;
app.current = () => current;

/** Opens a module, optionally one item in it (uid) or a quick action ("task", "note", "expense"…). */
app.go = (id, uid, quick) => {
  const m = MODULES.find((x) => x.id === id) || PAGES.find((x) => x.id === id);
  if (!m) return;
  closeSide();
  if (m !== current) show(m);
  if (uid && m.open) m.open(uid);
  if (quick && m.quick) m.quick(quick);
};

/** Re-renders the current module and refreshes title / buttons. */
app.refresh = () => {
  if (!current) return;
  const v = view();
  const top = v.scrollTop;
  current.render(clear(v));
  if (current.keepScroll !== false) v.scrollTop = top;
  bar();
};
app.bar = () => bar();

function show(m) {
  if (current && current.hide) current.hide();
  current = m;
  S.settings.module = m.id;
  S.saveSettings();
  const v = view();
  v.scrollTop = 0;
  m.render(clear(v));
  bar();
  buildSide();
}

function bar() {
  const back = current.back && current.isDeep && current.isDeep();
  $('#title').textContent = current.title ? current.title() : current.name;
  const nav = $('#nav-btn');
  clear(nav).appendChild(icon(back ? 'back' : 'menu'));
  nav.classList.toggle('menu-only', !back);
  nav.setAttribute('aria-label', back ? 'Back' : 'Menu');
}

// ------------------------------------------------------------------ side menu

function buildSide() {
  const side = clear($('#side'));
  side.appendChild(h('div.side-head', h('img', { src: 'icons/icon-192.png', alt: '' }), 'Doable'));
  const u = Account.user();
  if (u) {
    side.appendChild(h('button.side-account' + (current === Profile ? '.on' : ''), { type: 'button', onclick: () => app.go('profile') },
      Account.avatar(u, 36), h('span.side-account-text', h('span.side-account-name', u.name || u.email.split('@')[0]), h('span.side-account-mail', u.email))));
  }
  for (const m of MODULES) {
    if (!app.isVisible(m.id)) continue;
    if (m.id === 'log') side.appendChild(h('div.nav-sep'));
    side.appendChild(h('button.nav-item' + (m === current ? '.on' : ''), { type: 'button', onclick: () => app.go(m.id) }, icon(m.icon), m.name));
  }
  side.appendChild(h('div.nav-sep'));
  side.appendChild(h('button.nav-item', { type: 'button', onclick: () => { closeSide(); Sync.settingsSheet(); } }, icon('sync'), 'Sync & backup'));
  side.appendChild(h('div.sync-status#sync-status', Sync.statusLine()));
  side.appendChild(h('button.nav-item', { type: 'button', onclick: () => { closeSide(); chooseModules(); } }, icon('tune'), 'Show / hide modules'));
  side.appendChild(h('button.nav-item', { type: 'button', onclick: () => { closeSide(); settingsMenu(); } }, icon('settings'), 'Settings'));
  if (Account.isAdmin()) side.appendChild(h('button.nav-item' + (current === Admin ? '.on' : ''), { type: 'button', onclick: () => app.go('admin') }, icon('chart'), 'Admin'));
}

/** Profile, plan and the server's switches changed: side menu, ad strip, announcement. */
app.accountChanged = () => {
  if (!current) return;
  buildSide();
  const ad = $('#ad-slot');
  const c = Account.config();
  ad.hidden = !Account.showAds();
  if (!ad.hidden) {
    clear(ad).append(h('span.ad-label', 'Ad'), h('span.ad-text', c.ad_text || 'Doable is free thanks to ads like this one.'),
      Account.config().pro_enabled ? h('button.ad-remove', { type: 'button', onclick: () => app.go('profile') }, 'Remove ads') : '');
  }
  const note = $('#announce');
  const text = c.announcement || '';
  note.hidden = !text || localStorage.getItem('doable.announceSeen') === text;
  if (!note.hidden) {
    clear(note).append(h('span', text), h('button', { type: 'button', 'aria-label': 'Close', onclick: () => { localStorage.setItem('doable.announceSeen', text); note.hidden = true; } }, '✕'));
  }
  if (current === Profile || current === Admin) app.refresh();
};
app.syncStatusChanged = () => { const el = $('#sync-status'); if (el) el.textContent = Sync.statusLine(); };

// Wide screens show the menu beside the content; the ☰ button hides it (e.g. while sharing the screen).
const wide = () => matchMedia('(min-width: 900px)').matches;
function applySide() { document.body.classList.toggle('side-hidden', !!S.settings.sideHidden); }

function openSide() {
  $('#side').classList.add('open');
  $('#scrim').hidden = false;
}
function closeSide() {
  $('#side').classList.remove('open');
  $('#scrim').hidden = true;
}

function chooseModules() {
  const boxes = MODULES.map((m) => {
    const cb = h('input', { type: 'checkbox', checked: app.isVisible(m.id) });
    return { m, cb, el: h('label.check-row', cb, m.name) };
  });
  sheet({
    title: 'Show in the menu',
    body: [h('p.muted', 'Hidden modules keep their data and still sync.'), boxes.map((b) => b.el)],
    buttons: [{ label: 'Cancel' }, {
      label: 'Save', primary: true, action: () => {
        const hidden = boxes.filter((b) => !b.cb.checked).map((b) => b.m.id);
        if (hidden.length === MODULES.length) { toast('Keep at least one module'); return false; }
        S.settings.hidden = hidden;
        S.saveSettings();
        if (!app.isVisible(current.id)) show(MODULES.find((m) => app.isVisible(m.id)));
        else buildSide();
      },
    }],
  });
}

// ------------------------------------------------------------------ settings

function applyTheme() {
  const t = S.settings.theme;
  const dark = t === 'dark' || (t !== 'light' && matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
}
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);

function settingsMenu() {
  menu('Settings', [
    Account.user() ? { label: '👤 Profile & account', action: () => app.go('profile') } : null,
    { label: 'Theme: ' + { system: 'same as device', light: 'light', dark: 'dark' }[S.settings.theme], action: () => menu('Theme', [
      ['system', 'Same as device'], ['light', 'Light'], ['dark', 'Dark']].map(([v, l]) => ({
      label: l, checked: S.settings.theme === v, action: () => { S.settings.theme = v; S.saveSettings(); applyTheme(); },
    }))) },
    Protect.isOn() ? { label: '🔐 Lock Doable when it opens: ' + (Protect.locksAtStart() ? 'on' : 'off'),
      action: () => Protect.setStartLock(!Protect.locksAtStart()) } : null,
    Protect.isOn() && !Protect.usesFace() ? { label: '👆 Turn on Touch ID / Face ID…', action: () => Protect.enrollFace() } : null,
    Protect.isOn() ? null : { label: '🔐 Protect with Touch ID / Face ID…', action: async () => { if (await Protect.setup()) app.refresh(); } },
    Protect.isOn() ? null : { label: S.settings.lock ? 'App lock: on (PIN / Face ID)' : 'App lock: off', action: () => (S.settings.lock ? Lock.disable() : Lock.enable()) },
    { label: 'Notifications while Doable is open', action: askNotifications },
    { label: 'Install on this device…', action: installHelp },
    { label: 'About Doable', action: () => alert('Doable', 'Your data is stored on this device. With sync turned on, it is '
      + 'end-to-end encrypted with your sync passphrase before it goes to your own Google Drive.\n\nVersion 7.0 (web)') },
  ]);
}

async function askNotifications() {
  if (!('Notification' in window)) {
    alert('Notifications', 'This browser can\'t show notifications. Reminders show inside Doable while it is open.');
    return;
  }
  const p = await Notification.requestPermission();
  toast(p === 'granted' ? 'Reminders will pop up while Doable is open' : 'Notifications not allowed');
}

function installHelp() {
  alert('Install Doable', 'iPhone / iPad: open this page in Safari → Share button → "Add to Home Screen".\n\n'
    + 'Mac (Safari 17+): File → "Add to Dock".  Chrome / Edge: the install icon in the address bar.\n\n'
    + 'Once installed it opens like an app and works offline.');
}

// ------------------------------------------------------------------ search

function openSearch() {
  const input = h('input.field', { type: 'search', placeholder: 'Search everything', autofocus: true });
  const results = h('div');
  const run = () => {
    clear(results);
    const q = input.value.trim();
    if (q.length < 2) {
      results.appendChild(h('p.muted', 'Tasks, notes, diary, trips & expenses, groceries, habits, dates, checklists, wallet and the log. '
        + 'Credentials are searched inside Credentials.'));
      return;
    }
    const hits = searchAll(q);
    if (!hits.length) { results.appendChild(h('p.muted', 'Nothing found.')); return; }
    let mod = null;
    let c;
    for (const hit of hits) {
      if (hit.module !== mod) {
        mod = hit.module;
        results.appendChild(header(app.moduleName(mod)));
        c = card();
        results.appendChild(c);
      }
      c.appendChild(row({ icon: hit.icon, title: hit.title, sub: hit.sub, onclick: () => { s.close(); app.go(hit.module, hit.uid); } }));
    }
  };
  let t;
  input.addEventListener('input', () => { clearTimeout(t); t = setTimeout(run, 150); });
  const s = sheet({ title: 'Search', body: [input, results], wide: true, buttons: [{ label: 'Close' }] });
  run();
}

// ------------------------------------------------------------------ reminders while open

const fired = new Set();
function checkReminders() {
  const now = Date.now();
  for (const r of remindersDue(now - 60000, now)) {
    if (fired.has(r.key)) continue;
    fired.add(r.key);
    if ('Notification' in window && Notification.permission === 'granted' && navigator.serviceWorker) {
      navigator.serviceWorker.ready.then((reg) => reg.showNotification(r.title, { body: r.text, tag: r.key, icon: 'icons/icon-192.png' }))
        .catch(() => toast('🔔 ' + r.title));
    } else {
      toast('🔔 ' + r.title + (r.text ? ' — ' + r.text : ''), 'Open', () => app.go(r.module, r.uid), 8000);
    }
  }
}
function remindersDue(from, to) {
  const out = [];
  for (const t of S.all('tasks')) {
    if (t.done || !t.due || t.time < 0) continue;
    const [y, m, d] = t.due.split('-').map(Number);
    const at = t.snooze || new Date(y, m - 1, d, Math.floor(t.time / 60), t.time % 60).getTime();
    if (at > from && at <= to) out.push({ key: 't' + t.uid + at, title: t.title, text: 'Task reminder', module: 'tasks', uid: t.uid });
  }
  return out;
}

// ------------------------------------------------------------------ start

async function start() {
  await S.init(await Protect.unlockAtStart());
  applyTheme();
  await Sync.init();
  await Account.gate();
  clear($('#search-btn')).appendChild(icon('search'));
  clear($('#more-btn')).appendChild(icon('more'));
  $('#nav-btn').addEventListener('click', () => {
    if (current.isDeep && current.isDeep() && current.back) { current.back(); bar(); return; }
    if (wide()) { S.settings.sideHidden = !S.settings.sideHidden; S.saveSettings(); applySide(); return; }
    if ($('#side').classList.contains('open')) closeSide(); else openSide();
  });
  applySide();
  $('#scrim').addEventListener('click', closeSide);
  $('#search-btn').addEventListener('click', openSearch);
  $('#more-btn').addEventListener('click', () => {
    const items = (current.menu ? current.menu() : []).filter(Boolean);
    menu(current.title ? current.title() : current.name, [...items,
      { label: '🔄 Sync now', action: () => Sync.syncNow(true) },
      { label: 'Settings…', action: settingsMenu }]);
  });

  S.onChange((tables) => {
    if (current && !current.busy && (!current.tables || current.tables.some((t) => tables.has(t)))) app.refresh();
    Sync.localChanged(tables);
  });

  const start = MODULES.find((m) => m.id === S.settings.module && app.isVisible(m.id)) || MODULES.find((m) => app.isVisible(m.id));
  show(start);
  app.accountChanged();
  Lock.init();
  handleLaunch();
  Sync.start();
  setInterval(checkReminders, 30000);
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
}

/** ?module=…&quick=… (shortcuts) and shared text (?share). */
function handleLaunch() {
  const p = new URLSearchParams(location.search);
  if (p.has('module')) app.go(p.get('module'), p.get('item'), p.get('quick'));
  if (p.has('share')) {
    const text = [p.get('title'), p.get('text'), p.get('url')].filter(Boolean).join('\n');
    if (text) import('./share.js').then((m) => m.shareIn(text));
  }
  if (p.toString()) history.replaceState(null, '', location.pathname);
}

let lastRefresh = Date.now();
addEventListener('visibilitychange', () => {
  if (document.hidden) { S.flush(); Sync.onHidden(); return; }
  Sync.onVisible();
  if (Date.now() - lastRefresh > 15 * 60000) { lastRefresh = Date.now(); Account.refresh(); }
});

start().catch((e) => {
  document.body.textContent = 'Doable could not start: ' + e.message;
  console.error(e);
});
