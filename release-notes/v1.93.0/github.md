# Thor v1.93.0 Release Notes

The next **stable** release — a consolidation of the entire development cycle since **v1.92.0**. Thor's root engine has been rebuilt, hardened, and open-sourced as **Odin**; there's a new **Auto Reinstall Apps** feature; the **Home screen was redesigned** into an adaptive bento grid with one-tap access to the Extension Manager; the **release-build app-suspend bug is fixed**; the direct-download APK is **~48% smaller**; a **66-finding full-codebase audit** (crashes, memory/binder leaks, background ANRs, Clean-Architecture domain purity) was remediated; and the app now speaks **four new languages**.

> This changelog bundles everything shipped across the v1.92.1, v1.92.2 and v1.92.3 development builds, plus post-release hardening, into one stable release.

---

## ✨ Highlights

* 🔧 **Odin** — the in-house `libsu` fork is rebuilt in pure Kotlin, hardened, and published open-source (Apache-2.0) to Maven Central.
* 🔄 **Auto Reinstall Apps** — sync and reinstall your apps with custom install-time options.
* 🎨 **Redesigned, fully-adaptive Home** with a bento action grid and an Extension Manager tile.
* 🐛 **App suspend fixed on release / Play builds.**
* 📦 **~48% smaller** direct-download APK.
* 🛡️ **66-finding stability, leak & architecture audit** remediated.
* 🌍 **Arabic, Spanish, French & Chinese (Simplified)** translations.

---

## What's Changed

### 🔧 Odin — Root Engine Rebuilt, Hardened & Open-Sourced (#254, #264, #266)
Thor's privileged core was completely re-engineered and then extracted into its own open-source library:
* **Pure-Kotlin bootstrapping**: the upstream pre-compiled `main.jar` was replaced with a pure-Kotlin bootstrapper that loads on the `app_process` classpath directly from the installed APK — zero external binary assets to audit, write to disk, or cache (`b8e0c08`).
* **Now open source**: the fork is a standalone, Apache-2.0 library published to Maven Central as **`com.trinadhthatakula:odin:1.0.0`**. The bundled `:suCore` module is gone; Thor consumes the published dependency (`aacc18e`, `e58a5f1`).
* **Hang-proof root probe**: root detection can no longer wedge the app — a failed shell init is signalled via `onShellDied`, the `isRootGranted` probe is time-bounded, and it correctly rethrows cancellation (`0560e93`, `ec75323`).
* **Honest exit codes**: root shell commands now return the **real** process exit code — matching the Shizuku/Dhizuku gateways — instead of a hard-coded success (`aacc18e`).
* **Failures propagate across the Binder**: privileged operations (suspend / clear-data) now report real success or failure across the root-service `Binder` instead of silently reporting success (`28fef5c`).
* **Military-grade caller verification**: strict UID checks on the Binder interfaces — only root, the system server, or Thor's exact authenticated UID may bind — via a reusable `RootService.enforceCaller()` (`703685d`, `c77c668`).
* **Thread-safe, non-blocking IPC**: all AIDL transactions moved off the main thread, removing background stutters, layout lag, and ANRs on low-end devices (`703685d`).
* **Auto-reset lifecycle**: the privileged daemon proactively restarts on deployment so newly compiled code executes immediately (`0261496`).
* **Resilient root binding**: a dead privileged-daemon binder is now detected and re-bound instead of failing later root operations, and the Auto Reinstall receiver validates incoming package names (`72446f7`).

### 🐛 App Suspend Fixed on Release Builds (#265)
* Suspending / unsuspending apps silently failed on **release / Play** builds: R8 renamed bundled framework shadow classes, so the runtime calls hit the real framework classes and threw. The shadows were removed and the root-service daemon is kept intact, so **suspend works again on shipped builds** (`77ec023`).

### 🎨 Portable Installer Follows Your Theme (#273)
* Opening an APK from Files or another app now renders the installer in **your** chosen theme — dark / light mode, Material You dynamic color and AMOLED — instead of only the device night setting. `PortableInstallerActivity` was themed with defaults; it now reads your preferences like the rest of the app (`6bfb6ea`).

### 🔄 Auto Reinstall Apps (#251)
* **One-click reinstall**: sync app metadata automatically and trigger install-time overrides directly from your app-manager view (`6e689e8`).
* **Idempotency guarantee**: removed a redundant processing cache so reinstallations are seamless and idempotent (`df642fa`).
* **Preferences consolidation**: unified user-preference caching and installer arguments into a centralized, reactive `PreferenceRepository` (`e9df91b`).

