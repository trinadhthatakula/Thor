// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskStage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DataSyncWakeLockTest {

    @Test
    fun `checkpoint renews the lease only after ownership-fenced persistence succeeds`() = runTest {
        val events = mutableListOf<String>()
        val applied = renewingDataSyncCheckpointSink(
            delegate = DataTaskCheckpointSink {
                events += "persist"
                DataTaskSinkWrite.APPLIED
            },
            renewLease = { events += "renew" },
        )
        val rejected = renewingDataSyncCheckpointSink(
            delegate = DataTaskCheckpointSink {
                events += "reject"
                DataTaskSinkWrite.OWNERSHIP_LOST
            },
            renewLease = { events += "must-not-renew" },
        )

        applied.persist(CHECKPOINT)
        rejected.persist(CHECKPOINT)

        assertEquals(listOf("persist", "renew", "reject"), events)
    }

    private companion object {
        val CHECKPOINT = DataTaskCheckpoint(
            stage = DataTaskStage.CAPTURING,
            completed = 1,
            total = 2,
            activeItemOrdinal = 0,
            activeItemLabel = "Example",
            destructiveStarted = false,
            restoreMutationBreadcrumb = null,
            recordedAtEpochMs = 1,
        )
    }
}
