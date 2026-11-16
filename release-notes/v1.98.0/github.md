# Thor v1.98.0 Release Notes

Version **1.98.0 (1980)** is a cumulative update covering changes after the published
**v1.97.1 (1971)** through dev commit `ad227aea`. It brings together the 1.97.2–1.97.5
development releases and describes their final behavior after follow-up fixes.

Source comparison:
[`v1.97.1...ad227aea`](https://github.com/trinadhthatakula/Thor/compare/v1.97.1...ad227aea0f4db51c602a9d2058c063fc0eeedc5d).
The production promotion merge for 1.97.1 is not in dev's history, but its tree matches the
shared baseline. The latest published development build is **1.97.5**.

## ✨ Highlights

- 🧭 **Make navigation yours:** preview floating or docked navigation and customize its style,
  icons, labels and shape. Floating icons are the new default.
- 🌍 **Russian language support:** choose Русский in Thor or Android's per-app language settings.
- ⚡ **Bolt for AnyKernel3:** preview and flash supported local kernel ZIPs with root access.
- ❄️ **A better Freezer:** toggle a watchlist from Quick Settings and preserve pauses owned by
  Digital Wellbeing or other apps.
- 📱 **Easier browsing:** opt in to combined user and system app lists, and search or sort backups.
- 🔐 **More control:** optionally extend app lock to the installer and pause log following while
  reading earlier output.

## What's Changed

### 🧭 Navigation customization and defaults

[PR #608](https://github.com/trinadhthatakula/Thor/pull/608) and
[PR #618](https://github.com/trinadhthatakula/Thor/pull/618)
(`ea9b1583`, `b8b2d6cf`, `a1442c7e`):

- Preview docked or floating placement, navigation styles, icons, labels, shapes and spacing in
  **Settings → Customization → Navigation bar**. Apply saves the draft; Cancel and Back discard it.
- When no navigation choices are saved, default to floating icons with labels hidden. Saved
  preferences remain in place, and labels can be enabled in settings.
- Keep list ends and action controls accessible above floating navigation. Docked navigation
  extends its surface into the system navigation area for gesture and three-button modes.

### 🌍 Russian language support

[PR #616](https://github.com/trinadhthatakula/Thor/pull/616) and
[PR #617](https://github.com/trinadhthatakula/Thor/pull/617) (`be0c6895`, `b7dad3bf`):

- Add Russian resources and make Russian selectable in the app and Android's per-app language
  settings. Thanks to [stoxisavon-debug](https://github.com/stoxisavon-debug) for sharing a
  translation in [issue #615](https://github.com/trinadhthatakula/Thor/issues/615).
- Recognize Thor's Russian suspension dialogs during state readback, align the App Ops
  default-mode wording, and add locale and plural coverage.

### ⚡ AnyKernel3 flashing and Bolt

[PR #576](https://github.com/trinadhthatakula/Thor/pull/576), with interface refinements in
[PR #580](https://github.com/trinadhthatakula/Thor/pull/580)
(`10b94ba7`, `bfa52be8`, `c9ec3bec`, `a163c2fa`, `dafb7238`, `661adbf4`):

- Open the built-in kernel flasher from Extensions, or open a local AnyKernel3 ZIP through the
  portable installer. The installer becomes **Bolt** when it recognizes a supported package.
- Preview and confirm the archive and target, follow terminal output, and choose package default,
  current, inactive or both slots when supported. Both-slot requests run the installer twice with
  an additional acknowledgement; failures or unknown results stop the plan.
- Root and a supported BusyBox from the root manager are required. This handles AnyKernel3 ZIPs,
  not generic ROM ZIPs, raw boot images or generic root modules. Reboot remains a separate action.
- Preserve local `content://` and `file://` package routing, and offer a document picker when a
  local file cannot be read through the available access path.

Real kernel flashing and physical A/B acceptance remain unverified. Package recognition does not
establish device compatibility, and an installer exit status does not prove that a device will
boot. See the [kernel flasher validation record](https://github.com/trinadhthatakula/Thor/blob/ad227aea0f4db51c602a9d2058c063fc0eeedc5d/docs/kernel-flasher.md).

### ❄️ Freezer shortcuts and Quick Settings

[PR #568](https://github.com/trinadhthatakula/Thor/pull/568),
[PR #570](https://github.com/trinadhthatakula/Thor/pull/570) and
[PR #597](https://github.com/trinadhthatakula/Thor/pull/597)
(`d69f4a5a`, `135adf5d`, `02d6cdb7`, `d59d69e2`, `51b335e1`, `cd382f6d`):

- Enable Freezer shortcut availability by default while preserving an existing opt-out. Reconcile
  pinned shortcuts when launcher callbacks arrive after shortcuts have been disabled.
- Toggle eligible watchlist apps between Freeze and Unfreeze from Quick Settings. The tile shows
  counts, progress and results, refreshes for watchlist and package changes, and unfreezes frozen
  members first when a watchlist is mixed.
- Thanks to [sigmahfuj](https://github.com/sigmahfuj) for the original unfreeze-count contribution.

### ⏸️ Coexistence with other suspension owners

[PR #599](https://github.com/trinadhthatakula/Thor/pull/599) and
[PR #600](https://github.com/trinadhthatakula/Thor/pull/600)
(`cc645878`, `24eb4bc7`, `d4c7f7f2`):

- Preserve independently owned app pauses while freezing, suspending and restoring apps. Remove
  only verified Thor entries, keep uncertain ownership intact, and report remaining pauses.
- Retain successful operations in recovery tracking even when optional conversion cleanup fails;
  keep Suspend success feedback separate from restoration feedback.
- Availability depends on Android version and privilege mode: Android 9 Suspend is skipped,
  Android 10 cannot overlay an existing external suspension, and Dhizuku Suspend is skipped
  when independent ownership cannot be verified. See the
  [coexistence follow-up](https://github.com/trinadhthatakula/Thor/blob/ad227aea0f4db51c602a9d2058c063fc0eeedc5d/docs/follow-ups/external-suspension-coexistence.md)
  for compatibility and pending device checks.

### 📱 Combined app lists and clearer app types

[PR #608](https://github.com/trinadhthatakula/Thor/pull/608), addressing
[issue #589](https://github.com/trinadhthatakula/Thor/issues/589) (`ea9b1583`):

- Enable **Settings → Customization → Combined app lists** to select both user and system apps
  in Apps and Freezer. Single-source selection remains the default; each view remembers its filters.
- Label user apps clearly and apply debloat recommendation badges and filters to system apps.

### 📂 Backups, installer security and interface

[PR #580](https://github.com/trinadhthatakula/Thor/pull/580) (`661adbf4`, `f83a1084`):

- Search and sort backup archives, refresh after completed backup/export work, reconnect an
  unavailable custom folder, and see clearer deletion and access errors.
- Optionally extend the main app lock to the portable installer and Bolt. The separate preference
  starts off; accepted work can continue independently, and locked inputs survive recreation.
- Pause automatic log following by scrolling back, see new-output indication and jump to the latest
  output. Preserve the paused choice across recreation.
- Improve large-text and system-bar spacing, secure passphrase entry, search controls, tooltips,
  selected menu rows and built-in extension cards.

## 🔧 Project: persistence, tooling and website

- **Room migration — [PR #581](https://github.com/trinadhthatakula/Thor/pull/581)** (`fe85f848`):
  move to Room 3 and SQLite 2.7.1 while preserving database version 10, exported schemas and
  migration SQL. See the [migration record](https://github.com/trinadhthatakula/Thor/blob/ad227aea0f4db51c602a9d2058c063fc0eeedc5d/docs/follow-ups/room3-migration.md).
- **Build and dependencies — [PR #575](https://github.com/trinadhthatakula/Thor/pull/575),
  [PR #590](https://github.com/trinadhthatakula/Thor/pull/590),
  [PR #610](https://github.com/trinadhthatakula/Thor/pull/610)** and later updates
  (`989c9aa1`, `7d271b9c`, `b61d06c6`, `b8b805f3`): update Gradle, Android libraries,
  Asgard, release tooling, CI and website dependencies. Remove tracked IDE configuration files.
- **Website and store — [PR #569](https://github.com/trinadhthatakula/Thor/pull/569),
  [PR #596](https://github.com/trinadhthatakula/Thor/pull/596) and
  [PR #603](https://github.com/trinadhthatakula/Thor/pull/603)** (`85181c36`, `68971af9`,
  `de119981`, `6b5755d8`, `20350b72`): refresh large-screen screenshots, update vulnerable website
  dependencies, and add terms of service and support subscription terms.
- **Release preparation:** derive 1.98.0 from version code 1980 and provide curated notes and
  Fastlane locale copies. Shizu follows the separate production-pinned synchronization procedure.

## 🧪 Validation scope

The 1980 release branch passed `./gradlew test lintFossDebug lintStoreRelease`: the reports show
3,759 passing tests per debug flavor, and both lint reports have zero errors and warnings. The
first run's FOSS test worker crashed inside the Corretto 21 JVM; a single-worker retry using
JetBrains JDK 21 passed, reusing the successful Store test results from the first run.

All 13 release-script suites passed before and after retention pruning. Play notes use 424/500
characters and the assembled Telegram caption uses 849/1024 UTF-16 units with the conservative
wrapper. The existing Shizu changelog drift against production 1.97.1 remains a separate sync.

These results do not establish real kernel flashing, physical A/B behavior or the pending rooted
device and OEM checks for Quick Settings and external suspension coexistence.

## 🛠 Commits Log

All non-merge commits after `v1.97.1` through `ad227aea`, in chronological order:

- `d69f4a5a` fix: enable Freezer shortcuts by default
- `85181c36` chore: add foldable and tablet store screenshots
- `68971af9` chore: refresh foldable and tablet store screenshots
- `135adf5d` fix: honor disabled Freezer shortcuts after late pin callbacks
- `02d6cdb7` fix: serialize late pin state and bound receiver lifetime
- `32bf41d2` chore(deps): bump the web group in /web with 5 updates
- `989c9aa1` chore(deps): bump agp, kotlin, and material3
- `5d758bc6` chore(deps): bump gradle-wrapper from 9.8.0 to 9.8.1 in the maven group
- `10b94ba7` feat: add AnyKernel3 kernel flasher and Bolt installer
- `bfa52be8` fix: restrict portable installer to content URIs
- `c9ec3bec` fix: align installer intent filters with content-only input
- `a163c2fa` fix: restore local file installer routing
- `dafb7238` fix: offer document picker after local installer input failure
- `b657d99e` chore(deps): bump fastlane from 2.240.1 to 2.241.0 in the fastlane group
- `a3b48837` chore(deps): bump the web group in /web with 4 updates
- `103928a8` chore(deps): bump the actions group with 3 updates
- `661adbf4` fix: implement approved audit UX and security improvements
- `fe85f848` chore: migrate persistence to Room 3
- `f83a1084` fix: preserve installer input and logger state across recreation
- `a1ce4fe4` chore(release): prepare v1.97.2
- `83769211` chore(deps): bump the maven group with 3 updates
- `51da31e2` chore(deps): bump vite
- `7d271b9c` chore(deps): bump the maven group across 1 directory with 7 updates
- `03b52a61` chore(deps): bump fastlane
- `fb319d89` chore(deps): bump the web group across 1 directory with 5 updates
- `73a20eae` chore(deps): bump the actions group across 1 directory with 2 updates
- `50c1d6cd` chore(deps): bump undici
- `ad0db1e7` chore(deps): bump the npm_and_yarn group across 1 directory with 2 updates
- `de119981` fix(web): patch sharp librsvg vulnerability
- `d59d69e2` Add unfreezeCount tracking to FreezerTileService
- `51b335e1` fix(freezer): harden Quick Settings toggle and feedback
- `cd382f6d` fix(freezer): refresh tile for live watchlist and package changes
- `f676fbb0` chore(deps): bump the maven group with 4 updates
- `cc645878` fix: preserve external suspensions during freeze and restore
- `24eb4bc7` fix: retain preserved suspensions and successful suspend tracking
- `d4c7f7f2` fix: keep suspend success feedback separate from restoration
- `81850437` chore(deps): bump com.google.devtools.ksp
- `8479ef3c` chore(release): prepare v1.97.3 bug-fix update
- `6b5755d8` feat(web): add terms of service and support subscription terms
- `20350b72` fix(web): keep section headings below the mobile navigation
- `7aa665db` chore(web): promote web updates from dev to master
- `7b87e617` chore(deps): bump the actions group with 3 updates
- `ee260682` chore(deps): bump the web group in /web with 4 updates
- `ea9b1583` feat: customize navigation and add opt-in combined app lists
- `b8b2d6cf` chore: update Asgard to published 2.1.1
- `69121bc7` fix: report fake navigation write failures and document changed functions
- `8afc4bac` docs: describe remaining navigation and preference helpers
- `b61d06c6` chore: update Gradle and dependencies
- `2be7c50c` chore(release): prepare 1.97.4
- `b8b805f3` chore: update Gradle and dependencies, remove IDE configs, and add app type UX options
- `be0c6895` feat(i18n): add Russian language support
- `b7dad3bf` fix(i18n): address Russian locale follow-ups
- `a1442c7e` feat: default to floating icon navigation
- `440dd907` chore(release): prepare 1.97.5
