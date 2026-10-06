// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.privilege

import com.valhalla.thor.domain.model.RootConfirmation
import com.valhalla.thor.domain.model.RootRefreshStatus
import com.valhalla.thor.util.RootLifecycleDiagnostics
import com.valhalla.thor.util.RootLifecycleEvent
import com.valhalla.thor.util.RootRefreshPhase
import com.valhalla.thor.util.toDiagnosticLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootRefreshDiagnosticsTest {
    @Test
    fun `uncertain refresh diagnostics retain confirmation without exposing failure payload`() = runTest {
        val events = mutableListOf<RootLifecycleEvent>()
        var answer = RootProbeResult(RootProbeOutcome.ROOT)
        val coordinator = RootAvailabilityCoordinator(RootAvailabilityProbe { answer },
            StandardTestDispatcher(testScheduler), RootLifecycleDiagnostics(events::add))
        coordinator.requestRefresh().await()
        for ((outcome, status) in listOf(RootProbeOutcome.BUSY to RootRefreshStatus.BUSY,
            RootProbeOutcome.TIMED_OUT to RootRefreshStatus.TIMED_OUT, RootProbeOutcome.FAILED to RootRefreshStatus.FAILED)) {
            answer = RootProbeResult(outcome, "sensitive package=value\ncommand output")
            coordinator.requestRefresh().await()
            val event = events.last() as RootLifecycleEvent.Refresh
            assertEquals(status, event.status)
            assertEquals(RootConfirmation.ROOT, event.confirmation)
            assertFalse(coordinator.state.value.canAdmitRoot)
            assertEquals(coordinator.state.value.revision, event.revision)
            assertEquals(coordinator.state.value.confirmedRevision, event.confirmedRevision)
        }
        assertTrue(events.none { it.toDiagnosticLine().contains("sensitive") })
    }

    @Test
    fun `cancelled shared waiter does not report cancelling the refresh attempt`() = runTest {
        val answer = CompletableDeferred<RootProbeResult>()
        val events = mutableListOf<RootLifecycleEvent>()
        val coordinator = RootAvailabilityCoordinator(RootAvailabilityProbe { answer.await() },
            StandardTestDispatcher(testScheduler), RootLifecycleDiagnostics(events::add))
        val first = async { coordinator.requestRefresh().await() }
        val second = async { coordinator.requestRefresh().await() }
        runCurrent()
        first.cancelAndJoin()
        answer.complete(RootProbeResult(RootProbeOutcome.ROOT))
        assertTrue(second.await().canAdmitRoot)
        assertEquals(listOf(RootRefreshPhase.STARTED, RootRefreshPhase.COMPLETED),
            events.filterIsInstance<RootLifecycleEvent.Refresh>().map { it.phase })
    }

    @Test
    fun `diagnostic failure cannot break refresh admission or idle retry`() = runTest {
        var probes = 0
        val coordinator = RootAvailabilityCoordinator(RootAvailabilityProbe {
            probes++
            RootProbeResult(RootProbeOutcome.ROOT)
        }, StandardTestDispatcher(testScheduler), RootLifecycleDiagnostics { error("diagnostic failure") })
        coordinator.requestRefresh().await()
        coordinator.withRootAdmission {
            assertEquals(RootRefreshStatus.BUSY, coordinator.requestRefresh().await().refreshStatus)
            assertEquals(1, probes)
        }
        runCurrent()
        assertEquals(2, probes)
        assertTrue(coordinator.state.value.canAdmitRoot)
    }
}
