// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.manager

import android.app.Application
import com.valhalla.thor.data.gateway.root.DefaultRootLaneStatusSource
import com.valhalla.thor.data.privilege.RootAvailabilityCoordinator
import com.valhalla.thor.data.privilege.RootAvailabilityProbe
import com.valhalla.thor.data.privilege.RootProbeOutcome
import com.valhalla.thor.data.privilege.RootProbeResult
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.RootConfirmation
import com.valhalla.thor.domain.model.RootAvailabilityState
import com.valhalla.thor.domain.model.RootRefreshStatus
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.repository.RootRefreshController
import com.valhalla.thor.domain.repository.RootRefreshRequest
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.FakeSystemRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PrivilegeManagerRefreshTest {
    @Test
    fun `manager completion waits until matching root revision reaches its public state`() = runTest {
        val confirmed = RootAvailabilityState(
            confirmation = RootConfirmation.ROOT, revision = 2, confirmedRevision = 2, hasCompletedRefresh = true,
        )
        val refreshed = confirmed.copy(revision = 4, confirmedRevision = 4)
        val root = object : RootRefreshController {
            override val state = MutableStateFlow(confirmed)
            override suspend fun awaitInitialObservation() = state.value
            override fun requestRefresh(): RootRefreshRequest {
                state.value = confirmed.copy(refreshStatus = RootRefreshStatus.CHECKING, revision = 3)
                return RootRefreshRequest { refreshed }
            }
            override fun onRootWorkIdle() = Unit
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = PrivilegeManager(
            FakeSystemRepository(), FakePreferenceRepository(), dispatcher, dispatcher, root,
            DefaultRootLaneStatusSource(),
        )
        runCurrent()
        val waiting = async { manager.refreshAndAwait() }
        runCurrent()
        assertFalse(waiting.isCompleted)

        root.state.value = refreshed
        runCurrent()
        assertEquals(refreshed, waiting.await().rootAvailability)
    }

    @Test
    fun `equal refresh completes and cancelling one manager waiter preserves shared attempt`() = runTest {
        val answer = CompletableDeferred<RootProbeResult>()
        var calls = 0
        val fixture = fixture(StandardTestDispatcher(testScheduler), RootAvailabilityProbe {
            calls++
            if (calls == 1) RootProbeResult(RootProbeOutcome.ROOT) else answer.await()
        })
        runCurrent()
        assertTrue(fixture.manager.state.value.isReady)
        val initialRevision = fixture.manager.state.value.rootAvailability.revision

        val first = async { fixture.manager.refreshAndAwait() }
        val second = async { fixture.manager.refreshAndAwait() }
        runCurrent()
        assertEquals(2, calls)
        assertTrue(fixture.manager.state.value.root)
        assertEquals(RootRefreshStatus.CHECKING, fixture.manager.state.value.rootAvailability.refreshStatus)
        first.cancelAndJoin()
        answer.complete(RootProbeResult(RootProbeOutcome.ROOT))
        runCurrent()

        val refreshed = second.await()
        assertTrue(refreshed.isReady)
        assertTrue(refreshed.root)
        assertTrue(refreshed.rootAvailability.revision > initialRevision)
        assertEquals(RootRefreshStatus.IDLE, refreshed.rootAvailability.refreshStatus)
        assertEquals(PrivilegeMode.ROOT, refreshed.active)
        assertEquals(2, calls)
    }

    @Test
    fun `transient refresh keeps root confirmation and explicit alternative preference`() = runTest {
        var result = RootProbeResult(RootProbeOutcome.ROOT)
        val fixture = fixture(
            StandardTestDispatcher(testScheduler), RootAvailabilityProbe { result },
            preferred = PrivilegeMode.SHIZUKU,
        )
        runCurrent()
        for ((outcome, expectedStatus) in listOf(
            RootProbeOutcome.BUSY to RootRefreshStatus.BUSY,
            RootProbeOutcome.TIMED_OUT to RootRefreshStatus.TIMED_OUT,
            RootProbeOutcome.FAILED to RootRefreshStatus.FAILED,
        )) {
            result = RootProbeResult(outcome)
            val refresh = async { fixture.manager.refreshAndAwait() }
            runCurrent()
            val state = refresh.await()
            assertTrue(state.root)
            assertEquals(RootConfirmation.ROOT, state.rootAvailability.confirmation)
            assertEquals(expectedStatus, state.rootAvailability.refreshStatus)
            assertEquals(PrivilegeMode.SHIZUKU, state.active)
            assertEquals(PrivilegeMode.SHIZUKU, fixture.preferences.userPreferences.first().preferredPrivilegeMode)
        }
    }

    @Test
    fun `startup timeout is ready but unresolved and confirmed non-root enables fallback`() = runTest {
        var result = RootProbeResult(RootProbeOutcome.TIMED_OUT)
        val fixture = fixture(StandardTestDispatcher(testScheduler), RootAvailabilityProbe { result })
        runCurrent()
        val uncertain = fixture.manager.state.value
        assertTrue(uncertain.isReady)
        assertFalse(uncertain.root)
        assertEquals(RootConfirmation.UNKNOWN, uncertain.rootAvailability.confirmation)
        assertEquals(RootRefreshStatus.TIMED_OUT, uncertain.rootAvailability.refreshStatus)
        assertEquals(PrivilegeMode.ROOT, uncertain.active)

        result = RootProbeResult(RootProbeOutcome.NON_ROOT)
        val refresh = async { fixture.manager.refreshAndAwait() }
        runCurrent()
        assertEquals(PrivilegeMode.SHIZUKU, refresh.await().active)
        assertEquals(PrivilegeMode.ROOT, fixture.preferences.userPreferences.first().preferredPrivilegeMode)
    }

    @Test
    fun `lane idle before busy completion triggers one retry through production manager observer`() = runTest {
        val busyResult = CompletableDeferred<RootProbeResult>()
        var calls = 0
        val fixture = fixture(StandardTestDispatcher(testScheduler), RootAvailabilityProbe {
            calls++
            if (calls == 2) busyResult.await() else RootProbeResult(RootProbeOutcome.ROOT)
        })
        runCurrent()
        fixture.lanes.commandStarted(PrivilegeExecutionLane.INTERACTIVE, PrivilegeCommandClass("external.read"))
        runCurrent()
        val refresh = async { fixture.manager.refreshAndAwait() }
        runCurrent()
        fixture.lanes.commandFinished(PrivilegeExecutionLane.INTERACTIVE)
        runCurrent()
        busyResult.complete(RootProbeResult(RootProbeOutcome.BUSY))
        runCurrent()
        refresh.await()

        assertEquals(3, calls)
        assertEquals(RootRefreshStatus.IDLE, fixture.manager.state.value.rootAvailability.refreshStatus)
        fixture.lanes.commandStarted(PrivilegeExecutionLane.INTERACTIVE, PrivilegeCommandClass("later.read"))
        runCurrent()
        fixture.lanes.commandFinished(PrivilegeExecutionLane.INTERACTIVE)
        runCurrent()
        assertEquals(3, calls)
    }

    private fun fixture(
        dispatcher: CoroutineDispatcher,
        probe: RootAvailabilityProbe,
        preferred: PrivilegeMode = PrivilegeMode.ROOT,
    ): Fixture {
        val preferences = FakePreferenceRepository(UserPreferences(preferredPrivilegeMode = preferred))
        val system = object : SystemRepository by FakeSystemRepository() {
            override suspend fun isRootAvailable(execution: PrivilegeExecutionContext): Boolean =
                error("PrivilegeManager must observe the root coordinator, not probe the gateway")
            override suspend fun isShizukuAvailable(): Boolean = true
            override suspend fun isDhizukuAvailable(): Boolean = true
        }
        val lanes = DefaultRootLaneStatusSource()
        return Fixture(
            PrivilegeManager(
                system, preferences, dispatcher, dispatcher, RootAvailabilityCoordinator(probe, dispatcher), lanes,
            ),
            preferences,
            lanes,
        )
    }

    private data class Fixture(
        val manager: PrivilegeManager,
        val preferences: FakePreferenceRepository,
        val lanes: DefaultRootLaneStatusSource,
    )
}
