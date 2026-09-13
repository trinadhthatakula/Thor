// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.data.source.local.room.DataTaskCancellationDecision
import com.valhalla.thor.data.source.local.room.DataTaskDao
import com.valhalla.thor.data.service.ServiceStartResult
import com.valhalla.thor.domain.model.DataTaskKind
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.annotation.Single

internal interface DataTaskCancellationActions {
    suspend fun request(taskId: UUID): DataTaskCancellationDecision
    fun dropArchiveKey(taskId: UUID)
    fun dropRestoreSource(taskId: UUID)
    fun cancelActive(taskId: UUID): Boolean
    fun wakeQueue(taskId: UUID): ServiceStartResult
    suspend fun settleStaleClaim(
        decision: DataTaskCancellationDecision.InterruptActive,
    ): Boolean

    suspend fun cleanupCancelledRestoreSource(taskId: UUID)
    suspend fun cleanupCancelledShare(taskId: UUID)
}

@Single
internal class RoomDataTaskCancellationActions(
    dao: DataTaskDao,
    private val keys: ArchiveKeyHolder,
    private val restoreSources: RestoreSourceGrantHolder,
    private val restoreSourceStager: RestoreSourceStager,
    private val owners: DataTaskOwnerRegistry,
    private val wakeSignal: DataQueueWakeSignal,
    private val shareCleanup: SharePrepareCleanup,
) : DataTaskCancellationActions {
    private val store = DataTaskStore(dao)

    override suspend fun request(taskId: UUID): DataTaskCancellationDecision =
        store.requestCancellation(taskId, System.currentTimeMillis())

    override fun dropArchiveKey(taskId: UUID) = keys.drop(taskId.toString())
    override fun dropRestoreSource(taskId: UUID) = restoreSources.dropTask(taskId)
    override fun cancelActive(taskId: UUID): Boolean = owners.cancelActive(taskId)
    override fun wakeQueue(taskId: UUID): ServiceStartResult = wakeSignal.wake(taskId)

    override suspend fun cleanupCancelledRestoreSource(taskId: UUID) {
        if (taskId in store.uncommittedRestoreSourceCleanupTaskIds()) {
            restoreSourceStager.discardUncommittedTaskSources(taskId)
        }
    }

    override suspend fun cleanupCancelledShare(taskId: UUID) {
        if (store.loadTask(taskId)?.kind == DataTaskKind.SHARE_PREPARE) shareCleanup.cleanup(taskId)
    }

    override suspend fun settleStaleClaim(
        decision: DataTaskCancellationDecision.InterruptActive,
    ): Boolean {
        val taskId = decision.snapshot.taskId
        val taskClaimToken = decision.taskClaimToken ?: return false
        if (!owners.reserveStaleSettlement(taskId)) return false
        var settled = false
        try {
            val itemOrdinal = decision.activeItemOrdinal
            val itemClaimToken = decision.itemClaimToken
            settled = if (itemOrdinal != null && itemClaimToken != null) {
                store.settleClaimTimeout(
                    taskId = taskId,
                    taskClaimToken = taskClaimToken,
                    itemOrdinal = itemOrdinal,
                    itemClaimToken = itemClaimToken,
                    nowMs = System.currentTimeMillis(),
                )
            } else {
                store.settleClaimAcquisitionFailure(taskClaimToken, System.currentTimeMillis())
            }
            return settled
        } finally {
            owners.finishStaleSettlement(taskId, settled)
        }
    }
}

/** Applies task cancellation without ever widening it to the WorkManager chain or the whole queue. */
@Single
class DataTaskCancellationCoordinator internal constructor(
    private val actions: DataTaskCancellationActions,
) {
    internal constructor(
        requestCancellation: suspend (UUID) -> DataTaskCancellationDecision,
        dropArchiveKey: (UUID) -> Unit,
        dropRestoreSource: (UUID) -> Unit,
        cancelActive: (UUID) -> Boolean,
        wakeQueue: (UUID) -> ServiceStartResult,
        settleStaleClaim: suspend (DataTaskCancellationDecision.InterruptActive) -> Boolean,
        cleanupCancelledRestoreSource: suspend (UUID) -> Unit = {},
        cleanupCancelledShare: suspend (UUID) -> Unit = {},
    ) : this(
        object : DataTaskCancellationActions {
            override suspend fun request(taskId: UUID) = requestCancellation(taskId)
            override fun dropArchiveKey(taskId: UUID) = dropArchiveKey(taskId)
            override fun dropRestoreSource(taskId: UUID) = dropRestoreSource(taskId)
            override fun cancelActive(taskId: UUID) = cancelActive(taskId)
            override fun wakeQueue(taskId: UUID) = wakeQueue(taskId)
            override suspend fun settleStaleClaim(
                decision: DataTaskCancellationDecision.InterruptActive,
            ) = settleStaleClaim(decision)

            override suspend fun cleanupCancelledRestoreSource(taskId: UUID) =
                cleanupCancelledRestoreSource(taskId)

            override suspend fun cleanupCancelledShare(taskId: UUID) = cleanupCancelledShare(taskId)
        }
    )

    suspend fun cancel(taskId: UUID): DataTaskCancellationDecision {
        val decision = actions.request(taskId)
        actions.dropArchiveKey(taskId)
        actions.dropRestoreSource(taskId)
        val localOwner = decision is DataTaskCancellationDecision.InterruptActive &&
                actions.cancelActive(taskId)
        actions.wakeQueue(taskId)
        if (decision is DataTaskCancellationDecision.InterruptActive && !localOwner) {
            actions.settleStaleClaim(decision)
        }
        if (!localOwner) {
            // The child normally owns cleanup; unowned and stale settlements have no child left.
            withContext(NonCancellable) {
                withTimeout(2.seconds) { actions.cleanupCancelledShare(taskId) }
            }
            actions.cleanupCancelledRestoreSource(taskId)
        }
        return decision
    }
}
