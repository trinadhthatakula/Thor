// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.JOB_ERROR_KEY
import com.valhalla.thor.domain.model.JOB_WARNINGS_KEY
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import java.util.UUID
import javax.crypto.SecretKey

internal fun decodeLegacyArchiveBackupRequest(map: Map<String, Any?>): ArchiveBackupRequest? =
    ArchiveBackupRequest.fromMap(map)

internal fun decodeLegacyArchiveRestoreRequest(map: Map<String, Any?>): ArchiveRestoreRequest? =
    ArchiveRestoreRequest.fromMap(map)

/**
 * Strict compatibility drain for WorkSpecs persisted by released versions.
 *
 * Taking the key is deliberately the first effect. A missing process-local key produces one bounded
 * failure and cannot reach request construction, package lookup, source opening, Room, or retry.
 */
internal suspend fun <R> runLegacyArchiveTask(
    taskId: UUID,
    requestFactory: (UUID, SecretKey) -> DataTaskExecutionRequest,
    takeKey: (String) -> SecretKey?,
    runner: DataTaskRunner,
    checkpoints: DataTaskCheckpointSink,
    results: DataTaskResultSink<R>,
    missingKeyReason: String,
): R {
    val key = takeKey(taskId.toString())
        ?: return results.persist(
            DataTaskRunOutcome.TaskFailed(
                resultCode = DataTaskResultCode("LEGACY_ARCHIVE_KEY_MISSING"),
                arguments = listOf(missingKeyReason.boundedForJobData()),
            )
        )
    val request = requestFactory(taskId, key)
    require(request.kind == runner.kind) {
        "runner kind ${runner.kind} does not match request kind ${request.kind}"
    }
    return results.persist(runner.run(request, checkpoints))
}

/** Publishes process-local progress only; it never writes WorkManager progress Data. */
internal class LegacyWorkerCheckpointSink(
    private val publish: (ThorJobProgress) -> Unit,
) : DataTaskCheckpointSink {
    override suspend fun persist(checkpoint: DataTaskCheckpoint): DataTaskSinkWrite {
        publish(
            ThorJobProgress(
                stage = checkpoint.stage.toLegacyStage(),
                label = checkpoint.activeItemLabel.orEmpty(),
                completed = checkpoint.completed,
                total = checkpoint.total,
            )
        )
        return DataTaskSinkWrite.APPLIED
    }
}

/** Maps the typed runner outcome back to the exact released WorkManager result channel. */
internal class LegacyWorkerResultSink(
    private val kind: DataTaskKind,
) : DataTaskResultSink<ListenableWorker.Result> {
    override suspend fun persist(outcome: DataTaskRunOutcome): ListenableWorker.Result =
        when (outcome) {
            is DataTaskRunOutcome.ItemCompleted -> when (outcome.result.terminalState) {
                DataTaskItemTerminalState.SUCCEEDED -> success(outcome)
                DataTaskItemTerminalState.FAILED -> failure(outcome.result.resultCode.legacyReason())
                DataTaskItemTerminalState.CANCELLED -> failure("this job was cancelled")
            }

            is DataTaskRunOutcome.TaskFailed -> failure(
                outcome.arguments.firstOrNull() ?: outcome.resultCode.legacyReason()
            )

            is DataTaskRunOutcome.WaitingForAuthentication -> failure(
                "this archive's key is no longer in memory — start it again"
            )

            is DataTaskRunOutcome.WaitingForSource -> failure(
                "Thor could not read that backup file"
            )

            is DataTaskRunOutcome.InterruptedReview -> failure(
                "this restore stopped after it began changing the app; review it before trying again"
            )

            DataTaskRunOutcome.Cancelled -> failure("this job was cancelled")
            DataTaskRunOutcome.OwnershipLost -> failure("this job no longer owns its task")
        }

    private fun success(outcome: DataTaskRunOutcome.ItemCompleted): ListenableWorker.Result {
        if (kind != DataTaskKind.ARCHIVE_RESTORE) return ListenableWorker.Result.success()
        val warnings = outcome.result.warnings.map { warning ->
            (warning.arguments.firstOrNull() ?: warning.code.legacyReason()).boundedForJobData()
        }
        return if (warnings.isEmpty()) {
            ListenableWorker.Result.success()
        } else {
            ListenableWorker.Result.success(
                workDataOf(JOB_WARNINGS_KEY to warnings.toTypedArray())
            )
        }
    }

    private fun failure(reason: String): ListenableWorker.Result = ListenableWorker.Result.failure(
        workDataOf(JOB_ERROR_KEY to reason.boundedForJobData())
    )
}

private fun DataTaskStage.toLegacyStage(): ThorJobStage = when (this) {
    DataTaskStage.PREPARING,
    DataTaskStage.STAGING_SOURCE,
        -> ThorJobStage.PREPARING

    DataTaskStage.MEASURING -> ThorJobStage.MEASURING
    DataTaskStage.CAPTURING -> ThorJobStage.CAPTURING
    DataTaskStage.WRITING -> ThorJobStage.WRITING
    DataTaskStage.INSTALLING -> ThorJobStage.INSTALLING
    DataTaskStage.RESTORING -> ThorJobStage.RESTORING
    DataTaskStage.PUBLISHING,
    DataTaskStage.FINISHING,
        -> ThorJobStage.FINISHING
}

private fun DataTaskResultCode.legacyReason(): String = when (value) {
    "ARCHIVE_BACKUP_APP_NOT_INSTALLED" -> "the app is not installed"
    "ARCHIVE_BACKUP_BUNDLE_FAILED" -> "the app's installer bundle could not be built"
    "ARCHIVE_BACKUP_DESTINATION_REQUIRED" -> "choose a folder for Thor's backups first"
    "ARCHIVE_RESTORE_NOT_AN_ARCHIVE" -> "that file is not a Thor backup"
    "ARCHIVE_RESTORE_SOURCE_UNREADABLE" -> "Thor could not read that backup file"
    "ARCHIVE_RESTORE_AUTHENTICATION_FAILED" -> ARCHIVE_AUTH_FAILURE_REASON
    "ARCHIVE_RESTORE_INTERRUPTED" ->
        "this restore stopped after it began changing the app; review it before trying again"

    else -> "the archive job could not be completed"
}
