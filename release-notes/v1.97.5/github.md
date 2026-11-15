# Thor v1.97.5 Release Notes

Version **1.97.5 (1975)** adds Russian and updates the navigation defaults. These notes cover
changes after the latest published **v1.97.4-dev-162** through dev commit `c6b02456`.

Source comparison:
[`v1.97.4-dev-162...c6b02456`](https://github.com/trinadhthatakula/Thor/compare/v1.97.4-dev-162...c6b02456ca82a22bc9454f91fa1a189879328cdb).

## ✨ Highlights

- 🌍 **Russian language support:** select Русский in Thor or Android's per-app language settings.
- 🧭 **Floating icon navigation by default:** enable labels in settings; previously saved
  navigation choices remain in place.
- 🎨 **More seamless docked navigation:** the bar's background extends into the system navigation area.

## What's Changed

### 🌍 Russian language support

[PR #616](https://github.com/trinadhthatakula/Thor/pull/616) and
[PR #617](https://github.com/trinadhthatakula/Thor/pull/617) (`be0c6895`, `b7dad3bf`):

- Add Russian resources and make Russian available in the app and Android per-app language settings.
  Thanks to [stoxisavon-debug](https://github.com/stoxisavon-debug) for sharing a translation in
  [issue #615](https://github.com/trinadhthatakula/Thor/issues/615).
- Recognize Thor's Russian suspension dialogs when reading back suspended app state, align the
  App Ops default-mode wording, and cover Russian resources and plurals in locale tests.

### 🧭 Navigation defaults and system bar

[PR #618](https://github.com/trinadhthatakula/Thor/pull/618) (`a1442c7e`):

- Default to floating navigation with icons and hidden labels when no navigation choices are
  saved. Existing saved preferences are preserved; labels can be enabled in **Settings →
  Customization → Navigation bar**.
- Extend the docked navigation surface behind the system navigation bar, including gesture and
  three-button navigation, and restore the prior window styling when the bar is hidden.

## 🔧 Project: documentation and build tooling

- **Navigation and preference documentation — [PR #612](https://github.com/trinadhthatakula/Thor/pull/612)**
  (`8afc4bac`): document remaining helpers.
- **Build tooling and project assets** (`b8b805f3`): update Gradle and dependencies, remove tracked
  IDE configuration files, and add app-type UX option mockups.
- **Release preparation:** derive 1.97.5 from version code 1975, add curated notes and Fastlane
  locale copies, and retain the latest 20 curated notes directories. Shizu follows the separate
  production synchronization.

## 🛠 Commits Log

Non-merge commits after `v1.97.4-dev-162`, in chronological order:

- `8afc4bac` docs: describe remaining navigation and preference helpers
- `b8b805f3` chore: update Gradle and dependencies, remove IDE configs, and add app type UX options
- `be0c6895` feat(i18n): add Russian language support
- `b7dad3bf` fix(i18n): address Russian locale follow-ups
- `a1442c7e` feat: default to floating icon navigation
