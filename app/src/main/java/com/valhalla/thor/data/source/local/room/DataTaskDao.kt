// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskInterruption
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemState
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.MAX_TASK_WARNING_COUNT
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.model.StoredDataDestination
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.model.StoredRestoreSource
import com.valhalla.thor.domain.model.isPrivateRestoreSourceRelativePath
import com.valhalla.thor.domain.model.requireTaskPresentationArguments
import com.valhalla.thor.domain.model.toUserFacingJobMessage
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

@Dao
abstract class DataTaskDao {

    @Transaction
    open suspend fun insertTask(request: NewDataTaskRow): DataTaskSnapshot {
        validateNewTask(request)
        val queueSequence = nextQueueSequence()
        val task = DataTaskEntity(
            taskId = request.taskId,
            queueSequence = queueSequence,
            payloadSchemaVersion = request.payloadSchemaVersion,
            kind = request.kind.name,
            state = request.initialState.name,
            targetKey = request.targetKey,
            serviceSessionToken = null,
            claimToken = null,
            claimLeaseExpiresAtEpochMs = null,
            attemptCount = 0,
            cancelRequestedAtEpochMs = null,
            stage = null,
            interruption = DataTaskInterruption.NONE.name,
            completed = 0,
            total = request.items.size.toLong(),
            resultCode = null,
            createdAtEpochMs = request.createdAtEpochMs,
            claimedAtEpochMs = null,
            startedAtEpochMs = null,
            updatedAtEpochMs = request.createdAtEpochMs,
            terminalAtEpochMs = null,
            retainUntilEpochMs = null,
            acknowledgedAtEpochMs = null,
        )
        insertTaskEntity(task)
        insertDetail(request.taskId, request.kind, request.detail)
        insertItemEntities(request.items.map { item ->
            DataTaskItemEntity(
                taskId = request.taskId,
                ordinal = item.ordinal,
                packageName = item.packageName,
                displayLabel = item.displayLabel,
                state = DataTaskItemState.PENDING.name,
                claimToken = null,
                claimLeaseExpiresAtEpochMs = null,
                attemptCount = 0,
                resultCode = null,
                deterministicStagingIdentity = item.deterministicStagingIdentity,
                startedAtEpochMs = null,
                finishedAtEpochMs = null,
            )
        })
        return requireNotNull(loadSnapshot(request.taskId))
    }

    @Transaction
    open suspend fun loadTask(taskId: String): DataTaskSnapshot? {
        requireCanonicalUuid(taskId, "taskId")
        return loadSnapshot(taskId)
    }

    open fun observeTask(taskId: String): Flow<DataTaskSnapshot?> {
        requireCanonicalUuid(taskId, "taskId")
        return observeTaskAggregate(taskId).map { loadTask(taskId) }
    }

    fun observeActiveTaskId(kind: DataTaskKind, targetKey: String): Flow<UUID?> =
        observeActiveTaskIdRow(kind.name, targetKey).map { taskId ->
            taskId?.let(UUID::fromString)
        }

    suspend fun compareAndSetStartBlocked(
        taskId: String,
        expectedState: DataTaskState,
        blockedState: DataTaskState,
        nowMs: Long,
    ): Boolean {
        requireCanonicalUuid(taskId, "taskId")
        require(expectedState == DataTaskState.QUEUED || expectedState == DataTaskState.STAGING_SOURCE) {
            "expectedState must be QUEUED or STAGING_SOURCE"
        }
        require(
            blockedState == DataTaskState.START_BLOCKED ||
                    blockedState == DataTaskState.START_BLOCKED_NOTIFICATION
        ) {
            "blockedState must be START_BLOCKED or START_BLOCKED_NOTIFICATION"
        }
        return compareAndSetStartBlockedRow(
            taskId = taskId,
            expectedState = expectedState.name,
            blockedState = blockedState.name,
            nowMs = nowMs,
        ) == 1
    }

    /** Blocks the explicit wake, or the oldest actionable row for a sticky null-intent restart. */
    @Transaction
    open suspend fun blockCurrentStart(
        taskId: String?,
        blockedState: DataTaskState,
        nowMs: Long,
    ): DataTaskSnapshot? {
        require(
            blockedState == DataTaskState.START_BLOCKED ||
                    blockedState == DataTaskState.START_BLOCKED_NOTIFICATION
        ) {
            "blockedState must be START_BLOCKED or START_BLOCKED_NOTIFICATION"
        }
        taskId?.let { requireCanonicalUuid(it, "taskId") }
        val task = if (taskId == null) {
            findOldestStartableTask()
        } else {
            loadTaskEntity(taskId)
        } ?: return null
        if (
            task.state != DataTaskState.QUEUED.name &&
            task.state != DataTaskState.STAGING_SOURCE.name &&
            task.state != DataTaskState.RUNNING.name
        ) {
            return null
        }
        clearItemClaimsForRecovery(task.taskId, DataTaskItemState.PENDING.name)
        check(
            blockCurrentStartRow(
                taskId = task.taskId,
                expectedState = task.state,
                blockedState = blockedState.name,
                nowMs = nowMs,
            ) == 1
        ) { "actionable data task changed during start blocking transaction" }
        return loadSnapshot(task.taskId)
    }

    @Transaction
    open suspend fun acknowledgeTerminalTask(
        taskId: String,
        nowMs: Long,
    ): DataTaskSnapshot? {
        requireCanonicalUuid(taskId, "taskId")
        val task = loadTaskEntity(taskId) ?: return null
        if (DataTaskState.valueOf(task.state) !in ACKNOWLEDGEABLE_STATES) return null
        if (acknowledgeTerminalTaskRow(taskId, nowMs) != 1) return null
        return loadSnapshot(taskId)
    }

    @Transaction
    open suspend fun claimOldestRunnableTask(
        sessionToken: String,
        claimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): ClaimedDataTask? {
        require(sessionToken.isNotBlank()) { "sessionToken must not be blank" }
        require(claimToken.isNotBlank()) { "claimToken must not be blank" }
        require(leaseUntilMs > nowMs) { "leaseUntilMs must be after nowMs" }
        while (true) {
            val candidate = findOldestRunnableTask() ?: return null
            if (claimTaskRow(
                    candidate.taskId,
                    sessionToken,
                    claimToken,
                    nowMs,
                    leaseUntilMs
                ) == 1
            ) {
                return requireNotNull(loadClaimedTask(candidate.taskId))
            }
        }
    }

    @Transaction
    open suspend fun claimNextPendingItem(
        taskId: String,
        taskClaimToken: String,
        itemClaimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): ClaimedDataTaskItem? {
        requireCanonicalUuid(taskId, "taskId")
        require(taskClaimToken.isNotBlank()) { "taskClaimToken must not be blank" }
        require(itemClaimToken.isNotBlank()) { "itemClaimToken must not be blank" }
        require(leaseUntilMs > nowMs) { "leaseUntilMs must be after nowMs" }
        while (true) {
            val candidate = findNextPendingItem(taskId, taskClaimToken, nowMs) ?: return null
            if (
                claimItemRow(
                    taskId,
                    candidate.ordinal,
                    taskClaimToken,
                    itemClaimToken,
                    nowMs,
                    leaseUntilMs,
                ) == 1
            ) {
                return requireNotNull(loadClaimedItem(taskId, candidate.ordinal))
            }
        }
    }

    /**
     * Acquires task and item ownership in one transaction. Cancellation cannot observe the task-only
     * intermediate state; an exception or coroutine timeout rolls both claims back together.
     */
    @Transaction
    open suspend fun claimOldestRunnableWork(
        sessionToken: String,
        taskClaimToken: String,
        itemClaimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
        afterTaskClaimed: suspend () -> Unit = {},
    ): ClaimedDataTaskWork? {
        require(sessionToken.isNotBlank()) { "sessionToken must not be blank" }
        require(taskClaimToken.isNotBlank()) { "taskClaimToken must not be blank" }
        require(itemClaimToken.isNotBlank()) { "itemClaimToken must not be blank" }
        require(leaseUntilMs > nowMs) { "leaseUntilMs must be after nowMs" }
        while (true) {
            val candidate = findOldestRunnableTask() ?: return null
            if (
                claimTaskRow(
                    candidate.taskId,
                    sessionToken,
                    taskClaimToken,
                    nowMs,
                    leaseUntilMs,
                ) != 1
            ) {
                continue
            }
            afterTaskClaimed()
            val item = findNextPendingItem(candidate.taskId, taskClaimToken, nowMs)
            if (item == null) {
                check(finishClaimedTaskIfDrained(candidate.taskId, taskClaimToken, nowMs)) {
                    "claimed data task has unfinished but unclaimable items"
                }
                return null
            }
            check(
                claimItemRow(
                    candidate.taskId,
                    item.ordinal,
                    taskClaimToken,
                    itemClaimToken,
                    nowMs,
                    leaseUntilMs,
                ) == 1
            ) { "pending data task item changed inside claim transaction" }
            return ClaimedDataTaskWork(
                task = requireNotNull(loadClaimedTask(candidate.taskId)),
                item = requireNotNull(loadClaimedItem(candidate.taskId, item.ordinal)),
            )
        }
    }

    /** Releases an unknown claim-commit result while the provisional token is still registered. */
    @Transaction
    open suspend fun settleClaimAcquisitionFailure(
        taskClaimToken: String,
        nowMs: Long,
    ): Boolean {
        require(taskClaimToken.isNotBlank()) { "taskClaimToken must not be blank" }
        val task = loadClaimedTaskByToken(taskClaimToken) ?: return false
        val activeItem = loadRunningItem(task.taskId)
        if (activeItem?.claimToken != null) {
            return settleClaimTimeout(
                taskId = task.taskId,
                taskClaimToken = taskClaimToken,
                itemOrdinal = activeItem.ordinal,
                itemClaimToken = activeItem.claimToken,
                nowMs = nowMs,
            )
        }
        if (task.state == DataTaskState.CANCEL_REQUESTED.name) {
            cancelUnfinishedItems(task.taskId, RESULT_CANCELLED, nowMs)
            return settleRecoveredCancellationRow(
                task.taskId,
                taskClaimToken,
                nowMs,
                RESULT_CANCELLED,
            ) == 1
        }
        val archive = if (task.kind == DataTaskKind.ARCHIVE_RESTORE.name) {
            loadArchiveDetail(task.taskId)
        } else {
            null
        }
        return pauseTaskRow(
            taskId = task.taskId,
            taskClaimToken = taskClaimToken,
            state = if (archive?.destructiveStarted == true) {
                DataTaskState.INTERRUPTED_REVIEW.name
            } else {
                DataTaskState.QUEUED.name
            },
            interruption = if (archive?.destructiveStarted == true) {
                DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW.name
            } else {
                DataTaskInterruption.SERVICE_TIMEOUT.name
            },
            resultCode = RESULT_SERVICE_TIMEOUT,
            nowMs = nowMs,
        ) == 1
    }

