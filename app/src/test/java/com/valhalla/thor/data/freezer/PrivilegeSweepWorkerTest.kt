// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import com.valhalla.thor.domain.repository.SweepCreateResult
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegeSweepWorkerTest {

    @Test
    fun `missing request id fails permanently without touching Room`() = runTest {
        val store = FakeStore()

        val result = runner(store).run(null)

        assertTrue(result is PrivilegeSweepRunOutcome.PermanentFailure)
        assertEquals(0, store.loadCalls)
    }

    @Test
    fun `malformed request id fails permanently without touching Room`() = runTest {
        val store = FakeStore()

        val result = runner(store).run("not-a-uuid")

        assertTrue(result is PrivilegeSweepRunOutcome.PermanentFailure)
        assertEquals(0, store.loadCalls)
    }

    @Test
    fun `missing snapshot fails permanently without retry`() = runTest {
        val store = FakeStore()

        val result = runner(store).run(UUID.randomUUID().toString())

        assertTrue(result is PrivilegeSweepRunOutcome.PermanentFailure)
        assertEquals(1, store.loadCalls)
    }

    @Test
    fun `genuine rerun resets aggregate counts before first attempt`() = runTest {
        val events = mutableListOf<String>()
        val initial = stored(succeeded = 1, failed = 1, busy = 1)
        val store = FakeStore(events).apply { seed(initial) }
        val seenSnapshots = mutableListOf<StoredPrivilegeSweep>()
        val executor = PrivilegeSweepItemExecutor { snapshot, packageName ->
            events += "execute:$packageName"
            seenSnapshots += snapshot
            SweepAttemptOutcome.SUCCEEDED
        }

        val result = runner(store, executor).run(initial.requestId.toString())

        assertEquals(PrivilegeSweepRunOutcome.Success, result)
        assertTrue(events.indexOf("reset") < events.indexOf("execute:one.pkg"))
        assertTrue(seenSnapshots.all { it.succeeded == 0 && it.failed == 0 && it.busy == 0 })
        assertEquals(3, store.row(initial.requestId).succeeded)
        assertEquals(0, store.row(initial.requestId).failed)
        assertEquals(0, store.row(initial.requestId).busy)
    }

    @Test
    fun `busy and failed attempts are persisted and later packages continue`() = runTest {
        val initial = stored()
        val store = FakeStore().apply { seed(initial) }
        val executed = mutableListOf<String>()
        val outcomes = mapOf(
            "one.pkg" to SweepAttemptOutcome.BUSY,
            "two.pkg" to SweepAttemptOutcome.FAILED,
            "three.pkg" to SweepAttemptOutcome.SUCCEEDED,
        )
        val progress = mutableListOf<ThorJobProgress>()
        val notices = mutableListOf<StoredPrivilegeSweep>()

        val result = runner(
            store,
            PrivilegeSweepItemExecutor { _, packageName ->
                executed += packageName
                outcomes.getValue(packageName)
            },
        ).run(
            initial.requestId.toString(),
            publish = progress::add,
            noteResult = notices::add,
        )

        assertEquals(PrivilegeSweepRunOutcome.Success, result)
        assertEquals(initial.targets, executed)
        val terminal = store.row(initial.requestId)
        assertEquals(StoredSweepTerminal.PARTIAL, terminal.terminalState)
        assertEquals(1, terminal.succeeded)
        assertEquals(1, terminal.failed)
        assertEquals(1, terminal.busy)
        assertEquals(0, terminal.unresolved)
        assertEquals(listOf(1L, 2L, 3L), progress.map(ThorJobProgress::completed))
        assertEquals(listOf("one.pkg", "two.pkg", "three.pkg"), progress.map(ThorJobProgress::label))
        assertTrue(progress.all { it.stage == ThorJobStage.ACTING && it.total == 3L })
        assertEquals(listOf(terminal), notices)
    }

    @Test
    fun `terminal succeeds only when every target succeeds`() = runTest {
        val initial = stored()
        val store = FakeStore().apply { seed(initial) }

        val result = runner(
            store,
            PrivilegeSweepItemExecutor { _, _ -> SweepAttemptOutcome.SUCCEEDED },
        ).run(initial.requestId.toString())

        assertEquals(PrivilegeSweepRunOutcome.Success, result)
        val terminal = store.row(initial.requestId)
        assertEquals(StoredSweepTerminal.SUCCEEDED, terminal.terminalState)
        assertEquals(3, terminal.succeeded)
        assertEquals(0, terminal.failed)
        assertEquals(0, terminal.busy)
        assertEquals(0, terminal.unresolved)
    }

    @Test
    fun `record attempt losing to queue cancellation stops later package actions`() = runTest {
        val initial = stored()
        val store = FakeStore().apply {
            seed(initial)
            cancelWhenRecordingAttempt = 1
        }
        val executed = mutableListOf<String>()
        val notices = mutableListOf<StoredPrivilegeSweep>()

        val result = runner(
            store,
            PrivilegeSweepItemExecutor { _, packageName ->
                executed += packageName
                SweepAttemptOutcome.SUCCEEDED
            },
        ).run(initial.requestId.toString(), noteResult = notices::add)

        assertTrue(result is PrivilegeSweepRunOutcome.PermanentFailure)
        assertEquals(listOf("one.pkg"), executed)
        val terminal = store.row(initial.requestId)
        assertEquals(StoredSweepTerminal.CANCELLED, terminal.terminalState)
        assertEquals(3, terminal.unresolved)
        assertEquals(listOf(terminal), notices)
        assertFalse(store.events.any { it.startsWith("finish:") })
    }

    @Test
    fun `queue cancellation before next admission stops later package actions`() = runTest {
        val initial = stored()
        val store = FakeStore().apply {
            seed(initial)
            cancelBeforeAdmissionAfterAttempts = 1
        }
        val executed = mutableListOf<String>()
        val notices = mutableListOf<StoredPrivilegeSweep>()

        val result = runner(
            store,
            PrivilegeSweepItemExecutor { _, packageName ->
                executed += packageName
                SweepAttemptOutcome.SUCCEEDED
            },
        ).run(initial.requestId.toString(), noteResult = notices::add)

        assertTrue(result is PrivilegeSweepRunOutcome.PermanentFailure)
        assertEquals(listOf("one.pkg"), executed)
        val terminal = store.row(initial.requestId)
        assertEquals(StoredSweepTerminal.CANCELLED, terminal.terminalState)
        assertEquals(1, terminal.succeeded)
        assertEquals(2, terminal.unresolved)
        assertEquals(listOf(terminal), notices)
    }

    @Test
    fun `completed attempt is persisted before unmarked interruption leaves sweep replayable`() = runTest {
        val initial = stored()
        val store = FakeStore().apply { seed(initial) }
        val cancellation = CancellationException("stop after action")

        val worker = async {
            runner(
                store,
                PrivilegeSweepItemExecutor { _, _ ->
                    currentCoroutineContext().job.cancel(cancellation)
                    SweepAttemptOutcome.SUCCEEDED
                },
            ).run(initial.requestId.toString())
        }
        val thrown = runCatching { worker.await() }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(cancellation.message, thrown?.message)
        val interrupted = store.row(initial.requestId)
        assertEquals(null, interrupted.terminalState)
        assertEquals(1, interrupted.succeeded)
        assertEquals(0, interrupted.unresolved)
    }

    @Test
    fun `unmarked worker interruption is reset and replays canonical targets`() = runTest {
        val initial = stored()
        val store = FakeStore().apply { seed(initial) }
        val cancellation = CancellationException("scheduler stopped worker")
        val executed = mutableListOf<String>()
        var interruptFirstRun = true
        val executor = PrivilegeSweepItemExecutor { _, packageName ->
            executed += packageName
            if (interruptFirstRun) {
                currentCoroutineContext().job.cancel(cancellation)
            }
            SweepAttemptOutcome.SUCCEEDED
        }

        val firstRun = async {
            runner(store, executor).run(initial.requestId.toString())
        }
        val thrown = runCatching { firstRun.await() }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        val interrupted = store.row(initial.requestId)
        assertEquals(null, interrupted.terminalState)
        assertEquals(1, interrupted.succeeded)

        interruptFirstRun = false
        val replayed = runner(store, executor).run(initial.requestId.toString())

        assertEquals(PrivilegeSweepRunOutcome.Success, replayed)
        assertEquals(listOf("one.pkg", "one.pkg", "two.pkg", "three.pkg"), executed)
        val terminal = store.row(initial.requestId)
        assertEquals(StoredSweepTerminal.SUCCEEDED, terminal.terminalState)
        assertEquals(3, terminal.succeeded)
        assertEquals(0, terminal.failed)
        assertEquals(0, terminal.busy)
        assertEquals(0, terminal.unresolved)
    }

    @Test
    fun `cancellation during aggregate reset leaves the request replayable`() = runTest {
        val initial = stored(succeeded = 1, failed = 1, busy = 1)
        val cancellation = CancellationException("stop before replay")
        val store = FakeStore().apply {
            seed(initial)
            cancelOnReset = cancellation
        }
        val notices = mutableListOf<StoredPrivilegeSweep>()

        val thrown = runCatching {
            runner(store).run(initial.requestId.toString(), noteResult = notices::add)
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        val interrupted = store.row(initial.requestId)
        assertEquals(null, interrupted.terminalState)
        assertEquals(1, interrupted.succeeded)
        assertEquals(1, interrupted.failed)
        assertEquals(1, interrupted.busy)
        assertEquals(0, interrupted.unresolved)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `explicit queue cancellation preserves partial and unresolved counts`() = runTest {
        val initial = stored()
        val store = FakeStore().apply { seed(initial) }
        val cancellation = CancellationException("stop sweep")
        val notices = mutableListOf<StoredPrivilegeSweep>()
        var attempts = 0

        val thrown = runCatching {
            runner(
                store,
                PrivilegeSweepItemExecutor { _, _ ->
                    attempts++
                    if (attempts == 2) {
                        store.finish(
                            initial.requestId,
                            StoredSweepTerminal.CANCELLED,
                            NOW,
                        )
                        throw cancellation
                    }
                    SweepAttemptOutcome.SUCCEEDED
                },
            ).run(initial.requestId.toString(), noteResult = notices::add)
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        val terminal = store.row(initial.requestId)
        assertEquals(StoredSweepTerminal.CANCELLED, terminal.terminalState)
        assertEquals(1, terminal.succeeded)
        assertEquals(0, terminal.failed)
        assertEquals(0, terminal.busy)
        assertEquals(2, terminal.unresolved)
        assertEquals(listOf(terminal), notices)
    }

    @Test
    fun `engine failure terminalizes the request as failed without replaying it`() = runTest {
        val initial = stored()
        val store = FakeStore().apply { seed(initial) }
        val error = IllegalStateException("executor unavailable")

        val result = runner(
            store,
            PrivilegeSweepItemExecutor { _, _ -> throw error },
        ).run(initial.requestId.toString())

        assertTrue(result is PrivilegeSweepRunOutcome.PermanentFailure)
        val terminal = store.row(initial.requestId)
        assertEquals(StoredSweepTerminal.FAILED, terminal.terminalState)
        assertEquals(3, terminal.unresolved)
    }

    @Test
    fun `already terminal snapshot performs no package action or aggregate reset`() = runTest {
        val expected = mapOf(
            StoredSweepTerminal.SUCCEEDED to true,
            StoredSweepTerminal.PARTIAL to true,
            StoredSweepTerminal.CANCELLED to false,
            StoredSweepTerminal.FAILED to false,
        )

        expected.forEach { (terminal, isSuccess) ->
            val initial = stored(terminal = terminal, unresolved = 3)
            val store = FakeStore().apply { seed(initial) }
            var executions = 0
            val notices = mutableListOf<StoredPrivilegeSweep>()

            val result = runner(
                store,
                PrivilegeSweepItemExecutor { _, _ ->
                    executions++
                    SweepAttemptOutcome.SUCCEEDED
                },
            ).run(initial.requestId.toString(), noteResult = notices::add)

            assertEquals(0, executions)
            assertFalse(store.events.contains("reset"))
            assertEquals(isSuccess, result == PrivilegeSweepRunOutcome.Success)
            assertEquals(listOf(initial), notices)
        }
    }

    @Test
    fun `run outcome has no retry state`() = runTest {
        val successStore = FakeStore().apply { seed(stored()) }
        val success = runner(
            successStore,
            PrivilegeSweepItemExecutor { _, _ -> SweepAttemptOutcome.SUCCEEDED },
        ).run(successStore.rows.values.single().requestId.toString())
        val failure = runner(FakeStore()).run(null)

        assertEquals(PrivilegeSweepRunOutcome.Success, success)
        assertTrue(failure is PrivilegeSweepRunOutcome.PermanentFailure)
        listOf(success, failure).forEach { outcome ->
            when (outcome) {
                PrivilegeSweepRunOutcome.Success -> Unit
                is PrivilegeSweepRunOutcome.PermanentFailure -> Unit
            }
        }
    }

    private fun runner(
        store: FakeStore,
        executor: PrivilegeSweepItemExecutor = PrivilegeSweepItemExecutor { _, _ ->
            SweepAttemptOutcome.SUCCEEDED
        },
    ) = PrivilegeSweepRunner(
        store = store,
        executor = executor,
        clock = FakeClock,
        gate = PrivilegeSweepProcessGate(),
        ioDispatcher = UnconfinedTestDispatcher(),
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

    private object FakeClock : PrivilegeSweepClock {
        override fun nowMs(): Long = NOW
    }

    private class FakeStore(
        val events: MutableList<String> = mutableListOf(),
    ) : PrivilegeSweepStore {
        val rows = linkedMapOf<UUID, StoredPrivilegeSweep>()
        var loadCalls = 0
        var cancelOnReset: CancellationException? = null
        var cancelWhenRecordingAttempt: Int? = null
        var cancelBeforeAdmissionAfterAttempts: Int? = null
        private var attemptNumber = 0
        private val retained = MutableStateFlow<List<StoredPrivilegeSweep>>(emptyList())

        fun seed(snapshot: StoredPrivilegeSweep) {
            rows[snapshot.requestId] = snapshot
            retained.value = rows.values.toList()
        }

        fun row(requestId: UUID): StoredPrivilegeSweep = rows.getValue(requestId)

        override suspend fun load(requestId: UUID): StoredPrivilegeSweep? {
            loadCalls++
            events += "load"
            val current = rows[requestId] ?: return null
            if (attemptNumber > 0 &&
                cancelBeforeAdmissionAfterAttempts == attemptNumber &&
                current.terminalState == null
            ) {
                seed(
                    current.copy(
                        terminalState = StoredSweepTerminal.CANCELLED,
                        unresolved = current.targets.size -
                                current.succeeded - current.failed - current.busy,
                        terminalAtEpochMs = NOW,
                        retainUntilEpochMs = NOW + 1,
                    )
                )
            }
            return rows[requestId]
        }

        override suspend fun resetForRun(requestId: UUID): StoredPrivilegeSweep? {
            events += "reset"
            cancelOnReset?.let { throw it }
            val current = rows[requestId] ?: return null
            if (current.terminalState != null) return null
            return current.copy(
                succeeded = 0,
                failed = 0,
                busy = 0,
                unresolved = 0,
            ).also { seed(it) }
        }

        override suspend fun recordAttempt(
            requestId: UUID,
            outcome: SweepAttemptOutcome,
        ): Boolean {
            attemptNumber++
            events += "record:$outcome"
            val current = rows[requestId] ?: return false
            if (current.terminalState != null) return false
            if (cancelWhenRecordingAttempt == attemptNumber) {
                seed(
                    current.copy(
                        terminalState = StoredSweepTerminal.CANCELLED,
                        unresolved = current.targets.size -
                                current.succeeded - current.failed - current.busy,
                        terminalAtEpochMs = NOW,
                        retainUntilEpochMs = NOW + 1,
                    )
                )
                return false
            }
            seed(
                when (outcome) {
                    SweepAttemptOutcome.SUCCEEDED -> current.copy(succeeded = current.succeeded + 1)
                    SweepAttemptOutcome.FAILED -> current.copy(failed = current.failed + 1)
                    SweepAttemptOutcome.BUSY -> current.copy(busy = current.busy + 1)
                }
            )
            return true
        }

        override suspend fun finish(
            requestId: UUID,
            terminal: StoredSweepTerminal,
            nowMs: Long,
        ): Boolean {
            events += "finish:$terminal"
            val current = rows[requestId] ?: return false
            if (current.terminalState != null) return false
            seed(
                current.copy(
                    terminalState = terminal,
                    unresolved = current.targets.size -
                            current.succeeded - current.failed - current.busy,
                    terminalAtEpochMs = nowMs,
                    retainUntilEpochMs = nowMs + 1,
                )
            )
            return true
        }

        override suspend fun createOrFindEquivalent(
            snapshot: NewPrivilegeSweepSnapshot,
        ): SweepCreateResult = error("unused")

        override fun observe(requestId: UUID): Flow<StoredPrivilegeSweep?> = error("unused")

        override fun observeRetained(): Flow<List<StoredPrivilegeSweep>> = retained

        override fun observeRetained(
            source: PrivilegeSweepSource,
        ): Flow<List<StoredPrivilegeSweep>> = error("unused")

        override suspend fun cancelAllNonterminal(nowMs: Long): List<UUID> = error("unused")

        override suspend fun delete(requestId: UUID) = error("unused")

        override suspend fun deleteExpired(nowMs: Long): Int = error("unused")
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
