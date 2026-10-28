// Doable accounts: sign in with Google, profile, sign out, delete the account, the ads / Pro switches and the admin
// page. Called as POST /api/account with {action, ...}; signed-in calls send "Authorization: Bearer <session>".
// Identity comes from a Google access token that this server checks with Google (it must be issued to Doable's own
// web or Android client). No user content ever comes here.
const { q, sha, newToken, Fail, signedIn, clean } = require('./_db.js');

const CLIENT_IDS = (process.env.GOOGLE_CLIENT_IDS || [
  '183622277089-4cmkatrev66hlel5sm4g5pv5k19s5l51.apps.googleusercontent.com', // web
  '183622277089-3tpjo73hok0qib5bnid2p97nbvankabp.apps.googleusercontent.com', // Android
].join(',')).split(',').map((s) => s.trim()).filter(Boolean);
const admins = () => (process.env.ADMIN_EMAILS || '').toLowerCase().split(',').map((s) => s.trim()).filter(Boolean);

/** Switches the admin page can change; everything starts switched off. */
const CONFIG_DEFAULTS = {
  ads_enabled: false,     // show the ad strip to people who chose ads (or haven't chosen yet)
  pro_enabled: false,     // offer Doable Pro (no ads, forever) on the profile page
  pro_price: '4.99 EUR, once',
  ad_text: '',            // what the ad strip shows until a real ad network is connected
  announcement: '',       // a short message shown at the top of everyone's app
};


async function config() {
  const r = await q('select key, value from config');
  const c = { ...CONFIG_DEFAULTS };
  for (const x of r.rows) if (x.key in c) c[x.key] = x.value;
  return c;
}

function userOut(u) {
  return { id: String(u.id), email: u.email, name: u.name, picture: u.picture, plan: u.plan, ads_choice: u.ads_choice,
    created: u.created, admin: admins().includes(u.email.toLowerCase()) };
}

/** Asks Google who the access token belongs to; it must be one of Doable's own clients. */
async function googleUser(accessToken) {
  if (typeof accessToken !== 'string' || accessToken.length < 20) throw new Fail(400, 'invalid_request');
  const info = await (await fetch('https://oauth2.googleapis.com/tokeninfo?access_token=' + encodeURIComponent(accessToken))).json();
  if (info.error || !CLIENT_IDS.includes(info.azp || info.aud)) throw new Fail(401, 'google_token_rejected');
  if (!info.sub || !info.email || String(info.email_verified) !== 'true') throw new Fail(401, 'google_email_missing');
  let profile = {};
  try {
    const r = await fetch('https://www.googleapis.com/oauth2/v3/userinfo', { headers: { Authorization: 'Bearer ' + accessToken } });
    if (r.ok) profile = await r.json();
  } catch (e) { /* name and photo are optional */ }
  return { sub: info.sub, email: info.email, name: profile.name || '', picture: profile.picture || '' };
}



