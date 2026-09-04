// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.data.source.local.room.DataTaskCancellationDecision
import com.valhalla.thor.data.source.local.room.DataTaskDao
import java.util.UUID
import org.koin.core.annotation.Single

internal interface DataTaskCancellationActions {
    suspend fun request(taskId: UUID): DataTaskCancellationDecision
    fun dropArchiveKey(taskId: UUID)
    fun dropRestoreSource(taskId: UUID)
    fun cancelActive(taskId: UUID): Boolean
    fun wakeQueue(taskId: UUID)
}

@Single
internal class RoomDataTaskCancellationActions(
    dao: DataTaskDao,
    private val keys: ArchiveKeyHolder,
    private val restoreSources: RestoreSourceGrantHolder,
    private val owners: DataTaskOwnerRegistry,
    private val wakeSignal: DataQueueWakeSignal,
) : DataTaskCancellationActions {
    private val store = DataTaskStore(dao)

    override suspend fun request(taskId: UUID): DataTaskCancellationDecision =
        store.requestCancellation(taskId, System.currentTimeMillis())

    override fun dropArchiveKey(taskId: UUID) = keys.drop(taskId.toString())
    override fun dropRestoreSource(taskId: UUID) = restoreSources.dropTask(taskId)
    override fun cancelActive(taskId: UUID): Boolean = owners.cancelActive(taskId)
    override fun wakeQueue(taskId: UUID) {
        wakeSignal.wake(taskId)
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
        wakeQueue: (UUID) -> Unit,
    ) : this(
        object : DataTaskCancellationActions {
            override suspend fun request(taskId: UUID) = requestCancellation(taskId)
            override fun dropArchiveKey(taskId: UUID) = dropArchiveKey(taskId)
            override fun dropRestoreSource(taskId: UUID) = dropRestoreSource(taskId)
            override fun cancelActive(taskId: UUID) = cancelActive(taskId)
            override fun wakeQueue(taskId: UUID) = wakeQueue(taskId)
        }
    )

    suspend fun cancel(taskId: UUID): DataTaskCancellationDecision {
        val decision = actions.request(taskId)
        actions.dropArchiveKey(taskId)
        actions.dropRestoreSource(taskId)
        if (decision is DataTaskCancellationDecision.InterruptActive) {
            actions.cancelActive(taskId)
        }
        actions.wakeQueue(taskId)
        return decision
    }
}
