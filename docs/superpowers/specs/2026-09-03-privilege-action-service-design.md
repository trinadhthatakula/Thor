# Typed foreground-service queues — design

**Date:** 2026-09-03

**Status:** Proposed for review

**Branch:** `feat/worker-shell-lanes`

**Supersedes for future execution:** the WorkManager execution topology in
`2026-08-28-worker-shell-lanes-design.md`. The root-lane and package-coordination parts of that design
remain authoritative.

## 1. Problem

Thor's current feature tree routes four workloads through WorkManager:

1. archive backup;
2. archive restore;
3. single-app export;
4. privilege sweep.

Only the first three have shipped on `master`/`production`; `PrivilegeSweepWorker` exists only on this
feature branch. The archive jobs share `THOR_JOB_CHAIN`; privilege sweeps use `THOR_SWEEP_CHAIN`. Room
already stores privilege-sweep requests, but WorkManager still owns their serialization and liveness.
Archive and export lifecycle state is primarily WorkManager state, with high-frequency progress held
only in `JobRegistry`.

This architecture is durable, but WorkManager admission is visible to a user who has just pressed a
bulk-action button. The action can sit in `ENQUEUED` before execution begins, while the previous direct
implementation opened the terminal logger and started immediately. It also makes two different
execution systems own closely related concepts: Room owns the sweep snapshot while WorkManager owns
the queue.

The replacement must improve perceived and actual start latency without replacing a durable queue with
an in-memory service list. It must also classify foreground work honestly:

- local archive/export/share byte movement is `dataSync`;
- user-requested privileged package mutation does not fit a standard foreground-service type and is
  `specialUse`.

The design must survive configuration changes and process death, preserve cancellation and package
coordination, and never persist archive passphrases or derived keys.

## 2. Decisions

1. Add two independent, non-exported foreground services in Thor's main process:
   - `DataSyncService`, declared as `dataSync`;
   - `PrivilegeSweepService`, declared as `specialUse`.
2. Give each service its own durable FIFO Room queue and one active consumer. The services may run at
   the same time.
3. Keep Room as the source of truth. A service start intent is only an idempotent wake-up; it is never
   the task payload or queue authority.
4. Keep the existing root execution lanes:
   - data operations use `ARCHIVE` when they need privileged commands;
   - privileged sweeps use `SWEEP`;
   - direct actions continue to use `INTERACTIVE`.
5. Keep `DefaultPackageOperationCoordinator` as the cross-service, same-package exclusion boundary.
6. Move single- and multi-app export, archive backup, archive restore, and bulk-share preparation to
   the data-sync queue.
7. Move the existing durable sweep operations—freeze, unfreeze, per-app cache clearing, and verified
   Fix Store/reinstall—to the privilege queue. Adding force-stop, uninstall, clear-data, or other
   mutations requires a separate replay/postcondition review.
8. Reuse the terminal logger visually, but make Room-backed queue state its data source. Backgrounding
   the logger dismisses only the UI; it does not stop execution.
9. Add one Queue screen that projects both independent queues through a shared domain model. It does
   not merge their execution order.
10. Change cancellation from the current sweep-wide WorkManager-chain cancellation to per-task
    cancellation. A later queued task survives cancellation of the current task.
11. Do not use bulk share, export, auto-freeze, or auto-reinstall as evidence for `specialUse`. Bulk
    share and export are `dataSync`; auto-freeze and auto-reinstall are existing receiver/coroutine
    flows outside these queues.
12. After both migrations, Thor has zero feature workloads that need WorkManager. WorkManager remains
    temporarily only to drain released archive/export requests already persisted by an older app
    version. The feature-branch-only sweep chain is cancelled and reconciled from Room rather than
    drained. Complete dependency removal is a separate cleanup gate described in section 17.

## 3. Goals and non-goals

### 3.1 Goals

- Show immediate acknowledgement and the terminal logger when the user starts a supported action.
- Begin direct foreground-service execution without WorkManager scheduling latency.
- Serialize tasks within each queue while allowing the data and privilege queues to progress
  concurrently.
- Persist queue order, current task, per-item results, cancellation intent, bounded failures, and
  recovery state.
- Let the user dismiss the logger, leave Thor, reopen it, and reconnect to the same task.
- Provide one Queue screen for current, queued, actionable, and recently completed tasks.
- Preserve archive transactional publication, restore interruption warnings, root lane isolation,
  package leases, and current privilege fallback behavior.
- Use accurate Android foreground-service types and Play Console declarations.
- Remove all new WorkManager enqueue paths.

### 3.2 Non-goals

- No in-memory-only queue.
- No service binding as the UI state source.
- No remote or separate Android process for either service.
- No automatic chooser launch from a background service.
- No boot-time automatic restart of queued user work.
- No persisted passphrase, derived key, file descriptor, open stream, `AppInfo`, APK-path snapshot,
  shell command, or raw shell output.
- No new fake or nominal feature whose only purpose is to make `specialUse` easier to demonstrate.
- No migration of auto-freeze or auto-reinstall into either foreground service.
- No expansion of the privilege queue beyond operations with an explicit interruption contract.
- No change to the intentional Shizuku Manager permission-dialog flow.

## 4. Verified current baseline

The current feature tree contains exactly four concrete Workers, all indirectly extending the common
`ThorJobWorker` `CoroutineWorker`. Archive backup, archive restore, and export have shipped; the sweep
Worker and its chain exist only on this feature branch:

| Worker | Current producer | Replacement |
|---|---|---|
| `ArchiveBackupWorker` | `ThorJobLauncher.startBackup` | `DataSyncService` |
| `ArchiveRestoreWorker` | `ThorJobLauncher.startRestore` | `DataSyncService` |
| `AppExportWorker` | `ExportJobLauncherImpl.startExport` | `DataSyncService` |
| `PrivilegeSweepWorker` | `DefaultPrivilegeSweepController` | `PrivilegeSweepService` |

There are no production `PeriodicWorkRequest`s and no `AutoFreezeWorker` or `AutoInstallWorker`.

- `AutoFreezeManager` is an application-lifetime coroutine plus a dynamically registered
  `ACTION_SCREEN_OFF` receiver.
- `AutoReinstallReceiver` is a manifest receiver using `goAsync()` and an IO coroutine.
- `InstallReceiver` is a PackageInstaller result receiver.
- archive orphan cleanup is an application-start coroutine.

Those components remain outside this design. In particular, the background-triggered auto-freeze
path must not start `PrivilegeSweepService`: `specialUse` does not waive foreground-service
background-start restrictions or Play's user-initiation requirement.

## 5. Execution topology

```text
User surface
  ├─ validates and normalizes request
  ├─ opens logger in "Starting" state
  ├─ commits request to the appropriate Room queue
  └─ sends idempotent service wake-up

DataSyncService (dataSync)             PrivilegeSweepService (specialUse)
  ├─ one FIFO consumer                   ├─ one FIFO consumer
  ├─ archive/export/share runner         ├─ per-target sweep runner
  ├─ ARCHIVE root lane when needed       ├─ SWEEP root lane
  └─ data queue tables                   └─ sweep request/target tables
             \                              /
              \-- PackageOperationCoordinator --/

UI
  ├─ Queue screen combines repository flows
  ├─ logger observes one task/detail flow
  └─ never treats a Service instance as state
```

The queues are independent. An export for package A may progress while a freeze sweep processes
package B. If both reach the same package, `DefaultPackageOperationCoordinator` decides admission
using the existing lane-specific timeout. If dedicated root lanes degrade, `RootFallbackCoordinator`
continues to serialize background use of `MainShell` and keeps interactive admission fail-fast.

## 6. Shared queue contract

### 6.1 Separate persistence, shared projection

Do not create one nullable-heavy task table for both services. Preserve clear ownership:

- `DataSyncService` owns new data-task tables and DAO transactions.
- `PrivilegeSweepService` owns the evolved sweep request, source, and target tables.
- a read-only `TaskQueueRepository` combines both flows into `QueuedTaskSummary` for the Queue screen.

A shared summary contains only UI-level fields:

- stable task UUID and queue kind;
- operation kind and title inputs;
- creation order;
- lifecycle phase;
- aggregate progress;
- active item label when safe to display;
- whether cancel, resume, authenticate, review, share, acknowledge, or retry is available;
- terminal timestamp and retention deadline;
- root-lane degradation where applicable.

The shared projection does not expose DAO entities and cannot claim, complete, or reorder work.

### 6.2 FIFO and runnable tasks

Each queue assigns a durable sequence in the insertion transaction. Claims are ordered by sequence,
then UUID as a stable tie-breaker.

Only runnable tasks participate in FIFO claiming. A backup waiting for the user to re-enter a
passphrase does not block a later export. Returning that backup to `QUEUED` preserves its original
sequence; it runs after the currently active task and before later runnable tasks.

There is no priority, reordering, or concurrent execution inside one queue in this version.

### 6.3 Atomic claims

Each service creates a fresh process/service-session token. Every request persists explicit
`payloadSchemaVersion`, `serviceSessionToken`, `claimToken`, and `claimLeaseExpiresAt` fields. Claimed
item/target rows likewise persist `claimToken` and `claimLeaseExpiresAt`. Leases are renewed only at
durable stage/progress checkpoints. Room transactions must provide:

1. compare-and-set claim of the oldest runnable request;
2. claim-token ownership checks on every progress or terminal write;
3. compare-and-set claim of the next pending item/target;
4. rejection of late writes from an old service generation;
5. transactional cancellation that wins against later completion;
6. recovery of claims owned by an earlier process/session, or an expired lease whose local owner is no
   longer live;
7. a final empty-queue check before service shutdown.

A lease timeout alone must not steal work from a known-live service merely because one command is slow.
Session identity, local owner liveness, and lease expiry are considered together. An in-process mutex
prevents duplicate drain coroutines in one service instance. It is not the durable single-consumer
guarantee; Room claims are.

### 6.4 Persisted and hot progress

Persist:

- every request and item state transition;
- stage changes;
- aggregate completed/failed/busy/unresolved counts;
- bounded byte or item checkpoints;
- bounded typed failure and warning data;
- timestamps and claim ownership;
- output identity needed for reconciliation.

Keep high-frequency byte/chunk progress in an in-memory `StateFlow` overlay and throttle both Room and
notification updates. The UI combines durable state with the hot overlay when available and falls back
to Room after recreation.

Do not persist a line for every copied chunk. Sweep logger lines are derived from durable target states.
If richer logs are later needed, add bounded typed events—not raw shell output—with an explicit row and
byte cap.

## 7. Foreground-service lifecycle

### 7.1 Persist first, wake second

The acceptance sequence is:

1. allocate a task UUID;
2. show a provisional `Starting` logger state;
3. validate and normalize all safe request fields;
4. for secret jobs, put the derived key in `ArchiveKeyHolder` before making the row runnable;
5. insert the durable Room request and items in one transaction;
6. call `startForegroundService()` as an idempotent wake-up;
7. report accepted only after the Room transaction succeeds.