### 🎨 Redesigned Home — Adaptive Bento & Extension Manager (#260, #261)
* **Adaptive bento grid**: Home's stacked action cards are now a responsive 2×2 "bento" grid (`BentoTile` + `HomeActionsBento`), where a row collapses to full-width when a tile isn't applicable (`3323f10`, `bf69d7d`, `e55af89`, `93c9242`).
* **Extension Manager on Home**: a new **Extensions** tile opens the Extension Manager straight from Home (previously buried under Settings), shown only when a privilege (Root/Shizuku/Dhizuku) is active; back-navigation now correctly targets the active tab's back stack (`1829ec5`).
* **Truly adaptive**: the layout adjusts across compact / medium / expanded window sizes and caps its content width on large screens so it never sprawls (`1ab4a9f`).
* **Extension list correctness**: rapidly leaving and re-opening the Extension Manager no longer lets a slower, stale refresh overwrite newer state (`191f217`).
* **Landscape / tablet / foldable fixes**: the app-info, export, freezer-settings and installer sheets now scroll so actions are never cut off in landscape (`b72500a`), and the installer resets its scroll position on each install-state change (`cdb1659`); the Support & Community card aligns with the distribution chart and its buttons stack neatly on narrow panes (`0c1ca05`, `34aeb12`).

### 🔒 Modern API Bypass & Offset Caching (#254)
* **Robust unsafe mapping**: extracts the direct static `theUnsafe` field inside JVM/ART, making private-API access immune to standard `SecurityException` blocks (`5f3d34d`).
* **Stable reflections**: discarded unstable `ClassLoader` hacks for direct local shadow-class inspection, removing the crash-prone `CoreOjClassLoader` and preventing native segfaults; adds multi-layered, Kotlin-first bypass with offset caching (`b627ff7`).
* **Safer receiver registration**: app-private broadcast receivers (the root-service receiver and the installer-status receiver) now register with the version-safe `RECEIVER_NOT_EXPORTED` flag (`b8e0c08`, `60d10f7`).

### 🛡️ Stability, Leaks & Architecture — 66-Finding Audit (#256, #257, #258)
A full-codebase audit surfaced 66 issues; this release resolves them:
* **Crashes & robustness**: fixed an app-list rotation crash, a root-bind deadlock, and an extensions-screen crash; hardened the privilege gateways and freezer tile service (`9e8eb39`).
* **Memory / binder leaks**: fixed installer event-bus bitmap retention, per-tick coroutine-scope churn, abandoned PackageInstaller sessions, and a stale `ServiceConnection` in `RootSystemGateway` (`9e8eb39`, `d024694`).
* **No more background ANRs / jank**: one-off UI events moved to a lifecycle-safe buffered channel, all blocking I/O moved onto injected IO dispatchers, and framework types pulled out of ViewModels (`b5056de`, `7711f3e`, `6c84683`).
* **Installer hardening**: avoids `TransactionTooLargeException` when listing extensions (`537b37a`) plus batch-1 medium fixes across install/enumeration paths (`60d10f7`).
* **Clean-Architecture domain purity**: the domain layer is now free of Android framework types — icons load via a cache path + Coil, install intents ride a data-layer holder, dead models removed, and a single source of truth for preferences (`96bd218`, `eba85d8`, `aa9239c`, `b67aea8`, `0893980`, `3a798c7`).
* Plus numerous review-driven fixes across multiple gemini-code-assist rounds and holistic self-reviews.

### 🧊 Freezer Fix (#259)
* Freezing one of **your own** apps no longer shows the alarming "Freeze System App?" warning — that safety prompt is now correctly limited to actual system apps (`48a9539`).

### 📦 Much Smaller Download — foss APK 6.23 MB → 3.24 MB (−48%)
* The direct-download APK now **compresses its `classes.dex`** (`useLegacyPackaging`), the **foss build is obfuscated** (matching the store build), and foss ships **only its bundled locales**; bundled fonts were also subset — together nearly halving the foss-release APK (`38d875b`).
* **Note:** Google Play App Bundles are delivered per-device and are, by design, largely unaffected by the dex-packaging lever — this size win is biggest for the GitHub / direct-download APK.

### 🧹 Polish — Lint, Qodana & Leaner Vectors (#268, #269)
* Resolved the outstanding **Android lint + Qodana** findings: correct `<plurals>` handling, unused-resource cleanup, `mipmap-anydpi` rename, and coroutines bumped to 1.11.0 (`979f937`).
* **Losslessly compacted** the two largest vector drawables (`thor_mono`, `thor_drawn_foreground`) — **−6.8 KB** with **pixel-identical** rendering, verified by render-diff (`AE == 0`) at multiple sizes (`7055d50`, `7903a07`).

### 🌍 Localization (#259, #260)
* **68 strings** translated into **Arabic, Spanish, French and Chinese (Simplified)** (`5780379`), plus the new Home "Extensions" strings (`82cebb3`).

### 🎨 Other UI & UX Polish (#251)
* High-contrast styling for freezer action buttons and dialog prompts (`0261496`).
* Click-to-marquee scroll animations for extra-long configuration strings in settings rows (`ebe723c`).

---

