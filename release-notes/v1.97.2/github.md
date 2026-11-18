# Thor v1.97.2 Release Notes

Version **1.97.2 (1972)** contains the updates after the published **v1.97.1** release,
through merged dev commit `9bc1306e`. This is an incremental release; features already
covered by 1.97.1 are not repeated here.

Source comparison:
[`v1.97.1...9bc1306e`](https://github.com/trinadhthatakula/Thor/compare/v1.97.1...9bc1306e2abbac970c74229ba3370067dbe43c25).

## ✨ Highlights

- ⚡ **Bolt for AnyKernel3** — preview a local kernel ZIP, choose supported slot targets,
  confirm the operation and follow its output with Thor's terminal logger. Root is required.
- 🔐 **Optional installer app lock** — choose whether the main app lock also protects the
  portable installer and Bolt. The separate setting is off by default.
- 📂 **Easier backup browsing and recovery** — search and sort archives, refresh after
  completed backup/export work, reconnect unavailable folders and see clearer deletion errors.
- 📜 **Logs that stay where you read** — scrolling back pauses following; Jump to latest
  returns to new output, and the paused choice survives recreation.
- ❄️ **Freezer shortcuts available by default** — existing opt-outs remain respected,
  including when a launcher reports a pin result late.
- 🎨 **More usable controls** — built-in extension cards, secure passphrase fields,
  tooltips, clearer selection and improved spacing for system bars and larger text.

## What's Changed

### ⚡ AnyKernel3 flashing and Bolt entry points

[PR #576](https://github.com/trinadhthatakula/Thor/pull/576) (`10b94ba7`), with interface
refinements in [PR #580](https://github.com/trinadhthatakula/Thor/pull/580) (`661adbf4`):

- Add a built-in kernel flasher to Extensions. Shared/opened AnyKernel3 ZIPs and Home's
  **Install from File** use the portable installer, which changes its title to **Bolt**
  after recognition. Regular installable packages retain their existing installer flow.
- Require actual root access and a supported BusyBox supplied by the installed root manager.
  The feature accepts AnyKernel3 packages only; it does not install ROM ZIPs, raw boot images
  or generic Magisk/KernelSU/APatch modules, and provides no kernel download catalogue.
- Offer package default, current, inactive and both-slot requests when the device and package
  support the standard A/B contract. **Both** runs the complete installer twice, current then
  inactive, with an additional acknowledgement. A failure or unknown result stops the plan;
  neither rollback nor a next-boot slot change is automatic.
- Validate and stage the selected archive, bind confirmation to its contents and target,
  retain diagnostic output and distinguish interrupted/unknown outcomes from confirmed results.
  Recognizing the package format does not establish device compatibility or trust.
- Use Thor's shared terminal logger, accessible consent rows, navigation-bar spacing and
  **Refresh root access / Exit** controls. Reboot remains a separate user action.
- Present built-in extensions as horizontal icon cards above installed extensions.

Real kernel flashing and physical A/B acceptance remain unverified; an installer exit status
does not prove that a device will boot. See [the feature's scope and validation record](https://github.com/trinadhthatakula/Thor/blob/9bc1306e2abbac970c74229ba3370067dbe43c25/docs/kernel-flasher.md).

### 🔐 Installer lock and input restoration

[PR #580](https://github.com/trinadhthatakula/Thor/pull/580) (`661adbf4`, `f83a1084`):

- Offer **Extend app lock to installer** when enabling the main app lock. The separate
  installer preference starts off and remains adjustable in Security settings.
- When included, authentication precedes input staging and the installer/Bolt window gains
  screenshot and Recents protection. Already accepted operations continue independently.
- Refresh biometric capability when offering the choice, including after returning from enrollment.
- Save the full pending Intent and its new-intent flag across recreation, preserving an input
  received while locked instead of reverting to the original launch file.

### 📂 Backup browsing, refresh and recovery

[PR #580](https://github.com/trinadhthatakula/Thor/pull/580) (`661adbf4`, `f83a1084`):

- Add archive search, date/name sorting and a clear-search action when nothing matches.
- Refresh saved archives from terminal backup/export queue events while retaining legacy job observation.
- Distinguish unavailable storage sources, reconnect a custom folder, and report access,
  read-only, missing-file and unavailable-file deletion failures with suitable recovery actions.
- Keep the no-match message and clear-search button vertically arranged.

### 📜 Terminal reading and interface polish

[PR #580](https://github.com/trinadhthatakula/Thor/pull/580) (`661adbf4`, `f83a1084`):

- Pause automatic following while reading earlier logs, indicate new output and offer
  **Jump to latest**. Preserve the paused setting after recreation.
- Let each screen or dialog own its system insets, and make system bars follow Thor's theme.
- Allow shared bulk-action labels and distribution legends to adapt to available space and font size.
- Use secure passphrase entry, shared Material search controls, icon tooltips, selected
  settings-namespace menu rows and wavy progress for active operations.
- Supply the added interface text across all nine supported locales.

### 📦 Local package routing

[PR #576](https://github.com/trinadhthatakula/Thor/pull/576)
(`bfa52be8`, `c9ec3bec`, `a163c2fa`, `dafb7238`):

- Preserve the final `content://` and `file://` package-opening routes, including extension
  and path-suffix matching for file managers that supply generic MIME types.
- Establish readability through staging. If a local file cannot be read directly or through
  the available privilege path, offer a document picker so the user can grant access.
- Keep unrelated unknown-type text documents out of installer-extension matches.

### ❄️ Freezer launcher shortcuts

[PR #568](https://github.com/trinadhthatakula/Thor/pull/568) (`d69f4a5a`) and
[PR #570](https://github.com/trinadhthatakula/Thor/pull/570) (`135adf5d`, `02d6cdb7`):

- Enable shortcut availability by default and expose **Disable shortcuts** in settings.
  Migrate an explicitly saved opt-out without enabling shortcuts again.
- Reconcile pinned shortcuts with the current preference and handle late launcher callbacks
  after the user disables them. Serialize state updates and bound the receiver's lifetime.

## 🔧 Project: persistence, release tooling and store assets

- **Room 3 migration — [PR #581](https://github.com/trinadhthatakula/Thor/pull/581)**
  (`fe85f848`): move to Room **3.0.3** and SQLite **2.7.1**, with the platform SQLite driver
  and suspend migration APIs. Preserve `thor_database`, schema version **10**, all ten
  exported schemas, the migration SQL and existing data. Remove the debug destructive
  fallback. Test compatibility wrappers remain outside release runtime dependencies;
  WorkManager retains its own Room 2 dependency.
- **Large-screen store screenshots — [PR #569](https://github.com/trinadhthatakula/Thor/pull/569)**
  (`85181c36`, `68971af9`): add and refresh foldable/tablet listing images and their generation tooling.
- **Android build dependencies — [PR #575](https://github.com/trinadhthatakula/Thor/pull/575)**
  (`989c9aa1`, `5d758bc6`): update AGP to **9.5.0-alpha09**, Kotlin to **2.4.21**,
  Material 3 to **1.5.0-beta01** and Gradle to **9.8.1**.
- **Release and CI dependencies — [PR #577](https://github.com/trinadhthatakula/Thor/pull/577)**
  (`b657d99e`) and [PR #579](https://github.com/trinadhthatakula/Thor/pull/579)
  (`103928a8`): update Fastlane to **2.241.0**, Java/Ruby setup actions and CodeQL.
- **Website dependencies — [PR #572](https://github.com/trinadhthatakula/Thor/pull/572)**
  (`32bf41d2`) and [PR #578](https://github.com/trinadhthatakula/Thor/pull/578)
  (`a3b48837`): refresh Astro and the site's supporting build/test dependencies.
- **Release preparation:** bump only `versionCode` to **1972**, deriving **1.97.2**;
  add all three curated notes files and complete Fastlane copies for en-US, en-GB and hi-IN.
  Retire the v1.92.3 curated directory to retain 20 releases; historical Fastlane changelogs
  remain. Shizu follows the separate production-pinned synchronization procedure.

## 🧪 Validation scope

The **1972 release branch** passed `./gradlew test lintFossDebug lintStoreRelease`
with one Gradle worker, parallel execution disabled and a local 6 GiB Kotlin compiler heap.
Both variants passed **3,680 JVM tests**, with zero failures, errors or skips; both lint
gates reported zero errors or warnings. All **13 release-script suites** passed before
and after pruning. Play notes use **438/500 characters**, and the Telegram caption uses
**969/1024 UTF-16 units** with the conservative wrapper. All three Fastlane copies
match the complete Play notes.

PR #580 passed 3,680 JVM tests per variant and both requested lint gates. The independent
Room 3 branch passed 3,673 JVM tests per variant, both lint gates, 108 database/migration
tests and nine WorkManager/service integration tests on the SDK 37.2 AVD. These counts
describe those source revisions, not a new full hardware regression of this release.

The merged `9bc1306e` FOSS debug build was installed and launched on the physical Xiaomi
device. That launch does not establish kernel flashing, every supported SQLite version,
or all of the UI acceptance checks. Remaining checks are recorded in
[the audit follow-up](https://github.com/trinadhthatakula/Thor/blob/9bc1306e2abbac970c74229ba3370067dbe43c25/docs/follow-ups/approved-audit-improvements.md) and
[the Room migration record](https://github.com/trinadhthatakula/Thor/blob/9bc1306e2abbac970c74229ba3370067dbe43c25/docs/follow-ups/room3-migration.md).

## 🛠 Commits Log

Non-merge commits after `v1.97.1`, in chronological order:

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
