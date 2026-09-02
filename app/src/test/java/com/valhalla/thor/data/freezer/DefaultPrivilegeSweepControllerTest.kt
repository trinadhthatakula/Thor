// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.app.NotificationManager
import androidx.work.OneTimeWorkRequest
import com.valhalla.thor.data.backup.job.ThorJobNotificationCapability
import com.valhalla.thor.data.backup.job.jobNotificationsAvailable
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchRejection
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepSpec
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import com.valhalla.thor.domain.model.RootLaneMode
import com.valhalla.thor.domain.model.RootLaneStatus
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.domain.model.SWEEP_REQUEST_ID_KEY
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import com.valhalla.thor.domain.repository.SweepCreateResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPrivilegeSweepControllerTest {

    @Test
    fun `disabled app notifications reject before snapshot or enqueue`() = runTest {
        val fixture = Fixture(
            notifications = FakeNotificationCapability(appEnabled = false),
        )
        assertFalse(
            jobNotificationsAvailable(
                appNotificationsEnabled = false,
                postNotificationsGranted = true,
                channelImportance = NotificationManager.IMPORTANCE_LOW,
            )
        )

        val result = fixture.controller.launch(spec())

        assertEquals(
            PrivilegeSweepLaunchResult.Rejected(PrivilegeSweepLaunchRejection.NotificationsRequired),
            result,
        )
        assertTrue(fixture.store.rows.isEmpty())
        assertTrue(fixture.work.enqueued.isEmpty())
    }

    @Test
    fun `missing post notifications permission rejects before persistence`() = runTest {
        val fixture = Fixture(
            notifications = FakeNotificationCapability(postPermissionGranted = false),
        )
        assertFalse(
            jobNotificationsAvailable(
                appNotificationsEnabled = true,
                postNotificationsGranted = false,
                channelImportance = NotificationManager.IMPORTANCE_LOW,
            )
        )

        val result = fixture.controller.launch(spec())

        assertEquals(
            PrivilegeSweepLaunchResult.Rejected(PrivilegeSweepLaunchRejection.NotificationsRequired),
            result,
        )
        assertTrue(fixture.store.rows.isEmpty())
        assertTrue(fixture.work.enqueued.isEmpty())
    }

    @Test
    fun `disabled thor jobs channel rejects before persistence`() = runTest {
        val fixture = Fixture(
            notifications = FakeNotificationCapability(
                channelImportance = NotificationManager.IMPORTANCE_NONE,
            ),
        )
        assertFalse(
            jobNotificationsAvailable(
                appNotificationsEnabled = true,
                postNotificationsGranted = true,
                channelImportance = NotificationManager.IMPORTANCE_NONE,
            )
        )

        val result = fixture.controller.launch(spec())

        assertEquals(
            PrivilegeSweepLaunchResult.Rejected(PrivilegeSweepLaunchRejection.NotificationsRequired),
            result,
        )
        assertTrue(fixture.store.rows.isEmpty())
        assertTrue(fixture.work.enqueued.isEmpty())
    }

    @Test
    fun `no privilege rejects before snapshot or enqueue`() = runTest {
        val fixture = Fixture(privileged = false)

        val result = fixture.controller.launch(spec())

        assertEquals(
            PrivilegeSweepLaunchResult.Rejected(PrivilegeSweepLaunchRejection.NoPrivilege),
            result,
        )
        assertTrue(fixture.store.rows.isEmpty())
        assertTrue(fixture.work.enqueued.isEmpty())
    }

    @Test
    fun `an empty canonical target list rejects before snapshot or enqueue`() = runTest {
        val fixture = Fixture()

        val result = fixture.controller.launch(spec(packageNames = emptyList()))

        assertEquals(
            PrivilegeSweepLaunchResult.Rejected(PrivilegeSweepLaunchRejection.NoTargets),
            result,
        )
        assertTrue(fixture.store.rows.isEmpty())
        assertTrue(fixture.work.enqueued.isEmpty())
    }

    @Test
    fun `accepted request stores snapshot before enqueue`() = runTest {
        val fixture = Fixture()
        fixture.work.onEnqueue = { work ->
            val snapshot = fixture.store.rows.values.single()
            assertEquals(work.id, snapshot.workId)
            assertEquals(listOf("com.example.alpha", "com.example.beta"), snapshot.targets)
            fixture.events += "enqueue"
        }

        val result = fixture.controller.launch(spec())

        assertType<PrivilegeSweepLaunchResult.Accepted>(result)
        assertEquals(listOf("persist", "enqueue"), fixture.events)
    }

    @Suppress("RestrictedApi")
    @Test
    fun `work input contains request id and no package names`() = runTest {
        val fixture = Fixture()

        val result = assertType<PrivilegeSweepLaunchResult.Accepted>(
            fixture.controller.launch(spec())
        )
        val work = fixture.work.enqueued.single()
        val input = work.workSpec.input.keyValueMap

        assertEquals(setOf(SWEEP_REQUEST_ID_KEY), input.keys)
        assertEquals(result.requestId.toString(), input[SWEEP_REQUEST_ID_KEY])
        assertFalse(input.values.any { it == "com.example.alpha" || it == "com.example.beta" })
        assertEquals(PrivilegeSweepWorker::class.java.name, work.workSpec.workerClassName)
    }

    @Test
    fun `exact canonical duplicate coalesces onto existing work id`() = runTest {
        val fixture = Fixture()
        val first = assertType<PrivilegeSweepLaunchResult.Accepted>(
            fixture.controller.launch(spec(source = PrivilegeSweepSource.MAIN))
        )
        val secondaryObservation = async {
            fixture.controller.observeLatest(PrivilegeSweepSource.QS_TILE).first { it != null }
        }
        runCurrent()
        assertFalse(secondaryObservation.isCompleted)

        val second = assertType<PrivilegeSweepLaunchResult.Accepted>(
            fixture.controller.launch(spec(source = PrivilegeSweepSource.QS_TILE))
        )

        assertFalse(first.coalesced)
        assertTrue(second.coalesced)
        assertEquals(first.requestId, second.requestId)
        assertEquals(first.workId, second.workId)
        assertEquals(1, fixture.work.enqueued.size)
        assertEquals(1, fixture.store.rows.size)
        val secondaryStatus = secondaryObservation.await()
        assertEquals(first.requestId, secondaryStatus?.requestId)
        assertEquals(PrivilegeSweepSource.MAIN, secondaryStatus?.source)
    }

    @Test
    fun `profile identity reconstructs from persisted source associations`() = runTest {
        val fixture = Fixture()
        val accepted = assertType<PrivilegeSweepLaunchResult.Accepted>(
            fixture.controller.launch(
                spec(
                    operation = PrivilegeSweepOperation.FREEZE,
                    source = PrivilegeSweepSource.PROFILE,
                    profileId = 7L,
                )
            )
        )
        fixture.work.setState(accepted.workId, SweepWorkState.RUNNING)

        val reconstructed = fixture.newController()
            .observeLatest(PrivilegeSweepSource.PROFILE)
            .first { it != null }

        assertEquals(setOf(7L), reconstructed?.profileIds)
        assertTrue(
            fixture.store.rows.getValue(accepted.requestId)
                .sourceAssociations.containsAll(setOf("PROFILE", "PROFILE:7"))
        )
    }

    @Test
    fun `coalesced identical profiles retain both launched identities while different targets stay separate`() =
        runTest {
            val fixture = Fixture()
            val first = assertType<PrivilegeSweepLaunchResult.Accepted>(
                fixture.controller.launch(
                    spec(
                        operation = PrivilegeSweepOperation.FREEZE,
                        source = PrivilegeSweepSource.PROFILE,
                        profileId = 7L,
                    )
                )
            )
            val sameTargets = assertType<PrivilegeSweepLaunchResult.Accepted>(
                fixture.controller.launch(
                    spec(
                        operation = PrivilegeSweepOperation.FREEZE,
                        source = PrivilegeSweepSource.PROFILE,
                        profileId = 8L,
                    )
                )
            )
            val differentTargets = assertType<PrivilegeSweepLaunchResult.Accepted>(
                fixture.controller.launch(
                    spec(
                        operation = PrivilegeSweepOperation.FREEZE,
                        packageNames = listOf("com.example.gamma"),
                        source = PrivilegeSweepSource.PROFILE,
                        profileId = 9L,
                    )
                )
            )

            assertTrue(sameTargets.coalesced)
            assertEquals(first.requestId, sameTargets.requestId)
            assertFalse(differentTargets.coalesced)
            assertFalse(first.requestId == differentTargets.requestId)
            assertEquals(
                setOf(7L, 8L),
                fixture.store.rows.getValue(first.requestId)
                    .sourceAssociations
                    .filter { it.startsWith("PROFILE:") }
                    .mapTo(linkedSetOf()) { it.substringAfter(':').toLong() },
            )
            assertEquals(
                setOf(9L),
                fixture.store.rows.getValue(differentTargets.requestId)
                    .sourceAssociations
                    .filter { it.startsWith("PROFILE:") }
                    .mapTo(linkedSetOf()) { it.substringAfter(':').toLong() },
            )
        }

    @Test
    fun `latest source observation follows the newest coalesced association`() = runTest {
        val fixture = Fixture()
        val olderActive = assertType<PrivilegeSweepLaunchResult.Accepted>(
            fixture.controller.launch(
                spec(
                    operation = PrivilegeSweepOperation.CLEAR_CACHE,
                    source = PrivilegeSweepSource.MAIN,
                )
            )
        )
        fixture.clock.now = NOW + 1
        val newerTerminal = assertType<PrivilegeSweepLaunchResult.Accepted>(
            fixture.controller.launch(
                spec(
                    operation = PrivilegeSweepOperation.REINSTALL,
                    source = PrivilegeSweepSource.QS_TILE,
                )
            )
        )
        fixture.store.finish(
            newerTerminal.requestId,
            StoredSweepTerminal.SUCCEEDED,
            fixture.clock.now,
        )
        fixture.clock.now = NOW + 2

        val coalesced = assertType<PrivilegeSweepLaunchResult.Accepted>(
            fixture.controller.launch(
                spec(
                    operation = PrivilegeSweepOperation.CLEAR_CACHE,
                    source = PrivilegeSweepSource.QS_TILE,
                )
            )
        )

        assertTrue(coalesced.coalesced)
        assertEquals(olderActive.requestId, coalesced.requestId)
        assertEquals(
            olderActive.requestId,
            fixture.controller.observeLatest(PrivilegeSweepSource.QS_TILE).firstValue()?.requestId,
        )
    }

    @Test
    fun `opposite operation never coalesces`() = runTest {
        val fixture = Fixture()
        val first = assertType<PrivilegeSweepLaunchResult.Accepted>(
            fixture.controller.launch(spec(operation = PrivilegeSweepOperation.CLEAR_CACHE))
        )

        val second = assertType<PrivilegeSweepLaunchResult.Accepted>(
            fixture.controller.launch(spec(operation = PrivilegeSweepOperation.REINSTALL))
        )

        assertFalse(first.coalesced)
        assertFalse(second.coalesced)
        assertFalse(first.requestId == second.requestId)
        assertEquals(2, fixture.work.enqueued.size)
        assertEquals(2, fixture.store.rows.size)
    }

    @Test
    fun `enqueue rejection deletes newly inserted snapshot`() = runTest {
        val fixture = Fixture()
        val retained = stored(
            operation = PrivilegeSweepOperation.REINSTALL,
            terminal = StoredSweepTerminal.SUCCEEDED,
        )
        fixture.store.seed(retained)
        fixture.work.queueOperation(TestOperation.failed(IllegalStateException("database rejected")))

        val result = fixture.controller.launch(spec())

        val rejected = assertType<PrivilegeSweepLaunchResult.Rejected>(result)
        assertType<PrivilegeSweepLaunchRejection.EnqueueFailed>(rejected.reason)
        assertEquals(setOf(retained.requestId), fixture.store.rows.keys)
    }

    @Test
    fun `observer combines Room terminal counts with WorkInfo phase`() = runTest {
        val fixture = Fixture()
        val request = stored(
            terminal = null,
            succeeded = 1,
            failed = 1,
            busy = 0,
        )
        fixture.store.seed(request)
        fixture.work.setState(request.workId, SweepWorkState.RUNNING)
        fixture.rootStatuses.value = fixture.rootStatuses.value + (
                PrivilegeExecutionLane.SWEEP to RootLaneStatus(
                    PrivilegeExecutionLane.SWEEP,
                    RootLaneMode.DEGRADED,
                )
                )

        val running = fixture.controller.observe(request.requestId).firstValue()

        assertEquals(PrivilegeSweepPhase.RUNNING, running?.phase)
        assertEquals(3, running?.total)
        assertEquals(1, running?.succeeded)
        assertEquals(1, running?.failed)
        assertEquals(1, running?.unresolved)
        assertEquals(true, running?.rootLaneDegraded)

        fixture.store.seed(
            request.copy(
                terminalState = StoredSweepTerminal.PARTIAL,
                succeeded = 1,
                failed = 1,
                busy = 0,
                unresolved = 1,
                terminalAtEpochMs = NOW,
                retainUntilEpochMs = NOW + 1,
            )
        )

        val terminal = fixture.controller.observe(request.requestId).firstValue()
        assertEquals(PrivilegeSweepPhase.PARTIAL, terminal?.phase)
        assertEquals(1, terminal?.unresolved)
    }

    @Test
    fun `observer maps every nonterminal WorkInfo phase`() = runTest {
        val fixture = Fixture()
        val request = stored()
        fixture.store.seed(request)

        val expected = mapOf(
            SweepWorkState.BLOCKED to PrivilegeSweepPhase.QUEUED,
            SweepWorkState.ENQUEUED to PrivilegeSweepPhase.QUEUED,
            SweepWorkState.RUNNING to PrivilegeSweepPhase.RUNNING,
            SweepWorkState.CANCELLED to PrivilegeSweepPhase.CANCELLED,
            SweepWorkState.SUCCEEDED to PrivilegeSweepPhase.OBSERVER_FAILURE,
            SweepWorkState.FAILED to PrivilegeSweepPhase.OBSERVER_FAILURE,
        )
        expected.forEach { (workState, phase) ->
            fixture.work.setState(request.workId, workState)
            assertEquals(phase, fixture.controller.observe(request.requestId).firstValue()?.phase)
        }
    }

    @Test
    fun `missing WorkInfo becomes observer failure rather than endless running`() = runTest {
        val fixture = Fixture()
        val request = stored()
        fixture.store.seed(request)
        fixture.work.setState(request.workId, null)

        val status = fixture.controller.observe(request.requestId).firstValue()

        assertEquals(PrivilegeSweepPhase.OBSERVER_FAILURE, status?.phase)
    }

    @Test
    fun `missing Room snapshot emits null`() = runTest {
        val fixture = Fixture()

        assertNull(fixture.controller.observe(UUID.randomUUID()).firstValue())
    }

    @Test
    fun `active requests emits an empty list when no snapshots are retained`() = runTest {
        val fixture = Fixture()

        assertEquals(
            emptyList<PrivilegeSweepStatus>(),
            fixture.controller.activeRequests.firstValue()
        )
    }

    @Test
    fun `active requests maps active and retained terminal snapshots truthfully`() = runTest {
        val fixture = Fixture()
        val active = stored(
            requestId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            createdAt = NOW - 1,
            succeeded = 1,
        )
        val terminal = stored(
            requestId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            source = PrivilegeSweepSource.SETTINGS,
            terminal = StoredSweepTerminal.PARTIAL,
            succeeded = 1,
            failed = 1,
            unresolved = 1,
        )
        fixture.store.seed(active)
        fixture.store.seed(terminal)
        fixture.work.setState(active.workId, SweepWorkState.ENQUEUED)
        fixture.work.setState(terminal.workId, SweepWorkState.FAILED)

        val statuses = fixture.controller.activeRequests.firstValue().associateBy { it.requestId }

        assertEquals(setOf(active.requestId, terminal.requestId), statuses.keys)
        assertEquals(PrivilegeSweepPhase.QUEUED, statuses.getValue(active.requestId).phase)
        assertEquals(2, statuses.getValue(active.requestId).unresolved)
        assertEquals(PrivilegeSweepPhase.PARTIAL, statuses.getValue(terminal.requestId).phase)
        assertEquals(1, statuses.getValue(terminal.requestId).unresolved)
    }

    @Test
    fun `active requests react to every WorkInfo and sweep lane status change`() = runTest {
        val fixture = Fixture()
        val active = stored(createdAt = NOW - 1)
        val terminal = stored(
            source = PrivilegeSweepSource.SETTINGS,
            terminal = StoredSweepTerminal.SUCCEEDED,
            succeeded = 3,
            unresolved = 0,
        )
        fixture.store.seed(active)
        fixture.store.seed(terminal)
        fixture.work.setState(active.workId, SweepWorkState.ENQUEUED)
        fixture.work.setState(terminal.workId, SweepWorkState.SUCCEEDED)
        val emissions = mutableListOf<List<PrivilegeSweepStatus>>()
        val collection = backgroundScope.launch {
            fixture.controller.activeRequests.collect(emissions::add)
        }
        runCurrent()

        assertEquals(1, fixture.work.activeObservers.getValue(active.workId))
        assertEquals(1, fixture.work.activeObservers.getValue(terminal.workId))
        val beforeTerminalWorkChange = emissions.size

        fixture.work.setState(terminal.workId, SweepWorkState.RUNNING)
        runCurrent()

        assertEquals(beforeTerminalWorkChange + 1, emissions.size)
        assertEquals(
            PrivilegeSweepPhase.SUCCEEDED,
            emissions.last().single { it.requestId == terminal.requestId }.phase,
        )

        fixture.work.setState(active.workId, SweepWorkState.RUNNING)
        runCurrent()

        assertEquals(
            PrivilegeSweepPhase.RUNNING,
            emissions.last().single { it.requestId == active.requestId }.phase,
        )

        fixture.rootStatuses.value = fixture.rootStatuses.value.plus(
            PrivilegeExecutionLane.SWEEP to RootLaneStatus(
                PrivilegeExecutionLane.SWEEP,
                RootLaneMode.DEGRADED,
            )
        )
        runCurrent()

        assertTrue(emissions.last().all(PrivilegeSweepStatus::rootLaneDegraded))
        collection.cancel()
        runCurrent()
        assertEquals(0, fixture.work.activeObservers.getValue(active.workId))
        assertEquals(0, fixture.work.activeObservers.getValue(terminal.workId))
    }

    @Test
    fun `active requests maps missing WorkInfo to observer failure`() = runTest {
        val fixture = Fixture()
        val request = stored()
        fixture.store.seed(request)

        val status = fixture.controller.activeRequests.firstValue().single()

        assertEquals(PrivilegeSweepPhase.OBSERVER_FAILURE, status.phase)
    }

    @Test
    fun `observe latest filters by source and breaks equal timestamps by request id`() = runTest {
        val fixture = Fixture()
        val lowerId = stored(
            requestId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            createdAt = NOW,
            source = PrivilegeSweepSource.FREEZER,
        )
        val higherId = stored(
            requestId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            createdAt = NOW,
            source = PrivilegeSweepSource.FREEZER,
        )
        val newerOtherSource = stored(
            requestId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
            createdAt = NOW + 1,
            source = PrivilegeSweepSource.SETTINGS,
        )
        fixture.store.seed(lowerId)
        fixture.store.seed(higherId)
        fixture.store.seed(newerOtherSource)
        fixture.work.setState(lowerId.workId, SweepWorkState.RUNNING)
        fixture.work.setState(higherId.workId, SweepWorkState.ENQUEUED)
        fixture.work.setState(newerOtherSource.workId, SweepWorkState.RUNNING)

        val latest = fixture.controller.observeLatest(PrivilegeSweepSource.FREEZER).firstValue()

        assertEquals(higherId.requestId, latest?.requestId)
        assertEquals(PrivilegeSweepPhase.QUEUED, latest?.phase)
        assertNull(fixture.controller.observeLatest(PrivilegeSweepSource.PROFILE).firstValue())
    }

    @Test
    fun `observe latest reuses observer mapping for WorkInfo and sweep lane changes`() = runTest {
        val fixture = Fixture()
        val request = stored(source = PrivilegeSweepSource.FREEZER)
        fixture.store.seed(request)
        fixture.work.setState(request.workId, SweepWorkState.ENQUEUED)
        val emissions = mutableListOf<PrivilegeSweepStatus?>()
        val collection = backgroundScope.launch {
            fixture.controller.observeLatest(PrivilegeSweepSource.FREEZER).collect(emissions::add)
        }
        runCurrent()

        fixture.work.setState(request.workId, SweepWorkState.RUNNING)
        runCurrent()
        assertEquals(PrivilegeSweepPhase.RUNNING, emissions.last()?.phase)

        fixture.rootStatuses.value +=
            PrivilegeExecutionLane.SWEEP to RootLaneStatus(
                PrivilegeExecutionLane.SWEEP,
                RootLaneMode.DEGRADED,
            )
        runCurrent()

        assertEquals(true, emissions.last()?.rootLaneDegraded)
        collection.cancel()
    }

    @Test
    fun `observe latest follows retained selection changes without launching duplicate work`() =
        runTest {
            val fixture = Fixture()
            val older = stored(createdAt = NOW - 1, source = PrivilegeSweepSource.FREEZER)
            fixture.store.seed(older)
            fixture.work.setState(older.workId, SweepWorkState.ENQUEUED)
            val emissions = mutableListOf<PrivilegeSweepStatus?>()
            val collection = backgroundScope.launch {
                fixture.controller.observeLatest(PrivilegeSweepSource.FREEZER)
                    .collect(emissions::add)
            }
            runCurrent()
            assertEquals(older.requestId, emissions.last()?.requestId)
            assertEquals(1, fixture.work.activeObservers.getValue(older.workId))

            val newer = stored(createdAt = NOW, source = PrivilegeSweepSource.FREEZER)
            fixture.work.setState(newer.workId, SweepWorkState.RUNNING)
            fixture.store.seed(newer)
            runCurrent()

            assertEquals(newer.requestId, emissions.last()?.requestId)
            assertEquals(PrivilegeSweepPhase.RUNNING, emissions.last()?.phase)
            assertEquals(0, fixture.work.activeObservers.getValue(older.workId))
            assertEquals(1, fixture.work.activeObservers.getValue(newer.workId))
            assertTrue(fixture.work.enqueued.isEmpty())
            collection.cancel()
        }

    @Test
    fun `active requests cancel WorkInfo observers for snapshots no longer retained`() = runTest {
        val fixture = Fixture()
        val removed = stored(createdAt = NOW - 1)
        val retained = stored(createdAt = NOW)
        fixture.store.seed(removed)
        fixture.store.seed(retained)
        fixture.work.setState(removed.workId, SweepWorkState.ENQUEUED)
        fixture.work.setState(retained.workId, SweepWorkState.RUNNING)
        val collection = backgroundScope.launch {
            fixture.controller.activeRequests.collect { }
        }
        runCurrent()
        assertEquals(1, fixture.work.activeObservers.getValue(removed.workId))
        assertEquals(1, fixture.work.activeObservers.getValue(retained.workId))

        fixture.store.delete(removed.requestId)
        runCurrent()

        assertEquals(0, fixture.work.activeObservers.getValue(removed.workId))
        assertEquals(1, fixture.work.activeObservers.getValue(retained.workId))
        collection.cancel()
        runCurrent()
        assertEquals(0, fixture.work.activeObservers.getValue(retained.workId))
    }

    @Test
    fun `reconciler terminalizes orphaned nonterminal snapshots and prunes expired rows`() =
        runTest {
            val fixture = Fixture()
            val absent = stored(createdAt = NOW - 100)
            val cancelled = stored(createdAt = NOW - 90)
            val succeededWithoutRoomTerminal = stored(createdAt = NOW - 80)
            val running = stored(createdAt = NOW - 70)
            val expired = stored(
                createdAt = NOW - 60,
                terminal = StoredSweepTerminal.SUCCEEDED,
                retainUntil = NOW,
            )
            val retained = stored(
                createdAt = NOW - 50,
                terminal = StoredSweepTerminal.PARTIAL,
                retainUntil = NOW + 1,
            )
            listOf(absent, cancelled, succeededWithoutRoomTerminal, running, expired, retained)
                .forEach(fixture.store::seed)
            fixture.work.setState(absent.workId, null)
            fixture.work.setState(cancelled.workId, SweepWorkState.CANCELLED)
            fixture.work.setState(succeededWithoutRoomTerminal.workId, SweepWorkState.SUCCEEDED)
            fixture.work.setState(running.workId, SweepWorkState.RUNNING)

            fixture.reconciler.reconcile()

            assertEquals(
                StoredSweepTerminal.FAILED,
                fixture.store.rows[absent.requestId]?.terminalState
            )
            assertEquals(3, fixture.store.rows[absent.requestId]?.unresolved)
            assertEquals(
                StoredSweepTerminal.CANCELLED,
                fixture.store.rows[cancelled.requestId]?.terminalState
            )
            assertEquals(3, fixture.store.rows[cancelled.requestId]?.unresolved)
            assertEquals(
                StoredSweepTerminal.FAILED,
                fixture.store.rows[succeededWithoutRoomTerminal.requestId]?.terminalState,
            )
            assertNull(fixture.store.rows[running.requestId]?.terminalState)
            assertFalse(fixture.store.rows.containsKey(expired.requestId))
            assertEquals(
                StoredSweepTerminal.PARTIAL,
                fixture.store.rows[retained.requestId]?.terminalState
            )
            assertTrue(fixture.store.events.indexOf("finish") < fixture.store.events.indexOf("prune"))
        }

    @Test
    fun `reconciler never overwrites an existing Room terminal state`() = runTest {
        val fixture = Fixture()
        val terminal = stored(
            terminal = StoredSweepTerminal.SUCCEEDED,
            succeeded = 3,
            unresolved = 0,
            retainUntil = NOW + 1,
        )
        fixture.store.seed(terminal)
        fixture.work.setState(terminal.workId, SweepWorkState.FAILED)

        fixture.reconciler.reconcile()

        assertEquals(terminal, fixture.store.rows[terminal.requestId])
        assertEquals(0, fixture.store.finishCalls)
    }

    @Test
    fun `two equivalent concurrent launches settle creator enqueue failure before retrying`() =
        runTest {
            val fixture = Fixture()
            val firstOperation = TestOperation.pending()
            fixture.work.queueOperation(firstOperation)
            fixture.work.queueOperation(TestOperation.succeeded())

            val first = async { fixture.controller.launch(spec()) }
            fixture.work.enqueueStarted.await()
            val second =
                async { fixture.controller.launch(spec(source = PrivilegeSweepSource.QS_TILE)) }
            runCurrent()

            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(1, fixture.work.enqueued.size)

            firstOperation.fail(IllegalStateException("enqueue failed"))
            advanceUntilIdle()

            val firstResult = assertType<PrivilegeSweepLaunchResult.Rejected>(first.await())
            assertType<PrivilegeSweepLaunchRejection.EnqueueFailed>(firstResult.reason)
            val secondResult = assertType<PrivilegeSweepLaunchResult.Accepted>(second.await())
            assertFalse(secondResult.coalesced)
            assertEquals(2, fixture.work.enqueued.size)
            assertEquals(setOf(secondResult.requestId), fixture.store.rows.keys)
        }

    @Test
    fun `observer does not report failure while enqueue handoff is pending`() = runTest {
        val fixture = Fixture()
        val operation = TestOperation.pending()
        fixture.work.queueOperation(operation)
        val observed = async {
            fixture.controller.observeLatest(PrivilegeSweepSource.MAIN).first { it != null }
        }
        runCurrent()

        val launch = async { fixture.controller.launch(spec()) }
        fixture.work.enqueueStarted.await()
        runCurrent()
        val handoffStatus = observed.takeIf { it.isCompleted }?.await()

        operation.succeed()
        advanceUntilIdle()

        assertType<PrivilegeSweepLaunchResult.Accepted>(launch.await())
        assertFalse(handoffStatus?.phase == PrivilegeSweepPhase.OBSERVER_FAILURE)
        assertEquals(PrivilegeSweepPhase.QUEUED, (handoffStatus ?: observed.await())?.phase)
    }

    @Test
    fun `reconciler cannot orphan Room snapshot in pre-enqueue window`() = runTest {
        val fixture = Fixture()
        val operation = TestOperation.pending()
        fixture.work.queueOperation(operation)

        val launch = async { fixture.controller.launch(spec()) }
        fixture.work.enqueueStarted.await()
        val requestId = fixture.store.rows.keys.single()
        val reconcile = async { fixture.reconciler.reconcile() }
        runCurrent()

        assertFalse(reconcile.isCompleted)
        assertNull(fixture.store.rows.getValue(requestId).terminalState)

        operation.succeed()
        advanceUntilIdle()

        val accepted = assertType<PrivilegeSweepLaunchResult.Accepted>(launch.await())
        reconcile.await()
        assertEquals(requestId, accepted.requestId)
        assertNull(fixture.store.rows.getValue(requestId).terminalState)
        assertEquals(SweepWorkState.ENQUEUED, fixture.work.currentState(accepted.workId))
    }

    @Test
    fun `caller cancellation keeps gate until unresolved enqueue Operation settles`() = runTest {
        val fixture = Fixture()
        val operation = TestOperation.pending()
        fixture.work.queueOperation(operation)
        val observedFailure = CompletableDeferred<Throwable>()

        val launch = async {
            try {
                fixture.controller.launch(spec())
                error("cancelled launch returned normally")
            } catch (throwable: Throwable) {
                observedFailure.complete(throwable)
            }
        }
        fixture.work.enqueueStarted.await()
        val request = fixture.store.rows.values.single()
        launch.cancel()
        val reconcile = async { fixture.reconciler.reconcile() }
        runCurrent()

        assertFalse(observedFailure.isCompleted)
        assertFalse(reconcile.isCompleted)
        assertNull(fixture.store.rows.getValue(request.requestId).terminalState)

        operation.succeed()
        advanceUntilIdle()

        assertType<CancellationException>(observedFailure.await())
        reconcile.await()
        assertNull(fixture.store.rows.getValue(request.requestId).terminalState)
        assertEquals(SweepWorkState.ENQUEUED, fixture.work.currentState(request.workId))
    }

    private fun spec(
        operation: PrivilegeSweepOperation = PrivilegeSweepOperation.CLEAR_CACHE,
        packageNames: List<String> = listOf("com.example.alpha", "com.example.beta"),
        source: PrivilegeSweepSource = PrivilegeSweepSource.MAIN,
        profileId: Long? = null,
    ) = PrivilegeSweepSpec(
        operation = operation,
        packageNames = packageNames,
        freezerMode = if (operation == PrivilegeSweepOperation.FREEZE) FreezerMode.FREEZE else null,
        userId = 0,
        source = source,
        profileId = profileId,
    )

    private fun stored(
        requestId: UUID = UUID.randomUUID(),
        workId: UUID = UUID.randomUUID(),
        operation: PrivilegeSweepOperation = PrivilegeSweepOperation.CLEAR_CACHE,
        createdAt: Long = NOW,
        source: PrivilegeSweepSource = PrivilegeSweepSource.MAIN,
        terminal: StoredSweepTerminal? = null,
        succeeded: Int = 0,
        failed: Int = 0,
        busy: Int = 0,
        unresolved: Int = 0,
        retainUntil: Long? = terminal?.let { NOW + 1 },
    ) = StoredPrivilegeSweep(
        requestId = requestId,
        workId = workId,
        operation = operation,
        freezerMode = if (operation == PrivilegeSweepOperation.FREEZE) FreezerMode.FREEZE else null,
        userId = 0,
        source = source,
        createdAtEpochMs = createdAt,
        targets = listOf("com.example.alpha", "com.example.beta", "com.example.gamma"),
        terminalState = terminal,
        succeeded = succeeded,
        failed = failed,
        busy = busy,
        unresolved = unresolved,
        terminalAtEpochMs = terminal?.let { NOW - 1 },
        retainUntilEpochMs = retainUntil,
    )

    private class Fixture(
        private val notifications: FakeNotificationCapability = FakeNotificationCapability(),
        privileged: Boolean = true,
    ) {
        val events = mutableListOf<String>()
        val store = FakePrivilegeSweepStore(events)
        val work = FakePrivilegeSweepWorkManager()
        val clock = FakePrivilegeSweepClock(NOW)
        val gate = PrivilegeSweepProcessGate()
        val rootStatuses = MutableStateFlow(
            PrivilegeExecutionLane.entries.associateWith { lane ->
                RootLaneStatus(lane, RootLaneMode.ISOLATED)
            }
        )
        private val privilegeState = MutableStateFlow(
            PrivilegeState(
                root = privileged,
                active = if (privileged) PrivilegeMode.ROOT else PrivilegeMode.NONE,
                isReady = true,
            )
        )
        private val privilege = object : PrivilegeStateProvider {
            override val state = privilegeState
        }
        private val rootLaneStatusSource = object : RootLaneStatusSource {
            override val statuses = rootStatuses
        }
        val reconciler = PrivilegeSweepReconciler(store, work, clock, gate)
        val controller = newController()

        fun newController() = DefaultPrivilegeSweepController(
            store = store,
            privilegeState = privilege,
            notifications = notifications,
            workManager = work,
            clock = clock,
            reconciler = reconciler,
            gate = gate,
            queueCanceller = SweepQueueCanceller(store, clock, gate) {},
            rootLaneStatusSource = rootLaneStatusSource,
        )

        init {
            work.onEnqueueSettled = { request, success ->
                if (success) work.setState(request.id, SweepWorkState.ENQUEUED)
            }
            val existing = work.onEnqueue
            work.onEnqueue = { request ->
                events += "enqueue"
                existing?.invoke(request)
            }
        }
    }

    private class FakeNotificationCapability(
        private val appEnabled: Boolean = true,
        private val postPermissionGranted: Boolean = true,
        private val channelImportance: Int? = NotificationManager.IMPORTANCE_LOW,
    ) : ThorJobNotificationCapability {
        override fun canPostJobs(): Boolean = jobNotificationsAvailable(
            appNotificationsEnabled = appEnabled,
            postNotificationsGranted = postPermissionGranted,
            channelImportance = channelImportance,
        )
    }

    private class FakePrivilegeSweepClock(var now: Long) : PrivilegeSweepClock {
        override fun nowMs(): Long = now
    }

    private class FakePrivilegeSweepWorkManager : PrivilegeSweepWorkManager {
        val enqueued = mutableListOf<OneTimeWorkRequest>()
        val enqueueStarted = CompletableDeferred<Unit>()
        var onEnqueue: ((OneTimeWorkRequest) -> Unit)? = null
        var onEnqueueSettled: ((OneTimeWorkRequest, Boolean) -> Unit)? = null
        val activeObservers = mutableMapOf<UUID, Int>().withDefault { 0 }
        private val operations = ArrayDeque<TestOperation>()
        private val states = mutableMapOf<UUID, MutableStateFlow<SweepWorkState?>>()

        fun queueOperation(operation: TestOperation) {
            operations += operation
        }

        override suspend fun enqueue(work: OneTimeWorkRequest): Boolean {
            enqueued += work
            onEnqueue?.invoke(work)
            enqueueStarted.complete(Unit)
            val operation = operations.removeFirstOrNull() ?: TestOperation.succeeded()
            val succeeded = runCatching { operation.awaitSettlement() }.isSuccess
            onEnqueueSettled?.invoke(work, succeeded)
            return succeeded
        }

        override fun observeState(workId: UUID): Flow<SweepWorkState?> = flow {
            activeObservers[workId] = activeObservers.getValue(workId) + 1
            try {
                emitAll(states.getOrPut(workId) { MutableStateFlow(null) })
            } finally {
                activeObservers[workId] = activeObservers.getValue(workId) - 1
            }
        }

        override suspend fun currentState(workId: UUID): SweepWorkState? =
            states.getOrPut(workId) { MutableStateFlow(null) }.value

        fun setState(workId: UUID, state: SweepWorkState?) {
            states.getOrPut(workId) { MutableStateFlow(null) }.value = state
        }
    }

    private class TestOperation private constructor() {
        private val settlement = CompletableDeferred<Unit>()

        suspend fun awaitSettlement() {
            settlement.await()
        }

        fun succeed() {
            settlement.complete(Unit)
        }

        fun fail(throwable: Throwable) {
            settlement.completeExceptionally(throwable)
        }

        companion object {
            fun pending() = TestOperation()
            fun succeeded() = TestOperation().apply { succeed() }
            fun failed(throwable: Throwable) = TestOperation().apply { fail(throwable) }
        }
    }

    private class FakePrivilegeSweepStore(
        private val sharedEvents: MutableList<String>,
    ) : PrivilegeSweepStore {
        val rows = linkedMapOf<UUID, StoredPrivilegeSweep>()
        private val sources =
            mutableMapOf<UUID, MutableMap<String, Long>>()
        val events = mutableListOf<String>()
        var finishCalls = 0
        private val observed = mutableMapOf<UUID, MutableStateFlow<StoredPrivilegeSweep?>>()
        private val retained = MutableStateFlow<List<StoredPrivilegeSweep>>(emptyList())
        private val retainedBySource = PrivilegeSweepSource.entries.associateWith {
            MutableStateFlow<List<StoredPrivilegeSweep>>(emptyList())
        }

        override suspend fun createOrFindEquivalent(
            snapshot: NewPrivilegeSweepSnapshot,
        ): SweepCreateResult {
            val equivalent = rows.values.firstOrNull {
                it.terminalState == null &&
                        it.operation == snapshot.operation &&
                        it.freezerMode == snapshot.freezerMode &&
                        it.userId == snapshot.userId &&
                        it.targets == snapshot.targets
            }
            if (equivalent != null) {
                snapshot.sourceAssociations.forEach { association ->
                    sources.getOrPut(equivalent.requestId, ::mutableMapOf)[association] =
                        snapshot.createdAtEpochMs
                }
                val updated = equivalent.copy(
                    sourceAssociations = equivalent.sourceAssociations + snapshot.sourceAssociations
                )
                rows[equivalent.requestId] = updated
                publish(equivalent.requestId)
                return SweepCreateResult.Equivalent(updated)
            }
            val stored = StoredPrivilegeSweep(
                requestId = snapshot.requestId,
                workId = snapshot.workId,
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
                unresolved = 0,
                terminalAtEpochMs = null,
                retainUntilEpochMs = null,
                sourceAssociations = snapshot.sourceAssociations,
            )
            rows[stored.requestId] = stored
            snapshot.sourceAssociations.forEach { association ->
                sources.getOrPut(stored.requestId, ::mutableMapOf)[association] =
                    snapshot.createdAtEpochMs
            }
            sharedEvents += "persist"
            publish(stored.requestId)
            return SweepCreateResult.Created(stored)
        }

        override suspend fun load(requestId: UUID): StoredPrivilegeSweep? = rows[requestId]

        override fun observe(requestId: UUID): Flow<StoredPrivilegeSweep?> =
            observed.getOrPut(requestId) { MutableStateFlow(rows[requestId]) }

        override fun observeRetained(): Flow<List<StoredPrivilegeSweep>> = retained

        override fun observeRetained(
            source: PrivilegeSweepSource,
        ): Flow<List<StoredPrivilegeSweep>> = retainedBySource.getValue(source)

        override suspend fun resetForRun(requestId: UUID): StoredPrivilegeSweep? {
            val current = rows[requestId]?.takeIf { it.terminalState == null } ?: return null
            val updated = current.copy(succeeded = 0, failed = 0, busy = 0, unresolved = 0)
            rows[requestId] = updated
            publish(requestId)
            return updated
        }

        override suspend fun recordAttempt(
            requestId: UUID,
            outcome: SweepAttemptOutcome,
        ): Boolean {
            val current = rows[requestId]?.takeIf { it.terminalState == null } ?: return false
            rows[requestId] = when (outcome) {
                SweepAttemptOutcome.SUCCEEDED -> current.copy(succeeded = current.succeeded + 1)
                SweepAttemptOutcome.FAILED -> current.copy(failed = current.failed + 1)
                SweepAttemptOutcome.BUSY -> current.copy(busy = current.busy + 1)
            }
            publish(requestId)
            return true
        }

        override suspend fun finish(
            requestId: UUID,
            terminal: StoredSweepTerminal,
            nowMs: Long,
        ): Boolean {
            val current = rows[requestId]?.takeIf { it.terminalState == null } ?: return false
            finishCalls++
            events += "finish"
            rows[requestId] = current.copy(
                terminalState = terminal,
                unresolved = current.targets.size - current.succeeded - current.failed - current.busy,
                terminalAtEpochMs = nowMs,
                retainUntilEpochMs = nowMs + 24 * 60 * 60 * 1_000L,
            )
            publish(requestId)
            return true
        }

        override suspend fun cancelAllNonterminal(nowMs: Long): List<UUID> {
            val ids = rows.values.filter { it.terminalState == null }.map { it.requestId }
            ids.forEach { finish(it, StoredSweepTerminal.CANCELLED, nowMs) }
            return ids
        }

        override suspend fun delete(requestId: UUID) {
            rows.remove(requestId)
            sources.remove(requestId)
            publish(requestId)
        }

        override suspend fun deleteExpired(nowMs: Long): Int {
            events += "prune"
            val ids = rows.values.filter {
                it.terminalState != null && it.retainUntilEpochMs != null &&
                        it.retainUntilEpochMs <= nowMs
            }.map { it.requestId }
            ids.forEach {
                rows.remove(it)
                sources.remove(it)
                publish(it)
            }
            return ids.size
        }

        fun seed(snapshot: StoredPrivilegeSweep) {
            rows[snapshot.requestId] = snapshot
            snapshot.sourceAssociations.forEach { association ->
                sources.getOrPut(snapshot.requestId, ::mutableMapOf)[association] =
                    snapshot.createdAtEpochMs
            }
            publish(snapshot.requestId)
        }

        private fun publish(requestId: UUID) {
            observed.getOrPut(requestId) { MutableStateFlow(null) }.value = rows[requestId]
            val snapshots = rows.values.sortedByDescending { it.createdAtEpochMs }
            retained.value = snapshots
            retainedBySource.forEach { (source, flow) ->
                flow.value = rows.values.mapNotNull { snapshot ->
                    sources[snapshot.requestId]?.get(source.name)?.let { associatedAt ->
                        associatedAt to snapshot
                    }
                }.sortedWith(
                    compareByDescending<Pair<Long, StoredPrivilegeSweep>> { it.first }
                        .thenByDescending { it.second.requestId.toString() }
                ).map { it.second }
            }
        }
    }

    private suspend fun <T> Flow<T>.firstValue(): T = first()

    private inline fun <reified T> assertType(value: Any?): T {
        assertTrue("Expected ${T::class.java.name}, got ${value?.javaClass?.name}", value is T)
        return value as T
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
