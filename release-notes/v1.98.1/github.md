# Thor v1.98.1

This tester build adds one-tap Freezer profile shortcuts, APK fingerprints, and readable names
for app exports. [Changes since the last dev build](https://github.com/trinadhthatakula/Thor/compare/v1.98.0-dev-169...b52bd6c320a4c69ae338bba3c9bae3ba11655485)

## ✨ Highlights

- **Freeze a profile from your home screen.** A shortcut freezes the apps currently in the profile.
  Pick from eight icons to tell profiles apart.
- **Compare APK builds at a glance.** App versions can now show a short SHA-256 fingerprint of the
  base APK. App list CSVs include the full checksum.
- **Find exported APKs and bundles more easily.** Filenames include the app name and version.

## What's Changed

### ❄️ Freezer profiles and home screen shortcuts

- Use **Add to Home screen** in a profile's menu to pin it. Tap the shortcut to freeze the profile's
  current apps without opening Thor. [PR #634](https://github.com/trinadhthatakula/Thor/pull/634) (`845a062a`)
- Choose Freezer, Games, Office, Entertainment, Kids, Social, Communication, or Music when creating
  or editing a profile or when pinning a shortcut. Existing profiles start with Freezer, and icon
  changes update an existing shortcut in place. Canceling a new pin keeps the saved profile icon.
  [PR #635](https://github.com/trinadhthatakula/Thor/pull/635) (`235b9f14`)

### 🔍 APK fingerprints

- App Info, installer comparisons, and Backup Hub previews display the first eight characters of
  the base APK's SHA-256 below the version. Tap an underlined version in a preview to copy the full
  checksum. An empty `[]` marks an unavailable fingerprint.
  [PR #631](https://github.com/trinadhthatakula/Thor/pull/631) (`1f31c83e`)
- Installer comparisons keep the old and new versions side by side when they fit and give long
  version codes more room. CSV app list exports record the full checksum in `base_apk_sha256`.
  [PR #631](https://github.com/trinadhthatakula/Thor/pull/631) (`1f31c83e`)

### 📦 App exports

- Queued APK, APKS, and XAPK exports use readable filenames based on the app name and version.
  Retries recover completed exports and distinguish them from files still being written, for both
  Downloads and selected folders. [PR #633](https://github.com/trinadhthatakula/Thor/pull/633) (`9c63b3fc`)

### 🔐 App lock and package handling

- Strengthened app lock verification for biometrics and device credentials. APK bundle extraction
  enforces size and storage limits and cleans up incomplete files on failure.
  [PR #627](https://github.com/trinadhthatakula/Thor/pull/627) (`46158b46`)

## 🔧 Project and build tooling

- Pinned the Gradle distribution checksum and carried the pin forward to Gradle 9.9.2.
  [PR #623](https://github.com/trinadhthatakula/Thor/pull/623), [PR #630](https://github.com/trinadhthatakula/Thor/pull/630)
- Restored CodeQL coverage. [PR #627](https://github.com/trinadhthatakula/Thor/pull/627)
- Updated Gradle dependencies, including Android Gradle Plugin and Asgard, plus Fastlane, GitHub
  Actions, and website dependencies. [PR #624](https://github.com/trinadhthatakula/Thor/pull/624),
  [PR #625](https://github.com/trinadhthatakula/Thor/pull/625), [PR #626](https://github.com/trinadhthatakula/Thor/pull/626),
  [PR #630](https://github.com/trinadhthatakula/Thor/pull/630), [PR #637](https://github.com/trinadhthatakula/Thor/pull/637)

## 🛠 Commits Log

- `d47adf7e` Pin the Gradle 9.9.1 distribution checksum (#623)
- `6d74f734` Update the website dependency group (#626)
- `0b68c287` Update GitHub Actions dependencies (#625)
- `6cbe20bd` Update Fastlane (#624)
- `46158b46` Verify app unlocks, constrain APK extraction, and restore CodeQL coverage (#627)
- `30e10c0a` Update Gradle dependencies (#630)
- `1f31c83e` Show base APK SHA-256 fingerprints alongside package versions (#631)
- `9c63b3fc` Use readable filenames for exported app packages (#633)
- `845a062a` Pin Freezer profiles to the home screen (#634)
- `235b9f14` Let users choose Freezer profile icons (#635)
- `b52bd6c3` Update Android Gradle Plugin and Asgard (#637)
