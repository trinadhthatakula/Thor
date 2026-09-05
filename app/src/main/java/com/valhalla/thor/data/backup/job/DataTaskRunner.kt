// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.StoredDataDestination
import java.util.UUID
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal val ARCHIVE_AUTHENTICATION_REQUIRED =
    DataTaskResultCode("ARCHIVE_AUTHENTICATION_REQUIRED")
internal val ARCHIVE_RESTORE_INTERRUPTED =
    DataTaskResultCode("ARCHIVE_RESTORE_INTERRUPTED")
internal val RECOVERY_BREADCRUMB_MISSING =
    DataTaskResultCode("RECOVERY_BREADCRUMB_MISSING")

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
        /** Null only for released WorkSpecs, which have no durable publication identity. */
        val reconciliationIdentity: String? = null,
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

/** A durable archive request keeps its row and waits for a new in-memory key. */
internal fun archiveKeyUnavailableOutcome(): DataTaskRunOutcome =
    DataTaskRunOutcome.WaitingForAuthentication(ARCHIVE_AUTHENTICATION_REQUIRED)

/**
 * Refuses to replay a restore after its destructive boundary. A pre-mutation archive interruption is
 * deliberately allowed to restart only after the coordinator has supplied a fresh key and payload.
 */
internal fun recoveryOutcomeFor(request: DataTaskExecutionRequest): DataTaskRunOutcome? {
    val checkpoint = request.resumedFrom ?: return null
    if (!checkpoint.destructiveStarted) return null
    if (request.kind != DataTaskKind.ARCHIVE_RESTORE) {
        return DataTaskRunOutcome.TaskFailed(RECOVERY_BREADCRUMB_MISSING)
    }
    return DataTaskRunOutcome.InterruptedReview(
        resultCode = checkpoint.restoreMutationBreadcrumb
            ?.let { ARCHIVE_RESTORE_INTERRUPTED }
            ?: RECOVERY_BREADCRUMB_MISSING,
        breadcrumb = checkpoint.restoreMutationBreadcrumb,
    )
}

/**
 * Shared service-side execution boundary. The runner returns one typed outcome and this boundary is
 * the only owner that writes it. Cancellation first records the operation-specific recovery outcome,
 * then completes cleanup in [NonCancellable], and only then is rethrown to the parent service.
 */
internal suspend fun <R> runDataTaskAndPersist(
    runner: DataTaskRunner,
    request: DataTaskExecutionRequest,
    checkpoints: DataTaskCheckpointSink,
    results: DataTaskResultSink<R>,
    cleanup: suspend () -> Unit,
): R {
    require(runner.kind == request.kind) {
        "runner kind ${runner.kind} does not match request kind ${request.kind}"
    }
    var latestCheckpoint = request.resumedFrom
    val trackingCheckpoints = DataTaskCheckpointSink { checkpoint ->
        val write = checkpoints.persist(checkpoint)
        if (write == DataTaskSinkWrite.APPLIED) latestCheckpoint = checkpoint
        write
    }
    val callerJob = currentCoroutineContext()[Job]

    val persisted = try {
        val outcome = try {
            runner.run(request, trackingCheckpoints)
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                results.persist(interruptionOutcome(request.kind, latestCheckpoint))
            }
            throw cancellation
        }
        withContext(NonCancellable) { results.persist(outcome) }
    } finally {
        withContext(NonCancellable) { cleanup() }
    }
    callerJob?.ensureActive()
    return persisted
}

private fun interruptionOutcome(
    kind: DataTaskKind,
    checkpoint: DataTaskCheckpoint?,
): DataTaskRunOutcome {
    if (kind == DataTaskKind.ARCHIVE_RESTORE && checkpoint?.destructiveStarted == true) {
        return DataTaskRunOutcome.InterruptedReview(
            resultCode = checkpoint.restoreMutationBreadcrumb
                ?.let { ARCHIVE_RESTORE_INTERRUPTED }
                ?: RECOVERY_BREADCRUMB_MISSING,
            breadcrumb = checkpoint.restoreMutationBreadcrumb,
        )
    }
    return when (kind) {
        DataTaskKind.ARCHIVE_BACKUP,
        DataTaskKind.ARCHIVE_RESTORE,
            -> archiveKeyUnavailableOutcome()

        DataTaskKind.APP_EXPORT,
        DataTaskKind.SHARE_PREPARE,
            -> DataTaskRunOutcome.TaskFailed(DataTaskResultCode("TASK_INTERRUPTED"))
    }
}
