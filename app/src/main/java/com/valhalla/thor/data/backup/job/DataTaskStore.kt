// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.data.source.local.room.ClaimedDataTask
import com.valhalla.thor.data.source.local.room.ClaimedDataTaskItem
import com.valhalla.thor.data.source.local.room.ClaimedDataTaskWork
import com.valhalla.thor.data.source.local.room.DataTaskCancellationDecision
import com.valhalla.thor.data.source.local.room.DataTaskDao
import com.valhalla.thor.data.source.local.room.DataTaskOutputSnapshot
import com.valhalla.thor.data.source.local.room.DataTaskRecoveryCandidate
import com.valhalla.thor.data.source.local.room.DataTaskSnapshot
import com.valhalla.thor.data.source.local.room.NewDataTaskItem
import com.valhalla.thor.data.source.local.room.NewDataTaskRow
import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskInterruption
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.StoredDataDestination
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.model.StoredRestoreSource
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Typed boundary around the durable data queue.
 *
 * The store is deliberately unannotated until Task 9 activates the foreground-service queue. Raw
 * source and destination URIs are reduced to their durable contracts before they reach Room.
 */
internal class DataTaskStore(
    private val dao: DataTaskDao,
) : DataTaskAcceptanceStore {

    override suspend fun insertBackup(
        taskId: UUID,
        request: ArchiveBackupRequest,
        nowMs: Long,
    ): DataTaskState = dao.insertTask(
        NewDataTaskRow(
            taskId = taskId.toString(),
            payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
            kind = DataTaskKind.ARCHIVE_BACKUP,
            targetKey = request.packageName.toTargetKey(),
            initialState = DataTaskState.QUEUED,
            detail = StoredDataTaskDetail.ArchiveBackup(
                packageName = request.packageName,
                dataClassIds = DataClass.entries.filter { it in request.classes }.map { it.id },
                includeBundle = request.includeBundle,
                kdfSaltBase64 = Base64.getEncoder().encodeToString(request.salt),
                destination = StoredDataDestination.ArchiveStore,
                deterministicStagingIdentity = taskId.stagingIdentity(),
            ),
            items = listOf(request.packageName.toItem(taskId, displayLabel = null)),
            createdAtEpochMs = nowMs,
        ),
    ).state

    override suspend fun insertRestore(
        taskId: UUID,
        request: ArchiveRestoreRequest,
        nowMs: Long,
    ): DataTaskState = dao.insertTask(
        NewDataTaskRow(
            taskId = taskId.toString(),
            payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
            kind = DataTaskKind.ARCHIVE_RESTORE,
            targetKey = request.packageName.toTargetKey(),
            initialState = DataTaskState.STAGING_SOURCE,
            detail = StoredDataTaskDetail.ArchiveRestore(
                expectedPackageName = request.packageName,
                dataClassIds = request.orderedClasses().map { it.id },
                restoreObb = request.restoreObb,
                source = StoredRestoreSource.AwaitingTransientGrant,
                mutationBreadcrumb = null,
                deterministicStagingIdentity = taskId.stagingIdentity(),
            ),
            items = listOf(request.packageName.toItem(taskId, displayLabel = null)),
            createdAtEpochMs = nowMs,
        ),
    ).state

    override suspend fun insertExport(
        taskId: UUID,
        request: AppExportRequest,
        nowMs: Long,
    ): DataTaskState = dao.insertTask(
        NewDataTaskRow(
            taskId = taskId.toString(),
            payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
            kind = DataTaskKind.APP_EXPORT,
            targetKey = request.packageName.toTargetKey(),
            initialState = DataTaskState.QUEUED,
            detail = StoredDataTaskDetail.AppExport(
                requestedFormat = request.format,
                destination = request.treeUri?.let { uri ->
                    StoredDataDestination.PersistedTreeGrant(uri.toOpaqueGrantIdentity())
                } ?: StoredDataDestination.Downloads,
                namingLabel = request.label,
                publicationPolicy = DataTaskPublicationPolicy.PUBLIC_DOCUMENT,
                deterministicStagingIdentity = taskId.stagingIdentity(),
            ),
            items = listOf(request.packageName.toItem(taskId, request.label)),
            createdAtEpochMs = nowMs,
        ),
    ).state

    suspend fun loadTask(taskId: UUID): DataTaskSnapshot? = dao.loadTask(taskId.toString())

    fun observeTask(taskId: UUID): Flow<DataTaskSnapshot?> = dao.observeTask(taskId.toString())

    fun observeActiveTaskId(kind: DataTaskKind, target: String): Flow<UUID?> =
        dao.observeActiveTaskId(kind, "package:$target")

    override suspend fun compareAndSetStartBlocked(
        taskId: UUID,
        expectedState: DataTaskState,
        blockedState: DataTaskState,
        nowMs: Long,
    ): Boolean = dao.compareAndSetStartBlocked(
        taskId = taskId.toString(),
        expectedState = expectedState,
        blockedState = blockedState,
        nowMs = nowMs,
    )

    suspend fun blockCurrentStart(
        taskId: UUID?,
        blockedState: DataTaskState,
        nowMs: Long,
    ): DataTaskSnapshot? = dao.blockCurrentStart(
        taskId = taskId?.toString(),
        blockedState = blockedState,
        nowMs = nowMs,
    )

    suspend fun claimOldestRunnableTask(
        sessionToken: String,
        claimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): ClaimedDataTask? = dao.claimOldestRunnableTask(
        sessionToken = sessionToken,
        claimToken = claimToken,
        nowMs = nowMs,
        leaseUntilMs = leaseUntilMs,
    )

    suspend fun claimNextPendingItem(
        taskId: UUID,
        taskClaimToken: String,
        itemClaimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): ClaimedDataTaskItem? = dao.claimNextPendingItem(
        taskId = taskId.toString(),
        taskClaimToken = taskClaimToken,
        itemClaimToken = itemClaimToken,
        nowMs = nowMs,
        leaseUntilMs = leaseUntilMs,
    )

    suspend fun claimOldestRunnableWork(
        sessionToken: String,
        taskClaimToken: String,
        itemClaimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): ClaimedDataTaskWork? = dao.claimOldestRunnableWork(
        sessionToken = sessionToken,
        taskClaimToken = taskClaimToken,
        itemClaimToken = itemClaimToken,
        nowMs = nowMs,
        leaseUntilMs = leaseUntilMs,
    )

    suspend fun settleClaimAcquisitionFailure(
        taskClaimToken: String,
        nowMs: Long,
    ): Boolean = dao.settleClaimAcquisitionFailure(taskClaimToken, nowMs)

    suspend fun commitPrivateRestoreSource(
        taskId: UUID,
        taskClaimToken: String,
        itemOrdinal: Int,
        itemClaimToken: String,
        privateRelativePath: String,
        nowMs: Long,
    ): Boolean = dao.commitPrivateRestoreSource(
        taskId = taskId.toString(),
        taskClaimToken = taskClaimToken,
        itemOrdinal = itemOrdinal,
        itemClaimToken = itemClaimToken,
        privateRelativePath = privateRelativePath,
        nowMs = nowMs,
        transactionNowMs = System::currentTimeMillis,
    )

    suspend fun hasClaimTokens(
        taskClaimToken: String,
        itemClaimToken: String?,
    ): Boolean = dao.hasClaimTokens(taskClaimToken, itemClaimToken)

    suspend fun uncommittedRestoreSourceCleanupTaskIds(): List<UUID> =
        dao.uncommittedRestoreSourceCleanupTaskIds()

    suspend fun markClaimInterrupted(
        taskId: UUID,
        taskClaimToken: String,
        interruption: DataTaskInterruption,
        resultCode: DataTaskResultCode,
        nowMs: Long,
    ): Boolean = dao.markClaimInterrupted(
        taskId = taskId.toString(),
        taskClaimToken = taskClaimToken,
        interruption = interruption,
        resultCode = resultCode,
        nowMs = nowMs,
    )

    suspend fun settleClaimTimeout(
        taskId: UUID,
        taskClaimToken: String,
        itemOrdinal: Int,
        itemClaimToken: String,
        nowMs: Long,
    ): Boolean = dao.settleClaimTimeout(
        taskId = taskId.toString(),
        taskClaimToken = taskClaimToken,
        itemOrdinal = itemOrdinal,
        itemClaimToken = itemClaimToken,
        nowMs = nowMs,
    )

    suspend fun checkpointClaimedTask(
        taskId: UUID,
        taskClaimToken: String,
        itemOrdinal: Int,
        itemClaimToken: String,
        checkpoint: DataTaskCheckpoint,
        leaseUntilMs: Long,
    ): Boolean = dao.checkpointClaimedTask(
        taskId = taskId.toString(),
        taskClaimToken = taskClaimToken,
        itemOrdinal = itemOrdinal,
        itemClaimToken = itemClaimToken,
        checkpoint = checkpoint,
        leaseUntilMs = leaseUntilMs,
    )

    suspend fun completeClaimedItem(
        taskId: UUID,
        itemOrdinal: Int,
        taskClaimToken: String,
        itemClaimToken: String,
        result: DataTaskItemResult,
    ): Boolean = dao.completeClaimedItem(
        taskId = taskId.toString(),
        ordinal = itemOrdinal,
        taskClaimToken = taskClaimToken,
        itemClaimToken = itemClaimToken,
        result = result,
    )

    suspend fun settleClaimedTask(
        taskId: UUID,
        taskClaimToken: String,
        itemOrdinal: Int,
        itemClaimToken: String,
        outcome: DataTaskRunOutcome,
        nowMs: Long,
    ): Boolean = dao.settleClaimedTask(
        taskId = taskId.toString(),
        taskClaimToken = taskClaimToken,
        itemOrdinal = itemOrdinal,
        itemClaimToken = itemClaimToken,
        outcome = outcome,
        nowMs = nowMs,
    )

    suspend fun finishClaimedTaskIfDrained(
        taskId: UUID,
        taskClaimToken: String,
        nowMs: Long,
    ): Boolean = dao.finishClaimedTaskIfDrained(
        taskId = taskId.toString(),
        claimToken = taskClaimToken,
        nowMs = nowMs,
    )

    suspend fun requestCancellation(
        taskId: UUID,
        nowMs: Long,
    ): DataTaskCancellationDecision = dao.requestCancellation(taskId.toString(), nowMs)

    suspend fun recoverClaims(
        sessionToken: String,
        nowMs: Long,
        localOwnerIsLive: (taskId: UUID, claimToken: String) -> Boolean,
    ): List<DataTaskRecoveryCandidate> = dao.recoverClaims(
        sessionToken = sessionToken,
        nowMs = nowMs,
        localOwnerIsLive = { taskId, claimToken ->
            localOwnerIsLive(UUID.fromString(taskId), claimToken)
        },
    )

    suspend fun readyOutputsForShare(
        taskId: UUID,
        nowMs: Long,
    ): List<DataTaskOutputSnapshot> = dao.readyOutputsForShare(taskId.toString(), nowMs)

    suspend fun expiredReadyOutputs(nowMs: Long): List<DataTaskOutputSnapshot> =
        dao.expiredReadyOutputs(nowMs)

    suspend fun markReadyTaskExpiredAfterCleanup(
        taskId: UUID,
        outputIds: List<UUID>,
        nowMs: Long,
    ): Boolean = dao.markReadyTaskExpiredAfterCleanup(
        taskId = taskId.toString(),
        outputIds = outputIds.map(UUID::toString),
        nowMs = nowMs,
    )

    suspend fun acknowledgeTerminalTask(
        taskId: UUID,
        nowMs: Long,
    ): DataTaskSnapshot? = dao.acknowledgeTerminalTask(taskId.toString(), nowMs)

    suspend fun hasRunnableTasks(): Boolean = dao.hasRunnableTasks()

    suspend fun finishDrainIfQueueEmpty(onQueueEmpty: () -> Unit): Boolean =
        dao.finishDrainIfQueueEmpty(onQueueEmpty)

    private fun String.toTargetKey(): String = "package:$this"

    private fun UUID.stagingIdentity(): String = "stage-$this"

    private fun String.toItem(taskId: UUID, displayLabel: String?): NewDataTaskItem =
        NewDataTaskItem(
            ordinal = 0,
            packageName = this,
            displayLabel = displayLabel,
            deterministicStagingIdentity = "item-$taskId-0",
        )

    private fun String.toOpaqueGrantIdentity(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
        return "tree_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        const val PAYLOAD_SCHEMA_VERSION = 1
    }
}