## 🛠 Build & Dependencies
* **Version**: bumped to **1.93.0 (1930)** in `gradle.properties`.
* **Odin**: consumes the published `com.trinadhthatakula:odin:1.0.0` (Apache-2.0, Maven Central); the in-tree `:suCore` module was removed.
* **Dependency updates** (Dependabot): AndroidX Compose Material3 and grouped Maven/Gradle bumps, plus a GitHub Actions group bump (`cdfb7f7`, `d8573e5`, `6e86087`, `7e54cc0`).

---

## 🛠 Commits Log (`v1.92.0...HEAD`)

**Merged pull requests**
* `663eaac` — #273 fix portable-installer theming (honor user theme / dynamic color / AMOLED)
* `9f424ae` — #270 promote v1.92.3 to stable (dev → master)
* `9b1e1f3` — #269 losslessly compact the long `VectorPath` drawables
* `fbc8a8e` — #268 resolve Android lint + Qodana findings
* `11188cd` — #266 Odin Phase 3: consume published `com.trinadhthatakula:odin:1.0.0`
* `47e51b4` — #265 fix release-only app suspend broken by R8 (RootService daemon)
* `f7cc74d` — #264 Odin Phase 1: MainShell hang fix + move `ThorRootService` into `:app`
* `f14132f` — #263 promote v1.92.2 (dev → master)
* `df30057` — #262 release v1.92.2 (version bump + notes)
* `3f68fcb` — #261 make bottom sheets vertically scrollable in landscape
* `afcd353` — #260 adaptive Home bento actions grid + Extension Manager entry
* `55fb583` — #259 freezer freeze-dialog fix + lint cleanup + 68 translations
* `c24d991` — #258 A3 domain-layer purity refactor
* `68230ff` — #257 audit A-series (events/dispatchers/VM framework-types) + P2 tail
* `b9269a1` — #256 audit P0/P1 + leaks + robustness + batch-1 mediums
* `987478b` — #255 release-notes guide + v1.92.1 notes + PR review fixes
* `e03a0f5` — #254 bypass + Odin engine upgrade (offset caching, secure daemon)
* `4bbce47` — #251 Auto Reinstall Apps feature
* `295aa79` (#253) / `ca6da96` (#252) / `fbd3d3c` (#250) / `eb83a4a` (#249) — Dependabot dependency bumps

**Key commits**
* `6bfb6ea` fix(installer): honor user theme, dynamic color & AMOLED in portable installer
* `28fef5c` fix(root): propagate privileged-op failures across the `ThorRootService` Binder
* `aacc18e` feat(odin): consume published Odin 1.0.0; delete `:suCore`; real shell exit codes
* `77ec023` fix(app): fix release-only app suspend broken by R8
* `c77c668` refactor(sucore): move `ThorRootService` into `:app`; add `RootService.enforceCaller()`
* `0560e93` fix(sucore): signal shell-init failure via `onShellDied` + bound `isRootGranted` so it can't hang
* `ec75323` fix(sucore): rethrow `CancellationException` in the `isRootGranted` probe
* `38d875b` perf(app): shrink foss-release APK 6.23 MB → 3.24 MB (−48%)
* `979f937` chore(lint): resolve Android lint + Qodana findings (plurals, resources, coroutines 1.11.0)
* `7055d50` / `7903a07` perf(res): losslessly compact `thor_mono` / `thor_drawn_foreground` pathData
* `3323f10` feat(home): render actions as adaptive bento; add Extension Manager tile; drop ActionCard
* `1829ec5` feat(home): open Extension Manager from Home; active-stack back handling
* `b72500a` fix(ui): scrollable bottom sheets (landscape-phone crop)
* `48a9539` fix(freezer): don't show "Freeze System App?" for user apps
* `5780379` i18n: translate 68 strings into ar/es/fr/zh-rCN
* `96bd218`…`3a798c7` refactor(audit): A3 domain purity (stages 1–6)
* `b5056de` / `7711f3e` / `6c84683` refactor(audit): A-series + P2 tail
* `60d10f7` fix(audit): batch-1 medium findings; `9e8eb39` fix(audit): P0/P1 findings
* `b627ff7` upgrade bypass + vm-runtime (Kotlin-first bypass, offset caching)
* `5f3d34d` refactor: Odin & bypass review fixes (`theUnsafe` static-field mapping)
* `b8e0c08` refactor(suCore): pure-Kotlin bootstrap; drop upstream `main.jar`
* `703685d` impl(Odin): secure root daemon + non-blocking IPC
* `6e689e8` feat(reinstall): Auto Reinstall Apps with install-time overrides
* `e9df91b` refactor: consolidate preferences/installer args into `PreferenceRepository`
* `ebe723c` feat(settings): marquee-on-click for long setting values
* `191f217` fix(extensions): cancel stale `loadExtensions()` so it can't overwrite newer state (#263)
* `72446f7` fix(pr): root dead-binder recovery + Auto Reinstall package-name validation (#255)