If Room insertion fails after a secret was placed in `ArchiveKeyHolder`, drop that key immediately.
There is no atomic transaction across Room and `startForegroundService()`. If starting the service
throws after commit, retain the request as `START_BLOCKED`/queued and show a retry action. Never delete
an accepted durable request merely because one wake-up failed.

### 7.2 Foreground promotion order

Both services must call `ServiceCompat.startForeground()` at the beginning of `onStartCommand`, using
a minimal preparing notification, before:

- Koin dependency resolution beyond notification bootstrap;
- Room reads or reconciliation;
- privilege probing;
- Shizuku/Dhizuku calls;
- root-shell acquisition;
- file or archive IO.

After promotion, the service starts or signals its drain loop and returns `START_STICKY`. Every intent,
including a null sticky-restart intent, means only “the queue may contain work.” Request payloads do
not travel in the intent.

If foreground promotion fails, the service stops immediately and leaves the durable request visible as
start-blocked. It must not continue as an ordinary background service.

### 7.3 One drain loop and safe shutdown

Each service is an ordinary framework-created Android component with a public no-argument constructor.
It follows `FreezerTileService`'s pattern of lazy Koin property injection; it is never registered or
constructed as a Koin singleton. Testable queue orchestration lives in injected coordinators rather
than the framework class, and it never runs in `ThorApplication`'s lifetime scope.

Each service owns:

- `SupervisorJob` plus the injected IO dispatcher;
- one conflated wake signal or actor;
- at most one drain coroutine;
- one child job for the active task;
- the current start ID/generation.

Repeated starts do not create repeated consumers. When no runnable task remains, the service performs
a final Room check and uses `stopSelfResult(latestStartId)` so an older drain cannot stop a newer kick.
It removes the ongoing notification only after the active runner and cancellation cleanup have ended.

The services run in Thor's main process. This preserves `ArchiveKeyHolder`, package coordination, root
lane state, Koin singletons, and existing gateway assumptions.

After foreground promotion but before claiming or staging any cleanup-sensitive data task,
`DataSyncService` waits for `LaunchSweepBarrier`. If the barrier times out, it does not create or mutate
staging output: it releases or pauses the claim with a typed start-blocked reason and stops. This keeps
startup orphan cleanup from racing newly owned export, share, backup, or restore artifacts.

### 7.4 Wake locks

WorkManager currently supplies wake-lock behavior. Each direct service therefore owns a distinct,
non-reference-counted partial wake lock only while an active runner is executing.

- promote to foreground and atomically claim work before acquiring the wake lock;
- acquire with a ten-minute timeout;
- renew for another ten minutes only at verified progress/stage checkpoints, so a hung operation cannot
  renew itself forever;
- require every blocking root or file stage either to produce such a checkpoint within the lease or to
  have a hard deadline shorter than ten minutes;
- on a deadline, cancel the stage, invalidate any root-shell generation it owns, persist the applicable
  interruption state, and stop rather than continuing without CPU ownership;
- release in `finally` on success, failure, or cancellation;
- never hold a wake lock merely because tasks are queued or waiting for user action;
- test that every exit path releases it.

The foreground service improves process priority but is not treated as a CPU wake-lock substitute.

## 8. `DataSyncService`

### 8.1 Supported operations

The data queue supports:

- `ARCHIVE_BACKUP`;
- `ARCHIVE_RESTORE`;
- `APP_EXPORT`, with one or more ordered app targets;
- `SHARE_PREPARE`, initially for bulk share preparation.

Single-app export and the existing multi-app export runner converge on one durable export operation.
Every export/share bundle read acquires the existing package-operation lease so reinstall or uninstall
cannot race APK/OBB reads. Each task and item uses an operation-unique staging directory; the current
shared/reused bundle staging directory is not valid for multiple durable ready-to-share tasks.

The first implementation does not move ordinary installer session streaming or single-app quick-share
unless it uses the same explicit `SHARE_PREPARE` entry point.

### 8.2 Persistence model

Add a common data-task entity for lifecycle and progress, plus typed detail tables rather than an
opaque `Data` map or one entity with unrelated nullable columns:

- backup detail: package, selected data-class IDs, include-bundle flag, non-secret salt, destination;
- restore detail: expected package, selected classes, OBB choice, durable source token;
- export detail: explicit format, resolved destination, naming inputs;
- export/share items: parent task, stable ordinal, package, item state, deterministic staging name,
  output metadata, bounded failure;
- output metadata: publication state, display name, destination label, content/document identity or
  private relative path.

Do not persist `AppInfo`, current APK paths, current installed-package facts, an `installFirst`
decision, an authenticated open archive object, or an ephemeral FileProvider URI. Re-resolve mutable
package state when an item starts.

Room becomes the durable lifecycle authority that WorkInfo supplied. Explicit fields replace inference
such as “did the Worker ever run?”:

- attempt count;
- claimed/started/updated timestamps;
- current stage;
- cancellation timestamp;
- destructive-started flag;
- interruption/recovery requirement;
- cleanup/publication state.

### 8.3 Archive secrets

Preserve the existing security boundary:

- passphrases remain caller-owned `CharArray`s and are wiped;
- derived keys remain only in `ArchiveKeyHolder`;
- no passphrase or derived key enters Room, an intent, SavedState, logs, or notifications;
- the current one-hour unused-key expiry remains the queue-wait ceiling;
- the runner consumes the key when execution begins and drops it on every reachable exit path.

If the key expires or the process dies before the task starts, do not fail the durable request
permanently. Move it to `WAITING_FOR_AUTH`; the Queue screen prompts for the passphrase and returns the
same task to `QUEUED` after deriving a fresh key.

### 8.4 Restore source durability

