# Thor App Manager - Project Context

Thor is a modern, lightweight, and privacy-focused Android App Manager. It is designed to be 100%
offline, free, and open-source (FOSS), providing advanced app management capabilities through
Shizuku, Dhizuku, and Root access.

## 🏗 Architecture

The project follows **Clean Architecture** principles combined with **MVVM (Model-View-ViewModel)**
for the presentation layer.

### Modules:

- **`app/`**: The core application module.
    - **Presentation**: Built with **Jetpack Compose**. ViewModels manage state using `StateFlow`
      and Koin for dependency injection.
    - **Domain**: Pure Kotlin layer containing business logic, Use Cases, and repository interfaces.
      Platform-agnostic where possible.
    - **Data**: Implementation of repositories, interacting with Android's `PackageManager`,
      `Shizuku`/`Dhizuku` APIs, and `DataStore` for persistence.
    - **DI**: Dependency Injection using **Koin**, organized into `commonModule`, `installerModule`,
      `preferenceModule`, `coreModule`, and `roomModule`.
- **`suCore/`**: A specialized module for root shell management. It's a Kotlin-refactored version of
  the `libsu` core module by `topjohnwu`, optimized for modern Kotlin idioms and memory safety.
- **`bypass/`**: A core utility module for bypassing Android's hidden API restrictions using
  `VMRuntime` exemptions and enhanced reflection.
- **`vm-runtime/`**: Compile-only **Java** stubs required for the `bypass` module to interface with
  internal Android classes like `VMRuntime`. Intentionally a pure Java library (not Kotlin) to
  ensure correct class shadowing behaviour at compile time.

## 🛠 Tech Stack

- **Language**: Kotlin (all modules except `vm-runtime`, which is pure Java for stub compatibility)
- **UI Framework**: Jetpack Compose with `MaterialExpressiveTheme` + `MotionScheme.expressive()`.
  Static "Asgardian" color scheme by default; optional Material You dynamic color on Android 12+.
- **Dependency Injection**: Koin
- **Asynchronous Programming**: Kotlin Coroutines & Flow
- **Image Loading**: Coil 3
- **Animation**: Lottie + Compose `AnimatedVisibility`/`AnimatedContent`
- **Persistence**:
    - **Jetpack Room**: High-performance caching of `AppInfo` metadata, invalidated via
      `lastUpdateTime`.
    - **Jetpack DataStore**: User preferences including theme, AMOLED mode, biometric lock, and
      preferred privilege mode.
- **Security**: Android Biometrics (Fingerprint / Device Credential)
- **Elevated Privileges**:
    - **Root (su)**: Via `suCore` module (Kotlin-refactored fork of `libsu`).
    - **Shizuku**: Shell-command-first (`am`, `pm`, `appops`) with reflection fallback via
      `:bypass`.
    - **Dhizuku**: Device Owner API with reflection fallback via `:bypass`.
    - **Work Mode (`PrivilegeMode`)**: User-selectable privilege engine (ROOT / SHIZUKU / DHIZUKU)
      with automatic fallback strategy (Root → Shizuku → Dhizuku).
    - **Internal Bypass (`:bypass`)**: Custom Kotlin implementation using `VMRuntime` exemptions and
      reflection, backed by Java stubs in `:vm-runtime`.
- **Build System**: Gradle Kotlin DSL with Version Catalog (`libs.versions.toml`). AGP 9.x, Kotlin
  2.x, KSP.
- **Distribution**: Two product flavors: `store` (Play Store compliant) and `foss` (fully
  libre/open).

## ✨ Key Features

- **App Management**: Install, uninstall, freeze (disable/enable), suspend/unsuspend, and
  background-restrict apps. Tracks `isSuspended` and `isDebuggable` flags directly on `AppInfo`.
- **Work Mode**: User-selectable privilege engine (`PrivilegeMode`: ROOT, SHIZUKU, DHIZUKU) stored
  in `UserPreferences`. Falls back automatically if the preferred mode is unavailable.
- **Batch Operations**: Batch freeze/unfreeze, reinstall, uninstall, kill, suspend/unsuspend, and
  clear data — all logged in real time through the terminal logger dialog.
- **App Suspension**: Uses `IPackageManager` reflection to suspend apps, showing a custom "Thor"
  -branded system dialog. Supports Android 10 through 13+ with version-specific fallbacks.
- **Background Restriction**: Restricts an app's background activity via `setAppRestricted`.
- **Fix Store (Reinstall with Google)**: Reassigns installer to Play Store. Available in all
  privilege modes (Root, Shizuku, Dhizuku).
- **Clear Data / Clear Cache**: Available in all privilege modes; `clearAppData` uses `pm clear`
  with multi-user support.
- **Advanced Insights**: Installer source (resolved from package labels, not hardcoded), split APK
  indicators, version codes, SDK targets, `isSuspended`, `isHidden`.
- **System App Support**: Uninstall or freeze system apps (requires any privilege mode).
- **Security**: Biometric/device-credential lock for app access. Per-session authentication state.
- **App Metadata Caching**: Room DB cache for `AppInfo`, invalidated via `lastUpdateTime`.
- **Customization**: Dark/Light/System + AMOLED themes. "Asgardian" static color scheme is the
  default; Material You dynamic color opt-in. Preferred privilege mode persisted across sessions.
- **Privacy**: Fully offline, no ads, no trackers, FOSS (GPL-3.0).

## ⚠️ Limitations

- **Privilege Dependency**: Advanced features (freeze, suspend, system app removal) require at least
  one of Root, Shizuku, or Dhizuku. Work Mode selection with automatic fallback mitigates partial
  availability.
- **Suspension Compatibility**: `setAppSuspended` uses reflection against internal APIs; behaviour
  may vary across Android 10–14+ due to API signature changes.
- **Offline Only**: No cloud backup or remote synchronization (by design, for privacy).
- **Android Constraints**: Subject to evolving Android security restrictions (hidden API policy,
  target SDK requirements).
- **Feature Gap**: App data backup is not yet implemented.

## 🚀 Opportunities

- **Data Backup**: Implementing local app data backup and restoration.
- **Package Editing**: Direct editing of `packages.xml` for advanced users.
- **Batch Install**: Installing multiple APKs in one operation.
- **Automation**: Scheduled freezing/unfreezing or automated cleanup tasks.
- **Installer Integration**: Expanding support for third-party installers (e.g., F-Droid, Aurora
  Store).

## 🛡 Threats

- **Play Store Policies**: As an "App Manager" with elevated privileges, it faces strict scrutiny
  from Google Play.
- **Android OS Changes**: Future Android updates might further restrict Shizuku or root-level access
  methods.
- **Competition**: Several established open-source app managers exist; maintaining a niche in "
  lightweight & offline" is key.
