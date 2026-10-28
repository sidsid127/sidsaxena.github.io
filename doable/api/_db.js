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
alter table users enable row level security;
alter table sessions enable row level security;
alter table config enable row level security;
alter table events enable row level security;
`;

async function q(text, params) {
  if (!ready) ready = db().query(SCHEMA).catch((e) => { ready = null; throw e; });
  await ready;
  return db().query(text, params);
}

const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');
const newToken = () => crypto.randomBytes(32).toString('base64url');

/** For tests: forget the connection so the next call uses the current DATABASE_URL. */
async function reset() {
  if (pool) await pool.end();
  pool = null;
  ready = null;
}

module.exports = { q, sha, newToken, reset };
