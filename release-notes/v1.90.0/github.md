# Thor v1.90.0 Release Notes

One of our biggest releases yet — a full UI refresh powered by the new in-house **Asgard** component library, a smarter installer, broader localization, more ways to support the project, and a lot of groundwork laid under the hood.

## What's Changed

### ✨ UI & Experience
*   **Asgard-powered redesign**: Thor now dogfoods our standalone **Asgard** Expressive component library — refreshed navigation bar/rail, connected button groups, and smoother, more consistent motion throughout the app.
*   **App Details**: enhanced layout and information density; the details screen now closes automatically once an uninstall is triggered.
*   **Polish**: corrected theme backgrounds and layout transitions across Home and detail screens.

### 📦 Installer
*   **Tiered, shell-first install strategy** for Shizuku and Dhizuku — installs prefer fast, reliable shell commands (`pm`) and fall back to the reflection-based path only when needed.

### 🌐 Localization
*   Localized terminal logs and added clearer system-app safety warnings.
*   Expanded and refined translations.

### ❤️ Support the Developer
*   The Play Store build now presents support options in two tabs — **Play Store** (subscription tiers) and **Direct** (Patreon & PayPal) — so everyone has a convenient option.

### 🧩 Under the Hood (Experimental)
*   Groundwork for a future **extensions framework**: extension discovery/loading, an automation contract with a Composable configuration screen, host-provided `ShellExecutor`/`ExtensionDataStore`, and a shared contract API published to Maven Central (`com.trinadhthatakula:thor-extension-api`). This feature is **experimental and hidden** in this release while it stabilizes.

### 🐛 Bug Fixes & Stability
*   Fixed a `ButtonGroup` constraints crash that could occur during layout transitions/animations.
*   Privileged extension/shell commands now run through the active privilege gateway (Root/Shizuku/Dhizuku).
*   Moved Room database operations off the main thread to avoid jank/ANRs.
*   Carried forward the system-app unfreeze compatibility fix from v1.82.3.
