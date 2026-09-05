// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetState
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.StoredPrivilegeSweepTarget
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import com.valhalla.thor.domain.repository.SweepCreateResult
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivilegeSweepCutoverReconcilerTest {

    @Test
    fun `cutover closes admission then separately awaits worker body quiescence`() = runTest {
        val events = mutableListOf<String>()
        val fence = LegacyPrivilegeSweepExecutionFence()
        val registration = checkNotNull(fence.tryRegister())
        val store = FakeStore(listOf(stored(LEGACY_ID)), events)
        val cutover = cutover(fence, store) { events += "work-cancelled" }

        val result = async { cutover.awaitCompleted() }
        runCurrent()

        assertFalse(result.isCompleted)
        assertFalse(fence.isAdmissionOpenForTest())
        assertNull(fence.tryRegister())
        assertEquals(listOf("work-cancelled"), events)

        events += "worker-finally"
        registration.close()
        result.await()

        assertEquals(listOf("work-cancelled", "worker-finally", "legacy-unknown"), events)
    }

    @Test
    fun `cutover is one shot and reconciles legacy IDs even without WorkInfo`() = runTest {
        val events = mutableListOf<String>()
        val serviceId = newPrivilegeServiceExecutionId()
        val legacy = stored(LEGACY_ID)
        val store = FakeStore(listOf(legacy, stored(serviceId)), events)
        var cancellationCalls = 0
        val cutover = cutover(LegacyPrivilegeSweepExecutionFence(), store) {
            cancellationCalls++
        }

        cutover.awaitCompleted()
        cutover.awaitCompleted()

        assertEquals(1, cancellationCalls)
        assertEquals(listOf(legacy.requestId), store.marked)
    }

    @Test
    fun `failed cutover is retried once and only successful completion is cached`() {
        val fence = LegacyPrivilegeSweepExecutionFence()
        var calls = 0
        val cutover = cutover(fence, FakeStore(emptyList(), mutableListOf())) {
            calls++
            if (calls == 1) error("WorkManager unavailable")
        }

        assertThrows(IllegalStateException::class.java) { runTest { cutover.awaitCompleted() } }
        runTest { cutover.awaitCompleted() }
        runTest { cutover.awaitCompleted() }

        assertEquals(2, calls)
        assertFalse(fence.isAdmissionOpenForTest())
    }

    private fun cutover(
        fence: LegacyPrivilegeSweepExecutionFence,
        store: PrivilegeSweepStore,
        cancel: suspend () -> Unit,
    ) = PrivilegeSweepWorkManagerCutover(
        fence = fence,
        queueWorkManager = SweepQueueWorkManager { cancel() },
        store = store,
        clock = object : PrivilegeSweepClock {
            override fun nowMs(): Long = NOW
        },
        gate = PrivilegeSweepProcessGate(),
    )

    private fun stored(executionId: UUID) = StoredPrivilegeSweep(
        requestId = UUID.randomUUID(),
        workId = executionId,
        operation = PrivilegeSweepOperation.CLEAR_CACHE,
        freezerMode = null,
        userId = 0,
        source = PrivilegeSweepSource.MAIN,
        createdAtEpochMs = 1L,
        targets = listOf("com.example.alpha", "com.example.beta"),
        terminalState = null,
        succeeded = 0,
        failed = 0,
        busy = 0,
        unresolved = 2,
        terminalAtEpochMs = null,
        retainUntilEpochMs = null,
        targetSnapshots = listOf(
            target(0, "com.example.alpha"),
            target(1, "com.example.beta"),
        ),
    )

    private fun target(ordinal: Int, packageName: String) = StoredPrivilegeSweepTarget(
        requestId = UUID(0, 0),
        ordinal = ordinal,
        packageName = packageName,
        state = PrivilegeSweepTargetState.PENDING,
        claimToken = null,
        claimLeaseExpiresAtEpochMs = null,
        attemptCount = 0,
        startedAtEpochMs = null,
        finishedAtEpochMs = null,
        resultCode = null,
        rootLaneDegraded = false,
    )

    private class FakeStore(
        initial: List<StoredPrivilegeSweep>,
        private val events: MutableList<String>,
    ) : PrivilegeSweepStore {
        private val rows = initial.associateByTo(linkedMapOf(), StoredPrivilegeSweep::requestId)
        private val retained = MutableStateFlow(initial)
        val marked = mutableListOf<UUID>()

        override suspend fun markLegacyTargetsUnknown(
            requestId: UUID,
            ambiguousOrdinals: List<Int>,
            nowMs: Long,
        ): Boolean {
            events += "legacy-unknown"
            marked += requestId
            assertEquals(listOf(0, 1), ambiguousOrdinals)
            return true
        }

        override suspend fun load(requestId: UUID): StoredPrivilegeSweep? = rows[requestId]
        override fun observeRetained(): Flow<List<StoredPrivilegeSweep>> = retained
        override suspend fun createOrFindEquivalent(snapshot: NewPrivilegeSweepSnapshot): SweepCreateResult = error("unused")
        override fun observe(requestId: UUID): Flow<StoredPrivilegeSweep?> = error("unused")
        override fun observeRetained(source: PrivilegeSweepSource): Flow<List<StoredPrivilegeSweep>> = error("unused")
        override suspend fun resetForRun(requestId: UUID): StoredPrivilegeSweep? = error("unused")
        override suspend fun recordAttempt(requestId: UUID, outcome: SweepAttemptOutcome): Boolean = error("unused")
        override suspend fun finish(requestId: UUID, terminal: StoredSweepTerminal, nowMs: Long): Boolean = error("unused")
        override suspend fun cancelAllNonterminal(nowMs: Long): List<UUID> = error("unused")
        override suspend fun delete(requestId: UUID) = error("unused")
        override suspend fun deleteExpired(nowMs: Long): Int = error("unused")
    }

    private companion object {
        val LEGACY_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000011")
        const val NOW = 1_000_000L
    }
}
