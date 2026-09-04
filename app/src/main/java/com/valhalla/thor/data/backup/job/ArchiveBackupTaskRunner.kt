// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.ArchiveBackupOutcome
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveBundleCacheDir
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.NewDataTaskOutput
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.THORBAK_MIME
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.model.captureName
import com.valhalla.thor.domain.model.recoverableThorbakFileName
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private val ARCHIVE_BACKUP_COMMAND = PrivilegeCommandClass("archive.backup")
private val BACKUP_COMPLETED = DataTaskResultCode("ARCHIVE_BACKUP_COMPLETED")
private val BACKUP_APP_NOT_INSTALLED = DataTaskResultCode("ARCHIVE_BACKUP_APP_NOT_INSTALLED")
private val BACKUP_BUNDLE_FAILED = DataTaskResultCode("ARCHIVE_BACKUP_BUNDLE_FAILED")
private val BACKUP_FAILED = DataTaskResultCode("ARCHIVE_BACKUP_FAILED")
private val BACKUP_DESTINATION_REQUIRED =
    DataTaskResultCode("ARCHIVE_BACKUP_DESTINATION_REQUIRED")
private val BACKUP_REQUEST_MISMATCH = DataTaskResultCode("ARCHIVE_BACKUP_REQUEST_MISMATCH")

internal interface ArchiveBackupTaskOperations {
    suspend fun reconcilePublished(
        fileName: String,
        expectedPackageName: String,
        key: SecretKey,
    ): ArchiveBackupOutcome.Completed?

    suspend fun loadApp(packageName: String): AppInfo?

    fun onAppResolved(appInfo: AppInfo) = Unit

    suspend fun probeObb(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): ObbProbe

    suspend fun buildBundle(
        appInfo: AppInfo,
        cacheSubDir: String,
        format: BundleFormat,
        execution: PrivilegeExecutionContext,
    ): Result<File>

    suspend fun usableStagingBytes(): Long

    suspend fun backup(
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
    ): ArchiveBackupOutcome
}

