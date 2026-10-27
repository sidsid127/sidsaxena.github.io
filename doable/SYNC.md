# Doable sync format (shared by Android and the web app)

Sync is optional. When it is on, every device keeps its own full copy of the data (it still works
offline) and exchanges changes through the user's own Google Drive, in the hidden *app data* folder that
only Doable can see. Everything Doable puts on Drive is **end-to-end encrypted** with a key derived from
the user's *sync passphrase*; Google only ever stores ciphertext.

## Files in Drive `appDataFolder`

| name | content |
| --- | --- |
| `doable.sync` | the whole data set (see below), encrypted |
| `att-<file>` | one photo / scan / PDF / voice note, encrypted; `<file>` is the attachment's file name |

## Encryption

Key: PBKDF2-HMAC-SHA256(passphrase as UTF-8, salt, iterations) → 32 bytes, AES-256-GCM.

`doable.sync`:

```
"DOABLESYNC1"           11 bytes ASCII (also the GCM additional data)
salt                    16 bytes
iterations              uint32, big endian (210000)
iv                      12 bytes
ciphertext || tag       AES-GCM of the UTF-8 JSON document, 128-bit tag
```

The first device to sync creates the salt; the others read it from the file header, derive the key
from the passphrase and check it by decrypting. A wrong passphrase fails the GCM tag.

`att-<file>`: `"DOABLEFILE1"` | iv (12) | AES-GCM(file bytes), additional data = `"DOABLEFILE1" + file`.

## Document

```json
{
  "format": 1,
  "updated": 1767000000000,
  "by": "Pixel 8",
  "tables": {
    "tasks": {
      "3f2a…": { "m": 1767000000000, "r": { "title": "Call mum", "due": "2026-10-28", "category": "seed-cat-private", … } },
      "9b1c…": { "m": 1767000001234, "d": 1 }
    }
  },
  "files": ["0c5e….jpg"]
}
```

* Each row has a stable `uid`, a modification time `m` (ms since epoch) and either its columns `r` or
  `d: 1` (deleted — a tombstone, kept 180 days).
* **Merge** = per row, the higher `m` wins; on a tie the version already in the document wins (so all
  devices converge on what was uploaded first).
* Column names are the Android SQLite column names (without `_id`). Booleans are `0/1`.
* **Days** (no time of day) are `"YYYY-MM-DD"` strings, `""` = none — so they mean the same day in any
  time zone. Moments (`created`, `done_at`, `modified`, `snooze`, `ts`, …) are ms since epoch.
* **References** to other rows are the other row's `uid` (`""` = none). `attachments.owner_id` points into
  the table named by `owner`: `note`→notes, `diary`→diary, `expense`→expenses, `trip_item`→trip_items,
  `wallet`→wallet.
* Colours are ARGB ints as on Android (`0xFF3F2CB8` → -12637000).
* `files` lists attachment files already uploaded as `att-<file>`.

### Tables

| table | columns (→ reference, ⓓ day) |
| --- | --- |
| categories | name, parent→categories, color |
| tasks | title, done, due ⓓ, created, done_at, category→categories, priority, position, time, repeat, notes, snooze |
| subtasks | task→tasks, text, done, position |
| log | text, ts |
| notes | body, pinned, created, modified, color, folder, locked, deleted |
| shops | name, color |
| grocery_lists | name |
| grocery | name, shop→shops, done, created, qty, list→grocery_lists, aisle |
| grocery_history | name, shop→shops, uses, last_used, list→grocery_lists |
| prices | name, shop→shops, cents, qty, day ⓓ |
| diary | day ⓓ, mood, text, highs, lows, modified, place, lat, lon, weather, temp |
| trips | name, kind, destination, start ⓓ, finish ⓓ, currency, created, budget, rates, pd_full, pd_partial, km_rate, per_diem |
| expenses | trip→trips, day ⓓ, category, cents, currency, merchant, attendees, payment, note, submitted, created, km |
| trip_items | trip→trips, kind, subtype, title, body, day ⓓ, done, created |
| attachments | owner, owner_id→(see above), file, name, created |
| habits | name, emoji, quit, target, days, remind, color, archived, created ⓓ |
| habit_log | habit→habits, day ⓓ, count |
| wallet | type, title, number, holder, expiry ⓓ, barcode, notes, created |
| dates | title, kind, month, day, year, yearly, remind_days, notes, created |
| checklists | title, trip→trips, template, created |
| checklist_items | list→checklists, text, done, position |
| logins | type, title, folder, url, user, password, number, holder, expiry, cvv, pin, notes, modified (passwords; uid = login id) |

Locked notes are stored with their plain `body` and `locked: 1` — the document itself is encrypted.

### uids

* Random 32 hex characters, except:
* Rows that are unique by content get a uid made from it, so two devices creating "the same" row
  merge instead of duplicating: `diary:<day>`, `hl:<habit uid>:<day>`, `gh:<name lower-cased, ASCII only>`.
* Built-in starting data has fixed uids (stored with `m = 1` by a fresh install, so any real edit wins):
  `seed-cat-private|work|accenture`, `seed-shop-rewe|lidl|aldi`,
  `seed-list-groceries|drugstore|hardware`, `seed-tpl-business|holiday|weekend|leaving` and their items
  `seed-tpl-<name>-<n>` (n = 1, 2, … in template order).

## A sync round

1. Get an access token (Google OAuth, scope `drive.appdata`).
2. Download `doable.sync` (if any) and decrypt it.
3. Merge it with the local data; write the merged rows that differ back into the local database.
4. Upload attachment files the document doesn't list yet; download missing ones (on Android all, on the
   web lazily when shown).
5. If the merged document differs from what was downloaded, upload it.
