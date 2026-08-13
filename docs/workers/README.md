# 🧵 Workers — what Thor runs through WorkManager

Which of Thor's operations are WorkManager jobs, which are not, and what the difference costs.

**Read this if** you are adding a background job, wondering why a bulk action is *not* one, or
debugging a job that never showed up. For the branch/release side of the repo see
[`branching-and-releases.md`](../branching-and-releases.md); for deferred work see
[`follow-ups/README.md`](../follow-ups/README.md).

**Last verified:** 2026-08-13 (UTC) against branch `feat/job-seam-generalise` @ `ba65dc16`. Dates here
are UTC, matching the GitHub timestamps they can be checked against. Every line number below was
opened, not grepped for; if you move code, move the anchor.

---

## The answer, in four lines

**Two operations use WorkManager.** Back up **one** app to an encrypted `.thorbak`, and restore
**one** app from one. Both are single-app, both are archive work, and both go onto the same unique
chain, so they serialise and never run at once.

Everything else Thor does in the background — including the **multi-app export that shares the word
"backup"**, and every bulk freeze, unfreeze, force-stop, cache clear and reinstall — is a coroutine on
a process-lifetime scope or a `viewModelScope`. See [What is *not* on WorkManager](#what-is-not-on-workmanager-and-why).

---

## The two jobs

| | `ARCHIVE_BACKUP` | `ARCHIVE_RESTORE` |
|---|---|---|
| `ThorJobKind.id` | `archive-backup` | `archive-restore` |
| Worker | `ArchiveBackupWorker` — `AppArchiveWorker.kt:61` | `ArchiveRestoreWorker` — `AppArchiveWorker.kt:236` |
| Unique chain | `THOR_JOB_CHAIN` = `"thor.job.chain"` | same chain |
| Started by | `ThorJobLauncher.startBackup` (`:64`), from `AppBackupViewModel:361` | `ThorJobLauncher.startRestore` (`:102`), from `ArchiveRestoreViewModel:751` |
| Work tag | `thor.job.archive-backup.<pkg>` | `thor.job.archive-restore.<pkg>` |
| Input `Data` | `thor.backup.package` (String), `thor.backup.classes` (String[]), `thor.backup.bundle` (Boolean), `thor.backup.salt` (Base64 String) | `thor.restore.uri`, `thor.restore.package` (String), `thor.restore.classes` (String[]), `thor.restore.obb` (Boolean) |
| Output on success | bare `Result.success()` — no data | `Result.success(workDataOf(JOB_WARNINGS_KEY to …))`, a String[] |
| Output on failure | `JOB_ERROR_KEY` = `"thor.job.error"`, one sentence | same |
| Runs foreground | yes (`dataSync`) | yes (`dataSync`) |

**How a user gets there.** Backup: the app-info surfaces open the backup sheet, plus a second host —
`MainViewModel.BackupSheetState` (`MainViewModel.kt:205`), which is *only* ever opened by a
notification tap. Restore: the Settings → Restore row, or a notification tap routed through
`JobSheetLaunchActivity` (`AndroidManifest.xml:391`, `exported="false"`, `noHistory="true"`).

### The `Data` contract, and the one rule that is only half written down

`androidx.work.Data` accepts a small set of types. In Thor that is **String, Boolean and
`Array<String>`** — a `Set` or an enum passed to `workDataOf` throws **at enqueue time, in
production**. `ArchiveBackupRequest.toMap()` states this in its KDoc (`ArchiveBackupRequest.kt:47-54`);
`ArchiveRestoreRequest.toMap()` (`ArchiveRestoreRequest.kt:40`) is the identical shape, serialised the
same way at `ThorJobLauncher.kt:116`, and **says nothing**. Half the enqueued payloads in the app are
covered by that comment and half are not.

`fromMap` returns `null` for anything it cannot turn into a runnable job, and the worker converts that
into `Result.failure()` with a reason — never `Result.retry()`, which would re-read the same unusable
map forever. Unknown `DataClass` ids are *dropped* rather than fatal, so a job enqueued by a newer
build and run after a downgrade still backs up what it can.

### What is deliberately not in `Data`

No passphrase, no derived key. Input `Data` is written to WorkManager's own SQLite database, so a key
there would be on disk in the clear until the job is pruned. The key is derived in the foreground
(PBKDF2, 210 000 iterations, on `default`) and handed to `ArchiveKeyHolder` in **process memory** under
the request's id.

The KDF **salt** *is* in `Data`, on purpose: it is published in `thorbak.json` where every reader needs
it, and its job is to make one reused passphrase produce a different key per archive.

The restore side also deliberately omits `installFirst` and the header. The worker re-reads the header
from the URI and re-runs `evaluateArchiveRestoreGate` **at the moment it runs** — a gate decision
persisted at enqueue time describes an app that may have been installed, removed or updated while the
job waited its turn.

---

## ⚠️ Being on WorkManager here does **not** mean durable

Both workers open by taking their AES key out of process memory, and both fail immediately if it is
gone:

- `AppArchiveWorker.kt:128-130` — *"this backup's key is no longer in memory — start it again"*
- `AppArchiveWorker.kt:282-283` — the same for restore

So a reboot or an OOM kill does **not** resume the job. WorkManager re-runs the worker, and the worker
refuses. That is the intended design (`§9.2`), not a gap to close — but it means "it's a Worker, so it
survives" is false here, and anyone reasoning about a new job kind should decide explicitly which side
of that line it sits on.

**What WorkManager actually buys Thor is four things:**

1. A `dataSync` **foreground service**, so a multi-gigabyte capture is not frozen mid-write.
2. A **Cancel action in the shade** via `WorkManager.createCancelPendingIntent`, needing no receiver
   of Thor's own — and it cancels the *work*, not just the row.
3. A persisted `WorkInfo.State` a **reopened sheet can reattach to** after a rotation or a trip away
   from the app.
4. A **serialising chain** that holds peak disk to one job however many backups the user starts.

Nothing on that list is retry-on-reboot, and none of the four workers' inputs asks for it: neither
request sets `Constraints`, `setExpedited`, `setInitialDelay` or `setBackoffCriteria`.

> One consequence of no `setExpedited`: WorkManager never calls `getForegroundInfoAsync()` for this
> work — it does that only for expedited `WorkSpec`s. `getForegroundInfo()` is still on the deadline
> path, because `ThorJobWorker.doWork` opens with `setForeground(getForegroundInfo())`.

---

## The seam every job goes through

All of it lives in `app/src/main/java/com/valhalla/thor/data/backup/job/`.

| Class | Job |
|---|---|
| `ThorJobWorker` | `abstract … : CoroutineWorker`. Owns the foreground notification, progress throttling, one failure policy, and the `finally` that cleans up. `doWork()` and `getForegroundInfo()` are **`final`**. |
| `ThorJobLauncher` | `@Single(binds = [ArchiveJobLauncher::class])`. Derives the key, builds the request, and owns enqueue / status / cancel. |
| `enqueueUniqueJob(context, chainName, work, onAbandoned)` | Top-level `internal suspend fun` (`ThorJobLauncher.kt:240`) — the one enqueue expression in the app, extracted so the next launcher shares it rather than copying it. |
| `JobRegistry` | In-memory progress channel, used **instead of** `ListenableWorker.setProgress` (which has zero call sites anywhere in `app/src`). |
| `ThorJobNotifications` | `@Single`. Owns the shade rows, the ids, the channel and the cancel action. |
| `ArchiveKeyHolder`, `JobSheetTargets` | The two process-memory side channels: key material and notification tap targets. |
| `ThorJobWatcher` | Split off `ArchiveJobLauncher` so a screen can watch a job without being able to start one. |

### Adding a new job kind

1. Append to `ThorJobKind` (`ThorJob.kt:67`). **Append only — never insert or reorder** (see below).
2. Subclass `ThorJobWorker` and implement the four abstract members: `kind`, `initialLabel`,
   `sheetTarget`, `runJob()`.
3. Override the two `open` members if you need them: `runsForeground` (default `true`,
   `ThorJobWorker.kt:80`) and `onJobFinished()` (`:227` — non-suspend, on the cancellation path, and
   **must not throw**).
4. Annotate the class `@KoinWorker`.
5. Add an arm to `ThorJobNotifications.titleFor` (`:203`) and `iconFor` (`:221`). Both are `when`s with
   **no `else`**, so a new kind is a compile error until someone has worded it. That is the mechanism,
   not an inconvenience.
6. Call `enqueueUniqueJob(...)` from a launcher with the right chain name.

Useful helpers a subclass already has: `noteResult(message)` (`:203`), `retargetSheet(target)` (`:238`),
`fail(reason)` (`:258`), `publish(progress)` (`:280`).

> ⚠️ **`ThorJobKind` is append-only for a concrete reason.** `notificationId = BASE_NOTIFICATION_ID +
> kind.ordinal`, and that same number is the `PendingIntent` **request code** for the row's tap target.
> Inserting a kind renumbers every kind after it and hands a live notification another job's request
> code. `jobKindFromId` is immune (it matches on `id`); the id arithmetic is not.

### The two chains

| Name | Value | For | Producers today |
|---|---|---|---|
| `THOR_JOB_CHAIN` | `"thor.job.chain"` | anything that **moves bytes** | 2 (`startBackup`, `startRestore`) |
| `THOR_SWEEP_CHAIN` | `"thor.sweep.chain"` | privilege **sweeps** — bulk freeze, unfreeze, force-stop, cache clear, reinstall | **0** |

`THOR_SWEEP_CHAIN` exists, is documented at length (`ThorJob.kt:23-40`), and is asserted about by three
tests — and **nothing enqueues onto it**. It is the landing pad for the bulk-actions work, not a live
code path. Same for `ThorJobStage.ACTING`, `runsForeground = false`, and `ThorJobLauncher.cancel`.

The split exists because the disk argument for serialising byte-movers does not reach a sweep: a
freeze writes no bytes, so queueing a five-second sweep behind an hour-long backup buys nothing.

> ⚠️ **One argument in that KDoc does not survive the split, and the doc should not launder it.**
> `ThorJob.kt:36-38` says sweeps must serialise among themselves partly because *"Odin's root channel is
> one long-lived FIFO `su` session, so two 'concurrent' root sweeps interleave into that single session
> anyway."* That reasoning is chain-agnostic — it applies just as well to a sweep running beside a
> restore, which is exactly what two chains newly permit, and `ARCHIVE_RESTORE` is a privileged-shell
> path too (`RestoreAppArchiveUseCase` runs `extractInto` through `AppDataArchiveGateway`). Treat the
> cross-chain case as **not yet argued**, and settle it before the first sweep is enqueued.

### `ExistingWorkPolicy.APPEND_OR_REPLACE`, precisely

One call site: `ThorJobLauncher.kt:253-259`. Read from work-runtime 2.11.2's `EnqueueRunnable`:

- Any chain leaf **live** (ENQUEUED / RUNNING / BLOCKED / SUCCEEDED) ⇒ the newcomer is appended as a
  **dependent** and enters BLOCKED. It waits.
- Any leaf **FAILED or CANCELLED** ⇒ every `WorkSpec` carrying that name is **deleted**, and the new
  request becomes a fresh chain root. This is the anti-wedging escape hatch, and the reason the policy
  is not plain `APPEND`.

**It never pre-empts.** And a failing prerequisite cancels its dependents, which is the ugly path worth
knowing: a failed backup cancels the restore queued behind it, `doWork` is **never invoked**, so no
notification is ever posted and the UI sees `Failed(reason = null)`.

### The enqueue is awaited

`enqueueUniqueJob` calls `operation.awaitSuccess()` (`ThorJobLauncher.kt:261`). This matters: a
`WorkSpec` insert that fails, a corrupt WorkManager database or a rejected executor task all arrive as
a *failed* `Operation`, and catching only what is thrown synchronously catches argument validation and
nothing else. Without the wait, the launcher returned an id for a row that does not exist, and the
screen sat on a progress bar forever with no timeout anywhere.

If the caller's coroutine is cancelled mid-wait, the work is already WorkManager's, so the key is
**not** released — but `whenFailed` keeps watching the future for the one outcome that means no worker
will run (`ThorJobLauncher.kt:274-276`, `:335`).

> 🕳️ **Four comments in the tree still say the opposite** — *"`ThorJobLauncher` does not await
> `enqueue()`"* — at `AppBackupViewModel.kt:432`, `ArchiveRestoreViewModel.kt:882`,
> `AppBackupViewModelTest.kt:739` and `ArchiveRestoreViewModelTest.kt:1453`. They pre-date the awaited
> `Operation`. Do not quote them as fact.

---

## Progress and notifications

`ThorJobProgress(stage, label, completed, total)`. `completed`/`total` are **unit-agnostic** — bytes for
a streaming restore, class indices for a backup. `total == 0` means *unknown*, `percent` is null, and
the UI shows an **indeterminate** bar. Never render an unknown total as 0%.

`ThorJobStage` has **eight** values: `PREPARING`, `MEASURING`, `CAPTURING`, `WRITING`, `INSTALLING`,
`RESTORING`, `ACTING`, `FINISHING`. `ThorJobStatus` has **six**: `Gone`, `Pending`, `Running`,
`Succeeded(warnings)`, `Cancelled`, `Failed(reason?)`. `Gone` is the *ordinary* answer for an old id —
WorkManager prunes finished work — not an error.

| Thing | Value | Where |
|---|---|---|
| Channel | `"thor.jobs"`, `IMPORTANCE_LOW` | `ThorJobNotifications.CHANNEL_ID` |
| Ongoing row id | `1100 + kind.ordinal` | `BASE_NOTIFICATION_ID` |
| Outcome row id | `1200 + kind.ordinal` | `BASE_RESULT_NOTIFICATION_ID` |
| Outcome row lifetime | 10 s (`setTimeoutAfter`) | `TIMEOUT_MS` |
| Progress throttle | at most one update/second, or on a stage change | `NOTIFICATION_INTERVAL_MS`, `ThorJobWorker.kt:25` |
| Message cap | 512 chars, ellipsised | `MAX_JOB_MESSAGE_CHARS`, `ThorJobWorker.kt:298` |

`BulkResultNotifier` owns id 1001 and request code 0 — a separate channel on purpose, so silencing bulk
results does not silence jobs.

Two ids per kind, not one, because `ThorJobWorker`'s `finally` cancels the *ongoing* id on every
terminal path; an outcome posted under the same id would be cancelled microseconds after being posted.

**Why `postResult` exists at all:** WorkManager **discards a cancelled worker's `Result`** —
`WorkerWrapper` records CANCELLED and drops the output `Data`. So a sweep stopped at 7 of 20 cannot
report that count through `Result.success(...)`. Posting from the worker's own `finally` is the only
route those counts survive by, and that is what `noteResult` is for.

### ⚠️ The notification gate — a real hole today

`ThorJobNotifications.update` (`:110`) and `postResult` (`:145`) both open with:

```kotlin
if (!notificationManager.areNotificationsEnabled()) return
```

That is a cheap guard, not a `POST_NOTIFICATIONS` gate — and it is worth being exact about what the
user is left with:

- On **API 28–32** a job whose notifications are blocked has **no user-visible surface at all**. No
  indication it is running, and no way to stop it short of force-stopping Thor.
- On **API 33+** the system's Task Manager row exists, but it renders the app and a **Stop** button —
  not a notification's actions, so the cancel `PendingIntent` never reaches it. Its Stop force-stops the
  process, which runs **none** of `ThorJobWorker`'s `finally`: no registry clear, no key drop, no
  notification cancel. A restore is covered by §8.5's breadcrumb; a backup relies on the launch sweep.

`areNotificationsEnabled()` is also false when the permission *is* held but notifications are off
app-wide, so granting `POST_NOTIFICATIONS` is necessary and not always sufficient.

A revocation racing a `notify()` call is caught (`SecurityException`) so a lost shade row never fails a
backup.

---

## What is *not* on WorkManager, and why

Not one of these is a `ListenableWorker`. They are coroutines, on three different lifetimes.

| Operation | Where it runs | Anchor |
|---|---|---|
| **Multi-app export** ("backup all") | process-lifetime `@Single` scope | `BackupRunner.kt:58`, scope `:63` |
| **Bulk freeze / unfreeze engine** | process-lifetime `@Single` scope, 5-wide fan-out, 30 s deadline | `BulkFreezeRunner.kt:73`, scope `:84` |
| Bulk reinstall, uninstall, force-stop, cache clear, share, suspend, unsuspend | `viewModelScope` | `MainViewModel.performLoggedMultiAction`, `AppListViewModel`'s bulk branch |
| Counted freeze from the Freezer buttons | `viewModelScope` — **bypasses `BulkFreezeRunner`** | `MainViewModel.performCountedFreeze` |
| Auto-freeze | own scopes | `AutoFreezeManager.kt:49-50` |
| Dynamic shortcuts | own scope | `FreezerShortcutManager.kt:52` |
| QS tile + freezer launcher | own scopes | `FreezerTileService.kt:107`, `FreezerLaunchActivity.kt:49` |
| Launch-time sweeps | app scope | `ThorApplication.kt:204` |
| Privilege probing | own scope | `PrivilegeManager.kt:57` |
| Archive key expiry | own scope, 1 h timer | `ArchiveKeyHolder.kt:50` |
| Install / auto-reinstall broadcasts | `goAsync()` | `AutoReinstallReceiver.kt:40`, `InstallReceiver.kt:34` |
| Extension + freezer bridge IPC | `runBlocking` with a timeout | `ExtensionOpsProvider.kt`, `FreezerBridgeProvider.kt` |

`BackupRunner`'s **non**-adoption is deliberate and argued in its own KDoc (`BackupRunner.kt:47`) — read
that before "fixing" it.

**`MultiAppAction` has exactly ten members**: `ReInstall`, `Uninstall`, `Freeze`, `UnFreeze`, `Share`,
`Backup`, `Kill`, `ClearCache`, `Suspend`, `UnSuspend`. There is **no** bulk clear-data and **no** bulk
background-restrict; a `ClearData` variant existed, was reachable from nothing, and was removed (see the
comment at `MultiAppAction.kt:22-29` before proposing it again). Sizing a migration from an older list
will over-count.

**Nothing outside `:app` contributes a worker.** `settings.gradle.kts` includes only `:app`, `:bypass`
and `:vm-runtime`; neither of the latter two mentions `androidx.work`; and the three in-house AARs
(`odin`, `asgard`, `thor-extension-api`) carry zero `androidx.work` classes, zero `androidx.work`
manifest entries and no declared components at all. Extensions reach Thor only through the synchronous
`ExtensionOpsProvider`, so no extension can enqueue work.

**No `AlarmManager`, `JobScheduler`, `JobIntentService`, `GlobalScope` or Thor-owned foreground
`Service`** in Thor's own code. (Thor *does* end up using JobScheduler — see the next section.)

> 🪤 **Naming traps.** `BulkFreezeWorkerTest` is not a WorkManager test, and the "worker" inside
> `BulkFreezeRunner` is a coroutine. `BootReceiver` is inert. Grep for `CoroutineWorker` /
> `ListenableWorker`, not for the word *worker*.

---

## What adopting WorkManager cost

Verifiable, and currently invisible from the build file:

- **Three permissions Thor never wrote** ship in the APK. `work-runtime-2.11.2.aar`'s own manifest
  declares `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE` and `RECEIVE_BOOT_COMPLETED`; Thor
  declares only the last of those itself. All three extras are in the merged manifest.
- **A Play Console Foreground Service declaration** for `FOREGROUND_SERVICE_DATA_SYNC` now stands
  between the repo and a `store` release. That overlay lives in `main`
  (`AndroidManifest.xml:416-419`), so it ships in **both** flavours; `follow-ups/README.md:234` (row 23)
  already records it as one of two things blocking a `store` release.
- **Thor ships `androidx.work.impl.background.systemjob.SystemJobService`** (merged manifest `:463-468`),
  so the app *does* use JobScheduler — via WorkManager. The "no JobScheduler" claim above is true of
  Thor's own code and false as a statement about the shipped app.
- On `targetSdk = 37` the system can **time-cap a long `dataSync` FGS**. Thor renders that as a plain
  `Cancelled`, because `WorkInfo.getStopReason()` is read **nowhere** in `app/src`.

### Initialisation

Koin's `workManagerFactory()` (`ThorApplication.kt:227`) — which performs `WorkManager.initialize()` —
installs a `DelegatingWorkerFactory`. There is no `Configuration.Provider`. The manifest
`tools:node="remove"`s `androidx.startup`'s `WorkManagerInitializer` (`AndroidManifest.xml:424-433`) so
there is exactly one initializer; the merged manifest confirms it took effect (`InitializationProvider`
lists only `EmojiCompat`, `ProcessLifecycle` and `ProfileInstaller`).

> ⚠️ **One process, and the seam depends on it.** The merged manifest contains exactly one
> `android:process` attribute — `:root` on `ThorRootService`. `ArchiveKeyHolder`, `JobRegistry` and
> `JobSheetTargets` are process-memory singletons, so the whole seam works only because the worker runs
> in the **same process** as the ViewModel reading them. Give any of these components an
> `android:process` and three side channels break silently.

---

## Inspecting a job on a device

Everything from `beginUniqueWork` inward is verified by reading, not by tests — so on-device inspection
is the only ground truth. WorkManager ships a diagnostics receiver, exported, gated on
`android.permission.DUMP` (merged manifest `:484-493`), which `adb shell` holds:

```bash
# debug build; drop the .debug suffix for a release build
adb shell am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS \
  -n com.valhalla.thor.debug/androidx.work.impl.diagnostics.DiagnosticsReceiver
adb logcat -s WM-DiagnosticsWrkr
```

That dumps the queued, running and recently-completed `WorkSpec`s — chain names, tags, states.

---

## Test coverage — the honest position

**64 JVM tests across six files**, and **not one file in `app/src/test` or `app/src/androidTest`
imports `androidx.work`**:

| File | `@Test`s |
|---|---|
| `data/backup/job/AppArchiveWorkerTest.kt` | 21 |
| `data/backup/job/JobSheetTargetsTest.kt` | 14 |
| `domain/model/ThorJobTest.kt` | 10 |
| `data/backup/job/ArchiveKeyHolderTest.kt` | 8 |
| `data/backup/job/JobRegistryTest.kt` | 6 |
| `data/backup/job/EnqueueFailureWatchTest.kt` | 5 |

There is no `work-testing`, no Robolectric and no mockk on the classpath. What those 64 cover is the
seam's constants, its holders, and the top-level sentence builders that were deliberately hoisted out of
the workers so a JVM test could reach them (`wrongKeyReason`, `restoreFailureReason`, `obbNotice`,
`refusalReason`, `boundedForJobData`, `whenFailed`). What they do **not** cover is every chain-behaviour
claim in this document — those are read from WorkManager's source and confirmed against its bytecode,
and proving them is device-only.

`app/src/androidTest` contains zero `WorkManager`/`Worker` hits of any kind.

---

## Known gaps

| Gap | Detail |
|---|---|
| `THOR_SWEEP_CHAIN` has no producers | The whole sweep half of the seam is dead code awaiting the bulk-actions work. |
| The cross-chain `su` argument | Two chains let a sweep run beside a restore, which the KDoc's own FIFO-session reasoning argues against. Unresolved. |
| Notifications gate silently | A job runs and shows **nothing** when notifications are off; on API 28–32 there is no surface at all. |
| `getStopReason()` unread | A system-killed FGS is reported to the user as an ordinary cancel. |
| Four stale comments | They claim the enqueue is not awaited. It is. |
| `ArchiveRestoreRequest` carries no `Data`-schema KDoc | Its twin does; the rule is enforced by that one comment. |
| Everything past `beginUniqueWork` is desk-verified | No `work-testing`, no instrumented coverage. |

---

## Related

- [`follow-ups/README.md`](../follow-ups/README.md) — row 23 carries the seam's one-line summary and the
  `store`-release blocker.
- [`../../CLAUDE.md`](../../CLAUDE.md) — module layout, gateways, DI rules.
- `ThorJob.kt` and `ThorJobWorker.kt` KDocs are the primary source for chain policy and the
  no-`Result.retry()` rule. Where this document and a KDoc disagree, check the code: seven comments in
  this subsystem were found stale on 2026-08-13.
