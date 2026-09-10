# Thor v1.95.4 Release Notes

Changes since [v1.95.2-dev-60](https://github.com/trinadhthatakula/Thor/releases/tag/v1.95.2-dev-60),
the latest release tag including prereleases, through commit `a629b0ae`.
This development release brings background task tracking, safer bulk actions, stronger restore
validation, and explicit consent for legacy APK installation and permission grants.

## ✨ Highlights

- ⏳ **A Queue screen for background work.** Follow accepted backup/restore, single-app export,
  bulk-share preparation, and supported bulk app actions; reopen their progress and retained results.
- ❄️ **Bulk Freeze asks first.** Keep **Add to Freezer** checked to track successfully frozen apps,
  or uncheck it to freeze without adding entries. Bulk Suspend and Unsuspend now offer background
  execution and appear in Queue.
- 🕘 **Readable progress, newest results first.** Recent tasks from both queues share one
  newest-completion-first list. Status, app counter, and queue type have separate lines, and progress
  dialogs keep the originating screen visible.
- 📦 **Older-target APK installation with consent.** Eligible Root/Shizuku installs can override
  Android's low-target restriction after a warning. A default-off setting lets experienced users
  remember that choice.
- 🔐 **Permission grants are opt-in.** Privileged installation no longer requests all runtime
  permissions by default; the install screen and settings make the choice explicit.
- 🛡️ **Stronger restore validation and clearer settings.** Authenticated archives, verified APK
  sets, fully wrapping Freezer/security descriptions, and a Home/Settings startup crash correction.
- ⚠️ **App-data backup compatibility changes.** Older schema-1 `.thorbak` backups, including those
  made by v1.95.2, cannot be restored in this version. Make fresh backups while the original app data
  is still available.

## What's Changed

### ⏳ Background tasks and the Guardians Queue — [#453](https://github.com/trinadhthatakula/Thor/pull/453)

Accepted work is recorded in a durable database before its foreground service is awakened. Data
work and privilege sweeps have separate queues; each processes its own work serially. The supported
paths include archive backup/restore, single-app export, bulk-share preparation, freeze/unfreeze,
per-app cache clearing, and verified Fix Store/reinstall work.

The **Guardians** Queue shows Running, Queued, and Recent tasks, with operation icons and
Root/Shizuku/Dhizuku identities. These identities describe the task's selected mode; they are not a
live availability check for that provider.

- Reopen a task's logger to see localized progress, per-app outcomes, pending counts, cancellation,
  and recovery explanations.
- **Run in background**, Back, or dismissing progress closes the presentation without cancelling
  accepted work. Cancellation remains an explicit, request-specific action with a visible
  **Stopping** state.
- Service-start failures, unavailable observations, and interrupted work with uncertain outcomes
  remain visible instead of being reported as successful.
- Notifications and task details use durable task identities so stale callbacks cannot claim a new
  task's work or results.

This is bounded task history, not a comprehensive action audit journal. Persisting a request does
not promise automatic completion after every reboot, force-stop, provider failure, or lost
authorization. Direct paths such as single-app quick sharing and multi-app Backup/export remain
outside this queue migration.

Implementation landmarks: `6462fe90`, `77b93451`, `72fa4cf7`, `0fc62497`, `81e7e73f`,
`baac6038`, `1fe31820`, `cc67ad66`, `45bf8b24`, `225fce86`.

### ❄️ Bulk-action consent and progress polish — [#465](https://github.com/trinadhthatakula/Thor/pull/465)

App-list **Freeze** now opens a confirmation dialog with **Add to Freezer** checked for each new
selection. Cancel or dismiss submits nothing; rotation preserves an explicit opt-out. Empty or
safety-blocked selections do not become actionable work.

The choice travels with the durable task. Checked requests add membership after a successful freeze,
including an already-frozen package; failed, busy, or absent targets are not added. Unchecked requests
do not add or remove membership. Existing watchlist entries and profile associations are preserved.

Bulk **Suspend** and **Unsuspend** are distinct service-backed operations with accurate Queue and
notification labels. Suspension readback distinguishes a suspended package from a disabled one;
single-app operations and existing Freezer/profile defaults are unchanged.
The privilege-queue notification channel now lists suspend and unsuspend alongside the existing
operations in all eight locales.

Task details now use a Navigation 3 dialog scene so the app list or other originating screen remains
behind progress. Recent tasks are sorted across both queues by completion time descending, then
sequence descending and UUID for stable ties. Pending tasks retain their existing FIFO order.
Status, counter, and queue type wrap independently rather than squeezing **Completed with issues**
beside the app count.

Room schema **9 → 10** adds the tracking choice with an opt-out default for older tasks. Recovery
can retry an unfinished membership write without silently changing the original request's intent.

Implementation: `9ed5a4d8`; notification-description follow-up: `a629b0ae`.

### 📦 Legacy APK consent and safer permission grants — [#464](https://github.com/trinadhthatakula/Thor/pull/464), [#446](https://github.com/trinadhthatakula/Thor/pull/446), [#451](https://github.com/trinadhthatakula/Thor/pull/451)

The installer reads the target SDK from the selected APK or bundle's staged base APK. Android 14
normally blocks new installs targeting below API 23; Android 15/16 normally block targets below API
24. This concerns the APK's **target SDK**, not its minimum supported Android version.

For eligible Root/Shizuku shell installs, **Install this time** confirms the low-target bypass.
**Settings → Installing → Allow legacy APK installs without asking** is off by default; enabling
it skips the extra confirmation but keeps the inline warning. A one-time confirmation never enables
the setting. Cancel, changed selections, stale callbacks, and unreadable preferences do not authorize
another install.

Normal, Dhizuku, and external installation cannot apply this override. It does not bypass signature,
ABI, split consistency, downgrade, or Xiaomi system-app update restrictions, and does not guarantee
that an older app will run. Background restore/reinstall callers do not inherit this interactive
consent. The legacy-APK choice is separate from granting runtime permissions.

Install-time runtime-permission grants are now opt-in, with a per-install choice that resets for a
newly parsed package. The saved setting explains where that choice applies. Shizuku/Dhizuku broker
authorization permissions are excluded from Thor's self-grant path: broker consent must still come
from the broker. Older apps can retain their platform-defined legacy permission behavior even when
Thor does not request an explicit grant.

Implementation: `9e8611d2`, `4fc55d3f`, `35c21e2a`, `5c0f63ff`, `446c7fbb`, `634da1ba`.

### 🛡️ Authenticated restores and coordinated root work — [#453](https://github.com/trinadhthatakula/Thor/pull/453), [#461](https://github.com/trinadhthatakula/Thor/pull/461)

Backup/restore validation now authenticates the schema-v2 manifest and archive member bytes.
Encrypted-member authentication binds the expected data class and member/chunk identity.
**Unauthenticated restores are refused**, rather than silently retried through a weaker path.

**Backup compatibility:** `.thorbak` app-data backups created by v1.95.2 and earlier use schema 1
and cannot be restored in v1.95.4. Create fresh backups with this version while the original app
data is still available; existing archives are not automatically converted. This format change
applies to app-data backups; ordinary APK/APKS/XAPK installation remains supported.

Before installation, staged APK sets are checked for the expected package, version, exact signer,
and valid install set. If a newly installed package becomes unsafe to continue restoring, rollback
is limited to the still-matching installed copy that Thor recorded. Unknown or changed package
identity refuses automatic deletion; this is not a blanket rollback of every restore failure.

Interactive, archive, and sweep root work now use explicit execution lanes with per-package
mutation coordination. Dedicated archive/sweep shells can fall back to serialized MainShell work
with degraded execution recorded. Cancellation is distinguished from timeout, and cancelled
interactive commands are drained before the shell is reused. Root availability checks are serialized.

The Root sweep shell now requests the global mount namespace so cache operations can see other
packages' app data. Interactive shell configuration is preserved.

Implementation: `db8184f7`, `2c1330a8`, `3d7f9c63`, `26fe6af0`, `ce2b9fa0`,
`13d50cc7`, `7969ea37`, `385b9bf5`, `41830645`, `591c96ec`, `37831981`.

### 📤 Export recovery and prepared sharing — [#453](https://github.com/trinadhthatakula/Thor/pull/453), [#461](https://github.com/trinadhthatakula/Thor/pull/461)

Public exports reconcile recorded output identities and publish verified-name MediaStore/SAF
outputs. Recovery can recognize a completed publication instead of blindly publishing again.
Cleanup and cancellation are tied to the owning task; arbitrary provider behavior is not a
guarantee of power-loss durability or duplicate-free recovery.

Bulk sharing prepares private, task-owned files in the data queue. Reopening **Share** validates
ownership, expiry, file readability/size, and provider URIs before granting read-only access.
Prepared files are available for 24 hours, with best-effort expiry cleanup. Automatic format
selection uses APK for monolithic apps and APKS for split apps, not XAPK.

Reopening Export after returning from task details now resets stale **Exported** presentation.
Reopening while an export is active reconnects to that task without submitting another one.

Implementation: `8c49785f`, `b75964fc`, `8efa11a7`, `ba9f8281`, `fd8b97c9`, `1f6e8a7a`.

### ⚙️ Readable settings and startup compatibility — [#464](https://github.com/trinadhthatakula/Thor/pull/464)

All four Freezer switches and the biometric-lock explanation use fully wrapping titles and
descriptions, preserving one accessible switch target. Larger text can expand the scrolling
category without cutting off the explanation. The legacy-install setting uses the same readable
treatment.

Biometric-lock copy now explains launch authentication, screenshot/recording protection, and the
hidden Recents preview across all eight app locales. This is a discoverability change:
**existing app-lock and screenshot-protection behavior is unchanged**.

A Material 3 API removal had made Asgard's Home/Settings controls throw `NoSuchMethodError` at
startup. Thor restores the compatible alpha26 runtime with a strict constraint and excludes the
known-incompatible alpha27 Dependabot update. Real-Asgard control tests cover that failure; the
separate Asgard library upgrade is not part of this release.

Implementation: `99ddd6ac`, `634da1ba`.

## 🧰 Project & Internal

- **Database and recovery coverage** — exported schemas, migration tests, claim ownership,
  cancellation/recovery fencing, legacy-work compatibility, notification identity, publication
  ownership, and UI lifetime regressions accompany the foreground-service migration
  ([#453](https://github.com/trinadhthatakula/Thor/pull/453),
  [#461](https://github.com/trinadhthatakula/Thor/pull/461),
  [#465](https://github.com/trinadhthatakula/Thor/pull/465)). Historical intermediate WorkManager
  commits appear in the log; the final supported queued paths use the foreground services.
- **Website maintenance** — Astro, MDX, sitemap, Node typings, and lockfile dependency updates,
  including fast-uri, SVGO, and js-yaml maintenance. The web-only updates merged on master are
  reflected in dev via [#463](https://github.com/trinadhthatakula/Thor/pull/463); related grouped
  updates are [#448](https://github.com/trinadhthatakula/Thor/pull/448) and
  [#456](https://github.com/trinadhthatakula/Thor/pull/456). The internal worker-lane page is excluded
  from indexing. This does not claim a fix for the separate, unreproduced Android Brave layout report.
- **Build and workflow upkeep** — AGP 9.5.0-alpha04, Coil 3.6.2, test infrastructure, CodeQL,
  setup-java, and release-action updates
  ([#447](https://github.com/trinadhthatakula/Thor/pull/447),
  [#449](https://github.com/trinadhthatakula/Thor/pull/449),
  [#450](https://github.com/trinadhthatakula/Thor/pull/450),
  [#455](https://github.com/trinadhthatakula/Thor/pull/455),
  [#457](https://github.com/trinadhthatakula/Thor/pull/457),
  [#459](https://github.com/trinadhthatakula/Thor/pull/459)). The Material 3 compatibility constraint
  above deliberately takes precedence over the incompatible update.
- **Community assessment and acceptance records** — GitHub, Telegram, and Reddit requests are
  reconciled into implementation, product-decision, extension, and deferred work. The assessment
  itself is not implementation of every request. Device reports are distinguished from automated
  checks and remaining mode/edge-case acceptance.
- **Release bookkeeping** — version code **1954**, derived name **1.95.4**. Code **1953** was already
  consumed for Play's special-use foreground-service access submission and is deliberately skipped. Play
  notes are mirrored to every Fastlane locale; release-note retention remains 20 directories.
  The Shizu manifest remains tied to the latest production release, not this development release.

## 🧪 Verification and Remaining Acceptance

Release-preparation gates passed for version code **1954** on JDK 21 with a single worker and
a 4 GiB Kotlin compiler heap. These invocation options leave the project build settings unchanged:

```sh
./gradlew test lintFossDebug lintStoreRelease \
  assembleFossDebug compileFossDebugAndroidTestKotlin \
  --console=plain --no-parallel --max-workers=1 \
  '-Pkotlin.daemon.jvmargs=-Xmx4g -XX:+UseG1GC' \
  --no-configuration-cache --no-build-cache \
  -Pkotlin.incremental=false -Pksp.incremental=false
```

- FOSS Debug and Store Debug each passed **2,763 tests across 232 suites**, with no failures,
  errors, or skips. The full suites ran after the notification-description updates; the final
  pre-PR gate reused those unchanged unit-test outputs.
- Both lint gates have **zero errors** and no `MissingTranslation` findings or `:bypass`
  `SyntheticAccessor` errors. Existing warnings/hints remain: FOSS **66/15**, Store **53/15**.
- Release-note budgets pass: Play **487/500 characters** and Telegram **964/1024 assembled UTF-16
  units**. Both Fastlane locale copies match the Play source byte-for-byte; locale parity and the
  production-pinned Shizu manifest check pass. The shell suite passed before and after retention
  pruning (**10 test files**); Fastlane's Ruby suite passed **35 tests / 49 assertions**. The retained
  20 releases have no broken references to the removed v1.90.3 notes, and historical Fastlane
  changelogs remain intact.
- FOSS debug assembly and Android-test Kotlin compilation passed in the final pre-PR gate.
  The earlier bulk-action checkpoint passed **23 disposable-emulator instrumentation tests**,
  covering migrations and task dialogs. These were not rerun for the presentation-only Recents
  polish or this release preparation.
- Queue ordering, restoration, callbacks, and separated labels are tested across eight locales,
  including RTL, at 320dp/2× and 600dp/1.5× text.
- The maintainer reports physical-device confirmation of legacy APK installation through Root,
  settings readability, basic bulk Freeze consent/tracking choice, service-backed Suspend/Unsuspend,
  correct dialogs, and the latest Queue ordering/layout. These reports do not identify a complete
  device/ROM/privilege-mode matrix.
- Earlier service-queue acceptance records contain physical-device and emulator checks, but
  **T19 remains deferred and T22 remains incomplete**. No universal recovery, matched performance
  improvement, or exhaustive Root/Shizuku/Dhizuku acceptance is claimed. Legacy-install OEM/mode
  coverage, archive/provider edge cases, and relevant Store-policy checks remain documented follow-ups.

## 🛠 Commits Log

Complete non-merge history in chronological order: **130 commits** from
`v1.95.2-dev-60..a629b0ae`. This includes documentation, tests, maintenance, and intermediate
implementation stages; the final behavior is described above.

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

### Integration commits

All **20 merge commits** in the same range, also in chronological order.

- `72c25630` Merge pull request #446 from trinadhthatakula/fix/install-grant-all-permissions-optout
- `089de141` Merge pull request #447 from trinadhthatakula/dependabot/gradle/dev/maven-3bed97aa0c
- `2cc58c2d` Merge pull request #449 from trinadhthatakula/dependabot/github_actions/dev/actions-38908e4303
- `0c756735` Merge pull request #448 from trinadhthatakula/dependabot/npm_and_yarn/web/dev/web-3a2be4fbad
- `d3e31c0b` chore: sync feat/worker-shell-lanes with dev
- `b40334d1` Merge pull request #450 from trinadhthatakula/dependabot/gradle/dev/maven-424c98aa43
- `ed247f21` Merge remote-tracking branch 'origin/dev' into feat/worker-shell-lanes
- `a4b1fcef` Merge pull request #451 from trinadhthatakula/fix/shizuku-self-permission-grant
- `63d1d39b` Merge remote-tracking branch 'origin/dev' into feat/worker-shell-lanes
- `ceb201dd` Merge pull request #455 from trinadhthatakula/dependabot/gradle/dev/maven-3410fb38ec
- `9765d974` Merge pull request #457 from trinadhthatakula/dependabot/github_actions/dev/actions-6512b1d693
- `deaf370a` Merge pull request #456 from trinadhthatakula/dependabot/npm_and_yarn/web/dev/web-8b5dee0527
- `a3fa6321` Merge remote-tracking branch 'origin/dev' into feat/worker-shell-lanes
- `eb2345e1` Merge pull request #453 from trinadhthatakula/feat/worker-shell-lanes
- `d091ecb7` Merge pull request #459 from trinadhthatakula/dependabot/gradle/dev/maven-fe4467dd00
- `02b0956e` Merge dev into chore/service-queue-emulator-acceptance
- `504e3418` Merge pull request #461 from trinadhthatakula/chore/service-queue-emulator-acceptance
- `5c9672e6` Merge pull request #463 from trinadhthatakula/chore/sync-web-dependabot-to-dev
- `50dd13c3` Merge pull request #464 from trinadhthatakula/feat/legacy-apk-install
- `3f5ec724` Merge pull request #465 from trinadhthatakula/feat/legacy-apk-install

[Full source comparison](https://github.com/trinadhthatakula/Thor/compare/v1.95.2-dev-60...a629b0ae173b5293ae7575ef2d5ccba3133a64a1)
