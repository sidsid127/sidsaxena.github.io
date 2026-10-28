// Households and shared items ("spaces"). Everything shared is end-to-end encrypted on the devices: each space has a
// random key that only its members' devices hold (wrapped for each member's public key, or for the secret in an
// invite link, which never reaches this server). This server keeps who is in which space, pending invitations and
// the encrypted rows, and hands them out — it can't read them.
// POST /api/spaces with {action, ...} and "Authorization: Bearer <session>".
const crypto = require('crypto');
const { q, sha, Fail, clean, signedIn, serve } = require('./_db.js');

const MODULES = ['grocery', 'tasks', 'notes', 'checklists', 'dates', 'wallet', 'vault'];
const TABLES = ['grocery_lists', 'grocery', 'grocery_history', 'shops', 'categories', 'tasks', 'subtasks', 'notes', 'checklists',
  'checklist_items', 'dates', 'wallet', 'logins'];
const MAX_ROW = 200000; // characters of encrypted data per row
const newId = () => crypto.randomBytes(12).toString('hex');
const str = (v, n) => (typeof v === 'string' ? v.slice(0, n) : '');
const modulesOf = (v) => JSON.stringify((Array.isArray(v) ? v : []).filter((m) => MODULES.includes(m)));

async function member(spaceId, userId) {
  const r = await q(`select m.role, s.owner_id, s.kind from space_members m join spaces s on s.id = m.space_id
    where m.space_id = $1 and m.user_id = $2`, [spaceId, userId]);
  if (!r.rows.length) throw new Fail(403, 'not_a_member');
  return r.rows[0];
}
async function owner(spaceId, userId) {
  const m = await member(spaceId, userId);
  if (m.role !== 'owner') throw new Fail(403, 'not_the_owner');
  return m;
}

const person = (r) => ({ user_id: String(r.user_id), email: r.email, name: r.name, picture: r.picture });

