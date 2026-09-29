# Thor v1.96.3 Release Notes

Version **1.96.3 (1963)** is a hotfix for Shizuku system-app freezing on affected devices,
including Xiaomi builds that refuse disabling a system app for the current Android user.
It restores the removal fallback with a persistent opt-out and improves freeze/restore diagnostics.

These public release notes include all changes since
[v1.96.1](https://github.com/trinadhthatakula/Thor/releases/tag/v1.96.1) through `7994cf40`,
including the App Ops, scrollbar and Japanese-language changes delivered in 1.96.2 development
builds. Since the latest development release,
[v1.96.2-dev-89](https://github.com/trinadhthatakula/Thor/releases/tag/v1.96.2-dev-89),
the range contains the Shizuku hotfix and web/CI dependency updates.

## ✨ Highlights

- 🧊 **Restore affected Shizuku freezes.** When Android refuses disabling a system app,
  Thor can remove it for the current Android user and reinstall it on Unfreeze.
  **System app removal fallback is on by default when no saved choice exists.** A saved off
  choice stays off across updates. Find the option in **Settings → Freezer**.
- ⚠️ **Understand the fallback.** It retains the app's data files, but app-managed accounts
  may be lost. It applies to manual and automatic freezes. Turning the option back on after
  opting out requires confirmation.
- 🛡️ **App Ops in Permission Manager.** Inspect Android operations and change supported
  package or UID modes with Root or Shizuku. Thor reads changes back before confirming them.
- ↕️ **Draggable scrollbars** help navigate long Apps and Freezer lists or grids.
- 🇯🇵 **Japanese support** is available in the app's language selection.

## What's Changed

### 🧊 Shizuku system-app freeze and restore

[PR #521](https://github.com/trinadhthatakula/Thor/pull/521), addressing
[issue #519](https://github.com/trinadhthatakula/Thor/issues/519)
(`04d51a5e`, `d17ac998`, `63f31e43`; merged as `7994cf40`):

- Thor first tries the existing Binder disable and `pm disable-user` paths. Only a
  policy-refused **Shizuku system-app** disable can use `pm uninstall -k --user N`, and only
  while the removal fallback setting is enabled. Ordinary command/transport failures,
  user-installed apps, Root and Dhizuku do not use this fallback.
- A healthy local preference store with no saved choice defaults to enabled, including on
  upgrades. Explicit off choices persist and are checked when queued work executes. The setting
  is device-local and excluded from backups, so restoring other settings cannot override it.
  Unreadable preferences disable the fallback; corruption recovery persists an off choice.
- Unfreeze reinstalls packages removed for the current user. Restoration of legacy removed
  packages remains available even when the fallback is off.
- Queue history preserves distinct disable-refused, disable-failed and restore-failed reasons.
  Diagnostic logs retain both shell output streams. Unrelated root-availability probes no longer
  mark Shizuku tasks as degraded; task warnings follow commands actually executed for that target.
- Shizuku launches `sh` for either server identity, allowing the child process to inherit the
  server UID without an unnecessary nested `su` dependency.

### 🛡️ App Ops in Permission Manager

[PR #511](https://github.com/trinadhthatakula/Thor/pull/511)
(`f06b540d`, `06373e87`, `26f57ac0`, `76f0cba4`, `699fe66a`, `1ef6de96`):

- A separate App Ops tab discovers operation names, controlling switches, linked permissions,
  defaults and reset support from the running Android build. Search and the **Relevant**,
  **Changed** and **All** filters make long catalogs easier to inspect.
- Root or Shizuku can read and change supported package- or UID-scoped modes for the current
  Android user. Thor verifies the requested scope after writes, reports uncertain results and
  offers a separate reset-to-platform-default action. Shared-UID edits warn about other affected apps.
- Runtime-permission-controlled operations stay read-only in App Ops, with a path to Permissions.
  Unknown policy blocks potentially conflicting edits. Parsing handles supported mixed Xiaomi
  operation output, and the editor sheet dismisses when swiped down from its title.

### ↕️ Draggable scrollbars in Apps and Freezer

[PR #510](https://github.com/trinadhthatakula/Thor/pull/510)
(`7df4c174`, `664808ec`, `ebd93039`, `5ae8ab7d`):

- Drag scrollbar thumbs through long lists and grids, with a bubble showing the visible app
  range and accessibility progress controls for navigation.
- The expanded drag target and responsive spacing preserve grid columns and nearby row/card
  taps. Dragging remains stable when the item count changes. Sort-aware snapping is a follow-up.

### 🇯🇵 Japanese localization

[PR #513](https://github.com/trinadhthatakula/Thor/pull/513) (`bfc594cf`):

- Adds Japanese strings across the app, including App Ops, backup, settings and Store features,
  and exposes Japanese in the supported language picker and Android locale configuration.
- Updates locale tests and contribution guidance. The hotfix also supplies its new messages in
  all nine app locales and aligns Japanese wording with the existing Unfreeze terminology.

## 🔧 Project: distribution, dependencies and follow-up tracking

- [PR #512](https://github.com/trinadhthatakula/Thor/pull/512) (`ca46642f`, `29a3138d`)
  prepared 1.96.2 development release notes and added `en-GB` Fastlane metadata for Play's
  primary listing. This release provides the complete 1.96.3 Play/F-Droid changelog in every
  Fastlane locale.
- [PR #494](https://github.com/trinadhthatakula/Thor/pull/494) (`c68810c2`) synchronized
  the Shizu listing with production 1.96.1. Its changelog is synchronized to a new version after
  production promotion; the listing follows the public `/releases/latest/` APK.
- [PR #514](https://github.com/trinadhthatakula/Thor/pull/514) (`6888e96a`) refreshes
  dependencies, removes compiler warnings and records scrollbar design prototypes.
  [PR #499](https://github.com/trinadhthatakula/Thor/pull/499) (`265d3b0e`) updates Fastlane;
  [PR #507](https://github.com/trinadhthatakula/Thor/pull/507) (`c358263f`) updates Coil.
- Web dependency updates are in [PR #500](https://github.com/trinadhthatakula/Thor/pull/500)
  (`45f3eb83`) and [PR #516](https://github.com/trinadhthatakula/Thor/pull/516) (`fe319596`).
  CI action updates are in [PR #501](https://github.com/trinadhthatakula/Thor/pull/501)
  (`69131bcd`) and [PR #517](https://github.com/trinadhthatakula/Thor/pull/517) (`f770cd59`).
- [PR #506](https://github.com/trinadhthatakula/Thor/pull/506) (`0b5360c9`, `a2502252`,
  `3e58535b`) and [PR #508](https://github.com/trinadhthatakula/Thor/pull/508) (`ab3e98d6`,
  `fb214ae2`) reconcile README and follow-up reports with shipped behavior and remaining checks.
  The production-history reconciliation is recorded in `6e2df990`.

## 🧪 Validation scope and remaining device checks

For release preparation, `./gradlew test lintFossDebug lintStoreRelease` passed on
Zulu JDK 21.0.12.1: **3,095 unit tests per flavor**, zero failures, errors or skips,
and no lint warnings or errors. The release-note budgets, 13 shell test files, and
35 release-routing tests also passed. No additional device checks were run for the version bump.

The [issue #519 device record](https://github.com/trinadhthatakula/Thor/blob/7994cf40/docs/issues/519-shizuku-freeze.md)
and PR #521 report these checks for the hotfix:

- **POCO F7, Xiaomi.eu, Android 16/API 36, Shizuku ADB mode (UID 2000):** Android refused
  disabling the authorized Wallpaper Carousel fixture. Legacy restoration with the option off,
  refusal without removal when off, and foreground-queue freeze by removal followed by Unfreeze
  succeeded. The fixture was returned to its original removed state.
- **Fresh API 37.2 emulator, Shizuku ADB mode:** shell identity, disable/enable, gateway and
  foreground-queue round trips passed. Disabling worked on this build; removing a system app
  for a user required root. This is device/ROM-specific behavior, not a blanket Android-version rule.
- The PR records `./gradlew test lintFossDebug lintStoreRelease`, debug/instrumentation builds,
  confirmation UI checks, and preference regressions for the default and persistent opt-out.
  Its reported post-default-change run had 3,093 unit tests per flavor and no test failures or
  errors. Read-failure/corruption opt-out coverage was added in the review follow-up.
- **Limits:** the reporter's exact HyperOS build was not tested. Root-backed Shizuku was not
  live-tested after the shell-launch change. Account retention was not tested: the fixture was
  not an account provider. The checks do not establish a common cause for every reported
  unfreeze failure or compatibility with every vendor restriction.
- App Ops still needs broader acceptance for scoped Shizuku writes, Android 9, secondary/work
  users, shared UIDs and additional OEM formats. Scrollbar sort-aware snapping and remaining
  physical-device gesture checks are tracked separately.

## 🛠 Commits Log

Complete non-merge history in chronological order: **29 commits** from `v1.96.1..7994cf40`.
The release-preparation commit adds version 1963 and these notes.

- `c68810c2` fix(shizu): sync complete changelog to production 1.96.1
- `265d3b0e` chore(deps): bump fastlane from 2.239.0 to 2.240.1 in the fastlane group
- `45f3eb83` chore(deps-dev): bump the web group in /web with 2 updates
- `69131bcd` chore(deps): bump ruby/setup-ruby in the actions group
- `0b5360c9` chore: refresh Thor documentation and AGP version
- `a2502252` docs: distinguish partial Samsung fix from shipped work
- `3e58535b` docs: qualify extension catalog hash verification
- `c358263f` chore(deps): update Coil to 3.6.3
- `ab3e98d6` docs: mark shipped installer and bulk follow-ups
- `fb214ae2` docs: reconcile follow-up index and reports with shipped work
- `7df4c174` feat: add draggable scrollbars to Apps and Freezer
- `664808ec` fix: tighten grid scrollbar spacing without losing columns
- `ebd93039` feat: use responsive scrollbar spacing in lists and grids
- `5ae8ab7d` fix: preserve scrollbar drag when item count changes
- `f06b540d` feat(permissions): add verified App Ops manager
- `06373e87` fix(app-ops): support mixed Xiaomi operation responses
- `26f57ac0` fix(app-ops): explain runtime restrictions and restore editor input
- `76f0cba4` fix(app-ops): filter relevance by manifest permissions
- `699fe66a` fix(app-ops): guard unknown policy and reveal linked permissions
- `1ef6de96` fix(app-ops): make editor title swipe dismiss sheet
- `ca46642f` chore(release): prepare v1.96.2
- `29a3138d` chore(release): add en-GB Fastlane metadata
- `bfc594cf` i18n: add Japanese app localization
- `6888e96a` chore: refresh dependencies, fix warnings, and save scrollbar prototypes
- `fe319596` chore(deps): bump the web group in /web with 5 updates
- `f770cd59` chore(deps): bump the actions group with 2 updates
- `04d51a5e` fix(shizuku): restore consented system-app freeze fallback
- `d17ac998` fix(shizuku): enable removal fallback by default and preserve opt-outs
- `63f31e43` fix(shizuku): preserve removal opt-out on preference read failures
