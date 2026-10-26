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

Same modules as the Android app, switched from the ☰ side menu: **To-do** (categories, priority flags,
due dates, grouped by priority and category, completed tasks hidden after 12 hours), **Notes** (iPhone
Notes style), **Groceries** (grouped by shop, "Buy again" history), **Passwords** and the **Activity log**.
The ⋯ menu has each module's options plus theme (same as phone / light / dark), PIN + Face ID app lock,
and backup to / restore from a .json file.

**Passwords** are encrypted with AES-256-GCM. The key is unlocked by your master password (PBKDF2,
600,000 rounds) or, on iOS 18+, by Face ID through the passkey PRF extension, which releases a secret only
after Face ID succeeds. Without one of the two, the stored data is unreadable. Backups contain the
passwords still encrypted with your master password.

The app lock's Face ID uses a passkey created on the device purely as a local check. Because iOS stores passkeys in
iCloud Keychain, the passkey itself (not your tasks) may sync to your other Apple devices. The PIN always works too.
