# Expenses (Android)

Personal expense tracker. Native Kotlin, no third-party libraries, ~61 KB APK. Dark only.
Data is stored on the phone (`files/expenses.json`, app-private). No server, no account.

## Features
- Multiple lists: Expenses, Checklist and Notes can each hold as many as you like (e.g. Personal /
  Trip expenses, Groceries / Packing checklists, several notes). Switch with the chips at the top of the
  tab, `+ New` to create one, tap the open one (or long-press any) to rename or delete it. Reports follow
  the open expense list. The first expense list keeps `expenses.json`, others get `expenses_<id>.json`;
  checklists are `checklist_<id>.json`, notes `note.json` / `note_<id>.json`; names live in `lists_<kind>.json`
- Entries sorted by expense date (newest first), grouped by month → day
- Tap an entry to edit, long-press to delete (delete is also in the edit sheet)
- Reports tab: 6-month spent vs received bars with net, spending pace (this vs last month),
  weekday pattern, and per month a category donut, daily spending bars and highlights
- People tab (separate ledger in `files/ledger.json`, never mixed with expenses): record money you
  gave or got per person, see who owes you / whom you owe, per-person history, Settle up, and
  Split bill (equally, by %, or by exact amounts per person — it checks they add up to 100% / the bill; if you paid, each person owes you their share; if someone else paid,
  your share is recorded as owed to them). Names can be suggested from your contacts (read-only,
  asked on first use) — nothing is sent anywhere
- Checklist tab (replaces Wishlist and Ideas — their lists became checklists on update): items to tick
  off, optionally ★ Important (shown first). Tap an item to tick / untick it; ticked items stay at the
  bottom, struck through, until `Clear done`. Long-press an item to edit, (un)mark important or delete
- Notes tab: each note is one block of free text with **bold** — select text and tap B, or tap B and type.
  Saved as you type
- Settings (⋮ → Settings & purge): show/hide any tab (hidden tabs keep all their data; a hidden
  People tab also hides the People section in Reports), purge the open expense list (by months,
  date range or all), clear People / the open checklist / the open note's text, or erase everything (every list is emptied, the lists are kept)
- Reports also show a People section: you'll get / you owe / net, 6-month gave vs got, top
  balances both ways, this month's activity, oldest open balance and number of splits
- ⋮ menu: Export JSON backup (every list + People), Export CSV (the open expense list), Import JSON/CSV, Settings & purge
- Widgets: Quick add, Spending chart, Checklist and Note each show one list / note — when you place one you
  pick which (skipped if there's only one); on Android 12+ long-press → reconfigure to change it. Add the
  same widget several times for several lists
  - **Quick add** (3×3, resizable): scrollable list of every entry from the last 7 days with the
    7-day net, plus `+ Spent` / `+ Got` buttons. Buttons add, tapping an entry edits it — both in a
    popup over the home screen; the app itself never opens
  - **People** (3×3, resizable): who owes you and whom you owe, with `+ Gave` / `+ Got` / `Split`
    buttons; tapping a person adds an entry for them — all in a popup over the home screen
  - **Checklist** (3×3, resizable): one checklist; tap an item to tick it off right on the home
    screen, `+ Add` adds in a popup
  - **Note** (3×3, resizable): one note's text with its bold, scrollable; tap it (or Edit) to edit in
    a popup over the home screen
  - **Spending chart** (4×2, resizable): bar graph of net spend for the last 6 months (green when you received more than you spent)

## Data format (unchanged from the web app)
JSON: `{ "format": "expenses-tracker", "version": 1, "exportedAt", "count", "expenses": [ … ] }`
CSV columns: `id,date,type,category,amount,note,createdAt`
Each entry: `id`, `amount`, `category`, `note`, `date` (YYYY-MM-DD), `type` (`spent`|`received`), `createdAt` (ISO).
The full JSON backup also has `people`, `checklists`, `textNotes` (`{list, text, bold: [[start, end], …]}`) and
`lists` (`{ expenses|checklists|textNotes: [{id, name}] }`); each expense / checklist item carries `list` (its list's id).
Older backups' `wishlist` / `ideas` items import as checklists. On import, records go back into their list
(created if missing); records without one (older backups, CSV) go into the list open now.
Imports skip entries whose `id` already exists in that list, so re-importing a backup never duplicates.
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
- `Lists.kt` — named lists (`Books`): registry per section, the open list, and which list each widget shows
- `Checklists.kt` — checklist model, per-list storage, migration from Wishlist / Ideas, add sheet
- `Notes.kt` — note storage, bold ↔ ranges (`RichText`), the note editor and its widget popup
- `Settings.kt` — tab list and which tabs are shown
- `Contacts.kt` — read-only contact name suggestions
- `EntryDialog.kt` — add/edit bottom sheet (shared by app and widget popup)
- `Widgets.kt` — Quick add, Spending chart, People, Checklist and Note widgets, the widget list picker, widget popup activities
- `Charts.kt` — canvas bar / donut / line charts
- `Ui.kt` — colors, formatting, tiny view helpers
