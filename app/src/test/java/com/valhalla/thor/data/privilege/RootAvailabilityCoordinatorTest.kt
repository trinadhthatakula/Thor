// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.privilege

import com.valhalla.thor.domain.model.RootAdmissionUnavailable
import com.valhalla.thor.domain.model.RootConfirmation
import com.valhalla.thor.domain.model.RootRefreshStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootAvailabilityCoordinatorTest {
    @Test
    fun `concurrent refresh waiters share one attempt and cancellation cannot cancel it`() = runTest {
        val answer = CompletableDeferred<RootProbeResult>()
        var calls = 0
        val coordinator = RootAvailabilityCoordinator(
            RootAvailabilityProbe { calls++; answer.await() }, StandardTestDispatcher(testScheduler),
        )
        val first = async { coordinator.refreshAndAwait() }
        val second = async { coordinator.refreshAndAwait() }
        runCurrent()
        assertEquals(1, calls)
        assertEquals(RootRefreshStatus.CHECKING, coordinator.state.value.refreshStatus)
        first.cancelAndJoin()
        answer.complete(RootProbeResult(RootProbeOutcome.ROOT))
        runCurrent()

        assertEquals(RootConfirmation.ROOT, second.await().confirmation)
        assertTrue(coordinator.state.value.canAdmitRoot)
        assertEquals(1, calls)
    }

    @Test
    fun `equal confirmations publish new revisions and initial observers reuse the result`() = runTest {
        var calls = 0
        val coordinator = RootAvailabilityCoordinator(
            RootAvailabilityProbe { calls++; RootProbeResult(RootProbeOutcome.ROOT) },
            StandardTestDispatcher(testScheduler),
        )
        val initial = async { coordinator.awaitInitialObservation() }
        runCurrent()
        val first = initial.await()
        assertSame(first, coordinator.awaitInitialObservation())
        assertEquals(1, calls)

        val secondAttempt = coordinator.requestRefresh()
        assertTrue(coordinator.state.value.revision > first.revision)
        assertFalse(coordinator.state.value.canAdmitRoot)
        assertEquals(first.confirmedRevision, coordinator.state.value.confirmedRevision)
        runCurrent()
        val second = secondAttempt.await()
        assertEquals(first.confirmation, second.confirmation)
        assertTrue(second.revision > first.revision)
        assertTrue(second.confirmedRevision > first.confirmedRevision)
        assertEquals(2, calls)
    }

    @Test
    fun `uncertain results retain root and non-root confirmation without allowing admission`() = runTest {
        for (confirmation in listOf(RootProbeOutcome.ROOT, RootProbeOutcome.NON_ROOT)) {
            var next = RootProbeResult(confirmation)
            val coordinator = RootAvailabilityCoordinator(
                RootAvailabilityProbe { next }, StandardTestDispatcher(testScheduler),
            )
            val initial = coordinator.requestRefresh()
            runCurrent()
            val confirmed = initial.await()
            for ((outcome, status) in listOf(
                RootProbeOutcome.BUSY to RootRefreshStatus.BUSY,
                RootProbeOutcome.TIMED_OUT to RootRefreshStatus.TIMED_OUT,
                RootProbeOutcome.FAILED to RootRefreshStatus.FAILED,
            )) {
                next = RootProbeResult(outcome, "probe detail")
                val refresh = coordinator.requestRefresh()
                runCurrent()
                val uncertain = refresh.await()
                assertEquals(confirmed.confirmation, uncertain.confirmation)
                assertEquals(confirmed.confirmedRevision, uncertain.confirmedRevision)
                assertEquals(status, uncertain.refreshStatus)
                assertEquals("probe detail", uncertain.failure)
                assertFalse(uncertain.canAdmitRoot)
                val failure = runCatching { coordinator.withRootAdmission { error("must not run") } }.exceptionOrNull()
                assertTrue(failure is RootAdmissionUnavailable)
                assertSame(uncertain, (failure as RootAdmissionUnavailable).availability)
            }
        }
    }

    @Test
    fun `initial failure is unknown rather than confirmed non-root and explicit retry recovers`() = runTest {
        var fail = true
        val coordinator = RootAvailabilityCoordinator(
            RootAvailabilityProbe {
                if (fail) throw IllegalStateException("startup failed")
                RootProbeResult(RootProbeOutcome.NON_ROOT)
            }, StandardTestDispatcher(testScheduler),
        )
        val failed = coordinator.requestRefresh()
        runCurrent()
        assertEquals(RootConfirmation.UNKNOWN, failed.await().confirmation)
        assertEquals(RootRefreshStatus.FAILED, coordinator.state.value.refreshStatus)
        assertTrue(coordinator.state.value.hasCompletedRefresh)
        fail = false
        val retry = coordinator.requestRefresh()
        runCurrent()
        assertEquals(RootConfirmation.NON_ROOT, retry.await().confirmation)
        assertEquals(RootRefreshStatus.IDLE, coordinator.state.value.refreshStatus)
    }

    @Test
    fun `accepted operation keeps nested admission through busy refresh then retries exactly once on idle`() = runTest {
        var calls = 0
        val coordinator = RootAvailabilityCoordinator(
            RootAvailabilityProbe { calls++; RootProbeResult(RootProbeOutcome.ROOT) },
            StandardTestDispatcher(testScheduler),
        )
        coordinator.requestRefresh()
        runCurrent()
        val entered = CompletableDeferred<Unit>()
        val continueOperation = CompletableDeferred<Unit>()
        var nestedRan = false
        val operation = launch {
            coordinator.withRootAdmission {
                entered.complete(Unit)
                continueOperation.await()
                coordinator.withRootAdmission { nestedRan = true }
            }
        }
        entered.await()
        val busy = coordinator.requestRefresh()
        assertEquals(RootRefreshStatus.BUSY, busy.await().refreshStatus)
        assertSame(busy, coordinator.requestRefresh())
        assertEquals(1, calls)
        val rejected = runCatching { coordinator.withRootAdmission { error("must not start") } }.exceptionOrNull()
        assertTrue(rejected is RootAdmissionUnavailable)

        continueOperation.complete(Unit)
        operation.join()
        runCurrent()
        assertTrue(nestedRan)
        assertEquals(2, calls)
        assertTrue(coordinator.state.value.canAdmitRoot)
        coordinator.onRootWorkIdle()
        runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun `cancelled accepted work retains admission until noncancellable drain completes`() = runTest {
        var calls = 0
        val coordinator = RootAvailabilityCoordinator(
            RootAvailabilityProbe { calls++; RootProbeResult(RootProbeOutcome.ROOT) },
            StandardTestDispatcher(testScheduler),
        )
        coordinator.requestRefresh()
        runCurrent()
        val started = CompletableDeferred<Unit>()
        val drain = CompletableDeferred<Unit>()
        val operation = launch {
            coordinator.withRootAdmission {
                try {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                } finally {
                    withContext(NonCancellable) { drain.await() }
                }
            }
        }
        started.await()
        coordinator.requestRefresh()
        operation.cancel()
        runCurrent()
        assertEquals(1, calls)
        assertEquals(RootRefreshStatus.BUSY, coordinator.state.value.refreshStatus)
        drain.complete(Unit)
        operation.join()
        runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun `busy adapter outcome has only one event-driven idle retry`() = runTest {
        var calls = 0
        val coordinator = RootAvailabilityCoordinator(
            RootAvailabilityProbe { calls++; RootProbeResult(RootProbeOutcome.BUSY) },
            StandardTestDispatcher(testScheduler),
        )
        coordinator.requestRefresh()
        runCurrent()
        coordinator.onRootWorkIdle()
        runCurrent()
        coordinator.onRootWorkIdle()
        runCurrent()
        assertEquals(2, calls)
        assertEquals(RootRefreshStatus.BUSY, coordinator.state.value.refreshStatus)
    }

    @Test
    fun `idle event arriving before busy callback is retained for one retry`() = runTest {
        val firstResult = CompletableDeferred<RootProbeResult>()
        var calls = 0
        val coordinator = RootAvailabilityCoordinator(
            RootAvailabilityProbe {
                calls++
                if (calls == 1) firstResult.await() else RootProbeResult(RootProbeOutcome.BUSY)
            }, StandardTestDispatcher(testScheduler),
        )
        val first = coordinator.requestRefresh()
        runCurrent()
        coordinator.onRootWorkIdle()
        assertEquals(1, calls)
        firstResult.complete(RootProbeResult(RootProbeOutcome.BUSY))
        runCurrent()
        assertEquals(RootRefreshStatus.BUSY, first.await().refreshStatus)
        assertEquals(2, calls)
        coordinator.onRootWorkIdle()
        runCurrent()
        assertEquals(2, calls)
    }
}
