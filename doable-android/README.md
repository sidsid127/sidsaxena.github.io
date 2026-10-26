# Doable (Android)

An offline to-do app. All data stays on the phone in SQLite: no account, no internet permission,
and Android cloud backup is switched off.

- **Categories with sub-categories**: chips at the top (All · Private · Work …). Pick Work to see a second
  row with its sub-categories (e.g. Accenture, Customer A/B/C). New tasks go into the selected category.
  Tap **+ Category** / **+ Sub-category** to add; press and hold a chip to rename or delete it.
- **Priority flag** on the right of every task: tap to cycle None → Low → Medium → High, press and hold
  to pick directly. Higher priority sorts first, then the soonest due date.
- **Due dates**: shown under each task; tasks without one show a light-red **+ Add due date** shortcut.
- **Completed tasks** stay struck through for 12 hours, then hide. ⋮ → *Show all completed tasks* shows them all.
- **Fingerprint lock** (⋮ menu): asks for your fingerprint (or phone PIN) whenever the app is opened.
- ⋮ also has the optional **Activity log**, **Export / share all** as text, and **Delete completed tasks**.

Tap a task to edit its text, category, priority and due date. Long-press a task to delete it.

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
