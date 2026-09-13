# Typed Foreground Service Queues Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Thor's four feature WorkManager launch paths with two independently serialized, Room-backed foreground-service queues that start immediately, retain durable task state, preserve archive and privilege safety boundaries, and expose a recoverable Queue/terminal-logger UI.

**Architecture:** `DataSyncService` executes archive backup, archive restore, export, and bulk-share preparation under `dataSync`; `PrivilegeSweepService` executes explicit freeze, unfreeze, per-app cache clear, and verified Fix Store requests under `specialUse`. Room owns requests, per-item/per-target state, claims, cancellation, recovery, history, and the combined read-only UI projection; service intents are idempotent wake-ups only. Existing archive/export Workers remain isolated only to drain released `THOR_JOB_CHAIN` work, while the feature-only sweep WorkManager chain is cancelled and reconciled during cutover.

**Tech Stack:** Kotlin, coroutines and Flow, Android foreground services, Room 2.x, Compose Navigation and Material 3, Koin Annotations compiler plugin, AndroidX WorkManager compatibility bridge, JUnit, kotlinx-coroutines-test, Robolectric, Room migration tests, and Android instrumentation tests.

**Spec:** `docs/superpowers/specs/2026-09-03-privilege-action-service-design.md`

## Global Constraints

- Work only on `feat/worker-shell-lanes`; PR #453 remains based on `dev`, open, and unmerged.
- Keep `versionCode=1952`; release preparation and the 1953 bump are separate work.
- Never commit directly to `dev`, `master`, or `production`.
- Never add a `Co-Authored-By` trailer.
- Stage only named files. Never use `git add .` or `git add -A`; leave `.kotlin/` untracked and never stage `docs/audit/` or `docs/enforcement/`.
- Use JDK/Zulu 21. Run Gradle through context-mode, not Bash. Every JVM unit-test task uses `--rerun-tasks`.
- Parse test totals from `app/build/test-results/**/*.xml` with `xml.etree.ElementTree`; console summaries are not evidence.
- Use Koin Annotations and the Koin compiler plugin, not KSP, for dependency injection. Annotate scan-visible implementations; add `AppModule` providers only for Room DAOs or external/platform types the scan cannot infer.
- Framework services have public no-argument constructors, are not Koin-created singletons, and use lazy Koin injection only after foreground promotion.
- Keep the existing `ThorJobKind` IDs and ordinals unchanged. Preserve all public launcher and watcher signatures listed below.
- Room is the only durable authority. An Intent never carries a task payload and a service instance never owns authoritative queue state.
- Data and privilege queues are independent. Each has one durable FIFO consumer; both may run concurrently.
- `DefaultPackageOperationCoordinator` remains the same-package exclusion boundary. Root `ARCHIVE` and `SWEEP` lanes remain unchanged.
- Passphrases and derived archive keys never enter Room, Intent extras, SavedState, logs, notifications, or WorkManager `Data`.
- Preserve caller ownership of passphrase `CharArray`s and `ArchiveKeyHolder`'s one-hour unused-key lifetime.
- Preserve startup Shizuku authorization. Shizuku Manager's dialog is the consent boundary; do not add a Thor confirmation layer or remove `ShizukuPermissionHandler`.
- Auto-freeze and auto-reinstall remain receiver/coroutine flows. They do not start either new user-initiated foreground service.
- `DataSyncService` uses `dataSync`; `PrivilegeSweepService` uses `specialUse`. Do not classify privileged package mutations as `dataSync`, and do not describe file copying as `specialUse`.
- On API 35+, background `dataSync` foreground services share a six-hour allowance per rolling 24 hours across the app. `onTimeout(startId, foregroundServiceType)` must stop new claims, persist interruption, unwind the active child, and stop within the platform grace period; WorkManager is not a quota escape on API 36+.
- New work never creates a `WorkRequest`. Released archive/export Workers remain only for compatibility drain in this PR.
- Terminal history is retained for 24 hours. Ready-share outputs are retained for 24 hours unless explicitly consumed by existing output policy.
- Every ordinary failure stored in Room is a bounded typed code with sanitized arguments. Never persist raw shell commands/output, stack traces, decrypted data, credentials, or private paths unrelated to cleanup.
- Keep the temporary debug-only `ServiceQueueLatencyProbe` event vocabulary (`tap`, `logger_visible`, `durable_accepted`, `execution_admitted`, `first_operation`) semantically equivalent across the WorkManager baseline and new service paths. Task 19 removes the probe and every hook after recording post-cutover evidence; no probe code ships in the final branch.

## Temporary latency-marker ownership

The Task 1 baseline established these exact semantic owners. Later tasks may move an owner only as stated in this table; they must remove the replaced hook in the same compiling commit so one run never emits two candidate timestamps for one event. The probe's one-shot suppression is a guard, not permission to keep duplicate owners.

| Operation / event | WorkManager baseline owner | Post-cutover owner | Owning task |
|---|---|---|---:|
| Export `tap` | `presentation/appList/ExportBottomSheet.kt`, final Export callback | Same call site, immediately before durable acceptance | 9 |
| Export `logger_visible` | `presentation/appList/ExportBottomSheet.kt`, first drawn active progress surface | `presentation/queue/TaskDetailScreen.kt`, first drawn active logger surface for the selected export task | 15, activated by 16 |
| Export `durable_accepted` | `data/backup/job/ExportJobLauncherImpl.kt`, after awaited WorkManager enqueue | Same file, after the Room insert transaction returns and before the service wake | 9 |
| Export `execution_admitted` | `data/backup/job/ThorJobWorker.kt`, after successful `setForeground` | `data/backup/job/DataSyncCoordinator.kt`, after foreground promotion is known valid, the task/item claims commit, and immediately before runner dispatch | 9 |
| Export `first_operation` | `data/repository/AppBundleBuilderImpl.kt`, immediately before the first direct staged-byte `output.write` | Same shared builder boundary; do not add an earlier runner hook | 7 preserves; 9 consumes |
| Privilege sweep `tap` | `presentation/main/MainViewModel.kt`, confirmed Reinstall callback | Same confirmed-action boundary before durable acceptance | 16 |
| Privilege sweep `logger_visible` | `presentation/widgets/FreezeLoggerDialog.kt`, first drawn active logger frame | `presentation/queue/TaskDetailScreen.kt`, first drawn active logger surface for the selected sweep | 15, activated by 16 |
| Privilege sweep `durable_accepted` | `data/freezer/DefaultPrivilegeSweepController.kt`, after `createOrFindEquivalent` returns | Same file, after Room acceptance returns and before service wake | 11 |
| Privilege sweep `execution_admitted` | `data/backup/job/ThorJobWorker.kt`, after the non-foreground Worker's initial notification publication | `data/freezer/PrivilegeSweepDrainCoordinator.kt`, after foreground promotion is known valid, request/target claims commit, and immediately before target dispatch | 11 |
| Privilege sweep `first_operation` | `data/freezer/PrivilegeSweepWorker.kt`, immediately before target-executor dispatch | `data/freezer/PrivilegeSweepDrainCoordinator.kt`, at the identical pre-dispatch boundary | 11; Task 12 removes the tombstone Worker's obsolete hook |

Task 19's cleanup set is exact: `ServiceQueueLatencyProbe.kt`, `ExportBottomSheet.kt`, `TaskDetailScreen.kt`, `ExportJobLauncherImpl.kt`, `ThorJobWorker.kt`, `DataSyncCoordinator.kt`, `AppBundleBuilderImpl.kt`, `MainViewModel.kt`, `DefaultPrivilegeSweepController.kt`, and `PrivilegeSweepDrainCoordinator.kt`. `PrivilegeSweepWorker.kt` and `FreezeLoggerDialog.kt` lose their baseline hooks earlier when Task 12 tombstones the Worker and Task 16 deletes the dialog; Task 19's zero-reference scan still covers both by path/history.

## Preserved Interfaces

Implementation must keep these signatures source-compatible:

```kotlin
enum class ThorJobKind(val id: String)

data class ThorJobProgress(
    val stage: ThorJobStage,
    val label: String,
    val completed: Long = 0L,
    val total: Long = 0L,
)

suspend fun ArchiveJobLauncher.startBackup(
    request: ArchiveBackupRequest,
    passphrase: CharArray,
): UUID?

suspend fun ArchiveJobLauncher.startRestore(
    request: ArchiveRestoreRequest,
    passphrase: CharArray,
    salt: ByteArray,
    iterations: Int,
): UUID?

suspend fun ExportJobLauncher.startExport(request: AppExportRequest): UUID?
fun ThorJobWatcher.status(jobId: UUID): Flow<ThorJobStatus>
fun ThorJobWatcher.runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?>
fun ThorJobWatcher.cancel(jobId: UUID)

fun ArchiveKeyHolder.put(jobId: String, key: SecretKey)
fun ArchiveKeyHolder.take(jobId: String): SecretKey?
fun ArchiveKeyHolder.drop(jobId: String)

fun LaunchSweepBarrier.markSwept()
suspend fun LaunchSweepBarrier.awaitSwept(
    timeoutMs: Long = SWEEP_WAIT_TIMEOUT_MS,
): Boolean
```

Preserve the behavior and call order of `runArchiveRestorePreflight`, `archiveExecutionContext`, `String.boundedForJobData`, `restoreFailureReason`, `obbNotice`, `refusalReason`, and `exportFailureReason`. Runner contracts must not contain `CoroutineWorker`, `WorkerParameters`, `androidx.work.Result`, `WorkInfo`, or `Operation`.

## Locked File Structure

New Room entities and DAOs belong in the existing package:

```text
app/src/main/java/com/valhalla/thor/data/source/local/room/
```

Do not create `data/source/local/entity/` or `data/source/local/dao/` directories.

Primary new files:

```text
app/src/main/java/com/valhalla/thor/domain/model/DataTask.kt
app/src/main/java/com/valhalla/thor/domain/model/QueuedTask.kt
app/src/main/java/com/valhalla/thor/domain/repository/TaskQueueRepository.kt
app/src/main/java/com/valhalla/thor/domain/repository/TaskActionController.kt
app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskEntity.kt
app/src/main/java/com/valhalla/thor/data/source/local/room/ArchiveTaskDetailEntity.kt
app/src/main/java/com/valhalla/thor/data/source/local/room/ExportTaskDetailEntity.kt
app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskItemEntity.kt
app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskOutputEntity.kt
app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskDaoModels.kt
app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskDao.kt
app/src/main/java/com/valhalla/thor/data/source/local/room/PrivilegeSweepExecutionModels.kt
app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskStore.kt
app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskAcceptance.kt
app/src/main/java/com/valhalla/thor/data/service/ServiceStartResult.kt
app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskRunner.kt
app/src/main/java/com/valhalla/thor/data/backup/job/RoomDataTaskSinks.kt
app/src/main/java/com/valhalla/thor/data/backup/job/LegacyWorkerDataTaskSinks.kt
app/src/main/java/com/valhalla/thor/data/backup/job/RestoreSourceGrantHolder.kt
app/src/main/java/com/valhalla/thor/data/backup/job/ArchiveBackupTaskRunner.kt
app/src/main/java/com/valhalla/thor/data/backup/job/ArchiveRestoreTaskRunner.kt
app/src/main/java/com/valhalla/thor/data/backup/job/AppExportTaskRunner.kt
app/src/main/java/com/valhalla/thor/data/backup/job/SharePrepareTaskRunner.kt
app/src/main/java/com/valhalla/thor/data/backup/job/LegacyDataWorkDrainGate.kt
app/src/main/java/com/valhalla/thor/data/backup/job/DataSyncCoordinator.kt
app/src/main/java/com/valhalla/thor/data/backup/job/DataSyncServiceStarter.kt
app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskCancellationCoordinator.kt
app/src/main/java/com/valhalla/thor/data/backup/service/DataSyncServiceNotification.kt
app/src/main/java/com/valhalla/thor/data/backup/service/DataTaskCancelReceiver.kt
app/src/main/java/com/valhalla/thor/data/backup/service/DataSyncService.kt
app/src/main/java/com/valhalla/thor/data/service/ForegroundTaskWakeLock.kt
app/src/main/java/com/valhalla/thor/data/service/ForegroundServiceNotificationCapability.kt
app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepDrainCoordinator.kt
app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepServiceStarter.kt
app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepCancellationCoordinator.kt
app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepServiceNotification.kt
app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepCancelReceiver.kt
app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorkManagerCutover.kt
app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepService.kt
app/src/main/java/com/valhalla/thor/data/repository/RoomTaskQueueRepository.kt
app/src/main/java/com/valhalla/thor/data/repository/DefaultTaskActionController.kt
app/src/main/java/com/valhalla/thor/presentation/queue/QueueViewModel.kt
app/src/main/java/com/valhalla/thor/presentation/queue/QueueScreen.kt
app/src/main/java/com/valhalla/thor/presentation/queue/TaskDetailViewModel.kt
app/src/main/java/com/valhalla/thor/presentation/queue/TaskDetailScreen.kt
app/src/main/java/com/valhalla/thor/presentation/navigation/TaskNavigationTargets.kt
app/src/main/java/com/valhalla/thor/presentation/launcher/TaskQueueLaunchActivity.kt
app/src/main/java/com/valhalla/thor/presentation/share/ShareIntentFactory.kt
app/src/main/java/com/valhalla/thor/presentation/share/ReadyShareRetentionSweeper.kt
app/src/main/java/com/valhalla/thor/presentation/launcher/ShareHandoffActivity.kt
```

---

### Task 1: Capture the WorkManager latency baseline

**Files:**
- Create: `docs/workers/service-queue-latency-baseline.md`
- Create: `app/src/main/java/com/valhalla/thor/util/ServiceQueueLatencyProbe.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/ExportBottomSheet.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/ExportJobLauncherImpl.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobWorker.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/AppBundleBuilderImpl.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepController.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorker.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/widgets/FreezeLoggerDialog.kt`

**Interfaces:**
- Consumes: current WorkManager archive/export and sweep launch paths.
- Produces: twenty-run per-operation baseline distributions used by Task 19.

- [ ] **Step 1: Add evidence-only markers, then build and install the behaviorally unchanged baseline**

The unchanged build was audited and cannot expose exact semantic timestamps without observer-delay fabrication. Add a temporary debug-only `ServiceQueueLatencyProbe` and the smallest call-site hooks needed to emit one in-memory correlated run ID and `SystemClock.elapsedRealtimeNanos()` for these stable events: `tap`, `logger_visible`, `durable_accepted`, `execution_admitted`, and `first_operation`. A benchmark runs only one export or one sweep at a time, so a per-operation active token in the debug probe is sufficient; do not change any public launcher signature or durable payload merely for correlation. Marker output must contain only operation class, opaque run ID, event name, and monotonic timestamp—never package names, URIs, paths, shell commands/output, secrets, or stack traces. Release builds must emit nothing and execution behavior/order must remain unchanged. Keep the probe and hooks for Task 19's equivalent post-cutover measurements; Task 19 removes them after recording the comparison.

Run through context-mode:

```shell
./gradlew :app:assembleFossDebug --stacktrace
```

Install the resulting Foss debug APK on the designated emulator and select Shizuku as the working privilege mode.

- [ ] **Step 2: Measure at least twenty warm exports and twenty warm sweeps**

For every run, record monotonic timestamps for tap, logger visible, durable Room acceptance, WorkManager running/foreground admission, and first byte/target operation. Use the same emulator, build type, test app, export destination, and sweep target for every baseline and post-cutover run.

- [ ] **Step 3: Write the baseline table**

Write `# Service Queue Latency Baseline`, followed by the exact emulator serial/API level and `Build: Foss debug, WorkManager implementation`. Add a table with columns for class, run count, logger p50, Room p50, foreground p50, and first-operation p50. Include one fully measured row for Export and one for Privilege sweep, each with 20 runs, then list every raw observation chronologically. Do not commit the document until every device and measurement field contains observed data.

- [ ] **Step 4: Verify the baseline is reproducible**

Check that every row names its run number and that no run was dropped. Report distributions and raw counts, not one best-case sample.

- [ ] **Step 5: Commit the evidence**

```shell
git add docs/workers/service-queue-latency-baseline.md app/src/main/java/com/valhalla/thor/util/ServiceQueueLatencyProbe.kt app/src/main/java/com/valhalla/thor/presentation/appList/ExportBottomSheet.kt app/src/main/java/com/valhalla/thor/data/backup/job/ExportJobLauncherImpl.kt app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobWorker.kt app/src/main/java/com/valhalla/thor/data/repository/AppBundleBuilderImpl.kt app/src/main/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepController.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorker.kt app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt app/src/main/java/com/valhalla/thor/presentation/widgets/FreezeLoggerDialog.kt
git commit -m "docs(workers): capture service queue latency baseline"
```

### Task 2: Define shared durable task and UI contracts

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/DataTask.kt`
- Create: `app/src/main/java/com/valhalla/thor/domain/model/QueuedTask.kt`
- Create: `app/src/main/java/com/valhalla/thor/domain/repository/TaskQueueRepository.kt`
- Create: `app/src/main/java/com/valhalla/thor/domain/repository/TaskActionController.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ar/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-pt/strings.xml`
- Modify: `app/src/main/res/values-pt-rBR/strings.xml`
- Modify: `app/src/main/res/values-pl/strings.xml`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/QueuedTaskTest.kt`
- Modify test: `app/src/test/java/com/valhalla/thor/util/LocalePolicyTest.kt`

**Interfaces:**
- Consumes: `UUID`, `Flow`, current archive/export/sweep operation models.
- Produces: queue-neutral models consumed by Room stores, services, and Compose UI.

- [ ] **Step 1: Write failing model tests**

Pin these invariants against the production action policy, not hand-built fixtures:

```kotlin
@Test fun actionRequiredIsNeverAcknowledgable() {
    val actions = TaskActionPolicy.actionsFor(
        phase = TaskLifecyclePhase.WAITING_FOR_AUTH,
        requirement = TaskActionRequirement.ArchiveAuthentication("pkg", DataTaskKind.ARCHIVE_RESTORE),
    )
    assertFalse(TaskAction.ACKNOWLEDGE in actions)
    assertTrue(TaskAction.AUTHENTICATE_ARCHIVE in actions)
}

@Test fun privilegeAndUnknownSweepActionsAreDistinct() {
    assertEquals(
        setOf(TaskAction.AUTHORIZE_PRIVILEGE),
        TaskActionPolicy.actionsFor(
            TaskLifecyclePhase.WAITING_FOR_PRIVILEGE,
            TaskActionRequirement.PrivilegeAuthorization,
        ),
    )
    assertEquals(
        setOf(TaskAction.AUTHORIZE_SWEEP_RETRY),
        TaskActionPolicy.actionsFor(
            TaskLifecyclePhase.INTERRUPTED_REVIEW,
            unknownSweepRequirement,
        ),
    )
}

@Test fun expiredShareCanOnlyBeAcknowledged() {
    assertEquals(
        setOf(TaskAction.ACKNOWLEDGE),
        TaskActionPolicy.actionsFor(TaskLifecyclePhase.EXPIRED, null),
    )
}

@Test fun provisionalAndObserverPhasesArePresentationOnly() {
    assertFalse(TaskLifecyclePhase.STARTING.isPersistable)
    assertFalse(TaskLifecyclePhase.OBSERVER_FAILURE.isPersistable)
}

@Test fun queueKindDoesNotDefineCrossQueueOrder() {
    assertNotEquals(TaskQueueKind.DATA, TaskQueueKind.PRIVILEGE)
}
```

- [ ] **Step 2: Run the tests and verify RED**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.domain.model.QueuedTaskTest' --tests 'com.valhalla.thor.util.LocalePolicyTest' --stacktrace
```

Expected failure: unresolved queue model types.

- [ ] **Step 3: Add the exact domain shape**

Define queue-neutral types with stable task IDs and no Room entities. `DataTask.kt` owns the exact durable vocabulary shared by DAO and runner layers:

```kotlin
enum class DataTaskKind { ARCHIVE_BACKUP, ARCHIVE_RESTORE, APP_EXPORT, SHARE_PREPARE }

enum class DataTaskState {
    QUEUED, STAGING_SOURCE, RUNNING, CANCEL_REQUESTED,
    WAITING_FOR_AUTH, WAITING_FOR_SOURCE, INTERRUPTED_REVIEW,
    READY, READY_PARTIAL, START_BLOCKED, START_BLOCKED_NOTIFICATION,
    SUCCEEDED, PARTIAL, FAILED, CANCELLED, EXPIRED,
}

enum class DataTaskItemState { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }
enum class DataTaskItemTerminalState { SUCCEEDED, FAILED, CANCELLED }
enum class DataTaskOutputState { STAGING, READY, PUBLISHED, SHARED, DISMISSED, EXPIRED }
enum class DataTaskStage { PREPARING, STAGING_SOURCE, MEASURING, CAPTURING, WRITING, INSTALLING, RESTORING, PUBLISHING, FINISHING }
enum class DataTaskInterruption { NONE, AUTHENTICATION_REQUIRED, SOURCE_REQUIRED, DESTRUCTIVE_RESTORE_REVIEW }

@JvmInline
value class DataTaskResultCode(val value: String) {
    init { require(value.matches(Regex("[A-Z0-9_]{1,64}"))) }
}

data class DataTaskMessage(
    val code: DataTaskResultCode,
    val arguments: List<String> = emptyList(),
)

data class RestoreMutationBreadcrumb(
    val packageName: String,
    val appLabel: String,
    val startedAtEpochMs: Long,
)

sealed interface StoredDataDestination {
    data object ArchiveStore : StoredDataDestination
    data object Downloads : StoredDataDestination
    data object TaskPrivateStorage : StoredDataDestination
    data class PersistedTreeGrant(val grantIdentity: String) : StoredDataDestination
}

sealed interface StoredRestoreSource {
    data object AwaitingTransientGrant : StoredRestoreSource
    data class PersistedGrant(val grantIdentity: String) : StoredRestoreSource
    data class PrivateCopy(val privateRelativePath: String) : StoredRestoreSource
}

enum class DataTaskPublicationPolicy { PUBLIC_DOCUMENT, PRIVATE_SHARE_WITH_24_HOUR_EXPIRY }

sealed interface StoredDataTaskDetail {
    val deterministicStagingIdentity: String
    data class ArchiveBackup(
        val packageName: String,
        val dataClassIds: List<String>,
        val includeBundle: Boolean,
        val kdfSaltBase64: String,
        val destination: StoredDataDestination,
        override val deterministicStagingIdentity: String,
    ) : StoredDataTaskDetail
    data class ArchiveRestore(
        val expectedPackageName: String,
        val dataClassIds: List<String>,
        val restoreObb: Boolean,
        val source: StoredRestoreSource,
        val mutationBreadcrumb: RestoreMutationBreadcrumb?,
        override val deterministicStagingIdentity: String,
    ) : StoredDataTaskDetail
    data class AppExport(
        val requestedFormat: BundleFormat,
        val destination: StoredDataDestination,
        val namingLabel: String,
        val publicationPolicy: DataTaskPublicationPolicy,
        override val deterministicStagingIdentity: String,
    ) : StoredDataTaskDetail
    data class SharePrepare(
        val requestedFormat: BundleFormat,
        val publicationPolicy: DataTaskPublicationPolicy,
        override val deterministicStagingIdentity: String,
    ) : StoredDataTaskDetail
}

data class DataTaskCheckpoint(
    val stage: DataTaskStage,
    val completed: Long,
    val total: Long,
    val activeItemOrdinal: Int?,
    val activeItemLabel: String?,
    val destructiveStarted: Boolean,
    val restoreMutationBreadcrumb: RestoreMutationBreadcrumb?,
    val recordedAtEpochMs: Long,
)

data class NewDataTaskOutput(
    val outputId: UUID,
    val privateRelativePath: String?,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val state: DataTaskOutputState,
    val expiresAtEpochMs: Long?,
)

data class DataTaskItemResult(
    val terminalState: DataTaskItemTerminalState,
    val resultCode: DataTaskResultCode,
    val warnings: List<DataTaskMessage>,
    val outputs: List<NewDataTaskOutput>,
    val finishedAtEpochMs: Long,
)

sealed interface DataTaskRunOutcome {
    data class ItemCompleted(val result: DataTaskItemResult) : DataTaskRunOutcome
    data class WaitingForAuthentication(val resultCode: DataTaskResultCode) : DataTaskRunOutcome
    data class WaitingForSource(val resultCode: DataTaskResultCode) : DataTaskRunOutcome
    data class InterruptedReview(val resultCode: DataTaskResultCode, val breadcrumb: RestoreMutationBreadcrumb) : DataTaskRunOutcome
    data class TaskFailed(val resultCode: DataTaskResultCode, val arguments: List<String> = emptyList()) : DataTaskRunOutcome
    data object Cancelled : DataTaskRunOutcome
    data object OwnershipLost : DataTaskRunOutcome
}
```

`NewDataTaskOutput` is the runner-to-store output proposal and remains Room-independent. Stored grant identities are opaque hashes matched against current persisted grants, not URI text. Private paths are normalized relative paths under the task-owned staging root. Validate detail/kind pairing, bounded argument/warning collections, non-negative progress, monotonic `destructiveStarted`, and restore-only breadcrumbs. Never add automatic destructive-restore replay.

`QueuedTask.kt` owns the display/action vocabulary:

```kotlin
enum class TaskQueueKind { DATA, PRIVILEGE }

enum class TaskLifecyclePhase {
    STARTING, QUEUED, RUNNING, STOPPING, WAITING_FOR_AUTH, WAITING_FOR_SOURCE,
    WAITING_FOR_PRIVILEGE, INTERRUPTED_REVIEW, READY, READY_PARTIAL,
    START_BLOCKED, START_BLOCKED_NOTIFICATION, SUCCEEDED, PARTIAL, FAILED,
    CANCELLED, EXPIRED, OBSERVER_FAILURE;

    val isPersistable: Boolean get() = this != STARTING && this != OBSERVER_FAILURE
}

enum class TaskAction {
    CANCEL, AUTHENTICATE_ARCHIVE, PROVIDE_SOURCE, REVIEW_RESTORE,
    AUTHORIZE_PRIVILEGE, AUTHORIZE_SWEEP_RETRY, RETRY, RESUME, SHARE,
    OPEN_NOTIFICATION_SETTINGS, ACKNOWLEDGE,
}

sealed interface TaskActionRequirement {
    data class ArchiveAuthentication(val packageName: String, val kind: DataTaskKind) : TaskActionRequirement
    data class RestoreSource(val expectedPackageName: String) : TaskActionRequirement
    data class RestoreInterruptionReview(val breadcrumb: RestoreMutationBreadcrumb) : TaskActionRequirement
    data object PrivilegeAuthorization : TaskActionRequirement
    data class SweepRetryAuthorization(val targetOrdinal: Int, val packageName: String, val operation: PrivilegeSweepOperation) : TaskActionRequirement
    data class PreparedShare(val outputIds: List<UUID>) : TaskActionRequirement
    data class NotificationSettings(val channelId: String) : TaskActionRequirement
}

object TaskActionPolicy {
    fun actionsFor(
        phase: TaskLifecyclePhase,
        requirement: TaskActionRequirement?,
    ): Set<TaskAction>
}

data class TaskProgress(val completed: Long, val total: Long, val stageLabel: String?)

data class QueuedTaskSummary(
    val taskId: UUID,
    val queueKind: TaskQueueKind,
    val operationId: String,
    val titleArguments: List<String>,
    val sequence: Long,
    val phase: TaskLifecyclePhase,
    val progress: TaskProgress,
    val activeItemLabel: String?,
    val actionRequirement: TaskActionRequirement?,
    val actions: Set<TaskAction>,
    val terminalAtEpochMs: Long?,
    val retainUntilEpochMs: Long?,
    val rootLaneDegraded: Boolean,
)

enum class TaskLogLevel { INFO, SUCCESS, WARNING, ERROR }
data class TaskLogLine(
    val order: Long,
    val messageCode: String,
    val arguments: List<String> = emptyList(),
    val level: TaskLogLevel = TaskLogLevel.INFO,
)
data class QueuedTaskDetail(
    val summary: QueuedTaskSummary,
    val lines: List<TaskLogLine>,
    val resultCode: String?,
    val warningCodes: List<String>,
)
```

The production action policy maps archive authentication, restore source, destructive-restore review, privilege authorization, unknown-target retry authorization, ready output sharing, and notification settings only when the matching typed requirement exists. `EXPIRED` exposes only `ACKNOWLEDGE`; `STARTING`, `STOPPING`, action-required states without their exact requirement, and `OBSERVER_FAILURE` expose no acknowledgement.

- [ ] **Step 4: Add repository boundaries**

```kotlin
interface TaskQueueRepository {
    val tasks: Flow<List<QueuedTaskSummary>>
    fun observe(taskId: UUID): Flow<QueuedTaskDetail?>
}

sealed interface TaskUiRoute {
    val taskId: UUID
    data class AuthenticateArchive(override val taskId: UUID, val packageName: String, val kind: DataTaskKind) : TaskUiRoute
    data class PickRestoreSource(override val taskId: UUID, val expectedPackageName: String) : TaskUiRoute
    data class ReviewInterruptedRestore(override val taskId: UUID, val breadcrumb: RestoreMutationBreadcrumb) : TaskUiRoute
    data class AuthorizePrivilege(override val taskId: UUID) : TaskUiRoute
    data class ConfirmSweepRetry(override val taskId: UUID, val targetOrdinal: Int, val packageName: String, val operation: PrivilegeSweepOperation) : TaskUiRoute
    data class SharePreparedOutputs(override val taskId: UUID, val outputIds: List<UUID>) : TaskUiRoute
    data class OpenNotificationSettings(override val taskId: UUID, val channelId: String) : TaskUiRoute
}

enum class TaskActionRejection {
    NOT_FOUND, INVALID_STATE, STALE_PROJECTION, AUTHORIZATION_NOT_GRANTED,
    OUTPUT_EXPIRED, START_REJECTED,
}

sealed interface TaskActionDispatch {
    data object Applied : TaskActionDispatch
    data class Route(val destination: TaskUiRoute) : TaskActionDispatch
    data class Rejected(val reason: TaskActionRejection) : TaskActionDispatch
}

interface TaskActionController {
    suspend fun perform(taskId: UUID, action: TaskAction): TaskActionDispatch
    suspend fun submitArchivePassphrase(taskId: UUID, passphrase: CharArray): TaskActionDispatch
    suspend fun submitRestoreSource(taskId: UUID, transientSourceToken: UUID): TaskActionDispatch
    suspend fun privilegeAuthorizationReturned(taskId: UUID): TaskActionDispatch
    suspend fun authorizeSweepTargetRetry(taskId: UUID, targetOrdinal: Int): TaskActionDispatch
}
```

The repository is read-only; none of its methods may claim, complete, or reorder work. `perform` returns a route for ephemeral input/foreground UI rather than persisting it. Passphrases remain caller-owned `CharArray`s and restore `Uri`s live only behind a task-scoped in-memory token. Privilege authorization is Activity-owned: use the existing Shizuku/root/Dhizuku setup, call `PrivilegeManager.refresh()` on return, re-read `PrivilegeStateProvider`, and requeue only when current `hasAnyPrivilege` is true. Never persist a permission token, requested mode, credential, raw URI, or assertion that authorization succeeded.

- [ ] **Step 5: Define the complete localized presentation vocabulary before any UI consumes it**

Add all service-queue strings to every supported app locale in this contract task, before Tasks 9 and 11 create notifications or Tasks 14–16 reference `R.string` values. Include Queue; Running; Queued/action-required; Recent; empty states; data/privilege queue names; Starting; Background; Cancel; Stopping; Close; Authenticate archive; Provide source; Review restore; Authorize privilege; Authorize retry; Retry; Resume; Share; Open notification settings; Expired; all persisted/action-required state labels and reasons; service notification titles/status/action text; passphrase, restore-review, privilege-return, and target-retry dialog copy; ready-share/partial-ready/expired copy; and bounded derived logger message codes. Add the keys to `values`, `values-ar`, `values-es`, `values-fr`, `values-zh-rCN`, `values-pt`, `values-pt-rBR`, and `values-pl` in the same commit. Extend `LocalePolicyTest` to enforce exact key coverage and positional-placeholder parity. Later UI tasks consume these keys and may not bridge compilation with hard-coded user-visible strings.

- [ ] **Step 6: Run tests and commit**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.domain.model.QueuedTaskTest' --tests 'com.valhalla.thor.util.LocalePolicyTest' --stacktrace
git add app/src/main/java/com/valhalla/thor/domain/model/DataTask.kt app/src/main/java/com/valhalla/thor/domain/model/QueuedTask.kt app/src/main/java/com/valhalla/thor/domain/repository/TaskQueueRepository.kt app/src/main/java/com/valhalla/thor/domain/repository/TaskActionController.kt app/src/main/res/values/strings.xml app/src/main/res/values-ar/strings.xml app/src/main/res/values-es/strings.xml app/src/main/res/values-fr/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-pt/strings.xml app/src/main/res/values-pt-rBR/strings.xml app/src/main/res/values-pl/strings.xml app/src/test/java/com/valhalla/thor/domain/model/QueuedTaskTest.kt app/src/test/java/com/valhalla/thor/util/LocalePolicyTest.kt
git commit -m "feat(queue): define durable task contracts"
```

