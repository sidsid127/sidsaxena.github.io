# Doable for iPhone, iPad and Mac (web app)

The same Doable as the Android app, as an installable web app: one version for iPhone and Mac, no App Store
and no Apple developer account. After the first visit it works **offline**; your data lives on the device
(IndexedDB). Optional **sync** with your Android phone and other devices goes through your own Google Drive,
end-to-end encrypted with your sync passphrase (see [SYNC.md](SYNC.md) and [GOOGLE-SETUP.md](GOOGLE-SETUP.md)).

## Install

* **iPhone / iPad:** open <https://doable-nu-lovat.vercel.app> in **Safari** → Share → **Add to Home Screen**.
* **Mac:** Safari 17+ → File → **Add to Dock**; or Chrome / Edge → install icon in the address bar.

## What's different from Android

Web apps can't do everything a native app can:

* **Reminders** pop up only while Doable is open (no notifications when it's closed). Keep using the Android
  app for reminders — synced tasks, habits and dates still remind you there.
* No home-screen widgets, no password autofill into other apps, no "Share → Doable" on iPhone.
* Passwords use a **master password** (or Face ID / Touch ID on recent iOS / macOS) instead of the fingerprint.
* App lock: PIN, optionally with Face ID / Touch ID. Locked notes and Wallet are behind it.

The first web version (`/todo/`) now forwards here; its tasks, notes, groceries and log are brought over.

## Files

`index.html`, `app.css`, `js/*` (one module per screen), `sw.js` (offline cache), `auth.html` (Google sign-in
return page), `config.js` (Google client ID). Tests: `node test/logic.test.mjs`.
