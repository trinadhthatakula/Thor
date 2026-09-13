// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveRestoreDecision
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskMessage
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.InstalledAppFacts
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.ArchiveOpenOutcome
import com.valhalla.thor.domain.repository.ArchiveSource
import com.valhalla.thor.domain.usecase.ArchiveAuthenticationOutcome
import com.valhalla.thor.domain.usecase.ArchiveRestoreOutcome
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private val ARCHIVE_RESTORE_COMMAND = PrivilegeCommandClass("archive.restore")
private val RESTORE_COMPLETED = DataTaskResultCode("ARCHIVE_RESTORE_COMPLETED")
private val RESTORE_NOT_AN_ARCHIVE = DataTaskResultCode("ARCHIVE_RESTORE_NOT_AN_ARCHIVE")
private val RESTORE_SOURCE_UNREADABLE = DataTaskResultCode("ARCHIVE_RESTORE_SOURCE_UNREADABLE")
private val RESTORE_AUTHENTICATION_FAILED =
    DataTaskResultCode("ARCHIVE_RESTORE_AUTHENTICATION_FAILED")
private val RESTORE_GATE_REFUSED = DataTaskResultCode("ARCHIVE_RESTORE_GATE_REFUSED")
private val RESTORE_FAILED = DataTaskResultCode("ARCHIVE_RESTORE_FAILED")
private val RESTORE_REQUEST_MISMATCH = DataTaskResultCode("ARCHIVE_RESTORE_REQUEST_MISMATCH")
private val RESTORE_WARNING = DataTaskResultCode("ARCHIVE_RESTORE_WARNING")

internal interface ArchiveRestoreTaskOperations {
    suspend fun open(uriString: String): ArchiveOpenOutcome

    suspend fun authenticate(
        source: ArchiveSource,
        key: SecretKey,
    ): ArchiveAuthenticationOutcome

    suspend fun readPackageFacts(packageName: String): ArchiveRestorePackageFacts

    fun evaluateGate(
        header: ArchiveHeader,
        installed: InstalledAppFacts?,
        classes: Set<DataClass>,
    ): ArchiveRestoreDecision

    suspend fun restore(
        source: ArchiveSource,
        header: ArchiveHeader,
        key: SecretKey,
        classes: List<DataClass>,
        installFirst: Boolean,
        restoreObb: Boolean,
        execution: PrivilegeExecutionContext,
        appLabel: String,
        onProgress: (ThorJobProgress) -> Unit,
    ): ArchiveRestoreOutcome
}

