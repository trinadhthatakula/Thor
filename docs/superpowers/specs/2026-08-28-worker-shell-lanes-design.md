# Bulk sweep workers and root-shell lanes — design

**Date:** 2026-08-28
**Status:** implementation plan approved on 2026-08-28; implementation tracked by `docs/superpowers/plans/2026-08-28-worker-shell-lanes.md`.
**Scope:** bulk-actions phase 3 and the reported case where archive backup/restore blocks an unrelated
unfreeze.
**Branch:** not started. Implementation must use a topic branch from `dev`, target `dev`, and must not
change `versionCode`.

---

## 1. Problem

A privileged archive backup or restore can leave an unrelated operation, such as unfreezing another
app, apparently pending forever. The Freezer dialog continues playing its looping progress animation
without explaining whether the command is running, queued, cancelled, or abandoned.

The tempting explanation is that WorkManager has one worker thread or that archive work holds a Room
transaction. Neither is true here.

### 1.1 The blocking point is Odin's cached MainShell

The current root path is:

```text
ArchiveBackupWorker / ArchiveRestoreWorker
  -> AppDataArchiveGatewayImpl
  -> SystemRepository.executeShellCommand(...)
  -> RootSystemGateway
  -> singleton ShellRepository
  -> Odin getShellAwait()
  -> cached MainShell FIFO
  -> tar / extract / swap command

Freezer bulk unfreeze
  -> MainViewModel.performCountedFreeze(...)
  -> ManageAppUseCase.forceUnfreeze(...)
  -> SystemRepository
  -> RootSystemGateway
  -> the same singleton ShellRepository
  -> the same MainShell FIFO
```

Odin's `ShellImpl` owns one task deque. Its drain loop does not start the next task until the active
`JobTask.run(...)` returns. One multi-gigabyte `tar` or extraction command is therefore one
non-preemptible queue item. A later `pm enable`, unsuspend, or root-service reset waits behind it.

Thor bounds root *initialisation*, but it does not bound command execution. Odin's coroutine `await()`
is callback-backed, so the waiter does not consume an IO thread; cancelling that waiter also does not
remove the submitted task or terminate its child process. Wrapping the call in `withTimeout` without
changing the shell lifecycle would be incorrect: Thor could report failure and later execute the
supposedly cancelled command.

Relevant current anchors:

- `app/src/main/java/com/valhalla/thor/di/Modules.kt:90` provides one `ShellRepository`.
- `app/src/main/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImpl.kt:214` begins the
  long archive shell boundaries.
- `app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt:1534` reaches the singleton
  shell repository.
- Odin `ShellRepository.exec` calls the cached `getShellAwait()` at
  `../Odin/odin/src/main/java/com/valhalla/superuser/ktx/ShellRepository.kt:78`.
- Odin's FIFO is in
  `../Odin/odin/src/main/java/com/valhalla/superuser/internal/ShellImpl.kt:227`.

This source proves the head-of-line blocking mechanism. Runtime instrumentation is still required to
separate a genuinely wedged command from a merely slow command or a child whose end marker was lost.

### 1.2 WorkManager is a separate serialization layer

Thor currently has three real Workers: archive backup, archive restore, and single-app export. All
three use `THOR_JOB_CHAIN` with `ExistingWorkPolicy.APPEND_OR_REPLACE`.

`THOR_SWEEP_CHAIN` already exists for privilege sweeps, but has no producers. Unfreeze is not currently
a Worker; the Freezer's counted path runs in `MainViewModel.viewModelScope`. Increasing WorkManager's
executor parallelism, adding a second foreground service, or moving unfreeze to another WorkManager
chain would therefore leave the shared MainShell FIFO intact.

The two WorkManager chains and the shell lanes solve different problems:

- WorkManager chains define durable job ordering and process-death behaviour.
- Shell lanes prevent one privileged command class from monopolising unrelated command classes.

Both layers are required.

### 1.3 The endless animation is also a terminal-state bug

