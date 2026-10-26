# Doable (Android)

An offline organiser: to-do list, notes, groceries and a password safe. All data stays on the phone:
no account, no internet permission, and Android cloud backup is switched off.

Open the ☰ side menu to switch between modules. The ⋮ menu has each module's options plus the
fingerprint app lock and the theme (same as phone / light / dark).

- **To-do**: categories with sub-categories (chips at the top; press and hold a chip to rename/delete),
  a priority flag on every task (tap to cycle, hold to pick), due dates with a light-red **+ Add due date**
  shortcut. When the list spans several categories it is grouped: high-priority tasks first, then one
  section per category / sub-category. Completed tasks stay struck through for 12 hours, then hide
  (⋮ → *Show all completed tasks*).
- **Notes**: like the iPhone Notes app — searchable list, pinned notes on top, full-screen editor where the
  first line is the title. Notes save automatically; use the keyboard's microphone to dictate.
- **Groceries**: pick a shop chip (Rewe, Lidl, Aldi, or add your own) and add items; the list is grouped by
  shop. Tap an item to tick it off. Everything you've ever added appears under **Buy again** — one tap
  puts it back on the list with its shop. ⋮ → *Clear bought items* empties the cart.
- **Passwords**: logins encrypted with AES-256-GCM. The encryption key is protected by the Android
  Keystore (secure hardware) and, with *Require fingerprint* on, can only be used right after your
  fingerprint, face or screen lock. The screen blocks screenshots and locks itself when you leave it.
  Note: if you remove the phone's screen lock, Android deletes that key and the saved passwords can't
  be recovered — use ⋮ → *Export passwords* first if you ever plan to do that.
- **Activity log**: timestamped notes of what you did.

## Install on your phone

1. Get `release/Doable.apk` onto the phone (download it on the phone, or copy it over USB).
2. Open it and allow installs from that app (browser / Files) if Android asks.
3. Tap **Install** (or **Update**). If Play Protect warns about an unknown developer: **More details → Install anyway**.

Installing a newer APK over an older one keeps your data, as long as it is signed with the same `debug.keystore`.
Requires Android 6.0 or newer.

## Build it yourself

Built with plain command-line tools (no Gradle/Android Studio needed):

```sh
sudo apt install openjdk-17-jdk android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner
./build.sh        # -> release/Doable.apk
```

The first build downloads the Android 14 framework jar from Maven Central (compile-time only, for the
fingerprint API). `debug.keystore` (password `android`) is a throwaway test key committed so rebuilds can
update an installed copy; don't use it for a Play Store release.

## Layout

- `AndroidManifest.xml`, `res/` — manifest, layouts, colours, icons (rendered from `../brand/logo.svg`)
- `src/com/sidsaxena/daybook/Db.java` — SQLite storage (tasks, categories, activity log)
- `src/com/sidsaxena/daybook/MainActivity.java` — the whole UI, including the app lock

The app was first called Daybook; its internal package id (`com.sidsaxena.daybook`) is kept so that
updates install over existing copies without losing data.
