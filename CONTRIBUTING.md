# Contributing to Thor App Manager

Thank you for your interest in contributing to Thor! Whether you want to fix a bug, add a new feature, or localize the app into your language, your help is highly valued.

Here is a guide on how you can contribute to the project.

---

## 🌐 Localization & Translation Contributions

We want Thor to be accessible to everyone worldwide. You can help by translating either the **In-App Strings** or the **Store Metadata (Fastlane)**.

### 1. In-App Strings Translation
In-app strings are stored in standard Android resources:
* **Base Strings**: [app/src/main/res/values/strings.xml](app/src/main/res/values/strings.xml)
* **Localized Strings**: Located under `app/src/main/res/values-<locale-code>/strings.xml` (e.g., [values-es/strings.xml](app/src/main/res/values-es/strings.xml) for Spanish).

**How to contribute:**
1. Identify your target language code (e.g., `hi` for Hindi, `de` for German).
2. Create the directory `app/src/main/res/values-<locale-code>/` if it doesn't exist.
3. Copy the base [strings.xml](app/src/main/res/values/strings.xml) to your new folder and translate the text inside the `<string>` tags.

### 2. Store Listing Metadata (Fastlane) Translation
We use Fastlane to deploy the app to the Google Play Store and other stores. Store listings are localized under the [fastlane/metadata/android/](fastlane/metadata/android) directory.

**Directory Structure:**
```text
fastlane/metadata/android/
  ├── en-US/                  <-- English (Default)
  ├── hi-IN/                  <-- Hindi (India)
  └── <your-locale-code>/     <-- Your target locale (e.g., es-ES, fr-FR)
        ├── title.txt         <-- App title (Max 30 characters)
        ├── short_description.txt <-- Short description (Max 80 characters)
        └── full_description.txt  <-- Full description (Max 4000 characters)
```

**How to contribute:**
1. Create a directory under `fastlane/metadata/android/` with your standard locale identifier (e.g., `es-ES`, `de-DE`, `fr-FR`).
2. Add the following three files:
   * **`title.txt`**: Usually kept as `Thor - App Manager`.
   * **`short_description.txt`**: A catchy summary (max 80 characters).
   * **`full_description.txt`**: A detailed plain text description (max 4000 characters). 
     * *Note: Google Play Console does not support markdown in descriptions, and plain text with emoji bullet points is preferred over HTML tags. Check [en-US/full_description.txt](fastlane/metadata/android/en-US/full_description.txt) for the master reference.*

---

## 💻 Code Contributions

Thor is built using modern Android development practices. Please read the architecture and build guidelines before writing code.

### Tech Stack & Architecture
* **Language**: 100% Kotlin codebase.
* **UI**: Jetpack Compose with Material 3.
* **Architecture**: Clean Architecture (Domain, Data, Presentation layers).
* **Dependency Injection**: Koin.
* **Database**: Room DB (app list cache).
* **Root Operations**: Custom Kotlin fork of libsu (`suCore` module).
* **Hidden API Bypass**: Custom internal `:bypass` module.

### Useful Build Commands
* **Assemble Debug APK (FOSS)**: `./gradlew assembleFossDebug`
* **Assemble Release APKs**: `./gradlew assembleFossRelease` (FOSS) or `./gradlew assembleStoreRelease` (Play Store)
* **Run Unit Tests**: `./gradlew test`
* **Run Lint**: `./gradlew lint`
* **Clean Project**: `./gradlew clean`

### Versioning
* Version code is configured using the single `versionCode` property in [gradle.properties](gradle.properties).
* Do **not** edit `versionName` directly; it is automatically calculated from the version code (e.g. `1822` becomes `1.82.2`).

### Java Shadowing Requirement
If you modify Dalvik VM shadowing or hidden API bypasses, place your stub class definitions in the `:vm-runtime` module which compile-only shadows system classes using pure Java.

---

## 🚀 How to Submit Your Contribution

1. **Fork the Repository**: Create your own fork of the repository.
2. **Create a Branch**: 
   * For translations: `translate/<locale-code>` (e.g. `translate/hi-IN`)
   * For features/bugfixes: `feature/<feature-name>` or `fix/<bug-name>`
3. **Commit your changes**: Write clear, descriptive commit messages.
4. **Run Verification**: Ensure your code builds (`./gradlew assembleFossDebug`) and tests pass (`./gradlew test`).
5. **Submit a Pull Request**: Submit your pull request targeting the `dev` branch of the main repository.

---

Thank you again for contributing to Thor App Manager! 💖