### Task 3: Add the complete version 9 queue schema and migration

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskEntity.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/source/local/room/ArchiveTaskDetailEntity.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/source/local/room/ExportTaskDetailEntity.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskItemEntity.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskOutputEntity.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskDaoModels.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskDao.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/source/local/room/SweepRequestEntity.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/source/local/room/SweepTargetEntity.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/source/local/room/AppDatabase.kt`
- Modify: `app/src/main/java/com/valhalla/thor/di/Modules.kt`
- Test: `app/src/androidTest/java/com/valhalla/thor/data/source/local/room/DataTaskDaoTest.kt`
- Test: `app/src/androidTest/java/com/valhalla/thor/data/source/local/room/SweepMigrationTest.kt`
- Generate: `app/schemas/com.valhalla.thor.data.source.local.room.AppDatabase/9.json`

**Interfaces:**
- Consumes: Task 2 data persistence types, the approved sweep state literals, and existing Room conventions.
- Produces: the final schema 9 entity shape, one `MIGRATION_8_9`, and `DataTaskDao` transactions used by Tasks 4–9.

- [ ] **Step 1: Write migration and DAO tests first**

Add tests proving:

```kotlin
@Test fun claimOrderUsesSequenceThenUuid() = runTest { /* insert equal-time rows; assert stable order */ }
@Test fun concurrentClaimersHaveOneWinner() = runTest { /* race two claim tokens */ }
@Test fun staleTokenCannotCheckpointOrComplete() = runTest { /* assert update count is zero */ }
@Test fun blockedTaskDoesNotBlockLaterRunnableTask() = runTest { /* waiting auth then export */ }
@Test fun cancellationAndCompletionHaveOneDurableWinner() = runTest { /* exercise both orders */ }
@Test fun finalEmptyCheckSeesAConcurrentInsert() = runTest { /* insert before transaction ends */ }
```

For the final-empty race, instrument the producer Room connection with a test-only delegated `SupportSQLiteOpenHelper`/`SupportSQLiteDatabase`: mark entry to the actual writer-begin method before delegation and mark acquisition only after delegation returns. While `onQueueEmpty` holds the final transaction, require the attempted marker and prove acquisition/commit remain incomplete; after release, prove acquisition, commit, visibility, and runnability. Do not use Room `QueryCallback`, a marker before the DAO call, timing thresholds, or thread-state polling.

Extend `SweepMigrationTest` with `migrate8To9_createsDataTablesAndPreservesSweepRows`, `migrate8To9_nonterminalSweepPreservesOrdinalAndSourcesAndMapsEveryTargetLegacyUnknown`, `migrate8To9_terminalSweepPreservesHistoryWithoutInventingPerTargetSuccess`, and a test that runs every repository-supported starting version through the real migration chain to schema 9. These tests own the only generation and validation of `9.json`; Task 4 must not mutate the entity shape or migration.

- [ ] **Step 2: Run the instrumented tests and verify RED**

```shell
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.data.source.local.room.DataTaskDaoTest,com.valhalla.thor.data.source.local.room.SweepMigrationTest --stacktrace
```

Expected failure: missing entities/DAO and version 9 schema.

- [ ] **Step 3: Create normalized data tables**

Use separate tables rather than one nullable-heavy shared table:

```kotlin
@Entity(tableName = "data_tasks", indices = [Index(value = ["queue_sequence"], unique = true)])
data class DataTaskEntity(
    @PrimaryKey @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "queue_sequence") val queueSequence: Long,
    @ColumnInfo(name = "payload_schema_version") val payloadSchemaVersion: Int,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "target_key") val targetKey: String,
    @ColumnInfo(name = "service_session_token") val serviceSessionToken: String?,
    @ColumnInfo(name = "claim_token") val claimToken: String?,
    @ColumnInfo(name = "claim_lease_expires_at_epoch_ms") val claimLeaseExpiresAtEpochMs: Long?,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "cancel_requested_at_epoch_ms") val cancelRequestedAtEpochMs: Long?,
    @ColumnInfo(name = "stage") val stage: String?,
    @ColumnInfo(name = "interruption") val interruption: String?,
    @ColumnInfo(name = "completed") val completed: Long,
    @ColumnInfo(name = "total") val total: Long,
    @ColumnInfo(name = "result_code") val resultCode: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "claimed_at_epoch_ms") val claimedAtEpochMs: Long?,
    @ColumnInfo(name = "started_at_epoch_ms") val startedAtEpochMs: Long?,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
    @ColumnInfo(name = "terminal_at_epoch_ms") val terminalAtEpochMs: Long?,
    @ColumnInfo(name = "retain_until_epoch_ms") val retainUntilEpochMs: Long?,
    @ColumnInfo(name = "acknowledged_at_epoch_ms") val acknowledgedAtEpochMs: Long?,
)
```

`ArchiveTaskDetailEntity` stores non-secret archive options, package/user identity, deterministic private staging identity, and restore mutation breadcrumb. `ExportTaskDetailEntity` stores destination grant identity, requested format, naming inputs, and publication policy. `DataTaskItemEntity` stores ordinal, package identity, state, claim token/lease, attempts, bounded result code, and deterministic staging identity. `DataTaskOutputEntity` stores output UUID, task/item foreign keys, private relative path, display name, MIME type, byte size, state, and expiry. Do not store a passphrase, derived key, raw restore URI text, decrypted content, or public absolute path.

Create `DataTaskDaoModels.kt` with the exact Room boundary values below. Claim tokens exist only in claimed models, never snapshots/UI:

```kotlin
data class NewDataTaskItem(
    val ordinal: Int,
    val packageName: String,
    val displayLabel: String?,
    val deterministicStagingIdentity: String,
)

data class NewDataTaskRow(
    val taskId: String,
    val payloadSchemaVersion: Int,
    val kind: DataTaskKind,
    val targetKey: String,
    val initialState: DataTaskState,
    val detail: StoredDataTaskDetail,
    val items: List<NewDataTaskItem>,
    val createdAtEpochMs: Long,
)

data class DataTaskItemSnapshot(
    val ordinal: Int,
    val packageName: String,
    val displayLabel: String?,
    val state: DataTaskItemState,
    val attemptCount: Int,
    val resultCode: DataTaskResultCode?,
    val deterministicStagingIdentity: String,
    val startedAtEpochMs: Long?,
    val finishedAtEpochMs: Long?,
)

data class DataTaskOutputSnapshot(
    val outputId: UUID,
    val itemOrdinal: Int,
    val privateRelativePath: String?,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val state: DataTaskOutputState,
    val expiresAtEpochMs: Long?,
)

data class DataTaskSnapshot(
    val taskId: UUID,
    val queueSequence: Long,
    val payloadSchemaVersion: Int,
    val kind: DataTaskKind,
    val state: DataTaskState,
    val targetKey: String,
    val detail: StoredDataTaskDetail,
    val stage: DataTaskStage?,
    val completed: Long,
    val total: Long,
    val attemptCount: Int,
    val interruption: DataTaskInterruption,
    val resultCode: DataTaskResultCode?,
    val cancelRequestedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val claimedAtEpochMs: Long?,
    val startedAtEpochMs: Long?,
    val updatedAtEpochMs: Long,
    val terminalAtEpochMs: Long?,
    val retainUntilEpochMs: Long?,
    val acknowledgedAtEpochMs: Long?,
    val items: List<DataTaskItemSnapshot>,
    val outputs: List<DataTaskOutputSnapshot>,
)

data class ClaimedDataTask(
    val taskId: UUID,
    val queueSequence: Long,
    val payloadSchemaVersion: Int,
    val kind: DataTaskKind,
    val targetKey: String,
    val detail: StoredDataTaskDetail,
    val serviceSessionToken: String,
    val claimToken: String,
    val claimLeaseExpiresAtEpochMs: Long,
    val attemptCount: Int,
    val lastCheckpoint: DataTaskCheckpoint?,
)

data class ClaimedDataTaskItem(
    val taskId: UUID,
    val ordinal: Int,
    val packageName: String,
    val displayLabel: String?,
    val deterministicStagingIdentity: String,
    val claimToken: String,
    val claimLeaseExpiresAtEpochMs: Long,
    val attemptCount: Int,
)

sealed interface DataTaskRecovery {
    data object Resume : DataTaskRecovery
    data object WaitingForAuthentication : DataTaskRecovery
    data object WaitingForSource : DataTaskRecovery
    data class InterruptedReview(val breadcrumb: RestoreMutationBreadcrumb) : DataTaskRecovery
    data class Failed(val resultCode: DataTaskResultCode) : DataTaskRecovery
}

data class DataTaskRecoveryCandidate(
    val taskId: UUID,
    val kind: DataTaskKind,
    val interruptedItemOrdinal: Int?,
    val previousServiceSessionToken: String,
    val destructiveStarted: Boolean,
    val restoreMutationBreadcrumb: RestoreMutationBreadcrumb?,
    val recovery: DataTaskRecovery,
)

sealed interface DataTaskCancellationDecision {
    data object NotFound : DataTaskCancellationDecision
    data class AlreadyTerminal(val snapshot: DataTaskSnapshot) : DataTaskCancellationDecision
    data class Settled(val snapshot: DataTaskSnapshot) : DataTaskCancellationDecision
    data class InterruptActive(val snapshot: DataTaskSnapshot, val activeItemOrdinal: Int?) : DataTaskCancellationDecision
}
```

The insertion transaction allocates `queueSequence`; callers never provide it. Validate canonical UUIDs, non-empty contiguous items, non-negative counts/sizes, bounded codes/arguments, and `completed <= total` when total is positive.

Finalize the sweep half of schema 9 in the same migration. `SweepRequestEntity` retains immutable operation, freezer mode, user, source associations, and creation identity while adding payload schema version, queue sequence, request state, neutral execution ID, service session token, claim token/lease, claim/start/update timestamps, attempt count, cancellation metadata, non-null target-derived aggregates, acknowledgement, terminal timestamp, and retention timestamp. `SweepTargetEntity` adds ordinal-stable claim token/lease, attempt count, start/finish timestamps, state, and a bounded typed result code. Existing aggregate-only nonterminal rows migrate each target to `LEGACY_UNKNOWN`; terminal history is preserved without inventing target success. Never persist raw shell output.

- [ ] **Step 4: Implement transactional DAO operations**

Expose these exact operations, implemented with select-plus-CAS `@Transaction` methods:

```kotlin
suspend fun insertTask(request: NewDataTaskRow): DataTaskSnapshot
suspend fun claimOldestRunnableTask(sessionToken: String, claimToken: String, nowMs: Long, leaseUntilMs: Long): ClaimedDataTask?
suspend fun claimNextPendingItem(taskId: String, taskClaimToken: String, itemClaimToken: String, nowMs: Long, leaseUntilMs: Long): ClaimedDataTaskItem?
suspend fun checkpointClaimedTask(taskId: String, taskClaimToken: String, itemOrdinal: Int, itemClaimToken: String, checkpoint: DataTaskCheckpoint, leaseUntilMs: Long): Boolean
suspend fun completeClaimedItem(taskId: String, ordinal: Int, taskClaimToken: String, itemClaimToken: String, result: DataTaskItemResult): Boolean
suspend fun settleClaimedTask(taskId: String, taskClaimToken: String, itemOrdinal: Int, itemClaimToken: String, outcome: DataTaskRunOutcome, nowMs: Long): Boolean
suspend fun requestCancellation(taskId: String, nowMs: Long): DataTaskCancellationDecision
suspend fun recoverClaims(
    sessionToken: String,
    nowMs: Long,
    localOwnerIsLive: (taskId: String, claimToken: String) -> Boolean,
): List<DataTaskRecoveryCandidate>
suspend fun finishClaimedTaskIfDrained(taskId: String, claimToken: String, nowMs: Long): Boolean
suspend fun readyOutputsForShare(taskId: String, nowMs: Long): List<DataTaskOutputSnapshot>
suspend fun expiredReadyOutputs(nowMs: Long): List<DataTaskOutputSnapshot>
suspend fun markReadyTaskExpiredAfterCleanup(taskId: String, outputIds: List<String>, nowMs: Long): Boolean
suspend fun hasRunnableTasks(): Boolean
```

Every mutation checks the matching claim token. The insertion transaction allocates a durable monotonic sequence. Claim queries order by sequence and UUID and exclude waiting, review, ready-share, start-blocked, and terminal states.

- [ ] **Step 5: Add `MIGRATION_8_9` and register Room objects**

Update `AppDatabase` to version 9, add all five data entities and `abstract fun dataTaskDao(): DataTaskDao`, and add the one explicit `Migration(8, 9)` that creates the data tables and evolves the sweep tables to the complete claim-aware shape above. Register `MIGRATION_8_9` alongside `MIGRATION_1_2` in `Modules.kt`; keep destructive fallback debug-only. Generate and validate `9.json` once from this final entity shape; no later task may edit the v9 schema.

- [ ] **Step 6: Verify schema and commit**

```shell
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.data.source.local.room.DataTaskDaoTest,com.valhalla.thor.data.source.local.room.SweepMigrationTest --stacktrace
git add app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskEntity.kt app/src/main/java/com/valhalla/thor/data/source/local/room/ArchiveTaskDetailEntity.kt app/src/main/java/com/valhalla/thor/data/source/local/room/ExportTaskDetailEntity.kt app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskItemEntity.kt app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskOutputEntity.kt app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskDaoModels.kt app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskDao.kt app/src/main/java/com/valhalla/thor/data/source/local/room/SweepRequestEntity.kt app/src/main/java/com/valhalla/thor/data/source/local/room/SweepTargetEntity.kt app/src/main/java/com/valhalla/thor/data/source/local/room/AppDatabase.kt app/src/main/java/com/valhalla/thor/di/Modules.kt app/src/androidTest/java/com/valhalla/thor/data/source/local/room/DataTaskDaoTest.kt app/src/androidTest/java/com/valhalla/thor/data/source/local/room/SweepMigrationTest.kt app/schemas/com.valhalla.thor.data.source.local.room.AppDatabase/9.json
git commit -m "feat(database): add complete durable queue schema"
```

### Task 4: Add privilege sweep request and target claim transactions

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/source/local/room/PrivilegeSweepExecutionModels.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/source/local/room/PrivilegeSweepDao.kt`
- Test: `app/src/androidTest/java/com/valhalla/thor/data/source/local/room/PrivilegeSweepDaoTest.kt`

**Interfaces:**
- Consumes: Task 2 queue/action vocabulary and Task 3's final schema 9 sweep entity shape.
- Produces: exact sweep claim/result/recovery models plus target-granular CAS transactions used by Tasks 10–11 without any further schema change.

- [ ] **Step 1: Write RED tests for sweep claims**

Add named tests for:

```kotlin
legacyUnknownTargetIsNotClaimedUntilReconciledOrExplicitlyResumed()
requestAndTargetClaimsEachHaveOneWinner()
staleRequestOrTargetTokenCannotWrite()
completionAfterCancellationCannotResurrectTarget()
aggregatesAlwaysEqualTargetStates()
finalEmptyCheckSeesAConcurrentInsert()
```

For `finalEmptyCheckSeesAConcurrentInsert`, use the same test-only open-helper/database wrapper established by Task 3: signal and hold from the queue-empty callback after the final-check transaction is active; mark the producer's entry to the actual writer-begin method before delegation and acquisition only after delegation returns; require the attempted marker while proving acquisition/commit remain incomplete; then release the stop decision and verify acquisition, commit, visibility, and runnability. Do not use Room `QueryCallback`, a marker before the DAO call, timing thresholds, or thread-state polling.

Task 3 already proves migration and schema identity. These tests exercise only transaction and CAS behavior against schema 9.

- [ ] **Step 2: Run the tests and verify RED**

```shell
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.data.source.local.room.PrivilegeSweepDaoTest --stacktrace
```

Expected failure: missing claim-aware DAO methods.

- [ ] **Step 3: Add CAS transactions while preserving temporary compatibility**

Create `PrivilegeSweepExecutionModels.kt` with exact stored-state and DAO-boundary types:

```kotlin
enum class StoredSweepRequestState {
    QUEUED, RUNNING, CANCEL_REQUESTED, BLOCKED,
    SUCCEEDED, PARTIAL, CANCELLED, FAILED,
}
enum class StoredSweepBlockReason {
    PRIVILEGE_AUTHORIZATION_REQUIRED, START_BLOCKED, START_BLOCKED_NOTIFICATION,
}
enum class StoredSweepTargetState {
    PENDING, RUNNING, SUCCEEDED, FAILED, BUSY, CANCELLED, UNKNOWN, LEGACY_UNKNOWN,
}
enum class StoredSweepTargetTerminalState { SUCCEEDED, FAILED, BUSY }

@JvmInline
value class SweepTargetResultCode(val value: String) {
    init { require(value.matches(Regex("[A-Z0-9_]{1,64}"))) }
}

data class ClaimedSweepRequest(
    val requestId: String,
    val queueSequence: Long,
    val payloadSchemaVersion: Int,
    val executionId: String,
    val operation: PrivilegeSweepOperation,
    val freezerMode: FreezerMode?,
    val userId: Int,
    val source: PrivilegeSweepSource,
    val sourceAssociations: Set<String>,
    val targetCount: Int,
    val succeeded: Int,
    val failed: Int,
    val busy: Int,
    val unresolved: Int,
    val serviceSessionToken: String,
    val claimToken: String,
    val claimLeaseExpiresAtEpochMs: Long,
    val attemptCount: Int,
    val createdAtEpochMs: Long,
    val claimedAtEpochMs: Long,
)

data class ClaimedSweepTarget(
    val requestId: String,
    val ordinal: Int,
    val packageName: String,
    val claimToken: String,
    val claimLeaseExpiresAtEpochMs: Long,
    val attemptCount: Int,
    val startedAtEpochMs: Long,
)

data class StoredSweepTargetResult(
    val terminalState: StoredSweepTargetTerminalState,
    val resultCode: SweepTargetResultCode,
    val rootLaneDegraded: Boolean,
    val finishedAtEpochMs: Long,
)

sealed interface StoredSweepRecovery {
    val recoveredAtEpochMs: Long
    data class Completed(val result: StoredSweepTargetResult, override val recoveredAtEpochMs: Long) : StoredSweepRecovery
    data class Requeue(val resultCode: SweepTargetResultCode, override val recoveredAtEpochMs: Long) : StoredSweepRecovery
    data class MarkUnknown(val resultCode: SweepTargetResultCode, override val recoveredAtEpochMs: Long) : StoredSweepRecovery
}

sealed interface SweepCancellationDecision {
    data object NotFound : SweepCancellationDecision
    data class AlreadyTerminal(val requestId: String, val state: StoredSweepRequestState) : SweepCancellationDecision
    data class Settled(val requestId: String) : SweepCancellationDecision
    data class InterruptActive(val requestId: String, val activeTargetOrdinal: Int) : SweepCancellationDecision
}
```

`freezerMode` is present exactly for `FREEZE`; new execution IDs are neutral and any migrated WorkManager ID is historical metadata only. `UNKNOWN` and `LEGACY_UNKNOWN` are actionable non-runnable states. Result codes and source associations are bounded, and no exception text or shell output is stored.

Add the claim/target CAS surface below. Keep `resetForRun`, request-level increment methods, and other methods still called by the feature-branch Worker/controller as deprecated compatibility APIs through Task 16; do not break compilation while UI callers still use them. Add:

```kotlin
suspend fun claimOldestRunnableRequest(sessionToken: String, claimToken: String, nowMs: Long, leaseUntilMs: Long): ClaimedSweepRequest?
suspend fun claimNextPendingTarget(requestId: String, requestClaimToken: String, targetClaimToken: String, nowMs: Long, leaseUntilMs: Long): ClaimedSweepTarget?
suspend fun renewRequestClaim(requestId: String, claimToken: String, leaseUntilMs: Long): Boolean
suspend fun renewTargetClaim(requestId: String, ordinal: Int, claimToken: String, leaseUntilMs: Long): Boolean
suspend fun completeClaimedTarget(requestId: String, ordinal: Int, requestClaimToken: String, targetClaimToken: String, result: StoredSweepTargetResult): Boolean
suspend fun requestCancellation(requestId: String, nowMs: Long): SweepCancellationDecision
suspend fun recoverInterruptedTarget(requestId: String, ordinal: Int, recovery: StoredSweepRecovery): Boolean
suspend fun authorizeUnknownTargetRetry(requestId: String, ordinal: Int, nowMs: Long): Boolean
suspend fun finishClaimedRequestIfDrained(requestId: String, claimToken: String, nowMs: Long): Boolean
suspend fun hasRunnableRequests(): Boolean
```

