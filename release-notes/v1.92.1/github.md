# Thor v1.92.1 Release Notes

This release marks a massive structural milestone for Thor. It introduces the new **Auto Reinstall Apps** feature, completely replaces the legacy binary-based root framework with our modern, zero-dependency, pure-Kotlin privileged process engine (**Odin**), introduces advanced API bypass caching, fixes long-standing multi-platform binder leaks, and resolves background ANR/crash points.

---

## What's Changed

### 🔄 Auto Reinstall Apps Feature
Thor can now synchronize and reinstall your uninstalled/frozen apps cleanly with customized parameters:
* **One-Click Reinstall**: Sync metadata automatically and trigger install-time overrides directly from your app manager view.
* **Idempotency Guarantee**: Removed redundant processing caches to ensure seamless, idempotent reinstallations (`df642fa`).
* **Preferences Consolidation**: Unified user preference caching and installer arguments into a centralized, reactive `PreferenceRepository` (`e9df91b`).

### 🥶 Re-Engineering the Root Core (Odin Engine)
We have fully decommissioned the legacy binary-based privileged helper in favor of a modern, home-grown privileged container:
* **Pure Kotlin Bootstrapping**: Swapped the upstream, pre-compiled `main.jar` with a pure-Kotlin bootstrapper that loads dynamically on the `app_process` classpath directly from your installed APK (`b8e0c08`). This means zero external binary assets to audit, write to disk, or cache.
* **Military-Grade Caller Verification**: Implemented strict, cryptographic-level UID verification checks on our Binder interfaces (`703685d`). Only root, the system server, or Thor's exact authenticated UID are allowed access.
* **Thread-Safe, Non-Blocking IPC**: Shuffled all AIDL transactions off the main execution thread (`703685d`), solving background stutters, layout lag, and layout rendering ANRs on low-end devices.
* **Auto-Reset Lifecycle**: Added proactive process-restarting on deployment (`0261496`) to guarantee newly compiled privileged code is executed immediately.

### 🔒 Modern API Bypass & Offset Caching
* **Robust Unsafe Mapping**: Fixed standard reflection issues by extracting the direct static instance field `theUnsafe` (`5f3d34d`) inside JVM/ART, making private API access completely immune to standard `SecurityException` blocks.
* **Stable Reflections**: Discarded unstable `ClassLoader` hacks in favor of direct local shadow class inspections, completely removing the crash-prone custom `CoreOjClassLoader.kt` to prevent native segment-faults.
* **Targeted Platform Fixes**: Corrected an incorrect `RECEIVER_NOT_EXPORTED` flag registration (`5f3d34d`) to resolve standard system crashes on devices running Android 8.0 through 12L (API 26–32).
* **Memory Leak Elimination**: Implemented class-level persistent tracking of the active `ServiceConnection` inside `RootSystemGateway.kt`, ensuring any stale connections are robustly unbound on coroutine cancellation or re-bindings.

### 🎨 UI & UX Polish
* **Action Styling**: Enhanced high-contrast styling for freezer action buttons and dialog prompts (`0261496`).
* **Interactive Settings**: Added smooth click-to-marquee scroll animations to help view extra-long configuration strings in settings rows (`ebe723c`).
* **Manifest Cleanups**: Added `tools:ignore="Instantiatable"` annotations to suppress IDE warnings regarding non-service root class registrations (`5988954`).

---

## 🛠 Commits Log (`v1.92.0...HEAD`)

* `5988954` - fix(manifest): ignore 'Instantiatable' lint warning for ThorRootService
* `5f3d34d` - Refactor Odin system & bypass mechanisms to address PR code-review findings
* `0261496` - feat(freezer): auto-reset daemon and style freezer action button
* `57c16ad` - fix(Odin): resolve package manager check failure under root shell and add transaction error logging
* `703685d` - impl(Odin): secure root service daemon and prevent thread-blocking IPC
* `b8e0c08` - refactor(suCore): migrate namespace to com.valhalla.superuser (Odin) and bypass main.jar
* `df1110e` - fix(bypass): Change pm.getModuleInfo flags parameter from 1 to 0 to resolve WrongConstant lint error
* `fab6a01` - refactor(bypass): remove RequiresApi annotations and modernize Kotlin syntax
* `04dac18` - fix: Annotate writeToDownloads with RequiresApi(Q) to prevent lint warning on minSdk 28
* `e0b0f0f` - refactor: Migrate legacy Uri.parse and ContentValues to Android KTX extensions
* `634a23f` - refactor: simplify Toast string resources, use toUri() for intents, and sort imports
* `b13bbd4` - refactor: Migrate deprecated material3 and SDK 35 APIs to modern equivalents
* `b627ff7` - Upgrade bypass and vm-runtime modules with multi-layered Kotlin-first bypass and offset caching
* `df642fa` - fix(reinstall): remove redundant processedPackages cache in AutoReinstallReceiver to ensure idempotency
* `e9df91b` - refactor: consolidate duplicated user preference and installerArg fetching into PreferenceRepository
* `ebe723c` - feat(settings): enable marquee on click for various switch settings
* `b858da1` - chore(release): bump versionCode to 1921 (v1.92.1)
* `6e689e8` - feat(reinstall): implement Auto Reinstall Apps feature with install-time overrides and AutoReinstallReceiver metadata sync
