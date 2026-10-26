# Doable for iPhone (offline web app)

The same to-do app as the Android version, built as an installable web app because native iOS apps
can only be built and signed on a Mac with Xcode.

Once installed to the Home Screen it runs full-screen and fully offline (a service worker caches every
file). Tasks are stored only in this phone's browser storage; nothing is ever sent to a server.

## Install on iPhone

1. The files in this folder must be served over HTTPS once, e.g. with GitHub Pages.
2. Open the URL in **Safari** → Share button → **Add to Home Screen** → Add.
3. Launch it from the Home Screen icon. From then on it works in airplane mode.

## Features

Categories and sub-categories (chips; press and hold to edit), priority flags (tap to cycle, hold to pick),
due dates with a light-red "+ Add due date", completed tasks hidden after 12 hours (⋯ → Show all completed),
PIN + Face ID lock, optional activity log, backup to a .json file and restore, share as text.
Dark mode follows the phone.

Face ID uses a passkey created on the device purely as a local check. Because iOS stores passkeys in
iCloud Keychain, the passkey itself (not your tasks) may sync to your other Apple devices. The PIN always works too.