Every write checks the request and/or target token appropriate to that row. Claim order is queue sequence then request UUID; target order is ordinal. `UNKNOWN` and `LEGACY_UNKNOWN` contribute to unresolved totals and are excluded from all claim queries. Only proved reconciliation or explicit user-authorized retry can move them to `PENDING`.

- [ ] **Step 4: Verify claim semantics and commit**

```shell
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.data.source.local.room.PrivilegeSweepDaoTest --stacktrace
git add app/src/main/java/com/valhalla/thor/data/source/local/room/PrivilegeSweepExecutionModels.kt app/src/main/java/com/valhalla/thor/data/source/local/room/PrivilegeSweepDao.kt app/src/androidTest/java/com/valhalla/thor/data/source/local/room/PrivilegeSweepDaoTest.kt
git commit -m "feat(database): add sweep target claim transactions"
```

### Task 5: Implement the data store and unbound persist-first acceptance

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskDao.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskStore.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskAcceptance.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/service/ServiceStartResult.kt`
- Modify test: `app/src/androidTest/java/com/valhalla/thor/data/source/local/room/DataTaskDaoTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/DataTaskAcceptanceTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/ArchiveKeyHolderTest.kt`

**Interfaces:**
- Consumes: `DataTaskDao`, Task 2 data models, existing request models, and `ArchiveKeyHolder`.
- Produces: a service-neutral `DataQueueWakeSignal`, `DataTaskAcceptance`, and accepted durable rows. No production binding or live producer changes until Task 9 atomically adds the service.

- [ ] **Step 1: Write acceptance-order tests**

Prove key placement precedes runnable insertion, insertion precedes the wake port, insert failure drops the key, wake rejection retains the task as start-blocked, and caller cancellation does not delete an already accepted task. Add deterministic DAO coverage proving public load and Room observation include ordered item/output changes; only an exact `QUEUED` or `STAGING_SOURCE`, unclaimed, nonterminal, non-cancel-requested row can compare-and-set to `START_BLOCKED` or `START_BLOCKED_NOTIFICATION`; a stale expected state, any partial/full ownership, cancellation, or terminal state remains unchanged; acknowledgement accepts only `SUCCEEDED`, `PARTIAL`, `FAILED`, `CANCELLED`, or `EXPIRED`, never deletes/changes outputs, and is idempotent; and all terminal settlements record the 24-hour retention deadline. Task 5 does not own execution-child interruption or cancellation cleanup; Task 9's `DataTaskCancellationCoordinatorTest` proves the full persistence → key drop → matching-child interruption → non-cancellable cleanup ordering once that coordinator exists.

```kotlin
@Test fun keyIsAvailableBeforeBackupBecomesRunnable() = runTest {
    acceptance.acceptBackup(request, passphrase)
    assertEquals(listOf("derive", "put-key", "insert", "wake"), events)
}

@Test fun wakeFailureRetainsAcceptedRow() = runTest {
    wakeSignal.result = ServiceStartResult.Rejected(ServiceStartFailure.BACKGROUND_START_NOT_ALLOWED)
    val id = acceptance.acceptExport(request)
    assertEquals(DataTaskState.START_BLOCKED, store.load(id!!).state)
}
```

- [ ] **Step 2: Run focused tests and verify RED**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.backup.job.DataTaskAcceptanceTest' --tests 'com.valhalla.thor.data.backup.job.ArchiveKeyHolderTest' --stacktrace
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.data.source.local.room.DataTaskDaoTest --stacktrace
```

Expected failure: the durable store and acceptance boundary plus the required typed DAO operations do not exist.

- [ ] **Step 3: Implement a narrow store**

`DataTaskStore` maps domain requests to normalized rows and exposes only insertion, public load, Room-backed observation, claim/checkpoint, cancellation, recovery, output, acknowledgement, and retention operations. Every execution mutation takes a claim token. Extend `DataTaskDao` without changing schema 9: expose typed transactional snapshot load plus aggregate observation that is invalidated by task, detail, item, and output changes; add a compare-and-set start-block transition taking exact expected and blocked states; add terminal acknowledgement; and make terminal settlement populate `retainUntilEpochMs = terminalAtEpochMs + 24 hours`. The start-block CAS accepts only expected `QUEUED`/`STAGING_SOURCE` rows with `terminalAtEpochMs`, `cancelRequestedAtEpochMs`, `serviceSessionToken`, `claimToken`, and `claimLeaseExpiresAtEpochMs` all null, and target state `START_BLOCKED`/`START_BLOCKED_NOTIFICATION`. It changes only state and `updatedAtEpochMs`. Acknowledgement accepts only `SUCCEEDED`, `PARTIAL`, `FAILED`, `CANCELLED`, or `EXPIRED`, leaves outputs untouched, and repeated acknowledgement returns the same durable result rather than rewriting its first timestamp. Existing ready-output lookup/expiry methods form the current retention boundary; Task 17 adds physical artifact cleanup under the shared handoff lock. Keep all entities, `AppDatabase`, migrations, schema JSON, and schema version frozen.

- [ ] **Step 4: Implement acceptance behind an unbound wake port**

Define the exact service-neutral acceptance result in `data/service/ServiceStartResult.kt`; `DataTaskAcceptance.kt` owns only the data wake port and acceptance logic:

```kotlin
enum class ServiceStartFailure {
    BACKGROUND_START_NOT_ALLOWED,
    NOTIFICATION_CHANNEL_BLOCKED,
    SERVICE_COMPONENT_UNAVAILABLE,
    SECURITY_EXCEPTION,
    START_REQUEST_FAILED,
}

sealed interface ServiceStartResult {
    data object Requested : ServiceStartResult
    data object AlreadyRunning : ServiceStartResult
    data class Rejected(val reason: ServiceStartFailure) : ServiceStartResult
}

fun interface DataQueueWakeSignal {
    fun wake(taskId: UUID): ServiceStartResult
}
```

Implement an unannotated `DataTaskAcceptance`. Allocate the UUID before derivation, place a derived key under that UUID before making a secret row runnable, insert request/items atomically, drop the key after insert failure, then invoke the wake port. Rejection with `NOTIFICATION_CHANNEL_BLOCKED` settles `START_BLOCKED_NOTIFICATION`; every other rejected wake settles `START_BLOCKED`. `Requested` means only that Android accepted a start request, not that foreground promotion succeeded; Task 9 persists any asynchronous promotion failure. Return the accepted UUID after a rejected wake. Do not send the request or secret through the wake port. Do not modify `ThorJobLauncher`, `ExportJobLauncherImpl`, `JobRegistry`, or any production DI binding in this task; released WorkManager launchers remain active until Task 9.

- [ ] **Step 5: Run tests and commit**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.backup.job.DataTaskAcceptanceTest' --tests 'com.valhalla.thor.data.backup.job.ArchiveKeyHolderTest' --stacktrace
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.data.source.local.room.DataTaskDaoTest --stacktrace
git add app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskDao.kt app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskStore.kt app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskAcceptance.kt app/src/main/java/com/valhalla/thor/data/service/ServiceStartResult.kt app/src/androidTest/java/com/valhalla/thor/data/source/local/room/DataTaskDaoTest.kt app/src/test/java/com/valhalla/thor/data/backup/job/DataTaskAcceptanceTest.kt app/src/test/java/com/valhalla/thor/data/backup/job/ArchiveKeyHolderTest.kt
git commit -m "feat(queue): add durable data task acceptance"
```

### Task 6: Extract archive runners with safe interruption policy

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskRunner.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/RoomDataTaskSinks.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/LegacyWorkerDataTaskSinks.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/RestoreSourceGrantHolder.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/ArchiveBackupTaskRunner.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/ArchiveRestoreTaskRunner.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/AppArchiveWorker.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/ArchiveTaskRunnerTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/DataTaskRecoveryPolicyTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/LegacyArchiveWorkerAdapterTest.kt`

**Interfaces:**
- Consumes: current archive use cases, `ArchiveKeyHolder`, archive preflight helpers, claim-owned checkpoint sink.
- Produces: Worker-independent archive runners called by the service and retained legacy Workers.

- [ ] **Step 1: Write RED runner tests**

Pin these outcomes: a new Room task with a missing key becomes `WAITING_FOR_AUTH`; a released Worker with a missing key returns one bounded `JOB_ERROR_KEY` failure before touching package/source data; no partial backup is published; restore re-runs preflight after authentication; interruption before mutation is resumable; interruption after mutation becomes `INTERRUPTED_REVIEW`; cancellation cleanup runs in `NonCancellable`; and `CancellationException` is rethrown after settlement.

`LegacyArchiveWorkerAdapterTest` must restore each released input map exactly. Backup uses `thor.backup.package` (`String`), `thor.backup.classes` (`String[]`, unknown IDs dropped but at least one known ID required), `thor.backup.bundle` (`Boolean`, absent is `false`), and `thor.backup.salt` (Base64 `String`, exactly 16 decoded bytes). Restore uses `thor.restore.uri` (nonblank raw URI `String` already persisted by the released WorkSpec), `thor.restore.package` (nonblank `String`), `thor.restore.classes` (`String[]`, at least one known ID), and `thor.restore.obb` (`Boolean`, absent is `false`). Neither restore header nor `installFirst` is accepted from persisted input. Both adapters use the WorkSpec UUID as `taskId`, atomically `take` the key before package/source work, rethrow cancellation, never create a Room row, and never return retry. Backup success is bare success; restore success preserves bounded `thor.job.warnings`; both failures preserve bounded `thor.job.error`.

- [ ] **Step 2: Run focused tests and verify RED**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.backup.job.ArchiveTaskRunnerTest' --tests 'com.valhalla.thor.data.backup.job.DataTaskRecoveryPolicyTest' --tests 'com.valhalla.thor.data.backup.job.LegacyArchiveWorkerAdapterTest' --stacktrace
```

Expected failure: archive logic is Worker-bound.

- [ ] **Step 3: Define the runner contract**

```kotlin
data class DataTaskExecutionItem(
    val ordinal: Int,
    val packageName: String,
    val displayLabel: String?,
    val deterministicStagingIdentity: String,
    val attemptCount: Int,
)

sealed interface DataTaskExecutionPayload {
    val kind: DataTaskKind

    data class ArchiveBackup(
        val request: ArchiveBackupRequest,
        val key: SecretKey,
        val destination: StoredDataDestination,
    ) : DataTaskExecutionPayload {
        override val kind = DataTaskKind.ARCHIVE_BACKUP
    }

    data class ArchiveRestore(
        val request: ArchiveRestoreRequest,
        val key: SecretKey,
    ) : DataTaskExecutionPayload {
        override val kind = DataTaskKind.ARCHIVE_RESTORE
    }

    data class AppExport(
        val request: AppExportRequest,
        val publicationPolicy: DataTaskPublicationPolicy,
    ) : DataTaskExecutionPayload {
        override val kind = DataTaskKind.APP_EXPORT
    }

    data class SharePrepare(
        val requestedFormat: BundleFormat,
        val publicationPolicy: DataTaskPublicationPolicy,
    ) : DataTaskExecutionPayload {
        override val kind = DataTaskKind.SHARE_PREPARE
    }
}

data class DataTaskExecutionRequest(
    val taskId: UUID,
    val payload: DataTaskExecutionPayload,
    val item: DataTaskExecutionItem,
    val taskAttemptCount: Int,
    val resumedFrom: DataTaskCheckpoint?,
) {
    val kind: DataTaskKind get() = payload.kind
}

enum class DataTaskSinkWrite { APPLIED, OWNERSHIP_LOST }

fun interface DataTaskCheckpointSink {
    suspend fun persist(checkpoint: DataTaskCheckpoint): DataTaskSinkWrite
}

fun interface DataTaskResultSink<R> {
    suspend fun persist(outcome: DataTaskRunOutcome): R
}

interface DataTaskRunner {
    val kind: DataTaskKind
    suspend fun run(
        request: DataTaskExecutionRequest,
        checkpoints: DataTaskCheckpointSink,
    ): DataTaskRunOutcome
}
```

`DataTaskExecutionRequest` contains transient typed operation input only and no Room claim/session token. `StoredDataTaskDetail` remains the on-disk contract; the service adapter resolves its opaque persisted source/destination identity immediately before dispatch into `ArchiveRestoreRequest.uriString` or `AppExportRequest.treeUri`, which are in-memory only. Released Workers decode their already-persisted raw URI strings directly into those same transient request types and never counterfeit a `grantIdentity`. Archive payloads carry a non-null `SecretKey` obtained from `ArchiveKeyHolder`; a missing key yields the waiting/failure policy before a runnable payload exists. No payload, checkpoint, or result sink persists the raw URI or key. The runner returns exactly one Task 2 `DataTaskRunOutcome`; its caller persists that returned value through a result sink. `RoomDataTaskCheckpointSink` owns task/item claim tokens and returns `OWNERSHIP_LOST` on stale writes; `RoomDataTaskResultSink` calls Task 3's atomic `settleClaimedTask`. `LegacyWorkerCheckpointSink` translates checkpoints to the released `ThorJobProgress`, while `LegacyWorkerResultSink` maps the returned bounded outcome to `ListenableWorker.Result` and existing `JOB_WARNINGS_KEY`/`JOB_ERROR_KEY` output without a Room row. `CancellationException` is rethrown rather than translated to retry. No runner contract contains Room entities, claim tokens, service sessions, Worker parameters, or WorkManager result types.

- [ ] **Step 4: Extract archive logic**

Move domain execution from Workers into runners while preserving existing preflight order, archive execution context, warning/error mapping, `.part` publication, orphan ledger, and key take/drop behavior. The retained Workers become adapters to the same runners for already-persisted WorkSpecs. For backup, re-resolve `AppInfo`, probe OBB only when requested, build the XAPK under `ArchiveBundleCacheDir.NAME`, measure the actual staging volume, invoke `BackupAppArchiveUseCase`, and delete the temporary bundle in `finally`. For restore, open/authenticate the transient source, verify its package against the persisted request, re-read installed facts, evaluate the restore gate, retain `source.use` ownership in the runner, then invoke `RestoreAppArchiveUseCase` with ordered classes and the newly computed `installFirst`. `ArchiveKeyHolder.drop` remains the outer adapter/coordinator's unconditional `finally` cleanup.

Legacy progress is not WorkManager progress `Data`: the released code has no `setProgress` keys. `LegacyWorkerCheckpointSink` must publish the existing process-local `ThorJobProgress` through `JobRegistry` and notifications. Only `LegacyWorkerResultSink` writes bounded WorkManager output `Data` (`thor.job.error` and restore-only `thor.job.warnings`).

- [ ] **Step 5: Add restore-source staging behavior**

The service performs source import as the first runner stage. `RestoreSourceGrantHolder` stores `taskId + transientSourceToken -> Uri` in memory only, provides one-shot lookup/removal, and drops every token for a cancelled/terminal task. Foreground UI registers the URI and passes only the opaque token through `TaskActionController.submitRestoreSource`; the runner stages the content and persists only `StoredRestoreSource.PrivateCopy`. If the process dies before staging commits, settle `WAITING_FOR_SOURCE`; never make the UI copy a large archive and never persist raw URI text.

- [ ] **Step 6: Run tests and commit**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.backup.job.ArchiveTaskRunnerTest' --tests 'com.valhalla.thor.data.backup.job.DataTaskRecoveryPolicyTest' --tests 'com.valhalla.thor.data.backup.job.LegacyArchiveWorkerAdapterTest' --tests 'com.valhalla.thor.data.backup.job.AppArchiveWorkerTest' --stacktrace
git add app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskRunner.kt app/src/main/java/com/valhalla/thor/data/backup/job/RoomDataTaskSinks.kt app/src/main/java/com/valhalla/thor/data/backup/job/LegacyWorkerDataTaskSinks.kt app/src/main/java/com/valhalla/thor/data/backup/job/RestoreSourceGrantHolder.kt app/src/main/java/com/valhalla/thor/data/backup/job/ArchiveBackupTaskRunner.kt app/src/main/java/com/valhalla/thor/data/backup/job/ArchiveRestoreTaskRunner.kt app/src/main/java/com/valhalla/thor/data/backup/job/AppArchiveWorker.kt app/src/test/java/com/valhalla/thor/data/backup/job/ArchiveTaskRunnerTest.kt app/src/test/java/com/valhalla/thor/data/backup/job/DataTaskRecoveryPolicyTest.kt app/src/test/java/com/valhalla/thor/data/backup/job/LegacyArchiveWorkerAdapterTest.kt
git commit -m "refactor(backup): extract archive task runners"
```

### Task 7: Extract export and bulk-share runners

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/AppExportTaskRunner.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/SharePrepareTaskRunner.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/AppExportWorker.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/BackupRunner.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/AppExportTaskRunnerTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/SharePrepareTaskRunnerTest.kt`
- Modify test: `app/src/test/java/com/valhalla/thor/data/backup/BackupRunnerTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/LegacyAppExportWorkerAdapterTest.kt`

**Interfaces:**
- Consumes: current export use cases, normalized item rows, deterministic output identities.
- Produces: restartable per-item export/share execution and `READY`/`READY_PARTIAL` outputs.

- [ ] **Step 1: Write RED tests**

Prove single export restarts from the beginning after interrupted publication, destination permission is revalidated, multi-export and share skip succeeded items, incomplete `.part` files are removed or resumed only according to existing ledger policy, deterministic output identity prevents duplicates, and no runner launches a chooser.

- [ ] **Step 2: Run tests and verify RED**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.backup.job.AppExportTaskRunnerTest' --tests 'com.valhalla.thor.data.backup.job.SharePrepareTaskRunnerTest' --stacktrace
```

- [ ] **Step 3: Extract Worker-independent runners**

