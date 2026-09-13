// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.BACKUP_BUNDLE_KEY
import com.valhalla.thor.domain.model.BACKUP_CLASSES_KEY
import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.JOB_ERROR_KEY
import com.valhalla.thor.domain.model.JOB_WARNINGS_KEY
import com.valhalla.thor.domain.model.RESTORE_CLASSES_KEY
import com.valhalla.thor.domain.model.RESTORE_OBB_KEY
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.model.toUserFacingJobMessage
import java.util.UUID
import javax.crypto.SecretKey

internal fun decodeLegacyArchiveBackupRequest(map: Map<String, Any?>): ArchiveBackupRequest? {
    if (!map.hasReleasedStringArray(BACKUP_CLASSES_KEY)) return null
    if (map.containsKey(BACKUP_BUNDLE_KEY) && map[BACKUP_BUNDLE_KEY] !is Boolean) return null
    return ArchiveBackupRequest.fromMap(map)
}

internal fun decodeLegacyArchiveRestoreRequest(map: Map<String, Any?>): ArchiveRestoreRequest? {
    if (!map.hasReleasedStringArray(RESTORE_CLASSES_KEY)) return null
    if (map.containsKey(RESTORE_OBB_KEY) && map[RESTORE_OBB_KEY] !is Boolean) return null
    return ArchiveRestoreRequest.fromMap(map)
}

private fun Map<String, Any?>.hasReleasedStringArray(key: String): Boolean {
    val values = this[key] as? Array<*> ?: return false
    return values.javaClass.componentType == String::class.java &&
            values.all { it is String }
}

/**
 * Strict compatibility drain for WorkSpecs persisted by released versions.
 *
 * Decoding is validated before the key is taken. A malformed request or missing process-local key
 * produces one bounded failure and cannot reach request construction, package lookup, source opening,
 * Room, or retry.
 */
internal suspend fun <T, R> runLegacyArchiveTask(
    taskId: UUID,
    decodedRequest: T?,
    invalidRequestReason: String,
    requestFactory: (UUID, SecretKey, T) -> DataTaskExecutionRequest,
    takeKey: (String) -> SecretKey?,
    runner: DataTaskRunner,
    checkpoints: DataTaskCheckpointSink,
    results: DataTaskResultSink<R>,
    missingKeyReason: String,
): R {
    val legacyRequest = decodedRequest
        ?: return results.persist(
            DataTaskRunOutcome.TaskFailed(
                resultCode = DataTaskResultCode("LEGACY_ARCHIVE_REQUEST_INVALID"),
                arguments = listOf(invalidRequestReason.boundedForJobData()),
            )
        )
    val key = takeKey(taskId.toString())
        ?: return results.persist(
            DataTaskRunOutcome.TaskFailed(
                resultCode = DataTaskResultCode("LEGACY_ARCHIVE_KEY_MISSING"),
                arguments = listOf(missingKeyReason.boundedForJobData()),
            )
        )
    val request = requestFactory(taskId, key, legacyRequest)
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
    private val onSuccess: () -> Unit = {},
    private val onFailure: (String) -> Unit = {},
) : DataTaskResultSink<ListenableWorker.Result> {
    override suspend fun persist(outcome: DataTaskRunOutcome): ListenableWorker.Result =
        when (outcome) {
            is DataTaskRunOutcome.ItemCompleted -> when (outcome.result.terminalState) {
                DataTaskItemTerminalState.SUCCEEDED -> success(outcome)
                DataTaskItemTerminalState.FAILED ->
                    failure(outcome.result.resultCode.toUserFacingJobMessage())

                DataTaskItemTerminalState.CANCELLED -> failure("this job was cancelled")
            }

            is DataTaskRunOutcome.TaskFailed -> failure(
                outcome.arguments.firstOrNull() ?: outcome.resultCode.toUserFacingJobMessage()
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
        onSuccess()
        if (kind != DataTaskKind.ARCHIVE_RESTORE) return ListenableWorker.Result.success()
        val warnings = outcome.result.warnings.map { warning ->
            (warning.arguments.firstOrNull() ?: warning.code.toUserFacingJobMessage())
                .boundedForJobData()
        }
        return if (warnings.isEmpty()) {
            ListenableWorker.Result.success()
        } else {
            ListenableWorker.Result.success(
                workDataOf(JOB_WARNINGS_KEY to warnings.toTypedArray())
            )
        }
    }

    private fun failure(reason: String): ListenableWorker.Result {
        val bounded = reason.boundedForJobData()
        onFailure(bounded)
        return ListenableWorker.Result.failure(workDataOf(JOB_ERROR_KEY to bounded))
    }
}

internal fun DataTaskStage.toLegacyStage(): ThorJobStage = when (this) {
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
