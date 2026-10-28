// Doable's server database (Postgres, address in the Vercel environment variable DATABASE_URL). It holds accounts,
// sign-in sessions and app switches only — never anyone's notes, tasks or credentials. Tables are created on first use.
const { Pool } = require('pg');
const crypto = require('crypto');

let pool = null;
let ready = null;

function db() {
  if (!process.env.DATABASE_URL) throw Object.assign(new Error('not_configured'), { status: 501 });
  if (!pool) {
    const url = process.env.DATABASE_URL;
    const local = /@(localhost|127\.0\.0\.1)[:/]/.test(url);
    pool = new Pool({ connectionString: url, max: 3, ssl: local ? false : { rejectUnauthorized: false } });
  }
  return pool;
}

// Every table has row level security on and no policies: the database's public web API (anon key) can read nothing.
// Only this server, connecting as the database owner, can.
const SCHEMA = `
create table if not exists users (
  id bigserial primary key,
  google_sub text unique not null,
  email text not null,
  name text not null default '',
  picture text not null default '',
  created timestamptz not null default now(),
  last_seen timestamptz not null default now(),
  platform text not null default '',
  plan text not null default 'free',
  ads_choice text not null default ''
);
create table if not exists sessions (
  token_hash text primary key,
  user_id bigint not null references users(id) on delete cascade,
  created timestamptz not null default now(),
  last_used timestamptz not null default now(),
  platform text not null default '',
  device text not null default ''
);
create index if not exists sessions_user on sessions(user_id);
create table if not exists config (key text primary key, value jsonb not null);
create table if not exists events (id bigserial primary key, kind text not null, at timestamptz not null default now());
alter table users add column if not exists public_key text not null default '';
create table if not exists spaces (
  id text primary key,
  kind text not null default 'household',
  name text not null default '',
  modules text not null default '[]',
  owner_id bigint not null references users(id) on delete cascade,
  created timestamptz not null default now()
);
create table if not exists space_members (
  space_id text not null references spaces(id) on delete cascade,
  user_id bigint not null references users(id) on delete cascade,
  role text not null default 'member',
  wrapped_key text not null default '',
  key_id text not null default '',
  joined timestamptz not null default now(),
  primary key (space_id, user_id)
);
create index if not exists space_members_user on space_members(user_id);
create table if not exists invites (
  id text primary key,
  space_id text not null references spaces(id) on delete cascade,
  from_user bigint not null references users(id) on delete cascade,
  email text not null default '',
  token_hash text not null,
  enc_key text not null,
  wrapped_key text not null default '',
  key_id text not null default '',
  status text not null default 'pending',
  created timestamptz not null default now(),
  expires timestamptz not null default now() + interval '30 days'
);
create index if not exists invites_email on invites(lower(email));
create sequence if not exists space_seq;
create table if not exists space_rows (
  space_id text not null references spaces(id) on delete cascade,
  tbl text not null,
  uid text not null,
  m bigint not null,
  seq bigint not null default nextval('space_seq'),
  data text not null,
  by_user bigint,
  primary key (space_id, tbl, uid)
);
create index if not exists space_rows_seq on space_rows(space_id, seq);
alter table space_rows add column if not exists at timestamptz not null default now();
create table if not exists notices (
  id bigserial primary key,
  user_id bigint not null references users(id) on delete cascade,
  kind text not null,
  space_id text,
  from_user bigint,
  title text not null,
  body text not null default '',
  created timestamptz not null default now()
);
create index if not exists notices_user on notices(user_id, id);
create table if not exists push_subs (
  endpoint text primary key,
  user_id bigint not null references users(id) on delete cascade,
  sub text not null,
  created timestamptz not null default now()
);
alter table notices enable row level security;
alter table push_subs enable row level security;
alter table users enable row level security;
alter table spaces enable row level security;
alter table space_members enable row level security;
alter table invites enable row level security;
alter table space_rows enable row level security;
alter table sessions enable row level security;
alter table config enable row level security;
alter table events enable row level security;
`;

async function q(text, params) {
  if (!ready) ready = db().query(SCHEMA).catch((e) => { ready = null; throw e; });
  await ready;
  return db().query(text, params);
}

class Fail extends Error { constructor(status, code) { super(code); this.status = status; } }
const clean = (s, n) => String(s == null ? '' : s).replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, n);

/** The signed-in user for "Authorization: Bearer <session>" (and marks them active). */
async function signedIn(req) {
  const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
  if (!m) throw new Fail(401, 'signed_out');
  const r = await q(`update sessions set last_used = now() where token_hash = $1 returning user_id`, [sha(m[1])]);
  if (!r.rows.length) throw new Fail(401, 'signed_out');
  const u = await q(`update users set last_seen = now() where id = $1 returning *`, [r.rows[0].user_id]);
  if (!u.rows.length) throw new Fail(401, 'signed_out');
  return { user: u.rows[0], tokenHash: sha(m[1]) };
}

/** POST {action, ...} → actions[action](body, req); errors become JSON {error}. */
function serve(actions, name) {
  return async function handler(req, res) {
    res.setHeader('Cache-Control', 'no-store');
    if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });
    let b = req.body || {};
    if (typeof b === 'string') { try { b = JSON.parse(b); } catch (e) { b = {}; } }
    const act = Object.prototype.hasOwnProperty.call(actions, b.action) ? actions[b.action] : null;
    if (!act) return res.status(400).json({ error: 'unknown_action' });
    try {
      return res.status(200).json(await act(b, req));
    } catch (e) {
      if (e instanceof Fail || e.status) return res.status(e.status).json({ error: e.message });
      console.error(name, b.action, e);
      return res.status(500).json({ error: 'server_error' });
    }
  };
}

const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');
const newToken = () => crypto.randomBytes(32).toString('base64url');

/** For tests: forget the connection so the next call uses the current DATABASE_URL. */
async function reset() {
  if (pool) await pool.end();
  pool = null;
  ready = null;
}

module.exports = { q, sha, newToken, reset, Fail, clean, signedIn, serve };