const actions = {
  /** This device's public key (ECDH P-256, base64 of the 65-byte raw key). */
  async key_set(b, req) {
    const { user } = await signedIn(req);
    const k = str(b.public_key, 200);
    if (!/^[A-Za-z0-9+/]{86}[A-Za-z0-9+/=]{2}$/.test(k)) throw new Fail(400, 'invalid_key');
    await q(`update users set public_key = $2 where id = $1`, [user.id, k]);
    return { ok: true };
  },

  /** Public key of the Doable account with this email (to share with them straight away), or none. */
  async user_key(b, req) {
    await signedIn(req);
    const r = await q(`select id, name, email, picture, public_key from users where lower(email) = lower($1)`, [str(b.email, 200).trim()]);
    if (!r.rows.length) return { found: false };
    const u = r.rows[0];
    return { found: true, user_id: String(u.id), name: u.name, email: u.email, picture: u.picture, public_key: u.public_key };
  },

  /** My spaces with their members, my wrapped key, invitations waiting for me and the ones I sent. */
  async list(b, req) {
    const { user } = await signedIn(req);
    const spaces = (await q(`select s.*, m.role, m.wrapped_key, m.key_id from spaces s join space_members m on m.space_id = s.id
      where m.user_id = $1 order by s.created`, [user.id])).rows;
    const ids = spaces.map((s) => s.id);
    const members = ids.length ? (await q(`select m.space_id, m.user_id, m.role, m.key_id, u.email, u.name, u.picture, u.public_key
      from space_members m join users u on u.id = m.user_id where m.space_id = any($1) order by m.joined`, [ids])).rows : [];
    const sent = ids.length ? (await q(`select id, space_id, email, status, created, wrapped_key <> '' as ready from invites
      where space_id = any($1) and status = 'pending' and expires > now() order by created`, [ids])).rows : [];
    const incoming = (await q(`select i.id, i.space_id, i.wrapped_key, i.key_id, s.name, s.kind, s.modules, u.name as from_name, u.email as from_email,
      u.picture as from_picture from invites i join spaces s on s.id = i.space_id join users u on u.id = i.from_user
      where lower(i.email) = lower($1) and i.status = 'pending' and i.expires > now()
        and not exists (select 1 from space_members m where m.space_id = i.space_id and m.user_id = $2)
      order by i.created`, [user.email, user.id])).rows;
    return {
      me: String(user.id),
      public_key: user.public_key,
      spaces: spaces.map((s) => ({
        id: s.id, kind: s.kind, name: s.name, modules: JSON.parse(s.modules), owner: String(s.owner_id), role: s.role,
        wrapped_key: s.wrapped_key, key_id: s.key_id,
        members: members.filter((m) => m.space_id === s.id).map((m) => ({ ...person(m), role: m.role, key_id: m.key_id, public_key: m.public_key })),
        invites: sent.filter((i) => i.space_id === s.id).map((i) => ({ id: i.id, email: i.email, ready: i.ready, created: i.created })),
      })),
      incoming: incoming.map((i) => ({ id: i.id, space_id: i.space_id, name: i.name, kind: i.kind, modules: JSON.parse(i.modules),
        wrapped_key: i.wrapped_key, key_id: i.key_id, from: { name: i.from_name, email: i.from_email, picture: i.from_picture } })),
    };
  },

  async create(b, req) {
    const { user } = await signedIn(req);
    const kind = b.kind === 'share' ? 'share' : 'household';
    const name = clean(b.name, 60) || (kind === 'household' ? 'Household' : 'Shared');
    if (!str(b.wrapped_key, 400)) throw new Fail(400, 'key_missing');
    const id = newId();
    await q(`insert into spaces (id, kind, name, modules, owner_id) values ($1, $2, $3, $4, $5)`, [id, kind, name, modulesOf(b.modules), user.id]);
    await q(`insert into space_members (space_id, user_id, role, wrapped_key, key_id) values ($1, $2, 'owner', $3, $4)`,
      [id, user.id, str(b.wrapped_key, 400), str(b.key_id, 80)]);
    return { id };
  },

  async update(b, req) {
    const { user } = await signedIn(req);
    await owner(b.id, user.id);
    if (b.name !== undefined) await q(`update spaces set name = $2 where id = $1`, [b.id, clean(b.name, 60) || 'Household']);
    if (b.modules !== undefined) await q(`update spaces set modules = $2 where id = $1`, [b.id, modulesOf(b.modules)]);
    return { ok: true };
  },

  /** Leave a space; the owner leaving hands it to the longest member, or deletes it when alone. */
  async leave(b, req) {
    const { user } = await signedIn(req);
    const m = await member(b.id, user.id);
    await q(`delete from space_members where space_id = $1 and user_id = $2`, [b.id, user.id]);
    const rest = (await q(`select user_id from space_members where space_id = $1 order by joined`, [b.id])).rows;
    if (!rest.length) await q(`delete from spaces where id = $1`, [b.id]);
    else if (m.role === 'owner') {
      await q(`update space_members set role = 'owner' where space_id = $1 and user_id = $2`, [b.id, rest[0].user_id]);
      await q(`update spaces set owner_id = $2 where id = $1`, [b.id, rest[0].user_id]);
    }
    return { ok: true };
  },

  async delete(b, req) {
    const { user } = await signedIn(req);
    await owner(b.id, user.id);
    await q(`delete from spaces where id = $1`, [b.id]);
    return { ok: true };
  },

  async remove_member(b, req) {
    const { user } = await signedIn(req);
    await owner(b.id, user.id);
    if (String(b.user_id) === String(user.id)) throw new Fail(400, 'use_leave');
    await q(`delete from space_members where space_id = $1 and user_id = $2`, [b.id, b.user_id]);
    return { ok: true };
  },

  /** A member's device wraps the space key again for a member whose key changed (new phone without the old key). */
  async rewrap(b, req) {
    const { user } = await signedIn(req);
    await member(b.id, user.id);
    await q(`update space_members set wrapped_key = $3, key_id = $4 where space_id = $1 and user_id = $2`,
      [b.id, b.user_id, str(b.wrapped_key, 400), str(b.key_id, 80)]);
    return { ok: true };
  },

  // ---------------------------------------------------------------- invitations

  /**
   * enc_key: the space key encrypted with the secret in the invite link (only the link has it).
   * wrapped_key: the space key wrapped for the invitee's public key, when they already have Doable.
   */
  async invite(b, req) {
    const { user } = await signedIn(req);
    await member(b.id, user.id);
    if (!/^[0-9a-f]{64}$/.test(str(b.token_hash, 64)) || !str(b.enc_key, 400)) throw new Fail(400, 'invalid_invite');
    const id = newId();
    const email = str(b.email, 200).trim().toLowerCase();
    if (email && email === user.email.toLowerCase()) throw new Fail(400, 'cannot_invite_yourself');
    await q(`insert into invites (id, space_id, from_user, email, token_hash, enc_key, wrapped_key, key_id) values ($1, $2, $3, $4, $5, $6, $7, $8)`,
      [id, b.id, user.id, email, b.token_hash, str(b.enc_key, 400), str(b.wrapped_key, 400), str(b.key_id, 80)]);
    return { id };
  },

  /** An invitation sent by email can be completed for the invitee once they have Doable (any member's device does it). */
  async invite_wrap(b, req) {
    const { user } = await signedIn(req);
    const i = (await q(`select space_id from invites where id = $1 and status = 'pending'`, [b.invite_id])).rows[0];
    if (!i) throw new Fail(404, 'no_invite');
    await member(i.space_id, user.id);
    await q(`update invites set wrapped_key = $2, key_id = $3 where id = $1`, [b.invite_id, str(b.wrapped_key, 400), str(b.key_id, 80)]);
    return { ok: true };
  },

  /** Pending invitations to emails that now have a Doable key: for members' devices to complete (see invite_wrap). */
  async invites_to_wrap(b, req) {
    const { user } = await signedIn(req);
    const r = await q(`select i.id, i.space_id, u.public_key, u.id as user_id from invites i
      join space_members m on m.space_id = i.space_id and m.user_id = $1
      join users u on lower(u.email) = i.email
      where i.status = 'pending' and i.expires > now() and u.public_key <> '' and i.wrapped_key = ''`, [user.id]);
    return { invites: r.rows.map((x) => ({ id: x.id, space_id: x.space_id, public_key: x.public_key, user_id: String(x.user_id) })) };
  },

  /** What an invite link shows before joining (needs the link's secret, as its hash). */
  async invite_open(b, req) {
    const { user } = await signedIn(req);
    const r = await q(`select i.*, s.name, s.kind, s.modules, u.name as from_name, u.email as from_email from invites i
      join spaces s on s.id = i.space_id join users u on u.id = i.from_user where i.id = $1`, [str(b.invite_id, 40)]);
    const i = r.rows[0];
    if (!i || i.token_hash !== str(b.token_hash, 64)) throw new Fail(404, 'no_invite');
    if (i.status !== 'pending' || new Date(i.expires) < new Date()) throw new Fail(410, 'invite_used');
    if (i.email && i.email !== user.email.toLowerCase()) throw new Fail(403, 'invite_for_someone_else');
    const already = (await q(`select 1 from space_members where space_id = $1 and user_id = $2`, [i.space_id, user.id])).rows.length > 0;
    return { id: i.id, space_id: i.space_id, name: i.name, kind: i.kind, modules: JSON.parse(i.modules), enc_key: i.enc_key,
      from: { name: i.from_name, email: i.from_email }, already };
  },

  /** Accept (with the space key wrapped for my own public key) or reject. */
  async invite_answer(b, req) {
    const { user } = await signedIn(req);
    const i = (await q(`select * from invites where id = $1`, [str(b.invite_id, 40)])).rows[0];
    if (!i || i.status !== 'pending' || new Date(i.expires) < new Date()) throw new Fail(410, 'invite_used');
    const byEmail = i.email && i.email === user.email.toLowerCase();
    const byLink = b.token_hash && i.token_hash === b.token_hash && (!i.email || byEmail);
    if (!byEmail && !byLink) throw new Fail(403, 'invite_for_someone_else');
    if (!b.accept) {
      await q(`update invites set status = 'rejected' where id = $1`, [i.id]);
      return { ok: true };
    }
    if (!str(b.wrapped_key, 400)) throw new Fail(400, 'key_missing');
    await q(`insert into space_members (space_id, user_id, role, wrapped_key, key_id) values ($1, $2, 'member', $3, $4)
      on conflict (space_id, user_id) do update set wrapped_key = excluded.wrapped_key, key_id = excluded.key_id`,
    [i.space_id, user.id, str(b.wrapped_key, 400), str(b.key_id, 80)]);
    // A link without an email can only be used once; so can one with an email.
    await q(`update invites set status = 'accepted' where id = $1`, [i.id]);
    return { ok: true, space_id: i.space_id };
  },

  /**
   * The invitee's key changed since the invitation was made (e.g. a new phone): forget the old wrap so a member's
   * device wraps the space key again for the current key.
   */
  async invite_reset(b, req) {
    const { user } = await signedIn(req);
    await q(`update invites set wrapped_key = '', key_id = '' where id = $1 and status = 'pending' and email = lower($2)`, [str(b.invite_id, 40), user.email]);
    return { ok: true };
  },

  async invite_cancel(b, req) {
    const { user } = await signedIn(req);
    const i = (await q(`select space_id from invites where id = $1`, [str(b.invite_id, 40)])).rows[0];
    if (!i) return { ok: true };
    await member(i.space_id, user.id);
    await q(`update invites set status = 'cancelled' where id = $1`, [b.invite_id]);
    return { ok: true };
  },

  // ---------------------------------------------------------------- shared rows

  /** Rows changed since `since` (a sequence number), oldest first, at most 500 at a time. */
  async pull(b, req) {
    const { user } = await signedIn(req);
    await member(b.id, user.id);
    const r = await q(`select tbl, uid, m, seq, data from space_rows where space_id = $1 and seq > $2 order by seq limit 500`,
      [b.id, Number(b.since) || 0]);
    const rows = r.rows.map((x) => ({ tbl: x.tbl, uid: x.uid, m: Number(x.m), data: x.data }));
    const last = r.rows.length ? Number(r.rows[r.rows.length - 1].seq) : Number(b.since) || 0;
    return { rows, since: last, more: r.rows.length === 500 };
  },

  /** Upload changed rows; a row only replaces the stored one when it is newer (m), like the merge everywhere else. */
  async push(b, req) {
    const { user } = await signedIn(req);
    await member(b.id, user.id);
    const rows = Array.isArray(b.rows) ? b.rows.slice(0, 500) : [];
    let stored = 0;
    for (const x of rows) {
      if (!TABLES.includes(x.tbl) || typeof x.uid !== 'string' || x.uid.length > 80 || typeof x.data !== 'string' || x.data.length > MAX_ROW) continue;
      const r = await q(`insert into space_rows (space_id, tbl, uid, m, data, by_user) values ($1, $2, $3, $4, $5, $6)
        on conflict (space_id, tbl, uid) do update set m = excluded.m, data = excluded.data, by_user = excluded.by_user, seq = nextval('space_seq')
        where space_rows.m < excluded.m`, [b.id, x.tbl, x.uid, Math.floor(Number(x.m)) || 0, x.data, user.id]);
      stored += r.rowCount;
    }
    return { stored };
  },
};

module.exports = serve(actions, 'spaces');
module.exports.actions = actions;
module.exports.MODULES = MODULES;