`MainViewModel.performCountedFreeze` opens `FreezeLoggerState`, processes apps sequentially, and writes
`isComplete = true` only after the loop. `CancellationException` is deliberately rethrown below it,
and there is no enclosing terminal-state owner. An unexpected throw or cancellation can leave
`isVisible = true` and `isComplete = false` permanently.

`FreezeLoggerDialog` correctly loops while `isComplete == false`; it cannot repair missing lifecycle
state. The UI bug can therefore outlive the shell blockage and can also reproduce without it.

### 1.4 Rejected competing causes

- `@Named("io")` is ordinary `Dispatchers.IO`, not `limitedParallelism(1)`.
- Odin's `await()` suspends through a callback and does not pin an IO thread while queued.
- The archive gateway does not hold a Room transaction around tar/extract.
- WorkManager closes its internal WorkDatabase transaction before invoking the worker body.
- A notification does not require a foreground service. `NotificationManagerCompat.notify` works for
  a `runsForeground = false` sweep when notification permission/settings permit it.

---

## 2. Decisions

| # | Decision | Rationale |
|---|---|---|
| 1 | Keep `THOR_JOB_CHAIN` for byte movers. | Backup, restore, and export should remain serial to bound disk and staging pressure. |
| 2 | Activate `THOR_SWEEP_CHAIN` for replay-safe privilege sweeps. | A short sweep must not wait behind an hour-long archive, while sweeps still need deterministic ordering among themselves. |
| 3 | Sweeps use progress notifications but no foreground service. | They do not need a `dataSync` FGS, wakelock, or Thor's one long-running byte-mover slot. |
| 4 | Root uses three logical lanes: `INTERACTIVE`, `ARCHIVE`, and `SWEEP`. | Archive work must not block either UI actions or sweeps, and a bulk sweep must not fill the interactive FIFO. |
| 5 | `ARCHIVE` and `SWEEP` own lazily created Odin shells; `INTERACTIVE` keeps MainShell. | Odin already supports independent `Shell.Builder.create().build()` instances, each with its own process and FIFO. |
| 6 | Dedicated lanes submit at most one Odin task at a time. | Coroutine waiters remain cancellable before submission, and cancellation never leaves a second queued command that can execute later. |
| 7 | Cancelling an active dedicated-lane command invalidates and closes that shell generation. | Cancelling only the Kotlin continuation is not command cancellation. The next command must use a fresh process. |
| 8 | Same-package archive and mutation operations do not run concurrently. | Restore rewrites package data; enabling, reinstalling, or clearing the same package during it has undefined results. Different packages may proceed on different lanes. |
| 9 | Sweep targets are snapshotted durably before enqueue. | WorkManager can start after process death; it must execute what the user selected, not a later reconstruction of screen state. Package lists can exceed WorkManager `Data`'s 10 KiB limit. |
| 10 | Only replay-safe operations enter WorkManager in this cut. | WorkManager may rerun interrupted work regardless of a worker's returned `Result`. |
| 11 | Force-stop and uninstall stay outside WorkManager for now. | Replaying force-stop can kill an app the user relaunched; uninstall cannot be made exactly-once across the external package manager boundary. |
| 12 | No blanket timeout is placed around archive tar/extract. | Legitimate duration scales with data size. Isolation and real user cancellation solve interference without declaring a valid large archive failed. |
| 13 | A root manager that rejects an extra shell degrades visibly, not silently. | Compatibility must remain, but the old unexplained infinite queue is not an acceptable fallback. |
| 14 | Shizuku and Dhizuku keep their current transports in this cut. | The demonstrated shared-FIFO defect is Odin/MainShell-specific. Their cancellation limits remain documented and device-tested separately. |

---

## 3. Scope

### 3.1 In scope

- Root execution-lane routing and lifecycle.
- Dedicated archive and sweep Odin shell instances.
- Activation of `THOR_SWEEP_CHAIN`.
- A generic sweep Worker and launcher for:
  - freeze;
  - unfreeze;
  - per-app cache clear;
  - reinstall/Fix Store.