The current restore picker does not retain a persistable read grant, which is insufficient for a
service-owned durable request. Use these ordered strategies:

1. take and track a persistable read grant when the provider supports it, then persist that durable URI
   token;
2. otherwise persist a `STAGING_SOURCE` task while the temporary app grant is live, promote
   `DataSyncService`, and copy the source into operation-owned private storage as the service's first
   data-sync stage;
3. after the private copy commits, replace the temporary URI with a private source token and make the
   restore runnable;
4. if the process dies or the grant disappears before staging commits, move the task to
   `WAITING_FOR_SOURCE` and require the user to reselect the archive.

The UI must not copy a large archive on its own coroutine merely to make the later service durable.
The temporary URI may be stored only in the private operation row for the staging attempt; never expose
it in notification text or logs. Release a retained URI grant or delete the private source only when no
actionable task state needs it.

### 8.5 Operation-specific interruption

| Operation | Automatic replay after a stale claim | Required behavior |
|---|---|---|
| Archive backup | No | Discard exact partial/staging state, move to `WAITING_FOR_AUTH`, reauthenticate, and restart from the beginning. Reconcile a known final destination before publishing again. |
| Archive restore before mutation | No | Move to `WAITING_FOR_AUTH`; reopen, fully authenticate, re-read package state, and rerun every restore gate. |
| Archive restore after mutation began | Never | Move to `INTERRUPTED_REVIEW`, retain the restore breadcrumb, show that app data may be partial, and require explicit review before another attempt. |
| App export | Yes | Discard incomplete output, re-resolve the app and destination, and restart that unfinished item from the beginning. |
| Bulk export | Per item | Preserve terminal item rows; rebuild only pending/stale items with deterministic staging names. |
| Share preparation | Per item | Preserve prepared items, rebuild missing/incomplete items, and finish as ready or partially ready. Never launch the chooser automatically. |

`AppArchiveStore` transactional `.part` publication, `PartialArchiveLedger`, the restore breadcrumb,
`ArchiveOrphanSweeper`, `LaunchSweepBarrier`, archive verification, and existing use cases remain in
force. Cleanup becomes aware of nonterminal and ready-to-share operation ownership so startup sweeping
cannot delete live artifacts.

### 8.6 Share completion handoff

`DataSyncService` prepares artifacts only. It never starts `ACTION_SEND` or
`ACTION_SEND_MULTIPLE` from the background.

On completion:

- persist ready item metadata;
- regenerate FileProvider URIs from private relative paths when needed;
- expose `READY` or `READY_PARTIAL` in Room;
- if the initiating UI is foreground and still observing the task, offer the chooser there;
- otherwise post a “Ready to share” notification whose immutable PendingIntent opens a foreground
  handoff Activity or Queue task detail;
- for `ACTION_SEND_MULTIPLE`, attach every URI through both `EXTRA_STREAM` and `ClipData`, and grant
  temporary read access to the complete set;
- launch the chooser only after that foreground user action.

Ready-to-share private artifacts expire after 24 hours unless shared or explicitly dismissed sooner.
The task then becomes `EXPIRED`, and cleanup removes only artifacts owned by that task.

### 8.7 Android 15+ data-sync timeout

On Android 15+ for Thor's target-SDK contract, `dataSync` foreground services share an app-wide budget
of six hours while the app is in the background during each rolling 24-hour period. Implement
`Service.onTimeout(startId, foregroundServiceType)` on API 35+ and treat it as interruption, not
success:

1. persist cancellation/interruption intent;
2. cancel the active child;
3. complete non-cancellable cleanup and coarse progress settlement;
4. apply the operation-specific recovery table above;
5. stop foreground and stop the service within the platform grace period;
6. do not claim another data task in that service session; leave queued work paused until a later
   eligible user interaction starts or resumes the service.

A quota timeout must never publish a partial archive as complete or silently replay a destructive
restore.

## 9. `PrivilegeSweepService`

### 9.1 Supported operations

The first service cut supports exactly the existing sweep operations:

- `FREEZE`;
- `UNFREEZE`;
- `CLEAR_CACHE` per selected app;
- verified `REINSTALL`/Fix Store.

Target resolution remains an immutable snapshot of operation, Android user, resolved freezer mode,
canonical package order, and initiating source associations. Equivalent active requests remain
coalesced.

Force-stop, uninstall, clear-data, explicit suspend/unsuspend, whole-device cache trim, and component
Restore all remain outside the queue until each has a documented interruption and postcondition
contract.

### 9.2 Evolve Room from aggregates to target state

The current sweep request stores targets but only request-level counters. Add durable request state and
claim ownership:

- queued/running/cancel-requested/terminal state;
- service-session and request claim token;
- claim/start/update timestamps;
- attempt and cancellation metadata;
- neutral execution identity instead of WorkManager `workId`.

Add target execution fields:

- `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `BUSY`, `CANCELLED`, `UNKNOWN`, or
  `LEGACY_UNKNOWN`;
- target claim token, claim-lease expiry, and attempt count;
- start/finish timestamps;
- typed, sanitized result/failure code.

`UNKNOWN` and `LEGACY_UNKNOWN` are actionable but non-runnable and contribute to the request's
unresolved aggregate. `UNKNOWN` records an ambiguous current execution; `LEGACY_UNKNOWN` records an
aggregate-only row migrated from the feature-branch Worker schema. Only operation-specific
reconciliation that proves the target's current state, or an explicit user resume decision that
authorizes another attempt, may convert either state to `PENDING`; neither is claimed directly.

Replace `resetForRun`. A restarted service never erases aggregates or replays all targets. It claims
the lowest-ordinal pending target and derives or transactionally updates aggregates from target states.

### 9.3 Target completion and cancellation races

Complete a target only if:

- the request claim token still matches;
- the target is still `RUNNING` under the matching target claim;
- cancellation has not already won.

If completion commits first, retain that result and cancel only remaining work. If cancellation commits
first, a late completion is rejected and cannot resurrect the request. The external side effect may
already have occurred; exact-once mutation across Android/package state and Room is impossible, so
recovery relies on convergence and verification.

### 9.4 Interrupted-target recovery

- Freeze/unfreeze: re-read package state under the package lease; already-converged state succeeds.
- Reinstall: run the existing verified postcondition before deciding whether another mutation is
  necessary.
- Clear cache: if the prior outcome cannot be known, move the target to `UNKNOWN`/review. Clearing
  again is not temporally idempotent because the app may have recreated cache after the first attempt;
  repeat only after explicit user authorization.
- Persisted `SUCCEEDED`, `FAILED`, `BUSY`, or `CANCELLED` targets are never replayed automatically.

The existing `PrivilegeSweepItemExecutor`, `ManageAppUseCase`, package lease, `SWEEP` execution
context, root router, dedicated sweep shell, fallback coordinator, and lane-status source remain.

### 9.5 `specialUse` boundary

`PrivilegeSweepService` is started only by an explicit user action from an eligible foreground or
platform-recognized user-interaction surface. Activity and notification-action launches use the normal
direct path. Quick Settings and launcher surfaces must be tested on every supported Android version;
where their callback is not itself an eligible FGS start context, they route the user through a visible
handoff Activity rather than attempting a forbidden background start. It does not start from:

- application startup alone;
- screen-off auto-freeze;
- package-added auto-reinstall;
- boot completion;
- a periodic timer.

The service does not request Shizuku authorization. The existing Shizuku Manager dialog remains the
consent boundary. If no configured privilege is available when execution begins, the task settles with
an actionable authorization-required failure or blocked state; it does not add another Thor
confirmation layer.

## 10. Cancellation and backgrounding

### 10.1 Per-task cancellation

The logger cancel button, Queue screen action, and service notification cancel the selected task—not
the entire queue.

Cancellation order is mandatory:

1. Room transaction records `cancel_requested_at`.
2. Pending items/targets become cancelled immediately.
3. A queued task with no active item becomes terminal immediately.
4. The service is signalled and cancels only the matching active child.
5. The runner performs non-cancellable settlement and cleanup.
6. The task becomes terminal after no item remains running.

While the external command or IO is unwinding, UI and notification show `Stopping`. The service remains
foreground until unwind completes. A task inserted after the cancellation transaction is unaffected.

Current sweep-wide `SweepQueueCanceller` semantics and “Cancel queue” copy are retired. A future
“Cancel all” action requires an explicit confirmation and is not part of this cut.

### 10.2 Background action

The logger’s **Background** action dismisses the logger only. It does not:

- cancel the coroutine;
- stop the service;
- remove the queue row;
- hide the foreground notification;
- acknowledge a terminal result.

The Queue screen and notification can reopen the task detail at any time.

### 10.3 New tasks while another task runs

When a task is already running in the same queue:

- persist the new task in FIFO order;
- show a localized “Queued” snackbar/toast with a Queue action;
- do not replace the active logger or unexpectedly open another modal;
- do not create a second service consumer.

When it later becomes active, update the service notification. Do not automatically pop a logger over
whatever screen the user is then using.

## 11. Queue and logger UI

### 11.1 Queue destination

Add a Queue destination reachable from the main app bar and from both service notifications. It is a
normal navigation destination, not a fifth bottom-navigation tab.

The screen has:

- **Running**: at most one data task and one privilege task;
- **Queued / action required**: FIFO entries, waiting-auth, interrupted, or ready-to-share tasks;
- **Recent**: acknowledged or unacknowledged terminal history retained for 24 hours.

Each row shows operation, package/item summary, state, progress, queue type, and relevant action.
Opening a row shows task details and its logger projection.

Room is authoritative after recreation. SavedState may carry the selected task ID only.

### 11.2 Terminal logger

Reuse the existing monospaced list, auto-scroll, title, progress treatment, stop/close controls, and
visual identity of `TermLogger`.

Replace mutable `MainViewModel.LoggerState` for queued tasks with a task-detail ViewModel that observes
Room and the optional hot progress overlay. For privilege sweeps, one durable target row maps to a log
line. For data tasks, stage transitions and ordered item rows map to log lines.

The logger opens immediately in `Starting` state for a newly initiated task. Once Room accepts the
request, it transitions to queued or running. A launch failure is rendered in the same logger rather
than leaving an endless animation.

Terminal Close acknowledges the visible result but does not delete external archive/export output.
Actionable states such as waiting-auth, interrupted-review, or ready-to-share remain visible until the
user acts or their explicit artifact retention expires.

### 11.3 Observer failures

`OBSERVER_FAILURE` remains a presentation condition, not a persisted execution result. A repository
flow failure says the UI cannot currently establish task state; it must not rewrite a running Room task
as failed.

## 12. Notifications

Use separate low-importance channels and stable ongoing notification IDs:

- data operations;
- privileged app actions.

Each ongoing notification shows the current operation, coarse progress, and count of later queued
items. Its content PendingIntent contains the task UUID and opens the Queue task detail. Cancellation
uses an immutable PendingIntent carrying that UUID and persists cancellation before signalling the
service.

Notification IDs are not derived solely from enum ordinals. Task result notifications use a stable
hash/allocated ID that cannot retarget after enum insertion.

Notification capability has three distinct outcomes:

- On API 33+, denied `POST_NOTIFICATIONS` does not make foreground execution illegal; the system still
  exposes the FGS in Task Manager, and the in-app logger and Queue remain cancellation surfaces.
- A valid but user-blocked channel is a product-level start block for new tasks. Persist
  `START_BLOCKED_NOTIFICATION` and offer notification settings rather than beginning work without the
  promised visible progress surface. A channel blocked after a task starts does not rewrite the task's
  execution result.
- Missing/invalid channel construction, invalid notification construction, or failed initial
  `startForeground()` is an execution-start failure: stop immediately and retain the durable task for
  retry. Never continue as an unforegrounded service.

Replace the current boolean/conflated behavior in `ThorJobNotificationCapability` and its
producer-side checks with these typed outcomes. Terminal notifications remain best-effort after the
service has stopped; initial foreground promotion does not.

When a service drains several tasks, it keeps one ongoing notification and changes its content to the
new active task. It stops foreground when its queue has no runnable or settling task, then posts a
separate terminal/ready notification when policy and permission allow.

## 13. Process death, user stop, reboot, and force-stop

`START_STICKY` is a restart hint, not durable scheduling. Room must remain correct if the service is not
recreated.

### 13.1 Sticky recreation

On a null-intent restart, the service:

1. promotes itself immediately;
2. creates a new service-session token;
3. identifies claims from an older process/session;
4. applies operation-specific reconciliation;
5. claims runnable work;
6. stops if nothing is runnable.

Do not steal a claim using wall-clock age alone; a long valid command is not stale while its owning
process is alive. Session identity is the primary stale-claim signal.

### 13.2 Android Task Manager Stop

Swiping Thor's activity task from Recents dismisses the UI only. It does not cancel Room tasks or call
`stopService`; an already-running foreground service continues and remains reachable through its
notification. This is distinct from Android 13+ Task Manager Stop, which kills Thor's whole process
without dependable callbacks. The next app launch reconciles old claims and displays
interrupted/actionable tasks. It does not pretend cleanup or terminal settlement ran.

### 13.3 Reboot and force-stop

Do not add a boot receiver to restart these services. After reboot or force-stop, Room retains the
queue, but execution resumes only through an explicit foreground/user action. The Queue screen presents
Resume, Authenticate, Review, or Cancel according to the recovery contract.

A force-stopped app cannot run until the user launches it; the design does not claim otherwise.

### 13.4 Startup reconciliation

Application startup may reconcile Room and prune terminal history, but it must not start an FGS from a
background-restricted context. If runnable work exists, foreground UI displays a resume affordance.
An already-running/sticky service owns active execution.

## 14. Manifest and Android contracts

Retain or declare explicitly rather than relying on transitive manifest contributions:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

Add:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name=".data.backup.service.DataSyncService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />

<service
    android:name=".data.freezer.PrivilegeSweepService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Executes explicit user-requested queued app-management operations such as freeze, unfreeze, per-app cache clearing, and verified reinstall through a user-configured privileged gateway, with visible progress and cancellation." />
</service>
```

