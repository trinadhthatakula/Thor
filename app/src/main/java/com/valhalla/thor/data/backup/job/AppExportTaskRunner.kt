// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskMessage
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.ExportTargetChoice
import com.valhalla.thor.domain.repository.AppExportPublicationReconciliation
import com.valhalla.thor.domain.model.MAX_TASK_PRESENTATION_ARGUMENT_CHARS
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.repository.AppExportPublication
import com.valhalla.thor.domain.repository.AppExportPublicationIdentity
import com.valhalla.thor.domain.repository.VerifiedOperationBoundary
import com.valhalla.thor.domain.repository.VerifiedProgress
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import com.valhalla.thor.domain.usecase.ExportSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal const val LEGACY_APP_EXPORT_STAGING_IDENTITY = ExportAppUseCase.SINGLE_STAGING_DIR
internal const val DATA_TASK_PROGRESS_MIN_BYTES = 4L * 1024L * 1024L
private const val DATA_TASK_PROGRESS_MAX_INTERVAL_MS = 60_000L

internal val APP_EXPORT_COMPLETED = DataTaskResultCode("APP_EXPORT_COMPLETED")
internal val APP_EXPORT_REQUEST_MISMATCH = DataTaskResultCode("APP_EXPORT_REQUEST_MISMATCH")
internal val APP_EXPORT_CLEANUP_BUSY = DataTaskResultCode("APP_EXPORT_CLEANUP_BUSY")
internal val APP_EXPORT_APP_NOT_INSTALLED = DataTaskResultCode("APP_EXPORT_APP_NOT_INSTALLED")
internal val APP_EXPORT_DESTINATION_UNAVAILABLE =
    DataTaskResultCode("APP_EXPORT_DESTINATION_UNAVAILABLE")
internal val APP_EXPORT_FAILED = DataTaskResultCode("APP_EXPORT_FAILED")
internal val APP_EXPORT_PUBLICATION_UNCERTAIN = DataTaskResultCode("APP_EXPORT_PUBLICATION_UNCERTAIN")
internal val APP_EXPORT_FAILURE_REASON = DataTaskResultCode("APP_EXPORT_FAILURE_REASON")

private val APP_EXPORT_COMMAND = PrivilegeCommandClass("archive.export")
private val SAFE_STAGING_IDENTITY = Regex("[A-Za-z0-9._-]{1,200}")

internal fun isSafeDataTaskStagingIdentity(value: String): Boolean =
    value != "." && value != ".." && SAFE_STAGING_IDENTITY.matches(value)

/** Android-free operations needed by one export item. */
internal interface AppExportTaskOperations {
    suspend fun awaitLaunchSweep(): Boolean

    suspend fun loadApp(packageName: String): AppInfo?

    suspend fun isTreeWritable(treeUri: String): Boolean

    suspend fun reconcilePublication(
        target: ExportTargetChoice,
        identity: AppExportPublicationIdentity,
    ): AppExportPublicationReconciliation

    suspend fun exportInto(
        appInfo: AppInfo,
        format: BundleFormat,
        session: ExportSession,
        publicationIdentity: AppExportPublicationIdentity?,
        execution: PrivilegeExecutionContext,
        captureProgress: VerifiedProgress,
        captureBoundary: VerifiedOperationBoundary,
        publicationProgress: VerifiedProgress,
        publicationStart: suspend () -> Unit,
    ): Result<AppExportPublication>

    /** Process-local compatibility reporting may retain the destination label; Room never does. */
    fun onPublished(destinationLabel: String) = Unit
}