- Migration of the Freezer's selected freeze/unfreeze path away from
  `MainViewModel.performCountedFreeze`.
- Durable sweep target snapshots.
- Per-package conflict coordination.
- Cancellation-safe partial result reporting.
- Freezer progress terminal-state repair.
- Lane and queue observability.
- Physical-device validation of concurrent root sessions and active-command termination.

### 3.2 Deliberately out of scope

- Multi-app APK/XAPK export. Its staging and collision rules remain separate from this sweep work.
- Force-stop as a Worker.
- Uninstall as a Worker.
- Share, normal uninstall intents, or other actions requiring an Activity result.
- Whole-device `pm trim-caches`; it is one device-wide command, not a per-package sweep.
- Scheduled or periodic sweeps.
- A global pool of arbitrary root shells.
- Rewriting the archive format to make tar artificially chunkable.
- Changing Shizuku or Dhizuku transport internals merely to mirror the root design.
- Changing WorkManager's global executor.

Explicit bulk Suspend/Unsuspend buttons remain on their current path in this cut. Freeze already
resolves the configured Freezer mode to disable or suspend, and widening the migration before the four
agreed operations are proven would add scope without helping the reported contention.

---

## 4. Execution topology

```text
                                WorkManager ordering

  archive backup/restore/export ── THOR_JOB_CHAIN ──────────────┐
                                                                 │
  freeze/unfreeze/cache/reinstall ─ THOR_SWEEP_CHAIN ───────────┤
                                                                 │
  immediate one-app UI action ───── no Worker ──────────────────┤
                                                                 ▼
                          privilege transport selection
                       Root       Shizuku       Dhizuku
                         │            │             │
                         ▼            └──── existing transports ─┘
                 root command router
                  │       │       │
                  ▼       ▼       ▼
               MAIN    ARCHIVE   SWEEP
             MainShell  owned     owned
                        shell     shell
```

The root lanes are logical API choices, not dispatcher names. Putting two calls on different coroutine
dispatchers while both call `getShellAwait()` still sends both to MainShell and solves nothing.

### 4.1 `INTERACTIVE`

- Existing immediate, user-facing package actions default here.
- Uses Odin's cached MainShell so existing root initialisation and authorization behaviour remain.
- Must not receive archive tar/extract/swap commands or WorkManager sweep commands after migration.
- Existing non-migrated root call sites continue here by default.

### 4.2 `ARCHIVE`

- Used for every root shell phase of archive backup and restore, not merely tar creation.
- Includes listing/verification when it is part of the archive operation, tar creation, extract,
  staged swap, ownership repair, and archive-owned privileged cleanup.
- One process-lifetime, lazily built shell is reused while healthy.
- `THOR_JOB_CHAIN` still serializes archive/export Workers; the lane does not create archive
  parallelism.

Single-app export normally moves APK files without root archive commands. If a future export phase
requires a long root command, it must opt into `ARCHIVE` explicitly rather than inherit it from the
WorkManager chain name.

### 4.3 `SWEEP`

- Used by `PrivilegeSweepWorker` for per-app privileged mutations.
- One sweep Worker runs at a time through `THOR_SWEEP_CHAIN`.
- Root processes one package command at a time on the one sweep shell. The current five-wide
  `BulkFreezeRunner` fan-out provides no root-shell parallelism because MainShell serializes those
  jobs anyway; it only fills the queue ahead of later callers.
- Shizuku/Dhizuku may retain bounded parallelism where their transport supports it, but share the same
  counts, ordering, and cancellation contract at the worker boundary.

---

## 5. Root lane API and ownership

Thor introduces a transport-neutral lane value:

```kotlin
enum class PrivilegeExecutionLane {
    INTERACTIVE,
    ARCHIVE,
    SWEEP,
}
```

