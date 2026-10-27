# Setting up Google sign-in for sync (one time, ~10 minutes, free)

Doable has no server of its own. Sync goes through **your own** Google Drive, so Google needs to know about
"your" Doable once. You do this in a free Google Cloud project that only you use.

Doable only asks for two permissions: the hidden **app folder** of your Drive (`drive.appdata` — Doable can't
see any of your other files) and your **email address** (to show which account is connected). Both are
"non-sensitive", so no review by Google is needed.

## 1. Create the project

1. Open <https://console.cloud.google.com/> and sign in with the Gmail account you'll sync with.
2. Top bar → project picker → **New project** → name it `Doable` → **Create**, then select it.
3. Menu → **APIs & Services → Library** → search **Google Drive API** → **Enable**.

## 2. Consent screen (Branding)

1. Menu → **Google Auth Platform** → **Get started**: app name `Doable`, your Gmail as support email → Next →
   Audience **External** → your Gmail as contact → **Create**.
2. **Branding** page:
   * App name `Doable`, user support email: your Gmail
   * App logo: **leave empty** (uploading a logo makes Google require a manual review)
   * Application home page: `https://doable-sid.vercel.app/about.html`
   * Privacy policy: `https://doable-sid.vercel.app/privacy.html`
   * Terms of service: `https://doable-sid.vercel.app/terms.html`
   * Authorized domains → Add domain: `doable-sid.vercel.app`
   * Developer contact: your Gmail → **Save**
3. **Data access → Add or remove scopes**: tick `.../auth/drive.appdata` and `.../auth/userinfo.email` → Update → **Save**.
4. **Audience → Publish app → Confirm** (status "In production"). In "Testing" status Android sign-ins expire every
   7 days. Users see a "Google hasn't verified this app" note until the app is verified — Advanced → Go to Doable.

## 3. Client for the web app (iPhone & Mac)

**Clients → Create client** → type **Web application** → name `Doable web`

* Authorized JavaScript origins: `https://doable-sid.vercel.app`
* Authorized redirect URIs: `https://doable-sid.vercel.app/auth.html`

→ **Create** and copy the **Client ID** (`…apps.googleusercontent.com`).

## 4. Client for Android

**Clients → Create client** → type **Desktop app** → name `Doable Android` → **Create**, copy the
**Client ID** and the **Client secret** (`GOCSPX-…`). (For desktop/installed apps Google treats the "secret"
as not confidential.)

## 5. Put the IDs into Doable

Either send the three values to Claude to build them into the apps, or enter them yourself:

* **Web (iPhone / Mac):** Doable → ☰ → Sync & backup → Google client ID → paste the *web* client ID
  (once per device). Or put it in `doable/config.js` so every device has it.
* **Android:** ☰ → Sync with Google → **Google client…** → paste the *Desktop* client ID and secret.

## 6. Turn sync on

1. On the first device: **Turn on sync** → sign in with Google → **choose a sync passphrase** (8+ characters,
   write it down — without it the synced data can't be decrypted, not even by you).
2. On each other device: Turn on sync → sign in with the same Gmail → **enter the same passphrase**.

Everything then syncs: tasks, notes (also locked ones), diary, trips with receipts, groceries, habits, dates,
checklists, wallet, passwords and the activity log — encrypted end-to-end with your passphrase.

**To revoke access at any time:** <https://myaccount.google.com/permissions> → Doable → Remove access.