The exact package paths may follow implementation package boundaries, but the service names and types
are fixed by this design. Neither service declares a separate process.

Android-version requirements:

- API 26+: use `startForegroundService` and promote within the platform deadline;
- API 31+: start only from an eligible foreground/user-initiated context or permitted restart path;
- API 33+: handle notification denial without pretending the FGS notification obligation vanished;
- API 34+: declare the matching service type and type-specific permission;
- API 35+: implement `dataSync` timeout handling;
- API 36+: do not rely on WorkManager as a quota escape hatch; this design uses the declared direct
  service type and its actual policy constraints;
- API 37: retest against the finalized platform behavior; do not encode a preview-only rule as a
  settled contract.

## 15. Play declaration and review evidence

The `specialUse` declaration describes the actual privileged queue. It must not describe file copying,
bulk share, export, or a fake stock-device demo.

Console declaration:

- feature: user-initiated queued app management;
- operations: freeze/unfreeze, per-app cache clear, verified reinstall;
- why it cannot be deferred: the user is watching an explicit maintenance action whose partial state
  and package-level results must remain visible and cancellable while Thor is backgrounded;
- user benefit: reliable multi-app management with live progress, cancellation, and recovery;
- trigger: explicit selection and confirmation in Thor, a user-clicked tile/shortcut, or a
  user-clicked notification action;
- interruption impact: partial completion and unresolved package state must be reported accurately.

Record two separate evidence flows:

1. **`specialUse`** on an authorized Root or Shizuku test device: initiate a multi-app freeze or Fix
   Store action, show the immediate logger, background Thor, show the ongoing special-use
   notification, reopen Queue, cancel or complete, and verify final package state.
2. **`dataSync`** on a stock device: initiate a large export or bulk-share preparation, show progress
   while backgrounded, then use the user-clicked ready notification to open the chooser/output.

The stock flow demonstrates `dataSync`, not `specialUse`. Review evidence must state that privileged
operations require a user-configured gateway rather than pretending they run on an ordinary review
device. Play approval is independent of technical correctness. If `specialUse` is rejected, the
fallback is to redesign/shorten the privileged execution or make a distribution-channel decision—not
to relabel it as `dataSync`.

## 16. Room migration

The current app database is version 8. Add a real migration that:

- creates data-task, typed detail, item, and output tables;
- adds sweep request lifecycle/claim/cancellation columns;
- adds sweep target state/claim/result columns;
- maps existing nonterminal sweep targets to `LEGACY_UNKNOWN` without erasing their immutable order;
  aggregate-only history cannot prove which package was already changed, so migration must not mark
  every target executable;
