// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import android.os.PowerManager
import com.valhalla.thor.data.repository.archiveStagingVolume
import com.valhalla.thor.data.service.ForegroundTaskOwner
import com.valhalla.thor.data.service.ForegroundTaskWakeLock
import com.valhalla.thor.data.source.local.room.ClaimedDataTask
import com.valhalla.thor.data.source.local.room.ClaimedDataTaskItem
import com.valhalla.thor.data.source.local.room.DataTaskDao
import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.ArchiveBackupOutcome
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveRestoreDecision
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.InstalledAppFacts
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.StoredDataDestination
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.model.evaluateArchiveRestoreGate
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.AppBundleFileStore
import com.valhalla.thor.domain.repository.AppExportPublication
import com.valhalla.thor.domain.repository.AppExportPublicationIdentity
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.ArchiveOpenOutcome
import com.valhalla.thor.domain.repository.ArchiveSource
import com.valhalla.thor.domain.repository.ArchiveSourceFactory
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.repository.VerifiedOperationBoundary
import com.valhalla.thor.domain.repository.VerifiedProgress
import com.valhalla.thor.domain.usecase.ArchiveAuthenticationOutcome
import com.valhalla.thor.domain.usecase.ArchiveRestoreOutcome
import com.valhalla.thor.domain.usecase.BackupAppArchiveUseCase
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import com.valhalla.thor.domain.usecase.ExportSession
import com.valhalla.thor.domain.usecase.OpenArchiveUseCase
import com.valhalla.thor.domain.usecase.ReadInstalledAppFactsUseCase
import com.valhalla.thor.domain.usecase.RestoreAppArchiveUseCase
import com.valhalla.thor.util.ServiceQueueEvent
import com.valhalla.thor.util.ServiceQueueLatencyProbe
import com.valhalla.thor.util.ServiceQueueOperation
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Factory
import kotlin.time.Duration.Companion.seconds
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

internal data class DataSyncClaim(
    val taskId: UUID,
    val claimToken: String,
    val task: ClaimedDataTask? = null,
    val item: ClaimedDataTaskItem? = null,
)

internal interface DataSyncCoordinatorRuntime {
    suspend fun awaitLegacyDrain()
    suspend fun awaitLaunchSweep(): Boolean
    suspend fun recoverClaims(
        sessionToken: String,
        localOwnerIsLive: (UUID, String) -> Boolean,
    )

    suspend fun claimNext(sessionToken: String, claimToken: String): DataSyncClaim?
    suspend fun executeClaim(
        claim: DataSyncClaim,
        checkpoints: DataTaskCheckpointSink,
    ): DataTaskRunOutcome

    suspend fun settleTimeout(claim: DataSyncClaim): Boolean
    suspend fun persistOutcome(claim: DataSyncClaim, outcome: DataTaskRunOutcome)
    suspend fun cleanupClaim(claim: DataSyncClaim)
    fun checkpointSink(claim: DataSyncClaim): DataTaskCheckpointSink
    suspend fun finishDrainIfEmpty(onQueueEmpty: () -> Unit): Boolean
    fun nowMs(): Long
}

