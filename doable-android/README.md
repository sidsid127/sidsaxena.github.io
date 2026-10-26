# Doable (Android)

An offline organiser: to-do list, notes, groceries, passwords, diary, trips, habits, important dates,
checklists and a wallet. All data stays on the phone:
no account, no internet permission, and Android cloud backup is switched off.

Open the ☰ side menu to switch between modules (☰ → *Show / hide modules* to trim it), search everything,
or make a backup. The ⋮ menu has each module's options plus the fingerprint app lock and the theme.

- **Today**: tasks due, today's habits, birthdays & dates coming up, the diary prompt, the current trip and
  the shopping list on one page, with quick-add buttons.
- **To-do**: categories, priorities, due dates, **reminders** (with Done / Snooze on the notification),
  **repeating tasks** (daily, weekdays, weekly, every 2 weeks, monthly, yearly), **subtasks**, notes, smart
  views (Today, Upcoming, 📅 calendar) and **quick add in plain language** — "Call mum tomorrow 6pm !high
  every week" fills in date, reminder, priority and repeat.
- **Notes**: folders, colours, pinning, checklists (☐), **bold**/_italic_/headings, `[[links]]` between
  notes, photos, **document scans**, PDFs and **voice notes**; lock a single note with your fingerprint;
  *Recently deleted* keeps notes for 30 days.
- **Groceries**: several lists (groceries, drugstore, hardware store, party…), quantities and units,
  items sorted by aisle, shops, **prices** (asked when you tick an item, to compare shops) and today's
  total. *Share list* sends it to WhatsApp; share a family member's WhatsApp list back to Doable (or
  ⋮ → *Add items from a shared list*) and only the new items are added.
- **Passwords**: logins, bank cards, secure notes, Wi-Fi; **autofill** into apps and browsers
  (⋮ → *Use Doable for autofill*); import a CSV from Chrome / Edge / Firefox / Bitwarden.
- **My Diary**: mood, text, highlights, photos, place (GPS, no internet needed) and weather; optional
  daily reminder (⋮ → *Daily reminder*); export as a PDF book.
- **My Trips**: business trips with expenses in **any currency** (converted to your home currency with
  rates you enter), receipts, **per diem** and **mileage**, a monthly report as **CSV** (opens in Excel —
  e-mail it to the expense tool), PDF or text; private trips with plan, bookings, journal, **budget vs
  spent**; a packing list from a template for every trip.
- **Habits**: build (drink water 8×, read) or quit (no sweets) with streaks, a 7-day history you can
  correct, and reminders at set times or every few hours.
- **Important dates**: birthdays (with age), anniversaries, contract renewals, expiries — with a
  reminder days before. Wallet expiry dates show up here too.
- **Checklists**: reusable lists and templates (business trip, holiday, before leaving home…).
- **Wallet**: loyalty cards (barcode shown full-screen at the till), ID, passport, driving licence,
  insurance cards — front/back photos or scans, behind your fingerprint.
- **Activity log**: timestamped notes of what you did.

**Home-screen widgets**: long-press the home screen → Widgets → Doable, then pick a view (Today, To-do,
Groceries, Habits, Upcoming dates or Quick-add buttons); tap a row to tick it off. Long-press the app icon
for shortcuts. **Share → Doable** from any app turns text into a note, task, grocery items, diary line or
checklist, and photos / PDFs into a note, trip receipt, wallet document or diary photos.

### Backup (important)

☰ → *Backup & restore* → *Create backup* writes **one encrypted file** (AES-256, your own password)
with everything: data, photos, receipts, voice notes, settings, passwords and locked notes. Android asks
where to save it — choose Google Drive, Downloads (then copy it to your PC), a USB stick, or e-mail it to
yourself. On a new phone: install Doable, then *Backup & restore* → *Restore* and pick the file.
Doable itself never connects to the internet.

### What it can't do (it's offline)

- Weather is picked from a list, not fetched; exchange rates are typed in.
- Scans are cropped and straightened, but text isn't read automatically (no OCR), so card numbers are typed.
- Family sharing works by sending the list (e.g. to a WhatsApp group) and sharing replies back — there's no sync server.

Photos, screenshots and PDFs are copied into the app's private storage (images are shrunk to 2048 px).

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
- `src/com/sidsaxena/daybook/Db.java`, `Store.java` — SQLite storage
- `MainActivity.java` — side menu, app lock, intents (notifications, widgets, shortcuts, share), file pickers
- one `*Screen.java` per module; `Page.java` is the base for the code-built pages
- `Reminders.java`, `Notify.java`, `BootReceiver.java` — the single-alarm reminder engine
- `Widgets.java` and friends — home-screen widgets; `AutofillSvc.java` — password autofill
- `Backup.java` + `BackupCrypto.java` — encrypted backup file
- Pure-Java logic (unit-tested on the JVM): `QuickAdd`, `Recurrence`, `HabitLogic`, `TripMath`, `GroceryText`,
  `Barcode`, `Csv`, `BackupCrypto`

The app was first called Daybook; its internal package id (`com.sidsaxena.daybook`) is kept so that
updates install over existing copies without losing data.
