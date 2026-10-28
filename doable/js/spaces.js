// Households and sharing: which spaces I'm in, keeping their shared rows in step with the server (encrypted with
// each space's key, see e2e.js and SHARING.md), invitations (links, accept / reject) and the "Share…" actions the
// modules offer. A row belongs to a space through its own `space` column (lists, categories, notes, checklists,
// dates, wallet cards, tasks) or through its parent (items of a shared list, subtasks of a shared task…).
import * as S from './store.js';
import * as E from './e2e.js';
import * as A from './account.js';
import { app } from './ctx.js';
import { h, sheet, toast, confirm, alert, prompt, menu } from './ui.js';

export const MODULES = [
  ['grocery', '🛒 Groceries'], ['tasks', '✅ To-do'], ['checklists', '☑️ Checklists'], ['dates', '🎂 Important dates'], ['notes', '📝 Notes'],
  ['wallet', '💳 Wallet'], ['split', '💶 Shared expenses'],
];
export const moduleName = (id) => (MODULES.find((m) => m[0] === id) || [id, id])[1];
const DEFAULT_MODULES = ['grocery', 'tasks', 'checklists', 'dates', 'split'];

const OWN = new Set(['grocery_lists', 'categories', 'tasks', 'notes', 'checklists', 'dates', 'wallet', 'shared_expenses']);
const PARENT = { grocery: ['list', 'grocery_lists'], grocery_history: ['list', 'grocery_lists'], subtasks: ['task', 'tasks'],
  checklist_items: ['list', 'checklists'], tasks: ['category', 'categories'], categories: ['parent', 'categories'] };
const SYNCED = ['grocery_lists', 'grocery', 'grocery_history', 'categories', 'tasks', 'subtasks', 'notes', 'checklists', 'checklist_items', 'dates', 'wallet',
  'shared_expenses'];
export const TABLES = [...SYNCED, 'shops'];

// kv 'spaces': {list, incoming, cursors: {space: seq}, pushed: {space: {"t/uid": m}}, where: {"t/uid": space}}
let st = { list: [], incoming: [], cursors: {}, pushed: {}, where: {} };
const keys = new Map();    // space id → CryptoKey
const raws = new Map();    // space id → raw key bytes (to wrap it for new members)
let loaded = false;
const shown = new Set(); // invitations already put in front of the person this session
let running = null;
let timer = 0;

const save = () => S.kv.set('spaces', st);
export const list = () => st.list || [];
export const households = () => list().filter((s) => s.kind === 'household');
export const get = (id) => list().find((s) => s.id === id);
export const incoming = () => st.incoming || [];
export const ready = (id) => keys.has(id);

