# Thor App Manager - Project Context

Thor is a modern, lightweight, and privacy-focused Android App Manager. It is designed to be 100% offline, free, and open-source (FOSS), providing advanced app management capabilities through Shizuku, Dhizuku, and Root access.

## 🏗 Architecture
The project follows **Clean Architecture** principles combined with **MVVM (Model-View-ViewModel)** for the presentation layer.

### Modules:
- **`app/`**: The core application module.
  - **Presentation**: Built with **Jetpack Compose**. ViewModels manage state using `StateFlow` and Koin for dependency injection.
  - **Domain**: Pure Kotlin layer containing business logic, Use Cases, and repository interfaces. Platform-agnostic where possible.
  - **Data**: Implementation of repositories, interacting with Android's `PackageManager`, `Shizuku`/`Dhizuku` APIs, and `DataStore` for persistence.
  - **DI**: Dependency Injection using **Koin**, organized into `commonModule`, `installerModule`, `preferenceModule`, and `coreModule`.
- **`suCore/`**: A specialized module for root shell management. It's a Kotlin-refactored version of the `libsu` core module by `topjohnwu`, optimized for modern Kotlin idioms and memory safety.

## 🛠 Tech Stack
- **Language**: 100% Kotlin
- **UI Framework**: Jetpack Compose (Material 3 with Dynamic Colors/Material You)
- **Dependency Injection**: Koin
- **Asynchronous Programming**: Kotlin Coroutines & Flow
- **Image Loading**: Coil
- **Animation**: Lottie
- **Persistence**: Jetpack DataStore (Preferences)
- **Security**: Android Biometrics (Fingerprint Lock)
- **Elevated Privileges**: 
  - **Shizuku / Dhizuku API**: For system-level operations without full root.
  - **Root (su)**: Via `suCore` module.
  - **HiddenApiBypass**: To access restricted Android internal APIs.
- **Build System**: Gradle Kotlin DSL with Version Catalog (`libs.versions.toml`).
- **Distribution**: Two product flavors: `store` (Play Store compliant) and `foss` (fully libre/open).

## ✨ Key Features
- **App Management**: Install, uninstall, freeze (disable), and unfreeze apps.
- **Batch Operations**: Batch reinstall, uninstall, and kill apps.
- **Advanced Insights**: Display app installers (source), split APK indicators, version codes, and SDK targets.
- **System App Support**: Ability to uninstall or freeze system applications (requires Shizuku/Root).
- **Security**: Fingerprint lock for app access.
- **Customization**: Dark/Light/AMOLED themes with Material You support.
- **Privacy**: Fully offline, no ads, no trackers, and FOSS (GPL-3.0).

## ⚠️ Limitations
- **Privilege Dependency**: Advanced features like freezing or system app removal require Shizuku, Dhizuku, or Root access.
- **Offline Only**: No cloud backup or remote synchronization (by design, for privacy).
- **Android Constraints**: Subject to evolving Android security restrictions (e.g., Target SDK 36).
- **Feature Gap**: Currently lacks comprehensive app data backup (planned for future releases).

## 🚀 Opportunities
- **Data Backup**: Implementing local app data backup and restoration.
- **Package Editing**: Direct editing of `packages.xml` for advanced users.
- **Automation**: Scheduled freezing/unfreezing or automated cleanup tasks.
- **Installer Integration**: Expanding support for third-party installers (e.g., F-Droid, Aurora Store).

## 🛡 Threats
- **Play Store Policies**: As an "App Manager" with elevated privileges, it faces strict scrutiny from Google Play.
- **Android OS Changes**: Future Android updates might further restrict Shizuku or root-level access methods.
- **Competition**: Several established open-source app managers exist; maintaining a niche in "lightweight & offline" is key.
