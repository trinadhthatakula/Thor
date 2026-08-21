# Thor v1.95.1 Release Notes

The cumulative stable release, uniting everything shipped across the **v1.94.x** development cycle
and the **v1.95.0** dev build that followed it. If you last updated from a stable release, this is
the whole of that work in one step.

It brings complete, offline, encrypted **App Data Backup & Restore (`.thorbak`)**, the dedicated
**Backup & Restore Hub**, full **`.xapk` / OBB** game and split-package installation and export,
**App Info Action Customization**, explicit **Freezer action semantics**, comprehensive
**8-language localization**, and the package-visibility and privilege-detection fixes that followed
them on real devices.

---

## ✨ Highlights

* 📦 **Backup & Restore Hub** — dedicated home bento hub to browse, search, manage, share, and restore backups and installer bundles found in your device storage.
* 💾 **Encrypted App Data Backup (.thorbak)** — offline backups protected with AES-256-GCM authenticated encryption and key derivation. Full app data directories on root; APKs and shared storage on Shizuku.
* ⚙️ **App Info Actions Customization** — drag-and-drop reorder and toggle visibility for all per-app quick actions, with the live preview pinned above the list while you rearrange.
* 👆 **App Icon Gestures** — tap an app's icon in App Info to open it, long-press for its system settings page.
* 🎮 **Full .xapk & OBB Support** — export and install games and large multi-part apps seamlessly, with automated OBB directory staging.
* ❄️ **Explicit Freezer Semantics** — differentiated action labels between tabs (*"Unfreeze & Remove"* vs *"Remove from Watchlist"*) and automatic dead package pruning.
* 🌐 **8 Supported Languages** — full translation coverage across English, Arabic, Spanish, French, Polish, Portuguese (European & Brazilian), and Simplified Chinese.
* 🔍 **Package Visibility Recovery** — app lists that collapsed to Thor alone on Chinese OEM ROMs and fresh installs now populate correctly.
* 🔑 **Dynamic Privilege Detection** — Shizuku granted while Thor is running is picked up on its own; root and Dhizuku are picked up with **Refresh**. Neither needs the process killed.
* ⚙️ **Categorized Settings** — organized into 6 structured sections with responsive dual-pane layout on foldables and tablets.

---

## What's Changed

### 📦 Backup & Restore Hub (#412, #413)

The **Backup & Restore Hub** turns backup management from a hidden setting into a central,
top-level experience reachable from the Home Bento:

* **Automated Storage Scanning**: Scans `Downloads/Thor`, the system `MediaStore` Downloads and Files tables, and your configured SAF export directory for `.thorbak`, `.xapk`, `.apks`, and `.apk` archives — without requiring root privileges.
* **Archive Icon Extraction**: `ArchiveIconLoader` extracts, decodes, and renders app icons directly from archives with disk caching and bounded eviction.
* **Filtering & Management**: Search, filter by category (*All*, *Data Backups*, *App Bundles*), view detailed archive metadata, share files natively, or delete with confirmation.
* **Instant Backup Triggering**: Launch new backups directly from the hub using an integrated modal App Picker.
* **Unified Intent Routing**: Opening `.thorbak` files from "Install from file" or external file managers routes immediately to the restore sheet.

### 💾 Full App Data Backup & Restore Phase 2 (#379, #381, #385, #389)

End-to-end, offline, encrypted application data preservation:

* **AES-256-GCM Authenticated Encryption**: App data directories (`/data/data/<package>`), APKs, and external storage files are archived into encrypted `.thorbak` bundles.
* **Privilege-Aware Scope**: On root, backups cover private app data (`CE` and `DE`) alongside the APK and shared storage. On non-root shell privileges such as Shizuku, private data is not readable, so backups cover the APK bundle, `EXTERNAL_DATA` and `EXTERNAL_MEDIA` — and the size estimate reflects exactly what will be archived rather than promising data it cannot reach.
* **PBKDF2WithHmacSHA256 Key Derivation**: Per-archive unique salt and cryptographic derivation protect your data. Passphrase caching stores an encrypted DataStore blob protected by AndroidKeyStore.
* **WorkManager Foreground Sync**: Background backup and export execution powered by Android WorkManager with `FOREGROUND_SERVICE_DATA_SYNC` compliance, persistent progress notifications, and cancellation support. Backup staging verifies usable disk space before archiving begins.
* **Safety Pre-flight Validation**: Restoring performs package signature verification before touching app data.

### ⚙️ Customizable App Info Actions (#410, #418)

Tailor the action bar in App Info sheets and details screens to your exact workflow:

* **Drag-and-Drop Reordering**: Rearrange quick action tiles (Freeze, Suspend, Force Stop, Permissions, Clear Cache, Backup, Export, Share, etc.) in any order.
* **Visibility Toggles**: Hide actions you don't use.
* **Pinned Live Preview**: The preview, drag hint, and reset button stay fixed above the scrolling list, so you can still see where a tile lands when you drag one from the bottom of the list.
* **Reorder Hardening**: Fixes a drop that resolved against pre-swap layout information, two fingers on two handles driving one state holder, a drag endable by a key that wasn't dragging, and a preferences resync arriving mid-drag being dropped instead of deferred.

### 👆 App Icon Gestures (#421)

* **Tap to open, long-press for settings**: Two gestures on the app icon at the top of both app-info surfaces. Both are shortcuts to actions the row below already offers, so nothing is gesture-only — and because the header shares the row's exact lambda instances, tapping the icon of a frozen or suspended app unfreezes it first, just as the Open action does.

