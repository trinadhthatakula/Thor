// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.data.service.ServiceStartFailure
import com.valhalla.thor.data.service.ServiceStartResult
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchRejection
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepSpec
import com.valhalla.thor.domain.model.RootLaneMode
import com.valhalla.thor.domain.model.RootLaneStatus
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeSweepBlockReason
import com.valhalla.thor.domain.repository.PrivilegeSweepCancellationDecision
import com.valhalla.thor.domain.repository.PrivilegeSweepRequestState
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import com.valhalla.thor.domain.repository.SweepCreateResult
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPrivilegeSweepControllerTest {

    @Test
    fun `empty canonical target list is rejected without persistence or wake`() = runTest {
        val fixture = Fixture()

        val result = fixture.controller.launch(spec(packageNames = emptyList()))

        assertEquals(
            PrivilegeSweepLaunchResult.Rejected(PrivilegeSweepLaunchRejection.NoTargets),
            result,
        )
        assertTrue(fixture.store.rows.isEmpty())
        assertTrue(fixture.wakes.isEmpty())
    }

    @Test
    fun `launch durably snapshots canonical targets then wakes service without WorkRequest`() = runTest {
        val fixture = Fixture()

        val accepted = fixture.controller.launch(
            spec(packageNames = listOf("com.example.alpha", "com.example.beta"))
        ) as PrivilegeSweepLaunchResult.Accepted

        val stored = fixture.store.rows.getValue(accepted.requestId)
        assertEquals(listOf("com.example.alpha", "com.example.beta"), stored.targets)
        assertTrue(stored.executionId.isPrivilegeServiceExecutionId())
        assertEquals(listOf(accepted.requestId), fixture.wakes)
        assertFalse(accepted.coalesced)
    }

    @Test
    fun `accepted service-start rejection durably blocks unclaimed request`() = runTest {
        val fixture = Fixture(startResult = ServiceStartResult.Rejected(
            ServiceStartFailure.BACKGROUND_START_NOT_ALLOWED
        ))

        val accepted = fixture.controller.launch(spec()) as PrivilegeSweepLaunchResult.Accepted

        assertEquals(
            PrivilegeSweepBlockReason.START_BLOCKED,
            fixture.store.rows.getValue(accepted.requestId).blockReason,
        )
        assertEquals(PrivilegeSweepRequestState.BLOCKED, fixture.store.rows.getValue(accepted.requestId).requestState)
    }

    @Test
    fun `blocked notification maps to notification-specific durable reason`() = runTest {
        val fixture = Fixture(startResult = ServiceStartResult.Rejected(
            ServiceStartFailure.NOTIFICATION_CHANNEL_BLOCKED
        ))

        val accepted = fixture.controller.launch(spec()) as PrivilegeSweepLaunchResult.Accepted

        assertEquals(
            PrivilegeSweepBlockReason.START_BLOCKED_NOTIFICATION,
            fixture.store.rows.getValue(accepted.requestId).blockReason,
        )
    }

    @Test
    fun `explicit equivalent launch resumes actionable blocked request before wake`() = runTest {
        val fixture = Fixture()
        val first = fixture.controller.launch(spec()) as PrivilegeSweepLaunchResult.Accepted
        fixture.store.block(first.requestId, PrivilegeSweepBlockReason.PRIVILEGE_AUTHORIZATION_REQUIRED)
        fixture.events.clear()

        val second = fixture.controller.launch(spec(source = PrivilegeSweepSource.QS_TILE))
            as PrivilegeSweepLaunchResult.Accepted

        assertTrue(second.coalesced)
        assertEquals(first.requestId, second.requestId)
        assertEquals(listOf("resume", "wake"), fixture.events)
        assertEquals(PrivilegeSweepRequestState.QUEUED, fixture.store.rows.getValue(first.requestId).requestState)
        assertNull(fixture.store.rows.getValue(first.requestId).blockReason)
    }

    @Test
    fun `equivalent unblocked launch does not invoke resume transition`() = runTest {
        val fixture = Fixture()
        fixture.controller.launch(spec())
        fixture.events.clear()

        val second = fixture.controller.launch(spec()) as PrivilegeSweepLaunchResult.Accepted

        assertTrue(second.coalesced)
        assertEquals(listOf("wake"), fixture.events)
        assertEquals(0, fixture.store.resumeCalls)
    }

    @Test
    fun `observation derives phase from Room and degradation from sweep lane`() = runTest {
        val fixture = Fixture()
        val accepted = fixture.controller.launch(spec()) as PrivilegeSweepLaunchResult.Accepted
        fixture.store.update(accepted.requestId) {
            it.copy(requestState = PrivilegeSweepRequestState.RUNNING, succeeded = 1, unresolved = 1)
        }
        fixture.rootStatuses.value = fixture.rootStatuses.value + (
                PrivilegeExecutionLane.SWEEP to RootLaneStatus(
                    PrivilegeExecutionLane.SWEEP,
                    RootLaneMode.DEGRADED,
                )
                )

        val status = fixture.controller.observe(accepted.requestId).first()

        assertEquals(PrivilegeSweepPhase.RUNNING, status?.phase)
        assertEquals(1, status?.succeeded)
        assertEquals(1, status?.unresolved)
        assertEquals(true, status?.rootLaneDegraded)
    }

    @Test
    fun `terminal Room state wins without WorkInfo`() = runTest {
        val fixture = Fixture()
        val accepted = fixture.controller.launch(spec()) as PrivilegeSweepLaunchResult.Accepted
        fixture.store.update(accepted.requestId) {
            it.copy(
                requestState = PrivilegeSweepRequestState.PARTIAL,
                terminalState = StoredSweepTerminal.PARTIAL,
                failed = 1,
                unresolved = 1,
            )
        }

        assertEquals(
            PrivilegeSweepPhase.PARTIAL,
            fixture.controller.observe(accepted.requestId).first()?.phase,
        )
    }

    private fun spec(
        packageNames: List<String> = listOf("com.example.alpha", "com.example.beta"),
        source: PrivilegeSweepSource = PrivilegeSweepSource.MAIN,
    ) = PrivilegeSweepSpec(
        operation = PrivilegeSweepOperation.CLEAR_CACHE,
        packageNames = packageNames,
        freezerMode = null,
        userId = 0,
        source = source,
    )

    private class Fixture(
        private val startResult: ServiceStartResult = ServiceStartResult.Requested,
    ) {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val wakes = mutableListOf<UUID>()
        val rootStatuses = MutableStateFlow(
            PrivilegeExecutionLane.entries.associateWith { lane ->
                RootLaneStatus(lane, RootLaneMode.ISOLATED)
            }
        )
        private val cancellation = PrivilegeSweepCancellationCoordinator(
            requestCancellation = { PrivilegeSweepCancellationDecision.NotFound },
            cancelActive = { false },
            wake = { ServiceStartResult.AlreadyRunning },
            reconcileStaleClaim = {},
        )
        private val canceller = SweepQueueCanceller(store, cancellation)
        private val rootLaneStatusSource = object : RootLaneStatusSource {
            override val statuses = rootStatuses
        }
        val controller = DefaultPrivilegeSweepController(
            store = store,
            clock = object : PrivilegeSweepClock {
                override fun nowMs(): Long = NOW
            },
            gate = PrivilegeSweepProcessGate(),
            wakeSignal = PrivilegeQueueWakeSignal { requestId ->
                events += "wake"
                wakes += requestId
                startResult
            },
            queueCanceller = canceller,
            rootLaneStatusSource = rootLaneStatusSource,
        )
    }

    private class FakeStore(
        private val events: MutableList<String>,
    ) : PrivilegeSweepStore {
        val rows = linkedMapOf<UUID, StoredPrivilegeSweep>()
        var resumeCalls = 0
        private val observed = mutableMapOf<UUID, MutableStateFlow<StoredPrivilegeSweep?>>()
        private val retained = MutableStateFlow<List<StoredPrivilegeSweep>>(emptyList())

        override suspend fun createOrFindEquivalent(snapshot: NewPrivilegeSweepSnapshot): SweepCreateResult {
            val equivalent = rows.values.firstOrNull {
                it.terminalState == null &&
                        it.operation == snapshot.operation &&
                        it.freezerMode == snapshot.freezerMode &&
                        it.userId == snapshot.userId &&
                        it.targets == snapshot.targets
            }
            if (equivalent != null) {
                val updated = equivalent.copy(
                    sourceAssociations = equivalent.sourceAssociations + snapshot.sourceAssociations
                )
                rows[updated.requestId] = updated
                publish(updated.requestId)
                return SweepCreateResult.Equivalent(updated)
            }
            val stored = StoredPrivilegeSweep(
                requestId = snapshot.requestId,
                workId = snapshot.executionId,
                operation = snapshot.operation,
                freezerMode = snapshot.freezerMode,
                userId = snapshot.userId,
                source = snapshot.source,
                createdAtEpochMs = snapshot.createdAtEpochMs,
                targets = snapshot.targets,
                terminalState = null,
                succeeded = 0,
                failed = 0,
                busy = 0,
                unresolved = snapshot.targets.size,
                terminalAtEpochMs = null,
                retainUntilEpochMs = null,
                sourceAssociations = snapshot.sourceAssociations,
            )
            rows[stored.requestId] = stored
            publish(stored.requestId)
            return SweepCreateResult.Created(stored)
        }

        fun block(requestId: UUID, reason: PrivilegeSweepBlockReason) = update(requestId) {
            it.copy(requestState = PrivilegeSweepRequestState.BLOCKED, blockReason = reason)
        }

        fun update(requestId: UUID, transform: (StoredPrivilegeSweep) -> StoredPrivilegeSweep) {
            rows[requestId] = transform(rows.getValue(requestId))
            publish(requestId)
        }

        override suspend fun markUnclaimedStartBlocked(
            requestId: UUID,
            reason: PrivilegeSweepBlockReason,
            nowMs: Long,
        ): Boolean {
            val current = rows[requestId] ?: return false
            if (current.requestState != PrivilegeSweepRequestState.QUEUED) return false
            update(requestId) { it.copy(requestState = PrivilegeSweepRequestState.BLOCKED, blockReason = reason) }
            return true
        }

        override suspend fun resumeBlockedRequest(
            requestId: UUID,
            expectedReason: PrivilegeSweepBlockReason,
            nowMs: Long,
        ): Boolean {
            resumeCalls++
            val current = rows[requestId] ?: return false
            if (current.requestState != PrivilegeSweepRequestState.BLOCKED || current.blockReason != expectedReason) {
                return false
            }
            events += "resume"
            update(requestId) { it.copy(requestState = PrivilegeSweepRequestState.QUEUED, blockReason = null) }
            return true
        }

        override suspend fun load(requestId: UUID): StoredPrivilegeSweep? = rows[requestId]
        override fun observe(requestId: UUID): Flow<StoredPrivilegeSweep?> =
            observed.getOrPut(requestId) { MutableStateFlow(rows[requestId]) }
        override fun observeRetained(): Flow<List<StoredPrivilegeSweep>> = retained
        override fun observeRetained(source: PrivilegeSweepSource): Flow<List<StoredPrivilegeSweep>> =
            MutableStateFlow(rows.values.filter { source.name in it.sourceAssociations })
        override suspend fun resetForRun(requestId: UUID): StoredPrivilegeSweep? = error("unused")
        override suspend fun recordAttempt(requestId: UUID, outcome: SweepAttemptOutcome): Boolean = error("unused")
        override suspend fun finish(requestId: UUID, terminal: StoredSweepTerminal, nowMs: Long): Boolean = error("unused")
        override suspend fun cancelAllNonterminal(nowMs: Long): List<UUID> = error("unused")
        override suspend fun delete(requestId: UUID) = error("unused")
        override suspend fun deleteExpired(nowMs: Long): Int = error("unused")

        private fun publish(requestId: UUID) {
            observed.getOrPut(requestId) { MutableStateFlow(null) }.value = rows[requestId]
            retained.value = rows.values.sortedByDescending { it.createdAtEpochMs }
        }
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
