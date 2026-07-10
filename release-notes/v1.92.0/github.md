# Thor v1.92.0 Release Notes

**Extensions, Shortcuts, Suspend Mode, and Security** — introducing the trust-anchored Extensions framework, home-screen launcher integration for frozen apps, dynamic Freeze/Suspend modes, reactive privileges, app sorting by size, and major under-the-hood installer & performance fixes.

---

## What's Changed

### 🧩 Secure Extensions & In-App Store
Thor now supports verified, out-of-process extensions gated behind active privileges and explicit consent:
* **Extensions Store**: Browse official add-ons, perform SHA-256 integrity checks, verify pinned keys, and perform in-place upgrades.
* **Dynamic Versioning & Management**: Extension cards dynamically resolve version names from the `PackageManager` and feature an outlined **Uninstall** action.
* **Security Hardening**: Replaced broadcast-based triggers with a cold-startable `ExtensionOpsProvider`. Same-process calls are secured using Binder UID verification checks (`Binder.getCallingUid() == Process.myUid()`) to block isolated processes from spoofing identities.
* **Initial Extensions**:
  * *Strombringer* — Auto-unfreezes suspended apps when tapped (requires LSPosed).
  * *Thor Cluster Automator* — Group and schedule freeze/unfreeze operations.

### 🥶 Freezer Mode: Suspend vs. Freeze
* **Choose Your Freeze Method**: A new **Settings → Freezer → Freeze Mode** segmented selector lets you choose how apps are deactivated:
  * **Freeze (Disable)**: Completely disables the application (standard Android package disabling).
  * **Suspend (Android Suspend)**: Places the application in a suspended state (using Android's official suspend APIs).
* **State-Aware Actions**: Bulk freeze/unfreeze, single app toggle, launcher shortcuts, and auto-freeze actions all honor the selected mode, handling mixed disabled/suspended states gracefully across all paths.

### 🧊 Freezer Launcher Integration & Shortcuts
* **Grayscale State-Following Icons**: Pin frozen apps to your launcher. Icons display in grayscale while frozen, automatically turning full-color when enabled and launched.
* **Freeze All / Unfreeze All**: Create launcher shortcuts or access them directly from a long-press on Thor's app icon.
* **Add-to-Freezer Prompt**: Consistent confirmation prompt when freezing apps from the App Info dialog or App Details page.

### ⚡ Reactive Privileges & Smoothness
* **Live Status Updates**: Root, Shizuku, and Dhizuku availability updates instantly on Home, App List, and Freezer screens.
* **Main-Safe IPC**: Availability checks run cleanly on the IO dispatcher, removing any startup "flashes" or rendering lags.
* **UI Redesign & Performance**: Replaced legacy navigation and motion components with **Asgard UI library**. Swapped collectors to eliminate pull-to-refresh memory leaks. App icons are now decoded at their exact display sizes.

### 📏 Sort by Size & Folder Export
* **Sort by Disk Footprint**: Sort your application lists by total install size (requires Usage Access permission).
* **Scoped Directory Export**: Redesigned export sheets write APKs and split bundles directly to user-selected folders via the system picker.

### 📦 Installer Overhaul
* **XAPK / APKP Support**: Fixed package parsing issues for APKPure `.xapk` and APKMirror `.apkm` by implementing ZIP central directory reads.
* **Downgrade Logic Fixes**: Normal apps bundling nested helper APKs are no longer falsely flagged as system downgrades.
* **Root Session Installer**: Surfaces real `pm` failure reasons instead of a generic shell exit 255.

---

## 🛠 Build & Dependencies
* **Version**: Bumped to **1.92.0 (1920)** in `gradle.properties`.
* **AGP & KSP**: Upgraded Android Gradle Plugin to `9.4.0-alpha04` and KSP to `2.3.10`.
* **API Namespace**: Consuming Compose-free `thor-extension-api 3.0.0` from the new `com.trinadhthatakula` namespace.
