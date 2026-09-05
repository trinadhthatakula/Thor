// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.Operation
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.valhalla.thor.data.service.ServiceStartResult
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeSweepCancellationDecision
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import com.valhalla.thor.domain.repository.SweepCreateResult
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepQueueCancellerTest {

    @Test
    fun `deprecated adapter without displayed identity never guesses a retained request`() = runTest {
        val terminal = stored(terminal = StoredSweepTerminal.SUCCEEDED)
        val presented = stored()
        val later = stored()
        val store = FakeStore(listOf(terminal, presented, later))
        val cancelled = mutableListOf<UUID>()
        val canceller = SweepQueueCanceller(store, cancellation(cancelled))

        canceller.cancelQueue()

        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `deprecated adapter does nothing when no nonterminal request is presented`() = runTest {
        val store = FakeStore(listOf(stored(terminal = StoredSweepTerminal.CANCELLED)))
        val cancelled = mutableListOf<UUID>()

        SweepQueueCanceller(store, cancellation(cancelled)).cancelQueue()

        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `displayed A cancellation cannot select newer B`() = runTest {
        val a = stored()
        val b = stored()
        val cancelled = mutableListOf<UUID>()
        SweepQueueCanceller(FakeStore(listOf(b, a)), cancellation(cancelled)).cancel(a.requestId)
        assertEquals(listOf(a.requestId), cancelled)
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

    private fun stored(
        terminal: StoredSweepTerminal? = null,
    ) = StoredPrivilegeSweep(
        requestId = UUID.randomUUID(),
        workId = UUID.randomUUID(),
        operation = PrivilegeSweepOperation.CLEAR_CACHE,
        freezerMode = null,
        userId = 0,
        source = PrivilegeSweepSource.MAIN,
        createdAtEpochMs = 1L,
        targets = listOf("com.example.app"),
        terminalState = terminal,
        succeeded = if (terminal == StoredSweepTerminal.SUCCEEDED) 1 else 0,
        failed = 0,
        busy = 0,
        unresolved = if (terminal == null) 1 else 0,
        terminalAtEpochMs = terminal?.let { 2L },
        retainUntilEpochMs = terminal?.let { 3L },
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

    private class FakeStore(
        rows: List<StoredPrivilegeSweep>,
    ) : PrivilegeSweepStore {
        private val retained = MutableStateFlow(rows)

        override fun observeRetained(): Flow<List<StoredPrivilegeSweep>> = retained
        override suspend fun createOrFindEquivalent(snapshot: NewPrivilegeSweepSnapshot): SweepCreateResult = error("unused")
        override suspend fun load(requestId: UUID): StoredPrivilegeSweep? = error("unused")
        override fun observe(requestId: UUID): Flow<StoredPrivilegeSweep?> = error("unused")
        override fun observeRetained(source: PrivilegeSweepSource): Flow<List<StoredPrivilegeSweep>> = error("unused")
        override suspend fun resetForRun(requestId: UUID): StoredPrivilegeSweep? = error("unused")
        override suspend fun recordAttempt(requestId: UUID, outcome: SweepAttemptOutcome): Boolean = error("unused")
        override suspend fun finish(requestId: UUID, terminal: StoredSweepTerminal, nowMs: Long): Boolean = error("unused")
        override suspend fun cancelAllNonterminal(nowMs: Long): List<UUID> = error("unused")
        override suspend fun delete(requestId: UUID) = error("unused")
        override suspend fun deleteExpired(nowMs: Long): Int = error("unused")
    }
}
