# Thor v1.97.4 Release Notes

Version **1.97.4 (1974)** brings navigation customization and optional combined app lists.
These notes cover changes after the latest published **v1.97.3-beta-11** through dev commit
`aeb160dc`. The beta's promotion merge has no source changes absent from this dev tree.

Source comparison:
[`v1.97.3-beta-11...aeb160dc`](https://github.com/trinadhthatakula/Thor/compare/v1.97.3-beta-11...aeb160dc723dc5fc0d94ab64537a69c2b492adaa).

## ✨ Highlights

- 🧭 **Customize navigation:** preview docked or floating navigation, styles, labels, icons and
  shapes before applying your choices.
- 📱 **Browse apps together:** opt in to show user and system apps in the same Apps and Freezer
  lists, with separately remembered filters.
- 🛡️ **Understand app types:** user apps get a User app label; debloat recommendations remain
  specific to system apps.

## What's Changed

### 🧭 Navigation customization and floating placement

[PR #608](https://github.com/trinadhthatakula/Thor/pull/608) (`ea9b1583`, `b8b2d6cf`):

- Open **Settings → Customization → Navigation bar** to preview docked or floating placement,
  expanding, segmented or standard styles, icons, labels, shapes, spacing and padding. Floating
  margin and elevation are adjustable when floating placement is selected.
- Tap the preview to try destinations. **Apply** saves the draft; Cancel and Back discard it,
  while Reset changes the draft until Apply. A failed save keeps the editor open for retry.
- Hiding icons keeps destination names visible without losing the saved label choice when icons
  return. Drafts and preview selection survive state restoration.
- Floating navigation overlays the page while measured clearance keeps list ends, scrollbars and
  action controls accessible. Docked navigation retains its bottom-bar placement. Asgard's
  navigation-motion fixes restore animated tab transitions.

### 📱 Combined user and system app lists

[PR #608](https://github.com/trinadhthatakula/Thor/pull/608), addressing
[issue #589](https://github.com/trinadhthatakula/Thor/issues/589) (`ea9b1583`):

- Enable **Settings → Customization → Combined app lists** to select both User and System in
  Apps and Freezer. Single-source selection remains the default.
- Remember independent filters for User, System and combined lists. Disabling the option while
  both sources are selected returns to User and clears hidden bulk selections.
- Apply UAD recommendation filters and badges to system apps; label user app rows **User app**
  instead. Use the person icon consistently for User apps and remove its grid-icon overlay.

## 🔧 Project: dependencies and build tooling

- **[Asgard 2.1.1](https://github.com/trinadhthatakula/Asgard/releases/tag/v2.1.1) —
  [PR #608](https://github.com/trinadhthatakula/Thor/pull/608)** (`b8b2d6cf`): use the
  published navigation-motion fixes and multiselect introduced in
  [Asgard 2.1.0](https://github.com/trinadhthatakula/Asgard/releases/tag/v2.1.0). Its Android
  artifact requires compile SDK 37.2.
- **Android dependencies — [PR #610](https://github.com/trinadhthatakula/Thor/pull/610)**
  (`b61d06c6`): update Gradle to 9.9.0, AGP to 9.6.0-alpha03, Biometric to 1.4.0-beta01,
  Material 3 to 1.5.0-rc01, the Koin compiler plugin to 1.2.3 and Navigation 3 to 1.2.1.
- **CI and website dependencies — [PR #606](https://github.com/trinadhthatakula/Thor/pull/606)
  and [PR #607](https://github.com/trinadhthatakula/Thor/pull/607)** (`7b87e617`, `ee260682`):
  update GitHub Actions, Astro, its MDX integration, Node types and Vitest.
- **Test support — [PR #608](https://github.com/trinadhthatakula/Thor/pull/608)** (`69121bc7`):
  make the navigation preference fake report failed writes and document changed functions.
- **Release preparation:** derive 1.97.4 from version code 1974, add the curated notes and all
  Fastlane locale copies, and retire the oldest curated notes directory while keeping the
  historical Fastlane changelogs. Shizu follows the separate production synchronization.

## 🧪 Validation scope

The 1974 release branch passed `./gradlew test lintFossDebug lintStoreRelease` on Zulu JDK 21:
3,758 unit tests per debug flavor passed, and both lint reports had no errors or warnings. All
13 release-script suites passed before and after retention pruning. Play notes use 466/500
characters; the assembled Telegram caption uses 789/1024 UTF-16 units with the conservative wrapper.

Before the version bump, the dependency update passed both minified release builds and 24 focused
navigation, editor and app-list instrumentation checks on an Android 17 emulator. Those checks
validated the preceding code tree, rather than an installed 1974 build.

## 🛠 Commits Log

Non-merge commits after `v1.97.3-beta-11`, in chronological order:

- `7b87e617` chore(deps): bump the actions group with 3 updates
- `ee260682` chore(deps): bump the web group in /web with 4 updates
- `ea9b1583` feat: customize navigation and add opt-in combined app lists
- `b8b2d6cf` chore: update Asgard to published 2.1.1
- `69121bc7` fix: report fake navigation write failures and document changed functions
- `b61d06c6` chore: update Gradle and dependencies