- requires operation-specific reconciliation or explicit user resume before a `LEGACY_UNKNOWN` target
  becomes `PENDING`;
- maps legacy terminal sweep rows to terminal request history with target detail marked legacy or
  unresolved where aggregate counters cannot identify individual packages;
- preserves source associations and 24-hour retention;
- keeps the WorkManager sweep ID only long enough to cancel the feature-branch chain and correlate its
  Room snapshot, then removes or deprecates it; no released sweep Worker needs draining.

Debug destructive migration is not evidence. Add schema export and migration tests from version 8 to
the new version, plus direct-upgrade coverage from every schema version supported by the repository's
migration chain.

## 17. WorkManager cutover and removal

### 17.1 Why immediate deletion is unsafe

Archive/export Workers already exist on `dev`, `master`, and `production`. An app update can therefore
arrive while old WorkManager requests are persisted. WorkInfo does not expose enough public request
input to reconstruct every task outside its Worker, and backup/restore keys deliberately disappear on
process death.

Deleting Worker classes and the runtime in the first service release could silently lose a resumable
export or turn old work into an unresolvable class-instantiation failure.

### 17.2 Cutover release

In the first service release, all new producers write Room and start the two services; no new
`WorkRequest` is created. Legacy handling then differs by release history.

**Released archive/export chain:**

- keep `ArchiveBackupWorker`, `ArchiveRestoreWorker`, `AppExportWorker`, and the WorkManager runtime only
  to drain already-persisted `THOR_JOB_CHAIN` work;
- gate `DataSyncService` so it does not claim a new data task while that legacy chain is nonterminal;
- let old export resume normally;
- let old backup/restore fail closed when their in-memory key was lost, preserving existing behavior;
- mark this compatibility path clearly in logs and tests.

**Feature-branch-only sweep chain:**

- no released install can contain a persisted `PrivilegeSweepWorker`, so do not retain it as a release
  compatibility executor;
- for upgrades from internal feature/dev builds, cancel `THOR_SWEEP_CHAIN`, reconcile its Room snapshot,
  and map ambiguous nonterminal targets to `LEGACY_UNKNOWN`;
- remove the sweep WorkRequest producer and Worker at cutover; `PrivilegeSweepService` claims only
  explicitly runnable/reconciled targets.

The released data-chain drain does not block the independent privilege queue.

### 17.3 Final cleanup gate

After the compatibility policy is explicitly accepted, remove:

- `ThorJobWorker` and the three retained archive/export Worker classes;
- the remaining data WorkRequest builders and enqueue adapters;
- `THOR_JOB_CHAIN` (the feature-only `THOR_SWEEP_CHAIN` is removed during cutover);
- WorkInfo observation and WorkManager cancellation ports;
- Koin `workManagerFactory()` setup;
- WorkManager dependencies and generated Worker factory integration;
- `SystemForegroundService` overlay and default-initializer removal metadata;
- Worker-specific tests and documentation.

Users can skip app versions, so “one release has passed” is not by itself proof that no old persisted
request exists. The cleanup change must either retain a minimal compatibility bridge for supported
skip-upgrade paths or explicitly surface a one-time “operation interrupted by upgrade” result before
removing the runtime. It must not silently discard old work.

At the feature level, however, the service cutover leaves zero WorkManager workloads. Auto-freeze and
auto-reinstall are not exceptions because they were never Workers.

## 18. Errors, security, and observability

### 18.1 Error boundaries

- rethrow `CancellationException` from runners;
- map ordinary failures to bounded typed task/item failures;
- catch at the service drain boundary so one failed task does not kill the permanent service scope or
  block later queue entries;
- never infer success from a missing callback, empty external result, or process death;
- preserve `OBSERVER_FAILURE` as a UI uncertainty, not execution failure;
- make foreground-start failure visible without deleting the request.

### 18.2 Security

Never persist or display:

- passphrases or derived keys;
- decrypted archive data;
- raw restore URI text;
- shell commands or raw shell output;
- credentials or authorization tokens;
- private absolute paths not required for internal cleanup;
- full stack traces in Room or notifications.

Persist stable error codes plus bounded sanitized arguments. Developer diagnostics may go to the normal
logger without exposing secrets.

### 18.3 Metrics

Record timestamps for:

- user submission;
- Room acceptance;
- service start request;
- foreground promotion;
- queue claim;
- first item start;
- cancellation request and unwind completion;
- terminal settlement.

These timestamps support latency comparison without recording package-sensitive logs. Also record
queue depth, interruption class, root-lane degradation, and start-blocked failures.

## 19. Testing

### 19.1 JVM and DAO tests

- request validation and typed Room mapping;
- concurrent claimers cannot claim one request or target twice;
- FIFO claim order and stable tie-break;
- data and privilege queues claim independently;
- only matching claim tokens can update progress or terminal state;
- cancellation-before-completion and completion-before-cancellation races;
- late completion cannot resurrect a cancelled task;
- a task inserted after cancellation is unaffected;
- equivalent sweep coalescing remains correct;
- aggregates always match target rows;
- blocked/auth tasks do not block later runnable data tasks;
- bounded progress/event persistence;
- retention and acknowledgement;
- database migration from version 8, including `LEGACY_UNKNOWN` aggregate semantics;
- direct upgrade from every supported released schema, without assuming the transition release ran;
- feature/dev sweep-chain cancellation and Room reconciliation.

### 19.2 Runner recovery tests