The value is threaded explicitly through the privileged command/use-case boundary. Existing call sites
default to `INTERACTIVE`; archive code passes `ARCHIVE`; the sweep executor passes `SWEEP`.

This must not be implemented as a coroutine-context ambient or a dispatcher convention. An explicit
value is visible in tests, survives refactors, and cannot disappear when work moves to another scope.
Shizuku and Dhizuku may ignore the value initially; Root routes it.

Root's router depends on three executors:

- interactive adapter over the existing Odin `ShellRepository`;
- `OwnedRootShellExecutor(ARCHIVE)`;
- `OwnedRootShellExecutor(SWEEP)`.

The dedicated executor wraps Odin's public builder directly. A second `RealShellRepository` is not
sufficient because that implementation always calls `getShellAwait()` and therefore returns to the
cached MainShell.

### 5.1 Dedicated-shell invariants

Each `OwnedRootShellExecutor`:

1. Uses a coroutine `Mutex` before a command is submitted.
2. Builds its shell lazily through `Shell.Builder.create().build()`.
3. Submits exactly one `Shell.Job` while holding the lease.
4. Records a monotonically increasing shell generation.
5. Reuses a live generation after ordinary command completion.
6. Invalidates the generation after transport death, cancellation, or a proven command timeout.
7. Closes only the generation owned by the cancelling operation; a stale cleanup must never close a
   replacement shell already serving a successor.
8. Never automatically retries a mutating command after an unknown transport outcome. The command may
   already have executed.

Waiters blocked on the outer `Mutex` have not entered Odin and are ordinarily cancellable. This avoids
the need to remove queued Odin tasks from dedicated shells because there should be no queued Odin task
to remove.

### 5.2 Cancellation and deadlines

Cancellation has two distinct cases:

- **Waiting for the lane:** cancel the coroutine; nothing has been submitted and no system state can
  change later.
- **Active on a dedicated shell:** mark the generation invalid, close that shell under
  `NonCancellable`, and rethrow cancellation. The next command builds a new shell.

A per-package sweep command keeps the existing 30-second deadline. Unlike the current timeout, expiry
must invalidate the sweep shell so the command cannot execute after the worker counted it as failed.
The worker may then continue with the next package on a fresh generation.

Archive commands do not receive the sweep's fixed deadline. The user-facing cancel action must still
close the active archive generation. Cleanup that suspends runs in `withContext(NonCancellable + io)`
and, when root is needed, uses a fresh archive generation rather than enqueueing behind the generation
being abandoned.

Closing Odin's `ShellImpl` destroys its `su` process, but device validation must prove that the active
`tar`/extract child also terminates on each supported root manager. If the child survives, this design
is not considered implemented: cancellation then needs PID-scoped child termination from a fresh
control shell before cleanup is allowed to claim completion.

### 5.3 Degraded compatibility mode

If `ARCHIVE` or `SWEEP` cannot create an independent root shell:

- mark that lane degraded for the process lifetime;
- route it through MainShell under the same external lane coordinator;
- expose the degraded state to progress UI and logs;
- reject a conflicting interactive submission promptly with a typed busy result instead of placing an
  unexplained command behind a long archive command;
- tell the user which background operation owns the only available root channel.

The fallback preserves backup/restore on restrictive root managers. It does not pretend those devices
support concurrency.

---

## 6. Package conflict coordination

Shell separation permits unrelated packages to proceed concurrently. It must not permit logically
conflicting operations on the same package.

A process-singleton `PackageOperationCoordinator` owns keyed package leases:

- archive backup and restore hold an exclusive package lease for the full logical operation;
- a sweep holds the lease for one package only while that package is being processed;
- an immediate UI mutation tries to acquire the same package lease;
- unrelated package names do not block each other.

The coordinator returns a typed conflict carrying the owning operation class. It does not suspend an
interactive action indefinitely:

- interactive action against a package being backed up/restored: fail fast and show
  “Backup/restore in progress for this app”;
