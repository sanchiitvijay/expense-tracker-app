# Expenses (Android)

Personal expense tracker. Native Kotlin, no third-party libraries, ~61 KB APK. Dark only.
Data is stored on the phone (`files/expenses.json`, app-private). No server, no account.

## Features
- Entries sorted by expense date (newest first), grouped by month → day
- Tap an entry to edit, long-press to delete (delete is also in the edit sheet)
- Reports tab: 6-month spent vs received bars with net, spending pace (this vs last month),
  weekday pattern, and per month a category donut, daily spending bars and highlights
- People tab (separate ledger in `files/ledger.json`, never mixed with expenses): record money you
  gave or got per person, see who owes you / whom you owe, per-person history, Settle up, and
  Split bill (equally, by %, or by exact amounts per person — it checks they add up to 100% / the bill; if you paid, each person owes you their share; if someone else paid,
  your share is recorded as owed to them). Names can be suggested from your contacts (read-only,
  asked on first use) — nothing is sent anywhere
- Wishlist tab (own file `files/wishlist.json`): note things you need to order, optionally tagged
  ★ Important (shown first). Tap ✓ Ordered once bought and it's removed
- Reports also show a People section: you'll get / you owe / net, 6-month gave vs got, top
  balances both ways, this month's activity, oldest open balance and number of splits
- ⋮ menu: Export JSON backup (expenses + People + Wishlist), Export CSV (expenses only), Import JSON/CSV, Purge data (by months or date range)
- Widgets:
  - **Quick add** (3×3, resizable): scrollable list of every entry from the last 7 days with the
    7-day net, plus `+ Spent` / `+ Got` buttons. Buttons add, tapping an entry edits it — both in a
    popup over the home screen; the app itself never opens
  - **People** (3×3, resizable): who owes you and whom you owe, with `+ Gave` / `+ Got` / `Split`
    buttons; tapping a person adds an entry for them — all in a popup over the home screen
  - **Wishlist** (3×3, resizable): items to order, important first, with `+ Add item`; tap an item
    to mark it ordered — both in a popup over the home screen
  - **Spending chart** (4×2, resizable): bar graph of net spend for the last 6 months (green when you received more than you spent)

## Data format (unchanged from the web app)
JSON: `{ "format": "expenses-tracker", "version": 1, "exportedAt", "count", "expenses": [ … ] }`
CSV columns: `id,date,type,category,amount,note,createdAt`
Each entry: `id`, `amount`, `category`, `note`, `date` (YYYY-MM-DD), `type` (`spent`|`received`), `createdAt` (ISO).
Imports skip entries whose `id` already exists, so re-importing a backup never duplicates.
Backups from the old web app import directly.

## Build & install
```
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```
The release build is signed with your local debug key (fine for personal sideloading).

## Code
`app/src/main/java/com/sanchit/expenses/`
- `Store.kt` — data model, local file storage, import/export (JSON + CSV), purge
- `MainActivity.kt` — main screen, reports, menu, import/export/purge flows
- `Ledger.kt` — People ledger model and its own local storage
- `PeopleDialog.kt` — add/edit lend-borrow entry and Split bill sheets
- `Wishlist.kt` — wishlist model, its own local storage, add sheet
- `Contacts.kt` — read-only contact name suggestions
- `EntryDialog.kt` — add/edit bottom sheet (shared by app and widget popup)
- `Widgets.kt` — Quick add + Spending chart widgets, widget popup activity
- `Charts.kt` — canvas bar / donut / line charts
- `Ui.kt` — colors, formatting, tiny view helpers
