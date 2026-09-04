// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.data.source.local.room.DataTaskCancellationDecision
import com.valhalla.thor.data.source.local.room.DataTaskSnapshot
import com.valhalla.thor.domain.model.DataTaskInterruption
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.StoredDataDestination
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataTaskCancellationCoordinatorTest {

    @Test
    fun `cancel persists first then drops transient secrets and interrupts only active task`() =
        runTest {
            val events = mutableListOf<String>()
            val coordinator = DataTaskCancellationCoordinator(
                requestCancellation = {
                    events += "persist"
                    DataTaskCancellationDecision.InterruptActive(snapshot(), activeItemOrdinal = 0)
                },
                dropArchiveKey = { events += "drop-key" },
                dropRestoreSource = { events += "drop-source" },
                cancelActive = {
                    events += "interrupt:$it"
                    true
                },
                wakeQueue = { events += "wake" },
            )

            val decision = coordinator.cancel(TASK_ID)

            assertTrue(decision is DataTaskCancellationDecision.InterruptActive)
            assertEquals(
                listOf("persist", "drop-key", "drop-source", "interrupt:$TASK_ID", "wake"),
                events,
            )
        }

    @Test
    fun `settled cancellation wakes without interrupting an unrelated child`() = runTest {
        var interrupted = false
        var woke = false
        val coordinator = DataTaskCancellationCoordinator(
            requestCancellation = { DataTaskCancellationDecision.Settled(snapshot()) },
            dropArchiveKey = {},
            dropRestoreSource = {},
            cancelActive = {
                interrupted = true
                false
            },
            wakeQueue = { woke = true },
        )

        coordinator.cancel(TASK_ID)

        assertFalse(interrupted)
        assertTrue(woke)
    }

    private fun snapshot() = DataTaskSnapshot(
        taskId = TASK_ID,
        queueSequence = 1,
        payloadSchemaVersion = 1,
        kind = DataTaskKind.APP_EXPORT,
        state = DataTaskState.CANCEL_REQUESTED,
        targetKey = "package:com.example",
        detail = StoredDataTaskDetail.AppExport(
            requestedFormat = com.valhalla.thor.domain.model.BundleFormat.APK,
            destination = StoredDataDestination.Downloads,
            namingLabel = "Example",
            publicationPolicy = com.valhalla.thor.domain.model.DataTaskPublicationPolicy.PUBLIC_DOCUMENT,
            deterministicStagingIdentity = "stage-91",
        ),
        stage = null,
        completed = 0,
        total = 0,
        attemptCount = 1,
        interruption = DataTaskInterruption.NONE,
        resultCode = null,
        cancelRequestedAtEpochMs = 1,
        createdAtEpochMs = 1,
        claimedAtEpochMs = 1,
        startedAtEpochMs = 1,
        updatedAtEpochMs = 1,
        terminalAtEpochMs = null,
        retainUntilEpochMs = null,
        acknowledgedAtEpochMs = null,
        items = emptyList(),
        outputs = emptyList(),
    )

    private companion object {
        val TASK_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000091")
    }
}