internal class ArchiveRestoreTaskRunner(
    private val operations: ArchiveRestoreTaskOperations,
    private val ioDispatcher: CoroutineDispatcher,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DataTaskRunner {
    override val kind = DataTaskKind.ARCHIVE_RESTORE

    override suspend fun run(
        request: DataTaskExecutionRequest,
        checkpoints: DataTaskCheckpointSink,
    ): DataTaskRunOutcome = runArchiveRestoreTask(
        request = request,
        operations = operations,
        ioDispatcher = ioDispatcher,
        checkpoints = checkpoints,
        nowMs = nowMs,
    )
}

internal suspend fun runArchiveRestoreTask(
    request: DataTaskExecutionRequest,
    operations: ArchiveRestoreTaskOperations,
    ioDispatcher: CoroutineDispatcher,
    checkpoints: DataTaskCheckpointSink,
    nowMs: () -> Long,
): DataTaskRunOutcome {
    val payload = request.payload as? DataTaskExecutionPayload.ArchiveRestore
        ?: return archiveTaskFailure(
            RESTORE_REQUEST_MISMATCH,
            "this restore's request could not be read",
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
        ARCHIVE_RESTORE_COMMAND,
        payload.request.packageName,
        request.taskId,
    )
    return withContext(ioDispatcher) {
        when (val opened = operations.open(payload.request.uriString)) {
            ArchiveOpenOutcome.NotAnArchive -> archiveTaskFailure(
                RESTORE_NOT_AN_ARCHIVE,
                "that file is not a Thor backup",
            )

            ArchiveOpenOutcome.Unreadable -> DataTaskRunOutcome.WaitingForSource(
                RESTORE_SOURCE_UNREADABLE
            )

            is ArchiveOpenOutcome.Opened -> opened.source.use { source ->
                val preflight = runArchiveRestorePreflight(
                    expectedPackageName = payload.request.packageName,
                    selectedClasses = payload.request.classes,
                    authenticate = { operations.authenticate(source, payload.key) },
                    readPackageFacts = operations::readPackageFacts,
                    evaluateGate = operations::evaluateGate,
                )
                val ready = when (preflight) {
                    ArchiveRestorePreflight.AuthenticationRefused,
                    ArchiveRestorePreflight.PackageMismatch,
                        -> return@use archiveTaskFailure(
                        RESTORE_AUTHENTICATION_FAILED,
                        ARCHIVE_AUTH_FAILURE_REASON,
                    )

                    is ArchiveRestorePreflight.GateRefused -> return@use archiveTaskFailure(
                        RESTORE_GATE_REFUSED,
                        "this backup can no longer be restored: ${refusalReason(preflight.reason)}",
                    )

                    is ArchiveRestorePreflight.Ready -> preflight
                }
                activeLabel = ready.packageFacts.appLabel ?: payload.request.packageName
                var destructiveStarted = false
                var breadcrumb: RestoreMutationBreadcrumb? = null
                val progress: (ThorJobProgress) -> Unit = { update ->
                    if (
                        update.stage == ThorJobStage.INSTALLING ||
                        update.stage == ThorJobStage.RESTORING
                    ) {
                        destructiveStarted = true
                        if (breadcrumb == null) {
                            breadcrumb = RestoreMutationBreadcrumb(
                                packageName = payload.request.packageName,
                                appLabel = activeLabel,
                                startedAtEpochMs = nowMs(),
                            )
                        }
                    }
                    val write = runBlocking {
                        checkpoints.persist(
                            request.checkpoint(
                                stage = update.stage.toDataTaskStage(),
                                label = update.label.ifBlank { activeLabel },
                                completed = update.completed,
                                total = update.total,
                                destructiveStarted = destructiveStarted,
                                breadcrumb = breadcrumb,
                                nowMs = nowMs(),
                            )
                        )
                    }
                    if (write == DataTaskSinkWrite.OWNERSHIP_LOST) {
                        throw DataTaskOwnershipLostException()
                    }
                }
                val outcome = try {
                    operations.restore(
                        source = source,
                        header = ready.header,
                        key = payload.key,
                        classes = payload.request.orderedClasses(),
                        installFirst = ready.decision.installFirst,
                        restoreObb = payload.request.restoreObb,
                        execution = execution,
                        appLabel = activeLabel,
                        onProgress = progress,
                    )
                } catch (_: DataTaskOwnershipLostException) {
                    return@use DataTaskRunOutcome.OwnershipLost
                }
                when (outcome) {
                    is ArchiveRestoreOutcome.Completed -> DataTaskRunOutcome.ItemCompleted(
                        DataTaskItemResult(
                            terminalState = DataTaskItemTerminalState.SUCCEEDED,
                            resultCode = RESTORE_COMPLETED,
                            warnings = (
                                    outcome.warnings + listOfNotNull(obbNotice(outcome.obb))
                                    ).take(4).map { warning -> safeRestoreWarning(warning) },
                            outputs = emptyList(),
                            finishedAtEpochMs = nowMs(),
                        )
                    )

                    is ArchiveRestoreOutcome.Failed -> archiveTaskFailure(
                        RESTORE_FAILED,
                        restoreFailureReason(outcome),
                        "the restore could not be completed",
                    )
                }
            }
        }
    }
}

private fun safeRestoreWarning(warning: String): DataTaskMessage {
    val bounded = warning.boundedForJobData()
    return runCatching {
        DataTaskMessage(RESTORE_WARNING, listOf(bounded))
    }.getOrElse {
        DataTaskMessage(
            RESTORE_WARNING,
            listOf("part of the restore could not be completed"),
        )
    }
}