### 🎮 Full `.xapk` & OBB Split Package Handling (#376, #378)

* **Game & Multi-APK Support**: Install and export `.xapk` split bundles with automated detection and placement of expansion assets (`/Android/obb/<package>`).
* **Background Export via a Foreground Service**: Long exports run in the background as WorkManager jobs promoted to a `dataSync` foreground service, with notification progress channels.

### ❄️ Freezer Action Differentiation & Watchlist Pruning (#370, #415)

* **Differentiated Snowflake Labels**: Names the action by context — `"Unfreeze & Remove"` on the Freezer tab (restoring the app upon removal) vs `"Remove from Watchlist"` on the Apps tab (adjusting watchlist membership only).
* **Automatic Watchlist Pruning**: Uninstalled packages are cleaned up from the watchlist based on cache scan verdicts.
* **Atomic Profile Saves**: Profile editor writes are secured with database transactions and error rollbacks.
* **Per-Group Kill & Suspend**: Execute mass kill or suspend operations directly on freeze profile member apps.

### 🔍 Package Visibility & Privilege Acquisition (#417, #419)

The app list coming back with **Thor as the only installed app** on Chinese OEM ROMs is fixed, along
with the regression that reintroduced it:

* **Fresh installs and Chinese OEM ROMs** (ColorOS, OxygenOS, HyperOS, MIUI) recover visibility through fallback flag queries and automatic AppOps grants, with `SelfPermissionGranter` synchronized against `AppRepository` rescans.
* **The app-op grant is no longer gated on `pm grant`'s exit code.** `com.android.permission.GET_INSTALLED_APPS` is vendor-defined, not AOSP, so `pm grant` frequently exits non-zero on ROMs where the app-op is what actually governs access — making the tidy-up "skip follow-up work if the grant failed" precisely backwards for this one permission.
* **Privilege Manager Registry**: `PrivilegeManagerApp` resolves known root and privilege managers by package name — KernelSU, KernelSU-Next, APatch, Magisk, Shizuku, Dhizuku and others.
* **In-app grant requests** for Shizuku and Dhizuku, and a **Refresh** in the Privilege Check dialog that re-probes all three sources. Shizuku recovers on its own via binder and permission listeners; root and Dhizuku publish no such callback, so before this a grant made while Thor was running stayed invisible until the process was killed.
* **Removed `FLAG_MOUNT_MASTER`**, which caused root acquisition to fail outright under KernelSU and APatch.
* **Touch responsiveness** fixes in the App List sort/filter bottom sheet.

### 🌐 Comprehensive 8-Language Localization (#395, #397, #398, #400)

* Added **Polish** (`pl`) and **Portuguese** (European `pt` + Brazilian `pt-rBR` regional override).
* Backfilled and proofread **Arabic**, **Spanish**, **French**, and **Simplified Chinese** with 0 missing translations across all build flavours.
* The language picker sheet now scrolls, so the last locales in the list are reachable on short screens.

### ⚙️ Settings Reorganization & Tablet Multi-pane (#383)

* Reorganized settings into 6 clear categories: *General*, *Appearance*, *Freezer*, *Work Mode*, *Data & Backup*, and *Advanced*.
* Adaptive dual-pane navigation rail layout for large screens, foldables, and tablets.

### 📊 CSV App List Export with Formula Injection Guard (#371)

* Export your filtered, searched, or full app list to CSV directly from the filter sheet.
* Built-in protection against spreadsheet formula injection (RFC 4180 escaping and leading character neutering).

### 🐛 Post-Release Review Fixes (#420)

A review pass over everything merged since `v1.94.4-dev-26`:

* **Storage leaks on cancellation.** `ArchiveIconLoader`, `UriArchiveSourceFactory`, and `AppAnalyzerImpl` cleaned up staged copies in a `finally` that called a suspend function — which never runs on cancellation. Coil cancels an icon fetch whenever a row scrolls out, so every scroll of the Backup Hub left a full archive copy behind.
* **A stranded multi-gigabyte staging file.** When the internal volume is short, backup staging downgrades to `externalCacheDir`, and the orphan sweeper had no rule for that root — so a crash mid-backup left a tar file that nothing would ever clean up.
* **Backup Hub icon loading.** `.thorbak` rows staged a copy of the entire archive to draw a 44 dp icon, looking for an entry no `.thorbak` has ever contained, and repeated it on every scroll. Now `.thorbak` is never staged, every other stage is capped at 256 MB, decoding is sampled rather than allocating the source's full pixel buffer, and a miss is remembered.
* **`revokePermission` left the app-op open** in all three gateways, so a revoked permission kept working on the ROMs where the op is what is consulted.
* **Failed archive deletion is now reported** instead of silently closing the dialog and leaving the row in place.
* **Brazilian Portuguese** received all 22 Backup Hub strings; they had been resolving through European Portuguese.
* **The permissions-screen flicker**: opening Permissions from the app-info sheet left the sheet up behind the pushed route, so back popped the route and the re-emitting sheet fought it. The sheet now dismisses itself when it hands off.

---

## 🛠️ Reliability & Verification

* `./gradlew :app:testFossDebugUnitTest --rerun-tasks` — 122 suites, **1634 tests, 0 failures**.
* `./gradlew lintFossDebug lintStoreRelease` — clean, no `MissingTranslation` across any flavour.
* Website and documentation integrity validated: 304 web test suites passing, with type, link, claim, markup and sitemap checks.