async function api(action, body = {}) {
  const r = await fetch('api/spaces', { method: 'POST', headers: { 'Content-Type': 'application/json', ...A.authHeaders() }, body: JSON.stringify({ action, ...body }) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || 'HTTP ' + r.status);
  return j;
}

/** The space a row belongs to ('' = private). */
export function spaceOf(table, uid, depth = 0) {
  const e = S.data[table] && S.data[table].get(uid);
  if (!e || e.d) return '';
  if (OWN.has(table) && e.r.space) return e.r.space;
  const p = PARENT[table];
  if (p && e.r[p[0]] && depth < 4) return spaceOf(p[1], e.r[p[0]], depth + 1);
  return '';
}

/** A small label for shared things in lists ("🏠 Home", "👥 Anna"). */
export function badge(table, uid) {
  const id = spaceOf(table, uid);
  if (!id) return '';
  const sp = get(id);
  if (!sp) return '🏠';
  return sp.kind === 'household' ? '🏠' : '👥';
}
/** Members of the space this row is shared in ([] when private). */
export function membersOf(table, uid) {
  const sp = get(spaceOf(table, uid));
  return sp ? sp.members : [];
}
/** Tell a member about something only devices know (assigned a task, recorded a payment). */
export async function tell(spaceId, userId, kind) {
  try { await api('notify', { id: spaceId, user_id: userId, kind }); } catch (e) { /* best effort */ }
}
export { api as spacesApi };

export function spaceLabel(id) {
  const sp = get(id);
  if (!sp) return 'Shared';
  if (sp.kind === 'household') return '🏠 ' + sp.name;
  const others = sp.members.filter((m) => m.user_id !== A.user().id);
  return '👥 ' + (others.map((m) => (m.name || m.email).split(' ')[0]).join(', ') || sp.name);
}

// ------------------------------------------------------------------ keeping in step

export async function load() {
  if (loaded) return;
  loaded = true;
  st = { ...st, ...((await S.kv.get('spaces')) || {}) };
}

/** Spaces, members and invitations from the server; opens the space keys; helps finish others' invitations. */
export async function refresh() {
  if (!A.signedIn() || !navigator.onLine) return;
  await load();
  let j;
  try { j = await api('list'); } catch (e) { return; }
  let me;
  try { me = await E.myKeys(); } catch (e) { me = null; } // Google not reachable: keys come later
  if (me && j.public_key !== me.pub) { try { await api('key_set', { public_key: me.pub }); } catch (e) { /* next time */ } }
  const before = new Set(list().map((s) => s.id));
  st.list = j.spaces;
  st.incoming = j.incoming;
  if (me) {
    const myId = await E.keyId(me.pub);
    for (const sp of st.list) {
      if (!keys.has(sp.id) && sp.wrapped_key && sp.key_id === myId) {
        try { const raw = await E.unwrap(sp.wrapped_key); raws.set(sp.id, raw); keys.set(sp.id, await E.spaceKey(raw)); } catch (e) { /* wrapped for an older key */ }
      }
      if (!raws.has(sp.id)) continue;
      for (const m of sp.members) { // a member with a new key (new phone): wrap the space key for it again
        if (m.public_key && m.key_id !== await E.keyId(m.public_key)) {
          try { await api('rewrap', { id: sp.id, user_id: m.user_id, wrapped_key: await E.wrapFor(m.public_key, raws.get(sp.id)), key_id: await E.keyId(m.public_key) }); } catch (e) { /* later */ }
        }
      }
    }
    try {
      for (const i of (await api('invites_to_wrap')).invites) {
        if (raws.has(i.space_id)) await api('invite_wrap', { invite_id: i.id, wrapped_key: await E.wrapFor(i.public_key, raws.get(i.space_id)), key_id: await E.keyId(i.public_key) });
      }
    } catch (e) { /* later */ }
  }
  // Spaces I'm no longer in: their rows leave this device.
  for (const id of before) if (!get(id)) forgetSpace(id);
  await save();
  app.spacesChanged && app.spacesChanged();
}

function forgetSpace(id) {
  const gone = []; // find them all first: removing a list first would hide which items were in it
  for (const t of TABLES) for (const [uid, e] of S.data[t]) if (!e.d && t !== 'shops' && spaceOf(t, uid) === id) gone.push([t, uid, e.m]);
  for (const [t, uid, m] of gone) S.applyRemote(t, uid, { m: m + 1, d: 1 });
  delete st.cursors[id];
  delete st.pushed[id];
  for (const k of Object.keys(st.where)) if (st.where[k] === id) delete st.where[k];
  keys.delete(id);
  raws.delete(id);
}

/** Push this device's changes to every space it's in, then fetch everyone else's. */
export function round() {
  if (running) return running;
  running = (async () => {
    await activityLog();
    for (const sp of list()) {
      if (!keys.has(sp.id)) continue;
      try { await push(sp.id); await pull(sp.id); } catch (e) { /* offline: next round */ }
    }
    announce();
    await save();
  })().finally(() => { running = null; });
  return running;
}
export function localChanged(tables) {
  if (!list().length || ![...tables].some((t) => TABLES.includes(t))) return;
  clearTimeout(timer);
  timer = setTimeout(round, 1500);
}

async function push(id) {
  const key = keys.get(id);
  const pushed = st.pushed[id] || (st.pushed[id] = {});
  const out = [];
  const shops = new Set();
  for (const t of SYNCED) {
    for (const [uid, e] of S.data[t]) {
      const k = t + '/' + uid;
      if ((pushed[k] || 0) >= e.m) continue;
      const now = e.d ? '' : spaceOf(t, uid);
      if (now === id) {
        out.push([t, uid, e]);
        if (e.r.shop) shops.add(e.r.shop);
      } else if (st.where[k] === id) out.push([t, uid, { m: e.m, d: 1 }]); // deleted, or moved out of this space
    }
  }
  for (const uid of shops) {
    const e = S.data.shops.get(uid);
    if (e && !e.d && (pushed['shops/' + uid] || 0) < e.m) out.push(['shops', uid, e]);
  }
  for (let i = 0; i < out.length; i += 200) {
    const part = out.slice(i, i + 200);
    const rows = [];
    for (const [t, uid, e] of part) rows.push({ tbl: t, uid, m: e.m, data: await E.sealRow(key, id, t, uid, e) });
    await api('push', { id, rows });
    for (const [t, uid, e] of part) {
      pushed[t + '/' + uid] = e.m;
      if (e.d) { if (st.where[t + '/' + uid] === id) delete st.where[t + '/' + uid]; } else st.where[t + '/' + uid] = id;
    }
  }
}

async function pull(id) {
  const key = keys.get(id);
  const pushed = st.pushed[id] || (st.pushed[id] = {});
  let since = st.cursors[id] || 0;
  for (let n = 0; n < 50; n++) {
    const j = await api('pull', { id, since });
    for (const row of j.rows) {
      if (!S.data[row.tbl]) continue;
      let entry;
      try { entry = await E.openRow(key, id, row.tbl, row.uid, row.data); } catch (e) { continue; }
      const k = row.tbl + '/' + row.uid;
      const local = S.data[row.tbl].get(row.uid);
      if (row.by && row.by !== A.user().id && (!local || entry.m > local.m)) note(id, row.by, row.tbl, row.uid, local, entry);
      if (entry.d) {
        // Gone from the space: remove it here unless it now lives somewhere else on this device.
        if (local && !local.d && entry.m > local.m && spaceOf(row.tbl, row.uid) === id) S.applyRemote(row.tbl, row.uid, entry);
        if (st.where[k] === id) delete st.where[k];
      } else {
        if (!local || entry.m > local.m) S.applyRemote(row.tbl, row.uid, entry);
        st.where[k] = id;
      }
      pushed[k] = Math.max(pushed[k] || 0, entry.m);
    }
    since = j.since;
    st.cursors[id] = since;
    if (!j.more) break;
  }
}

// ------------------------------------------------------------------ activity

// kv 'activity': [{space, by, name, text, table, uid, at}], newest first, at most 300.
let activity = null;
let fresh = [];          // this round's entries by others, for one notification
export async function activityLog() {
  if (!activity) activity = (await S.kv.get('activity')) || [];
  return activity;
}
const nameOf = (id, userId) => {
  const sp = get(id);
  const m = sp && sp.members.find((x) => x.user_id === userId);
  return m ? (m.name || m.email).split(' ')[0] : 'Someone';
};
const title = (t, r) => (r ? (t === 'notes' ? (r.body || '').split('\n')[0].replace(/^#+\s*/, '') : r.title || r.name || r.text || '') : '').slice(0, 60);

/** "added Milk", "ticked Milk", "completed Call plumber", "assigned you Fix the tap" … */
function describe(t, before, after) {
  const r = after.r;
  const old = before && !before.d ? before.r : null;
  const what = title(t, r || old);
  if (after.d) return old ? 'removed ' + what : null;
  if (t === 'grocery_history' || t === 'shops' || t === 'subtasks' && !old) return null;
  if (!old && t === 'tasks' && r.assignee && r.assignee === A.user().id) return 'assigned you ' + what;
  if (!old) return { grocery: 'added ', tasks: 'added the task ', notes: 'added the note ', checklists: 'added the checklist ', checklist_items: 'added ',
    dates: 'added the date ', wallet: 'added the card ', grocery_lists: 'made the list ', categories: 'made the category ', subtasks: 'added ',
    shared_expenses: r.kind === 'settle' ? 'recorded a payment ' : 'added the expense ' }[t] + (t === 'shared_expenses' && r.kind === 'settle' ? '' : what);
  if ('done' in r && r.done !== old.done) return (r.done ? (t === 'tasks' ? 'completed ' : 'ticked ') : 'unticked ') + what;
  if (t === 'tasks' && r.assignee !== old.assignee) return r.assignee === A.user().id ? 'assigned you ' + what : r.assignee ? 'assigned ' + what : 'unassigned ' + what;
  return 'changed ' + what;
}
function note(id, by, t, uid, before, after) {
  const text = describe(t, before, after);
  if (!text) return;
  const e = { space: id, by, name: nameOf(id, by), text, table: t, uid, at: Date.now(), mine: t === 'tasks' && after.r && after.r.assignee === A.user().id && /^assigned you/.test(text) };
  activity.unshift(e);
  fresh.push(e);
}

/** One notification for what others changed in this round (when Doable is in the background, or for assignments). */
function announce() {
  const list = fresh;
  fresh = [];
  if (!list.length) return;
  activity.length = Math.min(activity.length, 300);
  S.kv.set('activity', activity);
  app.activityChanged && app.activityChanged(list);
  const mine = list.filter((e) => e.mine);
  if (!document.hidden && !mine.length) return;
  const first = mine[0] || list[0];
  const more = list.length - 1;
  const body = first.name + ' ' + first.text + (more > 0 ? ` (+${more} more)` : '');
  if ('Notification' in window && Notification.permission === 'granted' && navigator.serviceWorker) {
    navigator.serviceWorker.ready.then((reg) => reg.showNotification(spaceLabel(first.space).replace(/^\S+ /, ''), { body, tag: 'activity:' + first.space, icon: 'icons/icon-192.png' })).catch(() => {});
  } else toast(body);
}

// ------------------------------------------------------------------ households

export async function create(name, modules = DEFAULT_MODULES, kind = 'household') {
  const me = await E.myKeys();
  const raw = E.newSpaceKey();
  const j = await api('create', { name, kind, modules, wrapped_key: await E.wrapFor(me.pub, raw), key_id: await E.keyId(me.pub) });
  raws.set(j.id, raw);
  keys.set(j.id, await E.spaceKey(raw));
  await refresh();
  return j.id;
}
export async function update(id, patch) { await api('update', { id, ...patch }); await refresh(); }
export async function leave(id) { await api('leave', { id }); await refresh(); }
export async function remove(id) { await api('delete', { id }); await refresh(); }
export async function removeMember(id, userId) { await api('remove_member', { id, user_id: userId }); await refresh(); }
export async function cancelInvite(inviteId) { await api('invite_cancel', { invite_id: inviteId }); await refresh(); }

/** Creates an invitation; returns the link to send. With an email of an existing Doable user, they also see it in the app. */
export async function invite(id, email = '') {
  const raw = raws.get(id);
  if (!raw) throw new Error('This device can\'t open this household yet');
  const lock = await E.linkLock(raw);
  const body = { id, email, token_hash: lock.hash, enc_key: lock.encKey };
  if (email) {
    const u = await api('user_key', { email });
    if (u.found && u.public_key) { body.wrapped_key = await E.wrapFor(u.public_key, raw); body.key_id = await E.keyId(u.public_key); }
  }
  const j = await api('invite', body);
  await refresh();
  return location.origin + location.pathname.replace(/[^/]*$/, '') + '?join=' + j.id + '#' + lock.token;
}

/** The invitation text with the link and where to get Doable. */
export function inviteText(sp, link) {
  const mods = sp.modules.map((m) => moduleName(m).replace(/^\S+ /, '')).join(', ');
  return `${A.user().name || A.user().email} invited you to "${sp.name}" on Doable${mods ? ' to share ' + mods : ''}.\n\n`
    + `Join here: ${link}\n\nDoable for Android: ${location.origin}/android/Doable.apk\niPhone and Mac: open the link in Safari or Chrome.`;
}

/** Send an invitation: share sheet, WhatsApp, email or copy. */
export function sendInvite(sp, link, email = '') {
  const text = inviteText(sp, link);
  const btn = (label, fn) => h('button.btn', { type: 'button', style: { width: '100%', margin: '4px 0' }, onclick: fn }, label);
  const s = sheet({
    title: 'Send the invitation',
    body: [h('p.muted', 'Send this link to the person you invite. It works once' + (email ? ', for ' + email : '') + ' and expires in 30 days.'),
      h('div.invite-link', link),
      navigator.share ? btn('📤 Share…', () => navigator.share({ title: 'Join ' + sp.name + ' on Doable', text }).catch(() => {})) : null,
      btn('💬 WhatsApp', () => open('https://wa.me/?text=' + encodeURIComponent(text), '_blank')),
      btn('✉️ Email', () => { location.href = 'mailto:' + encodeURIComponent(email) + '?subject=' + encodeURIComponent('Join ' + sp.name + ' on Doable') + '&body=' + encodeURIComponent(text); }),
      btn('📋 Copy', async () => { try { await navigator.clipboard.writeText(text); toast('Copied'); } catch (e) { toast('Couldn\'t copy'); } })],
    buttons: [{ label: 'Done', primary: true }],
  });
  return s;
}

// ------------------------------------------------------------------ joining

async function join(inviteId, raw, extra = {}) {
  const me = await E.myKeys();
  await api('invite_answer', { invite_id: inviteId, accept: true, wrapped_key: await E.wrapFor(me.pub, raw), key_id: await E.keyId(me.pub), ...extra });
  await refresh();
  await round();
}

/** An invite link opened on this device (remembered through sign-in). */
export async function handleJoinLink() {
  let j;
  try { j = JSON.parse(localStorage.getItem('doable.join')); } catch (e) { j = null; }
  if (!j || !A.signedIn()) return;
  localStorage.removeItem('doable.join');
  await openLink(j.id, j.token);
}

/** "…?join=ID#TOKEN" pasted or opened: returns {id, token} or null. */
export function parseLink(text) {
  const m = /[?&]join=([0-9a-f]+)#([\w-]{16,})/.exec(String(text || '').trim());
  return m ? { id: m[1], token: m[2] } : null;
}

export async function openLink(id, token) {
  shown.add(id); // the same invitation may also be waiting by email: answer it once
  let inv;
  try {
    inv = await api('invite_open', { invite_id: id, token_hash: await E.linkHash(token) });
  } catch (e) {
    const why = { invite_used: 'This invitation was already used or has expired. Ask for a new link.',
      invite_for_someone_else: 'This invitation is for a different Google account. Sign in with the account it was sent to.',
      no_invite: 'This invitation link isn\'t valid.' }[e.message] || 'Couldn\'t open the invitation: ' + e.message;
    alert('Invitation', why);
    return;
  }
  if (inv.already) { toast('You\'re already in ' + inv.name); return; }
  answerSheet({ name: inv.name, kind: inv.kind, modules: inv.modules, from: inv.from }, async () => {
    toast('Joining…', null, null, 10000);
    await join(inv.id, await E.linkUnlock(token, inv.enc_key), { token_hash: await E.linkHash(token) });
  }, async () => { await api('invite_answer', { invite_id: inv.id, accept: false, token_hash: await E.linkHash(token) }); });
}

/** Paste an invitation link (when it opened somewhere else, or the in-app invitation isn't ready yet). */
export function pasteLink(why = '') {
  const field = h('input.field', { type: 'url', placeholder: 'https://doable-sid.vercel.app/?join=…', autocomplete: 'off' });
  sheet({
    title: why ? 'Almost there' : 'Join with an invitation link',
    body: [why ? h('p', why) : null, h('p.muted', 'Paste the invitation link you were sent (WhatsApp, SMS, email…):'), field],
    buttons: [{ label: 'Close' }, { label: 'Join', primary: true, action: async () => {
      const l = parseLink(field.value);
      if (!l) { toast('That isn\'t a Doable invitation link'); return false; }
      await openLink(l.id, l.token);
    } }],
  });
}

/** Invitations sent to my email (shown once per start, and from the profile page). */
export function promptInvites(force = false) {
  for (const i of incoming()) {
    if (shown.has(i.id) && !force) continue;
    shown.add(i.id);
    answerInvite(i);
  }
}
/** Whether an invitation to my email can be opened on this device (wrapped for my current key). */
export async function inviteReady(i) {
  if (!i.wrapped_key) return false;
  try {
    const me = await E.myKeys();
    if (i.key_id && i.key_id !== await E.keyId(me.pub)) {
      await api('invite_reset', { invite_id: i.id }); // made for an older key: a member's device wraps it again
      i.wrapped_key = '';
      return false;
    }
  } catch (e) { /* Google not reachable: try anyway */ }
  return true;
}

export async function answerInvite(i) {
  const ready = await inviteReady(i);
  const who = i.from.name || i.from.email;
  answerSheet(i, async () => {
    if (!ready) {
      pasteLink(`${who} invited you before you had Doable, so their Doable has to finish the invitation — it does that by itself `
        + `the next time it is open (on Android also in the background, within about 15 minutes). To join right away, use the invitation link ${who} sent you.`);
      return false;
    }
    toast('Joining…', null, null, 10000);
    await join(i.id, await E.unwrap(i.wrapped_key));
    return true;
  }, async () => { await api('invite_answer', { invite_id: i.id, accept: false }); await refresh(); });
}

function answerSheet(inv, onJoin, onReject) {
  const who = inv.from.name || inv.from.email;
  const what = inv.kind === 'household' ? `the household "${inv.name}"` : 'sharing in Doable';
  sheet({
    title: inv.kind === 'household' ? '🏠 ' + inv.name : '👥 Shared with you',
    body: [h('p', `${who} invited you to join ${what}.`),
      inv.modules && inv.modules.length ? h('p.muted', 'Shared: ' + inv.modules.map(moduleName).join(', ')) : null,
      h('p.muted', 'Everything shared stays end-to-end encrypted — only the members can read it.')],
    buttons: [{ label: 'Not now' }, { label: 'Reject', danger: true, action: async () => { try { await onReject(); toast('Invitation rejected'); } catch (e) { toast('Couldn\'t answer: ' + e.message); } } },
      { label: 'Join', primary: true, action: async () => {
        try {
          const ok = await onJoin();
          if (ok !== false) toast(inv.kind === 'household' ? 'You joined ' + inv.name : 'Shared with you by ' + who);
        } catch (e) { toast('Couldn\'t join: ' + e.message); }
      } }],
  });
}

// ------------------------------------------------------------------ sharing things

const SEED = /^seed-/;
/**
 * Puts a list / category / note / … into a space ('' = private again). Built-in starting rows (same uid on every
 * device) are copied to a new uid and their items moved there, so sharing your "Groceries" list doesn't merge it
 * with everyone's own one.
 */
export function setSpace(table, uid, space) {
  let id = uid;
  if (space && SEED.test(uid)) {
    const e = S.data[table].get(uid);
    id = S.put(table, { ...e.r, space });
    const kids = { grocery_lists: [['grocery', 'list'], ['grocery_history', 'list']], categories: [['tasks', 'category'], ['categories', 'parent']],
      checklists: [['checklist_items', 'list']] }[table] || [];
    for (const [t, col] of kids) for (const r of S.where(t, (x) => x[col] === uid)) S.put(t, { uid: r.uid, [col]: id });
    if (table === 'grocery_lists' && S.settings.selList === uid) { S.settings.selList = id; S.saveSettings(); }
    // The built-in row itself stays (empty) as your private one, like on Android.
  } else {
    S.put(table, { uid, space });
  }
  return id;
}

/** Menu items for sharing one thing of `module` (households that share the module, people, private again). */
export function shareItems(module, table, uid, { people = false } = {}) {
  if (!A.signedIn()) return [];
  const cur = (S.get(table, uid) || {}).space || '';
  const items = [];
  for (const sp of households()) {
    if (!sp.modules.includes(module)) continue;
    items.push(cur === sp.id ? { label: '🏠 Shared with ' + sp.name + ' — make private', action: () => unshare(table, uid, sp) }
      : { label: '🏠 Share with ' + sp.name, action: () => share(table, uid, sp.id) });
  }
  if (people) items.push({ label: '👤 Share with a person…', action: () => sharePerson(table, uid) });
  if (cur && !get(cur) && !items.length) items.push({ label: 'Make private', action: () => setSpace(table, uid, '') });
  if (cur && get(cur) && get(cur).kind === 'share') items.push({ label: spaceLabel(cur) + ' — stop sharing', action: () => unshare(table, uid, get(cur)) });
  if (!items.length && !households().length) items.push({ label: '🏠 Share… (create a household first)', action: () => app.go('household') });
  return items;
}
function share(table, uid, id) {
  setSpace(table, uid, id);
  toast('Shared with ' + spaceLabel(id).replace(/^\S+ /, ''));
  round();
}
async function unshare(table, uid, sp) {
  if (!await confirm('Stop sharing?', 'It stays with you as a private item and is removed for the others.', 'Stop sharing')) return;
  setSpace(table, uid, '');
  round();
}

/** Share one item with one person: their own 2-person space (made once, reused for everything shared with them). */
async function sharePerson(table, uid) {
  const known = new Map();
  for (const sp of list()) for (const m of sp.members) if (m.user_id !== A.user().id) known.set(m.email, m);
  const pick = (email) => shareWith(email, table, uid);
  menu('Share with', [
    ...[...known.values()].map((m) => ({ label: (m.name || m.email) + (m.name ? ' · ' + m.email : ''), action: () => pick(m.email) })),
    { label: '✉️ Someone else (by email)…', action: async () => {
      const email = await prompt('Their email address', '', { placeholder: 'name@gmail.com', type: 'email', message: 'The Google account they use (or will use) for Doable.' });
      if (email && /@/.test(email)) pick(email.trim().toLowerCase());
    } },
  ]);
}
async function shareWith(email, table, uid) {
  try {
    let sp = list().find((s) => s.kind === 'share' && s.members.length <= 2 && (s.members.some((m) => m.email === email) || s.invites.some((i) => i.email === email)));
    let link = null;
    if (!sp) {
      const id = await create('Shared', [], 'share');
      sp = get(id);
      link = await invite(id, email);
      sp = get(id);
    }
    setSpace(table, uid, sp.id);
    round();
    const joined = sp.members.some((m) => m.email === email);
    if (joined) toast('Shared with ' + email);
    else {
      if (!link) link = await invite(sp.id, email);
      sendInvite(sp, link, email);
    }
  } catch (e) { toast('Couldn\'t share: ' + e.message); }
}

