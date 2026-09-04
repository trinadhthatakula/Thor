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
import com.valhalla.thor.domain.model.MAX_TASK_PRESENTATION_ARGUMENT_CHARS
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.bundleFileNameFor
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import com.valhalla.thor.domain.usecase.ExportSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal const val LEGACY_APP_EXPORT_STAGING_IDENTITY = ExportAppUseCase.SINGLE_STAGING_DIR

internal val APP_EXPORT_COMPLETED = DataTaskResultCode("APP_EXPORT_COMPLETED")
internal val APP_EXPORT_REQUEST_MISMATCH = DataTaskResultCode("APP_EXPORT_REQUEST_MISMATCH")
internal val APP_EXPORT_CLEANUP_BUSY = DataTaskResultCode("APP_EXPORT_CLEANUP_BUSY")
internal val APP_EXPORT_APP_NOT_INSTALLED = DataTaskResultCode("APP_EXPORT_APP_NOT_INSTALLED")
internal val APP_EXPORT_DESTINATION_UNAVAILABLE =
    DataTaskResultCode("APP_EXPORT_DESTINATION_UNAVAILABLE")
internal val APP_EXPORT_FAILED = DataTaskResultCode("APP_EXPORT_FAILED")
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

    suspend fun exportInto(
        appInfo: AppInfo,
        format: BundleFormat,
        session: ExportSession,
        fileName: String?,
        execution: PrivilegeExecutionContext,
    ): Result<String>

    /** Process-local compatibility reporting may retain the destination label; Room never does. */
    fun onPublished(destinationLabel: String) = Unit
}

internal class AppExportTaskRunner(
    private val operations: AppExportTaskOperations,
    private val ioDispatcher: CoroutineDispatcher,
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

        var activeLabel = request.item.displayLabel ?: payload.request.label
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

        return withContext(ioDispatcher) {
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

            val legacy = request.item.deterministicStagingIdentity ==
                    LEGACY_APP_EXPORT_STAGING_IDENTITY
            val session = ExportSession(
                target = payload.request.target,
                stagingSubDir = request.item.deterministicStagingIdentity,
            )
            val fileName = if (legacy) {
                null
            } else {
                bundleFileNameFor(
                    appInfo = appInfo,
                    format = payload.request.format,
                    discriminator = "${request.item.packageName}_${request.item.ordinal}",
                )
            }
            val execution = archiveExecutionContext(
                APP_EXPORT_COMMAND,
                payload.request.packageName,
                request.taskId,
            )
            val destination = operations.exportInto(
                appInfo = appInfo,
                format = payload.request.format,
                session = session,
                fileName = fileName,
                execution = execution,
            ).getOrElse { cause ->
                if (cause is CancellationException) throw cause
                return@withContext failedExportItem(
                    code = APP_EXPORT_FAILED,
                    reason = exportFailureReason(cause) ?: "the export could not be completed",
                    nowMs = nowMs(),
                )
            }
            operations.onPublished(destination)
            DataTaskRunOutcome.ItemCompleted(
                DataTaskItemResult(
                    terminalState = DataTaskItemTerminalState.SUCCEEDED,
                    resultCode = APP_EXPORT_COMPLETED,
                    warnings = emptyList(),
                    outputs = emptyList(),
                    finishedAtEpochMs = nowMs(),
                )
            )
        }
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
