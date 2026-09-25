# Thor v1.96.2 Release Notes

Version **1.96.2 (1962)** adds App Ops controls to Permission Manager and draggable scrollbars
to Apps and Freezer.

Changes since [v1.96.1](https://github.com/trinadhthatakula/Thor/releases/tag/v1.96.1),
the latest published release tag, through `e2bc5f01`, plus this release's preparation.

## ✨ Highlights

- 🛡️ **Inspect and adjust App Ops.** Search the operations available on your device, see their
  current modes and related permissions, and change supported package or UID modes with Root or Shizuku.
  Thor reads changes back before confirming them and warns when a shared UID can affect other apps.
- ↕️ **Navigate long app lists.** Drag the scrollbar in Apps and Freezer lists or grids. A range
  bubble shows your position, and the expanded drag target keeps nearby rows and cards usable.

## What's Changed

### 🛡️ App Ops in Permission Manager

[PR #511](https://github.com/trinadhthatakula/Thor/pull/511)
(`f06b540d`, `06373e87`, `26f57ac0`, `76f0cba4`, `699fe66a`, `1ef6de96`):

- A separate App Ops tab discovers operation names, controlling switches, linked permissions,
  defaults and reset support from the running Android build. Search and the **Relevant**,
  **Changed** and **All** filters make long catalogs easier to inspect.
- Root or Shizuku can read and change package- or UID-scoped modes for the current Android user.
  Thor checks the requested scope after a write and reports uncertain results instead of claiming
  success. Resetting an operation to its platform default is a separate action.
- Runtime-permission-controlled operations stay read-only in App Ops, with a path to the linked
  Permissions controls. Unknown policy also blocks potentially conflicting edits. Shared-UID
  changes warn that other packages can be affected.
- The editor sheet now dismisses when swiped down from its title. Parsing also handles supported
  mixed Xiaomi operation output, and the feature includes localized strings and regression tests.

### ↕️ Draggable scrollbars in Apps and Freezer

[PR #510](https://github.com/trinadhthatakula/Thor/pull/510)
(`7df4c174`, `664808ec`, `ebd93039`, `5ae8ab7d`):

- Scrollbar thumbs can be dragged through long lists and grids, with a bubble showing the visible
  app range. Accessibility progress controls offer another way to navigate.
- The drag target expands during use. Responsive spacing preserves grid columns and keeps the
  trailing app rows and cards tappable.
- Sort-aware snapping remains a separate follow-up.

## 🔧 Project: stores, dependencies and follow-up tracking

- [PR #494](https://github.com/trinadhthatakula/Thor/pull/494) (`c68810c2`) synchronized the
  Shizu Store listing with **production 1.96.1**. Its `/releases/latest/` URL still serves that
  stable release, so the listing stays on 1.96.1 until 1.96.2 reaches production.
- [PR #499](https://github.com/trinadhthatakula/Thor/pull/499) (`265d3b0e`) updates Fastlane;
  [PR #500](https://github.com/trinadhthatakula/Thor/pull/500) (`45f3eb83`) updates web
  dependencies; [PR #501](https://github.com/trinadhthatakula/Thor/pull/501) (`69131bcd`)
  updates the Ruby setup action; and [PR #507](https://github.com/trinadhthatakula/Thor/pull/507)
  (`c358263f`) updates Coil.
- [PR #506](https://github.com/trinadhthatakula/Thor/pull/506) (`0b5360c9`, `a2502252`,
  `3e58535b`) and [PR #508](https://github.com/trinadhthatakula/Thor/pull/508) (`ab3e98d6`,
  `fb214ae2`) refresh the README and follow-up reports to distinguish shipped work from
  remaining device checks. The 1.96.1 production history was also reconciled (`6e2df990`).
- This release prepares complete Play/F-Droid notes for both Fastlane locales. It retires the
  oldest curated notes directory, `v1.91.2`, to keep the last 20 releases; existing Fastlane
  changelogs are retained.

## 🧪 Validation scope

- `./gradlew test lintFossDebug lintStoreRelease` passed on this branch: **6,168 test executions**
  across modules and flavors, with no failures, errors or skips. Both lint reports have zero errors
  and zero warnings.
- All **13 release-script test suites** passed after retention pruning. Play notes use **464/500
  characters**; the assembled Telegram caption uses **693/1024 UTF-16 units** with the
  conservative wrapper allowance. Both Fastlane locale files match the source notes, and the
  Shizu manifest check passes for production 1.96.1.
- PRs #510 and #511 also report focused emulator and physical-device checks.
- App Ops still needs broader device acceptance for scoped Shizuku writes, Android 9, secondary
  and work-profile users, shared UIDs and more OEM formats. Scrollbar sort-aware snapping and
  remaining physical-device gesture checks are tracked separately. These limits are not changed
  by the release-note preparation.

## 🛠 Commits Log

Complete non-merge history in chronological order: **20 commits** from `v1.96.1..e2bc5f01`.
The release-preparation commit adds the version and notes described above.

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