internal class ArchiveBackupTaskRunner(
    private val operations: ArchiveBackupTaskOperations,
    private val ioDispatcher: CoroutineDispatcher,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DataTaskRunner {
    override val kind = DataTaskKind.ARCHIVE_BACKUP

    override suspend fun run(
        request: DataTaskExecutionRequest,
        checkpoints: DataTaskCheckpointSink,
    ): DataTaskRunOutcome = runArchiveBackupTask(
        request = request,
        operations = operations,
        ioDispatcher = ioDispatcher,
        checkpoints = checkpoints,
        nowMs = nowMs,
    )
}

internal suspend fun runArchiveBackupTask(
    request: DataTaskExecutionRequest,
    operations: ArchiveBackupTaskOperations,
    ioDispatcher: CoroutineDispatcher,
    checkpoints: DataTaskCheckpointSink,
    nowMs: () -> Long,
): DataTaskRunOutcome {
    val payload = request.payload as? DataTaskExecutionPayload.ArchiveBackup
        ?: return archiveTaskFailure(
            BACKUP_REQUEST_MISMATCH,
            "this backup's request could not be read"
        )
    recoveryOutcomeFor(request)?.let { return it }
    var activeLabel = request.item.displayLabel ?: payload.request.packageName
    if (
        checkpoints.persist(
            request.checkpoint(
                stage = DataTaskStage.PREPARING,
                label = activeLabel,
                nowMs = nowMs(),
            )
        ) == DataTaskSinkWrite.OWNERSHIP_LOST
    ) {
        return DataTaskRunOutcome.OwnershipLost
    }

    val execution = archiveExecutionContext(
        ARCHIVE_BACKUP_COMMAND,
        payload.request.packageName,
        request.taskId,
    )
    val publicationFileName = payload.reconciliationIdentity?.let { identity ->
        recoverableThorbakFileName(payload.request.packageName, identity)
    }
    val recoveryFileName = if (request.resumedFrom != null) {
        publicationFileName ?: return archiveTaskFailure(
            BACKUP_REQUEST_MISMATCH,
            "this backup's recovery identity could not be read",
        )
    } else {
        null
    }
    return withContext(ioDispatcher) {
        if (recoveryFileName != null) {
            val reconciled = operations.reconcilePublished(
                fileName = recoveryFileName,
                expectedPackageName = payload.request.packageName,
                key = payload.key,
            )
            if (reconciled != null) {
                return@withContext archiveBackupCompleted(request, reconciled, nowMs())
            }
        }

        val appInfo = operations.loadApp(payload.request.packageName)
            ?: return@withContext archiveTaskFailure(
                BACKUP_APP_NOT_INSTALLED,
                "${payload.request.packageName} is not installed",
                "the app is not installed",
            )
        operations.onAppResolved(appInfo)
        activeLabel = appInfo.appName ?: payload.request.packageName

        var bundle: File? = null
        try {
            val probe = if (payload.request.includeBundle) {
                operations.probeObb(payload.request.packageName, execution)
            } else {
                ObbProbe.None
            }
            if (payload.request.includeBundle) {
                bundle = operations.buildBundle(
                    appInfo = appInfo,
                    cacheSubDir = ArchiveBundleCacheDir.NAME,
                    format = BundleFormat.XAPK,
                    execution = execution,
                ).getOrElse { failure ->
                    return@withContext archiveTaskFailure(
                        BACKUP_BUNDLE_FAILED,
                        "the app's installer bundle could not be built: ${failure.message}",
                        "the app's installer bundle could not be built",
                    )
                }
            }

            val progress: (ThorJobProgress) -> Unit = { update ->
                val write = runBlocking {
                    checkpoints.persist(
                        request.checkpoint(
                            stage = update.stage.toDataTaskStage(),
                            label = update.label.ifBlank { activeLabel },
                            completed = update.completed,
                            total = update.total,
                            nowMs = nowMs(),
                        )
                    )
                }
                if (write == DataTaskSinkWrite.OWNERSHIP_LOST) throw DataTaskOwnershipLostException()
            }
            val outcome = try {
                operations.backup(
                    request = payload.request,
                    key = payload.key,
                    bundle = bundle,
                    bundleObbCapture = probe.captureName(),
                    bundleObbCount = (probe as? ObbProbe.Present)?.files?.size ?: 0,
                    versionCode = appInfo.versionCode,
                    versionName = appInfo.versionName,
                    publicationFileName = publicationFileName,
                    usableStagingBytes = operations.usableStagingBytes(),
                    appLabel = activeLabel,
                    onProgress = progress,
                )
            } catch (_: DataTaskOwnershipLostException) {
                return@withContext DataTaskRunOutcome.OwnershipLost
            }
            when (outcome) {
                is ArchiveBackupOutcome.Completed -> archiveBackupCompleted(
                    request = request,
                    completed = outcome,
                    finishedAtEpochMs = nowMs(),
                )

                is ArchiveBackupOutcome.Failed -> archiveTaskFailure(
                    BACKUP_FAILED,
                    outcome.reason,
                    "the backup could not be completed",
                )

                ArchiveBackupOutcome.NoDestination -> archiveTaskFailure(
                    BACKUP_DESTINATION_REQUIRED,
                    "choose a folder for Thor's backups first",
                )
            }
        } finally {
            bundle?.delete()
        }
    }
}

private fun archiveBackupCompleted(
    request: DataTaskExecutionRequest,
    completed: ArchiveBackupOutcome.Completed,
    finishedAtEpochMs: Long,
): DataTaskRunOutcome.ItemCompleted = DataTaskRunOutcome.ItemCompleted(
    DataTaskItemResult(
        terminalState = DataTaskItemTerminalState.SUCCEEDED,
        resultCode = BACKUP_COMPLETED,
        warnings = emptyList(),
        outputs = listOf(
            NewDataTaskOutput(
                outputId = request.taskId,
                privateRelativePath = null,
                displayName = completed.fileName,
                mimeType = THORBAK_MIME,
                byteSize = completed.byteSize,
                state = DataTaskOutputState.PUBLISHED,
                expiresAtEpochMs = null,
            )
        ),
        finishedAtEpochMs = finishedAtEpochMs,
    )
)

internal class DataTaskOwnershipLostException : RuntimeException()

internal fun DataTaskExecutionRequest.checkpoint(
    stage: DataTaskStage,
    label: String?,
    completed: Long = 0L,
    total: Long = 0L,
    destructiveStarted: Boolean = false,
    breadcrumb: com.valhalla.thor.domain.model.RestoreMutationBreadcrumb? = null,
    nowMs: Long,
): DataTaskCheckpoint = DataTaskCheckpoint(
    stage = stage,
    completed = completed,
    total = total,
    activeItemOrdinal = item.ordinal,
    activeItemLabel = label,
    destructiveStarted = destructiveStarted,
    restoreMutationBreadcrumb = breadcrumb,
    recordedAtEpochMs = nowMs,
)

internal fun ThorJobStage.toDataTaskStage(): DataTaskStage = when (this) {
    ThorJobStage.PREPARING -> DataTaskStage.PREPARING
    ThorJobStage.MEASURING -> DataTaskStage.MEASURING
    ThorJobStage.CAPTURING -> DataTaskStage.CAPTURING
    ThorJobStage.WRITING -> DataTaskStage.WRITING
    ThorJobStage.INSTALLING -> DataTaskStage.INSTALLING
    ThorJobStage.RESTORING -> DataTaskStage.RESTORING
    ThorJobStage.ACTING -> DataTaskStage.PREPARING
    ThorJobStage.FINISHING -> DataTaskStage.FINISHING
}

internal fun archiveTaskFailure(
    code: DataTaskResultCode,
    reason: String,
    safeFallback: String = reason,
): DataTaskRunOutcome {
    val bounded = reason.boundedForJobData()
    return runCatching {
        DataTaskRunOutcome.TaskFailed(code, listOf(bounded))
    }.getOrElse {
        DataTaskRunOutcome.TaskFailed(code, listOf(safeFallback.boundedForJobData()))
    }
}