const actions = {
  async config() {
    return { config: await config() };
  },

  async signin(b) {
    const g = await googleUser(b.access_token);
    const platform = clean(b.platform, 20);
    const r = await q(`insert into users (google_sub, email, name, picture, platform) values ($1, $2, $3, $4, $5)
      on conflict (google_sub) do update set email = excluded.email,
        name = case when users.name = '' then excluded.name else users.name end,
        picture = case when excluded.picture <> '' then excluded.picture else users.picture end,
        platform = excluded.platform, last_seen = now()
      returning *, (xmax = 0) as is_new`, [g.sub, g.email, g.name, g.picture, platform]);
    const u = r.rows[0];
    if (u.is_new) await q(`insert into events (kind) values ('signup')`);
    const token = newToken();
    await q(`insert into sessions (token_hash, user_id, platform, device) values ($1, $2, $3, $4)`, [sha(token), u.id, platform, clean(b.device, 60)]);
    return { session: token, user: userOut(u), config: await config(), new_account: u.is_new };
  },

  async me(b, req) {
    const { user } = await signedIn(req);
    return { user: userOut(user), config: await config() };
  },

  async profile(b, req) {
    const { user } = await signedIn(req);
    const name = clean(b.name, 80);
    if (!name) throw new Fail(400, 'name_missing');
    const u = await q(`update users set name = $2 where id = $1 returning *`, [user.id, name]);
    return { user: userOut(u.rows[0]) };
  },

  /** "ads" (free, with ads) or "pro" (asks to buy Pro; the plan itself only changes after payment or by the admin). */
  async choose(b, req) {
    const { user } = await signedIn(req);
    if (!['ads', 'pro'].includes(b.choice)) throw new Fail(400, 'invalid_choice');
    const u = await q(`update users set ads_choice = $2 where id = $1 returning *`, [user.id, b.choice]);
    return { user: userOut(u.rows[0]) };
  },

  async signout(b, req) {
    const { tokenHash } = await signedIn(req);
    await q(`delete from sessions where token_hash = $1`, [tokenHash]);
    return { ok: true };
  },

  /** Deletes the account, its sessions and everything else tied to it on this server. */
  async delete(b, req) {
    const { user } = await signedIn(req);
    // Households this person owns go to their longest member instead of disappearing for everyone.
    const owned = (await q(`select id from spaces where owner_id = $1`, [user.id])).rows;
    for (const s of owned) {
      const next = (await q(`select user_id from space_members where space_id = $1 and user_id <> $2 order by joined limit 1`, [s.id, user.id])).rows[0];
      if (!next) continue;
      await q(`update spaces set owner_id = $2 where id = $1`, [s.id, next.user_id]);
      await q(`update space_members set role = 'owner' where space_id = $1 and user_id = $2`, [s.id, next.user_id]);
    }
    await q(`delete from users where id = $1`, [user.id]);
    await q(`insert into events (kind) values ('deleted')`);
    return { ok: true };
  },

  async admin(b, req) {
    const { user } = await signedIn(req);
    if (!admins().includes(user.email.toLowerCase())) throw new Fail(403, 'not_admin');
    const one = async (sql) => Number((await q(sql)).rows[0].n);
    const stats = {
      users: await one(`select count(*) n from users`),
      new_today: await one(`select count(*) n from users where created > now() - interval '1 day'`),
      new_7d: await one(`select count(*) n from users where created > now() - interval '7 days'`),
      new_30d: await one(`select count(*) n from users where created > now() - interval '30 days'`),
      active_1d: await one(`select count(*) n from users where last_seen > now() - interval '1 day'`),
      active_7d: await one(`select count(*) n from users where last_seen > now() - interval '7 days'`),
      active_30d: await one(`select count(*) n from users where last_seen > now() - interval '30 days'`),
      pro: await one(`select count(*) n from users where plan = 'pro'`),
      chose_ads: await one(`select count(*) n from users where ads_choice = 'ads'`),
      wants_pro: await one(`select count(*) n from users where ads_choice = 'pro' and plan <> 'pro'`),
      deleted: await one(`select count(*) n from events where kind = 'deleted'`),
      households: await one(`select count(*) n from spaces where kind = 'household'`),
      shares: await one(`select count(*) n from spaces where kind = 'share'`),
    };
    const platforms = (await q(`select platform, count(distinct user_id) n from sessions group by platform order by 2 desc`)).rows
      .map((r) => ({ platform: r.platform || 'unknown', users: Number(r.n) }));
    const signups = (await q(`select to_char(d, 'YYYY-MM-DD') as on_day, (select count(*) from users where created::date = d::date) n
      from generate_series(current_date - 29, current_date, interval '1 day') d order by d`)).rows.map((r) => ({ day: r.on_day, n: Number(r.n) }));
    const users = (await q(`select u.*, coalesce(string_agg(distinct s.platform, ', '), '') platforms from users u
      left join sessions s on s.user_id = u.id group by u.id order by u.created desc limit 500`)).rows
      .map((u) => ({ ...userOut(u), last_seen: u.last_seen, platforms: u.platforms }));
    return { stats, platforms, signups, users, config: await config() };
  },

  async admin_set(b, req) {
    const { user } = await signedIn(req);
    if (!admins().includes(user.email.toLowerCase())) throw new Fail(403, 'not_admin');
    if (b.config && typeof b.config === 'object') {
      for (const [k, v] of Object.entries(b.config)) {
        if (!(k in CONFIG_DEFAULTS) || typeof v !== typeof CONFIG_DEFAULTS[k]) continue;
        await q(`insert into config (key, value) values ($1, $2) on conflict (key) do update set value = excluded.value`,
          [k, JSON.stringify(typeof v === 'string' ? clean(v, 300) : v)]);
      }
    }
    if (b.user_id && ['free', 'pro'].includes(b.plan)) await q(`update users set plan = $2 where id = $1`, [b.user_id, b.plan]);
    return { config: await config() };
  },
};

/** What kind of address DATABASE_URL is (never the address or password itself), to help fix a wrong one. */
function urlHint() {
  try {
    const u = new URL(process.env.DATABASE_URL);
    return { protocol: u.protocol, port: u.port, pooler: /pooler\.supabase\.com$/.test(u.hostname), direct: /^db\./.test(u.hostname),
      user_has_project: u.username.includes('.'), password_placeholder: /YOUR-PASSWORD|%5B|\[/i.test(u.password), password_set: !!u.password };
  } catch (e) { return { parse_error: true }; }
}

module.exports = async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  if (req.method === 'GET') {
    if (!process.env.DATABASE_URL) return res.status(200).json({ configured: false });
    try { return res.status(200).json({ configured: true, ...(await actions.config()) }); } catch (e) {
      console.error('database', e.code, e.message);
      return res.status(503).json({ configured: true, error: 'database_unreachable', code: e.code || '', hint: urlHint() });
    }
  }
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });
  let b = req.body || {};
  if (typeof b === 'string') { try { b = JSON.parse(b); } catch (e) { b = {}; } }
  const act = Object.prototype.hasOwnProperty.call(actions, b.action) ? actions[b.action] : null;
  if (!act) return res.status(400).json({ error: 'unknown_action' });
  try {
    return res.status(200).json(await act(b, req));
  } catch (e) {
    if (e instanceof Fail || e.status) return res.status(e.status).json({ error: e.message });
    console.error('account', b.action, e);
    return res.status(500).json({ error: 'server_error' });
  }
};
module.exports.actions = actions;
