// Household & sharing page: your household(s), members, invitations, which modules are shared, and people you share
// single items with. Opened from the profile page and the side menu.
import * as A from './account.js';
import * as SP from './spaces.js';
import * as Push from './push.js';
import { app } from './ctx.js';
import { h, card, row, header, prompt, toast, confirm, sheet, menu, input } from './ui.js';

let openId = '';
let busy = false;
let log = null; // the activity log, loaded on first show
const MODULE_OF = { grocery: 'grocery', grocery_lists: 'grocery', tasks: 'tasks', subtasks: 'tasks', categories: 'tasks', notes: 'notes', checklists: 'checklists',
  checklist_items: 'checklists', dates: 'dates', wallet: 'wallet', shared_expenses: 'split' };
function ago(t) {
  const m = Math.round((Date.now() - t) / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return m + ' min ago';
  if (m < 1440) return Math.round(m / 60) + ' h ago';
  return new Date(t).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

function render(view) {
  if (!log) SP.activityLog().then((l) => { log = l; app.refresh(); });
  const page = h('div.page');
  view.appendChild(page);
  if (!A.signedIn()) { page.appendChild(h('div.empty', 'Sign in to share with your household.')); return; }
  const sp = openId && SP.get(openId);
  if (sp) return detail(page, sp);
  openId = '';

  if (SP.incoming().length) {
    page.appendChild(header('Invitations for you'));
    page.appendChild(card(SP.incoming().map((i) => row({ icon: i.kind === 'household' ? '🏠' : '👥',
      title: i.kind === 'household' ? i.name : 'Shared with you',
      sub: 'From ' + (i.from.name || i.from.email) + (i.wrapped_key ? ' · tap to join' : ' · waiting for their Doable — or use their link'), onclick: () => SP.answerInvite(i) }))));
  }

  const homes = SP.households();
  page.appendChild(header('Households'));
  page.appendChild(card(
    homes.map((s) => row({ icon: '🏠', title: s.name, sub: s.members.length + (s.members.length === 1 ? ' member' : ' members') + ' · ' + (s.modules.map((m) => SP.moduleName(m).replace(/^\S+ /, '')).join(', ') || 'nothing shared yet')
      + (SP.ready(s.id) ? '' : ' · ⏳ unlocking…'), onclick: () => { openId = s.id; app.refresh(); app.bar(); } })),
    row({ icon: '➕', title: homes.length ? 'Create another household' : 'Create a household', sub: 'Share groceries, to-dos, checklists and more with your family', onclick: createHousehold }),
    row({ icon: '🔗', title: 'Join with an invitation link', sub: 'Paste the link someone sent you', onclick: () => SP.pasteLink() }),
  ));
  if (homes.length && 'Notification' in window && Notification.permission !== 'granted') {
    page.appendChild(card(row({ icon: '🔔', title: 'Get notified', sub: 'When someone changes something, joins, or assigns you a task', onclick: () => Push.enable(true).then(() => app.refresh()) })));
  }
  page.appendChild(h('p.muted.small', 'Everything you share is end-to-end encrypted: only the members can read it — not Doable, not Google.'));

  const pairs = SP.list().filter((s) => s.kind === 'share');
  if (pairs.length) {
    page.appendChild(header('Shared with people'));
    page.appendChild(card(pairs.map((s) => {
      const others = s.members.filter((m) => m.user_id !== A.user().id);
      const pending = s.invites.map((i) => i.email).filter(Boolean);
      return row({ icon: '👥', title: others.map((m) => m.name || m.email).join(', ') || pending.join(', ') || 'Waiting for someone to join',
        sub: others.length ? others.map((m) => m.email).join(', ') : 'Invitation sent · not joined yet', onclick: () => { openId = s.id; app.refresh(); app.bar(); } });
    })));
  }
}

function detail(page, sp) {
  const mine = sp.role === 'owner';
  const meId = A.user().id;
  page.appendChild(card(h('div.profile-head', h('div.house-emoji', sp.kind === 'household' ? '🏠' : '👥'),
    h('div.profile-name', sp.kind === 'household' ? sp.name : SP.spaceLabel(sp.id).replace(/^\S+ /, '')),
    h('div.muted', sp.members.length + (sp.members.length === 1 ? ' member' : ' members') + (mine ? ' · you are the owner' : '')))));
  if (!SP.ready(sp.id)) page.appendChild(h('p.muted.small', '⏳ This device is still getting the key for this space. It needs another member\'s Doable to be open once; nothing to do on your side.'));

  page.appendChild(header('Members'));
  const people = card(sp.members.map((m) => row({ icon: A.avatar(m, 34), title: (m.name || m.email) + (m.user_id === meId ? ' (you)' : ''),
    sub: m.email + (m.role === 'owner' ? ' · owner' : ''),
    onclick: mine && m.user_id !== meId ? () => menu(m.name || m.email, [{ label: 'Remove from ' + (sp.kind === 'household' ? sp.name : 'sharing'), danger: true, action: async () => {
      if (await confirm('Remove ' + (m.name || m.email) + '?', 'They lose access to everything shared here.', 'Remove')) await act(() => SP.removeMember(sp.id, m.user_id));
    } }]) : null })));
  for (const i of sp.invites) {
    people.appendChild(row({ icon: '✉️', title: i.email || 'Invitation link', sub: 'Invited · not joined yet', onclick: () => menu(i.email || 'Invitation link', [
      { label: 'Cancel invitation', danger: true, action: () => act(() => SP.cancelInvite(i.id)) }]) }));
  }
  people.appendChild(row({ icon: '➕', title: 'Invite someone', sub: 'By link — WhatsApp, SMS, email…', onclick: () => inviteSomeone(sp) }));
  page.appendChild(people);

  const acts = (log || []).filter((e) => e.space === sp.id).slice(0, 25);
  page.appendChild(header('Recent activity'));
  page.appendChild(card(acts.length ? acts.map((e) => {
    const m = sp.members.find((x) => x.user_id === e.by);
    return row({ icon: A.avatar(m || { name: e.name }, 30), title: e.name + ' ' + e.text, sub: ago(e.at),
      onclick: MODULE_OF[e.table] ? () => app.go(MODULE_OF[e.table], ['tasks', 'notes', 'dates', 'wallet'].includes(e.table) ? e.uid : undefined) : null });
  }) : row({ icon: '🕊', title: 'Nothing yet', sub: 'Changes by other members show up here' })));

  if (sp.kind === 'household') {
    page.appendChild(header('Shared in this household'));
    page.appendChild(card(SP.MODULES.map(([id, label]) => row({ icon: label.split(' ')[0], title: label.replace(/^\S+ /, ''),
      sub: sp.modules.includes(id) ? 'Shared — use "Share with ' + sp.name + '" on a list, category, note…' : 'Not shared',
      end: h('span.switch' + (sp.modules.includes(id) ? '.on' : ''), { 'aria-hidden': 'true' }),
      onclick: mine ? () => act(() => SP.update(sp.id, { modules: sp.modules.includes(id) ? sp.modules.filter((x) => x !== id) : [...sp.modules, id] })) : () => toast('Only the owner can change this') }))));
    page.appendChild(h('p.muted.small', 'In a shared module, choose what to share: e.g. Groceries → a list\'s ⋮ menu → "Share with ' + sp.name + '". Your other lists stay private.'));
  }

  page.appendChild(header(''));
  page.appendChild(card(
    mine && sp.kind === 'household' ? row({ icon: '✏️', title: 'Rename', sub: sp.name, onclick: async () => { const n = await prompt('Household name', sp.name); if (n) act(() => SP.update(sp.id, { name: n })); } }) : null,
    row({ icon: '🚪', title: 'Leave', sub: 'Shared things are removed from your devices', cls: 'danger-row', onclick: async () => {
      if (await confirm('Leave ' + (sp.kind === 'household' ? sp.name : 'this sharing') + '?', 'Everything shared here is removed from your devices.' + (mine && sp.members.length > 1 ? ' Another member becomes the owner.' : ''), 'Leave')) {
        await act(() => SP.leave(sp.id)); openId = '';
      }
    } }),
    mine && sp.members.length > 1 ? row({ icon: '🗑', title: 'Delete for everyone', cls: 'danger-row', onclick: async () => {
      if (await confirm('Delete ' + sp.name + ' for everyone?', 'Everything shared here is removed from all members\' devices.', 'Delete')) { await act(() => SP.remove(sp.id)); openId = ''; }
    } }) : null,
  ));
}

async function act(fn) {
  if (busy) return;
  busy = true;
  try { await fn(); } catch (e) { toast('Couldn\'t do that: ' + e.message); }
  busy = false;
  app.refresh();
  app.bar();
}

async function createHousehold() {
  const name = await prompt('Name your household', A.user().name ? A.user().name.split(' ').slice(-1)[0] + ' Home' : 'Home', { placeholder: 'e.g. Saxena Home' });
  if (!name) return;
  toast('Creating…');
  try {
    openId = await SP.create(name);
    app.refresh();
    app.bar();
    inviteSomeone(SP.get(openId));
  } catch (e) {
    toast(/Google|token|Sign in/i.test(e.message) ? 'Connect Google first (Sync & backup → sign in), then try again' : 'Couldn\'t create: ' + e.message);
  }
}

function inviteSomeone(sp) {
  const email = input('', { type: 'email', placeholder: 'Their email (optional)', autocomplete: 'off' });
  sheet({
    title: 'Invite to ' + (sp.kind === 'household' ? sp.name : 'sharing'),
    body: [h('p.muted', 'You\'ll get a link to send by WhatsApp, SMS or email. If they already use Doable with this email, they also see the invitation in the app.'), email,
      h('p.muted.small', 'Without an email, whoever opens the link first can join.')],
    buttons: [{ label: 'Cancel' }, { label: 'Create link', primary: true, action: async () => {
      const e = email.value.trim().toLowerCase();
      if (e && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e)) { toast('That email doesn\'t look right'); return false; }
      try { const link = await SP.invite(sp.id, e); app.refresh(); SP.sendInvite(SP.get(sp.id), link, e); } catch (err) { toast('Couldn\'t invite: ' + err.message); }
    } }],
  });
}

export default {
  id: 'household',
  name: 'Household & sharing',
  icon: 'home',
  tables: [],
  title: () => (openId && SP.get(openId) ? (SP.get(openId).kind === 'household' ? SP.get(openId).name : 'Shared') : 'Household & sharing'),
  isDeep: () => !!openId,
  back: () => { openId = ''; app.refresh(); },
  render,
  open(id) { openId = id; },
  menu: () => [{ label: '🔄 Refresh', action: async () => { await SP.refresh(); await SP.round(); app.refresh(); } }],
};
