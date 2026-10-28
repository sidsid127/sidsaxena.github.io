// Admin page (owner only): how many people use Doable, sign-ups, platforms, the ads / Pro switches and each user's
// plan. Shows account details only — the server never has anyone's notes, tasks or credentials.
import * as A from './account.js';
import { app } from './ctx.js';
import { h, card, row, header, prompt, toast, menu, input } from './ui.js';

let data = null;
let loading = false;
let error = '';
let filter = '';

async function load() {
  if (loading) return;
  loading = true;
  try { data = await A.api('admin'); error = ''; } catch (e) { error = e.message === 'not_admin' ? 'This page is only for Doable\'s owner.' : 'Couldn\'t load: ' + e.message; }
  loading = false;
  app.refresh();
}

async function set(body) {
  try {
    const j = await A.api('admin_set', body);
    data.config = j.config;
    toast('Saved');
    load();
  } catch (e) { toast('Couldn\'t save: ' + e.message); }
}

const tile = (value, label, sub) => h('div.stat-tile', h('div.stat-value', String(value)), h('div.stat-label', label), sub ? h('div.stat-sub', sub) : null);

/** Sign-ups per day, last 30 days: one series, thin rounded bars, value on hover / focus. */
function signupChart(days) {
  const max = Math.max(1, ...days.map((d) => d.n));
  const tip = h('div.chart-tip', { 'aria-live': 'polite' }, 'Hover a bar for the day');
  const bars = days.map((d) => {
    const label = new Date(d.day + 'T12:00').toLocaleDateString(undefined, { day: 'numeric', month: 'short' }) + ': ' + d.n + ' sign-up' + (d.n === 1 ? '' : 's');
    const show = () => { tip.textContent = label; };
    return h('div.bar-slot', { tabindex: 0, 'aria-label': label, onmouseenter: show, onfocus: show, onclick: show },
      h('div.bar', { style: { height: (d.n ? Math.max(4, (d.n / max) * 100) : 0) + '%' } }));
  });
  const first = new Date(days[0].day + 'T12:00').toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
  return h('div.chart',
    h('div.chart-head', h('span', 'Sign-ups per day · last 30 days'), h('span.muted', 'max ' + max)),
    h('div.bars', bars),
    h('div.chart-axis', h('span', first), h('span', 'today')),
    tip);
}

function render(view) {
  const page = h('div.page');
  view.appendChild(page);
  if (!A.isAdmin()) { page.appendChild(h('div.empty', 'This page is only for Doable\'s owner.')); return; }
  if (!data) {
    page.appendChild(h('div.empty', error || 'Loading…'));
    if (!loading && !error) load();
    return;
  }
  const s = data.stats;
  page.appendChild(h('div.stat-grid',
    tile(s.users, 'People', s.new_today + ' new today'),
    tile(s.active_7d, 'Active this week', s.active_1d + ' today'),
    tile(s.new_30d, 'New in 30 days', s.new_7d + ' this week'),
    tile(s.pro, 'Pro', s.wants_pro + ' waiting to buy'),
  ));
  page.appendChild(card(signupChart(data.signups)));
  page.appendChild(h('p.muted.small', `${s.active_30d} active in the last 30 days · ${s.chose_ads} chose ads · ${s.deleted} deleted their account`));

  page.appendChild(header('Devices'));
  page.appendChild(card(data.platforms.length ? data.platforms.map((p) => row({ icon: p.platform === 'android' ? '🤖' : '🌐',
    title: p.platform === 'android' ? 'Android app' : p.platform === 'web' ? 'Web (iPhone, Mac, browsers)' : p.platform, end: h('span.muted', p.users + (p.users === 1 ? ' person' : ' people')) }))
    : row({ title: 'No devices yet' })));

  const c = data.config;
  const toggle = (on) => h('span.switch' + (on ? '.on' : ''), { 'aria-hidden': 'true' });
  page.appendChild(header('Switches'));
  page.appendChild(card(
    row({ icon: '📰', title: 'Ads', sub: c.ads_enabled ? 'On — people without Pro see the ad strip' : 'Off — nobody sees ads', end: toggle(c.ads_enabled),
      onclick: () => set({ config: { ads_enabled: !c.ads_enabled } }) }),
    row({ icon: '⭐', title: 'Offer Doable Pro', sub: c.pro_enabled ? 'On — shown on everyone\'s profile page' : 'Off', end: toggle(c.pro_enabled),
      onclick: () => set({ config: { pro_enabled: !c.pro_enabled } }) }),
    row({ icon: '🏷', title: 'Pro price', sub: c.pro_price, onclick: async () => { const v = await prompt('Pro price (as shown to people)', c.pro_price); if (v) set({ config: { pro_price: v } }); } }),
    row({ icon: '🪧', title: 'Ad strip text', sub: c.ad_text || 'Until an ad network is connected', onclick: async () => {
      const v = await prompt('Ad strip text', c.ad_text, { placeholder: 'e.g. Try Doable Pro — no ads, forever' }); if (v !== null) set({ config: { ad_text: v } });
    } }),
    row({ icon: '📣', title: 'Announcement', sub: c.announcement || 'None — shown at the top of everyone\'s app', onclick: async () => {
      const v = await prompt('Announcement for everyone', c.announcement, { placeholder: 'Empty = none' }); if (v !== null) set({ config: { announcement: v } });
    } }),
  ));

  page.appendChild(header('People (' + data.users.length + ')'));
  const search = input(filter, { type: 'search', placeholder: 'Search name or email' });
  const list = h('div');
  const fill = () => {
    list.textContent = '';
    const q = filter.toLowerCase();
    const users = data.users.filter((u) => !q || (u.email + ' ' + u.name).toLowerCase().includes(q));
    list.appendChild(card(users.length ? users.map((u) => row({
      icon: A.avatar(u, 34),
      title: (u.name || u.email) + (u.plan === 'pro' ? ' ⭐' : '') + (u.admin ? ' · owner' : ''),
      sub: `${u.email} · joined ${fmt(u.created)} · last seen ${fmt(u.last_seen)}${u.platforms ? ' · ' + u.platforms : ''}`,
      onclick: () => menu(u.name || u.email, [
        u.plan === 'pro' ? { label: 'Change to Free plan', action: () => set({ user_id: u.id, plan: 'free' }) }
          : { label: '⭐ Give Pro (e.g. paid, friends & family)', action: () => set({ user_id: u.id, plan: 'pro' }) },
        { label: '✉️ Email ' + u.email, action: () => { location.href = 'mailto:' + u.email; } },
      ]),
    })) : row({ title: 'Nobody found' })));
  };
  search.addEventListener('input', () => { filter = search.value; fill(); });
  fill();
  page.appendChild(search);
  page.appendChild(list);
}

const fmt = (t) => (t ? new Date(t).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: '2-digit' }) : '—');

export default {
  id: 'admin',
  name: 'Admin',
  icon: 'chart',
  tables: [],
  keepScroll: true,
  render,
  menu: () => [{ label: '↻ Reload', action: () => { data = null; load(); } }],
  hide() { data = null; },
};