- sweep item against an archived package: wait only for a small documented admission window, then
  count it as busy/unresolved and continue;
- archive requested while a short mutation owns the package: bounded wait, then fail before staging
  begins.

The lease is process-local by design. Thor runs these components in one process; process death removes
all in-process owners, WorkManager restarts durable work, and the root shell processes die with their
owning process. Device tests must verify the last statement.

A package lease coordinates Thor's own work, not external package-manager or user activity. Every
operation must still validate current package state immediately before mutation.

---

## 7. Durable sweep requests

### 7.1 Why WorkManager `Data` is not the target store

The selected package list can exceed WorkManager `Data`'s 10 KiB limit. Passing `AppInfo` objects is
also neither stable nor necessary. Reconstructing the list when the Worker eventually starts can act
on packages the user never selected or omit ones they did.

The launcher therefore snapshots a small operation record and ordered package-name rows in Room before
enqueue. A real database migration is mandatory; debug-only destructive fallback is not part of the
design.

Conceptually:

```text
sweep_request
  request_id
  work_id
  operation
  freezer_mode?       // resolved at enqueue when relevant
  user_id
  source_surface
  created_at

sweep_target
  request_id
  ordinal
  package_name
```

`WorkRequest.inputData` carries only `request_id`. No display labels, icons, or mutable `AppInfo`
snapshots are persisted.

The current `BulkScope` contains only `Watchlist` and `Profile`; it does **not** contain a Selection
variant. This design does not pretend otherwise. Every surface resolves its scope to a package-name
snapshot before enqueue, including the Freezer's current selected-app list.

### 7.2 Request lifecycle

1. Resolve privilege and validate that the operation is supported.
2. Resolve and validate targets once.
3. Persist the request and target rows transactionally.
4. Enqueue the Worker on `THOR_SWEEP_CHAIN` and await the enqueue `Operation`.
5. If enqueue is rejected, delete the snapshot and return a launch failure.
6. Worker loads the request by id. Missing or malformed input is a permanent failure, never
   `Result.retry()`.
7. A normally terminal Worker removes or expires its input after its UI/result consumers no longer
   need it.
8. A launch-time janitor removes snapshots whose recorded WorkManager id is terminal or absent and
   whose retention window has elapsed. This covers a pending Worker cancelled before `doWork()` ever
   runs, including cancellation through WorkManager's notification `PendingIntent`.

Target normalization is deterministic before the transaction: reject blank package names, remove exact
duplicates while preserving the first occurrence, then sort the remaining package names by Kotlin's
locale-independent natural `String` order. The persisted `ordinal` records that canonical order. Package
names are not case-folded or otherwise rewritten. As a result, two surfaces that resolve the same package
set do not create different requests merely because their source collection iterated differently.

Equivalent pending requests may coalesce only when operation, resolved mode, user id, and the complete
canonical target snapshot match. Opposite operations are not silently collapsed.

`APPEND_OR_REPLACE` links queued WorkRequests as dependencies. Cancelling the active sweep therefore also
cancels its queued successors; this is WorkManager's documented dependency behaviour, not an
implementation detail Thor can hide. The first cut makes that boundary explicit: the notification and UI
action are labelled as cancelling the **sweep queue**, every affected request transitions to a terminal
cancelled-before-start or cancelled-with-partial-counts state, and the janitor retains its snapshot long
enough for result consumers. It does not promise per-request cancellation while keeping later dependent
requests alive. If that product requirement is added later, replace the dependency chain with a durable
Room-backed dispatcher rather than attempting to patch cancellation propagation around
`APPEND_OR_REPLACE`.

### 7.3 Operations in this cut

A new sweep operation type is separate from the existing `BulkOp`. `BulkOp` currently means only
freeze/unfreeze and participates in `BulkFreezeRunner`'s mode and coalescing rules; widening it to
unrelated package operations would make those rules misleading.

Worker-safe operations:

| Operation | Replay rule |
|---|---|
| Freeze | Reapplying the desired disabled/suspended final state is safe. |
| Unfreeze | Reapplying enable + unsuspend is safe; empty/unknown unsuspend results remain failures, not success. |
| Per-app cache clear | Repeating clear-cache is safe, though counts describe final attempts rather than unique side effects. |
| Reinstall/Fix Store | The existing reinstall form is treated as converging on the installed state; its command and postcondition are verified before migration. |

Deferred operations:

| Operation | Why it stays outside WorkManager |
|---|---|
| Force-stop (`Kill`) | A process can restart between executions; replay can kill work the user started after the first attempt. |
| Uninstall | A crash can occur after PackageManager commits but before Thor checkpoints it. Mark-before risks skipping an unexecuted uninstall; mark-after risks replay. |

Exactly-once cannot be manufactured by a Room flag around an external system mutation. Moving either
deferred operation requires an explicit product decision about at-most-once versus at-least-once
behaviour.

---

## 8. Sweep Worker lifecycle

One generic `PrivilegeSweepWorker` owns the durable lifecycle; operation-specific functions own the
per-package action.

- Append one `PRIVILEGE_SWEEP` value to `ThorJobKind`. Never insert or reorder existing values because
  notification and `PendingIntent` ids derive from ordinals.
- `runsForeground = false`.
- Publish `ThorJobStage.ACTING` with processed/total counts.
- Use the existing low-importance background-job notification channel and WorkManager cancel action.
- Run root operations sequentially on `SWEEP`; use bounded transport-specific concurrency only where
  Shizuku/Dhizuku can actually execute independently.
- Do not return `Result.retry()` for an operation-level failure.
- Check cancellation before acquiring each package lease and before starting each package action.

The Worker maintains:

```text
total
succeeded
failed
busy
unresolved = total - succeeded - failed - busy
```

WorkManager discards a cancelled Worker's output `Data`. Partial counts therefore use the existing
`ThorJobWorker.noteResult` seam, which posts from the base worker's `finally`. The subclass records the
latest truthful sentence before cancellation escapes. A cancelled run reports what completed and how
many targets were not attempted; it never says the whole operation succeeded.

If Android kills the process, `finally` is not guaranteed. WorkManager may rerun the persisted request.
That is why every operation admitted to this Worker must be replay-safe.

### 8.1 Notification permission gate

A non-FGS sweep without notifications has no honest long-running surface or cancel action. Before
enqueue, the launcher checks notification capability:

- when notifications are available, enqueue normally;
- when unavailable, do not start hidden durable work; return a typed `NotificationsRequired` result so
  the screen can offer notification settings.

This closes the known API 28–32 hole where blocked notifications otherwise leave no visible running
surface at all. The API 33+ Task Manager Stop button is not a substitute for the notification's
per-work cancel action.

---

## 9. UI and progress ownership

The Worker/WorkInfo state becomes the lifecycle source of truth for migrated screen actions.

The Freezer's current selected freeze/unfreeze path no longer owns a free-running
`viewModelScope` loop. It launches a durable sweep, observes progress while on screen, and renders the
same compact count UI from worker progress.

Every path is terminal:

- succeeded: complete and auto-dismiss after the existing delay;
- completed with failures/busy items: complete and remain visible until Close;
- cancelled: complete with partial/unresolved counts;
- launch failure: dismiss running state and show the launch reason;
- unexpected observer failure: dismiss running state and emit an error;
- process recreation: reconnect by WorkRequest/request id instead of restarting the operation.

Until migration is complete, `performCountedFreeze` itself receives a `try/finally` terminal-state
repair so an exception or cancellation cannot strand `isComplete = false`. This guard is not the
worker migration; it is the independent UI bug fix.

The app-list and main-screen implementations currently duplicate several bulk loops. Migrated
operations call one launcher/use-case seam rather than retaining two behavioural copies.

---

## 10. Observability