internal class AppExportTaskRunner(
    private val operations: AppExportTaskOperations,
    private val ioDispatcher: CoroutineDispatcher,
    private val monotonicNowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DataTaskRunner {
    override val kind = DataTaskKind.APP_EXPORT

    override suspend fun run(
        request: DataTaskExecutionRequest,
        checkpoints: DataTaskCheckpointSink,
    ): DataTaskRunOutcome {
        val payload = request.payload as? DataTaskExecutionPayload.AppExport
            ?: return exportTaskFailure(
                APP_EXPORT_REQUEST_MISMATCH,
                "this export's request could not be read",
            )
        if (
            payload.publicationPolicy != DataTaskPublicationPolicy.PUBLIC_DOCUMENT ||
            payload.request.packageName != request.item.packageName ||
            !isSafeDataTaskStagingIdentity(request.item.deterministicStagingIdentity)
        ) {
            return exportTaskFailure(
                APP_EXPORT_REQUEST_MISMATCH,
                "this export's request could not be read",
            )
        }

        val legacy = request.item.deterministicStagingIdentity == LEGACY_APP_EXPORT_STAGING_IDENTITY
        val publicationIdentity = if (legacy) null else AppExportPublicationIdentity(
            "Thor-task-${request.taskId}-${request.item.ordinal}." + payload.request.format.extension
        )
        var activeLabel = request.item.displayLabel ?: payload.request.label
        if (!legacy && payload.request.treeUri != null && request.resumedFrom?.stage == DataTaskStage.PUBLISHING) {
            // Keep this fence through repeated interruption. No PREPARING, package lookup or build
            // may erase the only durable evidence that a provider side effect could already exist.
            return try {
                withContext(ioDispatcher) {
                    if (checkpoints.persist(request.checkpoint(stage = DataTaskStage.PUBLISHING, label = activeLabel, nowMs = nowMs())) ==
                        DataTaskSinkWrite.OWNERSHIP_LOST) return@withContext DataTaskRunOutcome.OwnershipLost
                    when (val reconciled = operations.reconcilePublication(payload.request.target, requireNotNull(publicationIdentity))) {
                        is AppExportPublicationReconciliation.Complete -> completed(reconciled.publication)
                        AppExportPublicationReconciliation.Absent -> uncertainPublication()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                uncertainPublication()
            }
        }
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

        return try {
            withContext(ioDispatcher) {
                if (!operations.awaitLaunchSweep()) {
                    return@withContext exportTaskFailure(
                        APP_EXPORT_CLEANUP_BUSY,
                        "the launch cleanup did not finish",
                    )
                }

                val appInfo = operations.loadApp(payload.request.packageName)
                    ?: return@withContext failedExportItem(
                        code = APP_EXPORT_APP_NOT_INSTALLED,
                        reason = "the app is not installed",
                        nowMs = nowMs(),
                    )
                activeLabel = appInfo.appName ?: activeLabel

                payload.request.treeUri?.let { treeUri ->
                    if (!operations.isTreeWritable(treeUri)) {
                        return@withContext failedExportItem(
                            code = APP_EXPORT_DESTINATION_UNAVAILABLE,
                            reason = "the selected export folder is no longer writable",
                            nowMs = nowMs(),
                        )
                    }
                }

                if (
                    checkpoints.persist(
                        request.checkpoint(
                            stage = DataTaskStage.CAPTURING,
                            label = activeLabel,
                            nowMs = nowMs(),
                        )
                    ) == DataTaskSinkWrite.OWNERSHIP_LOST
                ) {
                    return@withContext DataTaskRunOutcome.OwnershipLost
                }

                val session = ExportSession(
                    target = payload.request.target,
                    stagingSubDir = request.item.deterministicStagingIdentity,
                )
                val execution = archiveExecutionContext(
                    APP_EXPORT_COMMAND,
                    payload.request.packageName,
                    request.taskId,
                )
                val captureCheckpointer = DataTaskProgressCheckpointer(
                    request = request,
                    stage = DataTaskStage.CAPTURING,
                    label = { activeLabel },
                    checkpoints = checkpoints,
                    nowMs = nowMs,
                    monotonicNowMs = monotonicNowMs,
                )
                val captureProgress = captureCheckpointer.asProgress()
                val captureBoundary = if (legacy) {
                    VerifiedOperationBoundary.NONE
                } else {
                    captureCheckpointer.asOperationBoundary()
                }
                val publicationProgress = DataTaskProgressCheckpointer(
                    request = request,
                    stage = DataTaskStage.PUBLISHING,
                    label = { activeLabel },
                    checkpoints = checkpoints,
                    nowMs = nowMs,
                    monotonicNowMs = monotonicNowMs,
                ).asProgress()
                val publication = operations.exportInto(
                    appInfo = appInfo,
                    format = payload.request.format,
                    session = session,
                    publicationIdentity = publicationIdentity,
                    execution = execution,
                    captureProgress = captureProgress,
                    captureBoundary = captureBoundary,
                    publicationProgress = publicationProgress,
                    publicationStart = {
                        if (!legacy && checkpoints.persist(request.checkpoint(
                                stage = DataTaskStage.PUBLISHING, label = activeLabel, nowMs = nowMs(),
                            )) == DataTaskSinkWrite.OWNERSHIP_LOST) {
                            throw ExportTaskOwnershipLostCancellation()
                        }
                    },
                ).getOrElse { cause ->
                    if (cause is CancellationException) throw cause
                    return@withContext failedExportItem(
                        code = APP_EXPORT_FAILED,
                        reason = exportFailureReason(cause) ?: "the export could not be completed",
                        nowMs = nowMs(),
                    )
                }
                completed(publication)
            }
        } catch (_: ExportTaskOwnershipLostCancellation) {
            DataTaskRunOutcome.OwnershipLost
        }
    }
    private fun uncertainPublication() = failedExportItem(
        code = APP_EXPORT_PUBLICATION_UNCERTAIN,
        reason = "the interrupted export could not be verified; inspect the selected folder before exporting again",
        nowMs = nowMs(),
    )

    private fun completed(publication: AppExportPublication): DataTaskRunOutcome.ItemCompleted {
        operations.onPublished(publication.destinationLabel)
        return DataTaskRunOutcome.ItemCompleted(DataTaskItemResult(
            terminalState = DataTaskItemTerminalState.SUCCEEDED,
            resultCode = APP_EXPORT_COMPLETED,
            warnings = emptyList(),
            outputs = emptyList(),
            finishedAtEpochMs = nowMs(),
        ))
    }
}

internal class ExportTaskOwnershipLostCancellation :
    CancellationException("data task ownership lost")

internal class DataTaskProgressCheckpointer(
    private val request: DataTaskExecutionRequest,
    private val stage: DataTaskStage,
    private val label: () -> String,
    private val checkpoints: DataTaskCheckpointSink,
    private val nowMs: () -> Long,
    private val monotonicNowMs: () -> Long,
) {
    private var uncheckpointedBytes = 0L
    private var lastCheckpointMs = monotonicNowMs()

    fun asProgress(): VerifiedProgress = VerifiedProgress { bytes ->
        if (bytes <= 0L) return@VerifiedProgress
        uncheckpointedBytes += bytes
        val currentMonotonicMs = monotonicNowMs()
        if (
            uncheckpointedBytes < DATA_TASK_PROGRESS_MIN_BYTES &&
            currentMonotonicMs - lastCheckpointMs < DATA_TASK_PROGRESS_MAX_INTERVAL_MS
        ) {
            return@VerifiedProgress
        }
        persistVerifiedCheckpoint(currentMonotonicMs)
    }

    fun asOperationBoundary(): VerifiedOperationBoundary = VerifiedOperationBoundary {
        persistVerifiedCheckpoint(monotonicNowMs())
    }

    private suspend fun persistVerifiedCheckpoint(currentMonotonicMs: Long) {
        val write = checkpoints.persist(
            request.checkpoint(stage = stage, label = label(), nowMs = nowMs())
        )
        if (write == DataTaskSinkWrite.OWNERSHIP_LOST) {
            throw ExportTaskOwnershipLostCancellation()
        }
        uncheckpointedBytes = 0L
        lastCheckpointMs = currentMonotonicMs
    }
}

internal fun failedExportItem(
    code: DataTaskResultCode,
    reason: String,
    nowMs: Long,
): DataTaskRunOutcome.ItemCompleted = DataTaskRunOutcome.ItemCompleted(
    DataTaskItemResult(
        terminalState = DataTaskItemTerminalState.FAILED,
        resultCode = code,
        warnings = listOf(safeDataTaskMessage(APP_EXPORT_FAILURE_REASON, reason)),
        outputs = emptyList(),
        finishedAtEpochMs = nowMs,
    )
)

internal fun safeDataTaskMessage(
    code: DataTaskResultCode,
    reason: String,
    fallback: String = "the operation could not be completed",
): DataTaskMessage {
    fun bounded(value: String): String = if (value.length <= MAX_TASK_PRESENTATION_ARGUMENT_CHARS) {
        value
    } else {
        value.take(MAX_TASK_PRESENTATION_ARGUMENT_CHARS - 1) + "…"
    }
    return runCatching { DataTaskMessage(code, listOf(bounded(reason))) }
        .getOrElse { DataTaskMessage(code, listOf(bounded(fallback))) }
}

internal fun exportTaskFailure(
    code: DataTaskResultCode,
    reason: String,
): DataTaskRunOutcome.TaskFailed {
    val message = safeDataTaskMessage(code, reason)
    return DataTaskRunOutcome.TaskFailed(code, message.arguments)
}