- backup missing key becomes waiting-auth and never publishes a partial;
- restore reauthenticates and reevaluates gates;
- interrupted destructive restore requires review and preserves its breadcrumb;
- export restarts from the beginning and revalidates destination;
- bulk export/share resumes only unfinished items;
- ready share never launches a chooser from service context;
- stale freeze/unfreeze target converges from current package state;
- ambiguous clear-cache recovery becomes `UNKNOWN` and requires explicit retry authorization;
- reinstall verifies postcondition before repeating;
- `UNKNOWN` and `LEGACY_UNKNOWN` targets are never claimed directly;
- completed sweep targets are never replayed.

### 19.3 Service lifecycle tests

- foreground promotion occurs before Room, privilege, shell, or file work;
- correct foreground-service type is supplied on supported APIs;
- repeated and null start intents create one drain loop;
- an empty-queue shutdown cannot race a newer kick;
- a rejected background FGS start keeps the task visible/start-blocked and performs no work;
- start failure keeps the task visible and retryable;
- `DataSyncService` awaits `LaunchSweepBarrier` before a cleanup-sensitive claim or staging write, and a
  barrier timeout leaves artifacts untouched;
- wake locks are held only around active execution and released on every exit;
- blocking stages checkpoint within the wake-lock lease or hit a shorter hard deadline;
- active cancellation remains foreground until unwind finishes;
- dataSync timeout settles interruption, claims no later task, and stops within the grace period;
- process/session claim recovery rejects stale writes.

### 19.4 UI and notification tests

- logger appears immediately in Starting state;
- Background dismisses UI without changing execution;
- Cancel affects one task and shows Stopping until settled;
- a second action shows Queued without replacing the active logger;
- Queue survives process recreation and combines both queues correctly;
- notification task IDs open the right detail after process death;
- ready-share notification opens foreground handoff before chooser;
- multi-URI handoff populates both `EXTRA_STREAM` and `ClipData` with read grants;
- API 33+ notification-permission denial does not reject service work, while a blocked channel produces
  the defined start-blocked state;
- localized states and positional placeholders compile in every shipped locale.

### 19.5 Device matrix

On the rooted emulator:

- direct start latency for both services;
- concurrent ARCHIVE and SWEEP operations on different packages;
- same-package conflict behavior;
- root dedicated-lane and degraded fallback behavior;
- cancel during a root command and during file copy;
- process kill, sticky recreation, Recents swipe, Task Manager Stop, reboot, and force-stop recovery;
- an ineligible/background FGS start is rejected without executing the queued operation;
- notification permission denied and channel-disabled behavior;
- a direct upgrade from a released WorkManager build, including skipping the transition release;
- Shizuku server restart without adding another permission layer.

Repeat the critical privileged flow on a rooted physical device before release. Test a stock device for
export/share `dataSync` behavior and Play-review recording. Retest all service-start and timeout
assumptions on a finalized API 37 system image before claiming API 37 support.

## 20. Performance acceptance

Measure the current WorkManager build before removal and the direct-service build after implementation
on the same emulator and build type.

For at least 20 warm launches per operation class, capture:

- tap to logger visible;
- tap to durable Room acceptance;
- tap to foreground promotion;
- tap to first target/byte operation.

Acceptance requires:

- logger acknowledgement in the same UI interaction, without waiting for scheduler admission;
- no WorkManager `ENQUEUED` delay in the new path;
- lower median tap-to-first-operation than the WorkManager baseline for privilege sweeps and export;
- no regression in queue ordering, cancellation settlement, or terminal accuracy;
- no main-thread disk, archive, privilege, or shell work.

Report distributions and raw run counts rather than one best-case number.

## 21. Delivery sequence

1. Add shared queue projection and Room schema/migration.
2. Add data queue store, typed task details, claims, reconciliation, and service bootstrap.
3. Adapt archive backup/restore, single- and multi-export, and bulk-share preparation to
   `DataSyncService` while retaining domain use cases.
4. Evolve sweep target persistence and add `PrivilegeSweepService` using the existing item executor and
   root `SWEEP` lane.
5. Add per-task cancellation receiver/coordinator, service notifications, Queue destination, and
   Room-backed terminal logger.
6. Switch every new producer to Room-plus-service wake-up; enable the released data-job WorkManager
   drain gate, and cancel/reconcile the feature-only sweep chain.
7. Run automated, migration, lint, build, emulator, physical-root, and stock-device acceptance.
8. Record accurate Play evidence for `dataSync` and `specialUse`.
9. Perform the separately reviewed WorkManager compatibility cleanup when its skip-upgrade policy is
   satisfied.

No implementation step changes `versionCode`; release preparation remains a separate
`chore(release)` commit.

## 22. Acceptance criteria

The design is implemented when:

- `DataSyncService` is the only new executor for backup, restore, export, and bulk-share preparation;
- `PrivilegeSweepService` is the only new executor for supported user-requested privilege sweeps;
- both services promote immediately with the correct type;
- each queue has exactly one durable Room consumer and independent progress;
- the Queue screen and logger recover from UI/process recreation without a service binding;
- backgrounding the logger never cancels work;
- cancellation persists before coroutine interruption and affects only the selected task;
- completed items are not replayed after process death;
- backup/restore secrets never reach persistent storage;
- destructive restore interruption is never silently retried;
- share completion requires a foreground user handoff before chooser launch;
- package and root-lane coordination remain intact;
- auto-freeze and auto-reinstall remain outside WorkManager and outside the new user-initiated FGS
  queues;
- no new WorkRequest is created anywhere in production code;
- WorkManager compatibility code is isolated and has an explicit removal gate;
- the special-use manifest subtype and Play declaration describe the real privileged operation;
- performance evidence demonstrates that direct execution removes the visible scheduler delay.
