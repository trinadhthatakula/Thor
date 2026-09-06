// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.Operation
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.valhalla.thor.data.service.ServiceStartResult
import com.valhalla.thor.domain.repository.PrivilegeSweepCancellationDecision
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepQueueCancellerTest {

    @Test
    fun `cancellation forwards the exact displayed request identity`() = runTest {
        val requestId = UUID.randomUUID()
        val cancelled = mutableListOf<UUID>()

        SweepQueueCanceller(cancellation(cancelled)).cancel(requestId)

        assertEquals(listOf(requestId), cancelled)
    }

    @Test
    fun `operation await remains suspended until WorkManager future settles`() = runTest {
        val operation = PendingOperation()

        val awaiting = async { operation.awaitCompletion() }
        runCurrent()

        assertFalse(awaiting.isCompleted)
        operation.settle()
        advanceUntilIdle()

        assertTrue(awaiting.isCancelled)
    }

    private fun cancellation(cancelled: MutableList<UUID>) =
        PrivilegeSweepCancellationCoordinator(
            requestCancellation = { requestId ->
                cancelled += requestId
                PrivilegeSweepCancellationDecision.Settled(requestId)
            },
            cancelActive = { false },
            wake = { ServiceStartResult.AlreadyRunning },
            reconcileStaleClaim = {},
        )

    private class PendingOperation : Operation {
        private val state = MutableLiveData<Operation.State>()
        private val future = SettableFuture.create<Operation.State.SUCCESS>()

        override fun getState(): LiveData<Operation.State> = state
        override fun getResult(): ListenableFuture<Operation.State.SUCCESS> = future

        fun settle() {
            future.cancel(false)
        }
    }
}
