# Households and sharing (shared by Android and the web app)

People share through **spaces**: a *household* (a family, flatmates) or a *share* with one person. Everything
shared is **end-to-end encrypted** on the devices; Doable's server (`api/spaces`) keeps who is in which space,
pending invitations and encrypted rows, and can't read them. Web: `js/e2e.js`, `js/spaces.js`. Android:
`E2e.java`, `Spaces.java`. Both are checked against each other (test vectors in both directions).

## Keys

* **Sharing key pair** per person: ECDH P-256. The public key (65-byte raw point, base64) is on the server
  (`users.public_key`). The private key (PKCS#8, base64) is kept on each device and in the person's own Google
  Drive app folder as `doable.identity` (`{"v":1,"pub":…,"priv":…}`), so all their devices have it.
* **Key id**: first 16 hex characters of SHA-256(`"doable-key:" + public key`). A wrap remembers the key id it
  was made for; when a member's key changed (new device without the old key), any other member's device wraps
  the space key again for the new key (`rewrap`).
* **Space key**: 32 random bytes (AES-256), made by the device that creates the space.

## Formats

* **Wrap** (space key for one public key): ephemeral P-256 key pair; `shared` = ECDH x-coordinate (32 bytes);
  `k` = HKDF-SHA256(ikm = shared, salt = ephemeral public key (65), info = `"doable-wrap-v1"`, 32 bytes);
  `base64(ephemeral public key (65) | iv (12) | AES-256-GCM(k, iv, space key, aad = recipient public key))`.
* **Invite link**: `https://doable-sid.vercel.app/?join=<invite id>#<token>`, token = base64url of 16 random bytes,
  only in the link (the part after `#` never reaches a server). `k` = HKDF-SHA256(ikm = token bytes, salt = 32 zero
  bytes, info = `"doable-invite-v1"`); `enc_key = base64(iv | AES-GCM(k, iv, space key, aad = "doable-invite"))`;
  the server keeps `token_hash` = hex SHA-256(`"doable-invite:" + token`) to check the link.
* **Row**: the sync document's row entry (`{"m":…,"r":{…}}` or `{"m":…,"d":1}`, see SYNC.md) as UTF-8 JSON,
  `base64(iv (12) | AES-GCM(space key, iv, json, aad = "<space id>/<table>/<uid>"))`.

## Which rows are shared

A row belongs to a space through its own `space` column — `grocery_lists`, `categories`, `tasks`, `notes`,
`checklists`, `dates`, `wallet` — or through its parent: `grocery` and `grocery_history` → list, `subtasks` → task,
`checklist_items` → checklist, `tasks` → category, `categories` → parent category. Shops used by shared
groceries are sent along. Built-in starting rows (`seed-…` uids, the same on every device) are copied to a new
uid when shared, their items moved along, so sharing "Groceries" never merges with everyone's own list.

## Keeping in step

Each device pushes rows of a space whose `m` is newer than what it pushed or received last, and a tombstone
when a row was deleted or moved out of the space; the server keeps a row only if its `m` is newer (same merge
as sync). Devices pull rows changed since their cursor (a server sequence number). Leaving or being removed
from a space removes its rows from that person's devices. Photos and attachments are not shared yet.

## Invitations

Invite by link (anyone with the link, once) or by email: if the email already has a Doable key, the space key
is wrapped for it straight away and the person sees the invitation in the app (Accept / Reject); otherwise any
member's device completes it once that person has signed in to Doable (`invites_to_wrap` / `invite_wrap`).