/** Android/Room adapter for the service-owned data queue. */
@Single(binds = [DataSyncCoordinatorRuntime::class])
internal class RoomDataSyncCoordinatorRuntime(
    context: Context,
    dao: DataTaskDao,
    private val legacyGate: LegacyDataWorkDrainGate,
    private val launchSweep: LaunchSweepBarrier,
    private val keys: ArchiveKeyHolder,
    private val restoreSources: RestoreSourceGrantHolder,
    private val restoreSourceStager: RestoreSourceStager,
    private val backup: BackupAppArchiveUseCase,
    private val restore: RestoreAppArchiveUseCase,
    private val exportApp: ExportAppUseCase,
    private val appRepository: AppRepository,
    private val bundleBuilder: AppBundleBuilder,
    private val fileStore: AppBundleFileStore,
    private val systemRepository: SystemRepository,
    private val dataProbe: com.valhalla.thor.domain.repository.AppDataProbe,
    private val archiveSources: ArchiveSourceFactory,
    private val openArchive: OpenArchiveUseCase,
    private val installedFacts: ReadInstalledAppFactsUseCase,
    private val registry: JobRegistry,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : DataSyncCoordinatorRuntime {
    private val applicationContext = context.applicationContext
    private val store = DataTaskStore(dao)
    private val powerManager = requireNotNull(
        applicationContext.getSystemService(PowerManager::class.java)
    )

    override suspend fun awaitLegacyDrain() {
        legacyGate.awaitDrained()
    }

    override suspend fun awaitLaunchSweep(): Boolean = launchSweep.awaitSwept()

    override suspend fun recoverClaims(
        sessionToken: String,
        localOwnerIsLive: (UUID, String) -> Boolean,
    ) {
        store.recoverClaims(sessionToken, nowMs(), localOwnerIsLive)
    }

    override suspend fun claimNext(sessionToken: String, claimToken: String): DataSyncClaim? {
        val now = nowMs()
        val task = store.claimOldestRunnableTask(
            sessionToken = sessionToken,
            claimToken = claimToken,
            nowMs = now,
            leaseUntilMs = now + CLAIM_LEASE_MILLIS,
        ) ?: return null
        val item = store.claimNextPendingItem(
            taskId = task.taskId,
            taskClaimToken = task.claimToken,
            itemClaimToken = UUID.randomUUID().toString(),
            nowMs = now,
            leaseUntilMs = now + CLAIM_LEASE_MILLIS,
        )
        if (item == null) {
            store.finishClaimedTaskIfDrained(task.taskId, task.claimToken, nowMs())
            return null
        }
        return DataSyncClaim(
            taskId = task.taskId,
            claimToken = task.claimToken,
            task = task,
            item = item,
        )
    }

    override suspend fun executeClaim(
        claim: DataSyncClaim,
        checkpoints: DataTaskCheckpointSink,
    ): DataTaskRunOutcome {
        val wakeLock = ForegroundTaskWakeLock(powerManager, ForegroundTaskOwner.DATA_SYNC)
        return wakeLock.withClaimedExecution {
            val renewingCheckpoints = renewingDataSyncCheckpointSink(
                delegate = checkpoints,
                renewLease = ::renewAfterPersistedCheckpoint,
            )
            val prepared = prepare(claim, renewingCheckpoints)
            if (prepared is PreparedTask.Refused) return@withClaimedExecution prepared.outcome
            prepared as PreparedTask.Ready
            if (prepared.request.kind == DataTaskKind.APP_EXPORT) {
                ServiceQueueLatencyProbe.mark(
                    ServiceQueueOperation.EXPORT,
                    ServiceQueueEvent.EXECUTION_ADMITTED,
                )
            }
            prepared.runner.run(prepared.request, renewingCheckpoints)
        }
    }

    override suspend fun settleTimeout(claim: DataSyncClaim): Boolean {
        val item = requireNotNull(claim.item)
        return store.settleClaimTimeout(
            taskId = claim.taskId,
            taskClaimToken = claim.claimToken,
            itemOrdinal = item.ordinal,
            itemClaimToken = item.claimToken,
            nowMs = nowMs(),
        )
    }

    override suspend fun persistOutcome(claim: DataSyncClaim, outcome: DataTaskRunOutcome) {
        val task = requireNotNull(claim.task)
        val item = requireNotNull(claim.item)
        val write = RoomDataTaskResultSink(
            store = store,
            taskId = task.taskId,
            taskClaimToken = task.claimToken,
            itemOrdinal = item.ordinal,
            itemClaimToken = item.claimToken,
            nowMs = ::nowMs,
        ).persist(outcome)
        if (write == DataTaskSinkWrite.APPLIED && task.kind == DataTaskKind.ARCHIVE_RESTORE) {
            val settled = store.loadTask(task.taskId)
            if (settled != null && settled.state.noLongerNeedsRestoreSource()) {
                val detail = settled.detail as? StoredDataTaskDetail.ArchiveRestore
                detail?.source?.let(restoreSourceStager::discard)
            }
        }
    }

    override suspend fun cleanupClaim(claim: DataSyncClaim) {
        keys.drop(claim.taskId.toString())
        restoreSources.dropTask(claim.taskId)
        registry.clear(claim.taskId)
    }

    override fun checkpointSink(claim: DataSyncClaim): DataTaskCheckpointSink {
        val task = requireNotNull(claim.task)
        val item = requireNotNull(claim.item)
        val room = RoomDataTaskCheckpointSink(
            store = store,
            taskId = task.taskId,
            taskClaimToken = task.claimToken,
            itemOrdinal = item.ordinal,
            itemClaimToken = item.claimToken,
            leaseUntilMs = { nowMs() + CLAIM_LEASE_MILLIS },
        )
        return DataTaskCheckpointSink { checkpoint ->
            room.persist(checkpoint).also { write ->
                if (write == DataTaskSinkWrite.APPLIED) {
                    registry.publish(task.taskId, checkpoint.toLegacyProgress())
                }
            }
        }
    }

    override suspend fun finishDrainIfEmpty(onQueueEmpty: () -> Unit): Boolean =
        store.finishDrainIfQueueEmpty(onQueueEmpty)

    override fun nowMs(): Long = System.currentTimeMillis()

    private suspend fun prepare(
        claim: DataSyncClaim,
        checkpoints: DataTaskCheckpointSink,
    ): PreparedTask {
        val task = requireNotNull(claim.task)
        val item = requireNotNull(claim.item)
        val executionItem = DataTaskExecutionItem(
            ordinal = item.ordinal,
            packageName = item.packageName,
            displayLabel = item.displayLabel,
            deterministicStagingIdentity = item.deterministicStagingIdentity,
            attemptCount = item.attemptCount,
        )
        val payload = when (val detail = task.detail) {
            is StoredDataTaskDetail.ArchiveBackup -> prepareBackup(task, detail)
            is StoredDataTaskDetail.ArchiveRestore -> {
                prepareRestore(claim, task, detail, checkpoints)
            }

            is StoredDataTaskDetail.AppExport -> prepareExport(item, detail)
            is StoredDataTaskDetail.SharePrepare -> return PreparedTask.Refused(
                DataTaskRunOutcome.TaskFailed(DataTaskResultCode("TASK_KIND_NOT_ACTIVE"))
            )
        }
        if (payload is PreparedPayload.Refused) return PreparedTask.Refused(payload.outcome)
        payload as PreparedPayload.Ready
        val request = DataTaskExecutionRequest(
            taskId = task.taskId,
            payload = payload.payload,
            item = executionItem,
            taskAttemptCount = task.attemptCount,
            resumedFrom = task.lastCheckpoint,
        )
        val runner = when (request.kind) {
            DataTaskKind.ARCHIVE_BACKUP -> backupRunner()
            DataTaskKind.ARCHIVE_RESTORE -> restoreRunner()
            DataTaskKind.APP_EXPORT -> exportRunner()
            DataTaskKind.SHARE_PREPARE -> error("share preparation is not active in this task")
        }
        return PreparedTask.Ready(request, runner)
    }

    private fun prepareBackup(
        task: ClaimedDataTask,
        detail: StoredDataTaskDetail.ArchiveBackup,
    ): PreparedPayload {
        val key = keys.take(task.taskId.toString())
            ?: return PreparedPayload.Refused(archiveKeyUnavailableOutcome())
        val classes = detail.dataClassIds.toDataClasses()
        if (classes.isEmpty()) {
            return PreparedPayload.Refused(
                DataTaskRunOutcome.TaskFailed(DataTaskResultCode("TASK_REQUEST_INVALID"))
            )
        }
        val salt = runCatching { Base64.getDecoder().decode(detail.kdfSaltBase64) }.getOrNull()
            ?: return PreparedPayload.Refused(
                DataTaskRunOutcome.TaskFailed(DataTaskResultCode("TASK_REQUEST_INVALID"))
            )
        return PreparedPayload.Ready(
            DataTaskExecutionPayload.ArchiveBackup(
                request = ArchiveBackupRequest(
                    packageName = detail.packageName,
                    classes = classes,
                    includeBundle = detail.includeBundle,
                    salt = salt,
                ),
                key = key,
                destination = detail.destination,
                reconciliationIdentity = detail.deterministicStagingIdentity,
            )
        )
    }

    private suspend fun prepareRestore(
        claim: DataSyncClaim,
        task: ClaimedDataTask,
        detail: StoredDataTaskDetail.ArchiveRestore,
        checkpoints: DataTaskCheckpointSink,
    ): PreparedPayload {
        val uri = when (
            val source = restoreSourceStager.resolve(claim, detail.source, checkpoints)
        ) {
            is RestoreSourceResolution.Ready -> source.uriString
            RestoreSourceResolution.WaitingForSource -> return PreparedPayload.Refused(
                DataTaskRunOutcome.WaitingForSource(
                    DataTaskResultCode("RESTORE_SOURCE_REQUIRED")
                )
            )

            RestoreSourceResolution.OwnershipLost -> return PreparedPayload.Refused(
                DataTaskRunOutcome.OwnershipLost
            )
        }
        val key = keys.take(task.taskId.toString())
            ?: return PreparedPayload.Refused(archiveKeyUnavailableOutcome())
        val classes = detail.dataClassIds.toDataClasses()
        if (classes.isEmpty()) {
            return PreparedPayload.Refused(
                DataTaskRunOutcome.TaskFailed(DataTaskResultCode("TASK_REQUEST_INVALID"))
            )
        }
        return PreparedPayload.Ready(
            DataTaskExecutionPayload.ArchiveRestore(
                request = ArchiveRestoreRequest(
                    uriString = uri,
                    packageName = detail.expectedPackageName,
                    classes = classes,
                    restoreObb = detail.restoreObb,
                ),
                key = key,
            )
        )
    }

    private fun prepareExport(
        item: ClaimedDataTaskItem,
        detail: StoredDataTaskDetail.AppExport,
    ): PreparedPayload {
        val treeUri = when (val destination = detail.destination) {
            StoredDataDestination.Downloads -> null
            is StoredDataDestination.PersistedTreeGrant ->
                persistedWritableUriFor(destination.grantIdentity)
                    ?: return PreparedPayload.Refused(
                        DataTaskRunOutcome.TaskFailed(
                            DataTaskResultCode("APP_EXPORT_DESTINATION_UNAVAILABLE")
                        )
                    )

            StoredDataDestination.ArchiveStore,
            StoredDataDestination.TaskPrivateStorage,
                -> return PreparedPayload.Refused(
                DataTaskRunOutcome.TaskFailed(DataTaskResultCode("TASK_REQUEST_INVALID"))
            )
        }
        return PreparedPayload.Ready(
            DataTaskExecutionPayload.AppExport(
                request = AppExportRequest(
                    packageName = item.packageName,
                    format = detail.requestedFormat,
                    label = detail.namingLabel,
                    treeUri = treeUri,
                ),
                publicationPolicy = detail.publicationPolicy,
            )
        )
    }

    private fun backupRunner(): DataTaskRunner = ArchiveBackupTaskRunner(
        operations = object : ArchiveBackupTaskOperations {
            override suspend fun reconcilePublished(
                fileName: String,
                expectedPackageName: String,
                key: SecretKey,
            ): ArchiveBackupOutcome.Completed? = backup.reconcilePublished(
                fileName,
                expectedPackageName,
                key,
            )

            override suspend fun loadApp(packageName: String): AppInfo? =
                appRepository.getAppDetails(packageName)

            override suspend fun probeObb(
                packageName: String,
                execution: PrivilegeExecutionContext,
            ): ObbProbe = systemRepository.probeObb(packageName, execution)

            override suspend fun buildBundle(
                appInfo: AppInfo,
                cacheSubDir: String,
                format: BundleFormat,
                execution: PrivilegeExecutionContext,
            ): Result<File> = bundleBuilder.build(
                appInfo = appInfo,
                cacheSubDir = cacheSubDir,
                format = format,
                execution = execution,
            )

            @Suppress("UsableSpace")
            override suspend fun usableStagingBytes(): Long =
                archiveStagingVolume(applicationContext, dataProbe.probePrivateDataCapability())
                    ?.usableSpace ?: 0L

            override suspend fun backup(
                request: ArchiveBackupRequest,
                key: SecretKey,
                bundle: File?,
                bundleObbCapture: String,
                bundleObbCount: Int,
                versionCode: Long,
                versionName: String?,
                publicationFileName: String?,
                usableStagingBytes: Long,
                appLabel: String,
                onProgress: (ThorJobProgress) -> Unit,
            ): ArchiveBackupOutcome = this@RoomDataSyncCoordinatorRuntime.backup(
                request = request,
                key = key,
                bundle = bundle,
                bundleObbCapture = bundleObbCapture,
                bundleObbCount = bundleObbCount,
                versionCode = versionCode,
                versionName = versionName,
                publicationFileName = publicationFileName,
                usableStagingBytes = usableStagingBytes,
                appLabel = appLabel,
                onProgress = onProgress,
            )
        },
        ioDispatcher = ioDispatcher,
    )

    private fun restoreRunner(): DataTaskRunner = ArchiveRestoreTaskRunner(
        operations = object : ArchiveRestoreTaskOperations {
            override suspend fun open(uriString: String): ArchiveOpenOutcome =
                archiveSources.open(uriString)

            override suspend fun authenticate(
                source: ArchiveSource,
                key: SecretKey,
            ): ArchiveAuthenticationOutcome = openArchive.authenticate(source, key)

            override suspend fun readPackageFacts(packageName: String): ArchiveRestorePackageFacts {
                val app = appRepository.getAppDetails(packageName)
                return ArchiveRestorePackageFacts(
                    installed = app?.let { installedFacts(it) },
                    appLabel = app?.appName,
                )
            }

            override fun evaluateGate(
                header: ArchiveHeader,
                installed: InstalledAppFacts?,
                classes: Set<DataClass>,
            ): ArchiveRestoreDecision = evaluateArchiveRestoreGate(header, installed, classes)

            override suspend fun restore(
                source: ArchiveSource,
                header: ArchiveHeader,
                key: SecretKey,
                classes: List<DataClass>,
                installFirst: Boolean,
                restoreObb: Boolean,
                execution: PrivilegeExecutionContext,
                appLabel: String,
                onProgress: (ThorJobProgress) -> Unit,
            ): ArchiveRestoreOutcome = this@RoomDataSyncCoordinatorRuntime.restore(
                source = source,
                header = header,
                key = key,
                classes = classes,
                installFirst = installFirst,
                restoreObb = restoreObb,
                execution = execution,
                appLabel = appLabel,
                onProgress = onProgress,
            )
        },
        ioDispatcher = ioDispatcher,
    )

    private fun exportRunner(): DataTaskRunner = AppExportTaskRunner(
        operations = object : AppExportTaskOperations {
            override suspend fun awaitLaunchSweep(): Boolean = true

            override suspend fun loadApp(packageName: String): AppInfo? =
                appRepository.getAppDetails(packageName)

            override suspend fun isTreeWritable(treeUri: String): Boolean =
                fileStore.isTreeWritable(treeUri)

            override suspend fun exportInto(
                appInfo: AppInfo,
                format: BundleFormat,
                session: ExportSession,
                publicationIdentity: AppExportPublicationIdentity?,
                execution: PrivilegeExecutionContext,
                captureProgress: VerifiedProgress,
                captureBoundary: VerifiedOperationBoundary,
                publicationProgress: VerifiedProgress,
            ): Result<AppExportPublication> = requireNotNull(publicationIdentity).let { identity ->
                exportApp.exportDurableInto(
                    appInfo = appInfo,
                    format = format,
                    session = session,
                    publicationIdentity = identity,
                    execution = execution,
                    captureProgress = captureProgress,
                    captureBoundary = captureBoundary,
                    publicationProgress = publicationProgress,
                )
            }
        },
        ioDispatcher = ioDispatcher,
    )

    private fun persistedWritableUriFor(identity: String): String? =
        applicationContext.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isWritePermission }
            .map { it.uri.toString() }
            .firstOrNull { it.toOpaqueGrantIdentity() == identity }

    private fun String.toOpaqueGrantIdentity(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
        return "tree_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun List<String>.toDataClasses(): Set<DataClass> =
        mapNotNull { id -> DataClass.entries.firstOrNull { it.id == id } }.toSet()

    private sealed interface PreparedPayload {
        data class Ready(val payload: DataTaskExecutionPayload) : PreparedPayload
        data class Refused(val outcome: DataTaskRunOutcome) : PreparedPayload
    }

    private sealed interface PreparedTask {
        data class Ready(
            val request: DataTaskExecutionRequest,
            val runner: DataTaskRunner,
        ) : PreparedTask

        data class Refused(val outcome: DataTaskRunOutcome) : PreparedTask
    }

    private companion object {
        const val CLAIM_LEASE_MILLIS = ForegroundTaskWakeLock.LEASE_MILLIS
    }
}

/** Serial process-local owner of the Room-backed data lane for one service generation. */
@Factory
class DataSyncCoordinator internal constructor(
    @Named("io") dispatcher: CoroutineDispatcher,
    private val ownerRegistry: DataTaskOwnerRegistry,
    private val runtime: DataSyncCoordinatorRuntime,
    private val sessionToken: String = UUID.randomUUID().toString(),
    private val claimTokenFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private var drain: Job? = null
    private var wakePending = false
    private var claimsEnabled = true
    private var claimedCallback: ((UUID, String) -> Unit)? = null
    private var drainedCallback: (() -> Unit)? = null
    private var activeClaim: DataSyncClaim? = null
    private val drainLaunches = AtomicInteger()
    private val generationToken = UUID.randomUUID().toString()

    internal constructor(
        dispatcher: CoroutineDispatcher,
        ownerRegistry: DataTaskOwnerRegistry,
        awaitLegacyDrain: suspend () -> Unit,
        awaitLaunchSweep: suspend () -> Boolean,
        recoverClaims: suspend (String, (UUID, String) -> Boolean) -> Unit,
        claimNext: suspend (String, String) -> DataSyncClaim?,
        executeClaim: suspend (DataSyncClaim, DataTaskCheckpointSink) -> DataTaskRunOutcome,
        settleTimeout: suspend (DataSyncClaim) -> Boolean,
        persistOutcome: suspend (DataSyncClaim, DataTaskRunOutcome) -> Unit,
        cleanupClaim: suspend (DataSyncClaim) -> Unit,
        checkpointSink: (DataSyncClaim) -> DataTaskCheckpointSink,
        finishDrainIfEmpty: suspend (() -> Unit) -> Boolean,
        sessionToken: String,
        claimTokenFactory: () -> String,
        nowMs: () -> Long,
    ) : this(
        dispatcher = dispatcher,
        ownerRegistry = ownerRegistry,
        runtime = object : DataSyncCoordinatorRuntime {
            override suspend fun awaitLegacyDrain() = awaitLegacyDrain()
            override suspend fun awaitLaunchSweep(): Boolean = awaitLaunchSweep()
            override suspend fun recoverClaims(
                sessionToken: String,
                localOwnerIsLive: (UUID, String) -> Boolean,
            ) = recoverClaims(sessionToken, localOwnerIsLive)

            override suspend fun claimNext(sessionToken: String, claimToken: String) =
                claimNext(sessionToken, claimToken)

            override suspend fun executeClaim(
                claim: DataSyncClaim,
                checkpoints: DataTaskCheckpointSink,
            ) = executeClaim(claim, checkpoints)

            override suspend fun settleTimeout(claim: DataSyncClaim): Boolean =
                settleTimeout(claim)

            override suspend fun persistOutcome(
                claim: DataSyncClaim,
                outcome: DataTaskRunOutcome,
            ) = persistOutcome(claim, outcome)

            override suspend fun cleanupClaim(claim: DataSyncClaim) = cleanupClaim(claim)
            override fun checkpointSink(claim: DataSyncClaim) = checkpointSink(claim)
            override suspend fun finishDrainIfEmpty(onQueueEmpty: () -> Unit) =
                finishDrainIfEmpty(onQueueEmpty)

            override fun nowMs(): Long = nowMs()
        },
        sessionToken = sessionToken,
        claimTokenFactory = claimTokenFactory,
    )

    internal val drainLaunchCountForTest: Int
        get() = drainLaunches.get()

    internal var lastClaimTokenForTest: String? = null
        private set

    fun wake(
        onClaimed: (UUID, String) -> Unit = { _, _ -> },
        onDrained: () -> Unit,
    ) {
        synchronized(lock) {
            claimedCallback = onClaimed
            drainedCallback = onDrained
            wakePending = true
            if (!claimsEnabled || drain?.isActive == true) return
            drainLaunches.incrementAndGet()
            drain = scope.launch { drainQueue() }
        }
    }

    suspend fun stopClaimsAndInterrupt() {
        val (claim, drainJob) = synchronized(lock) {
            claimsEnabled = false
            activeClaim to drain
        }
        if (claim == null) {
            drainJob?.cancel()
        } else {
            ownerRegistry.cancelActiveOwnedBy(claim.claimToken)
        }
        drainJob?.join()
    }

    private suspend fun drainQueue() {
        var infrastructureFailed = false
        var parkedBeforeClaim = false
        var laneAcquired = false
        try {
            // Consume the wake represented by this generation before any fallible operation. A later
            // wake can then be distinguished and relaunched if infrastructure fails during unwind.
            synchronized(lock) { wakePending = false }
            ownerRegistry.acquireLane(generationToken)
            laneAcquired = true
            if (!claimsAreEnabled()) return
            runtime.awaitLegacyDrain()
            if (!claimsAreEnabled()) return
            if (!runtime.awaitLaunchSweep()) {
                parkedBeforeClaim = true
                return
            }
            if (!claimsAreEnabled()) return
            runtime.recoverClaims(sessionToken, ownerRegistry::isLive)
            while (claimsAreEnabled()) {
                synchronized(lock) { wakePending = false }
                runtime.awaitLegacyDrain()
                if (!claimsAreEnabled()) break

                val claimToken = claimTokenFactory()
                lastClaimTokenForTest = claimToken
                ownerRegistry.registerProvisional(claimToken)
                val claim = try {
                    // Room may commit before cancellation returns. Observe the result, but bound the
                    // non-cancellable window below the service timeout shutdown deadline.
                    withContext(NonCancellable) {
                        withTimeout(CLAIM_TRANSITION_TIMEOUT) {
                            runtime.claimNext(sessionToken, claimToken)
                        }
                    }
                } catch (failure: Throwable) {
                    ownerRegistry.unregisterProvisional(claimToken)
                    throw failure
                }
                if (claim == null) {
                    ownerRegistry.unregisterProvisional(claimToken)
                    val callback = synchronized(lock) { drainedCallback } ?: {}
                    if (runtime.finishDrainIfEmpty(callback)) break
                    continue
                }

                check(ownerRegistry.bindTask(claim.taskId, claimToken)) {
                    "Data task claim lost its process-local owner before execution"
                }
                val ownedClaim = claim.copy(claimToken = claimToken)
                val (claimsStillEnabled, onClaimed) = synchronized(lock) {
                    activeClaim = ownedClaim
                    claimsEnabled to claimedCallback
                }
                try {
                    if (claimsStillEnabled) {
                        onClaimed?.invoke(
                            claim.taskId,
                            claim.item?.displayLabel ?: claim.item?.packageName.orEmpty(),
                        )
                        executeOwnedClaim(ownedClaim)
                    } else {
                        settleClaimTimedOutBeforeExecution(ownedClaim)
                    }
                } catch (_: CancellationException) {
                    // The claimed child owns its bounded settlement before propagating cancellation.
                } finally {
                    synchronized(lock) {
                        if (activeClaim?.claimToken == claimToken) activeClaim = null
                    }
                    withContext(NonCancellable) {
                        ownerRegistry.unregister(claim.taskId, claimToken)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            infrastructureFailed = true
        } finally {
            if (laneAcquired) {
                withContext(NonCancellable) {
                    ownerRegistry.releaseLane(generationToken)
                }
            }
            val stopCallback = synchronized(lock) {
                drain = null
                when {
                    !claimsEnabled -> drainedCallback
                    wakePending -> {
                        drainLaunches.incrementAndGet()
                        drain = scope.launch { drainQueue() }
                        null
                    }

                    infrastructureFailed || parkedBeforeClaim -> drainedCallback
                    else -> null
                }
            }
            stopCallback?.invoke()
        }
    }

    private suspend fun settleClaimTimedOutBeforeExecution(claim: DataSyncClaim) {
        runBoundedSettlement {
            runtime.settleTimeout(claim)
            runtime.cleanupClaim(claim)
        }
    }

    private suspend fun executeOwnedClaim(claim: DataSyncClaim) = coroutineScope {
        val enterExecution = CompletableDeferred<Unit>()
        val child = async(start = CoroutineStart.UNDISPATCHED) {
            executeAndSettle(claim, enterExecution)
        }
        if (!ownerRegistry.attachChild(claim.taskId, claim.claimToken, child)) {
            child.cancel()
            enterExecution.complete(Unit)
            error("Data task claim lost its process-local owner before child attachment")
        }
        enterExecution.complete(Unit)
        child.await()
    }

    private suspend fun executeAndSettle(
        claim: DataSyncClaim,
        enterExecution: CompletableDeferred<Unit>,
    ) {
        try {
            val outcome = try {
                enterExecution.await()
                runtime.executeClaim(claim, runtime.checkpointSink(claim))
            } catch (cancelled: CancellationException) {
                runBoundedSettlement {
                    if (claimsAreEnabled()) {
                        runtime.cleanupClaim(claim)
                        runtime.persistOutcome(claim, DataTaskRunOutcome.Cancelled)
                    } else {
                        runtime.settleTimeout(claim)
                        runtime.cleanupClaim(claim)
                    }
                }
                throw cancelled
            } catch (_: Exception) {
                DataTaskRunOutcome.TaskFailed(DataTaskResultCode("TASK_EXECUTION_FAILED"))
            }
            runBoundedSettlement {
                runtime.persistOutcome(claim, outcome)
                runtime.cleanupClaim(claim)
            }
        } finally {
            // The enclosing drain unregisters this claim only after this wrapper exits.
        }
    }

    private suspend fun runBoundedSettlement(block: suspend () -> Unit) {
        val completed = withContext(NonCancellable) {
            withTimeoutOrNull(CLAIM_TRANSITION_TIMEOUT) {
                block()
                true
            } ?: false
        }
        check(completed) { "Data task settlement timed out" }
    }

    private fun claimsAreEnabled(): Boolean = synchronized(lock) { claimsEnabled }

    private companion object {
        val CLAIM_TRANSITION_TIMEOUT = 2.seconds
    }
}

private fun DataTaskState.noLongerNeedsRestoreSource(): Boolean = when (this) {
    DataTaskState.READY,
    DataTaskState.READY_PARTIAL,
    DataTaskState.SUCCEEDED,
    DataTaskState.PARTIAL,
    DataTaskState.FAILED,
    DataTaskState.CANCELLED,
    DataTaskState.EXPIRED,
        -> true

    DataTaskState.QUEUED,
    DataTaskState.STAGING_SOURCE,
    DataTaskState.RUNNING,
    DataTaskState.CANCEL_REQUESTED,
    DataTaskState.WAITING_FOR_AUTH,
    DataTaskState.WAITING_FOR_SOURCE,
    DataTaskState.INTERRUPTED_REVIEW,
    DataTaskState.START_BLOCKED,
    DataTaskState.START_BLOCKED_NOTIFICATION,
        -> false
}

internal fun renewingDataSyncCheckpointSink(
    delegate: DataTaskCheckpointSink,
    renewLease: () -> Unit,
): DataTaskCheckpointSink = DataTaskCheckpointSink { checkpoint ->
    delegate.persist(checkpoint).also { write ->
        if (write == DataTaskSinkWrite.APPLIED) renewLease()
    }
}

private fun DataTaskCheckpoint.toLegacyProgress(): ThorJobProgress = ThorJobProgress(
    stage = when (stage) {
        com.valhalla.thor.domain.model.DataTaskStage.PREPARING -> ThorJobStage.PREPARING
        com.valhalla.thor.domain.model.DataTaskStage.STAGING_SOURCE -> ThorJobStage.PREPARING
        com.valhalla.thor.domain.model.DataTaskStage.MEASURING -> ThorJobStage.MEASURING
        com.valhalla.thor.domain.model.DataTaskStage.CAPTURING -> ThorJobStage.CAPTURING
        com.valhalla.thor.domain.model.DataTaskStage.WRITING -> ThorJobStage.WRITING
        com.valhalla.thor.domain.model.DataTaskStage.INSTALLING -> ThorJobStage.INSTALLING
        com.valhalla.thor.domain.model.DataTaskStage.RESTORING -> ThorJobStage.RESTORING
        com.valhalla.thor.domain.model.DataTaskStage.PUBLISHING -> ThorJobStage.WRITING
        com.valhalla.thor.domain.model.DataTaskStage.FINISHING -> ThorJobStage.FINISHING
    },
    label = activeItemLabel.orEmpty(),
    completed = completed,
    total = total,
)
