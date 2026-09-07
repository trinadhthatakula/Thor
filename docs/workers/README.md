# Typed service queues and root shell lanes

This document describes the background-work boundary implemented on `feat/worker-shell-lanes`.
WorkManager remains for compatibility with previously persisted work; **new queue submissions use
Room-backed foreground services, not WorkManager chains**.

**Source mapping updated:** 7 September 2026. Source behavior is not device-acceptance evidence; see
[service-queue-progress.md](service-queue-progress.md) for current validation and open gates. The
[binding design](../superpowers/specs/2026-09-03-privilege-action-service-design.md) also includes
requirements that must not be mistaken for completed producer migrations.

## At a glance

| Durable queue | Executor / Android foreground-service type | Supported work |
|---|---|---|
| Data | `DataSyncService` / `dataSync` | Archive backup, archive restore, app export, durable share preparation |
| Privilege | `PrivilegeSweepService` / `specialUse` | Freeze, unfreeze, per-app cache clear, verified Fix Store |

Each queue has one active consumer and its own durable FIFO sequence. The queues may advance
concurrently; they do not share a global execution order. Services are non-exported, run in Thor's
application process, and do not bind the UI to their lifetime.

Three boundaries are deliberately separate:

- **Room queues** determine durable admission, ordering, claims, results and recovery.
- **Package-operation leases** prevent conflicting operations on the same package.
- **Root shell lanes** isolate root-command transport. Shizuku and Dhizuku do not use Odin lanes.

An Intent carries a task UUID wake signal, not the task's payload or authoritative state. A successful
service-start request is not proof that foreground promotion or execution succeeded.

## Current producers and deliberate exceptions

| Operation/path | Current producer | Execution |
|---|---|---|
| Archive backup/restore | `ThorJobLauncher` through `DataTaskAcceptance` | Durable data queue |
| Single-app export | `ExportJobLauncherImpl.startExport` | Durable data queue |
| Bulk share preparation | `ShareSubmissionCoordinator` → `ShareTaskLauncherImpl` | Durable data queue |
| Supported privilege selection | `DefaultPrivilegeSweepController` | Durable privilege queue |
| Single-app quick share | `ShareAppUseCase` | Direct foreground path |
| Multi-app “Backup”/export | `BackupRunner.start` → `BackupAppsUseCase` | Process-owned coroutine, not a durable service producer |

Do not describe the design's planned single/multi-export convergence as completed while that last
producer remains direct. A supported operation name also does not imply that every single-app quick
action with that name goes through a queue.

Other paths remain outside these typed queues: force-stop, uninstall, clear-data, explicit
suspend/unsuspend, whole-device cache trim, ordinary installer streaming, auto-freeze, auto-reinstall,
and component-control Restore all. Restore all concerns Thor-recorded component overrides across
packages for the current Android user, not every user on the device.

Do not use bulk export/share or automatic freeze/reinstall as justification for `specialUse`.
Byte-moving durable work belongs to `dataSync`; the privilege service has the explicit operation
allowlist above.

## Admission and Room authority

Data producers persist through `DataTaskAcceptance`; privilege producers resolve their selection and
persist through `DefaultPrivilegeSweepController`. Sweep resolution fixes package order, Android
user, relevant freezer mode and source/profile associations before execution. Equivalent active
sweep submissions can coalesce.

The order is **persist, then request a service wake**. Data admission explicitly protects the
insertion/wake handoff from a submitting caller's cancellation. Sweep surface launchers use
process-owned execution; the sweep controller itself serializes admission but does not provide that
same cancellation-shielded handoff. Dismissing a screen does not withdraw accepted work. A rejected
start retains actionable durable work, such as `START_BLOCKED` or `START_BLOCKED_NOTIFICATION`; it is
not the old enqueue-failure rollback model.

Queue sequence is assigned durably within each queue. Claims use sequence with a stable UUID
tie-breaker; nested items/targets use their own stable ordinals. Only runnable work participates in
claiming. An authentication-blocked backup does not prevent a later export from running. When the
backup becomes runnable again, its original sequence is preserved, but it does not pre-empt the
currently active task.