Keep existing export format selection and failure-reason helpers. Write through operation-unique staging paths and publish outputs transactionally. For bulk operations, claim one item at a time and persist completion before moving to the next item. Preserve Export `first_operation` at the existing `AppBundleBuilderImpl` boundary immediately before the first direct staged-byte `output.write`; the shared service runner must not add an earlier competing marker. The Task 19 comparison uses the same direct-copy benchmark path as Task 1.

- [ ] **Step 4: Implement ready-share output retention**

Persist each output's relative private path, display name, MIME type, byte count, and expiry. Finish as `READY` when all requested outputs exist and `READY_PARTIAL` when at least one usable output exists with bounded item failures. The runner never calls `startActivity` or constructs a chooser.

- [ ] **Step 5: Keep released Worker compatibility**

Make `AppExportWorker` decode the unchanged released WorkSpec keys with `AppExportRequest.fromMap`: `thor.export.pkg` is a nonblank `String`, `thor.export.format` is an exact `BundleFormat.name` with no fallback, `thor.export.label` is a nonblank `String`, and optional `thor.export.tree` is a nonblank SAF URI string whose absence means Downloads. Build transient `DataTaskExecutionPayload.AppExport(request, PUBLIC_DOCUMENT)`, create one synthetic ordinal-zero `DataTaskExecutionItem`, use the WorkSpec UUID as the execution-only task ID, and call the extracted runner through Task 6's `LegacyWorkerCheckpointSink`/`LegacyWorkerResultSink`. The raw tree URI stays only in the already-released WorkSpec and transient `AppExportRequest`; never copy it into a Room row or call it an opaque grant identity.

Preserve `LaunchSweepBarrier.awaitSwept()`, re-resolve `AppInfo`, revalidate custom-tree writability, reconstruct `ExportSession(request.target, ExportAppUseCase.SINGLE_STAGING_DIR)` without rereading current preferences, and invoke `ExportAppUseCase.exportInto`. Legacy progress remains process-local `ThorJobProgress` via `JobRegistry`/notifications; success is bare `Result.success()`, failure preserves bounded `JOB_ERROR_KEY` plus `noteResult`, cancellation is rethrown, no Room task is created, and retry is never returned. Task 6's `LegacyArchiveWorkerAdapterTest` pins the equivalent explicit decode contract for both existing archive Worker variants. Keep every live producer on its existing WorkManager implementation in this task; Task 9 performs the atomic single-export cutover and Task 17 performs the bulk-share cutover.

- [ ] **Step 6: Run tests and commit**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.backup.job.AppExportTaskRunnerTest' --tests 'com.valhalla.thor.data.backup.job.SharePrepareTaskRunnerTest' --tests 'com.valhalla.thor.data.backup.job.AppExportWorkerReasonTest' --tests 'com.valhalla.thor.data.backup.job.LegacyAppExportWorkerAdapterTest' --stacktrace
git add app/src/main/java/com/valhalla/thor/data/backup/job/AppExportTaskRunner.kt app/src/main/java/com/valhalla/thor/data/backup/job/SharePrepareTaskRunner.kt app/src/main/java/com/valhalla/thor/data/backup/job/AppExportWorker.kt app/src/main/java/com/valhalla/thor/data/backup/BackupRunner.kt app/src/test/java/com/valhalla/thor/data/backup/job/AppExportTaskRunnerTest.kt app/src/test/java/com/valhalla/thor/data/backup/job/SharePrepareTaskRunnerTest.kt app/src/test/java/com/valhalla/thor/data/backup/BackupRunnerTest.kt app/src/test/java/com/valhalla/thor/data/backup/job/LegacyAppExportWorkerAdapterTest.kt
git commit -m "refactor(export): add resumable data task runners"
```

### Task 8: Add typed notification capability, wake locks, and FGS permissions

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/service/ForegroundServiceNotificationCapability.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/service/ForegroundTaskWakeLock.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/valhalla/thor/data/service/ForegroundServiceNotificationCapabilityTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/service/ForegroundTaskWakeLockTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/service/ForegroundServicePermissionsManifestTest.kt`

**Interfaces:**
- Consumes: Android notification/channel and PowerManager APIs.
- Produces: typed start decisions and a ten-minute active-execution wake-lock lease.

- [ ] **Step 1: Write capability, wake-lock, and permission tests**

Test `Available`, API 33+ `PostPermissionDenied`, user `Blocked`, and construction/promotion `Invalid`; denied `POST_NOTIFICATIONS` permits execution, blocked channels prevent new execution, invalid notifications abort. Test non-reference-counted partial locks, acquisition only after claim, 600,000 ms lease, renewal only after durable checkpoint, and unconditional release. The manifest test asserts only the four FGS/wake-lock permissions in this task and explicitly asserts that neither new service component is declared before its class exists.

- [ ] **Step 2: Run tests and verify RED**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.service.ForegroundServiceNotificationCapabilityTest' --tests 'com.valhalla.thor.data.service.ForegroundTaskWakeLockTest' --tests 'com.valhalla.thor.data.service.ForegroundServicePermissionsManifestTest' --stacktrace
```

- [ ] **Step 3: Implement typed capability and shared identifiers**

```kotlin
sealed interface ForegroundNotificationState {
    data object Available : ForegroundNotificationState
    data object PostPermissionDenied : ForegroundNotificationState
    data object Blocked : ForegroundNotificationState
    data class Invalid(val reason: String) : ForegroundNotificationState
}
```

Define low-importance channel IDs `thor.jobs.data` and `thor.jobs.privileged` and UUID-derived immutable-PendingIntent request-code namespaces for later service notification builders. Do not modify released Worker notification behavior yet; Tasks 9 and 11 create service-specific channels, content intents, and cancellation intents together with their concrete receivers.

- [ ] **Step 4: Implement the wake-lock owner**

Each service receives a distinct tag (`Thor:DataSync`, `Thor:PrivilegeSweep`). Acquire after atomic claim and immediately before runner execution; renew only after a persisted checkpoint; every blocking stage checkpoints inside ten minutes or has a shorter hard deadline. Deadline handling cancels the stage, invalidates its owned shell generation when relevant, persists interruption, and stops.

- [ ] **Step 5: Add permissions without dangling components**

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

Do not declare either new service or cancellation receiver yet. Task 9 adds the data components with their classes; Task 11 adds the privilege components with their classes. Neither service uses a separate process and no boot receiver starts either service.

- [ ] **Step 6: Run tests and commit**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.service.ForegroundServiceNotificationCapabilityTest' --tests 'com.valhalla.thor.data.service.ForegroundTaskWakeLockTest' --tests 'com.valhalla.thor.data.service.ForegroundServicePermissionsManifestTest' --stacktrace
git add app/src/main/java/com/valhalla/thor/data/service/ForegroundServiceNotificationCapability.kt app/src/main/java/com/valhalla/thor/data/service/ForegroundTaskWakeLock.kt app/src/main/AndroidManifest.xml app/src/test/java/com/valhalla/thor/data/service/ForegroundServiceNotificationCapabilityTest.kt app/src/test/java/com/valhalla/thor/data/service/ForegroundTaskWakeLockTest.kt app/src/test/java/com/valhalla/thor/data/service/ForegroundServicePermissionsManifestTest.kt
git commit -m "feat(service): add typed foreground prerequisites"
```

### Task 9: Atomically activate `DataSyncCoordinator` and `DataSyncService`

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/LegacyDataWorkDrainGate.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/DataSyncCoordinator.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskOwnerRegistry.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/DataSyncServiceStarter.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskCancellationCoordinator.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/service/DataSyncServiceNotification.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/service/DataTaskCancelReceiver.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/backup/service/DataSyncService.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobLauncher.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/ExportJobLauncherImpl.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/JobRegistry.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/LaunchSweepBarrier.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/LegacyDataWorkDrainGateTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/DataSyncCoordinatorTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/DataTaskOwnerRegistryTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/DataTaskCancellationCoordinatorTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/DataSyncWakeLockTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/backup/job/LaunchSweepBarrierTest.kt`
- Modify test: `app/src/test/java/com/valhalla/thor/data/backup/job/DataTaskAcceptanceTest.kt`
- Test: `app/src/androidTest/java/com/valhalla/thor/data/backup/job/DataSyncServiceLifecycleTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/service/DataSyncServiceManifestTest.kt`

**Interfaces:**
- Consumes: Tasks 3, 5–8, released `THOR_JOB_CHAIN`, `LaunchSweepBarrier`, and the named IO dispatcher.
- Produces: the only new executor for data tasks, UUID-scoped notification cancellation, and the atomic public-launcher cutover.

- [ ] **Step 1: Write gate, coordinator, cancellation, lifecycle, and cutover tests**

Prove nonterminal released `THOR_JOB_CHAIN` work blocks every new data claim while privilege work remains independent; absent/terminal legacy work opens the gate without polling; persisted legacy export can drain without a duplicate Room row; missing in-memory keys make old backup/restore fail closed. Also prove one drain coroutine under repeated kicks, one active task, FIFO claims, launch-sweep barrier wait before cleanup-sensitive mutation, claim-token rejection, final-empty-check race safety, and the exact cancellation order: durable cancel request, in-memory key drop, matching-child interruption, non-cancellable cleanup, then terminal settlement. Prove immutable UUID notification intents, no next claim after API 35 timeout, and that public archive/export launchers persist then wake instead of creating a WorkRequest. Recovery tests must deterministically cover an overlapping earlier service generation whose registered child is still live, an earlier-session claim with no registered local owner, a current-session claim whose child died before lease expiry, and claim-token pre-registration across the Room-claim-to-child-start window.

- [ ] **Step 2: Run focused tests and verify RED**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.backup.job.LegacyDataWorkDrainGateTest' --tests 'com.valhalla.thor.data.backup.job.DataSyncCoordinatorTest' --tests 'com.valhalla.thor.data.backup.job.DataTaskOwnerRegistryTest' --tests 'com.valhalla.thor.data.backup.job.DataTaskCancellationCoordinatorTest' --tests 'com.valhalla.thor.data.backup.job.DataSyncWakeLockTest' --tests 'com.valhalla.thor.data.backup.job.LaunchSweepBarrierTest' --tests 'com.valhalla.thor.data.backup.job.DataTaskAcceptanceTest' --tests 'com.valhalla.thor.data.service.DataSyncServiceManifestTest' --stacktrace
```

- [ ] **Step 3: Gate and implement coordinator ownership**

`LegacyDataWorkDrainGate` observes the released `THOR_JOB_CHAIN` and exposes a cancellable terminal-state barrier. Before every Room claim, `DataSyncCoordinator` must await this gate; the gate's terminal transition wakes the same drain rather than requiring app restart. Retain `ThorJobWorker`, `ArchiveBackupWorker`, `ArchiveRestoreWorker`, `AppExportWorker`, WorkManager/Koin initialization, and `SystemForegroundService` only to drain persisted released work. Annotate `DataSyncCoordinator` as `@Factory` so each framework service instance receives a fresh coordinator and session token. Annotate `DataTaskOwnerRegistry` as `@Single` so overlapping service generations in the same process share one thread-safe liveness authority. Give each coordinator a mutex-protected conflated wake signal, at most one drain, and one active child. Use the process-wide owner registry keyed by task and claim token; pre-register a newly generated claim token before the Room claim CAS and remove it if the CAS loses, keep it registered for the complete lifetime of the active child, and remove it only after non-cancellable durable settlement/unwind. Pass this registry's explicit lookup into `recoverClaims`; recover an earlier-session claim only when its registered local owner is absent, and recover a same-session claim only when its lease expired and its registered local owner is absent. Never infer death from token inequality or lease expiry alone. Catch ordinary runner failures at the drain boundary so later entries continue; rethrow cancellation after non-cancellable settlement. Move Export `execution_admitted` from `ThorJobWorker` to `DataSyncCoordinator`: emit it only after the service has promoted successfully, the task and item claims have committed, and immediately before shared-runner dispatch. The retained legacy Worker's baseline hook may remain for compatibility-drain observability, but `LegacyDataWorkDrainGate` prevents it from running concurrently with a new service benchmark; Task 19 removes that hook.

- [ ] **Step 4: Implement task-scoped cancellation and notifications**

`DataTaskCancellationCoordinator` persists cancellation, drops any in-memory archive key, wakes the service, cancels only the matching active child, completes non-cancellable cleanup, and settles only after no claimed item remains. `DataSyncServiceNotification` creates `thor.jobs.data`, an immutable content intent, and an immutable cancellation intent carrying only the UUID. `DataTaskCancelReceiver` is non-exported and routes that UUID to the same coordinator used later by `TaskActionController`; it never calls WorkManager cancellation.

- [ ] **Step 5: Implement the framework service shell and manifest component**

`DataSyncService` is unannotated and has a public no-argument constructor. At the start of every `onStartCommand`, record `startId`, build the minimal preparing notification, and call `ServiceCompat.startForeground` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` before Koin/Room/file work. Then lazily resolve the coordinator and named IO dispatcher, signal the drain, and return `START_STICKY`. `onBind()` returns null and `onTaskRemoved()` does not cancel work. Add the service and receiver declarations only now that their classes exist:

```xml
<service
    android:name=".data.backup.service.DataSyncService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
<receiver
    android:name=".data.backup.service.DataTaskCancelReceiver"
    android:exported="false" />
```

- [ ] **Step 6: Atomically cut over public data producers**

Create `DataSyncServiceStarter` as the production `DataQueueWakeSignal`. It evaluates `ForegroundServiceNotificationCapability`, maps a blocked channel to `Rejected(NOTIFICATION_CHANNEL_BLOCKED)`, catches only the platform start exceptions into the remaining typed failure reasons, and returns `Requested`/`AlreadyRunning` without implying promotion success. If the framework Service later cannot promote, it must persist `START_BLOCKED` or `START_BLOCKED_NOTIFICATION` before stopping and never execute the task invisibly. Rewire the existing source-compatible archive and single-export launchers plus `JobRegistry` to `DataTaskAcceptance` only after the concrete service and manifest declaration exist in this same task. Allocation/key/insertion/wake ordering remains Task 5's contract. Keep bulk share on its old foreground path until Task 17. Verify no new data producer creates a WorkRequest; any remaining builder is inside the named legacy Worker compatibility path.

- [ ] **Step 7: Implement safe shutdown and timeout**

After the final transactional empty check and cancellation cleanup, remove the ongoing notification and call `stopSelfResult(latestStartId)`. On API 35+ `onTimeout(startId, foregroundServiceType)`, stop further claims, persist a typed interruption, cancel the active child, stay foreground during unwind, and stop within the grace period.

- [ ] **Step 8: Run tests and commit**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.backup.job.LegacyDataWorkDrainGateTest' --tests 'com.valhalla.thor.data.backup.job.DataSyncCoordinatorTest' --tests 'com.valhalla.thor.data.backup.job.DataTaskOwnerRegistryTest' --tests 'com.valhalla.thor.data.backup.job.DataTaskCancellationCoordinatorTest' --tests 'com.valhalla.thor.data.backup.job.DataSyncWakeLockTest' --tests 'com.valhalla.thor.data.backup.job.LaunchSweepBarrierTest' --tests 'com.valhalla.thor.data.backup.job.DataTaskAcceptanceTest' --tests 'com.valhalla.thor.data.service.DataSyncServiceManifestTest' --stacktrace
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.data.backup.job.DataSyncServiceLifecycleTest --stacktrace
git add app/src/main/java/com/valhalla/thor/data/backup/job/LegacyDataWorkDrainGate.kt app/src/main/java/com/valhalla/thor/data/backup/job/DataSyncCoordinator.kt app/src/main/java/com/valhalla/thor/data/backup/job/DataSyncServiceStarter.kt app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskCancellationCoordinator.kt app/src/main/java/com/valhalla/thor/data/backup/service/DataSyncServiceNotification.kt app/src/main/java/com/valhalla/thor/data/backup/service/DataTaskCancelReceiver.kt app/src/main/java/com/valhalla/thor/data/backup/service/DataSyncService.kt app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobLauncher.kt app/src/main/java/com/valhalla/thor/data/backup/job/ExportJobLauncherImpl.kt app/src/main/java/com/valhalla/thor/data/backup/job/JobRegistry.kt app/src/main/java/com/valhalla/thor/data/backup/job/LaunchSweepBarrier.kt app/src/main/AndroidManifest.xml app/src/test/java/com/valhalla/thor/data/backup/job/LegacyDataWorkDrainGateTest.kt app/src/test/java/com/valhalla/thor/data/backup/job/DataSyncCoordinatorTest.kt app/src/test/java/com/valhalla/thor/data/backup/job/DataTaskCancellationCoordinatorTest.kt app/src/test/java/com/valhalla/thor/data/backup/job/DataSyncWakeLockTest.kt app/src/test/java/com/valhalla/thor/data/backup/job/LaunchSweepBarrierTest.kt app/src/test/java/com/valhalla/thor/data/backup/job/DataTaskAcceptanceTest.kt app/src/androidTest/java/com/valhalla/thor/data/backup/job/DataSyncServiceLifecycleTest.kt app/src/test/java/com/valhalla/thor/data/service/DataSyncServiceManifestTest.kt
git commit -m "feat(service): activate durable data queue"
```

### Task 10: Replace sweep aggregate store and reconcile interrupted targets

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/domain/repository/PrivilegeSweepStore.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/RoomPrivilegeSweepStore.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepReconciler.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepItemExecutor.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/repository/RoomPrivilegeSweepStoreTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepReconcilerTest.kt`
- Modify test: `app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepItemExecutorTest.kt`

**Interfaces:**
- Consumes: Task 4 DAO and existing package lease/`SWEEP` execution context.
- Produces: claim-aware sweep store and operation-specific recovery.

- [ ] **Step 1: Write store and recovery tests**

Test neutral execution identity, target-derived aggregates, sanitized result codes, no reset/replay, converged freeze/unfreeze, verified reinstall postcondition, ambiguous clear-cache to `UNKNOWN`, migrated `LEGACY_UNKNOWN`, known-live session protection, and no replay of terminal targets.

- [ ] **Step 2: Run tests and verify RED**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.repository.RoomPrivilegeSweepStoreTest' --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepReconcilerTest' --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepItemExecutorTest' --stacktrace
```

