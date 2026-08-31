// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.Operation
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import com.valhalla.thor.domain.repository.SweepCreateResult
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class SweepQueueCancellerTest {

    @Test
    fun `all nonterminal requests are marked cancelled before WorkManager cancellation`() =
        runTest {
            val events = mutableListOf<String>()
            val store = FakeStore(events).apply {
                seed(stored())
                seed(stored())
            }
            val workManager = FakeQueueWorkManager(events)
            val canceller = canceller(store, workManager)

            canceller.cancelQueue()

            assertEquals(listOf("room", "work-manager"), events)
            assertEquals(
                setOf(StoredSweepTerminal.CANCELLED),
                store.rows.values.mapNotNull { it.terminalState }.toSet(),
            )
        }

    @Test
    fun `terminal rows are not overwritten`() = runTest {
        val store = FakeStore().apply {
            seed(
                stored(
                    terminal = StoredSweepTerminal.SUCCEEDED,
                    succeeded = 3,
                    unresolved = 0,
                )
            )
            seed(stored())
        }
        val terminalBefore = store.rows.values.first { it.terminalState != null }

        canceller(store).cancelQueue()

        assertEquals(terminalBefore, store.rows.getValue(terminalBefore.requestId))
    }

    @Test
    fun `queued request becomes cancelled with all targets unresolved`() = runTest {
        val store = FakeStore().apply { seed(stored()) }

        canceller(store).cancelQueue()

        val cancelled = store.rows.values.single()
        assertEquals(StoredSweepTerminal.CANCELLED, cancelled.terminalState)
        assertEquals(3, cancelled.unresolved)
        assertEquals(NOW, cancelled.terminalAtEpochMs)
    }

    @Test
    fun `running request preserves counts and derives unresolved remainder`() = runTest {
        val store = FakeStore().apply {
            seed(stored(succeeded = 1, failed = 1, busy = 0))
        }

        canceller(store).cancelQueue()

        val cancelled = store.rows.values.single()
        assertEquals(1, cancelled.succeeded)
        assertEquals(1, cancelled.failed)
        assertEquals(0, cancelled.busy)
        assertEquals(1, cancelled.unresolved)
    }

    @Test
    fun `repeated queue cancellation is idempotent`() = runTest {
        val store = FakeStore().apply { seed(stored()) }
        val workManager = FakeQueueWorkManager()
        val canceller = canceller(store, workManager)

        canceller.cancelQueue()
        val afterFirst = store.rows.values.single()
        canceller.cancelQueue()

        assertEquals(afterFirst, store.rows.values.single())
        assertEquals(listOf(1, 0), store.cancelledCounts)
        assertEquals(2, workManager.calls)
    }

    @Test
    fun `process gate remains held until WorkManager cancellation settles`() = runTest {
        val store = FakeStore().apply { seed(stored()) }
        val release = CompletableDeferred<Unit>()
        val workManager = FakeQueueWorkManager(release = release)
        val gate = PrivilegeSweepProcessGate()
        val canceller = SweepQueueCanceller(store, FakeClock(), gate, workManager)

        val cancellation = async { canceller.cancelQueue() }
        workManager.started.await()
        val nextGateOwner = async { gate.serialized { "entered" } }
        runCurrent()

        assertFalse(nextGateOwner.isCompleted)
        release.complete(Unit)
        advanceUntilIdle()

        cancellation.await()
        assertEquals("entered", nextGateOwner.await())
    }

    @Test
    fun `operation await remains suspended until the future settles`() = runTest {
        val operation = PendingOperation()

        val awaiting = async { operation.awaitCompletion() }
        runCurrent()

        assertFalse(awaiting.isCompleted)
        operation.settle()
        advanceUntilIdle()

        assertTrue(awaiting.isCancelled)
    }

    @Test
    fun `caller cancellation cannot release gate before WorkManager cancellation settles`() =
        runTest {
            val store = FakeStore().apply { seed(stored()) }
            val release = CompletableDeferred<Unit>()
            val workManager = FakeQueueWorkManager(release = release)
            val gate = PrivilegeSweepProcessGate()
            val canceller = SweepQueueCanceller(store, FakeClock(), gate, workManager)

            val cancellation = async { canceller.cancelQueue() }
            workManager.started.await()
            cancellation.cancel()
            val nextGateOwner = async { gate.serialized { "entered" } }
            runCurrent()

            assertFalse(nextGateOwner.isCompleted)
            release.complete(Unit)
            advanceUntilIdle()

            assertTrue(cancellation.isCancelled)
            assertEquals("entered", nextGateOwner.await())
        }

    private fun canceller(
        store: FakeStore,
        workManager: FakeQueueWorkManager = FakeQueueWorkManager(),
    ) = SweepQueueCanceller(
        store = store,
        clock = FakeClock(),
        gate = PrivilegeSweepProcessGate(),
        workManager = workManager,
    )

    private fun stored(
        requestId: UUID = UUID.randomUUID(),
        terminal: StoredSweepTerminal? = null,
        succeeded: Int = 0,
        failed: Int = 0,
        busy: Int = 0,
        unresolved: Int = 0,
    ) = StoredPrivilegeSweep(
        requestId = requestId,
        workId = UUID.randomUUID(),
        operation = PrivilegeSweepOperation.CLEAR_CACHE,
        freezerMode = null,
        userId = 0,
        source = PrivilegeSweepSource.MAIN,
        createdAtEpochMs = NOW - 1,
        targets = listOf("one.pkg", "two.pkg", "three.pkg"),
        terminalState = terminal,
        succeeded = succeeded,
        failed = failed,
        busy = busy,
        unresolved = unresolved,
        terminalAtEpochMs = terminal?.let { NOW - 1 },
        retainUntilEpochMs = terminal?.let { NOW + 1 },
    )

    private class FakeClock : PrivilegeSweepClock {
        override fun nowMs(): Long = NOW
    }

    private class FakeQueueWorkManager(
        private val events: MutableList<String> = mutableListOf(),
        private val release: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    ) : SweepQueueWorkManager {
        val started = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun cancelQueue() {
            calls++
            events += "work-manager"
            started.complete(Unit)
            release.await()
        }
    }

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
        private val events: MutableList<String> = mutableListOf(),
    ) : PrivilegeSweepStore {
        val rows = linkedMapOf<UUID, StoredPrivilegeSweep>()
        val cancelledCounts = mutableListOf<Int>()
        private val retained = MutableStateFlow<List<StoredPrivilegeSweep>>(emptyList())

        fun seed(snapshot: StoredPrivilegeSweep) {
            rows[snapshot.requestId] = snapshot
            retained.value = rows.values.toList()
        }

        override suspend fun cancelAllNonterminal(nowMs: Long): List<UUID> {
            events += "room"
            val requestIds = rows.values
                .filter { it.terminalState == null }
                .map { it.requestId }
            requestIds.forEach { requestId ->
                val current = rows.getValue(requestId)
                rows[requestId] = current.copy(
                    terminalState = StoredSweepTerminal.CANCELLED,
                    unresolved = current.targets.size -
                            current.succeeded - current.failed - current.busy,
                    terminalAtEpochMs = nowMs,
                    retainUntilEpochMs = nowMs + 1,
                )
            }
            cancelledCounts += requestIds.size
            retained.value = rows.values.toList()
            return requestIds
        }

        override suspend fun createOrFindEquivalent(
            snapshot: NewPrivilegeSweepSnapshot,
        ): SweepCreateResult = error("unused")

        override suspend fun load(requestId: UUID): StoredPrivilegeSweep? = rows[requestId]

        override fun observe(requestId: UUID): Flow<StoredPrivilegeSweep?> = error("unused")

        override fun observeRetained(): Flow<List<StoredPrivilegeSweep>> = retained

        override fun observeRetained(
            source: PrivilegeSweepSource,
        ): Flow<List<StoredPrivilegeSweep>> = error("unused")

        override suspend fun resetForRun(requestId: UUID): StoredPrivilegeSweep? = error("unused")

        override suspend fun recordAttempt(
            requestId: UUID,
            outcome: SweepAttemptOutcome,
        ): Boolean = error("unused")

        override suspend fun finish(
            requestId: UUID,
            terminal: StoredSweepTerminal,
            nowMs: Long,
        ): Boolean = error("unused")

        override suspend fun delete(requestId: UUID) = error("unused")

        override suspend fun deleteExpired(nowMs: Long): Int = error("unused")
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