Room persists task/request and item/target state. Session identity, claim tokens and leases fence
ownership-sensitive writes. In-memory registries and service instances support execution but cannot
replace that authority. Do not infer success from absent callbacks, missing observer data, lease age
alone or process death.

## Foreground lifecycle and notifications

Initial foreground promotion precedes coordinator execution. Repeated wakes must not create repeated
consumers. Successful service admission uses sticky restart behavior and generation-aware shutdown;
that is not a guarantee of scheduling, reboot auto-resume or restart after force-stop.

`DataSyncService` handles platform timeout by stopping new claims and initiating bounded interruption
and settlement. Startup/legacy-drain barriers must settle before cleanup-sensitive data execution.
Final-empty shutdown must account for runnable and settling work, including a wake racing shutdown.

Separate low-importance channels are used:

- `thor.jobs.data` for data operations;
- `thor.jobs.privileged` for privilege operations.

Notification capability is typed, not a single permission Boolean:

1. On API 33+, denied `POST_NOTIFICATIONS` does not itself prohibit foreground execution. Android's
   Task Manager and Thor's in-app Queue/logger remain relevant visibility/cancellation surfaces.
2. A valid but user-blocked channel is a product start block for new tasks. Retain actionable state
   rather than beginning work without the promised surface. A later channel revocation does not
   rewrite an operation's execution result.
3. Missing/invalid notification infrastructure or failed initial foreground promotion is a start
   failure. Never continue execution as an unforegrounded service.

Active-task content and cancel PendingIntents are immutable and task-specific. Active-task content
opens that UUID's Queue detail; cancel persists cancellation before signalling the owner. Initial
preparing notifications instead show Starting with a generic Home destination. Ready-share content
opens `ShareHandoffActivity` for foreground validation and handoff, not Queue detail. Terminal
notifications are best-effort, unlike required initial foreground promotion.

Each claimed active-task notification shows operation, coarse progress and other queued-task count.
The count uses the existing Queue projection, excludes the active task and action-required/terminal
rows, and includes an older task resumed behind the active task rather than filtering solely by
greater queue sequence. Nested items/targets are not separate queued tasks. The service-owned
observer conflates updates; claiming a task immediately publishes the correct UUID. Cancellation
renders the localized **Stopping** state while command/IO cleanup unwinds, without dropping operation,
progress or queued count. Identity checks prevent delayed updates from restoring an old task's
notification after handover or shutdown. See the progress document for validation scope.

## Execution, cancellation and recovery

`DataSyncCoordinator` and the privilege executor drain their respective durable stores. Task/item or
request/target settlement preserves completed results; one item's outcome is not permission to
invent the outcome of unresolved items.

**Cancellation affects one task/request**, not the entire privilege queue. Room records cancellation
before the active owner is signalled. The UI can show `STOPPING` until settlement; cancelling does not
promise immediate rollback or undo already completed effects. A later queued task survives
cancellation of the current one.

Recovery combines durable ownership with process/session knowledge. A known-live owner is not stolen
merely because its lease appears old. Important recovery distinctions include:

- Archive keys remain process-memory-only. Lost authentication yields `WAITING_FOR_AUTH`, not a
  persisted key or an automatic unauthenticated retry.
- A restore source that cannot be recovered requires `WAITING_FOR_SOURCE`. Interruption after a
  destructive restore mutation requires `INTERRUPTED_REVIEW`, including relevant cancellation paths.
- Export/share can resume eligible unfinished items while retaining completed results. Ready-share
  output loss is explicitly reconciled rather than treated as successful delivery.
- Sweep recovery can check freeze/unfreeze state and reinstall postconditions. Ambiguous cache-clear
  effects remain `UNKNOWN`; `UNKNOWN` and `LEGACY_UNKNOWN` are not runnable work until reconciled or
  explicitly authorized for retry.

Ordinary persisted failures use bounded typed codes and sanitized arguments. Passphrases, derived
keys, raw command output and stack traces do not become Room/Intent/notification payloads.

## Prepared share and retention