    @Transaction
    open suspend fun commitPrivateRestoreSource(
        taskId: String,
        taskClaimToken: String,
        itemOrdinal: Int,
        itemClaimToken: String,
        privateRelativePath: String,
        nowMs: Long,
        transactionNowMs: () -> Long = { nowMs },
    ): Boolean {
        val parsedTaskId = requireCanonicalUuid(taskId, "taskId")
        StoredRestoreSource.PrivateCopy(privateRelativePath)
        require(isPrivateRestoreSourceRelativePath(parsedTaskId, privateRelativePath)) {
            "private restore source path must belong to taskId"
        }
        val task = loadTaskEntity(taskId) ?: return false
        val item = loadItemEntity(taskId, itemOrdinal) ?: return false
        if (task.kind != DataTaskKind.ARCHIVE_RESTORE.name) return false
        val detail = loadArchiveDetail(taskId) ?: return false
        if (detail.restoreSourceKind != RESTORE_SOURCE_AWAITING_GRANT) return false
        val transactionNow = transactionNowMs()
        if (
            !task.ownsClaim(taskClaimToken, transactionNow) ||
            !item.ownsClaim(itemClaimToken, transactionNow)
        ) {
            return false
        }
        if (commitPrivateRestoreSourceRow(taskId, privateRelativePath) != 1) return false
        check(touchClaimedTaskRow(taskId, taskClaimToken, transactionNow) == 1)
        return true
    }

    @Transaction
    open suspend fun hasClaimTokens(
        taskClaimToken: String,
        itemClaimToken: String?,
    ): Boolean {
        require(taskClaimToken.isNotBlank()) { "taskClaimToken must not be blank" }
        require(itemClaimToken == null || itemClaimToken.isNotBlank()) {
            "itemClaimToken must not be blank"
        }
        return hasTaskClaimToken(taskClaimToken) ||
                (itemClaimToken != null && hasItemClaimToken(itemClaimToken))
    }

    open suspend fun uncommittedRestoreSourceCleanupTaskIds(): List<UUID> =
        loadUncommittedRestoreSourceCleanupTaskIds().map(UUID::fromString)

    suspend fun markClaimInterrupted(
        taskId: String,
        taskClaimToken: String,
        interruption: DataTaskInterruption,
        resultCode: DataTaskResultCode,
        nowMs: Long,
    ): Boolean {
        requireCanonicalUuid(taskId, "taskId")
        require(interruption != DataTaskInterruption.NONE) { "interruption must be typed" }
        return markClaimInterruptedRow(
            taskId = taskId,
            taskClaimToken = taskClaimToken,
            interruption = interruption.name,
            resultCode = resultCode.value,
            nowMs = nowMs,
        ) == 1
    }

    /**
     * Atomically fences timeout against normal settlement and derives recovery from current rows.
     */
    @Transaction
    open suspend fun settleClaimTimeout(
        taskId: String,
        taskClaimToken: String,
        itemOrdinal: Int,
        itemClaimToken: String,
        nowMs: Long,
    ): Boolean {
        requireCanonicalUuid(taskId, "taskId")
        val task = loadTaskEntity(taskId) ?: return false
        val item = loadItemEntity(taskId, itemOrdinal) ?: return false
        if (
            task.state !in TIMEOUT_SETTLEABLE_STATES ||
            task.claimToken != taskClaimToken ||
            item.state != DataTaskItemState.RUNNING.name ||
            item.claimToken != itemClaimToken
        ) {
            return false
        }

        val kind = DataTaskKind.valueOf(task.kind)
        val archive = if (kind == DataTaskKind.ARCHIVE_RESTORE) {
            loadArchiveDetail(taskId) ?: return false
        } else {
            null
        }
        val destructiveRestore = kind == DataTaskKind.ARCHIVE_RESTORE &&
                archive?.destructiveStarted == true
        if (task.state == DataTaskState.CANCEL_REQUESTED.name && !destructiveRestore) {
            return terminateClaimedTask(
                taskId,
                taskClaimToken,
                itemOrdinal,
                itemClaimToken,
                DataTaskState.CANCELLED,
                DataTaskItemState.CANCELLED,
                DataTaskResultCode(RESULT_CANCELLED),
                nowMs,
            )
        }

        val timeoutCode = DataTaskResultCode(RESULT_SERVICE_TIMEOUT)
        return when (kind) {
            DataTaskKind.ARCHIVE_BACKUP -> pauseClaimedTask(
                taskId,
                taskClaimToken,
                itemOrdinal,
                itemClaimToken,
                DataTaskState.WAITING_FOR_AUTH,
                DataTaskInterruption.AUTHENTICATION_REQUIRED,
                timeoutCode,
                nowMs,
                keepItemRunning = false,
            )

            DataTaskKind.ARCHIVE_RESTORE -> when {
                destructiveRestore -> pauseClaimedTask(
                    taskId,
                    taskClaimToken,
                    itemOrdinal,
                    itemClaimToken,
                    DataTaskState.INTERRUPTED_REVIEW,
                    DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW,
                    timeoutCode,
                    nowMs,
                    keepItemRunning = true,
                )

                archive?.restoreSourceKind == RESTORE_SOURCE_AWAITING_GRANT -> pauseClaimedTask(
                    taskId,
                    taskClaimToken,
                    itemOrdinal,
                    itemClaimToken,
                    DataTaskState.WAITING_FOR_SOURCE,
                    DataTaskInterruption.SOURCE_REQUIRED,
                    timeoutCode,
                    nowMs,
                    keepItemRunning = false,
                )

                else -> pauseClaimedTask(
                    taskId,
                    taskClaimToken,
                    itemOrdinal,
                    itemClaimToken,
                    DataTaskState.WAITING_FOR_AUTH,
                    DataTaskInterruption.AUTHENTICATION_REQUIRED,
                    timeoutCode,
                    nowMs,
                    keepItemRunning = false,
                )
            }

            DataTaskKind.APP_EXPORT,
            DataTaskKind.SHARE_PREPARE,
                -> pauseClaimedTask(
                taskId,
                taskClaimToken,
                itemOrdinal,
                itemClaimToken,
                DataTaskState.QUEUED,
                DataTaskInterruption.SERVICE_TIMEOUT,
                timeoutCode,
                nowMs,
                keepItemRunning = false,
            )
        }
    }

    @Transaction
    open suspend fun checkpointClaimedTask(
        taskId: String,
        taskClaimToken: String,
        itemOrdinal: Int,
        itemClaimToken: String,
        checkpoint: DataTaskCheckpoint,
        leaseUntilMs: Long,
        transactionNowMs: () -> Long = System::currentTimeMillis,
    ): Boolean {
        requireCanonicalUuid(taskId, "taskId")
        require(leaseUntilMs > checkpoint.recordedAtEpochMs) {
            "leaseUntilMs must be after the checkpoint"
        }
        require(checkpoint.activeItemOrdinal == null || checkpoint.activeItemOrdinal == itemOrdinal) {
            "checkpoint active item must match itemOrdinal"
        }
        val task = loadTaskEntity(taskId) ?: return false
        val item = loadItemEntity(taskId, itemOrdinal) ?: return false
        val kind = DataTaskKind.valueOf(task.kind)
        if (checkpoint.destructiveStarted || checkpoint.restoreMutationBreadcrumb != null) {
            require(kind == DataTaskKind.ARCHIVE_RESTORE) {
                "destructive restore state belongs only to archive restore tasks"
            }
        }
        val authoritativeNowMs = transactionNowMs()
        require(authoritativeNowMs >= 0) { "transaction time must be non-negative" }
        if (leaseUntilMs <= authoritativeNowMs ||
            !task.ownsClaim(taskClaimToken, authoritativeNowMs) ||
            !item.ownsClaim(itemClaimToken, authoritativeNowMs)
        ) {
            return false
        }
        check(
            checkpointTaskRow(
                taskId = taskId,
                taskClaimToken = taskClaimToken,
                stage = checkpoint.stage.name,
                completed = checkpoint.completed,
                total = checkpoint.total,
                recordedAtEpochMs = checkpoint.recordedAtEpochMs,
                ownershipCheckedAtEpochMs = authoritativeNowMs,
                leaseUntilMs = leaseUntilMs,
            ) == 1
        ) { "task claim changed inside checkpoint transaction" }
        check(
            renewItemClaimRow(
                taskId,
                itemOrdinal,
                itemClaimToken,
                authoritativeNowMs,
                leaseUntilMs,
            ) == 1
        ) { "item claim changed inside checkpoint transaction" }
        if (checkpoint.destructiveStarted || checkpoint.restoreMutationBreadcrumb != null) {
            val breadcrumb = checkpoint.restoreMutationBreadcrumb
            check(
                updateRestoreCheckpoint(
                    taskId = taskId,
                    destructiveStarted = checkpoint.destructiveStarted,
                    mutationPackageName = breadcrumb?.packageName,
                    mutationAppLabel = breadcrumb?.appLabel,
                    mutationStartedAtEpochMs = breadcrumb?.startedAtEpochMs,
                ) == 1
            ) { "restore detail changed inside checkpoint transaction" }
        }
        return true
    }

    @Transaction
    open suspend fun completeClaimedItem(
        taskId: String,
        ordinal: Int,
        taskClaimToken: String,
        itemClaimToken: String,
        result: DataTaskItemResult,
    ): Boolean {
        requireCanonicalUuid(taskId, "taskId")
        val task = loadTaskEntity(taskId) ?: return false
        val item = loadItemEntity(taskId, ordinal) ?: return false
        if (task.state != DataTaskState.RUNNING.name ||
            task.claimToken != taskClaimToken ||
            item.state != DataTaskItemState.RUNNING.name ||
            item.claimToken != itemClaimToken
        ) {
            return false
        }
        validateOutputs(result)
        val outputEntities = result.outputs.map { output ->
            DataTaskOutputEntity(
                outputId = output.outputId.toString(),
                taskId = taskId,
                itemOrdinal = ordinal,
                privateRelativePath = output.privateRelativePath,
                displayName = output.displayName,
                mimeType = output.mimeType,
                byteSize = output.byteSize,
                state = output.state.name,
                expiresAtEpochMs = output.expiresAtEpochMs,
            )
        }
        if (outputEntities.isNotEmpty()) insertOutputEntities(outputEntities)
        if (
            completeItemRow(
                taskId,
                ordinal,
                taskClaimToken,
                itemClaimToken,
                result.terminalState.toItemState().name,
                result.toStoredResult(),
                result.finishedAtEpochMs,
            ) != 1
        ) {
            return false
        }
        refreshTaskProgress(taskId, taskClaimToken, result.finishedAtEpochMs)
        return true
    }

