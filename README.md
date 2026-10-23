# Expenses (Android)

Personal expense tracker. Native Kotlin, no third-party libraries, ~47 KB APK. Dark only.
Data is stored on the phone (`files/expenses.json`, app-private). No server, no account.

## Features
- Entries sorted by expense date (newest first), grouped by month → day
- Tap an entry to edit, long-press to delete (delete is also in the edit sheet)
- Reports tab: spent / received / net per month + category bars
- ⋮ menu: Export JSON backup, Export CSV, Import JSON/CSV, Purge data (by months or date range)
- Widgets:
  - **Quick add** (2×2): today's spend, month total, `+ Spent` / `+ Got` buttons that open a
    popup over the home screen — the app itself never opens
  - **Spending chart** (4×2, resizable): bar graph of total spend for the last 6 months

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
- `EntryDialog.kt` — add/edit bottom sheet (shared by app and widget popup)
- `Widgets.kt` — Quick add + Spending chart widgets, widget popup activity
- `Ui.kt` — colors, formatting, tiny view helpers
