// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DataSyncCoordinatorTest {

    @Test
    fun `repeated wakes share one FIFO drain and wait for both gates before execution`() = runTest {
        val events = mutableListOf<String>()
        val legacyOpen = CompletableDeferred<Unit>()
        val sweepOpen = CompletableDeferred<Unit>()
        val claims = ArrayDeque(listOf(claim(1), claim(2)))
        var concurrent = 0
        var maxConcurrent = 0
        val coordinator = coordinator(
            awaitLegacyDrain = {
                events += "legacy"
                legacyOpen.await()
            },
            awaitLaunchSweep = {
                events += "sweep"
                sweepOpen.await()
                true
            },
            claimNext = { _, token ->
                if (claims.isEmpty()) null else claims.removeFirst().copy(claimToken = token)
            },
            executeClaim = { claimed, _ ->
                concurrent += 1
                maxConcurrent = maxOf(maxConcurrent, concurrent)
                events += "run:${claimed.taskId}"
                concurrent -= 1
                completed()
            },
        )

        repeat(20) { coordinator.wake {} }
        advanceUntilIdle()
        assertEquals(listOf("legacy"), events)

        legacyOpen.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("legacy", "sweep"), events)

        sweepOpen.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, maxConcurrent)
        assertEquals(listOf(TASK_1, TASK_2), events.filter { it.startsWith("run:") }.map {
            UUID.fromString(it.removePrefix("run:"))
        })
        assertEquals(1, coordinator.drainLaunchCountForTest)
    }

    @Test
    fun `claimed task is announced before its runner starts`() = runTest {
        val events = mutableListOf<String>()
        val claims = ArrayDeque(listOf(claim(1)))
        val coordinator = coordinator(
            claimNext = { _, token ->
                if (claims.isEmpty()) null else claims.removeFirst().copy(claimToken = token)
            },
            executeClaim = { claimed, _ ->
                events += "run:${claimed.taskId}"
                completed()
            },
        )

        coordinator.wake(
            onClaimed = { taskId, _ -> events += "active:$taskId" },
            onDrained = {},
        )
        advanceUntilIdle()

        assertEquals(listOf("active:$TASK_1", "run:$TASK_1"), events)
    }

    @Test
    fun `ordinary runner failure settles and later queue entry still runs`() = runTest {
        val claims = ArrayDeque(listOf(claim(1), claim(2)))
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            claimNext = { _, token ->
                if (claims.isEmpty()) null else claims.removeFirst().copy(claimToken = token)
            },
            executeClaim = { claimed, _ ->
                events += "run:${claimed.taskId}"
                if (claimed.taskId == TASK_1) error("boom")
                completed()
            },
            persistOutcome = { claimed, outcome ->
                events += "settle:${claimed.taskId}:${outcome::class.simpleName}"
            },
        )

        coordinator.wake {}
        advanceUntilIdle()

        assertTrue(events.any { it == "settle:$TASK_1:TaskFailed" })
        assertTrue(events.any { it == "run:$TASK_2" })
    }

    @Test
    fun `timed out normal settlement parks drain before another claim`() = runTest {
        val settlementStarted = CompletableDeferred<Unit>()
        var claimCalls = 0
        var stops = 0
        val coordinator = coordinator(
            claimNext = { _, token ->
                claimCalls += 1
                when (claimCalls) {
                    1 -> claim(1).copy(claimToken = token)
                    2 -> claim(2).copy(claimToken = token)
                    else -> null
                }
            },
            executeClaim = { _, _ -> completed() },
            persistOutcome = { claimed, _ ->
                if (claimed.taskId == TASK_1) {
                    settlementStarted.complete(Unit)
                    awaitCancellation()
                }
            },
        )

        coordinator.wake { stops += 1 }
        runCurrent()
        settlementStarted.await()
        advanceTimeBy(2.seconds + 1.milliseconds)
        advanceUntilIdle()

        assertEquals(1, claimCalls)
        assertEquals(1, stops)
    }

    @Test
    fun `timed out cancellation settlement parks drain before another claim`() = runTest {
        val registry = DataTaskOwnerRegistry()
        val runnerStarted = CompletableDeferred<Unit>()
        val settlementStarted = CompletableDeferred<Unit>()
        var claimCalls = 0
        var stops = 0
        val coordinator = coordinator(
            registry = registry,
            claimNext = { _, token ->
                claimCalls += 1
                when (claimCalls) {
                    1 -> claim(1).copy(claimToken = token)
                    2 -> claim(2).copy(claimToken = token)
                    else -> null
                }
            },
            executeClaim = { _, _ ->
                runnerStarted.complete(Unit)
                awaitCancellation()
            },
            persistOutcome = { claimed, outcome ->
                if (claimed.taskId == TASK_1 && outcome is DataTaskRunOutcome.Cancelled) {
                    settlementStarted.complete(Unit)
                    awaitCancellation()
                }
            },
        )

        coordinator.wake { stops += 1 }
        runCurrent()
        runnerStarted.await()
        assertTrue(registry.cancelActive(TASK_1))
        runCurrent()
        settlementStarted.await()
        advanceTimeBy(2.seconds + 1.milliseconds)
        advanceUntilIdle()

        assertEquals(1, claimCalls)
        assertEquals(1, stops)
    }

    @Test
    fun `claim token is registered before claim and stays live through cleanup and settlement`() =
        runTest {
            val registry = DataTaskOwnerRegistry()
            val events = mutableListOf<String>()
            val claims = ArrayDeque(listOf(claim(1)))
            val coordinator = coordinator(
                registry = registry,
                claimNext = { _, token ->
                    if (claims.isEmpty()) {
                        null
                    } else {
                        assertTrue(registry.isLive(TASK_1, token))
                        claims.removeFirst().copy(claimToken = token)
                    }
                },
                executeClaim = { _, _ -> completed() },
                cleanupClaim = { claimed ->
                    assertTrue(registry.isLive(claimed.taskId, claimed.claimToken))
                    events += "cleanup"
                },
                persistOutcome = { claimed, _ ->
                    assertTrue(registry.isLive(claimed.taskId, claimed.claimToken))
                    events += "settle"
                },
            )

            coordinator.wake {}
            advanceUntilIdle()

            assertEquals(listOf("settle", "cleanup"), events)
            assertFalse(registry.isLive(TASK_1, coordinator.lastClaimTokenForTest!!))
        }

    @Test
    fun `timeout cancels active child settles after cleanup and prevents another claim`() =
        runTest {
            val events = mutableListOf<String>()
            val claims = ArrayDeque(listOf(claim(1), claim(2)))
            val running = CompletableDeferred<Unit>()
            var claimCalls = 0
            val coordinator = coordinator(
                claimNext = { _, token ->
                    claimCalls += 1
                    if (claims.isEmpty()) null else claims.removeFirst().copy(claimToken = token)
                },
                executeClaim = { _, _ ->
                    try {
                        running.await()
                        completed()
                    } finally {
                        events += "child-cancelled"
                    }
                },
                settleTimeout = {
                    events += "timeout-settle"
                    true
                },
                cleanupClaim = { events += "cleanup" },
                persistOutcome = { _, outcome -> events += "settle:${outcome::class.simpleName}" },
            )

            coordinator.wake { events += "stop" }
            testScheduler.runCurrent()
            coordinator.stopClaimsAndInterrupt()
            advanceUntilIdle()

            assertEquals(1, claimCalls)
            assertEquals(
                listOf(
                    "child-cancelled",
                    "timeout-settle",
                    "cleanup",
                    "stop",
                ),
                events,
            )
        }

    @Test
    fun `timeout settles a claim that commits while claim call is being cancelled`() = runTest {
        val claimEntered = CompletableDeferred<Unit>()
        val releaseClaim = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var executed = false
        val coordinator = coordinator(
            claimNext = { _, token ->
                claimEntered.complete(Unit)
                try {
                    releaseClaim.await()
                } catch (_: CancellationException) {
                    withContext(NonCancellable) { releaseClaim.await() }
                }
                claim(1).copy(claimToken = token)
            },
            executeClaim = { _, _ ->
                executed = true
                completed()
            },
            settleTimeout = {
                events += "timeout-settle"
                true
            },
            cleanupClaim = { events += "cleanup" },
            persistOutcome = { _, outcome -> events += "settle:${outcome::class.simpleName}" },
        )

        coordinator.wake { events += "stop" }
        testScheduler.runCurrent()
        claimEntered.await()
        val stopping = launch { coordinator.stopClaimsAndInterrupt() }
        runCurrent()
        releaseClaim.complete(Unit)
        advanceUntilIdle()
        stopping.join()

        assertFalse(executed)
        assertEquals(
            listOf("timeout-settle", "cleanup", "stop"),
            events,
        )
    }

    @Test
    fun `launch sweep timeout parks generation before any Room claim`() = runTest {
        var claimCalls = 0
        var stops = 0
        val coordinator = coordinator(
            awaitLaunchSweep = { false },
            claimNext = { _, _ ->
                claimCalls += 1
                claim(1)
            },
        )

        coordinator.wake { stops += 1 }
        advanceUntilIdle()

        assertEquals(0, claimCalls)
        assertEquals(1, stops)
    }

    @Test
    fun `replacement coordinator waits for live generation before claiming later row`() = runTest {
        val registry = DataTaskOwnerRegistry()
        val firstRunning = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var firstClaimed = false
        var secondClaimed = false
        val first = coordinator(
            registry = registry,
            claimNext = { _, token ->
                if (firstClaimed) null else claim(1).copy(claimToken = token).also {
                    firstClaimed = true
                }
            },
            executeClaim = { claimed, _ ->
                events += "run:${claimed.taskId}"
                firstRunning.complete(Unit)
                releaseFirst.await()
                completed()
            },
        )
        val second = coordinator(
            registry = registry,
            claimNext = { _, token ->
                if (secondClaimed) null else claim(2).copy(claimToken = token).also {
                    secondClaimed = true
                }
            },
            executeClaim = { claimed, _ ->
                events += "run:${claimed.taskId}"
                completed()
            },
        )

        first.wake {}
        runCurrent()
        firstRunning.await()
        second.wake {}
        runCurrent()

        assertEquals(listOf("run:$TASK_1"), events)
        assertFalse(secondClaimed)

        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("run:$TASK_1", "run:$TASK_2"), events)
    }

    @Test
    fun `cancellation retained before child entry still settles exact claim`() = runTest {
        val registry = DataTaskOwnerRegistry()
        var executed = false
        val settled = mutableListOf<DataTaskRunOutcome>()
        var claimed = false
        val coordinator = coordinator(
            registry = registry,
            claimNext = { _, token ->
                if (claimed) null else claim(1).copy(claimToken = token).also { claimed = true }
            },
            executeClaim = { _, _ ->
                executed = true
                completed()
            },
            persistOutcome = { _, outcome -> settled += outcome },
        )

        coordinator.wake(
            onClaimed = { taskId, _ -> assertTrue(registry.cancelActive(taskId)) },
            onDrained = {},
        )
        advanceUntilIdle()

        assertFalse(executed)
        assertEquals(listOf(DataTaskRunOutcome.Cancelled), settled)
    }

    @Test
    fun `wake concurrent with infrastructure failure launches a fresh drain`() = runTest {
        val recoveryEntered = CompletableDeferred<Unit>()
        val failFirstRecovery = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var recoveryCalls = 0
        var claimed = false
        val coordinator = coordinator(
            recoverClaims = { _, _ ->
                recoveryCalls += 1
                if (recoveryCalls == 1) {
                    recoveryEntered.complete(Unit)
                    failFirstRecovery.await()
                    error("room unavailable")
                }
            },
            claimNext = { _, token ->
                if (claimed) null else claim(1).copy(claimToken = token).also { claimed = true }
            },
            executeClaim = { claim, _ ->
                events += "run:${claim.taskId}"
                completed()
            },
        )

        coordinator.wake { events += "stop:first" }
        runCurrent()
        recoveryEntered.await()
        coordinator.wake { events += "stop:latest" }
        failFirstRecovery.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, recoveryCalls)
        assertTrue(events.contains("run:$TASK_1"))
        assertEquals("stop:latest", events.last())
    }

    @Test
    fun `ordinary drain infrastructure failure stops the service generation`() = runTest {
        var stops = 0
        val coordinator = coordinator(
            recoverClaims = { _, _ -> error("room unavailable") },
        )

        coordinator.wake { stops += 1 }
        advanceUntilIdle()

        assertEquals(1, stops)
    }

    @Test
    fun `recovery always receives shared registry liveness lookup`() = runTest {
        val registry = DataTaskOwnerRegistry()
        registry.registerProvisional(CLAIM)
        registry.bindTask(TASK_1, CLAIM)
        var observed = false
        val coordinator = coordinator(
            registry = registry,
            recoverClaims = { _, isLive -> observed = isLive(TASK_1, CLAIM) },
        )

        coordinator.wake {}
        advanceUntilIdle()

        assertTrue(observed)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        registry: DataTaskOwnerRegistry = DataTaskOwnerRegistry(),
        awaitLegacyDrain: suspend () -> Unit = {},
        awaitLaunchSweep: suspend () -> Boolean = { true },
        recoverClaims: suspend (String, (UUID, String) -> Boolean) -> Unit = { _, _ -> },
        claimNext: suspend (String, String) -> DataSyncClaim? = { _, _ -> null },
        executeClaim: suspend (DataSyncClaim, DataTaskCheckpointSink) -> DataTaskRunOutcome = { _, _ ->
            completed()
        },
        settleTimeout: suspend (DataSyncClaim) -> Boolean = { true },
        persistOutcome: suspend (DataSyncClaim, DataTaskRunOutcome) -> Unit = { _, _ -> },
        cleanupClaim: suspend (DataSyncClaim) -> Unit = {},
    ) = DataSyncCoordinator(
        dispatcher = StandardTestDispatcher(testScheduler),
        ownerRegistry = registry,
        awaitLegacyDrain = awaitLegacyDrain,
        awaitLaunchSweep = awaitLaunchSweep,
        recoverClaims = recoverClaims,
        claimNext = claimNext,
        executeClaim = executeClaim,
        settleTimeout = settleTimeout,
        persistOutcome = persistOutcome,
        cleanupClaim = cleanupClaim,
        checkpointSink = { DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED } },
        finishDrainIfEmpty = { onEmpty -> onEmpty(); true },
        sessionToken = "session",
        claimTokenFactory = { "claim-${nextClaim++}" },
        nowMs = { 1_000L },
    )

    private fun claim(number: Int) = DataSyncClaim(
        taskId = if (number == 1) TASK_1 else TASK_2,
        claimToken = "unbound-$number",
    )

    private fun completed() = DataTaskRunOutcome.ItemCompleted(
        DataTaskItemResult(
            terminalState = DataTaskItemTerminalState.SUCCEEDED,
            resultCode = DataTaskResultCode("DONE"),
            warnings = emptyList(),
            outputs = emptyList(),
            finishedAtEpochMs = 1_000L,
        )
    )

    private companion object {
        val TASK_1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000091")
        val TASK_2: UUID = UUID.fromString("00000000-0000-0000-0000-000000000092")
        const val CLAIM = "claim-live"
        var nextClaim = 0
    }
}
