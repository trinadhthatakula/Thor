# App-list bulk freeze consent and background suspend (QA-001)

Status: implemented in Doctor on `feat/legacy-apk-install`, with basic bulk-action flows and the
Recents polish physically confirmed by the maintainer on 2026-09-10. Ready for integration into
`dev`; not yet merged or released. These changes are separate from PR #464's merged
legacy-installer/settings changes and its disposable-emulator wording correction.

## Tester request — 2026-09-10

- Confirm bulk Freeze from the app list, as bulk Suspend already does.
- Include **Add to Freezer**, checked for each new request; allow the user to uncheck it.
- Give bulk Suspend the same service-backed progress and **Run in background** option as Freeze.
- Keep the app list or originating activity visible behind progress. The previous Freeze task-detail
  navigation entry replaced the originating screen before rendering its logger dialog.
- Follow-up after physical confirmation: show the newest recent task first, and separate statuses
  such as **Completed with issues** from the progress counter.

## Behavior

App-list Freeze now waits for explicit confirmation. Cancel/dismiss performs no work. The checkbox
is scoped to that selection: rotation preserves an explicit opt-out, while a new request starts
checked. The saved selection includes package identities and safety-classification inputs, not APK
paths or the complete app metadata. Empty selections cannot be confirmed; safety-blocked apps are
filtered before a task is submitted.

Checked requests add each successfully frozen package to the Freezer watchlist, including packages
already frozen. Unchecked requests do not add or remove membership. Existing entries and profile
associations are preserved. Failed, busy, or absent targets are not added. A failed membership write
does not count as a successful task item; retrying remains idempotent.

The choice is persisted with the task, not read from a dialog after enqueue. Schema 10 adds
`sweep_requests.add_to_freezer` with default `0`; the 9→10 migration leaves every older request
unopted-in. Requests with different tracking choices do not coalesce. If the process stops between
freezing and recording membership, recovery retries the missing tracking step instead of reporting
premature success. Cancellation still takes precedence over requeueing.

Bulk Suspend and Unsuspend are distinct service-backed task operations, with accurate queue and
notification labels. Their exact suspension readback does not confuse a disabled package with a
suspended one. Single-app operations and existing Freezer/watchlist/profile defaults are unchanged.

Task detail uses Navigation 3's dialog scene, keeping the source entry rendered behind one modal
window. Its existing saved back stack and per-entry ViewModel ownership remain in place. Background,
system Back, and scrim dismissal close the presentation without cancelling the service task;
cancellation remains an explicit task action. Queue/notification opens use the same dialog treatment.

## Verification

### Initial bulk-action checks

Verified on 2026-09-10 against the local changes after `d91378d3`, before the Recents polish below:

- `./gradlew test assembleFossDebug assembleFossDebugAndroidTest lintFossDebug lintStoreRelease`
  passed with Java 21, one worker, and incremental compilation/build/configuration caches disabled
  for this invocation. No project build settings or dependencies were changed.
- FOSS Debug and Store Debug each passed **2,756 tests** across 231 suites, with no failures,
  errors, or skipped tests. Coverage includes confirmation consent/restoration/large text, exact
  suspension readback, successful-only watchlist tracking, recovery, and queue routing.
- FOSS Debug lint: **0 errors, 66 warnings, 15 hints**. Store Release lint: **0 errors,
  53 warnings, 15 hints**. Neither report contains `MissingTranslation` findings or `:bypass`
  `SyntheticAccessor` errors. The obsolete foreground Suspend/Unsuspend title strings were removed
  from all eight locales after the task-queue migration made them unused.
- Fresh app and test APKs were installed only on disposable API 36 AVD `Thor_Root_API36`
  (`emulator-5580`). `SweepMigrationTest` and `TaskDetailScreenTest` passed **23 tests**:
  6 migration tests and 17 dialog tests. These include the real migration chain from every schema
  version 1–9 to 10, the default opt-out for old tasks, and origin-preserving Background, system Back,
  and scrim dismissal. The emulator was then shut down without saving state.
- `git diff --check` passed. The main `dev` checkout remained clean; unrelated local configuration
  files in Doctor were left untouched.

The emulator tests exercise SQLite migrations and dialog UI with supplied task state, not actual
privileged freeze/suspend operations. On 2026-09-10, a physical-device check of this local work
confirmed that app-list bulk Freeze waits for confirmation, the Add to Freezer checkbox works,
Suspend and Unsuspend run through the service and appear in Queue Recents, and the dialogs display
correctly. The device, OS version, and privilege mode were not recorded, so this is not Root,
Shizuku, or Dhizuku matrix acceptance. Earlier settings-device approval remains separate.

### Recents ordering and row readability — 2026-09-10

Recent tasks now form one history across Data and Privilege queues, ordered by recorded completion
time descending. Sequence descending and task UUID provide stable tie-breakers. Running selection
and the per-queue FIFO display of pending work are unchanged; execution order was not modified.
Status, progress count, and queue-kind labels each occupy their own full-width line, wrapping as
needed without squeezing **Completed with issues** beside its counter. Existing theme styling,
translations, task counts, and row/action callbacks are preserved.

The following passed with Java 21 and the same serial/nonincremental build options:

```sh
./gradlew test lintFossDebug lintStoreRelease assembleFossDebug compileFossDebugAndroidTestKotlin
```

- FOSS Debug and Store Debug each passed **2,763 tests across 232 suites**, with no failures,
  errors, or skips. These counts supersede the earlier 2,756-test checkpoint.
- Added tests reuse JUnit4, native Robolectric, and Compose; no test dependencies were added.
  They cover cross-queue completion order, stable ties, retained-history refresh, correct task
  callbacks before/after restoration, and separate complete labels in all eight locales (including
  RTL) at 320dp/2× and 600dp/1.5× text.
- Both lint gates passed: **0 errors** and no `MissingTranslation` or `:bypass` `SyntheticAccessor`
  errors. Warning/hint totals remain 66/15 for FOSS Debug and 53/15 for Store Release.
- The debug APK was rebuilt and Android-test Kotlin compiled. The earlier 23 emulator
  instrumentation tests were not rerun for this presentation-only follow-up.
- The maintainer subsequently confirmed that everything in the latest APK is in order, including
  newest-first Recents and the separate status/count lines, and approved committing the changes on
  2026-09-10. This is maintainer-reported physical acceptance, not an agent-run device test or an
  exhaustive device, locale, font-size, or privilege-mode matrix.
- `git diff --check` passed.

## Remaining physical-device acceptance

- [ ] Exercise cancel and reopen, rotation after unchecking, failed/busy/absent targets, and
  existing watchlist/profile entries. The basic confirmation and checkbox flow is confirmed, but
  successful-only tracking and state-restoration edge cases are not.
- [ ] Suspend/Unsuspend several safe test packages with Root, Shizuku, and Dhizuku where available;
  Background the task, return through Queue, and verify state/results. The basic service/Recents
  flow is confirmed on one unspecified device/mode; no implicit privilege fallback or enabling of
  disabled apps should occur.
- [ ] Verify the source app list stays behind progress on the reporter's device, including Back,
  outside-tap, completion, and notification re-entry. Dialog display is confirmed; those dismissal
  and re-entry paths are not.
- [ ] Upgrade a real existing install and confirm watchlist/profile membership and retained tasks
  survive schema migration. Do not use production/user packages as disposable freeze fixtures.
- [x] Confirm newest-first Recents and the separated status/count labels in the rebuilt debug APK
  on the physical device — maintainer confirmed on 2026-09-10.