- [ ] **Step 3: Replace aggregate mutations with claim-aware mapping**

Map DAO projections to domain snapshots without exposing entities. Extend `PrivilegeSweepStore.kt` with the claim/recovery domain types and methods required by Task 11; every progress/completion method takes request and target claim tokens. Keep `resetForRun`, `recordAttempt`, and every method still used by the feature-branch Worker/controller as deprecated compatibility members through Task 15. Task 16 removes them only after all ViewModel/screen/controller callers have moved in the same compiling cutover.

- [ ] **Step 4: Implement operation-specific recovery**

Freeze/unfreeze re-read current package state under `DefaultPackageOperationCoordinator`; reinstall runs the existing verified postcondition; clear-cache ambiguity becomes `UNKNOWN`; terminal states remain terminal. Only reconciliation that proves convergence or explicit user-authorized retry can make unknown targets runnable.

- [ ] **Step 5: Preserve executor safety seams**

Replace only the Worker-specific `workRequestId` with a neutral execution ID. Keep package leases, timeout, state convergence, `ManageAppUseCase`, root `SWEEP` lane/context, root router, and lane-status source unchanged.

- [ ] **Step 6: Run tests and commit**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.repository.RoomPrivilegeSweepStoreTest' --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepReconcilerTest' --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepItemExecutorTest' --stacktrace
git add app/src/main/java/com/valhalla/thor/domain/repository/PrivilegeSweepStore.kt app/src/main/java/com/valhalla/thor/data/repository/RoomPrivilegeSweepStore.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepReconciler.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepItemExecutor.kt app/src/test/java/com/valhalla/thor/data/repository/RoomPrivilegeSweepStoreTest.kt app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepReconcilerTest.kt app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepItemExecutorTest.kt
git commit -m "feat(sweep): recover durable target state"
```

### Task 11: Atomically activate `PrivilegeSweepService` and request-scoped cancellation

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/LegacyPrivilegeSweepExecutionFence.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorkManagerCutover.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepDrainCoordinator.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepOwnerRegistry.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepServiceStarter.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepCancellationCoordinator.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepServiceNotification.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepCancelReceiver.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepService.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorker.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepController.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/freezer/SweepQueueCanceller.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepCutoverReconcilerTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/freezer/LegacyPrivilegeSweepExecutionFenceTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepDrainCoordinatorTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepOwnerRegistryTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepCancellationCoordinatorTest.kt`
- Modify test: `app/src/test/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepControllerTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/service/PrivilegeSweepServiceManifestTest.kt`
- Test: `app/src/androidTest/java/com/valhalla/thor/data/freezer/PrivilegeSweepServiceIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 10 store/reconciler, Task 4's liveness-aware `recoverRequestClaims` boundary, feature-only `THOR_SWEEP_CHAIN`, and Task 8 platform helpers.
- Produces: the only new executor for the four supported explicit privilege sweeps, guarded by an awaited one-time WorkManager cutover.

- [ ] **Step 1: Write cutover, drain, cancellation, and activation tests**

Test that `THOR_SWEEP_CHAIN` cancellation is idempotent and awaited before the first service claim; an already-running Worker registers with a shared fence and the first service claim remains blocked until its target body and cleanup release the fence from `finally`; a Worker racing after admission closes is denied before any target mutation; WorkManager `Operation` completion alone never opens the service barrier; no old Worker and new service target mutation overlap; ambiguity maps to `LEGACY_UNKNOWN`; one request executes at a time in ordinal order; data and privilege queues remain independent; stale writers are rejected; leases renew only after checkpoints; privilege loss blocks actionably; final-empty-check enqueue races are safe; completion-first and cancellation-first commits each have one winner; a later request remains untouched; and new controller launches create no WorkRequest. Also deterministically prove that an overlapping earlier service generation with a registered child blocks request recovery, an earlier-session claim with no registered local owner is recoverable, a same-session claim is recoverable only after lease expiry and owner absence, malformed/missing owner identity fails closed, and claim-token reservation covers the Room-CAS-to-child-start window.

- [ ] **Step 2: Run tests and verify RED**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepCutoverReconcilerTest' --tests 'com.valhalla.thor.data.freezer.LegacyPrivilegeSweepExecutionFenceTest' --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepDrainCoordinatorTest' --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepOwnerRegistryTest' --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepCancellationCoordinatorTest' --tests 'com.valhalla.thor.data.freezer.DefaultPrivilegeSweepControllerTest' --tests 'com.valhalla.thor.data.service.PrivilegeSweepServiceManifestTest' --stacktrace
```

- [ ] **Step 3: Implement the awaited cutover barrier and FIFO drain**

`LegacyPrivilegeSweepExecutionFence` is a process-local admission/quiescence barrier shared by the old Worker and the cutover. `PrivilegeSweepWorker` must atomically register before its first target mutation, reject without mutation if admission is already closed, and release its registration from `finally` only after the target body and non-cancellable cleanup have exited. `PrivilegeSweepWorkManagerCutover` closes Worker admission first, cancels `THOR_SWEEP_CHAIN`, awaits the WorkManager `Operation`, then awaits the fence's active-registration count reaching zero before reconciliation. WorkManager operation completion is never treated as worker quiescence. After that fence, correlate rows by request ID, preserve source and ordinal data, and map any ambiguous target to `LEGACY_UNKNOWN`. Process death needs no in-memory wait because no worker from that process survives; persisted rows are still reconciled before claims.

`PrivilegeSweepDrainCoordinator` must await the complete close → cancellation-operation → worker-quiescence → reconciliation one-shot barrier before its first claim; no service target can execute if any stage fails. Annotate the coordinator `@Factory` so each framework service generation owns a fresh coordinator and session token. Annotate `PrivilegeSweepOwnerRegistry` `@Single` so overlapping service generations share one process-wide liveness authority; keep `PrivilegeSweepService` framework-created, unannotated, and publicly constructible with no arguments. Before the Room request-claim CAS, reserve the generated request claim token in the registry; while the request ID is not yet known, `localOwnerIsLive(requestId, claimToken)` treats that unique provisional token as live. Atomically bind a successful claim to `(requestId, claimToken)`, or remove the reservation when the CAS loses. Keep the bound owner registered through the active child's non-cancellable durable settlement and unwind, then unregister it. Pass that exact registry lookup to `recoverRequestClaims(sessionToken, nowMs, localOwnerIsLive)`: an earlier-session claim is recoverable only when no local owner is registered; a same-session claim additionally requires lease expiry; malformed/missing persisted ownership fails closed. Session-token inequality and lease expiry alone never prove death. After recovery, claim one request and only the lowest-ordinal `PENDING` target. Renew leases only after durable checkpoints, verify claims on completion, catch one target/task failure without killing its supervisor, and never execute `UNKNOWN` or `LEGACY_UNKNOWN`. Move both privilege markers into this coordinator: emit `execution_admitted` only after successful service promotion plus committed request/target claims, and emit `first_operation` immediately before the first `PrivilegeSweepItemExecutor` dispatch. Each marker is one-shot for the benchmark request.

- [ ] **Step 4: Implement persistence-first cancellation and notifications**

Cancellation order is fixed: persist `cancel_requested_at`, mark pending targets cancelled, immediately settle an inactive request, signal the service, cancel only the matching child, perform non-cancellable cleanup, then mark terminal after no target remains running. `PrivilegeSweepServiceNotification` creates `thor.jobs.privileged`, an immutable content intent, and an immutable cancellation intent containing only the request UUID. `PrivilegeSweepCancelReceiver` is non-exported and routes that UUID to the same cancellation coordinator used later by `TaskActionController`. Keep the deprecated queue-wide adapter compiling through Task 16, but have it resolve and cancel only the currently presented durable request; never cancel every queued request.

- [ ] **Step 5: Replace controller launch and observation without deleting compatibility APIs**

Define `fun interface PrivilegeQueueWakeSignal { fun wake(requestId: UUID): ServiceStartResult }` and implement it with `PrivilegeSweepServiceStarter`, using Task 5's shared typed start result. `DefaultPrivilegeSweepController.launch` validates and snapshots targets, persists/coalesces, emits `durable_accepted` only after `createOrFindEquivalent` returns, then invokes that port without a `WorkRequest`. `Requested` means only Android accepted the request; foreground-promotion failure is persisted asynchronously. Observation combines Room state with `RootLaneStatusSource`; it no longer derives new request phases from `WorkInfo`. API 33 permission denial accepts; a blocked channel stores request `BLOCKED` with reason `START_BLOCKED_NOTIFICATION`; missing privilege stores request `BLOCKED` with reason `PRIVILEGE_AUTHORIZATION_REQUIRED`. Task 13 projects that reason as `WAITING_FOR_PRIVILEGE` plus `TaskActionRequirement.PrivilegeAuthorization`. Retain source-compatible adapters still called by existing ViewModels/screens until Task 16 migrates those callers and removes them atomically.

- [ ] **Step 6: Implement the service shell and manifest components**

At every start, immediately promote with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, then lazily inject and signal one drain loop. Return `START_STICKY`; use final Room check plus `stopSelfResult(latestStartId)`. Do not request Shizuku permission and do not cancel from `onTaskRemoved()`. Add the service and receiver only now that their classes exist:

```xml
<service
    android:name=".data.freezer.PrivilegeSweepService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Executes explicit user-requested queued app-management operations such as freeze, unfreeze, per-app cache clearing, and verified reinstall through a user-configured privileged gateway, with visible progress and cancellation." />
</service>
<receiver
    android:name=".data.freezer.PrivilegeSweepCancelReceiver"
    android:exported="false" />
```

- [ ] **Step 7: Run tests and commit**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepCutoverReconcilerTest' --tests 'com.valhalla.thor.data.freezer.LegacyPrivilegeSweepExecutionFenceTest' --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepDrainCoordinatorTest' --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepOwnerRegistryTest' --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepCancellationCoordinatorTest' --tests 'com.valhalla.thor.data.freezer.DefaultPrivilegeSweepControllerTest' --tests 'com.valhalla.thor.data.service.PrivilegeSweepServiceManifestTest' --stacktrace
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.data.freezer.PrivilegeSweepServiceIntegrationTest --stacktrace
git add app/src/main/java/com/valhalla/thor/data/freezer/LegacyPrivilegeSweepExecutionFence.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorkManagerCutover.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepDrainCoordinator.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepOwnerRegistry.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepServiceStarter.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepCancellationCoordinator.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepServiceNotification.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepCancelReceiver.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepService.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorker.kt app/src/main/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepController.kt app/src/main/java/com/valhalla/thor/data/freezer/SweepQueueCanceller.kt app/src/main/AndroidManifest.xml app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepCutoverReconcilerTest.kt app/src/test/java/com/valhalla/thor/data/freezer/LegacyPrivilegeSweepExecutionFenceTest.kt app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepDrainCoordinatorTest.kt app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepOwnerRegistryTest.kt app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepCancellationCoordinatorTest.kt app/src/test/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepControllerTest.kt app/src/test/java/com/valhalla/thor/data/service/PrivilegeSweepServiceManifestTest.kt app/src/androidTest/java/com/valhalla/thor/data/freezer/PrivilegeSweepServiceIntegrationTest.kt
git commit -m "feat(sweep): activate foreground service queue"
```

### Task 12: Lock the WorkManager compatibility boundary

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorker.kt`
- Modify: `app/src/main/java/com/valhalla/thor/ThorApplication.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorkerTombstoneTest.kt`
- Test: `app/src/androidTest/java/com/valhalla/thor/data/service/LegacyWorkManagerCutoverIntegrationTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/service/NoNewWorkRequestArchitectureTest.kt`

**Interfaces:**
- Consumes: Task 9's released-data gate and legacy runner sinks plus Task 11's awaited sweep cutover.
- Produces: a no-mutation tombstone for the feature-only Worker, no new WorkRequests, and an explicit future removal gate while released data Workers remain runnable.

- [ ] **Step 1: Write compatibility and tombstone tests**

Prove restored released export WorkSpecs map directly to the shared runner and legacy sink without a Room row; old backup/restore WorkSpecs fail closed when their in-memory key is absent; `PrivilegeSweepWorker` cannot mutate even if WorkManager reconstructs its FQCN; startup reconciliation/pruning never starts either FGS; and `THOR_JOB_CHAIN`/`THOR_SWEEP_CHAIN` IDs and `ThorJobKind` IDs/ordinals remain unchanged.

- [ ] **Step 2: Write the source architecture test**

Scan production Kotlin and fail if any feature producer calls `OneTimeWorkRequestBuilder`, `PeriodicWorkRequestBuilder`, `beginUniqueWork`, or `enqueueUniqueWork` outside the named released-data compatibility files. Also fail if a production path invokes `PrivilegeSweepWorker` or constructs new `THOR_SWEEP_CHAIN` work. Keep both chain constants because compatibility gates need their historical names.

- [ ] **Step 3: Run tests and verify RED**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepWorkerTombstoneTest' --tests 'com.valhalla.thor.data.service.NoNewWorkRequestArchitectureTest' --stacktrace
```

- [ ] **Step 4: Tombstone feature-only sweep execution**

Keep `PrivilegeSweepWorker` at its existing FQCN so WorkManager can reconstruct persisted specs, but make it a no-mutation tombstone that returns a bounded terminal result after Task 11's cutover reconciler has captured ambiguity. Remove its obsolete baseline `first_operation` hook in this same change because the tombstone must never dispatch a target; Task 11's coordinator is now the sole privilege first-operation owner. Do not delete `PrivilegeSweepRunner`, deprecated store/controller members, `SweepQueueCancelReceiver`, or queue-wide compatibility signatures yet; Task 16 removes them together with their final UI callers.

- [ ] **Step 5: Preserve released data drain explicitly**

Retain `ThorJobWorker`, `ArchiveBackupWorker`, `ArchiveRestoreWorker`, `AppExportWorker`, WorkManager/Koin initialization, and the `SystemForegroundService` manifest overlay solely to drain persisted `THOR_JOB_CHAIN` work. Their adapters use `DataTaskExecutionRequest` and the legacy checkpoint/result sink defined in Tasks 6–7; they never create a Room task. Document the future removal gate in `LegacyDataWorkDrainGate` KDoc, but do not alter Task 9's claim integration.

- [ ] **Step 6: Keep startup reconciliation non-starting**

`ThorApplication` may reconcile/prune and mark `LaunchSweepBarrier` in `finally`; it must not start either FGS. Force-stop remains authoritative until the user launches Thor.

- [ ] **Step 7: Run compatibility tests and commit**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.freezer.PrivilegeSweepWorkerTombstoneTest' --tests 'com.valhalla.thor.data.service.NoNewWorkRequestArchitectureTest' --stacktrace
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.data.service.LegacyWorkManagerCutoverIntegrationTest --stacktrace
git add app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorker.kt app/src/main/java/com/valhalla/thor/ThorApplication.kt app/src/test/java/com/valhalla/thor/data/freezer/PrivilegeSweepWorkerTombstoneTest.kt app/src/androidTest/java/com/valhalla/thor/data/service/LegacyWorkManagerCutoverIntegrationTest.kt app/src/test/java/com/valhalla/thor/data/service/NoNewWorkRequestArchitectureTest.kt
git commit -m "refactor(workers): isolate compatibility execution"
```

### Task 13: Implement the combined Room-backed task repository and actions

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/repository/RoomTaskQueueRepository.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/repository/DefaultTaskActionController.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/repository/RoomTaskQueueRepositoryTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/repository/DefaultTaskActionControllerTest.kt`

**Interfaces:**
- Consumes: data-task and sweep retained flows, task-specific cancellation/resume/retry ports.
- Produces: the Task 2 read-only projection and one-task action router.

- [ ] **Step 1: Write projection tests**

Test simultaneous data and privilege running tasks, FIFO order within each queue, no invented combined execution order, waiting/review/ready grouping, 24-hour retention boundary, root-lane degradation, bounded derived log lines, and actions computed from state.

- [ ] **Step 2: Write action-routing tests**

Test that cancel routes to one owning queue; acknowledge never deletes external outputs; action-required rows cannot be acknowledged; retry/resume use explicit state transitions; archive authentication accepts a caller-owned `CharArray` without persistence; restore source accepts only an in-memory token; destructive-restore review routes before an explicit resume; privilege authorization refreshes and re-reads the real provider before requeue; `UNKNOWN`/`LEGACY_UNKNOWN` require target-specific retry authorization; share/settings actions return typed routes; `EXPIRED` rejects share and permits only acknowledgement; and an unknown UUID returns `Rejected(NOT_FOUND)` with bounded diagnostics.

- [ ] **Step 3: Run and verify RED**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.repository.RoomTaskQueueRepositoryTest' --tests 'com.valhalla.thor.data.repository.DefaultTaskActionControllerTest' --stacktrace
```

- [ ] **Step 4: Implement projection and action routing**

Annotate implementations with `@Single` and their domain bindings. Combine flows without exposing Room entities, claim tokens, or service instances. Derive `TaskActionRequirement` from durable task state and use `TaskActionPolicy` to expose only supported actions. Route `CANCEL` to Task 9/11's queue-specific persistence-first coordinator. Route archive authentication through `ArchiveKeyHolder`, restore source through `RestoreSourceGrantHolder`, restore review through a typed foreground route plus explicit `RESUME`, privilege authorization through the foreground setup flow and a fresh `PrivilegeManager.refresh()`/`PrivilegeStateProvider` read, unknown sweep retry through `authorizeUnknownTargetRetry`, ready output through `SharePreparedOutputs`, and blocked notification through `OpenNotificationSettings`. Never persist a passphrase, URI, permission assertion, or navigation event. `OBSERVER_FAILURE` is synthesized by presentation consumers only and is never written to Room.

