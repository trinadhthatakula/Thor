# Workers, sweep queues, and root shell lanes

This document describes the background-work boundary that is implemented in Thor. It is intentionally
narrow: being a long-running or multi-app operation does not by itself make something a WorkManager
job.

**Last verified:** 2026-09-02 on `feat/worker-shell-lanes`.

## At a glance

Thor has two independent serial WorkManager chains:

| Unique work name | Work on the chain | Execution shape |
|---|---|---|
| `THOR_JOB_CHAIN` (`"thor.job.chain"`) | Single-app archive backup, single-app archive restore, and single-app `.apk`/`.apks`/`.xapk` export | Byte-moving jobs; foreground `dataSync` work |
| `THOR_SWEEP_CHAIN` (`"thor.sweep.chain"`) | Freeze, unfreeze, per-app cache clear, and verified Fix Store over a resolved selection | Durable privilege sweeps; non-foreground workers with visible progress notifications |

Both chains use `ExistingWorkPolicy.APPEND_OR_REPLACE`. Work is serial within its own chain, but an
archive/export job and a privilege sweep may run at the same time. The split prevents a short sweep
from waiting behind a large archive and prevents queued sweeps from delaying byte-moving work.

The root transport has three execution lanes:

| Lane | Normal root transport | Typical callers |
|---|---|---|
| `INTERACTIVE` | Odin `MainShell` | Direct, user-facing actions |
| `ARCHIVE` | An owned, reusable dedicated root shell | Archive backup and restore commands |
| `SWEEP` | A different owned, reusable dedicated root shell | Items in a privilege sweep |

The WorkManager chain and the root shell lane are separate concepts. A chain controls job ordering;
a lane controls root-command transport. Shizuku and Dhizuku keep their own gateway behavior and do
not use Odin's root shell lanes.

## The byte-moving job chain

`THOR_JOB_CHAIN` contains exactly three operations:

| Kind | Worker | Producer |
|---|---|---|
| `ARCHIVE_BACKUP` | `ArchiveBackupWorker` | `ThorJobLauncher.startBackup` |
| `ARCHIVE_RESTORE` | `ArchiveRestoreWorker` | `ThorJobLauncher.startRestore` |
| `APP_EXPORT` | `AppExportWorker` | `ExportJobLauncherImpl.startExport` |

All three requests receive a package-specific work tag even though they share one unique chain. The
tag lets a screen detect or reattach to work for one package without allowing two byte-heavy jobs to
run concurrently.

Archive backup and restore keep derived encryption keys only in `ArchiveKeyHolder`, in process
memory. A process-death rerun therefore fails before touching archive contents; WorkManager does not
make those two operations durable across process death. App export carries no secret key and can run
again in a new process. Before it stages files, it waits for `LaunchSweepBarrier` so the application
startup cleanup cannot delete its staging directory while it is being written.

These jobs run through `ThorJobWorker` as foreground `dataSync` work. They publish in-memory progress
to `JobRegistry`, throttle notification updates, and return bounded error/warning data. The archive
and export cancel actions remain per-work cancellation; they do not cancel every item on
`THOR_JOB_CHAIN`.

`ThorJobKind` is append-only. Notification ids and `PendingIntent` request codes are derived from its
ordinal, so inserting or reordering entries can retarget an existing notification after an app update.

## The durable privilege-sweep chain

`THOR_SWEEP_CHAIN` is live. `DefaultPrivilegeSweepController` creates a
`PrivilegeSweepWorker`, persists the request, and enqueues it on that chain. The supported operations
are the four members of `PrivilegeSweepOperation`:

- `FREEZE`, using the freezer mode resolved before enqueue;
- `UNFREEZE`, restoring either disabled or suspended targets to active;
- `CLEAR_CACHE`, for each selected app rather than the whole-device trim command;
- `REINSTALL`, the Fix Store path, including its postcondition verification.

### Resolve first, then persist

`PrivilegeSweepTargetResolver` turns a mutable screen, profile, watchlist, tile, or launcher selection
into an immutable, normalized package list before enqueue. It also records the current Android user
and resolves the freezer mode at that boundary. The request, target order, source associations,
WorkManager id, counts, and terminal state are stored in Room.

Equivalent active requests are coalesced rather than appended twice. Source surfaces can reconnect to
the retained request. Terminal snapshots are retained for 24 hours so a recreated screen can still
show the outcome.

The Room-to-WorkManager handoff is serialized by `PrivilegeSweepProcessGate`. A failed enqueue removes
the new snapshot. `PrivilegeSweepReconciler` repairs nonterminal Room state from WorkManager state and
then prunes expired terminal snapshots.

### Execution and states

`PrivilegeSweepRunner` processes the persisted targets in their stable order. Each item is recorded as
one of:

- succeeded;
- failed;
- busy because another operation owns that package.

Anything not recorded at a terminal interruption is unresolved. The UI derives these lifecycle states
from Room plus WorkManager:

