// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskInterruption
import com.valhalla.thor.domain.model.DataTaskItemState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import java.util.UUID

data class NewDataTaskItem(
    val ordinal: Int,
    val packageName: String,
    val displayLabel: String?,
    val deterministicStagingIdentity: String,
)

data class NewDataTaskRow(
    val taskId: String,
    val payloadSchemaVersion: Int,
    val kind: DataTaskKind,
    val targetKey: String,
    val initialState: DataTaskState,
    val detail: StoredDataTaskDetail,
    val items: List<NewDataTaskItem>,
    val createdAtEpochMs: Long,
)

data class DataTaskItemSnapshot(
    val ordinal: Int,
    val packageName: String,
    val displayLabel: String?,
    val state: DataTaskItemState,
    val attemptCount: Int,
    val resultCode: DataTaskResultCode?,
    val deterministicStagingIdentity: String,
    val startedAtEpochMs: Long?,
    val finishedAtEpochMs: Long?,
)

data class DataTaskOutputSnapshot(
    val outputId: UUID,
    val itemOrdinal: Int,
    val privateRelativePath: String?,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val state: DataTaskOutputState,
    val expiresAtEpochMs: Long?,
)

data class DataTaskSnapshot(
    val taskId: UUID,
    val queueSequence: Long,
    val payloadSchemaVersion: Int,
    val kind: DataTaskKind,
    val state: DataTaskState,
    val targetKey: String,
    val detail: StoredDataTaskDetail,
    val stage: DataTaskStage?,
    val completed: Long,
    val total: Long,
    val attemptCount: Int,
    val interruption: DataTaskInterruption,
    val resultCode: DataTaskResultCode?,
    val cancelRequestedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val claimedAtEpochMs: Long?,
    val startedAtEpochMs: Long?,
    val updatedAtEpochMs: Long,
    val terminalAtEpochMs: Long?,
    val retainUntilEpochMs: Long?,
    val acknowledgedAtEpochMs: Long?,
    val items: List<DataTaskItemSnapshot>,
    val outputs: List<DataTaskOutputSnapshot>,
    val warnings: List<String> = emptyList(),
    val failureReason: String? = null,
)

data class ClaimedDataTask(
    val taskId: UUID,
    val queueSequence: Long,
    val payloadSchemaVersion: Int,
    val kind: DataTaskKind,
    val targetKey: String,
    val detail: StoredDataTaskDetail,
    val serviceSessionToken: String,
    val claimToken: String,
    val claimLeaseExpiresAtEpochMs: Long,
    val attemptCount: Int,
    val lastCheckpoint: DataTaskCheckpoint?,
)

data class ClaimedDataTaskItem(
    val taskId: UUID,
    val ordinal: Int,
    val packageName: String,
    val displayLabel: String?,
    val deterministicStagingIdentity: String,
    val claimToken: String,
    val claimLeaseExpiresAtEpochMs: Long,
    val attemptCount: Int,
)

/** Task and first item ownership committed by one Room transaction. */
data class ClaimedDataTaskWork(
    val task: ClaimedDataTask,
    val item: ClaimedDataTaskItem,
)

sealed interface DataTaskRecovery {
    data object Resume : DataTaskRecovery
    data object Cancelled : DataTaskRecovery
    data object WaitingForAuthentication : DataTaskRecovery
    data object WaitingForSource : DataTaskRecovery
    data class InterruptedReview(
        val breadcrumb: RestoreMutationBreadcrumb?,
        val resultCode: DataTaskResultCode = DataTaskResultCode("DESTRUCTIVE_RESTORE_REVIEW"),
    ) : DataTaskRecovery

    data class Failed(val resultCode: DataTaskResultCode) : DataTaskRecovery
}

data class DataTaskRecoveryCandidate(
    val taskId: UUID,
    val kind: DataTaskKind,
    val interruptedItemOrdinal: Int?,
    val previousServiceSessionToken: String,
    val destructiveStarted: Boolean,
    val restoreMutationBreadcrumb: RestoreMutationBreadcrumb?,
    val recovery: DataTaskRecovery,
)

sealed interface DataTaskCancellationDecision {
    data object NotFound : DataTaskCancellationDecision
    data class AlreadyTerminal(val snapshot: DataTaskSnapshot) : DataTaskCancellationDecision
    data class Settled(val snapshot: DataTaskSnapshot) : DataTaskCancellationDecision
    data class InterruptActive(
        val snapshot: DataTaskSnapshot,
        val activeItemOrdinal: Int?,
        val taskClaimToken: String? = null,
        val itemClaimToken: String? = null,
    ) : DataTaskCancellationDecision
}