- [ ] **Step 5: Run tests and commit**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.data.repository.RoomTaskQueueRepositoryTest' --tests 'com.valhalla.thor.data.repository.DefaultTaskActionControllerTest' --stacktrace
git add app/src/main/java/com/valhalla/thor/data/repository/RoomTaskQueueRepository.kt app/src/main/java/com/valhalla/thor/data/repository/DefaultTaskActionController.kt app/src/test/java/com/valhalla/thor/data/repository/RoomTaskQueueRepositoryTest.kt app/src/test/java/com/valhalla/thor/data/repository/DefaultTaskActionControllerTest.kt
git commit -m "feat(queue): project durable task history"
```

### Task 14: Build the Queue screen

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/queue/QueueViewModel.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/queue/QueueScreen.kt`
- Test: `app/src/test/java/com/valhalla/thor/presentation/queue/QueueViewModelTest.kt`
- Test: `app/src/androidTest/java/com/valhalla/thor/presentation/queue/QueueScreenTest.kt`

**Interfaces:**
- Consumes: `TaskQueueRepository`, `TaskActionController`.
- Produces: a normal navigation destination with Running, Queued/action-required, and Recent sections.

- [ ] **Step 1: Write ViewModel tests**

Assert up to one running item per queue, per-queue FIFO, action-required grouping, 24-hour recent boundary, process reconstruction from repository flow, and action forwarding by UUID.

- [ ] **Step 2: Run JVM tests and verify RED**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.presentation.queue.QueueViewModelTest' --stacktrace
```

- [ ] **Step 3: Implement ViewModel and Compose screen**

Render operation, safe package/item summary, state, progress, queue kind, and only advertised actions. Do not imply one cross-queue ordering. Selecting a row forwards its exact UUID.

- [ ] **Step 4: Add Compose tests**

Cover section visibility, empty states, dual running rows, row labels/actions, action-required rows, and exact selection callback.

- [ ] **Step 5: Run tests and commit**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.presentation.queue.QueueViewModelTest' --stacktrace
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.presentation.queue.QueueScreenTest --stacktrace
git add app/src/main/java/com/valhalla/thor/presentation/queue/QueueViewModel.kt app/src/main/java/com/valhalla/thor/presentation/queue/QueueScreen.kt app/src/test/java/com/valhalla/thor/presentation/queue/QueueViewModelTest.kt app/src/androidTest/java/com/valhalla/thor/presentation/queue/QueueScreenTest.kt
git commit -m "feat(queue): add task queue screen"
```

### Task 15: Replace the sweep dialog with Room-backed task detail and logger

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/queue/TaskDetailViewModel.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/queue/TaskDetailScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/widgets/TermLogger.kt`
- Test: `app/src/test/java/com/valhalla/thor/presentation/queue/TaskDetailViewModelTest.kt`
- Test: `app/src/androidTest/java/com/valhalla/thor/presentation/queue/TaskDetailScreenTest.kt`

**Interfaces:**
- Consumes: selected task UUID, `TaskQueueRepository`, optional hot progress overlay, task actions.
- Produces: reusable terminal logger content with Background and selected-task Cancel.

- [ ] **Step 1: Write ViewModel tests**

Cover provisional Starting to queued/running transition, persisted rejection, overlay fallback to Room, selected-task cancellation, Stopping until durable settlement, later-task isolation, every typed `TaskUiRoute`, `EXPIRED` share rejection/acknowledgement, SavedState recreation with task ID only, and observer failure without writes.

- [ ] **Step 2: Run JVM tests and verify RED**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.presentation.queue.TaskDetailViewModelTest' --stacktrace
```

- [ ] **Step 3: Extract `TermLoggerContent`**

Keep the visual terminal component reusable and stateless. Add explicit presentations for Background, Cancel, Stopping, Close, Authenticate archive, Provide source, Review restore, Authorize privilege, Authorize retry, Retry, Resume, Share, and Open notification settings. Render only actions supplied by `TaskActionPolicy` and dispatch them through the Task 2 controller contract. Background performs navigation dismissal only. Cancel requests durable cancellation and remains Stopping until Room settles. `TaskDetailScreen` emits `logger_visible` on the first drawn active logger surface, selecting Export versus Privilege-sweep from the observed task kind; Task 16 removes the replaced `ExportBottomSheet` logger hook and deletes the old `FreezeLoggerDialog` hook when it activates this screen.

- [ ] **Step 4: Enforce terminal/action semantics**

Close acknowledges a terminal task but never deletes archive/export/share output. Action-required states cannot be acknowledged away. `EXPIRED` displays that prepared share output was removed, offers no Share action, and can be acknowledged. Missing/error observation maps to presentation-only `OBSERVER_FAILURE`.

- [ ] **Step 5: Add Compose parity tests while retaining the old host dialog**

Test controls, disabled Stopping action, terminal Close, actionable retention, derived log order, system back, and outside dismissal. Keep `FreezeLoggerDialog` and its test unchanged in this task because production hosts still compile against them; Task 16 deletes both only after all hosts move to task detail.

- [ ] **Step 6: Run tests and commit**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.presentation.queue.TaskDetailViewModelTest' --stacktrace
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.presentation.queue.TaskDetailScreenTest --stacktrace
git add app/src/main/java/com/valhalla/thor/presentation/queue/TaskDetailViewModel.kt app/src/main/java/com/valhalla/thor/presentation/queue/TaskDetailScreen.kt app/src/main/java/com/valhalla/thor/presentation/widgets/TermLogger.kt app/src/test/java/com/valhalla/thor/presentation/queue/TaskDetailViewModelTest.kt app/src/androidTest/java/com/valhalla/thor/presentation/queue/TaskDetailScreenTest.kt
git commit -m "feat(queue): add recoverable task logger"
```

### Task 16: Wire navigation, notification ingress, and action launchers

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/navigation/ThorRoute.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/navigation/TaskNavigationTargets.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/navigation/TaskActionRouteHost.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/share/ReadyShareAccessLock.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/share/ShareIntentFactory.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/launcher/TaskQueueLaunchActivity.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/launcher/ShareHandoffActivity.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/main/MainScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/ExportBottomSheet.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/repository/PrivilegeSweepController.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/repository/PrivilegeSweepStore.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/RoomPrivilegeSweepStore.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/source/local/room/PrivilegeSweepDao.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepController.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/freezer/SweepQueueCanceller.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobNotifications.kt`
- Delete: `app/src/main/java/com/valhalla/thor/data/freezer/SweepQueueCancelReceiver.kt`
- Delete: `app/src/main/java/com/valhalla/thor/presentation/widgets/FreezeLoggerDialog.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/AppListScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/home/components/DashboardHeader.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/valhalla/thor/presentation/navigation/TaskNavigationTargetsTest.kt`
- Test: `app/src/androidTest/java/com/valhalla/thor/presentation/navigation/TaskActionRouteHostTest.kt`
- Test: `app/src/androidTest/java/com/valhalla/thor/presentation/launcher/TaskQueueLaunchActivityTest.kt`
- Test: `app/src/androidTest/java/com/valhalla/thor/presentation/launcher/ShareHandoffActivityTest.kt`
- Modify test: `app/src/test/java/com/valhalla/thor/presentation/main/MainViewModelTest.kt`
- Modify test: `app/src/test/java/com/valhalla/thor/presentation/appList/AppListViewModelTest.kt`
- Modify test: `app/src/test/java/com/valhalla/thor/presentation/freezer/FreezerViewModelTest.kt`
- Modify test: `app/src/test/java/com/valhalla/thor/presentation/settings/SettingsViewModelTest.kt`
- Modify test: `app/src/test/java/com/valhalla/thor/presentation/ViewModelTestDoubles.kt`
- Modify test: `app/src/test/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepControllerTest.kt`
- Modify test: `app/src/test/java/com/valhalla/thor/data/repository/RoomPrivilegeSweepStoreTest.kt`
- Delete test: `app/src/androidTest/java/com/valhalla/thor/data/freezer/SweepQueueCancelReceiverTest.kt`
- Delete test: `app/src/androidTest/java/com/valhalla/thor/presentation/widgets/FreezeLoggerDialogTest.kt`

**Interfaces:**
- Consumes: Queue/detail screens and caller-generated task UUID launch contracts.
- Produces: immediate Starting logger and task-specific notification navigation.

- [ ] **Step 1: Write navigation and ViewModel tests**

Prove notification publication before `MainScreen` collection opens the exact detail once, malformed IDs resume Thor without opening an arbitrary task, user launch emits `OpenTaskDetail(taskId)` before suspension, persisted rejection remains observable, same-queue contention shows localized Queued feedback instead of replacing the active logger, and all four live sweep producers—including `SettingsViewModel.unfreezeAll()`—open the caller-generated accepted UUID's provisional detail while preserving rejection feedback.

For the route host, exercise every `TaskUiRoute`: unsaveable passphrase entry calls `submitArchivePassphrase` and clears local input; restore document selection registers the returned `Uri` in `RestoreSourceGrantHolder` and passes only its token to `submitRestoreSource`; cancelled pickers/dialogs leave the task actionable; restore-review confirmation dispatches `RESUME`; privilege setup completion calls `privilegeAuthorizationReturned`; target-retry confirmation calls `authorizeSweepTargetRetry`; Share starts `ShareHandoffActivity` with only the task UUID; notification settings uses the exact channel ID. Prove stale/double result callbacks are ignored and no passphrase, raw URI, or asserted permission result enters navigation/SavedState/Intent extras. In `ShareHandoffActivityTest`, assert one output uses `ACTION_SEND`, multiple outputs use `ACTION_SEND_MULTIPLE`, every ordered URI appears in both `EXTRA_STREAM` and `ClipData`, read grants are complete, partial-ready tasks include only ready outputs, and missing/expired tasks refuse cleanly.

- [ ] **Step 2: Run tests and verify RED**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.presentation.navigation.TaskNavigationTargetsTest' --tests 'com.valhalla.thor.presentation.main.MainViewModelTest' --tests 'com.valhalla.thor.presentation.appList.AppListViewModelTest' --tests 'com.valhalla.thor.presentation.freezer.FreezerViewModelTest' --tests 'com.valhalla.thor.presentation.settings.SettingsViewModelTest' --stacktrace
```

- [ ] **Step 3: Add Queue and detail routes**

Add `Queue` and `TaskDetail(taskId: String)` to `ThorRoute`, push them on the active tab stack, and register entries in `MainScreen`. Do not add Queue to `AppDestinations`; it is not a fifth bottom tab.

- [ ] **Step 4: Add visible Queue entry points**

Add `onNavigateToQueue` to Home, Apps, Freezer, and Settings root screens and use the existing `list_alt` drawable in root headers.

- [ ] **Step 5: Add task notification ingress**

`TaskNavigationTargets` is a conflated UUID ingress retaining a target published before collection. `TaskQueueLaunchActivity` validates the UUID, publishes it, resumes Thor's main task, and finishes. Register it and `ShareHandoffActivity` non-exported; all service/share content intents are immutable and carry only the task UUID.

- [ ] **Step 6: Implement every foreground action route**

`TaskActionRouteHost` is the single foreground owner for all seven `TaskUiRoute` variants. It keeps passphrase text in unsaveable local state, converts it to a caller-owned `CharArray`, calls `submitArchivePassphrase`, clears its local input, and zeroes the submitted array in `finally`. Its `OpenDocument` launcher gives the selected `Uri` directly to `RestoreSourceGrantHolder`, which returns a task-scoped opaque token; only that token reaches `submitRestoreSource`. Restore-review and target-retry dialogs call `perform(taskId, RESUME)` and `authorizeSweepTargetRetry` only after explicit confirmation. Cancelling any dialog/picker performs no state transition, so Room continues to advertise the same action.

For `AuthorizePrivilege`, reuse the existing foreground Home privilege setup controls; retain only the pending task UUID in unsaveable memory, and on the Activity/setup return call `privilegeAuthorizationReturned`, whose Task 13 implementation refreshes and re-reads actual privilege state. Never have the Service request Shizuku authorization and never add a second Thor confirmation in front of Shizuku Manager. `OpenNotificationSettings` launches the system channel-settings intent for the route's exact channel. `SharePreparedOutputs` launches the non-exported `ShareHandoffActivity` with only the task UUID.

Create `ReadyShareAccessLock`, `ShareIntentFactory`, and the initial `ShareHandoffActivity` in this task so Share has a real handler when task-detail navigation is activated. The Activity accepts only a task UUID, locks ready-output resolution, revalidates current task/output state, expiry, and file existence, regenerates FileProvider URIs, and launches the chooser only in this foreground Activity. A single output uses `ACTION_SEND`; multiple use `ACTION_SEND_MULTIPLE`; every ordered URI appears in both `EXTRA_STREAM` and `ClipData` with read grants. Task 17 adds retention deletion under the same lock and expands these tests for expiry races.

- [ ] **Step 7: Atomically migrate action producers and remove compatibility APIs**

Generate the task UUID before asynchronous acceptance and immediately emit the provisional Starting detail. Keep the confirmed Reinstall `tap` marker in `MainViewModel`; in `ExportBottomSheet`, keep the final Export `tap` marker but remove its replaced `logger_visible` hook so Task 15's `TaskDetailScreen` becomes the sole logger marker owner. Move `MainViewModel`, `AppListViewModel`, `FreezerViewModel`, and `SettingsViewModel.unfreezeAll()` plus their screens from local sweep WorkInfo, retained work IDs, optimistic `SweepProgressUiState`, queue-wide cancellation, and `FreezeLoggerDialog` to the durable task UUID/detail flow. In this same compiling change, remove the deprecated `cancelQueue`/aggregate mutation/controller adapters from `PrivilegeSweepController`, `DefaultPrivilegeSweepController`, `SweepQueueCanceller`, `PrivilegeSweepStore`, `RoomPrivilegeSweepStore`, and `PrivilegeSweepDao`; delete `SweepQueueCancelReceiver` and its manifest/notification wiring; delete `FreezeLoggerDialog` and its parity test. Keep `THOR_SWEEP_CHAIN` and the tombstone Worker for transition-release reconstruction and the Task 11 cutover barrier.

- [ ] **Step 8: Run tests and commit**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.presentation.navigation.TaskNavigationTargetsTest' --tests 'com.valhalla.thor.presentation.main.MainViewModelTest' --tests 'com.valhalla.thor.presentation.appList.AppListViewModelTest' --tests 'com.valhalla.thor.presentation.freezer.FreezerViewModelTest' --tests 'com.valhalla.thor.data.freezer.DefaultPrivilegeSweepControllerTest' --tests 'com.valhalla.thor.data.repository.RoomPrivilegeSweepStoreTest' --stacktrace
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.presentation.launcher.TaskQueueLaunchActivityTest --stacktrace
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.presentation.navigation.TaskActionRouteHostTest --stacktrace
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.presentation.launcher.ShareHandoffActivityTest --stacktrace
git add app/src/main/java/com/valhalla/thor/presentation/navigation/ThorRoute.kt app/src/main/java/com/valhalla/thor/presentation/navigation/TaskNavigationTargets.kt app/src/main/java/com/valhalla/thor/presentation/navigation/TaskActionRouteHost.kt app/src/main/java/com/valhalla/thor/presentation/share/ReadyShareAccessLock.kt app/src/main/java/com/valhalla/thor/presentation/share/ShareIntentFactory.kt app/src/main/java/com/valhalla/thor/presentation/launcher/TaskQueueLaunchActivity.kt app/src/main/java/com/valhalla/thor/presentation/launcher/ShareHandoffActivity.kt app/src/main/java/com/valhalla/thor/presentation/main/MainScreen.kt app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt app/src/main/java/com/valhalla/thor/presentation/appList/ExportBottomSheet.kt app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt app/src/main/java/com/valhalla/thor/presentation/settings/SettingsViewModel.kt app/src/main/java/com/valhalla/thor/domain/repository/PrivilegeSweepController.kt app/src/main/java/com/valhalla/thor/domain/repository/PrivilegeSweepStore.kt app/src/main/java/com/valhalla/thor/data/repository/RoomPrivilegeSweepStore.kt app/src/main/java/com/valhalla/thor/data/source/local/room/PrivilegeSweepDao.kt app/src/main/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepController.kt app/src/main/java/com/valhalla/thor/data/freezer/SweepQueueCanceller.kt app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobNotifications.kt app/src/main/java/com/valhalla/thor/presentation/home/HomeScreen.kt app/src/main/java/com/valhalla/thor/presentation/appList/AppListScreen.kt app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt app/src/main/java/com/valhalla/thor/presentation/home/components/DashboardHeader.kt app/src/main/AndroidManifest.xml app/src/test/java/com/valhalla/thor/presentation/navigation/TaskNavigationTargetsTest.kt app/src/androidTest/java/com/valhalla/thor/presentation/navigation/TaskActionRouteHostTest.kt app/src/androidTest/java/com/valhalla/thor/presentation/launcher/TaskQueueLaunchActivityTest.kt app/src/androidTest/java/com/valhalla/thor/presentation/launcher/ShareHandoffActivityTest.kt app/src/test/java/com/valhalla/thor/presentation/main/MainViewModelTest.kt app/src/test/java/com/valhalla/thor/presentation/appList/AppListViewModelTest.kt app/src/test/java/com/valhalla/thor/presentation/freezer/FreezerViewModelTest.kt app/src/test/java/com/valhalla/thor/presentation/settings/SettingsViewModelTest.kt app/src/test/java/com/valhalla/thor/presentation/ViewModelTestDoubles.kt app/src/test/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepControllerTest.kt app/src/test/java/com/valhalla/thor/data/repository/RoomPrivilegeSweepStoreTest.kt
git add -u app/src/main/java/com/valhalla/thor/data/freezer/SweepQueueCancelReceiver.kt app/src/main/java/com/valhalla/thor/presentation/widgets/FreezeLoggerDialog.kt app/src/androidTest/java/com/valhalla/thor/data/freezer/SweepQueueCancelReceiverTest.kt app/src/androidTest/java/com/valhalla/thor/presentation/widgets/FreezeLoggerDialogTest.kt
git commit -m "feat(queue): navigate to durable task details"
```