`SHARE_PREPARE` stages operation/item-owned private files and completes as `READY` or `READY_PARTIAL`.
It does not open a chooser from a background service. Ready outputs have a 24-hour expiry policy.
`SharePrepareFormat` is distinct from export's bundle format: AUTO resolves fresh app metadata,
choosing APK for monolithic apps and APKS for split apps, not automatic XAPK. Explicit APK rejects
split apps.

Foreground handoff receives only the task UUID, reloads Room and validates the complete output set,
ownership, expiry and files before generating fresh provider URIs. The chooser receives read-only
grants with matching streams/ClipData. `ReadyShareAccessLock` serializes this validation/dispatch
against retention cleanup.

Cleanup deletes eligible owned leaves only, without following symlinks or deleting another task's
files. Exact-snapshot/output-set settlement prevents a stale cleanup result from expiring replacement
outputs. Cancellation and recovery use the same guarded cleanup. Its timeout is cooperative: it
bounds suspension/lock waiting, not arbitrary blocking filesystem calls.

Startup retention is best-effort once per process, not an exact expiry alarm. Handoff independently
rejects expired/missing outputs; a delayed sweep is not permission to share stale content.

## Queue and logger UI

`RoomTaskQueueRepository` combines the two stores for presentation, without imposing cross-queue
execution order. Queue exposes running, queued/action-required and retained recent sections, with the
data and privilege lanes distinguished.

`ThorRoute.Queue` and `ThorRoute.TaskDetail(taskId)` are durable navigation destinations. Home, Apps,
Freezer and Settings provide entry points. Detail observes a task rather than holding a service
instance. Background, Back and outside dismissal dismiss presentation—not accepted execution.

A same-queue submission need not replace the currently displayed task; acceptance can acknowledge
that another task was queued. Dismissing a provisional detail must not cause late acceptance to reopen
it. `STARTING` and `OBSERVER_FAILURE` are presentation states, not persisted execution states. Observer
failure means that the result cannot currently be established, not that the operation failed.

The repository supports an optional progress overlay, but the current production binding is
`EmptyTaskProgressOverlaySource`. Do not claim a wired high-frequency Queue overlay. Compatibility
`JobRegistry` progress remains in use; durable lifecycle/results still come from Room.

## Released WorkManager compatibility and schema 9

There are no new WorkRequest producers for the migrated paths. WorkManager remains initialized via
Koin's `workManagerFactory()` so previously persisted archive/export work can drain through retained
adapters. AndroidX Startup's default initializer remains removed; this is not a
`Configuration.Provider` application.

- `ArchiveBackupWorker`, `ArchiveRestoreWorker` and `AppExportWorker` retain released compatibility
  responsibilities. Their old `THOR_JOB_CHAIN` is not the current data producer path.
- `LegacyDataWorkDrainGate.awaitDrained` waits for persisted legacy data work to become terminal before
  new data-lane execution proceeds. The independent privilege lane is not globally held behind it.
- Feature-only sweep cutover closes legacy admission, awaits whole-chain cancellation and execution
  fence quiescence, then preserves ambiguous legacy target outcomes as unknown.
- `PrivilegeSweepWorker` is a non-executing tombstone, not a retained sweep executor.
  `SweepQueueCanceller.cancel(requestId)` is request-scoped; whole-chain cancellation belongs to the
  compatibility cutover, not normal user cancellation.

`AppDatabase` is version **9**. `MIGRATION_8_9` adds typed data-task tables and evolves sweep
ownership/state. Legacy target rows become `LEGACY_UNKNOWN`; nonterminal legacy requests are blocked
with unresolved targets rather than fabricated completion. Terminal history/counters and retention
are preserved without inventing per-target success.

Do not delete released Worker classes while persisted work may still refer to them. Do not reinstate
`APPEND_OR_REPLACE` producers as a shortcut around the service admission contract.

## Root shell lanes and package exclusion

| Lane | Normal root transport |
|---|---|
| `INTERACTIVE` | Coordinated Odin `MainShell` access |
| `ARCHIVE` | Owned, reusable dedicated root shell |
| `SWEEP` | Separate owned, reusable dedicated root shell |

`RootCommandRouter` chooses transport from `PrivilegeExecutionContext`. Each owned executor manages
its session and generation. Timeout/cancellation invalidates the exact generation; arbitrary command
failure or transport loss is not silently replayed.

