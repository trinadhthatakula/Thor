# Thor v1.95.0 Release Notes

The major cumulative stable release uniting everything shipped across the **v1.94.x** development cycle — **45+ pull requests**.

This release brings complete, offline, encrypted **App Data Backup & Restore (`.thorbak`)**, the dedicated **Backup & Restore Hub**, full **`.xapk` / OBB** game and split-package installation and export, **App Info Action Customization**, explicit **Freezer action semantics**, and comprehensive **8-language localization**.

---

## ✨ Highlights

* 📦 **Backup & Restore Hub** — dedicated home bento hub to browse, search, manage, share, and restore backups and installer bundles across your device storage.
* 💾 **Encrypted App Data Backup (.thorbak)** — complete offline backups of app data directories and APKs protected with AES-256-GCM authenticated encryption and key derivation.
* ⚙️ **App Info Actions Customization** — drag-and-drop reorder and toggle visibility for all per-app quick actions with live interactive preview.
* 🎮 **Full .xapk & OBB Support** — export and install games and large multi-part apps seamlessly, with automated OBB directory staging.
* ❄️ **Explicit Freezer Semantics** — differentiated action labels between tabs (*"Unfreeze & Remove"* vs *"Remove from Watchlist"*) and automatic dead package pruning.
* 🌐 **8 Supported Languages** — full translation coverage across English, Arabic, Spanish, French, Polish, Portuguese (European & Brazilian), and Simplified Chinese.
* ⚙️ **Categorized Settings** — organized into 6 structured sections with responsive dual-pane layout on foldables and tablets.
* 🛡️ **Guarded DataStore Writes** — disk failure protection and atomicity across all application preference writes.

---

## What's Changed

### 📦 Backup & Restore Hub (#412, #413)

The new **Backup & Restore Hub** transforms backup management from a hidden setting into a central, top-level experience accessible directly from the Home Bento:
* **Automated Storage Scanning**: Automatically scans `Downloads/Thor`, system `MediaStore`, and custom SAF directories for `.thorbak`, `.xapk`, `.apks`, and `.apk` archives without requiring root privileges.
* **Archive Icon Extraction**: `ArchiveIconLoader` extracts, decodes, and renders app icons directly from `.thorbak` and `.apk` archives with disk caching and bounded eviction.
* **Filtering & Management**: Search, filter by category (*All*, *Data Backups*, *App Bundles*), view detailed archive metadata, share files natively, or delete with confirmation.
* **Instant Backup Triggering**: Launch new backups directly from the hub using an integrated modal App Picker.
* **Unified Intent Routing**: Opening `.thorbak` files from "Install from file" or external file managers routes immediately to the restore sheet.

### 💾 Full App Data Backup & Restore Phase 2 (#379, #381, #385, #389)

Delivers end-to-end, offline, encrypted application data preservation:
* **AES-256-GCM Authenticated Encryption**: App data directories (`/data/data/<package>`), APKs, and external storage files are archived into encrypted `.thorbak` bundles.
* **Argon2id / PBKDF2 Key Derivation**: Per-archive unique salt and cryptographic derivation protect your data. Optional in-memory passphrase caching with Biometric / KeyStore isolation.
* **WorkManager Foreground Sync**: Background backup and export execution powered by Android WorkManager with `FOREGROUND_SERVICE_DATA_SYNC` compliance, persistent progress notifications, and cancellation support.
* **Safety Pre-flight Validation**: Restoring performs package signature verification, target SDK compatibility checks, and disk capacity validation before touching app data.

### ⚙️ Customizable App Info Actions (#410)

Tailor the action bar in App Info sheets and details screens to your exact workflow:
* **Drag-and-Drop Reordering**: Rearrange quick action tiles (Freeze, Suspend, Force Stop, Permissions, Clear Cache, Backup, Export, Share, etc.) in any order.
* **Visibility Toggles**: Hide actions you don't use.
* **Interactive Live Preview**: Preview your custom action layout in real-time with an instant reset-to-default button.

### 🎮 Full `.xapk` & OBB Split Package Handling (#376, #378)

* **Game & Multi-APK Support**: Install and export `.xapk` split bundles with automated detection and placement of expansion assets (`/Android/obb/<package>`).
* **Background Foreground Export**: Long exports run as background foreground services with notification progress channels.

### ❄️ Freezer Action Differentiation & Watchlist Pruning (#370, #415)

* **Differentiated Snowflake Labels**: Explicitly names the action based on context — `"Unfreeze & Remove"` on the Freezer tab (restoring the app upon removal) vs `"Remove from Watchlist"` on the Apps tab (adjusting watchlist membership only).
* **Automatic Watchlist Pruning**: Uninstalled packages are automatically cleaned up from the watchlist based on cache scan verdicts.
* **Atomic Profile Saves**: Profile editor writes are secured with database transactions and error rollbacks.
* **Per-Group Kill & Suspend**: Execute mass kill or suspend operations directly on freeze profile member apps.

### 🌐 Comprehensive 8-Language Localization (#395, #397, #398, #400)

* Added **Polish** (`pl`) and **Portuguese** (European `pt` + Brazilian `pt-rBR` regional override).
* Backfilled and proofread **Arabic**, **Spanish**, **French**, and **Simplified Chinese** with 0 missing translations across all build flavours.

### ⚙️ Settings Reorganization & Tablet Multi-pane (#383)

* Reorganized settings into 6 clear categories: *General*, *Appearance*, *Freezer*, *Work Mode*, *Data & Backup*, and *Advanced*.
* Adaptive dual-pane navigation rail layout for large screens, foldables, and tablets.

### 📊 CSV App List Export with Formula Injection Guard (#371)

* Export your filtered, searched, or full app list to CSV directly from the filter sheet.
* Built-in protection against spreadsheet formula injection (RFC 4180 escaping and leading character neutering).

---

## 🛠️ Reliability & Verification

* Passed extensive automated test suites and static analysis gates: `./gradlew test lintFossDebug lintStoreRelease` (0 errors, 0 missing translations).
* Validated website and documentation integrity: all 304 web test suites passing with dynamic follow-up matrix validation.
