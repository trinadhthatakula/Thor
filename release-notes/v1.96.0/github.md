# Thor v1.96.0 Release Notes

This cumulative update brings together **v1.95.0 (1950)**, **v1.95.1**, **v1.95.2**,
**v1.95.4**, and all subsequent merged work through `f7bb0001`. It includes the backup,
customization, Freezer, and localization work already collected in the v1.95.0 notes, alongside
component control, the Guardians Queue, safer installation, and the new font presets.

The earlier notes remain available for
[v1.95.0](https://github.com/trinadhthatakula/Thor/blob/f7bb0001/release-notes/v1.95.0/github.md),
[v1.95.1](https://github.com/trinadhthatakula/Thor/blob/f7bb0001/release-notes/v1.95.1/github.md),
[v1.95.2](https://github.com/trinadhthatakula/Thor/blob/f7bb0001/release-notes/v1.95.2/github.md), and
[v1.95.4](https://github.com/trinadhthatakula/Thor/blob/f7bb0001/release-notes/v1.95.4/github.md).
The descriptions below reflect the final implementation, including changes that supersede those
earlier releases.

## ✨ Highlights

- ⏳ **Guardians Queue** — follow background backups, restores, exports, prepared sharing, and
  supported bulk actions; reopen progress, logs, and retained results.
- 💾 **Encrypted backups and the Backup & Restore Hub** — find, create, inspect, share, and restore
  archives. Private app-data backup requires root; Shizuku can back up APKs and shared storage.
- 🧩 **Per-component control** — open activities, stop services, and manage individual components,
  with privilege-aware controls and a record of changes Thor can restore.
- 🎨 **Make Thor your own** — reorder or hide App Info actions, use app-icon shortcuts, and choose
  **Asgard** or **System** fonts throughout the app and external installer.
- ❄️ **Safer Freezer and bulk actions** — explicit tracking consent, background Suspend/Unsuspend,
  clearer progress, guarded watchlist writes, and sequential Android uninstall confirmations.
- 📦 **More reliable installation** — repaired privileged installer paths, XAPK/OBB support,
  consent for eligible older-target APKs, and opt-in runtime-permission grants.
- 🔍 **Better app visibility and readable settings** — fresh-install/OEM recovery, privilege
  refresh, adaptive settings, CSV export, and eight app languages.

**Backup compatibility:** app-data backups made by **v1.95.2 or earlier** use the older schema-1
`.thorbak` format and **cannot be restored in v1.96.0**. Make fresh backups after updating while
the original app data is still available. Existing archives are not automatically converted.
Keep your backup passphrase: it cannot be recovered. Ordinary APK/APKS/XAPK installation is
unaffected by this archive-format change.

## What's Changed

### 💾 Encrypted backups and the Backup & Restore Hub — [#379](https://github.com/trinadhthatakula/Thor/pull/379), [#381](https://github.com/trinadhthatakula/Thor/pull/381), [#385](https://github.com/trinadhthatakula/Thor/pull/385), [#389](https://github.com/trinadhthatakula/Thor/pull/389), [#412](https://github.com/trinadhthatakula/Thor/pull/412), [#413](https://github.com/trinadhthatakula/Thor/pull/413)

- The Home **Backup & Restore Hub** scans `Downloads/Thor`, MediaStore, and the configured SAF
  directory for `.thorbak`, `.xapk`, `.apks`, and `.apk` files without requiring root to browse.
  Search and filter archives, inspect their metadata, share them, or delete them with confirmation.
- Start a backup from the Hub's app picker. Opening a `.thorbak` through **Install from file** or
  a supported external file-manager intent routes to the restore sheet.
- Offline `.thorbak` backups use AES-256-GCM encryption, a per-archive salt, and
  PBKDF2WithHmacSHA256 passphrase derivation. Optional passphrase caching is protected by
  AndroidKeyStore.
- Root backups can include private credential-encrypted and device-encrypted data, APKs, and shared
  storage. Non-root Shizuku cannot read private app data; its scope and size estimate cover the APK
  bundle and accessible shared storage. Staging checks the volume that will actually hold the data.
- Backup/restore and single-app export now use the foreground-service data queue described below;
  the WorkManager execution descriptions in the earlier cumulative notes are historical.

Carried-forward implementation: `940480ef`, `299fc471`, `fa093e69`, `ab96071d`, `db3d78c7`,
`37c4df9b`, `7361f0e4`.

### 🛡️ Authenticated restores and archive safety — [#420](https://github.com/trinadhthatakula/Thor/pull/420), [#453](https://github.com/trinadhthatakula/Thor/pull/453), [#461](https://github.com/trinadhthatakula/Thor/pull/461)

- Schema-v2 archives authenticate the manifest and member bytes, binding encrypted data to its
  expected class and member/chunk identity. Unauthenticated restores are refused.
- Staged APK sets are checked for the expected package, version, exact signer, and install-set
  consistency before installation. Automatic rollback is limited to a still-matching newly
  installed copy that Thor recorded; unknown or changed identities are not automatically deleted.
- Cancellation cleans up staged copies, and orphan cleanup covers both internal and external
  cache staging roots. Reads from untrusted archives are bounded.
- Backup Hub icons avoid copying an entire `.thorbak` just to draw its row. Other staged icon reads
  are capped, decoding is sampled, and misses are cached. Refused archive deletions show an error.

Implementation: `d5d4c1e0`, `5406c046`, `a86409aa`, `4896c5b2`, `66bc3a32`, `e3b99cd5`,
`26fe6af0`, `ce2b9fa0`, `13d50cc7`, `7969ea37`, `385b9bf5`.

### ⏳ Guardians Queue and background work — [#453](https://github.com/trinadhthatakula/Thor/pull/453), [#461](https://github.com/trinadhthatakula/Thor/pull/461), [#465](https://github.com/trinadhthatakula/Thor/pull/465)

Accepted work is recorded in Room before a foreground service is awakened. Data operations and
privilege sweeps have separate queues, each processing its work serially. Supported paths include
archive backup/restore, single-app export, bulk-share preparation, freeze/unfreeze, per-app cache
clearing, eligible Fix Store/reinstall operations, and bulk suspend/unsuspend.

- **Running**, **Queued**, and **Recent** sections show tasks and their selected privilege mode.
  The Root/Shizuku/Dhizuku identity names the mode selected for that task; it does not assert that
  the provider is currently available.
- Reopen localized logs, per-app outcomes, counters, cancellation state, and recovery explanations.
  **Run in background**, Back, and dismissing progress leave accepted work running. Cancellation
  is explicit and shows **Stopping** while the request is being stopped.
- Task details preserve the originating screen behind a dialog. Status, app counts, and queue type
  wrap separately. Recent results from both queues are ordered by newest completion first; pending
  work retains FIFO ordering.
- Service-start failures, interrupted work, and uncertain outcomes stay visible. Durable task
  identities prevent stale callbacks from claiming another request's progress or result.

This is bounded task history. Request persistence does not guarantee completion after every reboot,
force-stop, lost authorization, or provider failure. Single-app quick sharing and multi-app
Backup/export remain direct paths outside this queue migration.

Implementation: `6462fe90`, `77b93451`, `72fa4cf7`, `0fc62497`, `81e7e73f`, `baac6038`,
`cc67ad66`, `45bf8b24`, `225fce86`, `9ed5a4d8`, `a629b0ae`.

### 📤 XAPK/OBB, export recovery, and prepared sharing — [#376](https://github.com/trinadhthatakula/Thor/pull/376), [#378](https://github.com/trinadhthatakula/Thor/pull/378), [#389](https://github.com/trinadhthatakula/Thor/pull/389), [#453](https://github.com/trinadhthatakula/Thor/pull/453), [#461](https://github.com/trinadhthatakula/Thor/pull/461)

- Install and export XAPK game/split bundles with OBB detection and staging for expansion assets.
- Single-app exports report background progress. Recovery reconciles recorded MediaStore/SAF
  output identities before publishing again; cancellation and cleanup belong to the owning task.
- Bulk sharing prepares private files in the data queue. Reopening **Share** checks task ownership,
  expiry, readability, size, and provider URIs before granting read-only access. Prepared files are
  available for 24 hours with best-effort expiry cleanup. Automatic format selection uses APK for
  monolithic apps and APKS for split apps.
- Reopening Export after completion resets stale **Exported** presentation; reopening during an
  active export reconnects to that task without submitting a duplicate.

Provider-specific power-loss durability and duplicate-free recovery are not guaranteed.
Implementation: `91100e58`, `0fd72541`, `193d893e`, `8c49785f`, `b75964fc`, `8efa11a7`,
`ba9f8281`, `fd8b97c9`, `1f6e8a7a`.

### 🧩 Per-component control — [#435](https://github.com/trinadhthatakula/Thor/pull/435), [#436](https://github.com/trinadhthatakula/Thor/pull/436), [#439](https://github.com/trinadhthatakula/Thor/pull/439), [#440](https://github.com/trinadhthatakula/Thor/pull/440), [#442](https://github.com/trinadhthatakula/Thor/pull/442)

**App Info → Components** supports Open/Force open, Stop now for services, Disable/Enable/Reset to
default, copying class names, and badges such as **Restricted by Thor**, **Changed elsewhere**,
**Not exported**, and **Off by default**. Disabling asks for consent for the current session.

Ordinary exported, unguarded activities can open without privilege. Component changes and forced
launches require effective uid 0: **Root or Shizuku started as root**. Shell-mode Shizuku and Dhizuku
show why those controls are unavailable. Commands quote component names safely, and service/launch
verdicts account for output and transport failures instead of trusting an exit code alone.

Thor records components it disabled. **Restore all** spans apps for the **current Android user**
and restores recorded defaults, including components that originally shipped disabled. Only
components recorded by Thor are included. Partial failures remain retryable and report how many
entries were restored. This ledger does not reapply restrictions at boot.

Implementation: `3104018e`, `cf19a134`, `e7090e42`, `6ed95a38`, `73c81259`, `6517660d`.

### 🎨 App Info actions, icon gestures, and fonts — [#410](https://github.com/trinadhthatakula/Thor/pull/410), [#418](https://github.com/trinadhthatakula/Thor/pull/418), [#420](https://github.com/trinadhthatakula/Thor/pull/420), [#421](https://github.com/trinadhthatakula/Thor/pull/421), [#473](https://github.com/trinadhthatakula/Thor/pull/473)

- Reorder App Info quick actions by dragging, hide actions you do not use, and reset to defaults.
  The preview stays above the scrolling list. Drag ownership, accessibility, and preference resync
  handling are hardened.
- Tap an app icon in App Info to open the app; long-press for its system settings. These use the
  existing actions, including their thaw behavior for frozen/suspended apps.
- **Settings → Customization → Fonts** offers **Asgard** and **System** presets, applies the choice
  immediately, and saves it for the next launch. Asgard remains the default, using Outfit and Fira
  Code; System uses the device's UI font. Logs and package identifiers remain monospaced.
- The external installer follows the same saved font choice and shows accessible loading feedback
  while preferences are being read. Both presets preserve text sizes/weights and respect system
  text scaling.

Implementation: `75a78988`, `bfa67935`, `43630a15`, `60f341df`, `679c19fa`, `683b9b9f`,
`12f215ce`, `6b4e562a`, `ec5e010f`.

### ❄️ Freezer profiles, tracking consent, and reliable bookkeeping — [#370](https://github.com/trinadhthatakula/Thor/pull/370), [#415](https://github.com/trinadhthatakula/Thor/pull/415), [#437](https://github.com/trinadhthatakula/Thor/pull/437), [#438](https://github.com/trinadhthatakula/Thor/pull/438), [#465](https://github.com/trinadhthatakula/Thor/pull/465)

- Labels distinguish **Unfreeze & Remove** in Freezer from **Remove from Watchlist** in Apps.
  Uninstalled packages are pruned from tracking; profile saves are transactional, and profile
  members support group kill/suspend actions.
- Bulk Freeze asks for confirmation with **Add to Freezer** checked. Unchecking freezes without
  adding membership. The choice survives rotation and travels with the queued request; only
  successfully frozen targets gain membership. Existing memberships and profile associations are
  preserved. Cancel submits nothing.
- Bulk Suspend/Unsuspend run in the background with distinct Queue/notification labels and
  suspension readback. Room schema 10 stores the tracking choice, with an opt-out default for
  older tasks.
- Failed watchlist or shortcut writes are reported instead of crashing the process. When the
  privileged action succeeded but bookkeeping failed, the UI explains both outcomes. Launcher
  shortcuts are disabled before membership is removed so failures remain retryable.

Implementation: `0b142916`, `800b41e3`, `d937b229`, `e0ed3f76`, `b8acbf0a`, `9ed5a4d8`,
`a629b0ae`.

### 📦 Installer consent, uninstall prompts, and Fix Store — [#434](https://github.com/trinadhthatakula/Thor/pull/434), [#446](https://github.com/trinadhthatakula/Thor/pull/446), [#451](https://github.com/trinadhthatakula/Thor/pull/451), [#464](https://github.com/trinadhthatakula/Thor/pull/464), [#466](https://github.com/trinadhthatakula/Thor/pull/466), [#469](https://github.com/trinadhthatakula/Thor/pull/469)

- Shizuku/Dhizuku installation uses working streaming sessions and correctly wrapped session
  transports, addressing the unintended fallback to Android's confirmation dialog. Dhizuku uses
  its own installer transport.
- Eligible **Root/Shizuku** installs can override Android's low-target-SDK block after explicit
  consent. **Allow legacy APK installs without asking** is off by default. One-time consent does
  not enable it, and stale callbacks, new selections, or unreadable preferences do not authorize
  another install.
- This override covers low-target blocking only. It does not bypass signature, ABI, split-set,
  downgrade, or OEM restrictions, and does not promise an old app will run. Normal, Dhizuku,
  external, and background restore/reinstall paths do not inherit this interactive consent.
- Runtime-permission grants are opt-in, separately from legacy APK consent, and the per-install
  choice resets for each newly parsed package. Shizuku/Dhizuku broker authorization still comes
  from the broker; it is excluded from Thor's self-grant path.
- When privileged batch uninstall fails, Android asks for confirmation per app. Cancelling one
  dialog advances to the next app; **Stop** leaves the remaining apps untouched.
- **Fix Store requires Root or Shizuku.** Home, individual-app, and batch requests share capability
  routing and wait for privilege initialization. Normal/Dhizuku requests explain that no apps
  were changed, and Dhizuku rejects the unsupported operation below the UI as well.

Implementation: `0bd1369e`, `9e8611d2`, `4fc55d3f`, `5c0f63ff`, `446c7fbb`, `634da1ba`,
`bd2d8c8a`, `f3da333d`, `ae74c03e`, `c2965493`.

### 🔍 App visibility, privilege detection, and CSV export — [#371](https://github.com/trinadhthatakula/Thor/pull/371), [#417](https://github.com/trinadhthatakula/Thor/pull/417), [#419](https://github.com/trinadhthatakula/Thor/pull/419), [#420](https://github.com/trinadhthatakula/Thor/pull/420)

- Fresh installs and affected Chinese OEM ROMs recover app lists that previously showed only
  Thor, using fallback package queries and synchronized visibility/AppOps grants. OEM AppOps work
  proceeds even when the vendor permission's `pm grant` call fails; revoking closes the AppOp too.
- Known privilege managers are detected by package name. Shizuku and Dhizuku have in-app grant
  requests; Shizuku listeners pick up grants dynamically, while **Refresh** re-probes Root and
  Dhizuku and invalidates stale non-root shell state.
- Root acquisition compatibility improves for KernelSU/APatch. App List sort/filter touch
  conflicts are corrected, and sort/filter/search changes return the list or grid to the top.
- Export the full or filtered/searched app list as CSV, with RFC 4180 escaping and protection
  against spreadsheet formula injection.

Implementation: `cdbe0d3e`, `1d2bf9c6`, `1f70dfb8`, `b6044de4`, `560ba578`, `e2d71218`,
`ce4048d6`, `8020e7f7`, `1489c9b4`, `f31f52f9`.

### 🌍 Settings, languages, and readable feedback — [#369](https://github.com/trinadhthatakula/Thor/pull/369), [#383](https://github.com/trinadhthatakula/Thor/pull/383), [#395](https://github.com/trinadhthatakula/Thor/pull/395), [#397](https://github.com/trinadhthatakula/Thor/pull/397), [#398](https://github.com/trinadhthatakula/Thor/pull/398), [#400](https://github.com/trinadhthatakula/Thor/pull/400), [#420](https://github.com/trinadhthatakula/Thor/pull/420), [#434](https://github.com/trinadhthatakula/Thor/pull/434), [#438](https://github.com/trinadhthatakula/Thor/pull/438), [#439](https://github.com/trinadhthatakula/Thor/pull/439), [#464](https://github.com/trinadhthatakula/Thor/pull/464)

- Settings are categorized with adaptive multi-pane navigation. Freezer switches, legacy-install
  options, and biometric-lock explanations wrap at large text sizes without clipping their meaning.
  Biometric copy explains the existing launch lock, screenshot/recording protection, and hidden
  Recents preview.
- Eight app languages: English, Arabic, Spanish, French, Polish, European Portuguese, Brazilian
  Portuguese, and Simplified Chinese. Translation coverage and proofreading include the Backup Hub,
  component controls, queue actions, and new settings. The language picker scrolls on short screens.
- Backup dates follow the in-app language. Preference writes are guarded against storage failures.
  Opening Permissions from App Info dismisses the old sheet instead of making navigation flicker.
- Nested error text resolves into readable messages, and action-success toasts use localized action
  names instead of obfuscated class names.

Implementation: `e210768f`, `994853b0`, `1d2d692f`, `a7b6da8d`, `7829c618`, `db45cb6c`,
`583198af`, `ba581061`, `feaa6bd0`, `dae9f613`, `ab029f94`.

## 🧰 Project: execution, stores, website, and build

- **Coordinated root execution** — interactive, archive, and sweep lanes coordinate mutations per
  package. Dedicated archive/sweep shells can fall back to serialized MainShell execution with
  degraded operation recorded. Cancellation and timeout are distinguished, cancelled interactive
  commands are drained before reuse, and root probes are serialized. The sweep shell requests the
  global mount namespace for cache access; this is distinct from interactive root acquisition
  ([#453](https://github.com/trinadhthatakula/Thor/pull/453),
  [#461](https://github.com/trinadhthatakula/Thor/pull/461); `db8184f7`, `2c1330a8`, `3d7f9c63`,
  `41830645`, `591c96ec`, `37831981`).
- **Tests, migrations, and documentation** — component-ledger and task-schema migrations;
  ownership, cancellation, publication, recovery, Queue layout, and localization coverage;
  component/worker design and acceptance records. Community follow-ups distinguish implemented
  work from product decisions and remaining device checks.
- **SyntheticAccessor enforcement** — the earlier app cleanup and corrected reports are followed
  by removal of remaining avoidable accessors and an explicit lint-error gate
  ([#441](https://github.com/trinadhthatakula/Thor/pull/441),
  [#443](https://github.com/trinadhthatakula/Thor/pull/443),
  [#474](https://github.com/trinadhthatakula/Thor/pull/474); `de4119e3`, `dfd1a8b0`, `6748bb8f`).
- **Shizu Store and web** — live-schema/profile corrections, the production changelog sync,
  translated listings, downloads/landing-page work, IndexNow, SEO/robots/LLM documentation, and
  web-layer convergence from master to dev
  ([#422](https://github.com/trinadhthatakula/Thor/pull/422),
  [#428](https://github.com/trinadhthatakula/Thor/pull/428),
  [#433](https://github.com/trinadhthatakula/Thor/pull/433),
  [#463](https://github.com/trinadhthatakula/Thor/pull/463)).
- **Runtime compatibility and build maintenance** — Kotlin 2.4.20, Room 2.8.5, Compose BOM
  2026.09.00, Navigation 3 1.1.7, and Koin compiler plugin 1.2.1. Asgard 2.0.1 is paired with
  Material 3 1.5.0-alpha28, superseding the earlier alpha26 compatibility pin. Kotlin compiler
  heap is 4 GiB after release-build exhaustion; stdlib ordering/hex helpers and explicit installer
  event storage simplify internals ([#468](https://github.com/trinadhthatakula/Thor/pull/468);
  `df4fff56`, `825408ee`, `e5b61192`, `a691b773`).
- **Dependency and release tooling upkeep** — Gradle wrapper, Android libraries, Fastlane/Ruby,
  web packages, CodeQL, and setup-java updates across the cycle. The latest grouped updates include
  Fastlane 2.239.0, MDX 8.0.1, Vitest 5.0.0, CodeQL 4.38.0, and setup-java 6.0.1
  ([#470](https://github.com/trinadhthatakula/Thor/pull/470),
  [#471](https://github.com/trinadhthatakula/Thor/pull/471),
  [#472](https://github.com/trinadhthatakula/Thor/pull/472)). The commit log includes the earlier
  grouped dependency, workflow, and security maintenance as well.
- **Release cleanup** — update AGP from 9.5.0-alpha04 to **9.5.0-alpha05** and ignore the local
  `.omo` directory ([#475](https://github.com/trinadhthatakula/Thor/pull/475)).
- **Release bookkeeping** — version code **1960** derives to **1.96.0**. Code 1953 was consumed by
  the Play special-use foreground-service access submission, so there is no separate 1.95.3 notes
  set to consolidate. Play/F-Droid notes cover every Fastlane locale. The oldest retained notes,
  v1.90.4, are retired to keep 20 release directories; historical Fastlane changelogs remain.
  The Shizu listing continues to describe the latest production APK and is synchronized after
  production promotion, as required by the release guide.

## 🧪 Verification and remaining acceptance

Release-preparation host gates passed on **Zulu JDK 21** with **AGP 9.5.0-alpha05**, using one
worker and no parallel task execution:

```sh
./gradlew test lintFossDebug lintStoreRelease --no-parallel --max-workers=1 --continue
```

- FOSS Debug and Store Debug each passed **2,798 tests**, with **zero failures, errors, or skips**.
- Both app lint reports contain **zero errors or warnings**, no `MissingTranslation` or
  `SyntheticAccessor` findings, and nine Hint findings each. Explicit `:bypass:lintDebug` and
  `:bypass:lintRelease` checks also passed with **zero issues**.
- FOSS debug APK assembly is verified separately from the test/lint invocation.
- Release budgets pass: Play **442/500 characters**, Telegram **971/1024 assembled UTF-16 units**.
  The English Fastlane copy matches the source byte-for-byte, and the Hindi changelog is
  **443 characters**. Every configured store locale has notes for code 1960.
- The release-script suite passed both before and after retention pruning (**10 test files,
  zero failures**). The production-pinned Shizu manifest check, commit/PR references, and
  whitespace checks pass. Twenty release directories remain, with no references to the retired
  v1.90.4 directory outside historical notes.

Historical test counts remain in their original release notes. Device/emulator evidence is
recorded separately from host tests: the service-queue acceptance record still marks **T19
deferred and T22 incomplete**. Earlier maintainer reports cover some physical-device Root installs,
settings, and basic bulk-action/Queue behavior, but do not establish the full device/ROM/privilege
matrix. Archive/provider edge cases, legacy-install OEM/mode coverage, and relevant Store-policy
checks remain documented follow-ups.

The emulator profiling added in `22949711` has no matched pre-update baseline, so it does not
establish a performance improvement from the dependency upgrade. Font previews come from JVM UI
tests and do not establish how every OEM system font renders.

## 🛠 Commits Log

Complete non-merge history after the 1950 release: **237 commits**, in chronological
order, from `v1.95.0-dev-40..f7bb0001`. The v1.95.0 notes already include the
v1.94.x development cycle; those carried-forward features and their implementation hashes appear
above. This log also includes tests, documentation, release preparation, and intermediate changes
that were revised before merge; the final behavior is described in the themed sections.

In particular, the intermediate legacy Fix Store fallback was removed by `c2965493`, and
earlier WorkManager stages were superseded by the supported foreground-service queues.

- `496fa740` docs(web): stop telling users Dhizuku destroys system-app data
- `56294b15` docs(web): name the user argument in the fallback command
- `5e4618f4` docs(web): the API 37 refusal is uid-0-only, not shell-only
- `560f9617` feat(web): sync web landing page, downloads hero, themes, SEO & GEO to master for production deployment
- `b5ddd4c4` fix(deps): resolve Dependabot security alerts for nanoid and ruby json
- `d086c11a` feat(web): sync web layer, enhance llms.txt and robots.txt
- `ab4198c7` feat(web): add IndexNow protocol support and automated submission
- `ca6f6c42` fix(web): add 10s timeout to IndexNow and verify staged deployment directory
- `1d2bf9c6` fix(apps): restore package visibility and app list fetching on fresh installs
- `b6044de4` fix(permissions): synchronize SelfPermissionGranter and privilege events with AppRepository rescans
- `1f70dfb8` fix(permissions): grant Chinese OEM AppOps and fallback package flags on visibility collapse
- `e2d71218` fix(privileges): invalidate non-root Odin shell cache on refresh and add in-app Dhizuku grant support
- `ff6851a6` feat(privilege): add PrivilegeManagerApp registry and dynamically show installed managers
- `560ba578` fix(privileges): add in-app Shizuku grant request and remove FLAG_MOUNT_MASTER to fix KernelSU/APatch root acquisition
- `ccd2aba3` fix(home): reactively trigger data reload and scan bump on privilege state changes
- `01323101` fix(activity): re-probe privileges on activity onResume to auto-acquire root grants
- `1489c9b4` fix(app-list): resolve sort and filter touch conflicts in bottom sheet
- `f31f52f9` fix(app-list): scroll list and grid to top on sort, filter, or search change
- `b83fb664` fix(review): address CodeRabbit review feedback on gateways, cache sync, privilege registry, and freezer scroll
- `8ffb5ee4` fix(review): address self-review findings on PR #417
- `43630a15` fix(customization): pin the action preview above the scrolling list
- `60f341df` fix(customization): rework drag-and-drop, a11y and header layout
- `ce4048d6` fix(visibility): stop gating GET_INSTALLED_APPS app-ops on the pm grant exit code
- `070fc5c7` fix(review): close the scan-revision window opened by drop(1)
- `7829c618` fix(i18n): make the language picker sheet scrollable
- `d5d4c1e0` fix(backup): stop stranding staged copies in /data/local/tmp on cancellation
- `8020e7f7` fix(visibility): close the GET_INSTALLED_APPS app-op on revoke
- `5406c046` fix(backup): sweep the staging directory under both roots
- `dae9f613` fix(app-info): dismiss the sheet when opening the permissions screen
- `a86409aa` perf(backup): stop copying whole archives to draw a 44 dp icon
- `41779d87` fix(backup): report a delete the volume refused
- `679c19fa` fix(customization): three ways a reorder drag came apart
- `db45cb6c` i18n(pt-BR): Brazilian wording for the Backup Hub strings
- `1005dbc2` test(scan): make the cache-policy tests able to fail
- `77f9386c` fix(web,tools): three quiet wrong answers
- `2fb42af4` fix(visibility): report the gate that actually opened, not just for self
- `683b9b9f` fix(reorder): key each drag delta to the gesture that owns it
- `4896c5b2` fix(analyzer,icons): bound the two copies that had no ceiling
- `947aad47` docs(i18n,web,tools): three wordings that said the wrong thing
- `66bc3a32` fix(restore): bound the third copy out of an untrusted archive
- `0d35576f` fix(icons): make the pixel ceiling hold for a one-pixel axis
- `32ca102c` fix(visibility,restore,tools): three logs that misreport what happened
- `02e5363c` fix(icons,visibility): correct two claims the verification pass refuted
- `0bf9b212` fix(restore,reorder): three regressions this branch introduced
- `e3b99cd5` fix(backup): measure the staging volume the tar will actually use
- `12f215ce` feat(app-info): tap the icon to open an app, long-press for its settings
- `e34915d3` fix(app-info): the glow ended in a straight line, not a fade
- `e6e35873` fix(glow): derive the header glow's margins instead of hand-tuning them
- `b6991044` fix(store): re-vendor the live Shizu schema and rename ad -> has_ads
- `dc997e65` fix(store): restore Shizu listing validity and fill the developer profile
- `db51f0e4` feat(store): fill the Shizu developer profile and the app website slot
- `aef0f20d` chore(release): bump version to 1951 (v1.95.1) and add release notes
- `71b33dd9` chore(deps): bump the web group in /web with 3 updates
- `91f5f784` chore(deps): bump github/codeql-action in the actions group
- `0541d937` chore(deps): bump gradle-wrapper from 9.7.0 to 9.7.1 in the maven group
- `61ab1ec6` chore(shizu): sync store changelog to v1.95.1
- `0bd1369e` fix(installer): make the Shizuku and Dhizuku silent-install rungs actually work
- `583198af` fix(backup): format the archive date in the app locale
- `4d038c1a` docs(installer): correct two overstated claims and one overstated test
- `535e7c9f` docs(installer): say what actually exempts the shell from Android/data
- `f8408b48` docs(components): design spec for per-component control in App Info
- `3104018e` feat(components): open, force-open, stop and disable individual components
- `cf19a134` fix(components): read stopservice's verdict from stderr, not its exit code
- `06781eac` fix(components): rank failure markers by usefulness, not by line order
- `e7090e42` fix(components): a dead transport is not a stopped service
- `6ed95a38` fix(components): answer the three review findings on the disable path
- `037aaa3c` fix(components): the session-consent box says whether it is ticked
- `60cc4daf` fix(components): give the consent row back the 48dp the null handler gave up
- `e0ed3f76` fix(freezer): a watchlist write that fails must not take the process
- `b8acbf0a` fix(freezer): the review follow-ups PR #437 left behind
- `ab029f94` fix(uitext): make a leaked UiText legible and kill the obfuscated toast
- `84422d9d` docs(components): document per-component control, and correct the spec's scope
- `73c81259` docs(components): Restore all is cross-app, not device-wide
- `8b6382b0` docs(components): say which rows a partial Restore all leaves behind
- `de4119e3` chore(lint): pin SyntheticAccessor in :app, and correct what it is worth
- `6517660d` fix(strings): a partial Restore All must not claim the rest are off
- `dfd1a8b0` docs(follow-ups): exact lint id, and every source set the :app fix touched
- `da6ce9c9` chore(release): 1952 (v1.95.2) — per-component control, and a 20-release cap on this directory
- `7b4419c1` chore(release): translate the Hindi changelog, and time the pruning check right
- `5349aaeb` docs(release-notes): a placeholder nobody can run is worse than an example
- `9e8611d2` fix(installer): make the install-time permission grant opt-in
- `4fc55d3f` feat(installer): let the user answer the permission grant per install
- `35c21e2a` i18n(settings): say which installs the permission grant reaches
- `5c0f63ff` fix(installer): clear the per-install grant answer when a new package is parsed
- `092050c9` docs: record worker shell lane design
- `6b150603` fix(freezer): terminate bulk progress on cancellation
- `7333706d` test(freezer): assert outer cancellation job
- `7425be97` chore(deps): bump the maven group with 3 updates
- `bf6b12b5` feat(privilege): define execution lane contracts
- `db8184f7` feat(privilege): coordinate package mutations
- `4855a78b` fix(privilege): make package lease handoff atomic
- `2c1330a8` feat(root): own isolated archive and sweep shells
- `45c278bd` fix(root): harden isolated Odin shell ownership
- `53960f49` fix(root): distinguish cancellation from command timeout
- `3d7f9c63` feat(root): route commands across three lanes
- `41b195b2` fix(root): make MainShell submission atomic
- `49d7c8f2` fix(root): handle MainShell acquisition failure
- `f82507ae` refactor(root): centralize privileged command routing
- `042849ba` refactor(root): centralize privileged command routing
- `93649881` refactor(root): centralize privileged command routing
- `a4ae5e37` refactor(root): centralize privileged command routing
- `4d7a6381` test(root): cover concrete execution boundaries
- `428d4e4d` fix(archive): isolate root commands from interactive work
- `97aa3817` fix(archive): preserve force-stop execution metadata
- `b30d7e64` chore(deps): bump the web group in /web with 3 updates
- `152e4618` chore(deps): bump the actions group with 2 updates
- `9aa77b03` feat(sweep): persist durable request snapshots
- `2ae1df2b` feat(sweep): enqueue and observe durable requests
- `8e01e7d5` fix(sweep): expose durable request discovery
- `e44b1e94` chore(deps): bump the maven group with 5 updates
- `6b56bf74` fix(backup): preserve enqueue handoff before background
- `7cd21db0` fix(archive): access private data through archive shell
- `f55a4981` fix(sweep): preserve enqueue and source observation
- `be5fb3a2` feat(sweep): cancel the durable queue safely
- `d588143e` test(room): enable migration foreign keys
- `756700c6` feat(sweep): execute durable privilege sweeps
- `446c7fbb` fix(privilege): exclude broker authorization self-grants
- `61c8a888` feat(sweep): migrate bulk freeze actions to WorkManager
- `890bbf31` fix(sweep): retain durable profile identity
- `eca4eb74` feat(sweep): render durable progress states
- `d6717b8b` fix(sweep): prefer newest retained profile request
- `0844212d` feat(sweep): add replay-safe cache and reinstall actions
- `34f67e1e` fix(sweep): reject failed reinstall path lookups
- `5479b643` fix(deps): restore Asgard Material3 compatibility
- `9ddcaa94` docs(workers): document shell lanes and sweep states
- `ce7e5895` test(sweep): avoid restricted WorkManager states
- `2fa5b714` test(sweep): align dialog lifecycle assertions
- `26fe6af0` feat(archive): authenticate schema v2 manifests
- `ce2b9fa0` feat(archive): authenticate bundle bytes
- `13d50cc7` feat(archive): verify staged APK clusters
- `7969ea37` feat(archive): roll back unsafe new installs
- `385b9bf5` feat(archive): refuse unauthenticated restores
- `7e07a2e9` fix(archive): harden authenticated restore flow
- `9671462c` fix(archive): close install cancellation gap
- `d516a54d` docs: record worker lane acceptance evidence
- `41830645` fix(root): drain cancelled interactive MainShell work
- `23c4677c` docs: finalize worker lane acceptance evidence
- `60dc074e` fix(web): exclude internal lane architecture page
- `591c96ec` fix(privilege): serialize root availability probes
- `c1c4fe29` docs(workers): capture service queue latency baseline
- `c08bc4c4` fix(ui): remove release latency draw hooks
- `a70daf65` feat(queue): define durable task contracts
- `5ab628bf` fix(queue): validate durable task contracts
- `c057ceea` fix(queue): harden presentation arguments
- `c147f04d` feat(database): add complete durable queue schema
- `e18762c8` fix(database): harden durable task recovery
- `0a8fbf8e` fix(database): require explicit recovery liveness
- `c7f37826` test(database): prove final drain writer contention
- `148265de` feat(database): add sweep target claim transactions
- `1d70dd69` fix(database): harden sweep claim recovery
- `e1902e28` fix(database): reject partial sweep ownership
- `b84dabe3` fix(database): fence malformed sweep targets
- `3fddfa47` fix(database): validate sweep ownership tokens
- `9e662f48` fix(database): align sweep runnable detection
- `6462fe90` feat(queue): add durable data task acceptance
- `5667f97c` fix(queue): preserve keys after accepted cancellation
- `0a8f5422` refactor(backup): extract archive task runners
- `5cee7b2f` chore(deps): bump io.coil-kt.coil3:coil-compose in the maven group
- `7e4ea876` fix(backup): harden archive task recovery
- `70be7af0` refactor(export): add resumable data task runners
- `8c49785f` fix(export): reconcile durable publications
- `b75964fc` fix(export): fence durable publication leases
- `33265150` feat(service): add typed foreground prerequisites
- `77b93451` feat(service): activate durable data queue
- `e8e7d98e` fix(service): harden data queue ownership
- `a4bd12da` fix(service): fence data queue generations
- `f4f5a2bd` fix(service): complete data queue fencing
- `76f87280` fix(service): close data queue recovery gaps
- `67672a0d` fix(service): settle exact data claims
- `9be7d51a` fix(service): finish recovered data claims
- `e76894e8` fix(service): close data recovery races
- `b6efdb3c` fix(service): preserve data recovery completion
- `a0afd1ab` fix(service): retry inherited data recovery
- `b088330f` chore(deps): bump the web group in /web with 4 updates
- `17c75f05` chore(deps): bump softprops/action-gh-release in the actions group
- `d535d3f5` fix(service): gate inherited recovery retry
- `3b7ca931` feat(sweep): recover durable target state
- `9a108824` fix(sweep): preserve legacy work identity
- `72fa4cf7` feat(sweep): activate foreground service queue
- `e5b80b5b` refactor(workers): isolate compatibility execution
- `0fc62497` feat(queue): project durable task history
- `81e7e73f` feat(queue): add task queue screen
- `baac6038` feat(queue): add task detail logger
- `1fe31820` feat(queue): navigate to durable task details
- `8efa11a7` feat(share): queue durable share preparation and retention
- `ba9f8281` fix(share): reclaim cancelled and recovered share outputs
- `cc67ad66` fix(queue): render localized progress and cancellation notifications
- `c8da3fb3` chore(build): retain Studio AGP alpha04 update
- `92e7d476` docs(queue): record service migration and remaining acceptance gates
- `b4d3328b` docs(queue): record PR publication and final review gate
- `93439274` fix(test): avoid recursive source-sanitizer overflow
- `d1eda765` chore(web): update fast-uri to patched 3.1.7
- `98be2f6c` docs(queue): record CI corrections and review recovery
- `fd8b97c9` fix(queue): harden task ownership, export publication and UI lifetimes
- `25ffb74e` docs(queue): record CI acceptance and bounded emulator measurements
- `45bf8b24` fix(queue): consolidate pending logs and space task controls
- `225fce86` feat(queue): add Guardians branding and operation icons
- `eada3844` chore(deps): bump the maven group across 1 directory with 2 updates
- `1f6e8a7a` fix(export): reset sheet state after task navigation
- `37831981` fix(root): use mount-master for sweep cache operations
- `cb1068d5` chore(queue): remove acceptance probes and stabilize Back test
- `8bc360d6` docs(queue): reconcile physical and emulator validation
- `9e65c745` chore(deps): bump svgo
- `d3e8d418` chore(web): sync Astro and js-yaml updates from master
- `99ddd6ac` fix(deps): pin Material3 to the Asgard-compatible runtime
- `634da1ba` feat(installer): add legacy APK consent and readable settings
- `95ba6770` docs(community): record feature assessment and validation status
- `d91378d3` docs(installer): clarify disposable smoke-test emulator
- `9ed5a4d8` feat(app-list): confirm bulk actions and improve task progress
- `a629b0ae` fix(i18n): describe queued suspend and unsuspend actions
- `f5ad6f70` chore(release): prepare v1.95.4 notes and metadata
- `bd2d8c8a` fix(uninstall): sequence standard dialogs for batch fallback
- `df4fff56` chore(deps): update Kotlin and Android libraries
- `825408ee` refactor(kotlin): use stdlib ordering and hex encoding
- `e5b61192` refactor(kotlin): use explicit backing field for installer events
- `22949711` docs(perf): record emulator profiling results
- `a691b773` fix(build): increase Kotlin compiler heap for release builds
- `aa58f5b2` feat(i18n): add English and Arabic legacy Fix Store guidance
- `b6aa4c3f` feat(i18n): translate legacy Fix Store guidance to Spanish and French
- `67fa5df9` feat(i18n): translate legacy Fix Store guidance to Polish and Portuguese
- `b9e6dc22` feat(i18n): translate legacy Fix Store guidance to Brazilian Portuguese and Chinese
- `d4a1d4fa` feat(installer): define version-aware Fix Store routing
- `8db86e2f` feat(installer): verify legacy reinstalls and reject split APKs
- `b83e7521` fix(installer): track legacy install requests across lifecycle changes
- `f3da333d` fix(dhizuku): reject unsupported silent Fix Store operations
- `1637179a` fix(ui): support readable logs and neutral completion states
- `933f4a7d` feat(installer): integrate safe user-confirmed legacy Fix Store
- `ae74c03e` fix(app-list): forward reinstall requests through shared routing
- `d44b0894` feat(ui): expose legacy Fix Store without elevated access
- `6bbcb793` docs(installer): document legacy Fix Store compatibility limits
- `6e55aca3` chore(deps): bump fastlane from 2.238.0 to 2.239.0 in the fastlane group
- `59689621` chore(deps): bump the web group in /web with 3 updates
- `c19b46a5` chore(deps): bump the actions group with 2 updates
- `c2965493` fix(installer): remove legacy Fix Store fallback
- `6b4e562a` feat(settings): add Asgard and system font presets
- `ec5e010f` fix(installer): show feedback while preferences load
- `6748bb8f` fix(lint): remove synthetic accessors and enforce the check

**Changes after the 1950 release:** https://github.com/trinadhthatakula/Thor/compare/v1.95.0-dev-40...f7bb0001dbd4511705aedee646039fe767503ec6

**Including the development cycle consolidated into 1950:** https://github.com/trinadhthatakula/Thor/compare/v1.94.0...f7bb0001dbd4511705aedee646039fe767503ec6
