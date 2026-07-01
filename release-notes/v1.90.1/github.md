# Thor v1.90.1 Release Notes

A focused **stability & smoothness** release on top of v1.90.0 — hardened APK installation, a fix for the Settings crash on large fonts, and a broad pass to eliminate memory leaks and UI jank across the app.

## What's Changed

### 📦 Installer
*   **No more false "downgrade" blocks**: normal apps that bundle helper `.apk` assets (e.g. Muntashirakon **App Manager**, **MT File Manager**) were being misidentified as a system downgrade and blocked. A file is now recognized as a single APK by its top-level manifest instead of by any nested `.apk` entry, so it installs correctly. (#207)
*   **`.xapk` installs fixed**: bundles that failed with *"Error: failed to parse package"* now install — tolerant manifest parsing (numeric `version_code`, missing fields) and correct base-APK selection that skips config splits. (#159)

### 🐛 Crash Fix
*   **Settings no longer crashes** with system Font Size = Maximum and Display Size = Larger. The connected button group was rebuilt (via **Asgard 1.0.1**) so it can no longer produce the internal `ButtonGroup` measurement crash at any font/display scale. (#197)

### 🚀 Performance & Smoothness
*   App-list **pull-to-refresh no longer stacks background collectors** — previously each refresh leaked broadcast receivers and re-ran a full package enumeration.
*   Privilege availability checks (Shizuku/Dhizuku binder IPC) are now **`suspend` and main-safe**, moved off the main thread across Settings, App Details, App List, and the installer — noticeably less jank.
*   **Less recomposition work**: list filtering/sorting and date formatting are memoized in App Details, Freezer, and Permission Manager.
*   **App icons decode at their display size** — lower memory use and smoother scrolling in the app grid.
*   Lifecycle-aware state collection and one-shot side effects throughout (no more background activity launches).

### 🔒 Stability & Resource Fixes
*   Privileged **shell/process execution** (Shizuku/Dhizuku) now uses **bounded waits with guaranteed teardown** and daemon reader threads — a hung command can no longer pin a background thread forever or leak file descriptors.
*   Fixed a **cross-thread data race** in the debloat (UAD) cache.
*   The **biometric prompt** no longer orphans a cancellation signal when re-triggered (auto-prompt + manual tap).
*   Billing no longer initializes at app shutdown; added a proper teardown hook.
*   Removed an unused cache-scanner path that leaked a `su` subprocess and its file descriptors.

### ✅ Issues Resolved
*   **#197** — Settings crash on large font/display size.
*   **#207** — installation wrongly blocked as a system downgrade.
*   **#159** — `.xapk` install failing to parse.
*   Also closed **#77** (clear cache / force-stop — already fixed) and **#161** (tap-to-install — already shipped).
