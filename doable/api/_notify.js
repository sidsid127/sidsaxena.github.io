// Notifications: a short notice per person (shown in the app, and by Android's background check) and a web push to
// their browsers that allowed it. Notices only say who did what kind of thing where ("Anna made changes in Saxena
// Home") — the shared content itself is encrypted and never known here.
const webpush = require('web-push');
const { q } = require('./_db.js');

let vapid = null;
/** The server's push key pair, made once and kept in the config table (the public half goes to browsers). */
async function keys() {
  if (vapid) return vapid;
  const r = await q(`select value from config where key = 'vapid'`);
  if (r.rows.length) vapid = r.rows[0].value;
  else {
    vapid = webpush.generateVAPIDKeys();
    await q(`insert into config (key, value) values ('vapid', $1) on conflict (key) do nothing`, [JSON.stringify(vapid)]);
    vapid = (await q(`select value from config where key = 'vapid'`)).rows[0].value;
  }
  webpush.setVapidDetails('mailto:doable.by.sid@gmail.com', vapid.publicKey, vapid.privateKey);
  return vapid;
}

/**
 * Tells these people. kind 'changed' is sent at most every 10 minutes per person and space (a burst of edits is one
 * notice); the others (invite, assigned, joined) always.
 */
async function notify(userIds, { kind, spaceId = null, fromUser = null, title, body = '', url = './' }) {
  for (const uid of userIds) {
    if (kind === 'changed') {
      const recent = await q(`select 1 from notices where user_id = $1 and kind = 'changed' and space_id = $2 and created > now() - interval '10 minutes'`, [uid, spaceId]);
      if (recent.rows.length) continue;
    }
    await q(`insert into notices (user_id, kind, space_id, from_user, title, body) values ($1, $2, $3, $4, $5, $6)`,
      [uid, kind, spaceId, fromUser, title.slice(0, 120), body.slice(0, 300)]);
    const subs = (await q(`select endpoint, sub from push_subs where user_id = $1`, [uid])).rows;
    if (!subs.length) continue;
    await keys();
    for (const s of subs) {
      try {
        await webpush.sendNotification(JSON.parse(s.sub), JSON.stringify({ title, body, kind, url, tag: kind + ':' + (spaceId || '') }), { TTL: 86400, timeout: 4000 });
      } catch (e) {
        if (e.statusCode === 404 || e.statusCode === 410) await q(`delete from push_subs where endpoint = $1`, [s.endpoint]); // unsubscribed
      }
    }
  }
}

module.exports = { notify, keys };
