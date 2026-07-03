# Thor v1.90.3 Release Notes

Features **and** reliability: sort your apps by install size, export APKs/bundles to any folder, plus installer and privilege fixes across the board.

## What's Changed

### 📏 Sort by Size (#57)
*   New **Size** sort option — order the app list by total install size (app + data + cache).
*   Each app's total size now appears in the **App Info** sheet.
*   Size data needs **Usage Access**: Thor shows a clear prompt, and a new **Permissions** section in Settings surfaces the current Usage Access status.

### 📤 Export to Folder (#164)
*   **Export** an app's APK — or its full split bundle — straight to a folder you choose, via the system picker / MediaStore.
*   Redesigned export sheet with scrollable tabs, and an **Export** action added to the App Info dialog.
*   Thor remembers your chosen export destination for next time.

### ⚡ Reactive Privileges (#224)
*   Root / Shizuku / Dhizuku availability now updates **live** across Home, App List, and Freezer.
*   No more privilege "flash" on cold start — screens wait for the real privilege state and pick up a newly granted privilege immediately.

### 📦 Installer
*   **Bundles with dotted names now open.** File managers can hand Thor a bundle whose filename contains version numbers or a source domain (e.g. `App_1.2.3.xapk`, `Amazon+Shopping_32.12.4.100_APKPure.xapk`). The old intent filter only matched single-dot names; it now matches any name via `pathSuffix` (Android 12+) with a dot-count `pathPattern` fallback for older versions. (#159)
*   **`.apkp` support** — `.apkp` bundles are now recognized both when opening a file and when detecting the bundle type.
*   **Root install reliability** — fixed root installs failing with a bare *exit 255*; the installer now runs via an install session and **surfaces the real `pm` failure reason** instead of a generic error. (#232)

### ✅ Issues Resolved
*   **#57** — sort apps by total install size.
*   **#164** — export an app to a folder.
*   **#159** — bundle files with multi-dot names failing to open.
*   **#232** — root install failing with exit 255.
