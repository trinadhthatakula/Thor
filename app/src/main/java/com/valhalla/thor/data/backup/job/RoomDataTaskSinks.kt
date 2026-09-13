// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import java.util.UUID

/** Claim-fenced checkpoint writer for one running task item. */
internal class RoomDataTaskCheckpointSink(
    private val store: DataTaskStore,
    private val taskId: UUID,
    private val taskClaimToken: String,
    private val itemOrdinal: Int,
    private val itemClaimToken: String,
    private val leaseUntilMs: () -> Long,
) : DataTaskCheckpointSink {
    override suspend fun persist(checkpoint: DataTaskCheckpoint): DataTaskSinkWrite =
        if (
            store.checkpointClaimedTask(
                taskId = taskId,
                taskClaimToken = taskClaimToken,
                itemOrdinal = itemOrdinal,
                itemClaimToken = itemClaimToken,
                checkpoint = checkpoint,
                leaseUntilMs = leaseUntilMs(),
            )
        ) {
            DataTaskSinkWrite.APPLIED
        } else {
            DataTaskSinkWrite.OWNERSHIP_LOST
        }
}

/** Atomic claim-fenced outcome writer for one running task item. */
internal class RoomDataTaskResultSink(
    private val store: DataTaskStore,
    private val taskId: UUID,
    private val taskClaimToken: String,
    private val itemOrdinal: Int,
    private val itemClaimToken: String,
    private val nowMs: () -> Long,
) : DataTaskResultSink<DataTaskSinkWrite> {
    override suspend fun persist(outcome: DataTaskRunOutcome): DataTaskSinkWrite =
        if (
            store.settleClaimedTask(
                taskId = taskId,
                taskClaimToken = taskClaimToken,
                itemOrdinal = itemOrdinal,
                itemClaimToken = itemClaimToken,
                outcome = outcome,
                nowMs = nowMs(),
            )
        ) {
            DataTaskSinkWrite.APPLIED
        } else {
            DataTaskSinkWrite.OWNERSHIP_LOST
        }
}