- `QUEUED`
- `RUNNING`
- `SUCCEEDED`
- `PARTIAL`
- `CANCELLED`
- `FAILED`
- `OBSERVER_FAILURE`

A successful run requires every target to have been processed with no failed or busy result. Other
completed mixes are partial. An observer failure is deliberately distinct from an execution failure:
it says Thor can no longer establish the result and the user should inspect the affected apps.

The sweep worker uses `ThorJobWorker` but sets `runsForeground = false`. It still posts a progress
notification through `NotificationManagerCompat`, including a cancel action, but does not spend the
app's foreground-service `dataSync` slot. Sweep launch is rejected if Thor cannot keep that visible
notification surface available.

### Cancellation means the whole sweep queue

The sweep notification and active progress dialog both say **Cancel queue**. They do not cancel only
the currently displayed request.

`SweepQueueCanceller` performs cancellation in this order while holding the process gate:

1. mark every nonterminal sweep snapshot `CANCELLED` in Room;
2. call `WorkManager.cancelUniqueWork(THOR_SWEEP_CHAIN)`;
3. wait for WorkManager's cancellation operation to settle;
4. release the gate.

The critical section is non-cancellable once it begins, so a launch or reconciliation cannot observe a
half-cancelled queue. Completed counts remain in Room; unattempted targets remain unresolved. The
worker's outcome notification reads those persisted counts because WorkManager discards a cancelled
worker's returned `Result`.

## Root shell lane routing

`RootCommandRouter` routes each `RootCommand` from its `PrivilegeExecutionContext`:

- `INTERACTIVE` goes to `MainShellCommandExecutor`, which uses Odin `MainShell`.
- `ARCHIVE` goes to the `@Named("archive")` `OwnedRootShellExecutor`.
- `SWEEP` goes to the `@Named("sweep")` `OwnedRootShellExecutor`.

The two owned executors are separate objects and open separate Odin shell sessions. Each keeps one
healthy session, serializes commands within that lane, and owns that session's cleanup. Cancellation
or timeout invalidates exactly the generation that ran the command. A transport death after a session
was acquired fails that command and discards the dead session; it is not replayed invisibly.

This replaces the old assumption that every root operation necessarily queues through one
`MainShell`. In the normal isolated mode, archive, sweep, and interactive root commands have separate
transports.

### Degraded fallback is explicit

If an archive or sweep lane cannot open its dedicated shell, `RootCommandRouter` marks that lane
`DEGRADED` for the rest of the process and routes it through coordinated `MainShell`. It does not keep
retrying a known-unavailable dedicated lane for every package.

`RootFallbackCoordinator` gives degraded background work an exclusive lease on `MainShell`:

- degraded archive/sweep commands wait until the fallback is available;
- an interactive command never waits behind a degraded background command;
- if the fallback is already leased, interactive admission fails immediately with
  `ShellLaneBusy(owner)` so the UI can report a busy result instead of appearing frozen.

`DefaultRootLaneStatusSource` records lane mode, active command class, and fallback ownership. Sweep
status exposes the SWEEP lane's degraded state and the progress surfaces display it. This is a visible
degraded mode, not silent serialization through `MainShell`.

## Same-package coordination

Separate chains and shell sessions allow unrelated work to overlap; they do not permit conflicting
mutations of the same package.

`DefaultPackageOperationCoordinator` owns a process-wide lease per package. Archive backup/restore,
sweep items, and direct mutations acquire that lease through their use cases. Admission timeouts are:

| Lane | Package admission |
|---|---|
| `INTERACTIVE` | immediate (`0`) |
| `SWEEP` | 2 seconds |
| `ARCHIVE` | 5 seconds |

A prompt action therefore reports `PackageOperationBusy` immediately when the same package is already
owned. A sweep waits briefly and records the item as busy if it still cannot enter. An archive waits
longer but still fails rather than blocking indefinitely. Different packages are not globally locked.

This package lease is independent of degraded `MainShell` coordination. A caller can be rejected
because the package is busy even when its shell lane is isolated, or because `MainShell` is leased even
when the package itself is free.

## Operations deliberately outside these chains

The following remain direct operations or ordinary coroutines and must not be described as sweep
workers:

- force-stop;
- uninstall;
- whole-device cache trim (`clearAllCaches`), distinct from per-app cache clear;
- share;
- explicit suspend and unsuspend actions;
- multi-app export (the `BackupRunner`/"backup all" path).

Single-app export is the exception to the last item: it is `APP_EXPORT` on `THOR_JOB_CHAIN`.

Force-stop and uninstall are intentionally excluded from durable sweeps because an interrupted worker
may be re-run by WorkManager, and a second pass can act on a selection whose state was changed by the
first pass. Adding one of these requires a persisted per-target resume contract, not just another enum
arm.

