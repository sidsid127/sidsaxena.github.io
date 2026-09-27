# Notes

A private, offline notes app for Android, built to feel like iOS Notes: a
searchable list with pinned notes at the top, a distraction-free editor
where the first line becomes the title, and lightweight checklists.

## Why this exists

You asked for something you can put your critical notes into without any
network dependency. So:

- **No `INTERNET` permission at all.** It's not in the manifest, so Android
  won't even let the app open a socket -- this isn't a "we promise not to
  use it" thing, it's structurally impossible for this app to phone home.
- **Storage is a local SQLite database (via Room)**, private to the app, in
  the phone's own app-data storage. Nothing syncs anywhere automatically.
- **Optional app lock.** Flip it on in Settings and the app requires your
  phone's fingerprint/face/PIN before showing your notes -- and re-locks
  every time you background the app, not just on a cold start.
- **Manual backup, your choice of location.** "Export Notes" writes a plain
  JSON file whereever you pick with Android's file picker (e.g. a folder,
  an SD card, a USB drive) -- useful for backups, but never automatic and
  never networked.

## What's in the app

- **Notes list** -- large title, search field, pinned section, swipe-left
  to delete, tap the "..." menu to pin/unpin.
- **Editor** -- tap `+` for a new note. The first line you type becomes the
  bold title (just like iOS Notes); everything after is the body. Saves
  automatically as you type (small debounce) and again the instant you
  leave the screen.
- **Checklists** -- put your cursor on a line and tap the checklist icon in
  the toolbar: a plain line becomes `☐ item`, and tapping the icon again
  toggles it to `☑ item`.
- **Settings** (gear icon on the notes list) -- toggle the app lock, export
  a backup, import a backup (this replaces what's currently on the phone,
  so it's meant for restoring onto a fresh install or moving devices).

## Project layout

Standard Android Studio / Gradle project:

```
android-notes-app/
  app/src/main/java/com/sidsaxena/notes/
    data/        Room entity, DAO, database, repository
    security/    optional biometric/PIN app-lock
    backup/      local JSON export/import
    ui/notes/    the notes list screen
    ui/editor/   the note editor screen
    ui/lock/     the lock screen shown when app-lock is on
    ui/settings/ the settings bottom sheet
    ui/theme/    colors/type/theme (iOS-Notes-inspired palette)
```

Kotlin + Jetpack Compose (Material 3) + Room. No Firebase, no analytics
SDK, no ad SDK, no cloud anything.

## Building it

This project needs Android Studio (or the Android SDK + command-line
tools) to compile, because it depends on the Android platform and
AndroidX/Compose libraries, which live on Google's Maven repository.
**That repository was not reachable from the sandboxed environment this
project was written in**, so the code below has been written and reviewed
carefully but has *not* been compiled in this session -- you'll get the
first real compiler feedback when you open it on your own machine. If
anything doesn't compile cleanly, it should only be small things like a
library version bump (Android Studio will offer to fix these for you).

1. Install [Android Studio](https://developer.android.com/studio) (free).
2. `File > Open`, pick the `android-notes-app` folder.
3. Let Gradle sync (first sync downloads the Android SDK bits it needs --
   this is the one time the *build tooling* needs the internet; the app
   itself never will).
4. Plug your Samsung phone in via USB with Developer Options + USB
   debugging enabled (Settings > About phone > tap "Build number" 7 times,
   then Settings > Developer options > USB debugging), or use
   `Build > Generate Signed Bundle / APK` to produce an APK you can
   sideload.
5. Click Run (▶) with your phone selected, or install the built APK
   directly (`app/build/outputs/apk/debug/app-debug.apk`) via
   `adb install app-debug.apk`.

No Play Store listing needed -- this is meant to stay a personal,
sideloaded app.

## If a dependency version has moved on

The versions pinned in `gradle/libs.versions.toml` were the latest stable
releases as of early 2026. If Gradle can't resolve one by the time you
build, Android Studio will flag it and usually offer a one-click upgrade;
otherwise bump the version in that file.
