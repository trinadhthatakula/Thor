# Thor v1.96.1 Release Notes

**A bug-fix and transition update on the way to the full customization release.**
Version **1.96.1 (1961)** improves Dhizuku operations, Freezer profile management and the first
pieces of multi-app action customization. **1.97.0 (1970)** is planned as the next major update
bringing the full set of customizations.

Changes since [v1.96.0](https://github.com/trinadhthatakula/Thor/releases/tag/v1.96.0),
the latest published release tag, through `4bcaeb41`, plus this release's preparation.

## ✨ Highlights

- ❄️ **Dhizuku freezing keeps the app and its data.** Freeze and unfreeze now use Android's
  device-owner hide/unhide operations, and Thor recognizes hidden apps as frozen.
- 📦 **More reliable Dhizuku actions.** Installation, removal, data clearing and suspension use
  the operations Android supports for a device owner. Unsupported controls explain their limits.
- 🗂️ **Add a selection to existing Freezer profiles.** See which profiles contain an app and choose
  how to recover frozen apps when removing their last membership.
- 🎛️ **Arrange multi-app actions your way.** Reorder or hide actions separately for App list and
  Freezer. These are the first pieces of the broader customization update planned for 1.97.0.
- 💛 **Optional ways to support Thor after a successful action.** App-management features remain
  free.

## What's Changed

### ❄️ Dhizuku freeze, unfreeze and hidden-app recovery

[PR #483](https://github.com/trinadhthatakula/Thor/pull/483)
(`e04e8c77`, `aab7c93f`):

- Dhizuku freezes apps with device-owner hiding and unfreezes them by unhiding, preserving the
  installed APK and app data. Thor no longer treats device-owner access as shell-level authority.
- Hidden apps appear as frozen in app metadata and Freezer state, so they remain available for
  recovery rather than disappearing from Thor's view.
- Root and Shizuku recovery also account for apps hidden by another mode, while keeping legacy
  recovery for system apps removed for the current user by older Thor versions.
- Freeze failures use localized, actionable messages.

### 📦 Supported device-owner operations and safer install completion

[PR #484](https://github.com/trinadhthatakula/Thor/pull/484)
(`92b6f127`, `d5b272d3`):

- Dhizuku uses device-owner operations for clearing app data, suspension, runtime permission
  changes, app removal and APK installation, and reports their actual results.
- Ordinary APK replacement remains supported. Fix Store and Auto Reinstall's Play attribution
  require Root or Shizuku; force-stop and cache clearing are unavailable through Dhizuku.
- Installation controls reflect mode capabilities. Install-time permission grants and the
  low-target-SDK override remain available only on supported Root/Shizuku paths.
- An install already submitted to Android is no longer reported as a submission failure merely
  because closing its session fails afterward.

### 🗂️ Profile assignment, live membership and recovery choices

[PR #487](https://github.com/trinadhthatakula/Thor/pull/487)
(`aac7bd60`, `d747b558`, `cecf0592`):

- **Add to profiles** assigns selected apps from App list or Freezer to existing profiles, with
  an option to also add them to the Freezer list. Assignment itself does not freeze the apps.
- App Info shows live profile memberships. Membership is managed through the profile editor.
- Removing a frozen app's last profile membership, when it is not in the Freezer list, offers an
  explicit recovery choice: leave it frozen or unfreeze it. Changes from elsewhere remain
  reflected while the sheet is open.
- The combined unfreeze action covers the Freezer list and profiles; recovery failures are
  reported without duplicate error messages.

### 🎛️ Multi-app action customization

[PR #488](https://github.com/trinadhthatakula/Thor/pull/488) (`8e76ce3c`):

- Settings → Customization now has separate layouts for App list and Freezer multi-app actions.
- Reorder actions, hide those you do not use, or restore defaults. Each toolbar keeps its own
  saved preferences, and Close remains available.
- Action availability still depends on the selected apps and privilege mode. Saved layouts
  preserve chosen order and incorporate newly introduced actions.

### 💛 Optional support after successful operations

[PR #485](https://github.com/trinadhthatakula/Thor/pull/485)
(`c74b0eff`, `c4b08213`, `d3b25b36`, `cd15b850`):

- Successful operations can offer a **Support Thor** shortcut through completion feedback.
- Invitations respect supporter eligibility, and the automatic introduction is shown only once.
  FOSS users, and Store users whose billing service is unavailable, can choose **I already support
  Thor** to hide invitations on that device.
- Confirmed Freezer additions get clearer success feedback, and completion sheets use consistent
  support buttons. Support remains optional in both distributions.

## 🔧 Project: release metadata, documentation and maintenance

- [PR #486](https://github.com/trinadhthatakula/Thor/pull/486) (`17953b04`) synchronized the Shizu
  Store listing with production 1.96.0. Its `/releases/latest/` download continues to follow the
  latest production APK, so the live manifest is synchronized to 1.96.1 after production promotion.
- This release prepares the complete multiline Play/F-Droid changelog for every Fastlane locale,
  with blank lines preserved between bullets. The same source supplies Shizu's production sync.
  Dev uploads now use the same complete-file, all-locale copy as production; empty curated notes
  and copy failures stop publication. New checks reject truncated English changelogs, preserve
  contributed translations and verify multiline Shizu JSON round trips.
- [PR #478](https://github.com/trinadhthatakula/Thor/pull/478) updates `@types/node` to 26.5.1
  (`950c7d11`); [PR #479](https://github.com/trinadhthatakula/Thor/pull/479) updates KSP to 2.3.12
  (`98f5c0fa`).
- Repository documentation now describes Dhizuku's supported operations and support entry points
  more accurately. Added tests cover operation results, profile membership/recovery, action
  preferences and support invitations.
- Release-note retention remains at 20 versions. The oldest directory, `v1.91.1`, is retired;
  historical Fastlane changelogs are retained.

## 🧪 Validation scope

- `./gradlew test lintFossDebug lintStoreRelease` passed with Zulu JDK 21: **5,936 tests** across
  both flavors, with no failures, errors or skips. Lint reported no errors, warnings,
  `MissingTranslation` or `SyntheticAccessor` findings.
- All **12 release-script test suites** passed after retention pruning, including 35 existing
  release-routing tests and 8 new Fastfile changelog tests. Changed shell scripts pass ShellCheck
  0.11.0; Ruby syntax checks pass. A first-line-only fixture was correctly rejected.
- Play notes use **444/500 characters**; the assembled Telegram caption uses **808/1024 UTF-16
  units** with the conservative wrapper allowance. Both Fastlane locale files match the complete
  source. The Shizu production manifest passes its checks, and isolated 1.96.1 sync plus the pinned
  Fastlane Supply parser preserve every bullet and blank line.

The merged changes include automated coverage and targeted emulator/device reports. This release
preparation does not establish complete Android-version, OEM, work-profile or privilege-mode
coverage. In particular, cross-mode recovery and device-owner behavior still depend on the device's
capabilities. Release-preparation checks are recorded separately from live-device acceptance.

## 🛠 Commits Log

Complete non-merge history in chronological order: **15 commits** from `v1.96.0..4bcaeb41`.

- `950c7d11` chore(deps-dev): bump @types/node in /web in the web group
- `98f5c0fa` chore(deps): bump com.google.devtools.ksp in the maven group
- `e04e8c77` fix: use device-owner hiding for Dhizuku freeze
- `aab7c93f` fix: localize Dhizuku freeze failures
- `92b6f127` fix: use device-owner APIs for Dhizuku actions
- `c74b0eff` feat: add optional support actions after successful operations
- `c4b08213` docs: show support invitation entry points
- `d5b272d3` fix(installer): preserve submission after session cleanup failure
- `d3b25b36` fix(support): announce confirmed freezer additions and polish labels
- `cd15b850` style(support): outline completion sheet support buttons
- `17953b04` fix(shizu): sync changelog to production 1.96.0
- `aac7bd60` feat: assign selected apps to existing freezer profiles
- `d747b558` feat: clarify profile membership and add recovery choices
- `8e76ce3c` feat: customize App list and Freezer multi-app actions
- `cecf0592` fix: keep profile membership live and report recovery errors once