Every privileged command boundary logs a structured, redacted event containing:

- logical lane;
- shell generation/instance id when Root is active;
- WorkRequest and sweep request ids where applicable;
- operation class;
- package name when the command is package-scoped;
- enqueue timestamp;
- actual start timestamp;
- end timestamp;
- queue wait and execution duration;
- terminal reason: success, exit code, cancellation, timeout, transport death, package busy, or
  degraded fallback.

Raw commands, archive passphrases, user-selected filesystem paths, and command output are not logged.
A stable command-class label such as `archive.tar`, `archive.extract`, or `package.enable` replaces the
raw command text.

This is local diagnostic logging, not analytics or telemetry.

---

## 11. Error model

Lane and coordination failures are typed before being mapped to `Result.failure`/UI text:

- `PackageOperationBusy(owner)`;
- `ShellLaneUnavailable(lane, cause)`;
- `ShellLaneDegraded(lane)`;
- `ShellTransportDied(lane, generation)`;
- `ShellCommandTimedOut(lane, operation)`;
- `ShellCommandCancelled(lane, operation)`;
- `SweepInputMissing(requestId)`;
- `NotificationsRequired`.

A root transport death is an **unknown command outcome**, not automatically an exit-code failure. Thor
must not retry a mutating command merely because no end marker arrived. Operation-specific postcondition
checks may classify final state, but the generic shell router cannot.

Errors are mapped at the presentation/worker boundary. The lane implementation never creates Android
UI text.

---

## 12. Testing

### 12.1 JVM tests

- A blocked fake archive shell does not delay interactive or sweep executors.
- A blocked sweep shell does not delay interactive execution.
- Commands in one dedicated lane remain serial.
- Cancelling a waiter before lease acquisition submits no Odin job.
- Cancelling an active command closes only its shell generation.
- A stale cleanup cannot close a replacement generation.
- Transport death is not automatically retried.
- Root routes each operation to its explicit lane; Shizuku/Dhizuku preserve existing routing.
- Package leases block only matching package names.
- Interactive same-package conflicts fail promptly and name the owner.
- Request snapshots round-trip in order and survive process recreation.
- Oversized selections never enter WorkManager `Data`.
- Missing request input is a permanent failure.
- Cancelled sweep summaries include completed and unresolved counts through `noteResult`.
- `ThorJobKind.PRIVILEGE_SWEEP` is appended and existing notification ids do not change.
- Force-stop and uninstall cannot be encoded as worker sweep operations.
- Freeze/unfreeze target filtering preserves UAD safety for freeze and the escape path for unfreeze.
- Freezer progress reaches a terminal state on success, ordinary failure, cancellation, and unexpected
  exception.

Use fake command executors and pure state reducers so JVM tests do not initialize Shizuku or Android
Binder. Injecting a dispatcher alone does not make static Shizuku code unit-testable.

### 12.2 WorkManager/device tests

- `THOR_JOB_CHAIN` and `THOR_SWEEP_CHAIN` can run simultaneously.
- Two sweeps remain ordered.
- A cancelled sweep leaves truthful partial counts and no ongoing notification.
- A process-killed replay converges for every admitted operation.
- A pending cancelled Worker has its persisted request removed by the janitor.
- Notifications-disabled launch is refused visibly.
- Rotation/process recreation reconnects the Freezer progress UI without launching twice.

### 12.3 Root-manager device gate

Test at least the available Magisk, KernelSU, and APatch configurations; record unavailable coverage
rather than assuming it:

1. Establish MainShell, then create archive and sweep shells.
2. Verify no repeated authorization prompt.
3. Verify uid, mount namespace, SELinux access, and target paths match the existing MainShell where
   required.
