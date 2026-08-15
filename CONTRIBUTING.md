# Contributing to Thor App Manager

Thank you for your interest in contributing to Thor! Whether you want to fix a bug, add a new feature, or localize the app into your language, your help is highly valued.

Here is a guide on how you can contribute to the project.

---

## 🌐 Localization & Translation Contributions

We want Thor to be accessible to everyone worldwide. You can help by translating either the **In-App Strings** or the **Store Metadata (Fastlane)**.

### 1. In-App Strings Translation
In-app strings are stored in standard Android resources, split across **three** files:
* **Base Strings**: [values/strings.xml](app/src/main/res/values/strings.xml) (the bulk),
  [values/strings_settings.xml](app/src/main/res/values/strings_settings.xml) and
  [values/strings_backup.xml](app/src/main/res/values/strings_backup.xml)
* **Localized Strings**: the same three file names under `app/src/main/res/values-<locale-code>/`
  (e.g. [values-pt/](app/src/main/res/values-pt) for Portuguese).

`values/non-translatable.xml` is the one file you should *not* copy — everything in it is marked
`translatable="false"` on purpose.

The file names carry no meaning to the resource merger: it keys on `name=`, so what matters is that
the union of your files covers the union of the base ones. Mirror the three-file split anyway — the
four oldest locales (`ar`, `es`, `fr`, `zh-rCN`) predate it and fold the settings strings into their
`strings.xml`, which is why their `strings.xml` is longer than the base one.

**How to contribute:**
1. Identify your target language code (e.g., `hi` for Hindi, `de` for German).
2. Create the directory `app/src/main/res/values-<locale-code>/` if it doesn't exist.
3. Copy the three base files into it and translate the text inside the `<string>` tags. Every
   `name=` must survive: lint runs with `warningsAsErrors`, so **one missing string fails the
   build**, not just your locale.
4. Get the `<plurals>` categories right for your language — the ones CLDR defines for it, no more
   and no fewer. Polish needs `one/few/many/other`; Chinese needs only `other`; a category your
   language does not have is a lint error too.
5. Leave every `%1$s`, `%1$d` and `\n` exactly as the English has them, and escape a literal
   apostrophe as `\'`.

**Then wire the language up, or it ships and nobody can select it.** A `values-xx` directory alone
does nothing; five other places have to agree, and `lintFossDebug` only catches two of them:

| File | What to add |
|---|---|
| [`app/build.gradle.kts`](app/build.gradle.kts) → `translatedLocales` | the **resource qualifier** (`pt-rBR`, not `pt-BR`). Missing here means the resources are compiled and then filtered straight back out. |
| [`res/xml/locales_config.xml`](app/src/main/res/xml/locales_config.xml) | the **BCP-47 tag** (`pt-BR`, not `pt-rBR`). This is what Android 13+'s own per-app language screen reads. |
| [`LocalePolicy.kt`](app/src/main/java/com/valhalla/thor/util/LocalePolicy.kt) → `AppLanguage` | an enum entry with its BCP-47 tag. Read the KDoc first — whether the tag carries a region is not a free choice. |
| [`SettingsCatalog.kt`](app/src/main/java/com/valhalla/thor/presentation/settings/SettingsCatalog.kt) → `labelRes` | the picker's own row label, plus a new `<string>` for the language name **in every locale** (they are exonyms — `values-fr` says "Anglais", not "English"). |
| [`app/src/store/res/values-<x>/strings.xml`](app/src/store/res) | the 16 billing/support strings of the **store flavour**. They live outside `src/main`, so `lintFossDebug` cannot see them — only `lintStoreRelease` (which is what CI runs) reports them missing. |

`LocalePolicyTest` pins several of these, so before opening the PR run
`./gradlew :app:testFossDebugUnitTest :app:lintFossDebug :app:lintStoreRelease` — **both** lint
variants, or the store source set stays unchecked until CI says otherwise.

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
* **Language**: Kotlin — every module except `vm-runtime`, which is intentionally pure Java so its
  stubs shadow the platform classes correctly.
* **UI**: Jetpack Compose with Material 3.
* **Architecture**: Clean Architecture (Domain, Data, Presentation layers).
* **Dependency Injection**: Koin.
* **Database**: Room DB (app list cache).
* **Root Operations**: [Odin](https://github.com/trinadhthatakula/Odin), an in-house Kotlin fork of
  libsu, consumed as `com.trinadhthatakula:odin` from Maven Central.
* **Hidden API Bypass**: Custom internal `:bypass` module.
* **Background Work**: WorkManager, but only for two operations — see
  [docs/workers/README.md](docs/workers/README.md) for which ones, what the job seam requires of a new
  job kind, and why every bulk action is a plain coroutine instead.

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

> 📖 **Which branch does what?** Thor uses a three-rung release ladder — `dev` → `master` →
> `production` — and your PR always targets `dev`. See
> [docs/branching-and-releases.md](docs/branching-and-releases.md) for the full picture, including
> how a merged commit reaches the Play Store and why you should not bump `versionCode` in a
> feature PR.

---

Thank you again for contributing to Thor App Manager! 💖
