# Daybook (Android)

A small offline Android app for two things:

- **To-do**: add tasks, optionally with a due date (calendar button), tick them off.
  Open tasks are sorted by due date; overdue ones are flagged in red.
- **Activity log**: jot down what you just did; each note is timestamped and grouped by day.

Tap any item to edit it (text, due date, or the log entry's time). Long-press to delete.
The ⋮ menu can export everything as text (share to email, Drive, WhatsApp…) and clear completed tasks.

All data is stored locally on the phone in SQLite. No account, no internet, no permissions.

## Install on your phone

1. Get `release/Daybook.apk` onto the phone (download it from GitHub on the phone, or copy it over USB).
2. Open it. Android will ask you to allow installs from that app (browser / Files) — allow it.
3. Tap **Install**. Play Protect may warn that the app is from an unknown developer; choose
   **More details → Install anyway**.

Requires Android 6.0 or newer. To update later, install a newer APK over the old one; your data is kept
as long as it is signed with the same `debug.keystore`.

## Build it yourself

The APK is built with plain command-line tools (no Gradle/Android Studio needed):

```sh
sudo apt install openjdk-17-jdk android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner
./build.sh        # -> release/Daybook.apk
```

`debug.keystore` (password `android`) is a throwaway test key, committed so rebuilds can update an
installed copy. Don't use it for anything you publish to the Play Store.

## Layout

- `AndroidManifest.xml`, `res/` — manifest, layouts, colors, icons
- `src/com/sidsaxena/daybook/Db.java` — SQLite storage
- `src/com/sidsaxena/daybook/MainActivity.java` — the whole UI
- `tools/make_icon.py` — regenerates the launcher icon PNGs