If a dedicated background lane cannot open, it enters explicit degraded MainShell fallback.
`RootFallbackCoordinator` allows background commands to wait for coordinated access while interactive
admission fails promptly when occupied. Lane degradation and fallback ownership remain observable;
they are not silent global serialization.

`DefaultPackageOperationCoordinator` provides same-package exclusion independently of root transport.
Archive work, supported sweep items and coordinated direct mutations acquire package leases. Durable
export/share additionally use `PackageReadDataTaskRunner` with `PackageOperationOwner.BUNDLE_READ`;
delegate cleanup finishes before that lease is released. Different packages are not globally locked.

Root availability probes are serialized by `ActiveGatewayResolver.rootProbeMutex` above command-lane
admission. This avoids competing startup probes without weakening fail-fast admission for real
interactive commands. Shizuku Manager remains the authorization boundary; probe serialization does
not remove the intentional consent flow or grant broker permissions on Thor's behalf.

## Change checklist

Before adding or moving an operation:

1. Decide whether it belongs to the data allowlist, privilege allowlist, or a direct path. Do not infer
   a queue from an operation's duration, name or number of apps.
2. Resolve immutable inputs before admission; persist payload/state in Room and send only UUID wakes.
3. Define session/claim fencing, interruption, cancellation and recovery—including uncertainty—not
   merely success and retry.
4. Preserve foreground promotion before execution, typed channel/permission handling and race-safe
   final-empty shutdown.
5. Select root lanes and package leases independently. Preserve startup-probe serialization and
   Shizuku/Dhizuku consent boundaries.
6. Keep secrets memory-only, cleanup ownership-bounded and restore interruption actionable.
7. Preserve legacy drain and migration semantics; do not fabricate migrated outcomes or add new
   WorkManager producers.
8. Keep notification UUID routing, cancellation wording, localized quantities and accessible Queue
   actions consistent with actual behavior.
9. Validate focused regressions, complete Foss/Store JVM suites, instrumentation compilation, separate
   lint variants and assemblies. Keep emulator evidence distinct from physical-device verification.
10. Update this boundary document and the progress record with what was actually implemented and
    verified, including remaining direct producers.

## Useful entry points

Paths below are under `app/src/main/java/com/valhalla/thor/` unless noted.

- `domain/model/DataTask.kt`, `domain/model/PrivilegeSweep.kt` — durable models and operation allowlists.
- `data/backup/job/DataTaskAcceptance.kt` — data admission and service-wake handoff.
- `data/backup/job/DataSyncCoordinator.kt`, `data/backup/service/DataSyncService.kt` — data execution/lifecycle.
- `data/freezer/DefaultPrivilegeSweepController.kt`, `data/freezer/PrivilegeSweepService.kt` — privilege admission/lifecycle.
- `data/source/local/room/DataTaskDao.kt`, `PrivilegeSweepDao.kt`, `AppDatabase.kt` — claims, settlement and migration.
- `data/service/ForegroundServiceNotificationCapability.kt` — typed notification capability.
- `data/backup/job/DataTaskCancellationCoordinator.kt`, `data/freezer/PrivilegeSweepCancellationCoordinator.kt` — task cancellation.
- `data/freezer/PrivilegeSweepReconciler.kt` — privilege recovery/reconciliation.
- `data/backup/job/LegacyDataWorkDrainGate.kt`, `data/freezer/PrivilegeSweepWorkManagerCutover.kt` — compatibility barriers.
- `data/backup/job/PackageReadDataTaskRunner.kt` — durable export/share package-read lease.
- `presentation/share/ShareIntentFactory.kt`, `ReadyShareRetentionSweeper.kt` — foreground handoff and expiry.
- `data/repository/RoomTaskQueueRepository.kt`, `presentation/queue/TaskDetailViewModel.kt` — durable Queue/detail projection.
- `data/gateway/root/RootCommandRouter.kt`, `OwnedRootShellExecutor.kt`, `RootFallbackCoordinator.kt` — root transport.
- `data/repository/ActiveGatewayResolver.kt` — startup capability-probe serialization.
- `data/privilege/DefaultPackageOperationCoordinator.kt` — package exclusion.