    @Transaction
    open suspend fun settleClaimedTask(
        taskId: String,
        taskClaimToken: String,
        itemOrdinal: Int,
        itemClaimToken: String,
        outcome: DataTaskRunOutcome,
        nowMs: Long,
    ): Boolean {
        val task = loadTaskEntity(taskId) ?: return false
        val item = loadItemEntity(taskId, itemOrdinal) ?: return false
        if (
            task.claimToken != taskClaimToken ||
            item.state != DataTaskItemState.RUNNING.name ||
            item.claimToken != itemClaimToken ||
            task.state !in TIMEOUT_SETTLEABLE_STATES
        ) {
            return false
        }
        val kind = DataTaskKind.valueOf(task.kind)
        val restoreDetail = if (kind == DataTaskKind.ARCHIVE_RESTORE) {
            loadArchiveDetail(taskId)
        } else {
            null
        }
        val cancellationRequested = task.state == DataTaskState.CANCEL_REQUESTED.name
        if (cancellationRequested) {
            if (
                kind == DataTaskKind.ARCHIVE_RESTORE &&
                restoreDetail?.destructiveStarted != false
            ) {
                val review = outcome as? DataTaskRunOutcome.InterruptedReview
                review?.breadcrumb?.let { breadcrumb ->
                    updateRestoreCheckpoint(
                        taskId = taskId,
                        destructiveStarted = true,
                        mutationPackageName = breadcrumb.packageName,
                        mutationAppLabel = breadcrumb.appLabel,
                        mutationStartedAtEpochMs = breadcrumb.startedAtEpochMs,
                    )
                }
                val breadcrumb = review?.breadcrumb ?: restoreDetail?.toBreadcrumb()
                return pauseClaimedTask(
                    taskId,
                    taskClaimToken,
                    itemOrdinal,
                    itemClaimToken,
                    DataTaskState.INTERRUPTED_REVIEW,
                    DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW,
                    review?.resultCode ?: DataTaskResultCode(
                        if (breadcrumb == null) {
                            RESULT_RECOVERY_BREADCRUMB_MISSING
                        } else {
                            RESULT_DESTRUCTIVE_RESTORE_REVIEW
                        }
                    ),
                    nowMs,
                    keepItemRunning = true,
                )
            }
            return terminateClaimedTask(
                taskId,
                taskClaimToken,
                itemOrdinal,
                itemClaimToken,
                DataTaskState.CANCELLED,
                DataTaskItemState.CANCELLED,
                DataTaskResultCode(RESULT_CANCELLED),
                nowMs,
            )
        }
        if (outcome is DataTaskRunOutcome.OwnershipLost) return false
        val terminalFailureOrCancellation = when (outcome) {
            is DataTaskRunOutcome.TaskFailed,
            DataTaskRunOutcome.Cancelled,
                -> true

            is DataTaskRunOutcome.ItemCompleted ->
                outcome.result.terminalState != DataTaskItemTerminalState.SUCCEEDED

            else -> false
        }
        if (
            kind == DataTaskKind.ARCHIVE_RESTORE &&
            terminalFailureOrCancellation &&
            restoreDetail?.destructiveStarted != false &&
            outcome !is DataTaskRunOutcome.InterruptedReview
        ) {
            return pauseClaimedTask(
                taskId,
                taskClaimToken,
                itemOrdinal,
                itemClaimToken,
                DataTaskState.INTERRUPTED_REVIEW,
                DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW,
                DataTaskResultCode(RESULT_DESTRUCTIVE_RESTORE_REVIEW),
                nowMs,
                keepItemRunning = true,
            )
        }

        return when (outcome) {
            is DataTaskRunOutcome.ItemCompleted ->
                completeClaimedItem(
                    taskId,
                    itemOrdinal,
                    taskClaimToken,
                    itemClaimToken,
                    outcome.result,
                ) && (
                        countUnfinishedItems(taskId) != 0 ||
                                finishClaimedTaskIfDrained(taskId, taskClaimToken, nowMs)
                        )

            is DataTaskRunOutcome.WaitingForAuthentication -> pauseClaimedTask(
                taskId,
                taskClaimToken,
                itemOrdinal,
                itemClaimToken,
                DataTaskState.WAITING_FOR_AUTH,
                DataTaskInterruption.AUTHENTICATION_REQUIRED,
                outcome.resultCode,
                nowMs,
                keepItemRunning = false,
            )

            is DataTaskRunOutcome.WaitingForSource -> pauseClaimedTask(
                taskId,
                taskClaimToken,
                itemOrdinal,
                itemClaimToken,
                DataTaskState.WAITING_FOR_SOURCE,
                DataTaskInterruption.SOURCE_REQUIRED,
                outcome.resultCode,
                nowMs,
                keepItemRunning = false,
            )

            is DataTaskRunOutcome.InterruptedReview -> {
                require(kind == DataTaskKind.ARCHIVE_RESTORE) {
                    "interrupted review belongs only to archive restore tasks"
                }
                outcome.breadcrumb?.let { breadcrumb ->
                    updateRestoreCheckpoint(
                        taskId = taskId,
                        destructiveStarted = true,
                        mutationPackageName = breadcrumb.packageName,
                        mutationAppLabel = breadcrumb.appLabel,
                        mutationStartedAtEpochMs = breadcrumb.startedAtEpochMs,
                    )
                }
                pauseClaimedTask(
                    taskId,
                    taskClaimToken,
                    itemOrdinal,
                    itemClaimToken,
                    DataTaskState.INTERRUPTED_REVIEW,
                    DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW,
                    outcome.resultCode,
                    nowMs,
                    keepItemRunning = true,
                )
            }

            is DataTaskRunOutcome.TaskFailed -> terminateClaimedTask(
                taskId,
                taskClaimToken,
                itemOrdinal,
                itemClaimToken,
                DataTaskState.FAILED,
                DataTaskItemState.FAILED,
                outcome.resultCode,
                nowMs,
                failureReason = outcome.arguments.firstOrNull()
                    ?: outcome.resultCode.toUserFacingJobMessage(),
            )

            DataTaskRunOutcome.Cancelled -> terminateClaimedTask(
                taskId,
                taskClaimToken,
                itemOrdinal,
                itemClaimToken,
                DataTaskState.CANCELLED,
                DataTaskItemState.CANCELLED,
                DataTaskResultCode(RESULT_CANCELLED),
                nowMs,
            )

            DataTaskRunOutcome.OwnershipLost -> false
        }
    }

    @Transaction
    open suspend fun requestCancellation(
        taskId: String,
        nowMs: Long,
    ): DataTaskCancellationDecision {
        requireCanonicalUuid(taskId, "taskId")
        val task = loadTaskEntity(taskId) ?: return DataTaskCancellationDecision.NotFound
        if (DataTaskState.valueOf(task.state).isTerminalOrReady()) {
            return DataTaskCancellationDecision.AlreadyTerminal(requireNotNull(loadSnapshot(taskId)))
        }
        val activeItem = loadRunningItem(taskId)
        if (task.claimToken != null || activeItem?.claimToken != null) {
            requestTaskCancellationRow(taskId, task.state, nowMs)
            cancelPendingItems(taskId, RESULT_CANCELLED, nowMs)
            return DataTaskCancellationDecision.InterruptActive(
                snapshot = requireNotNull(loadSnapshot(taskId)),
                activeItemOrdinal = activeItem?.ordinal,
                taskClaimToken = task.claimToken,
                itemClaimToken = activeItem?.claimToken,
            )
        }
        cancelUnfinishedItems(taskId, RESULT_CANCELLED, nowMs)
        settleUnclaimedCancellationRow(taskId, task.state, nowMs, RESULT_CANCELLED)
        return DataTaskCancellationDecision.Settled(requireNotNull(loadSnapshot(taskId)))
    }

    @Transaction
    open suspend fun recoverClaims(
        sessionToken: String,
        nowMs: Long,
        localOwnerIsLive: (taskId: String, claimToken: String) -> Boolean,
    ): List<DataTaskRecoveryCandidate> {
        require(sessionToken.isNotBlank()) { "sessionToken must not be blank" }
        return loadRecoverableTasks().mapNotNull { task ->
            val previousSession = task.serviceSessionToken ?: return@mapNotNull null
            val previousClaim = task.claimToken ?: return@mapNotNull null
            if (localOwnerIsLive(task.taskId, previousClaim)) return@mapNotNull null
            if (
                previousSession == sessionToken &&
                task.claimLeaseExpiresAtEpochMs?.let { it <= nowMs } != true
            ) {
                return@mapNotNull null
            }
            val activeItem = loadRunningItem(task.taskId)
            val archive = loadArchiveDetail(task.taskId)
            val breadcrumb = archive?.toBreadcrumb()
            val destructiveStarted = archive?.destructiveStarted == true
            val kind = DataTaskKind.valueOf(task.kind)
            if (
                task.state == DataTaskState.CANCEL_REQUESTED.name &&
                !(kind == DataTaskKind.ARCHIVE_RESTORE && destructiveStarted)
            ) {
                cancelUnfinishedItems(task.taskId, RESULT_CANCELLED, nowMs)
                settleRecoveredCancellationRow(
                    task.taskId,
                    previousClaim,
                    nowMs,
                    RESULT_CANCELLED,
                )
                return@mapNotNull null
            }
            val recovery = when {
                task.state == DataTaskState.WAITING_FOR_AUTH.name ->
                    DataTaskRecovery.WaitingForAuthentication

                task.state == DataTaskState.WAITING_FOR_SOURCE.name ->
                    DataTaskRecovery.WaitingForSource

                task.state == DataTaskState.INTERRUPTED_REVIEW.name || destructiveStarted ->
                    if (kind == DataTaskKind.ARCHIVE_RESTORE) {
                        DataTaskRecovery.InterruptedReview(
                            breadcrumb = breadcrumb,
                            resultCode = DataTaskResultCode(
                                if (breadcrumb == null) {
                                    RESULT_RECOVERY_BREADCRUMB_MISSING
                                } else {
                                    RESULT_DESTRUCTIVE_RESTORE_REVIEW
                                }
                            ),
                        )
                    } else {
                        DataTaskRecovery.Failed(
                            DataTaskResultCode(RESULT_RECOVERY_BREADCRUMB_MISSING)
                        )
                    }

                kind == DataTaskKind.ARCHIVE_RESTORE &&
                        archive?.restoreSourceKind == RESTORE_SOURCE_AWAITING_GRANT ->
                    DataTaskRecovery.WaitingForSource

                kind == DataTaskKind.ARCHIVE_BACKUP || kind == DataTaskKind.ARCHIVE_RESTORE ->
                    DataTaskRecovery.WaitingForAuthentication

                else -> DataTaskRecovery.Resume
            }
            when (recovery) {
                DataTaskRecovery.Resume -> {
                    clearTaskClaimForRecovery(
                        task.taskId,
                        previousClaim,
                        DataTaskState.QUEUED.name,
                        nowMs
                    )
                    clearItemClaimsForRecovery(task.taskId, DataTaskItemState.PENDING.name)
                }

                DataTaskRecovery.WaitingForAuthentication -> {
                    pauseTaskForRecovery(
                        task.taskId,
                        previousClaim,
                        DataTaskState.WAITING_FOR_AUTH.name,
                        DataTaskInterruption.AUTHENTICATION_REQUIRED.name,
                        RESULT_RECOVERY_AUTHENTICATION_REQUIRED,
                        nowMs,
                    )
                    clearItemClaimsForRecovery(task.taskId, DataTaskItemState.PENDING.name)
                }

                DataTaskRecovery.WaitingForSource -> {
                    pauseTaskForRecovery(
                        task.taskId,
                        previousClaim,
                        DataTaskState.WAITING_FOR_SOURCE.name,
                        DataTaskInterruption.SOURCE_REQUIRED.name,
                        RESULT_RECOVERY_SOURCE_REQUIRED,
                        nowMs,
                    )
                    clearItemClaimsForRecovery(task.taskId, DataTaskItemState.PENDING.name)
                }

                is DataTaskRecovery.InterruptedReview -> {
                    pauseTaskForRecovery(
                        task.taskId,
                        previousClaim,
                        DataTaskState.INTERRUPTED_REVIEW.name,
                        DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW.name,
                        recovery.resultCode.value,
                        nowMs,
                    )
                    clearItemClaimTokens(task.taskId)
                }

                is DataTaskRecovery.Failed -> {
                    failItemClaimsForRecovery(
                        task.taskId,
                        recovery.resultCode.value,
                        nowMs,
                    )
                    cancelPendingItems(task.taskId, RESULT_CANCELLED, nowMs)
                    failTaskForRecovery(
                        task.taskId,
                        previousClaim,
                        recovery.resultCode.value,
                        nowMs
                    )
                }
            }
            DataTaskRecoveryCandidate(
                taskId = UUID.fromString(task.taskId),
                kind = DataTaskKind.valueOf(task.kind),
                interruptedItemOrdinal = activeItem?.ordinal,
                previousServiceSessionToken = previousSession,
                destructiveStarted = destructiveStarted,
                restoreMutationBreadcrumb = breadcrumb,
                recovery = recovery,
            )
        }
    }

