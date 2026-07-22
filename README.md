<p align="center">
  <img src="app/src/main/thor_drawn-playstore.png" alt="Thor Logo" height="192dp">
</p>

<h1 align="center">Thor App Manager</h1>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.valhalla.thor" target="_blank">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="60">
  </a>
  &nbsp;
  <a href="https://apt.izzysoft.de/fdroid/index/apk/com.valhalla.thor" target="_blank">
    <img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" height="60">
  </a>
  &nbsp;
  <a href="https://www.indusappstore.com/apps/productivity/thor/com.valhalla.thor/?page=details&id=com.valhalla.thor" target="_blank">
    <img src=".github/assets/indus-badge.png" alt="Download on Indus Appstore" height="60">
  </a>
</p>

<p align="center">
  <a href="https://t.me/thorAppDev">Telegram Channel</a>
</p>


---

* Kotlin + Material 3 Design
* Jetpack Compose
* Room DB App Caching
* Custom Hidden API Bypass
* PlayStore Download Size (around 3.0 MB)
* Smallest APK size (less than 6 MB)
* FOSS - GPL-3.0
* Fully Offline
* No Ads/Trackers

## Working Features

- High-performance app list loading with Room DB metadata caching
- Fingerprint Lock
- Themes (dark, light, system) + AMOLED + Asgardian static theme
- **Redesigned App Installer** — install packages with root, shizuku, or normal, featuring detailed UI states and associations for split formats (`.apkm`, `.apks`, and `.xapk`)
- **Universal Android Debloater (UAD) Integration** — safety recommendation chips (Recommended, Advanced, Expert, Unsafe) dynamically shown for system packages
- **Safe System App Debloating & Freezing** — uninstalls system apps for the current user (`pm uninstall --user`) and restores them (`pm install-existing`) to support modern Android versions safely
- **Adaptive UI Layouts** — vertical navigation rail for tablets/foldables, optimized viewport layouts, and split landscape detail screens
- **Safety Gating** — blocks freezing of system apps marked as **Unsafe** by UAD to prevent bootloops, and warns on **Expert** packages
- Root Support
- Shizuku Support
- Dhizuku Support
- Fully reproducible, copyleft libre software (GPLv3.0)
- Material 3 with optional dynamic colors (Material You)
- Work Mode selection — manually choose between Root, Shizuku, or Dhizuku as the active privilege
  engine
- Displays App List while sorting them based on Installation source
- Search in App List and Freezer
- Multi-language support (English, Spanish, French, Arabic, Chinese) with in-app language switcher
- Launch App Activities
- Install/Uninstall/Freeze/Unfreeze Apk files
- Suspend/Unsuspend apps (shows custom Thor-branded system dialog)
- Background Restriction (restrict app background activity)
- Reinstall APKs/Fix Store installer record (available in all privilege modes)
- Share App Apk file
- Batch Reinstall/Uninstall/Freeze/Unfreeze/Kill/Suspend/Clear Data
- Split App Indicator
- AppState Indicator (frozen / suspended / hidden)
- Local icon caching and danger badges for user-uninstalled system apps
- Uninstall System Apps
- Freeze/UnFreeze System apps
- Sorting & filters
- Layout preference persistence (grid/list mode preserved across restarts)
- Clear Data/Cache (available in all privilege modes)

## Upcoming Features

- BackUp App Data
- Editing Packages.xml
- Batch Install
- Launcher-shortcut / deep-link triggering for automation extensions, via an authenticated handoff
  (explicit-component intent or nonce-signed token). The earlier public `thor://extension/trigger`
  deep link was removed because, being exported, any app could drive triggers through Thor's
  signature-level permission.
- Many more

## 💖 Support Development

Thor is a labor of love, built to be **100% offline, ad-free, and tracker-free**. If this tool has
made your Android management easier, consider supporting its continued development. Your
contributions help keep the project alive and free for everyone.

| Platform            | Link                                                        |
|---------------------|-------------------------------------------------------------|
| **Patreon**         | [Support on Patreon](https://www.patreon.com/trinadh)       |
| **Buy Me a Coffee** | [Buy me a coffee](https://www.buymeacoffee.com/trinadh)     |
| **PayPal**          | [Donate via PayPal](https://www.paypal.me/trinadhthatakula) |

## Credits

- Built the **Odin** root-service binding framework inside the [
  `suCore`](https://github.com/trinadhthatakula/Thor/tree/master/suCore) module under package `com.valhalla.superuser`. Odin was adapted from the architectural design of [`libsu`](https://github.com/topjohnwu/libsu) by [topjohnwu](https://github.com/topjohnwu/) and completely rewritten to eliminate all `com.topjohnwu` package namespaces.
- Replaced [`AndroidHiddenApiBypass`](https://github.com/LSPosed/AndroidHiddenApiBypass) with an
  internal Kotlin implementation in the [
  `bypass`](https://github.com/trinadhthatakula/Thor/tree/master/bypass) module, backed by Java
  stubs in the [`vm-runtime`](https://github.com/trinadhthatakula/Thor/tree/master/vm-runtime)
  module for maximum compatibility when shadowing system classes.

### Architectural Advances in Odin (suCore)

- **Pure Kotlin**: Fully converted the original Java-based design of `libsu` into a high-performance, modern, reactive Kotlin library.
- **Zero Raw Binaries**: Completely eliminated precompiled binary dependencies (such as `main.jar`), bypassing external file extractions.
- **Direct APK Classpath Invocations**: Dynamically mounts the app's main APK (`base.apk`) on `app_process`'s execution `CLASSPATH`, bootstrapping directly using a custom Kotlin classloader trampoline (`com.valhalla.superuser.internal.RootServerMain`).
- Refer to the SuCore [README](https://github.com/trinadhthatakula/Thor/blob/master/suCore/README.md) for more details.

## License

**Copyright © 2025–2026 Trinadh Thatakula.**

Thor is free software: you can redistribute it and/or modify it under the terms of the **GNU
General Public License as published by the Free Software Foundation — either version 3 of the
License, or (at your option) any later version** (`GPL-3.0-or-later`). See the [LICENSE](LICENSE)
file for the full text.

- The project as a whole is distributed under the GNU General Public License v3.0 or later.
- Portions derive from [`libsu`](https://github.com/topjohnwu/libsu) (Apache-2.0); all such use
  complies with the Apache-2.0 requirements.
- The **Thor name, logo, and icon are trademarks** of Trinadh Thatakula and are **not** licensed
  under the GPL. See [TRADEMARK.md](TRADEMARK.md).

### ⚠️ Official builds & unofficial forks

Thor is **100% FOSS with no ads and no trackers**. Official, ad-free builds come **only** from
[Google Play](https://play.google.com/store/apps/details?id=com.valhalla.thor),
[IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/com.valhalla.thor),
[Indus App Store](https://www.indusappstore.com/apps/productivity/thor/com.valhalla.thor/), and
[GitHub Releases](https://github.com/trinadhthatakula/Thor/releases).

The GPL guarantees your right to fork and modify Thor. However, an unofficial build that **injects
ads or trackers, or bundles a proprietary ad SDK** (e.g. AdMob) is **not** the official Thor, is
not endorsed, and — because it combines GPL-licensed code with proprietary components — is likely
in violation of the GPL. Such builds must also **rename the app and change the icon** per
[TRADEMARK.md](TRADEMARK.md). Please report them.