### Task 17: Add ready-share retention and migrate bulk share

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/share/ReadyShareRetentionSweeper.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/share/ReadyShareRetentionStartup.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskDao.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskStore.kt`
- Modify: `app/src/main/java/com/valhalla/thor/ThorApplication.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/main/MainScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt`
- Test: `app/src/test/java/com/valhalla/thor/presentation/share/ReadyShareRetentionSweeperTest.kt`
- Test: `app/src/test/java/com/valhalla/thor/presentation/share/ReadyShareRetentionStartupTest.kt`
- Modify test: `app/src/androidTest/java/com/valhalla/thor/presentation/launcher/ShareHandoffActivityTest.kt`

**Interfaces:**
- Consumes: Task 2's already-localized queue/share copy, Task 16's foreground handoff/lock, ready output rows, and FileProvider.
- Produces: mandatory startup retention cleanup and queued bulk-share preparation.

- [ ] **Step 1: Write retention, startup-wiring, and handoff race tests**

In the retention test, prove not-yet-expired files survive, expired files are deleted before the matching CAS transition to `EXPIRED`, missing files still settle, and a stale output-ID set cannot expire replacement output. Extend `ShareHandoffActivityTest` to prove handoff cannot interleave with deletion under Task 16's shared lock and still refuses missing/expired/replaced output. `ReadyShareRetentionStartupTest` proves one invocation per process startup, on the application scope, and verifies the startup hook calls neither `DataQueueWakeSignal` nor `PrivilegeQueueWakeSignal`.

- [ ] **Step 2: Run tests and verify RED**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.presentation.share.ReadyShareRetentionSweeperTest' --tests 'com.valhalla.thor.presentation.share.ReadyShareRetentionStartupTest' --stacktrace
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.presentation.launcher.ShareHandoffActivityTest --stacktrace
```

- [ ] **Step 3: Implement mandatory retention startup**

`ReadyShareRetentionSweeper` and Task 16's handoff resolution share the same process-local `ReadyShareAccessLock`. The sweeper loads expired ready outputs, deletes each operation-owned private file (missing is already clean), then CAS-marks matching output IDs and their `READY`/`READY_PARTIAL` task as `EXPIRED`. A crash after deletion is retried on next launch; handoff revalidates expiry and file existence inside the same lock and either receives the complete ordered ready set or refuses.

`ReadyShareRetentionStartup` is the single idempotent process-start owner. `ThorApplication` must invoke it exactly once from the application scope on every process launch; this is mandatory, not optional. The startup coroutine performs retention cleanup only, does not wake either queue, and must not start either foreground service. Force-stop remains authoritative until the next explicit app launch.

- [ ] **Step 4: Migrate bulk share**

`MainViewModel` enqueues share preparation and opens task detail. Remove direct bulk `MainSideEffect.ShareApps` URI construction. Any remaining foreground single-share path uses `ShareIntentFactory`.

- [ ] **Step 5: Validate the predeclared localized copy**

Task 2 already added the complete queue, service-notification, action-dialog, logger, expiry, and ready-share vocabulary to all eight app locales before any consumer compiled. Run `LocalePolicyTest` here as a final integration gate and confirm every new Task 14–17 `R.string` reference uses that vocabulary with positional-placeholder parity. Do not add hard-coded user-visible fallback strings.

- [ ] **Step 6: Run tests and commit**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests 'com.valhalla.thor.presentation.share.ReadyShareRetentionSweeperTest' --tests 'com.valhalla.thor.presentation.share.ReadyShareRetentionStartupTest' --tests 'com.valhalla.thor.util.LocalePolicyTest' --stacktrace
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest --rerun-tasks -Pandroid.testInstrumentationRunnerArguments.class=com.valhalla.thor.presentation.launcher.ShareHandoffActivityTest --stacktrace
git add app/src/main/java/com/valhalla/thor/presentation/share/ReadyShareRetentionSweeper.kt app/src/main/java/com/valhalla/thor/presentation/share/ReadyShareRetentionStartup.kt app/src/main/java/com/valhalla/thor/data/source/local/room/DataTaskDao.kt app/src/main/java/com/valhalla/thor/data/backup/job/DataTaskStore.kt app/src/main/java/com/valhalla/thor/ThorApplication.kt app/src/main/java/com/valhalla/thor/presentation/main/MainScreen.kt app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt app/src/test/java/com/valhalla/thor/presentation/share/ReadyShareRetentionSweeperTest.kt app/src/test/java/com/valhalla/thor/presentation/share/ReadyShareRetentionStartupTest.kt app/src/androidTest/java/com/valhalla/thor/presentation/launcher/ShareHandoffActivityTest.kt
git commit -m "feat(share): hand off prepared outputs in foreground"
```

### Task 18: Run full automated validation

**Files:**
- Inspect: all changed files
- Inspect: `gradle.properties`
- Inspect: `app/schemas/com.valhalla.thor.data.source.local.room.AppDatabase/9.json`

**Interfaces:**
- Consumes: complete implementation.
- Produces: authoritative XML totals, compile/lint/build evidence, and a clean intended diff.

- [ ] **Step 1: Run focused tests for every new subsystem**

Run the named tests from Tasks 2–17 through context-mode with `--rerun-tasks`. Do not proceed on a failure.

- [ ] **Step 2: Run complete Foss and Store JVM suites separately**

```shell
./gradlew :app:testFossDebugUnitTest --rerun-tasks --stacktrace
./gradlew :app:testStoreDebugUnitTest --rerun-tasks --stacktrace
```

- [ ] **Step 3: Parse authoritative JUnit XML totals**

Use Python `xml.etree.ElementTree` over `app/build/test-results/**/*.xml`. Report file count, tests, failures, errors, and skipped separately for Foss and Store; require nonzero tests and zero failures/errors.

- [ ] **Step 4: Compile Koin and instrumentation sources**

```shell
./gradlew :app:compileFossDebugKotlin :app:compileFossDebugAndroidTestKotlin --stacktrace
```

Koin compile-safety must pass; do not add KSP to fix a Koin binding.

- [ ] **Step 5: Run lint separately to avoid backend memory pressure**

```shell
./gradlew :app:lintFossDebug --stacktrace
./gradlew :app:lintStoreRelease --stacktrace
```

Record exact error/warning counts and inspect all new warnings.

- [ ] **Step 6: Assemble deliverables**

```shell
./gradlew :app:assembleFossDebug :app:assembleFossRelease :app:assembleStoreRelease --stacktrace
```

- [ ] **Step 7: Run source and repository gates**

```shell
rg -n 'OneTimeWorkRequestBuilder|PeriodicWorkRequestBuilder|beginUniqueWork|enqueueUniqueWork' app/src/main/java
git diff --check
git status --short
git diff -- gradle.properties
```

Require no new WorkRequest producer, schema 9 present, `versionCode=1952`, and only intended files plus untracked `.kotlin/`.

### Task 19: Validate the completed flow on the Shizuku emulator

**Files:**
- Modify: `docs/workers/service-queue-latency-baseline.md` with post-cutover evidence
- Delete: `app/src/main/java/com/valhalla/thor/util/ServiceQueueLatencyProbe.kt` after measurements
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/ExportBottomSheet.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/queue/TaskDetailScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/ExportJobLauncherImpl.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobWorker.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/backup/job/DataSyncCoordinator.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/AppBundleBuilderImpl.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepController.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepDrainCoordinator.kt`

**Interfaces:**
- Consumes: Foss debug APK, emulator with Shizuku running and authorized.
- Produces: acceptance and performance evidence for PR #453.

- [ ] **Step 1: Start and verify Shizuku**

On the designated emulator, start Shizuku using its installed script when needed, verify `shizuku_server`, retain the existing Thor grant, and select Shizuku as Thor's active privilege mode. Treat Shizuku Manager's dialog as consent; do not add another layer.

- [ ] **Step 2: Verify privilege queue behavior**

Run freeze, unfreeze, per-app cache clear, and Fix Store. Verify immediate Starting logger, queued/running/terminal transitions, target count equal to target rows, no duplicate target execution, task-specific cancel, Background dismissing UI only, and Queue reopening the task.

- [ ] **Step 3: Verify independent and conflicting work**

Run export concurrently with a different-package sweep and prove both queues progress. Run a same-package conflict and require bounded `BUSY` rather than overlap.

- [ ] **Step 4: Verify lifecycle recovery**

Exercise process kill/recreation, Recents swipe, sticky null-intent recreation, Task Manager Stop, reboot, and force-stop. Confirm completed items do not replay, ambiguous work becomes the correct actionable state, and force-stop prevents service restart until explicit launch.

- [ ] **Step 5: Verify Shizuku and notification boundaries**

Restart Shizuku during a sweep. Confirm no extra Thor confirmation, no false success, and retry only after reconciliation/authorization. Test denied notification permission, a disabled channel, and an ineligible background start; queued work remains visible and never executes invisibly.

- [ ] **Step 6: Measure post-cutover latency**

Repeat at least twenty warm exports and twenty warm sweeps with the same conditions as Task 1. Add raw results and distributions. Acceptance requires immediate logger acknowledgement, no WorkManager ENQUEUED delay, lower median tap-to-first-operation for export and sweep, and no correctness regression.

- [ ] **Step 7: Remove the temporary latency probe**

After the final raw observation is recorded, delete `ServiceQueueLatencyProbe.kt` and remove the hooks from the exact final-owner set listed above. Also search the earlier baseline owners `ThorJobWorker.kt`, `PrivilegeSweepWorker.kt`, and the deleted `FreezeLoggerDialog.kt` by path/history so a transition task cannot have left a duplicate hook behind. Require zero production/test matches for the probe symbols `ServiceQueueLatencyProbe`, `ServiceQueueOperation`, and `ServiceQueueEvent`; also require zero matches for the four distinctive marker literals `logger_visible`, `durable_accepted`, `execution_admitted`, and `first_operation`. Do not use the generic word `tap` as a repository-wide zero-match assertion. Rebuild the Foss debug APK to prove the evidence seam was removed without changing the measured implementation.

- [ ] **Step 8: Record scope and commit evidence honestly**

Label this as emulator Shizuku evidence. Do not claim physical Root or stock-device evidence; the user will perform physical verification before merge. Stage only the evidence document, deleted probe, and exact final marker owners:

```shell
git add docs/workers/service-queue-latency-baseline.md app/src/main/java/com/valhalla/thor/presentation/appList/ExportBottomSheet.kt app/src/main/java/com/valhalla/thor/presentation/queue/TaskDetailScreen.kt app/src/main/java/com/valhalla/thor/data/backup/job/ExportJobLauncherImpl.kt app/src/main/java/com/valhalla/thor/data/backup/job/ThorJobWorker.kt app/src/main/java/com/valhalla/thor/data/backup/job/DataSyncCoordinator.kt app/src/main/java/com/valhalla/thor/data/repository/AppBundleBuilderImpl.kt app/src/main/java/com/valhalla/thor/presentation/main/MainViewModel.kt app/src/main/java/com/valhalla/thor/data/freezer/DefaultPrivilegeSweepController.kt app/src/main/java/com/valhalla/thor/data/freezer/PrivilegeSweepDrainCoordinator.kt
git add -u app/src/main/java/com/valhalla/thor/util/ServiceQueueLatencyProbe.kt
git commit -m "test(performance): verify service queue latency"
```

### Task 20: Update, commit, and push PR #453 without merging

**Files:**
- Add: `docs/superpowers/specs/2026-09-03-privilege-action-service-design.md`
- Add: `docs/superpowers/plans/2026-09-03-typed-foreground-service-queues.md`
- Modify: PR #453 title and body

**Interfaces:**
- Consumes: Tasks 1–19 evidence.
- Produces: updated remote branch and detailed open PR.

- [ ] **Step 1: Inspect the final explicit file list**

Use `git status --short`, `git diff --stat`, and `git diff --check`. Confirm `.kotlin/` remains untracked and `gradle.properties` remains unchanged at 1952.

- [ ] **Step 2: Commit remaining documentation and evidence**

Stage only the specification, plan, evidence, and any named implementation files not already committed. Do not use broad staging and do not add a co-author trailer.

```shell
git add docs/superpowers/specs/2026-09-03-privilege-action-service-design.md docs/superpowers/plans/2026-09-03-typed-foreground-service-queues.md
git commit -m "docs(workers): document typed service queue design"
```

- [ ] **Step 3: Update PR metadata in first-person maintainer voice**

Set a title that names both typed foreground services and startup Root-probe serialization. The body must detail:

- Root startup crash correction and intentional Shizuku consent flow;
- WorkManager latency motivation;
- separate Room-backed data and privilege queues;
- `dataSync` versus `specialUse` operation boundaries;
- request/item/target claims, cancellation, and process recovery;
- schema 8 to 9 migration and `LEGACY_UNKNOWN` semantics;
- released archive/export Worker drain and feature-only sweep-chain cancellation;
- Queue/logger/background/share handoff UX;
- exact XML test totals, lint/build results, emulator model/API, Shizuku state, and acceptance results;
- retained `versionCode=1952`;
- deferred physical Root and stock-device verification;
- explicit statement that the PR remains open for device verification.

Do not imply PR #451 removed the intentional Shizuku dialog; describe it only as excluding broker permissions from Thor's Android self-grant plan.

- [ ] **Step 4: Push the existing feature branch**

```shell
git push origin HEAD:feat/worker-shell-lanes
```

- [ ] **Step 5: Verify remote state**

Fetch `origin/dev` and the feature branch, require remote SHA equal to local, require `origin/dev` to be an ancestor of HEAD, confirm PR base is `dev`, and confirm PR #453 remains open and unmerged.

### Task 21: Review the complete branch, fix findings, and update the open PR

**Files:**
- Review target: `origin/dev...origin/feat/worker-shell-lanes`
- Modify: only files required by verified findings
- Modify: PR #453 body with corrected validation evidence

**Interfaces:**
- Consumes: pushed complete branch.
- Produces: zero verified unresolved findings, corrective commits, updated CI evidence, and an open PR.

- [ ] **Step 1: Run a maximum-effort full-branch correctness review**

Review the entire diff, not only the final commit. Explicit lenses:

```text
Room migration/data preservation
claim/session/lease ownership
cancellation versus completion races
service foreground-promotion ordering
sticky restart and final-empty shutdown
wake-lock and API 35 timeout settlement
archive secrets and restore interruption
legacy WorkManager compatibility
same-package/root-lane coordination
PendingIntent UUID routing and share grants
Compose lifecycle and observer uncertainty
localization and accessibility
```

- [ ] **Step 2: Run a separate security review**

Search for persisted passphrases/keys, raw URI text, shell commands/output, private paths, stack traces, mutable/exported PendingIntents, exported components, unsafe background starts, and accidental `specialUse` misuse.

- [ ] **Step 3: Adversarially verify every finding**

For each candidate, reproduce it with a deterministic test or trace the exact input/state to the wrong output. Discard findings that cannot survive verification; do not modify code for speculative style preferences.

- [ ] **Step 4: Fix confirmed findings with RED/GREEN tests**

Add a failing regression test, run it to prove RED, implement the smallest correction matching surrounding code, run the focused test to GREEN, then rerun all affected subsystem tests.

- [ ] **Step 5: Rerun full validation after corrections**

Repeat Task 18's complete Foss/Store unit suites, XML parsing, Koin compilation, separate lint variants, and assemblies. Repeat affected Shizuku emulator scenarios from Task 19.

- [ ] **Step 6: Commit and push corrections**

Stage only named corrected files, commit without a co-author trailer, and push to the existing branch. Update PR evidence if counts or behavior changed.

- [ ] **Step 7: Verify CI and review threads**

```shell
gh pr checks 453 --watch --fail-fast
```

Inspect CodeRabbit and human threads. Reproduce and address only verified findings, then rerun affected tests after each corrective push.

- [ ] **Step 8: Stop at the open-PR gate**

Require green required CI, no verified unresolved review findings, remote/local SHA equality, and an open PR. Do not merge PR #453; physical-device and additional emulator verification remain the user's merge gate.

## Plan Self-Review Result

- **Specification coverage:** Sections 1–5 are reflected in the goal, topology, and global constraints; Sections 6–9 map to Tasks 2–12; Sections 10–12 map to Tasks 13–17; Sections 13–17 map to Tasks 8–12 and 18–19; Sections 18–20 map to Tasks 18–19 and 21; Sections 21–22 map to the ordered task sequence and final PR/review gates.
- **Worker compatibility:** New producers create no WorkRequests. Released archive/export Workers and WorkManager runtime remain only for `THOR_JOB_CHAIN` drain; feature-only sweep work is cancelled/reconciled and cannot mutate through an old Worker.
- **State consistency:** Data and privilege tables remain separate; every claimed write is token-guarded; `UNKNOWN` and `LEGACY_UNKNOWN` are actionable, unresolved, and non-runnable; `OBSERVER_FAILURE` is UI-only.
- **Security consistency:** Archive secrets stay in caller-owned memory/`ArchiveKeyHolder`; restore staging persists no raw URI text; service Intents and SavedState contain task IDs only; share chooser starts only from a foreground handoff.
- **Android consistency:** Promotion precedes Koin/Room/privilege/IO; both services use correct manifest types; denied notification permission differs from blocked channels; API 35 timeout stops future claims; Recents swipe does not cancel; force-stop is respected.
- **UI consistency:** Queue is not a bottom tab; Background only navigates away; Cancel is request-scoped and persistence-first; action-required results cannot be acknowledged away; two queues may each show one running task.
- **Validation consistency:** Unit tasks use `--rerun-tasks`; totals come from XML; lint variants run separately; Shizuku emulator evidence remains distinct from physical Root and stock-device evidence.
- **Completeness scan:** Every implementation choice is resolved, and the baseline task requires observed values before its evidence commit.
- **Version and delivery:** `versionCode=1952`, PR #453 targets `dev`, branch is pushed but never merged, and final review covers `origin/dev...origin/feat/worker-shell-lanes`.
