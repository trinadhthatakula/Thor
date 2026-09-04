// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.NewDataTaskOutput
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.bundleFileNameFor
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal const val SHARE_READY_RETENTION_MS = 24L * 60L * 60L * 1_000L
private const val SHARE_READY_ROOT = "share_ready"

private val SHARE_PREPARE_COMMAND = PrivilegeCommandClass("archive.share.prepare")
private val SHARE_PREPARE_COMPLETED = DataTaskResultCode("SHARE_PREPARE_COMPLETED")
private val SHARE_PREPARE_REQUEST_MISMATCH = DataTaskResultCode("SHARE_PREPARE_REQUEST_MISMATCH")
private val SHARE_PREPARE_CLEANUP_BUSY = DataTaskResultCode("SHARE_PREPARE_CLEANUP_BUSY")
private val SHARE_PREPARE_APP_NOT_INSTALLED =
    DataTaskResultCode("SHARE_PREPARE_APP_NOT_INSTALLED")
private val SHARE_PREPARE_STAGING_FAILED = DataTaskResultCode("SHARE_PREPARE_STAGING_FAILED")
private val SHARE_PREPARE_FAILED = DataTaskResultCode("SHARE_PREPARE_FAILED")
private val SHARE_PREPARE_FAILURE_REASON = DataTaskResultCode("SHARE_PREPARE_FAILURE_REASON")

/** File operations needed to prepare one private artifact; no chooser or URI exists on this port. */
internal interface SharePrepareTaskOperations {
    suspend fun awaitLaunchSweep(): Boolean

    suspend fun loadApp(packageName: String): AppInfo?

    /** Remove private bytes from an interrupted attempt before the item is rebuilt. */
    suspend fun discardIncomplete(stagingSubDir: String, packageName: String): Boolean

    suspend fun buildBundle(
        appInfo: AppInfo,
        cacheSubDir: String,
        format: BundleFormat,
        fileName: String,
        execution: PrivilegeExecutionContext,
    ): Result<File>
}

internal class SharePrepareTaskRunner(
    private val operations: SharePrepareTaskOperations,
    private val ioDispatcher: CoroutineDispatcher,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DataTaskRunner {
    override val kind = DataTaskKind.SHARE_PREPARE

    override suspend fun run(
        request: DataTaskExecutionRequest,
        checkpoints: DataTaskCheckpointSink,
    ): DataTaskRunOutcome {
        val payload = request.payload as? DataTaskExecutionPayload.SharePrepare
            ?: return exportTaskFailure(
                SHARE_PREPARE_REQUEST_MISMATCH,
                "this share request could not be read",
            )
        if (
            payload.publicationPolicy !=
            DataTaskPublicationPolicy.PRIVATE_SHARE_WITH_24_HOUR_EXPIRY ||
            !isSafeDataTaskStagingIdentity(request.item.deterministicStagingIdentity)
        ) {
            return exportTaskFailure(
                SHARE_PREPARE_REQUEST_MISMATCH,
                "this share request could not be read",
            )
        }

        var activeLabel = request.item.displayLabel ?: request.item.packageName
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
                return@withContext failedShareItem(
                    code = SHARE_PREPARE_CLEANUP_BUSY,
                    reason = "the launch cleanup did not finish",
                    nowMs = nowMs(),
                )
            }
            val appInfo = operations.loadApp(request.item.packageName)
                ?: return@withContext failedShareItem(
                    code = SHARE_PREPARE_APP_NOT_INSTALLED,
                    reason = "the app is not installed",
                    nowMs = nowMs(),
                )
            activeLabel = appInfo.appName ?: activeLabel

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

            val stagingSubDir = "$SHARE_READY_ROOT/${request.item.deterministicStagingIdentity}"
            if (!operations.discardIncomplete(stagingSubDir, request.item.packageName)) {
                return@withContext failedShareItem(
                    code = SHARE_PREPARE_STAGING_FAILED,
                    reason = "the interrupted share output could not be removed",
                    nowMs = nowMs(),
                )
            }

            val fileName = bundleFileNameFor(
                appInfo = appInfo,
                format = payload.requestedFormat,
                discriminator = "${request.item.packageName}_${request.item.ordinal}",
            )
            val execution = archiveExecutionContext(
                SHARE_PREPARE_COMMAND,
                request.item.packageName,
                request.taskId,
            )
            val file = try {
                operations.buildBundle(
                    appInfo = appInfo,
                    cacheSubDir = stagingSubDir,
                    format = payload.requestedFormat,
                    fileName = fileName,
                    execution = execution,
                ).getOrElse { cause ->
                    if (cause is CancellationException) throw cause
                    return@withContext failedShareItem(
                        code = SHARE_PREPARE_FAILED,
                        reason = exportFailureReason(cause)
                            ?: "the share bundle could not be prepared",
                        nowMs = nowMs(),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return@withContext failedShareItem(
                    code = SHARE_PREPARE_FAILED,
                    reason = exportFailureReason(failure)
                        ?: "the share bundle could not be prepared",
                    nowMs = nowMs(),
                )
            }

            if (!file.isFile || file.name != fileName) {
                return@withContext failedShareItem(
                    code = SHARE_PREPARE_STAGING_FAILED,
                    reason = "the prepared share output was not complete",
                    nowMs = nowMs(),
                )
            }

            val finishedAt = nowMs()
            val output = try {
                NewDataTaskOutput(
                    outputId = deterministicShareOutputId(request),
                    privateRelativePath = "$stagingSubDir/${request.item.packageName}/$fileName",
                    displayName = fileName,
                    mimeType = payload.requestedFormat.mime,
                    byteSize = file.length(),
                    state = DataTaskOutputState.READY,
                    expiresAtEpochMs = finishedAt + SHARE_READY_RETENTION_MS,
                )
            } catch (failure: IllegalArgumentException) {
                return@withContext failedShareItem(
                    code = SHARE_PREPARE_STAGING_FAILED,
                    reason = "the prepared share output had an invalid private location",
                    nowMs = finishedAt,
                )
            }
            DataTaskRunOutcome.ItemCompleted(
                DataTaskItemResult(
                    terminalState = DataTaskItemTerminalState.SUCCEEDED,
                    resultCode = SHARE_PREPARE_COMPLETED,
                    warnings = emptyList(),
                    outputs = listOf(output),
                    finishedAtEpochMs = finishedAt,
                )
            )
        }
    }
}

private fun deterministicShareOutputId(request: DataTaskExecutionRequest): UUID =
    UUID.nameUUIDFromBytes(
        "share:${request.taskId}:${request.item.ordinal}:${request.item.deterministicStagingIdentity}"
            .toByteArray(StandardCharsets.UTF_8)
    )

private fun failedShareItem(
    code: DataTaskResultCode,
    reason: String,
    nowMs: Long,
): DataTaskRunOutcome.ItemCompleted = DataTaskRunOutcome.ItemCompleted(
    DataTaskItemResult(
        terminalState = DataTaskItemTerminalState.FAILED,
        resultCode = code,
        warnings = listOf(safeDataTaskMessage(SHARE_PREPARE_FAILURE_REASON, reason)),
        outputs = emptyList(),
        finishedAtEpochMs = nowMs,
    )
)