    @Transaction
    open suspend fun finishClaimedTaskIfDrained(
        taskId: String,
        claimToken: String,
        nowMs: Long,
    ): Boolean {
        val task = loadTaskEntity(taskId) ?: return false
        if (task.claimToken != claimToken || task.state != DataTaskState.RUNNING.name) return false
        if (countUnfinishedItems(taskId) != 0) return false
        val succeeded = countItemsInState(taskId, DataTaskItemState.SUCCEEDED.name)
        val failed = countItemsInState(taskId, DataTaskItemState.FAILED.name)
        val cancelled = countItemsInState(taskId, DataTaskItemState.CANCELLED.name)
        val readyOutputs = countOutputsInState(taskId, DataTaskOutputState.READY.name)
        val state = when {
            readyOutputs > 0 && (failed > 0 || cancelled > 0) -> DataTaskState.READY_PARTIAL
            readyOutputs > 0 -> DataTaskState.READY
            failed == 0 && cancelled == 0 -> DataTaskState.SUCCEEDED
            succeeded > 0 -> DataTaskState.PARTIAL
            failed > 0 -> DataTaskState.FAILED
            else -> DataTaskState.CANCELLED
        }
        val itemPresentations = loadItemEntities(taskId).map { item ->
            item.state to decodeStoredResult(item.resultCode)
        }
        val explicitWarnings = itemPresentations.flatMap { it.second.warnings }
        val failedReasons = itemPresentations.mapNotNull { (itemState, presentation) ->
            if (itemState == DataTaskItemState.FAILED.name) {
                presentation.failureReason
                    ?: presentation.resultCode?.toUserFacingJobMessage()
            } else {
                null
            }
        }
        val warnings = when (state) {
            DataTaskState.READY_PARTIAL, DataTaskState.PARTIAL ->
                (explicitWarnings + failedReasons).take(MAX_TASK_WARNING_COUNT)

            else -> explicitWarnings.take(MAX_TASK_WARNING_COUNT)
        }
        val failureReason = if (state == DataTaskState.FAILED) failedReasons.firstOrNull() else null
        val resultCode = itemPresentations.firstNotNullOfOrNull { it.second.resultCode }
        return finishTaskRow(
            taskId = taskId,
            claimToken = claimToken,
            state = state.name,
            resultCode = encodeStoredResult(resultCode, warnings, failureReason),
            nowMs = nowMs,
        ) == 1
    }

    @Transaction
    open suspend fun readyOutputsForShare(
        taskId: String,
        nowMs: Long,
    ): List<DataTaskOutputSnapshot> {
        requireCanonicalUuid(taskId, "taskId")
        return loadReadyOutputs(taskId, nowMs).map { it.toSnapshot() }
    }

    @Transaction
    open suspend fun expiredReadyOutputs(nowMs: Long): List<DataTaskOutputSnapshot> =
        loadExpiredReadyOutputs(nowMs).map { it.toSnapshot() }

    @Transaction
    open suspend fun markReadyTaskExpiredAfterCleanup(
        taskId: String,
        outputIds: List<String>,
        nowMs: Long,
    ): Boolean {
        requireCanonicalUuid(taskId, "taskId")
        if (outputIds.isEmpty()) return false
        outputIds.forEach { requireCanonicalUuid(it, "outputId") }
        require(outputIds.distinct().size == outputIds.size) { "outputIds must be unique" }
        if (countExpiredReadyOutputs(taskId, outputIds, nowMs) != outputIds.size) return false
        if (expireReadyOutputs(taskId, outputIds, nowMs) != outputIds.size) return false
        if (countOutputsInState(taskId, DataTaskOutputState.READY.name) != 0) return false
        return expireReadyTask(taskId, nowMs) == 1
    }

    @Transaction
    open suspend fun hasRunnableTasks(): Boolean = hasRunnableTasksQuery()

