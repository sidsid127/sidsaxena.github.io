// Profile: who is signed in, plan (free / Pro) and the ads-or-Pro choice, sync, help, sign out, delete the account.
import * as A from './account.js';
import * as Sync from './sync.js';
import * as SP from './spaces.js';
import { app } from './ctx.js';
import { h, card, row, header, prompt, toast, sheet } from './ui.js';

function render(view) {
  const u = A.user();
  const page = h('div.page');
  if (!u) {
    page.appendChild(h('div.empty', 'Not signed in.'));
    page.appendChild(h('button.btn.primary', { type: 'button', onclick: () => A.welcome() }, 'Sign in with Google'));
    view.appendChild(page);
    return;
  }
  const since = u.created ? new Date(u.created).toLocaleDateString(undefined, { month: 'long', year: 'numeric' }) : '';
  page.appendChild(card(h('div.profile-head',
    A.avatar(u, 72),
    h('div.profile-name', u.name || u.email.split('@')[0]),
    h('div.muted', u.email),
    h('div.profile-badges', h('span.badge' + (u.plan === 'pro' ? '.pro' : ''), u.plan === 'pro' ? '⭐ Pro' : 'Free plan'),
      since ? h('span.muted', 'Member since ' + since) : null))));

  page.appendChild(header('Account'));
  page.appendChild(card(
    row({ icon: '✏️', title: 'Name', sub: u.name || 'Not set', onclick: async () => {
      const n = await prompt('Your name', u.name);
      if (!n) return;
      try { await A.rename(n); app.refresh(); } catch (e) { toast('Couldn\'t save — are you online?'); }
    } }),
    row({ icon: '🔄', title: 'Sync & backup', sub: Sync.statusLine(), onclick: () => Sync.settingsSheet() }),
    row({ icon: '🏠', title: 'Household & sharing', sub: SP.households().map((x) => x.name).join(', ') || 'Share lists with your family',
      end: SP.incoming().length ? h('span.nav-badge', String(SP.incoming().length)) : null, onclick: () => app.go('household') }),
  ));

  const c = A.config();
  if (c.pro_enabled || c.ads_enabled) {
    page.appendChild(header('Ads or Pro'));
    page.appendChild(card(u.plan === 'pro'
      ? row({ icon: '⭐', title: 'You have Doable Pro', sub: 'No ads, forever. Thank you for supporting Doable!' })
      : [
        row({ icon: '📰', title: 'Free, with a small ad', sub: u.ads_choice === 'ads' ? '✓ Your choice' : 'A small banner at the bottom of the screen',
          onclick: async () => { try { await A.choose('ads'); app.refresh(); } catch (e) { toast('Couldn\'t save — are you online?'); } } }),
        c.pro_enabled ? row({ icon: '⭐', title: 'Doable Pro — ' + c.pro_price, sub: 'No ads, forever, on all your devices', onclick: buyPro }) : null,
      ]));
  }

  page.appendChild(header('Help'));
  page.appendChild(card(
    row({ icon: '✉️', title: 'Contact', sub: A.CONTACT, onclick: () => { location.href = 'mailto:' + A.CONTACT + '?subject=Doable'; } }),
    row({ icon: '🔏', title: 'Privacy policy', onclick: () => open('privacy.html', '_blank') }),
    row({ icon: '📄', title: 'Terms', onclick: () => open('terms.html', '_blank') }),
  ));

  if (A.isAdmin()) {
    page.appendChild(header('Owner'));
    page.appendChild(card(row({ icon: '📊', title: 'Admin page', sub: 'Users, sign-ups, ads and Pro switches', onclick: () => app.go('admin') })));
  }

  page.appendChild(header(''));
  page.appendChild(card(
    row({ icon: '🚪', title: 'Sign out', sub: 'On this device', onclick: () => A.signOut() }),
    row({ icon: '🗑', title: 'Delete account', sub: 'Your account and all your Doable data', cls: 'danger-row', onclick: () => A.deleteAccount() }),
  ));
  view.appendChild(page);
}

function buyPro() {
  sheet({
    title: '⭐ Doable Pro',
    body: [h('p', 'Pay once, use Doable without ads forever — on all your devices.'), h('p.muted', 'Buying opens soon. Tap "Notify me" and we\'ll keep Doable ad-free for you as soon as it does.')],
    buttons: [{ label: 'Not now' }, { label: 'Notify me', primary: true, action: async () => {
      try { await A.choose('pro'); toast('Noted — thank you!'); app.refresh(); } catch (e) { toast('Couldn\'t save — are you online?'); }
    } }],
  });
}

export default {
  id: 'profile',
  name: 'Profile',
  icon: 'person',
  tables: [],
  render,
};