Explicit suspend/unsuspend remain distinct from sweep freeze/unfreeze. A freeze sweep may use suspend
mode because that mode was resolved into its snapshot; this does not move the separate explicit
suspend actions onto WorkManager.

Component-control **Restore all** is also not a privilege sweep. It restores all component overrides
recorded by Thor across packages for the current `thorUserId`. It is cross-app but single-Android-user,
not device-wide.

## Enqueue and retry rules

`enqueueUniqueJob` awaits WorkManager's `Operation`. A synchronous rejection or an asynchronous
failure returns no accepted work id, and sweep launch rolls back the Room snapshot. Cancelling only
the coroutine that is waiting for enqueue does not cancel work already handed to WorkManager.

`APPEND_OR_REPLACE` does not pre-empt current work. It appends behind a live chain and replaces a chain
whose leaf is failed or cancelled so that a terminal predecessor cannot wedge future requests.

No `ThorJobWorker` subclass returns `Result.retry()`. That does not remove the requirement to tolerate
a process-death rerun: WorkManager can rerun interrupted work regardless of the result the old process
would have returned. The archive jobs fail closed without their in-memory key; export and the four
accepted sweep operations are designed around replay-safe effects and persisted state.

## Notifications and progress

All kinds share the `thor.jobs` low-importance channel. `ThorJobNotifications` owns progress rows,
cancel actions, transient outcome rows, ids, and the one-second notification throttle.
`JobRegistry` carries high-frequency in-process progress; WorkManager progress data is intentionally
not written on every byte or item.

Byte-moving jobs call `setForeground`. Sweeps post the same style of ongoing row without starting a
foreground service. A sweep result notification is assembled from the persisted succeeded, failed,
busy, and unresolved counts, including cancellation paths.

Notification capability is checked before a sweep is persisted. A permission or channel revocation
that races a running job is caught so the operation is not turned into a failure, but it can still
remove the user's visible progress surface for the remainder of that run.

## Initialization and boundaries

Koin's `workManagerFactory()` initializes WorkManager with the generated worker factory. Thor removes
AndroidX Startup's default `WorkManagerInitializer`; it does not implement
`Configuration.Provider`.

Workers run in Thor's main application process. Several parts of the design are process-memory
singletons (`ArchiveKeyHolder`, `JobRegistry`, `JobSheetTargets`, `LaunchSweepBarrier`, and root lane
status), so moving a worker to another Android process would break those contracts.

Nothing in `:bypass` or `:vm-runtime` contributes a worker. Extensions call Thor through their bridge;
they do not enqueue Thor work directly.

## Change checklist

Before adding or moving an operation:

1. Decide whether it moves bytes (`THOR_JOB_CHAIN`), is a replay-safe privilege sweep
   (`THOR_SWEEP_CHAIN`), or should remain direct.
2. Prove behavior after process death; banning `Result.retry()` is not enough.
3. Select the correct `PrivilegeExecutionLane` for every root command.
4. Use the package-operation coordinator for package mutations; do not replace it with a global lock.
5. Persist every value a sweep needs before enqueue. Do not make a worker reconstruct a mutable screen
   selection.
6. Await the enqueue `Operation` and define rollback for a rejected handoff.
7. Add user-visible queued, running, terminal, degraded, busy, observer-failure, and cancellation copy
   to all shipped locales with matching positional placeholders.
8. Keep cancellation wording honest: per-work for archive/export, whole-queue for sweeps.
9. Keep `ThorJobKind` append-only and add exhaustive notification title/icon handling.
10. Validate JVM tests, both lint variants, and a resource/build compile gate.

## Useful entry points

- `domain/model/ThorJob.kt` — chain constants, job kinds, and progress stages.
- `domain/model/PrivilegeExecution.kt` — lane, timeout, busy, and degradation contracts.
- `domain/model/PrivilegeSweep.kt` — sweep operations, sources, states, and retention.
- `data/backup/job/ThorJobLauncher.kt` — archive enqueue and the shared enqueue helper.
- `data/backup/job/ExportJobLauncherImpl.kt` — single-app export producer.
- `data/backup/job/ThorJobWorker.kt` — common lifecycle, notification, and result behavior.
- `data/freezer/DefaultPrivilegeSweepController.kt` — durable sweep handoff and observation.
- `data/freezer/PrivilegeSweepWorker.kt` — per-target execution and terminalization.
- `data/freezer/SweepQueueCanceller.kt` — whole-chain cancellation.
- `data/repository/RoomPrivilegeSweepStore.kt` — persisted sweep snapshots and counts.
- `data/gateway/root/RootCommandRouter.kt` — lane routing and degradation.
- `data/gateway/root/OwnedRootShellExecutor.kt` — dedicated shell ownership.
- `data/gateway/root/RootFallbackCoordinator.kt` — coordinated MainShell fallback.
- `data/privilege/DefaultPackageOperationCoordinator.kt` — same-package exclusion.