    /**
     * Serializes the final empty check and its stop decision with task insertion transactions.
     *
     * A task admitted first is visible to this check. If the queue is empty, a concurrent insert
     * cannot commit until [onQueueEmpty] has made the stop decision; that producer can then wake a
     * fresh drain after its insert commits.
     *
     * @return `true` only when the queue was empty and [onQueueEmpty] was invoked.
     */
    @Transaction
    open suspend fun finishDrainIfQueueEmpty(onQueueEmpty: () -> Unit): Boolean {
        if (hasRunnableTasksQuery()) return false
        onQueueEmpty()
        return true
    }

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM data_tasks
            WHERE state IN ('QUEUED', 'STAGING_SOURCE', 'RUNNING', 'CANCEL_REQUESTED')
        )
        """
    )
    protected abstract suspend fun hasRunnableTasksQuery(): Boolean

    @Insert
    protected abstract suspend fun insertTaskEntity(entity: DataTaskEntity)

    @Insert
    protected abstract suspend fun insertArchiveDetail(entity: ArchiveTaskDetailEntity)

    @Insert
    protected abstract suspend fun insertExportDetail(entity: ExportTaskDetailEntity)

    @Insert
    protected abstract suspend fun insertItemEntities(entities: List<DataTaskItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOutputEntities(entities: List<DataTaskOutputEntity>)

    @Query("SELECT COALESCE(MAX(queue_sequence), 0) + 1 FROM data_tasks")
    protected abstract suspend fun nextQueueSequence(): Long

    @Query("SELECT * FROM data_tasks WHERE task_id = :taskId")
    protected abstract suspend fun loadTaskEntity(taskId: String): DataTaskEntity?

    @Query("SELECT * FROM data_task_items WHERE task_id = :taskId ORDER BY ordinal")
    protected abstract suspend fun loadItemEntities(taskId: String): List<DataTaskItemEntity>

    @Query("SELECT * FROM data_task_items WHERE task_id = :taskId AND ordinal = :ordinal")
    protected abstract suspend fun loadItemEntity(taskId: String, ordinal: Int): DataTaskItemEntity?

    @Query("SELECT * FROM data_task_items WHERE task_id = :taskId AND state = 'RUNNING' ORDER BY ordinal LIMIT 1")
    protected abstract suspend fun loadRunningItem(taskId: String): DataTaskItemEntity?

    @Query("SELECT * FROM data_task_outputs WHERE task_id = :taskId ORDER BY item_ordinal, output_id")
    protected abstract suspend fun loadOutputEntities(taskId: String): List<DataTaskOutputEntity>

    @Query("SELECT * FROM archive_task_details WHERE task_id = :taskId")
    protected abstract suspend fun loadArchiveDetail(taskId: String): ArchiveTaskDetailEntity?

    @Query("SELECT * FROM export_task_details WHERE task_id = :taskId")
    protected abstract suspend fun loadExportDetail(taskId: String): ExportTaskDetailEntity?

    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT task_id FROM data_tasks WHERE task_id = :taskId
            UNION ALL
            SELECT task_id FROM archive_task_details WHERE task_id = :taskId
            UNION ALL
            SELECT task_id FROM export_task_details WHERE task_id = :taskId
            UNION ALL
            SELECT task_id FROM data_task_items WHERE task_id = :taskId
            UNION ALL
            SELECT task_id FROM data_task_outputs WHERE task_id = :taskId
        )
        """
    )
    protected abstract fun observeTaskAggregate(taskId: String): Flow<Int>

    @Query(
        """
        SELECT task_id FROM data_tasks
        WHERE kind = :kind
          AND target_key = :targetKey
          AND state IN ('QUEUED', 'STAGING_SOURCE', 'RUNNING', 'CANCEL_REQUESTED')
        ORDER BY queue_sequence ASC, task_id ASC
        LIMIT 1
        """
    )
    protected abstract fun observeActiveTaskIdRow(kind: String, targetKey: String): Flow<String?>

    @Query(
        """
        UPDATE data_tasks
        SET state = :blockedState,
            updated_at_epoch_ms = :nowMs
        WHERE task_id = :taskId
          AND state = :expectedState
          AND state IN ('QUEUED', 'STAGING_SOURCE')
          AND :blockedState IN ('START_BLOCKED', 'START_BLOCKED_NOTIFICATION')
          AND terminal_at_epoch_ms IS NULL
          AND cancel_requested_at_epoch_ms IS NULL
          AND service_session_token IS NULL
          AND claim_token IS NULL
          AND claim_lease_expires_at_epoch_ms IS NULL
        """
    )
    protected abstract suspend fun compareAndSetStartBlockedRow(
        taskId: String,
        expectedState: String,
        blockedState: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        SELECT * FROM data_tasks
        WHERE state IN ('QUEUED', 'STAGING_SOURCE', 'RUNNING')
          AND terminal_at_epoch_ms IS NULL
          AND cancel_requested_at_epoch_ms IS NULL
        ORDER BY queue_sequence ASC, task_id ASC
        LIMIT 1
        """
    )
    protected abstract suspend fun findOldestStartableTask(): DataTaskEntity?

    @Query(
        """
        UPDATE data_tasks
        SET state = :blockedState,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs
        WHERE task_id = :taskId
          AND state = :expectedState
          AND state IN ('QUEUED', 'STAGING_SOURCE', 'RUNNING')
          AND terminal_at_epoch_ms IS NULL
          AND cancel_requested_at_epoch_ms IS NULL
          AND :blockedState IN ('START_BLOCKED', 'START_BLOCKED_NOTIFICATION')
        """
    )
    protected abstract suspend fun blockCurrentStartRow(
        taskId: String,
        expectedState: String,
        blockedState: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_tasks
        SET acknowledged_at_epoch_ms = COALESCE(acknowledged_at_epoch_ms, :nowMs)
        WHERE task_id = :taskId
          AND state IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'EXPIRED')
        """
    )
    protected abstract suspend fun acknowledgeTerminalTaskRow(taskId: String, nowMs: Long): Int

    @Query(
        """
        SELECT * FROM data_tasks
        WHERE state IN ('QUEUED', 'STAGING_SOURCE')
          AND claim_token IS NULL
        ORDER BY queue_sequence ASC, task_id ASC
        LIMIT 1
        """
    )
    protected abstract suspend fun findOldestRunnableTask(): DataTaskEntity?

    @Query(
        """
        UPDATE data_tasks
        SET state = CASE WHEN state IN ('QUEUED', 'STAGING_SOURCE') THEN 'RUNNING' ELSE state END,
            service_session_token = :sessionToken,
            claim_token = :claimToken,
            claim_lease_expires_at_epoch_ms = :leaseUntilMs,
            attempt_count = attempt_count + 1,
            claimed_at_epoch_ms = :nowMs,
            started_at_epoch_ms = COALESCE(started_at_epoch_ms, :nowMs),
            updated_at_epoch_ms = :nowMs
        WHERE task_id = :taskId
          AND state IN ('QUEUED', 'STAGING_SOURCE')
          AND claim_token IS NULL
        """
    )
    protected abstract suspend fun claimTaskRow(
        taskId: String,
        sessionToken: String,
        claimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): Int

    @Query("SELECT * FROM data_tasks WHERE task_id = :taskId AND claim_token IS NOT NULL")
    protected abstract suspend fun loadClaimedTaskEntity(taskId: String): DataTaskEntity?

    @Query("SELECT * FROM data_tasks WHERE claim_token = :claimToken LIMIT 1")
    protected abstract suspend fun loadClaimedTaskByToken(claimToken: String): DataTaskEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM data_tasks WHERE claim_token = :claimToken)")
    protected abstract suspend fun hasTaskClaimToken(claimToken: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM data_task_items WHERE claim_token = :claimToken)")
    protected abstract suspend fun hasItemClaimToken(claimToken: String): Boolean

    @Query(
        """
        SELECT data_tasks.task_id
        FROM data_tasks
        INNER JOIN archive_task_details
          ON archive_task_details.task_id = data_tasks.task_id
        WHERE data_tasks.kind = 'ARCHIVE_RESTORE'
          AND archive_task_details.restore_source_kind = 'AWAITING_TRANSIENT_GRANT'
          AND data_tasks.state IN ('WAITING_FOR_SOURCE', 'CANCELLED', 'FAILED', 'EXPIRED')
        ORDER BY data_tasks.queue_sequence ASC, data_tasks.task_id ASC
        """
    )
    protected abstract suspend fun loadUncommittedRestoreSourceCleanupTaskIds(): List<String>

    @Query(
        """
        SELECT data_task_items.* FROM data_task_items
        INNER JOIN data_tasks ON data_tasks.task_id = data_task_items.task_id
        WHERE data_task_items.task_id = :taskId
          AND data_task_items.state = 'PENDING'
          AND (data_task_items.claim_token IS NULL OR data_task_items.claim_lease_expires_at_epoch_ms <= :nowMs)
          AND data_tasks.state = 'RUNNING'
          AND data_tasks.claim_token = :taskClaimToken
          AND data_tasks.claim_lease_expires_at_epoch_ms > :nowMs
        ORDER BY data_task_items.ordinal ASC
        LIMIT 1
        """
    )
    protected abstract suspend fun findNextPendingItem(
        taskId: String,
        taskClaimToken: String,
        nowMs: Long,
    ): DataTaskItemEntity?

    @Query(
        """
        UPDATE data_task_items
        SET state = 'RUNNING',
            claim_token = :itemClaimToken,
            claim_lease_expires_at_epoch_ms = :leaseUntilMs,
            attempt_count = attempt_count + 1,
            started_at_epoch_ms = COALESCE(started_at_epoch_ms, :nowMs)
        WHERE task_id = :taskId AND ordinal = :ordinal
          AND state = 'PENDING'
          AND (claim_token IS NULL OR claim_lease_expires_at_epoch_ms <= :nowMs)
          AND EXISTS (
              SELECT 1 FROM data_tasks
              WHERE data_tasks.task_id = :taskId
                AND data_tasks.state = 'RUNNING'
                AND data_tasks.claim_token = :taskClaimToken
                AND data_tasks.claim_lease_expires_at_epoch_ms > :nowMs
          )
        """
    )
    protected abstract suspend fun claimItemRow(
        taskId: String,
        ordinal: Int,
        taskClaimToken: String,
        itemClaimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): Int

    @Query(
        """
        SELECT * FROM data_task_items
        WHERE task_id = :taskId AND ordinal = :ordinal AND claim_token IS NOT NULL
        """
    )
    protected abstract suspend fun loadClaimedItemEntity(
        taskId: String,
        ordinal: Int,
    ): DataTaskItemEntity?

    @Query(
        """
        UPDATE archive_task_details
        SET restore_source_kind = 'PRIVATE_COPY',
            restore_source_grant_identity = NULL,
            restore_source_private_relative_path = :privateRelativePath
        WHERE task_id = :taskId
          AND restore_source_kind = 'AWAITING_TRANSIENT_GRANT'
        """
    )
    protected abstract suspend fun commitPrivateRestoreSourceRow(
        taskId: String,
        privateRelativePath: String,
    ): Int

    @Query(
        """
        UPDATE data_tasks
        SET updated_at_epoch_ms = :nowMs
        WHERE task_id = :taskId
          AND state = 'RUNNING'
          AND claim_token = :taskClaimToken
          AND claim_lease_expires_at_epoch_ms > :nowMs
        """
    )
    protected abstract suspend fun touchClaimedTaskRow(
        taskId: String,
        taskClaimToken: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_tasks
        SET interruption = :interruption,
            result_code = :resultCode,
            updated_at_epoch_ms = :nowMs
        WHERE task_id = :taskId
          AND state = 'RUNNING'
          AND claim_token = :taskClaimToken
          AND claim_lease_expires_at_epoch_ms > :nowMs
        """
    )
    protected abstract suspend fun markClaimInterruptedRow(
        taskId: String,
        taskClaimToken: String,
        interruption: String,
        resultCode: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_tasks
        SET stage = :stage,
            completed = :completed,
            total = :total,
            updated_at_epoch_ms = :recordedAtEpochMs,
            claim_lease_expires_at_epoch_ms = :leaseUntilMs
        WHERE task_id = :taskId
          AND state = 'RUNNING'
          AND claim_token = :taskClaimToken
          AND claim_lease_expires_at_epoch_ms > :ownershipCheckedAtEpochMs
        """
    )
    protected abstract suspend fun checkpointTaskRow(
        taskId: String,
        taskClaimToken: String,
        stage: String,
        completed: Long,
        total: Long,
        recordedAtEpochMs: Long,
        ownershipCheckedAtEpochMs: Long,
        leaseUntilMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_task_items
        SET claim_lease_expires_at_epoch_ms = :leaseUntilMs
        WHERE task_id = :taskId AND ordinal = :ordinal
          AND state = 'RUNNING'
          AND claim_token = :itemClaimToken
          AND claim_lease_expires_at_epoch_ms > :nowMs
        """
    )
    protected abstract suspend fun renewItemClaimRow(
        taskId: String,
        ordinal: Int,
        itemClaimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): Int

    @Query(
        """
        UPDATE archive_task_details
        SET destructive_started = CASE
                WHEN destructive_started = 1 OR :destructiveStarted = 1 THEN 1 ELSE 0 END,
            mutation_package_name = COALESCE(:mutationPackageName, mutation_package_name),
            mutation_app_label = COALESCE(:mutationAppLabel, mutation_app_label),
            mutation_started_at_epoch_ms = COALESCE(:mutationStartedAtEpochMs, mutation_started_at_epoch_ms)
        WHERE task_id = :taskId
        """
    )
    protected abstract suspend fun updateRestoreCheckpoint(
        taskId: String,
        destructiveStarted: Boolean,
        mutationPackageName: String?,
        mutationAppLabel: String?,
        mutationStartedAtEpochMs: Long?,
    ): Int

    @Query(
        """
        UPDATE data_task_items
        SET state = :state,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            result_code = :resultCode,
            finished_at_epoch_ms = :finishedAtEpochMs
        WHERE task_id = :taskId AND ordinal = :ordinal
          AND state = 'RUNNING'
          AND claim_token = :itemClaimToken
          AND EXISTS (
              SELECT 1 FROM data_tasks
              WHERE data_tasks.task_id = :taskId
                AND data_tasks.state = 'RUNNING'
                AND data_tasks.claim_token = :taskClaimToken
          )
        """
    )
    protected abstract suspend fun completeItemRow(
        taskId: String,
        ordinal: Int,
        taskClaimToken: String,
        itemClaimToken: String,
        state: String,
        resultCode: String,
        finishedAtEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_tasks
        SET completed = (
                SELECT COUNT(*) FROM data_task_items
                WHERE task_id = :taskId AND state IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
            ),
            total = (SELECT COUNT(*) FROM data_task_items WHERE task_id = :taskId),
            updated_at_epoch_ms = :nowMs
        WHERE task_id = :taskId AND state = 'RUNNING' AND claim_token = :taskClaimToken
        """
    )
    protected abstract suspend fun refreshTaskProgress(
        taskId: String,
        taskClaimToken: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_task_items
        SET state = :state,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL
        WHERE task_id = :taskId AND ordinal = :ordinal
          AND state = 'RUNNING' AND claim_token = :itemClaimToken
        """
    )
    protected abstract suspend fun releaseClaimedItemRow(
        taskId: String,
        ordinal: Int,
        itemClaimToken: String,
        state: String,
    ): Int

    @Query(
        """
        UPDATE data_tasks
        SET state = :state,
            interruption = :interruption,
            result_code = :resultCode,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs
        WHERE task_id = :taskId AND claim_token = :taskClaimToken
        """
    )
    protected abstract suspend fun pauseTaskRow(
        taskId: String,
        taskClaimToken: String,
        state: String,
        interruption: String,
        resultCode: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_task_items
        SET state = :state,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            result_code = :resultCode,
            finished_at_epoch_ms = :nowMs
        WHERE task_id = :taskId AND ordinal = :ordinal
          AND state = 'RUNNING' AND claim_token = :itemClaimToken
        """
    )
    protected abstract suspend fun terminateActiveItemRow(
        taskId: String,
        ordinal: Int,
        itemClaimToken: String,
        state: String,
        resultCode: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_task_items
        SET state = 'CANCELLED',
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            result_code = :resultCode,
            finished_at_epoch_ms = :nowMs
        WHERE task_id = :taskId AND state = 'PENDING'
        """
    )
    protected abstract suspend fun cancelPendingItems(
        taskId: String,
        resultCode: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_task_items
        SET state = 'CANCELLED',
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            result_code = :resultCode,
            finished_at_epoch_ms = :nowMs
        WHERE task_id = :taskId AND state IN ('PENDING', 'RUNNING')
        """
    )
    protected abstract suspend fun cancelUnfinishedItems(
        taskId: String,
        resultCode: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_tasks
        SET state = :state,
            interruption = 'NONE',
            result_code = :resultCode,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs,
            terminal_at_epoch_ms = :nowMs,
            retain_until_epoch_ms = :nowMs + 86400000
        WHERE task_id = :taskId AND claim_token = :taskClaimToken
        """
    )
    protected abstract suspend fun terminateTaskRow(
        taskId: String,
        taskClaimToken: String,
        state: String,
        resultCode: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_tasks
        SET state = 'CANCEL_REQUESTED',
            cancel_requested_at_epoch_ms = COALESCE(cancel_requested_at_epoch_ms, :nowMs),
            updated_at_epoch_ms = :nowMs
        WHERE task_id = :taskId AND state = :expectedState
        """
    )
    protected abstract suspend fun requestTaskCancellationRow(
        taskId: String,
        expectedState: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_tasks
        SET state = 'CANCELLED',
            cancel_requested_at_epoch_ms = COALESCE(cancel_requested_at_epoch_ms, :nowMs),
            interruption = 'NONE',
            result_code = :resultCode,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs,
            terminal_at_epoch_ms = :nowMs,
            retain_until_epoch_ms = :nowMs + 86400000
        WHERE task_id = :taskId AND state = :expectedState AND claim_token IS NULL
        """
    )
    protected abstract suspend fun settleUnclaimedCancellationRow(
        taskId: String,
        expectedState: String,
        nowMs: Long,
        resultCode: String,
    ): Int

    @Query(
        """
        UPDATE data_tasks
        SET state = 'CANCELLED',
            interruption = 'NONE',
            result_code = :resultCode,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs,
            terminal_at_epoch_ms = :nowMs,
            retain_until_epoch_ms = :nowMs + 86400000
        WHERE task_id = :taskId
          AND state = 'CANCEL_REQUESTED'
          AND claim_token = :oldClaimToken
        """
    )
    protected abstract suspend fun settleRecoveredCancellationRow(
        taskId: String,
        oldClaimToken: String,
        nowMs: Long,
        resultCode: String,
    ): Int

    @Query(
        """
        SELECT * FROM data_tasks
        WHERE claim_token IS NOT NULL OR service_session_token IS NOT NULL
        ORDER BY queue_sequence, task_id
        """
    )
    protected abstract suspend fun loadRecoverableTasks(): List<DataTaskEntity>

    @Query(
        """
        UPDATE data_tasks
        SET state = :state,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs
        WHERE task_id = :taskId AND claim_token = :oldClaimToken
        """
    )
    protected abstract suspend fun clearTaskClaimForRecovery(
        taskId: String,
        oldClaimToken: String?,
        state: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_tasks
        SET state = :state,
            interruption = :interruption,
            result_code = :resultCode,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs
        WHERE task_id = :taskId AND claim_token = :oldClaimToken
        """
    )
    protected abstract suspend fun pauseTaskForRecovery(
        taskId: String,
        oldClaimToken: String?,
        state: String,
        interruption: String,
        resultCode: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_task_items
        SET state = CASE WHEN state = 'RUNNING' THEN :state ELSE state END,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL
        WHERE task_id = :taskId AND claim_token IS NOT NULL
        """
    )
    protected abstract suspend fun clearItemClaimsForRecovery(taskId: String, state: String): Int

    @Query(
        """
        UPDATE data_task_items
        SET state = 'FAILED',
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            result_code = :resultCode,
            finished_at_epoch_ms = :nowMs
        WHERE task_id = :taskId AND state = 'RUNNING' AND claim_token IS NOT NULL
        """
    )
    protected abstract suspend fun failItemClaimsForRecovery(
        taskId: String,
        resultCode: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_task_items
        SET claim_token = NULL, claim_lease_expires_at_epoch_ms = NULL
        WHERE task_id = :taskId AND claim_token IS NOT NULL
        """
    )
    protected abstract suspend fun clearItemClaimTokens(taskId: String): Int

    @Query(
        """
        UPDATE data_tasks
        SET state = 'FAILED',
            interruption = 'NONE',
            result_code = :resultCode,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs,
            terminal_at_epoch_ms = :nowMs,
            retain_until_epoch_ms = :nowMs + 86400000
        WHERE task_id = :taskId AND claim_token = :oldClaimToken
        """
    )
    protected abstract suspend fun failTaskForRecovery(
        taskId: String,
        oldClaimToken: String?,
        resultCode: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM data_task_items
        WHERE task_id = :taskId AND state IN ('PENDING', 'RUNNING')
        """
    )
    protected abstract suspend fun countUnfinishedItems(taskId: String): Int

    @Query("SELECT COUNT(*) FROM data_task_items WHERE task_id = :taskId AND state = :state")
    protected abstract suspend fun countItemsInState(taskId: String, state: String): Int

    @Query("SELECT COUNT(*) FROM data_task_outputs WHERE task_id = :taskId AND state = :state")
    protected abstract suspend fun countOutputsInState(taskId: String, state: String): Int

    @Query(
        """
        UPDATE data_tasks
        SET state = :state,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            result_code = :resultCode,
            updated_at_epoch_ms = :nowMs,
            terminal_at_epoch_ms = CASE
                WHEN :state IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'EXPIRED') THEN :nowMs
                ELSE NULL
            END,
            retain_until_epoch_ms = CASE
                WHEN :state IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'EXPIRED')
                    THEN :nowMs + 86400000
                ELSE NULL
            END
        WHERE task_id = :taskId AND state = 'RUNNING' AND claim_token = :claimToken
        """
    )
    protected abstract suspend fun finishTaskRow(
        taskId: String,
        claimToken: String,
        state: String,
        resultCode: String?,
        nowMs: Long,
    ): Int

    @Query(
        """
        SELECT data_task_outputs.* FROM data_task_outputs
        INNER JOIN data_tasks ON data_tasks.task_id = data_task_outputs.task_id
        WHERE data_task_outputs.task_id = :taskId
          AND data_task_outputs.state = 'READY'
          AND (data_task_outputs.expires_at_epoch_ms IS NULL OR data_task_outputs.expires_at_epoch_ms > :nowMs)
          AND data_tasks.state IN ('READY', 'READY_PARTIAL')
        ORDER BY data_task_outputs.item_ordinal, data_task_outputs.output_id
        """
    )
    protected abstract suspend fun loadReadyOutputs(
        taskId: String,
        nowMs: Long,
    ): List<DataTaskOutputEntity>

    @Query(
        """
        SELECT * FROM data_task_outputs
        WHERE state = 'READY'
          AND expires_at_epoch_ms IS NOT NULL
          AND expires_at_epoch_ms <= :nowMs
        ORDER BY expires_at_epoch_ms, output_id
        """
    )
    protected abstract suspend fun loadExpiredReadyOutputs(nowMs: Long): List<DataTaskOutputEntity>

    @Query(
        """
        SELECT COUNT(*) FROM data_task_outputs
        WHERE task_id = :taskId AND output_id IN (:outputIds)
          AND state = 'READY'
          AND expires_at_epoch_ms IS NOT NULL
          AND expires_at_epoch_ms <= :nowMs
        """
    )
    protected abstract suspend fun countExpiredReadyOutputs(
        taskId: String,
        outputIds: List<String>,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_task_outputs
        SET state = 'EXPIRED', private_relative_path = NULL
        WHERE task_id = :taskId AND output_id IN (:outputIds)
          AND state = 'READY'
          AND expires_at_epoch_ms IS NOT NULL
          AND expires_at_epoch_ms <= :nowMs
        """
    )
    protected abstract suspend fun expireReadyOutputs(
        taskId: String,
        outputIds: List<String>,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE data_tasks
        SET state = 'EXPIRED',
            updated_at_epoch_ms = :nowMs,
            terminal_at_epoch_ms = :nowMs,
            retain_until_epoch_ms = :nowMs + 86400000
        WHERE task_id = :taskId AND state IN ('READY', 'READY_PARTIAL')
        """
    )
    protected abstract suspend fun expireReadyTask(taskId: String, nowMs: Long): Int

    private suspend fun insertDetail(
        taskId: String,
        kind: DataTaskKind,
        detail: StoredDataTaskDetail,
    ) {
        when (detail) {
            is StoredDataTaskDetail.ArchiveBackup -> {
                check(kind == DataTaskKind.ARCHIVE_BACKUP)
                val destination = detail.destination.toStoredColumns()
                insertArchiveDetail(
                    ArchiveTaskDetailEntity(
                        taskId = taskId,
                        packageName = detail.packageName,
                        dataClassIdsJson = JSON.encodeToString(detail.dataClassIds),
                        includeBundle = detail.includeBundle,
                        kdfSaltBase64 = detail.kdfSaltBase64,
                        destinationKind = destination.kind,
                        destinationGrantIdentity = destination.grantIdentity,
                        restoreObb = null,
                        restoreSourceKind = null,
                        restoreSourceGrantIdentity = null,
                        restoreSourcePrivateRelativePath = null,
                        deterministicStagingIdentity = detail.deterministicStagingIdentity,
                        destructiveStarted = false,
                        mutationPackageName = null,
                        mutationAppLabel = null,
                        mutationStartedAtEpochMs = null,
                    ),
                )
            }

            is StoredDataTaskDetail.ArchiveRestore -> {
                check(kind == DataTaskKind.ARCHIVE_RESTORE)
                val source = detail.source.toStoredColumns()
                insertArchiveDetail(
                    ArchiveTaskDetailEntity(
                        taskId = taskId,
                        packageName = detail.expectedPackageName,
                        dataClassIdsJson = JSON.encodeToString(detail.dataClassIds),
                        includeBundle = null,
                        kdfSaltBase64 = null,
                        destinationKind = null,
                        destinationGrantIdentity = null,
                        restoreObb = detail.restoreObb,
                        restoreSourceKind = source.kind,
                        restoreSourceGrantIdentity = source.grantIdentity,
                        restoreSourcePrivateRelativePath = source.privateRelativePath,
                        deterministicStagingIdentity = detail.deterministicStagingIdentity,
                        destructiveStarted = detail.mutationBreadcrumb != null,
                        mutationPackageName = detail.mutationBreadcrumb?.packageName,
                        mutationAppLabel = detail.mutationBreadcrumb?.appLabel,
                        mutationStartedAtEpochMs = detail.mutationBreadcrumb?.startedAtEpochMs,
                    ),
                )
            }

            is StoredDataTaskDetail.AppExport -> {
                check(kind == DataTaskKind.APP_EXPORT)
                val destination = detail.destination.toStoredColumns()
                insertExportDetail(
                    ExportTaskDetailEntity(
                        taskId = taskId,
                        requestedFormat = detail.requestedFormat.name,
                        destinationKind = destination.kind,
                        destinationGrantIdentity = destination.grantIdentity,
                        namingLabel = detail.namingLabel,
                        publicationPolicy = detail.publicationPolicy.name,
                        deterministicStagingIdentity = detail.deterministicStagingIdentity,
                    ),
                )
            }

            is StoredDataTaskDetail.SharePrepare -> {
                check(kind == DataTaskKind.SHARE_PREPARE)
                insertExportDetail(
                    ExportTaskDetailEntity(
                        taskId = taskId,
                        requestedFormat = detail.requestedFormat.name,
                        destinationKind = null,
                        destinationGrantIdentity = null,
                        namingLabel = null,
                        publicationPolicy = detail.publicationPolicy.name,
                        deterministicStagingIdentity = detail.deterministicStagingIdentity,
                    ),
                )
            }
        }
    }

    private suspend fun loadSnapshot(taskId: String): DataTaskSnapshot? {
        val task = loadTaskEntity(taskId) ?: return null
        return task.toSnapshot(
            detail = loadDetail(task),
            items = loadItemEntities(taskId).map { it.toSnapshot() },
            outputs = loadOutputEntities(taskId).map { it.toSnapshot() },
        )
    }

    private suspend fun loadClaimedTask(taskId: String): ClaimedDataTask? {
        val task = loadClaimedTaskEntity(taskId) ?: return null
        val activeItem = loadRunningItem(taskId)
        val archive = loadArchiveDetail(taskId)
        return ClaimedDataTask(
            taskId = UUID.fromString(task.taskId),
            queueSequence = task.queueSequence,
            payloadSchemaVersion = task.payloadSchemaVersion,
            kind = DataTaskKind.valueOf(task.kind),
            targetKey = task.targetKey,
            detail = loadDetail(task),
            serviceSessionToken = requireNotNull(task.serviceSessionToken),
            claimToken = requireNotNull(task.claimToken),
            claimLeaseExpiresAtEpochMs = requireNotNull(task.claimLeaseExpiresAtEpochMs),
            attemptCount = task.attemptCount,
            lastCheckpoint = task.stage?.let { stage ->
                DataTaskCheckpoint(
                    stage = DataTaskStage.valueOf(stage),
                    completed = task.completed,
                    total = task.total,
                    activeItemOrdinal = activeItem?.ordinal,
                    activeItemLabel = activeItem?.displayLabel,
                    destructiveStarted = archive?.destructiveStarted == true,
                    restoreMutationBreadcrumb = archive?.toBreadcrumb(),
                    recordedAtEpochMs = task.updatedAtEpochMs,
                )
            },
        )
    }

    private suspend fun loadClaimedItem(taskId: String, ordinal: Int): ClaimedDataTaskItem? {
        val item = loadClaimedItemEntity(taskId, ordinal) ?: return null
        return ClaimedDataTaskItem(
            taskId = UUID.fromString(item.taskId),
            ordinal = item.ordinal,
            packageName = item.packageName,
            displayLabel = item.displayLabel,
            deterministicStagingIdentity = item.deterministicStagingIdentity,
            claimToken = requireNotNull(item.claimToken),
            claimLeaseExpiresAtEpochMs = requireNotNull(item.claimLeaseExpiresAtEpochMs),
            attemptCount = item.attemptCount,
        )
    }

    private suspend fun loadDetail(task: DataTaskEntity): StoredDataTaskDetail =
        when (DataTaskKind.valueOf(task.kind)) {
            DataTaskKind.ARCHIVE_BACKUP,
            DataTaskKind.ARCHIVE_RESTORE,
                -> requireNotNull(loadArchiveDetail(task.taskId)).toDomain(
                DataTaskKind.valueOf(task.kind),
                UUID.fromString(task.taskId),
            )

            DataTaskKind.APP_EXPORT,
            DataTaskKind.SHARE_PREPARE,
                -> requireNotNull(loadExportDetail(task.taskId)).toDomain(DataTaskKind.valueOf(task.kind))
        }

    private suspend fun pauseClaimedTask(
        taskId: String,
        taskClaimToken: String,
        itemOrdinal: Int,
        itemClaimToken: String,
        state: DataTaskState,
        interruption: DataTaskInterruption,
        resultCode: DataTaskResultCode,
        nowMs: Long,
        keepItemRunning: Boolean,
    ): Boolean {
        val itemState =
            if (keepItemRunning) DataTaskItemState.RUNNING else DataTaskItemState.PENDING
        return releaseClaimedItemRow(
            taskId,
            itemOrdinal,
            itemClaimToken,
            itemState.name,
        ) == 1 && pauseTaskRow(
            taskId,
            taskClaimToken,
            state.name,
            interruption.name,
            resultCode.value,
            nowMs,
        ) == 1
    }

    private suspend fun terminateClaimedTask(
        taskId: String,
        taskClaimToken: String,
        itemOrdinal: Int,
        itemClaimToken: String,
        taskState: DataTaskState,
        itemState: DataTaskItemState,
        resultCode: DataTaskResultCode,
        nowMs: Long,
        failureReason: String? = null,
    ): Boolean {
        if (
            terminateActiveItemRow(
                taskId,
                itemOrdinal,
                itemClaimToken,
                itemState.name,
                resultCode.value,
                nowMs,
            ) != 1
        ) {
            return false
        }
        cancelPendingItems(taskId, RESULT_CANCELLED, nowMs)
        return terminateTaskRow(
            taskId,
            taskClaimToken,
            taskState.name,
            encodeStoredResult(resultCode, failureReason = failureReason) ?: resultCode.value,
            nowMs,
        ) == 1
    }

    private fun DataTaskItemResult.toStoredResult(): String = encodeStoredResult(
        resultCode = resultCode,
        warnings = warnings.map { warning ->
            warning.arguments.firstOrNull() ?: warning.code.toUserFacingJobMessage()
        },
        failureReason = if (terminalState == DataTaskItemTerminalState.FAILED) {
            resultCode.toUserFacingJobMessage()
        } else {
            null
        },
    ) ?: resultCode.value

    private fun encodeStoredResult(
        resultCode: DataTaskResultCode?,
        warnings: List<String> = emptyList(),
        failureReason: String? = null,
    ): String? {
        if (warnings.isEmpty() && failureReason == null) return resultCode?.value
        requireTaskPresentationArguments(warnings, "warnings")
        failureReason?.let { requireTaskPresentationArguments(listOf(it), "failureReason") }
        val parts = buildList {
            add(STORED_RESULT_PREFIX)
            add(resultCode?.value ?: "-")
            add(failureReason?.let { "+${it.encodeStoredResultPart()}" } ?: "-")
            add(warnings.size.toString())
            warnings.forEach { add(it.encodeStoredResultPart()) }
        }
        return parts.joinToString(STORED_RESULT_SEPARATOR)
    }

    private fun decodeStoredResult(stored: String?): StoredResultPresentation {
        if (stored == null) return StoredResultPresentation()
        if (!stored.startsWith("$STORED_RESULT_PREFIX$STORED_RESULT_SEPARATOR")) {
            return StoredResultPresentation(
                resultCode = runCatching { DataTaskResultCode(stored) }.getOrNull(),
            )
        }
        return runCatching {
            val parts = stored.split(STORED_RESULT_SEPARATOR)
            require(parts.size >= STORED_RESULT_HEADER_PARTS)
            require(parts.first() == STORED_RESULT_PREFIX)
            val warningCount = parts[3].toInt()
            require(warningCount in 0..MAX_TASK_WARNING_COUNT)
            require(parts.size == STORED_RESULT_HEADER_PARTS + warningCount)
            val resultCode = parts[1].takeUnless { it == "-" }?.let(::DataTaskResultCode)
            val failureReason = parts[2].takeUnless { it == "-" }?.also {
                require(it.startsWith('+'))
            }?.drop(1)?.decodeStoredResultPart()
            val warnings =
                parts.drop(STORED_RESULT_HEADER_PARTS).map { it.decodeStoredResultPart() }
            requireTaskPresentationArguments(warnings, "stored warnings")
            failureReason?.let {
                requireTaskPresentationArguments(listOf(it), "stored failure reason")
            }
            StoredResultPresentation(resultCode, warnings, failureReason)
        }.getOrElse { StoredResultPresentation() }
    }

    private fun String.encodeStoredResultPart(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(StandardCharsets.UTF_8))

    private fun String.decodeStoredResultPart(): String =
        String(Base64.getUrlDecoder().decode(this), StandardCharsets.UTF_8)

    private fun validateNewTask(request: NewDataTaskRow) {
        requireCanonicalUuid(request.taskId, "taskId")
        require(request.payloadSchemaVersion > 0) { "payloadSchemaVersion must be positive" }
        require(request.targetKey.isNotBlank()) { "targetKey must not be blank" }
        require(request.createdAtEpochMs >= 0) { "createdAtEpochMs must be non-negative" }
        require(request.items.isNotEmpty()) { "items must not be empty" }
        require(request.items.map(NewDataTaskItem::ordinal) == request.items.indices.toList()) {
            "item ordinals must be contiguous from zero"
        }
        require(request.items.all { it.packageName.isNotBlank() }) { "packageName must not be blank" }
        require(request.items.all { it.deterministicStagingIdentity.isNotBlank() }) {
            "deterministicStagingIdentity must not be blank"
        }
        require(
            when (request.kind) {
                DataTaskKind.ARCHIVE_BACKUP -> request.detail is StoredDataTaskDetail.ArchiveBackup
                DataTaskKind.ARCHIVE_RESTORE -> request.detail is StoredDataTaskDetail.ArchiveRestore
                DataTaskKind.APP_EXPORT -> request.detail is StoredDataTaskDetail.AppExport
                DataTaskKind.SHARE_PREPARE -> request.detail is StoredDataTaskDetail.SharePrepare
            },
        ) { "detail must match kind" }
    }

    private fun validateOutputs(result: DataTaskItemResult) {
        require(result.finishedAtEpochMs >= 0) { "finishedAtEpochMs must be non-negative" }
        require(result.outputs.map { it.outputId }.distinct().size == result.outputs.size) {
            "output IDs must be unique"
        }
    }

    private fun DataTaskEntity.ownsClaim(token: String, nowMs: Long): Boolean =
        state == DataTaskState.RUNNING.name &&
                claimToken == token &&
                claimLeaseExpiresAtEpochMs != null &&
                claimLeaseExpiresAtEpochMs > nowMs

    private fun DataTaskItemEntity.ownsClaim(token: String, nowMs: Long): Boolean =
        state == DataTaskItemState.RUNNING.name &&
                claimToken == token &&
                claimLeaseExpiresAtEpochMs != null &&
                claimLeaseExpiresAtEpochMs > nowMs

    private fun DataTaskEntity.toSnapshot(
        detail: StoredDataTaskDetail,
        items: List<DataTaskItemSnapshot>,
        outputs: List<DataTaskOutputSnapshot>,
    ): DataTaskSnapshot {
        val presentation = decodeStoredResult(resultCode)
        return DataTaskSnapshot(
            taskId = UUID.fromString(taskId),
            queueSequence = queueSequence,
            payloadSchemaVersion = payloadSchemaVersion,
            kind = DataTaskKind.valueOf(kind),
            state = DataTaskState.valueOf(state),
            targetKey = targetKey,
            detail = detail,
            stage = stage?.let(DataTaskStage::valueOf),
            completed = completed,
            total = total,
            attemptCount = attemptCount,
            interruption = interruption?.let(DataTaskInterruption::valueOf)
                ?: DataTaskInterruption.NONE,
            resultCode = presentation.resultCode,
            cancelRequestedAtEpochMs = cancelRequestedAtEpochMs,
            createdAtEpochMs = createdAtEpochMs,
            claimedAtEpochMs = claimedAtEpochMs,
            startedAtEpochMs = startedAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs,
            terminalAtEpochMs = terminalAtEpochMs,
            retainUntilEpochMs = retainUntilEpochMs,
            acknowledgedAtEpochMs = acknowledgedAtEpochMs,
            items = items,
            outputs = outputs,
            warnings = presentation.warnings,
            failureReason = presentation.failureReason,
        )
    }

    private fun DataTaskItemEntity.toSnapshot(): DataTaskItemSnapshot {
        val presentation = decodeStoredResult(resultCode)
        return DataTaskItemSnapshot(
            ordinal = ordinal,
            packageName = packageName,
            displayLabel = displayLabel,
            state = DataTaskItemState.valueOf(state),
            attemptCount = attemptCount,
            resultCode = presentation.resultCode,
            deterministicStagingIdentity = deterministicStagingIdentity,
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
        )
    }

    private fun DataTaskOutputEntity.toSnapshot() = DataTaskOutputSnapshot(
        outputId = UUID.fromString(outputId),
        itemOrdinal = itemOrdinal,
        privateRelativePath = privateRelativePath,
        displayName = displayName,
        mimeType = mimeType,
        byteSize = byteSize,
        state = DataTaskOutputState.valueOf(state),
        expiresAtEpochMs = expiresAtEpochMs,
    )

    private fun ArchiveTaskDetailEntity.toDomain(
        kind: DataTaskKind,
        ownerTaskId: UUID,
    ): StoredDataTaskDetail =
        when (kind) {
            DataTaskKind.ARCHIVE_BACKUP -> StoredDataTaskDetail.ArchiveBackup(
                packageName = packageName,
                dataClassIds = JSON.decodeFromString(dataClassIdsJson),
                includeBundle = requireNotNull(includeBundle),
                kdfSaltBase64 = requireNotNull(kdfSaltBase64),
                destination = requireNotNull(destinationKind).toDestination(destinationGrantIdentity),
                deterministicStagingIdentity = deterministicStagingIdentity,
            )

            DataTaskKind.ARCHIVE_RESTORE -> StoredDataTaskDetail.ArchiveRestore(
                expectedPackageName = packageName,
                dataClassIds = JSON.decodeFromString(dataClassIdsJson),
                restoreObb = requireNotNull(restoreObb),
                source = requireNotNull(restoreSourceKind).toRestoreSource(
                    ownerTaskId,
                    restoreSourceGrantIdentity,
                    restoreSourcePrivateRelativePath,
                ),
                mutationBreadcrumb = toBreadcrumb(),
                deterministicStagingIdentity = deterministicStagingIdentity,
            )

            else -> error("$kind does not use archive task details")
        }

    private fun ExportTaskDetailEntity.toDomain(kind: DataTaskKind): StoredDataTaskDetail =
        when (kind) {
            DataTaskKind.APP_EXPORT -> StoredDataTaskDetail.AppExport(
                requestedFormat = BundleFormat.valueOf(requestedFormat),
                destination = requireNotNull(destinationKind).toDestination(destinationGrantIdentity),
                namingLabel = requireNotNull(namingLabel),
                publicationPolicy = DataTaskPublicationPolicy.valueOf(publicationPolicy),
                deterministicStagingIdentity = deterministicStagingIdentity,
            )

            DataTaskKind.SHARE_PREPARE -> StoredDataTaskDetail.SharePrepare(
                requestedFormat = BundleFormat.valueOf(requestedFormat),
                publicationPolicy = DataTaskPublicationPolicy.valueOf(publicationPolicy),
                deterministicStagingIdentity = deterministicStagingIdentity,
            )

            else -> error("$kind does not use export task details")
        }

    private fun ArchiveTaskDetailEntity.toBreadcrumb(): RestoreMutationBreadcrumb? {
        val packageName = mutationPackageName ?: return null
        return RestoreMutationBreadcrumb(
            packageName = packageName,
            appLabel = requireNotNull(mutationAppLabel),
            startedAtEpochMs = requireNotNull(mutationStartedAtEpochMs),
        )
    }

    private fun StoredDataDestination.toStoredColumns(): DestinationColumns = when (this) {
        StoredDataDestination.ArchiveStore -> DestinationColumns(DESTINATION_ARCHIVE_STORE, null)
        StoredDataDestination.Downloads -> DestinationColumns(DESTINATION_DOWNLOADS, null)
        StoredDataDestination.TaskPrivateStorage -> DestinationColumns(
            DESTINATION_TASK_PRIVATE,
            null
        )

        is StoredDataDestination.PersistedTreeGrant ->
            DestinationColumns(DESTINATION_PERSISTED_TREE, grantIdentity)
    }

    private fun String.toDestination(grantIdentity: String?): StoredDataDestination = when (this) {
        DESTINATION_ARCHIVE_STORE -> StoredDataDestination.ArchiveStore
        DESTINATION_DOWNLOADS -> StoredDataDestination.Downloads
        DESTINATION_TASK_PRIVATE -> StoredDataDestination.TaskPrivateStorage
        DESTINATION_PERSISTED_TREE -> StoredDataDestination.PersistedTreeGrant(
            requireNotNull(
                grantIdentity
            )
        )

        else -> error("Unknown stored destination: $this")
    }

    private fun StoredRestoreSource.toStoredColumns(): RestoreSourceColumns = when (this) {
        StoredRestoreSource.AwaitingTransientGrant ->
            RestoreSourceColumns(RESTORE_SOURCE_AWAITING_GRANT, null, null)

        is StoredRestoreSource.PersistedGrant ->
            RestoreSourceColumns(RESTORE_SOURCE_PERSISTED_GRANT, grantIdentity, null)

        is StoredRestoreSource.PrivateCopy ->
            RestoreSourceColumns(RESTORE_SOURCE_PRIVATE_COPY, null, privateRelativePath)
    }

    private fun String.toRestoreSource(
        ownerTaskId: UUID,
        grantIdentity: String?,
        privateRelativePath: String?,
    ): StoredRestoreSource = when (this) {
        RESTORE_SOURCE_AWAITING_GRANT -> StoredRestoreSource.AwaitingTransientGrant
        RESTORE_SOURCE_PERSISTED_GRANT -> StoredRestoreSource.PersistedGrant(
            requireNotNull(
                grantIdentity
            )
        )

        RESTORE_SOURCE_PRIVATE_COPY -> StoredRestoreSource.PrivateCopy(
            requireNotNull(privateRelativePath).also { path ->
                require(isPrivateRestoreSourceRelativePath(ownerTaskId, path)) {
                    "private restore source path must belong to taskId"
                }
            }
        )

        else -> error("Unknown stored restore source: $this")
    }

    private fun DataTaskItemTerminalState.toItemState(): DataTaskItemState = when (this) {
        DataTaskItemTerminalState.SUCCEEDED -> DataTaskItemState.SUCCEEDED
        DataTaskItemTerminalState.FAILED -> DataTaskItemState.FAILED
        DataTaskItemTerminalState.CANCELLED -> DataTaskItemState.CANCELLED
    }

    private fun DataTaskState.isTerminalOrReady(): Boolean = this in TERMINAL_OR_READY_STATES

    private fun requireCanonicalUuid(value: String, fieldName: String): UUID {
        val parsed = runCatching { UUID.fromString(value) }
            .getOrElse { throw IllegalArgumentException("$fieldName must be a canonical UUID", it) }
        require(parsed.toString() == value) { "$fieldName must be a canonical UUID" }
        return parsed
    }

    private data class DestinationColumns(
        val kind: String,
        val grantIdentity: String?,
    )

    private data class RestoreSourceColumns(
        val kind: String,
        val grantIdentity: String?,
        val privateRelativePath: String?,
    )

    private data class StoredResultPresentation(
        val resultCode: DataTaskResultCode? = null,
        val warnings: List<String> = emptyList(),
        val failureReason: String? = null,
    )

    private companion object {
        val JSON = Json
        const val DESTINATION_ARCHIVE_STORE = "ARCHIVE_STORE"
        const val DESTINATION_DOWNLOADS = "DOWNLOADS"
        const val DESTINATION_TASK_PRIVATE = "TASK_PRIVATE_STORAGE"
        const val DESTINATION_PERSISTED_TREE = "PERSISTED_TREE_GRANT"
        const val RESTORE_SOURCE_AWAITING_GRANT = "AWAITING_TRANSIENT_GRANT"
        const val RESTORE_SOURCE_PERSISTED_GRANT = "PERSISTED_GRANT"
        const val RESTORE_SOURCE_PRIVATE_COPY = "PRIVATE_COPY"
        const val STORED_RESULT_PREFIX = "THOR_RESULT_V1"
        const val STORED_RESULT_SEPARATOR = "|"
        const val STORED_RESULT_HEADER_PARTS = 4
        const val RESULT_CANCELLED = "CANCELLED"
        const val RESULT_SERVICE_TIMEOUT = "SERVICE_TIMEOUT"
        const val RESULT_DESTRUCTIVE_RESTORE_REVIEW = "DESTRUCTIVE_RESTORE_REVIEW"
        const val RESULT_RECOVERY_AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED"
        const val RESULT_RECOVERY_BREADCRUMB_MISSING = "RECOVERY_BREADCRUMB_MISSING"
        const val RESULT_RECOVERY_SOURCE_REQUIRED = "SOURCE_REQUIRED"
        val TIMEOUT_SETTLEABLE_STATES = setOf(
            DataTaskState.RUNNING.name,
            DataTaskState.CANCEL_REQUESTED.name,
        )
        val ACKNOWLEDGEABLE_STATES = setOf(
            DataTaskState.SUCCEEDED,
            DataTaskState.PARTIAL,
            DataTaskState.FAILED,
            DataTaskState.CANCELLED,
            DataTaskState.EXPIRED,
        )
        val TERMINAL_OR_READY_STATES = setOf(
            DataTaskState.READY,
            DataTaskState.READY_PARTIAL,
            DataTaskState.SUCCEEDED,
            DataTaskState.PARTIAL,
            DataTaskState.FAILED,
            DataTaskState.CANCELLED,
            DataTaskState.EXPIRED,
        )
    }
}