4. Hold a deliberately slow archive command on `ARCHIVE`.
5. Confirm an unrelated interactive unfreeze starts and completes on `INTERACTIVE`.
6. Confirm a sweep starts and progresses on `SWEEP`.
7. Confirm a same-package mutation is refused with a visible busy result.
8. Cancel archive work and prove the `tar`/extract child is gone, not merely the Kotlin waiter.
9. Confirm partial staging/plaintext cleanup completes after cancellation.
10. Confirm closing/recreating `ARCHIVE` or `SWEEP` does not kill MainShell or the other lane.
11. Kill Thor during each Worker class and verify restart/replay behaviour.
12. Exercise the degraded path with a fake or manager configuration that rejects another shell.

The shell-lane change does not ship on root based only on JVM tests. Child termination, namespaces, and
root-manager policy are physical-device facts.

---

## 13. Delivery sequence

This is sequencing, not the implementation plan:

1. Add failing tests and structured lane observability.
2. Repair Freezer progress terminal-state ownership independently.
3. Add the explicit lane value, root router, dedicated executors, and package coordinator.
4. Route all archive root command phases to `ARCHIVE`; device-test archive versus interactive work.
5. Add durable sweep request storage and its real Room migration.
6. Add `PrivilegeSweepWorker`, notifications, and launcher on `THOR_SWEEP_CHAIN`.
7. Migrate freeze/unfreeze first and device-test process death/cancellation.
8. Migrate per-app cache clear and reinstall after their postconditions are verified.
9. Update `docs/workers/README.md` to replace the now-resolved cross-chain shell warning and to list
   actual sweep producers.
10. Run JVM tests with `--rerun-tasks`, parse totals from JUnit XML, then run the full applicable lint
    and build matrix.

No implementation commit mixes the Odin/root-lane change with a `versionCode` bump or release notes.

---

## 14. Risks and safeguards

| Risk | Safeguard |
|---|---|
| Root manager serializes or rejects extra `su` processes. | Device gate plus explicit degraded mode; never silently enqueue interactive work behind archive work. |
| Killing `su` leaves `tar` alive. | Prove child death on device; otherwise add PID-scoped termination before claiming cancellation. |
| Cancellation cleanup targets a replacement shell. | Generation-token ownership check. |
| Same package mutates while restore rewrites its data. | Package-scoped exclusive coordinator and immediate state revalidation. |
| Worker restarts repeat side effects. | Admit only replay-safe operations; no force-stop or uninstall. |
| Selected package list exceeds WorkManager `Data`. | Durable Room snapshot; `Data` carries only request id. |
| Cancelled output disappears. | `noteResult` from the Worker's `finally`, with partial/unresolved counts. |
| Notification-disabled sweep becomes invisible. | Refuse durable launch and return `NotificationsRequired`. |
| Five-wide root fan-out fills another FIFO. | One in-flight command on the dedicated root sweep shell. |
| Raw commands leak paths or secrets through logs. | Structured command-class labels and redaction. |
| UI spinner outlives the operation. | Worker state as lifecycle truth plus a terminal `finally` in the legacy path. |
| A transport error is retried after the command may have committed. | Unknown outcome is terminal until an operation-specific postcondition proves the final state. |

---

## 15. Acceptance criteria

The design is implemented only when all of the following are true:

- A long root archive command cannot occupy the interactive or sweep shell process.
- An unrelated app can be unfrozen while backup/restore is active.
- A same-package conflict is refused or bounded with an explicit user-visible reason.
- Cancelling a dedicated-lane command stops the underlying child process on validated devices.
- No cancelled queued command executes later.
- Sweeps persist across Activity recreation and ordinary process death.
- Sweeps do not use a foreground service.
- Notification-disabled users are not given invisible, uncancellable durable work.
- Partial cancellation counts remain truthful.
- Force-stop and uninstall remain outside WorkManager.
- Existing archive jobs stay serialized on `THOR_JOB_CHAIN`.
- Sweep jobs serialize independently on `THOR_SWEEP_CHAIN`.
- The Freezer progress UI reaches a terminal state on every path.
- Root, Shizuku, and Dhizuku retain their supported operation behaviour outside the intended routing
  change.
