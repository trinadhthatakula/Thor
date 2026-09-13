// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.data.service.ForegroundTaskWakeLock
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
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
                DataTaskSinkWrite.APPLIED
            },
        )

        coordinator.wake {}
        advanceUntilIdle()

        assertTrue(events.any { it == "settle:$TASK_1:TaskFailed" })
        assertTrue(events.any { it == "run:$TASK_2" })
    }

    @Test
    fun `timed out normal settlement exact release continues queue arbitration`() = runTest {
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
                DataTaskSinkWrite.APPLIED
            },
        )

        coordinator.wake { stops += 1 }
        runCurrent()
        settlementStarted.await()
        advanceTimeBy(2.seconds + 1.milliseconds)
        advanceUntilIdle()

        assertEquals(3, claimCalls)
        assertEquals(1, stops)
    }

    @Test
    fun `timed out cancellation release reaches another claim without a false drain`() = runTest {
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
                DataTaskSinkWrite.APPLIED
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

        assertEquals(2, claimCalls)
        assertEquals(0, stops)
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
                    DataTaskSinkWrite.APPLIED
                },
            )

            coordinator.wake {}
            advanceUntilIdle()

            assertEquals(listOf("settle", "cleanup"), events)
            assertFalse(registry.isLive(TASK_1, coordinator.lastClaimTokenForTest!!))
        }

    @Test
    fun `exactly released non-applied outcome is reclaimed and drained transactionally`() =
        runTest {
            listOf(completed(), DataTaskRunOutcome.OwnershipLost).forEach { outcome ->
                val registry = DataTaskOwnerRegistry()
                val events = mutableListOf<String>()
                val claimedTokens = mutableListOf<String>()
                var claimCalls = 0
                var executionCalls = 0
                var persistenceCalls = 0
                var finishCalls = 0
                val coordinator = coordinator(
                    registry = registry,
                    claimNext = { _, token ->
                        claimCalls += 1
                        if (claimCalls <= 2) {
                            claimedTokens += token
                            claim(1).copy(claimToken = token)
                        } else {
                            null
                        }
                    },
                    executeClaim = { _, _ ->
                        executionCalls += 1
                        events += "run:$executionCalls"
                        outcome
                    },
                    persistOutcome = { claimed, persisted ->
                        assertTrue(registry.isLive(claimed.taskId, claimed.claimToken))
                        assertEquals(outcome, persisted)
                        persistenceCalls += 1
                        if (persistenceCalls == 1) {
                            events += "settle-lost"
                            DataTaskSinkWrite.OWNERSHIP_LOST
                        } else {
                            events += "settle-applied"
                            DataTaskSinkWrite.APPLIED
                        }
                    },
                    settleTimeout = { claimed ->
                        assertTrue(registry.isLive(claimed.taskId, claimed.claimToken))
                        events += "release"
                        true
                    },
                    cleanupClaim = { claimed ->
                        assertTrue(registry.isLive(claimed.taskId, claimed.claimToken))
                        events += "cleanup"
                    },
                    finishDrainIfEmpty = { onEmpty ->
                        finishCalls += 1
                        events += "finish-empty"
                        onEmpty()
                        true
                    },
                )

                val generation = coordinator.wake { events += "drained" }
                advanceUntilIdle()

                assertEquals(1L, generation)
                assertEquals(
                    listOf(
                        "run:1",
                        "settle-lost",
                        "release",
                        "cleanup",
                        "run:2",
                        "settle-applied",
                        "cleanup",
                        "finish-empty",
                        "drained",
                    ),
                    events,
                )
                assertEquals(3, claimCalls)
                assertEquals(2, executionCalls)
                assertEquals(2, persistenceCalls)
                assertEquals(1, finishCalls)
                assertEquals(1, coordinator.drainLaunchCountForTest)
                assertEquals(0, coordinator.reconciliationJobCountForTest)
                assertFalse(coordinator.hasDrainJobForTest)
                claimedTokens.forEach { token ->
                    assertFalse(registry.isLive(TASK_1, token))
                    assertFalse(registry.hasUncertainClaimRelease(token))
                }
            }
        }

    @Test
    fun `on claimed failure is exactly released then reclaimed without a newer wake`() = runTest {
        val events = mutableListOf<String>()
        var claimCalls = 0
        var announced = 0
        val coordinator = coordinator(
            claimNext = { _, token ->
                claimCalls += 1
                if (claimCalls <= 2) claim(1).copy(claimToken = token) else null
            },
            executeClaim = { claimed, _ ->
                events += "run:${claimed.taskId}"
                completed()
            },
            settleTimeout = {
                events += "release"
                true
            },
            persistOutcome = { _, _ ->
                events += "settle"
                DataTaskSinkWrite.APPLIED
            },
            cleanupClaim = { events += "cleanup" },
            finishDrainIfEmpty = { onEmpty ->
                events += "finish-empty"
                onEmpty()
                true
            },
        )

        coordinator.wake(
            onClaimed = { _, _ ->
                announced += 1
                events += "announce:$announced"
                if (announced == 1) error("notification update failed")
            },
            onDrained = { events += "drained" },
        )
        advanceUntilIdle()

        assertEquals(
            listOf(
                "announce:1",
                "release",
                "cleanup",
                "announce:2",
                "run:$TASK_1",
                "settle",
                "cleanup",
                "finish-empty",
                "drained",
            ),
            events,
        )
        assertEquals(3, claimCalls)
        assertEquals(1, coordinator.drainLaunchCountForTest)
        assertFalse(coordinator.hasDrainJobForTest)
    }

    @Test
    fun `outcome persistence exception is exactly released then reclaimed without a newer wake`() =
        runTest {
            val events = mutableListOf<String>()
            var claimCalls = 0
            var persistCalls = 0
            val coordinator = coordinator(
                claimNext = { _, token ->
                    claimCalls += 1
                    if (claimCalls <= 2) claim(1).copy(claimToken = token) else null
                },
                executeClaim = { claimed, _ ->
                    events += "run:${claimed.taskId}"
                    completed()
                },
                settleTimeout = {
                    events += "release"
                    true
                },
                persistOutcome = { _, _ ->
                    persistCalls += 1
                    events += "settle:$persistCalls"
                    if (persistCalls == 1) error("outcome store unavailable")
                    DataTaskSinkWrite.APPLIED
                },
                cleanupClaim = { events += "cleanup" },
                finishDrainIfEmpty = { onEmpty ->
                    events += "finish-empty"
                    onEmpty()
                    true
                },
            )

            coordinator.wake { events += "drained" }
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "run:$TASK_1",
                    "settle:1",
                    "release",
                    "cleanup",
                    "run:$TASK_1",
                    "settle:2",
                    "cleanup",
                    "finish-empty",
                    "drained",
                ),
                events,
            )
            assertEquals(3, claimCalls)
            assertEquals(2, persistCalls)
            assertEquals(1, coordinator.drainLaunchCountForTest)
            assertFalse(coordinator.hasDrainJobForTest)
        }

    @Test
    fun `cancellation after runner return settles before owner release without spinning`() =
        runTest {
            val registry = DataTaskOwnerRegistry()
            val events = mutableListOf<String>()
            var claimCalls = 0
            var persistCalls = 0
            val coordinator = coordinator(
                registry = registry,
                claimNext = { _, token ->
                    claimCalls += 1
                    if (claimCalls == 1) claim(1).copy(claimToken = token) else null
                },
                executeClaim = { _, _ ->
                    events += "runner-returned"
                    completed()
                },
                persistOutcome = { claimed, outcome ->
                    persistCalls += 1
                    assertTrue(outcome is DataTaskRunOutcome.ItemCompleted)
                    events += "settle"
                    assertTrue(registry.cancelActive(claimed.taskId))
                    events += "cancelled"
                    DataTaskSinkWrite.APPLIED
                },
                cleanupClaim = { events += "cleanup" },
            )

            val generation = coordinator.wake { events += "drained" }
            advanceUntilIdle()

            assertEquals(1L, generation)
            assertEquals(
                listOf("runner-returned", "settle", "cancelled", "cleanup", "drained"),
                events,
            )
            assertEquals(1, persistCalls)
            assertEquals(2, claimCalls)
            assertEquals(1, coordinator.drainLaunchCountForTest)
            assertEquals(0, coordinator.reconciliationJobCountForTest)
            assertFalse(registry.isLive(TASK_1, coordinator.lastClaimTokenForTest!!))
        }

    @Test
    fun `non-applied cancellation settlement releases exact claim before cleanup`() = runTest {
        val registry = DataTaskOwnerRegistry()
        val runnerStarted = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var claimCalls = 0
        val coordinator = coordinator(
            registry = registry,
            claimNext = { _, token ->
                claimCalls += 1
                if (claimCalls == 1) claim(1).copy(claimToken = token) else null
            },
            executeClaim = { _, _ ->
                runnerStarted.complete(Unit)
                awaitCancellation()
            },
            persistOutcome = { claimed, outcome ->
                assertTrue(registry.isLive(claimed.taskId, claimed.claimToken))
                assertEquals(DataTaskRunOutcome.Cancelled, outcome)
                events += "settle-lost"
                DataTaskSinkWrite.OWNERSHIP_LOST
            },
            settleTimeout = { claimed ->
                assertTrue(registry.isLive(claimed.taskId, claimed.claimToken))
                events += "release"
                true
            },
            cleanupClaim = { claimed ->
                assertTrue(registry.isLive(claimed.taskId, claimed.claimToken))
                events += "cleanup"
            },
        )

        coordinator.wake { events += "drained" }
        runCurrent()
        runnerStarted.await()
        assertTrue(registry.cancelActive(TASK_1))
        advanceUntilIdle()

        assertEquals(listOf("settle-lost", "release", "cleanup", "drained"), events)
        assertEquals(2, claimCalls)
        assertEquals(1, coordinator.drainLaunchCountForTest)
        assertEquals(0, coordinator.reconciliationJobCountForTest)
        assertFalse(registry.isLive(TASK_1, coordinator.lastClaimTokenForTest!!))
        assertFalse(registry.hasUncertainClaimRelease(coordinator.lastClaimTokenForTest!!))
    }

    @Test
    fun `timeout cancels active child settles after cleanup and prevents another claim`() =
        runTest {
            val events = mutableListOf<String>()
            val claims = ArrayDeque(listOf(claim(1), claim(2)))
            val running = CompletableDeferred<Unit>()
            var claimCalls = 0
            var finishCalls = 0
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
                persistOutcome = { _, outcome ->
                    events += "settle:${outcome::class.simpleName}"
                    DataTaskSinkWrite.APPLIED
                },
                finishDrainIfEmpty = { onEmpty ->
                    finishCalls += 1
                    onEmpty()
                    true
                },
            )

            coordinator.wake { events += "stop" }
            testScheduler.runCurrent()
            coordinator.stopClaimsAndInterrupt()
            advanceUntilIdle()

            assertEquals(1, claimCalls)
            assertEquals(
                listOf(
                    "timeout-settle",
                    "child-cancelled",
                    "cleanup",
                ),
                events,
            )
            assertEquals(0, finishCalls)
        }

    @Test
    fun `timeout settlement completes before child cancellation on multithreaded dispatcher`() =
        runTest {
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
                val claimStarted = CompletableDeferred<Unit>()
                val settlementEntered = CompletableDeferred<Unit>()
                val allowSettlement = CompletableDeferred<Unit>()
                val childCancelled = CountDownLatch(1)
                val events = java.util.concurrent.CopyOnWriteArrayList<String>()
                var claimed = false
                val coordinator = coordinator(
                    dispatcher = dispatcher,
                    claimNext = { _, token ->
                        if (claimed) null else claim(1).copy(claimToken = token)
                            .also { claimed = true }
                    },
                    executeClaim = { _, _ ->
                        claimStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            events += "child-cancelled"
                            childCancelled.countDown()
                        }
                    },
                    settleTimeout = {
                        events += "timeout-entered"
                        settlementEntered.complete(Unit)
                        allowSettlement.await()
                        events += "timeout-completed"
                        true
                    },
                )

                coordinator.wake {}
                claimStarted.await()
                val stopping = async { coordinator.stopClaimsAndInterrupt() }
                settlementEntered.await()

                assertFalse(childCancelled.await(1, TimeUnit.SECONDS))
                allowSettlement.complete(Unit)
                stopping.await()
                assertEquals(
                    listOf("timeout-entered", "timeout-completed", "child-cancelled"),
                    events,
                )
            }
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
            persistOutcome = { _, outcome ->
                events += "settle:${outcome::class.simpleName}"
                DataTaskSinkWrite.APPLIED
            },
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
            listOf("timeout-settle", "cleanup"),
            events,
        )
    }

    @Test
    fun `timeout transaction starts after runner return before normal persistence completes`() =
        runTest {
            val normalStarted = CompletableDeferred<Unit>()
            val allowNormalToFinish = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            var claimed = false
            val coordinator = coordinator(
                claimNext = { _, token ->
                    if (claimed) null else claim(1).copy(claimToken = token).also { claimed = true }
                },
                executeClaim = { _, _ -> completed() },
                persistOutcome = { _, _ ->
                    events += "normal-started"
                    normalStarted.complete(Unit)
                    allowNormalToFinish.await()
                    events += "normal-finished"
                    DataTaskSinkWrite.APPLIED
                },
                settleTimeout = {
                    events += "timeout"
                    allowNormalToFinish.complete(Unit)
                    true
                },
            )

            coordinator.wake { events += "stop" }
            runCurrent()
            normalStarted.await()
            val stopping = launch { coordinator.stopClaimsAndInterrupt() }
            runCurrent()

            assertEquals(listOf("normal-started", "timeout"), events.take(2))
            advanceUntilIdle()
            stopping.join()
            assertTrue(events.indexOf("timeout") < events.indexOf("normal-finished"))
        }

    @Test
    fun `normal transaction may win before concurrently submitted timeout`() = runTest {
        val normalCommitted = CompletableDeferred<Unit>()
        val allowNormalToReturn = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var claimed = false
        val coordinator = coordinator(
            claimNext = { _, token ->
                if (claimed) null else claim(1).copy(claimToken = token).also { claimed = true }
            },
            executeClaim = { _, _ -> completed() },
            persistOutcome = { _, _ ->
                events += "normal-committed"
                normalCommitted.complete(Unit)
                allowNormalToReturn.await()
                DataTaskSinkWrite.APPLIED
            },
            settleTimeout = {
                events += "timeout-rejected"
                allowNormalToReturn.complete(Unit)
                false
            },
        )

        coordinator.wake { events += "stop" }
        runCurrent()
        normalCommitted.await()
        val stopping = launch { coordinator.stopClaimsAndInterrupt() }
        runCurrent()

        assertEquals(listOf("normal-committed", "timeout-rejected"), events.take(2))
        advanceUntilIdle()
        stopping.join()
    }

    @Test
    fun `claim transition deadline compensates before provisional owner unregisters`() = runTest {
        val registry = DataTaskOwnerRegistry()
        val claimEntered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var claimToken = ""
        val coordinator = coordinator(
            registry = registry,
            claimNext = { _, token ->
                claimToken = token
                claimEntered.complete(Unit)
                awaitCancellation()
            },
            settleProvisionalClaim = { token ->
                assertTrue(registry.isLive(TASK_1, token))
                events += "release:$token"
                true
            },
        )

        coordinator.wake { events += "stop" }
        runCurrent()
        claimEntered.await()
        advanceTimeBy(2.seconds + 1.milliseconds)
        advanceUntilIdle()

        assertEquals(listOf("release:$claimToken"), events)
        assertFalse(registry.isLive(TASK_1, claimToken))
    }

    @Test
    fun `claim transition error compensates before provisional owner unregisters`() = runTest {
        val registry = DataTaskOwnerRegistry()
        val events = mutableListOf<String>()
        var claimToken = ""
        val coordinator = coordinator(
            registry = registry,
            claimNext = { _, token ->
                claimToken = token
                assertTrue(registry.isLive(TASK_1, token))
                error("claim response lost after commit")
            },
            settleProvisionalClaim = { token ->
                assertTrue(registry.isLive(TASK_1, token))
                events += "release:$token"
                true
            },
        )

        coordinator.wake { events += "stop" }
        advanceUntilIdle()

        assertEquals(listOf("release:$claimToken"), events)
        assertFalse(registry.isLive(TASK_1, claimToken))
    }

    @Test
    fun `ambiguous compensation reconciles owner then services retained newer wake`() = runTest {
        val registry = DataTaskOwnerRegistry()
        val events = mutableListOf<String>()
        var claimCalls = 0
        var compensationAttempts = 0
        var firstToken = ""
        lateinit var coordinator: DataSyncCoordinator
        coordinator = coordinator(
            registry = registry,
            claimNext = { _, token ->
                claimCalls += 1
                when (claimCalls) {
                    1 -> {
                        firstToken = token
                        error("claim response lost after commit")
                    }

                    2 -> claim(2).copy(claimToken = token)
                    else -> null
                }
            },
            settleProvisionalClaim = { token ->
                assertEquals(firstToken, token)
                compensationAttempts += 1
                if (compensationAttempts == 1) {
                    coordinator.wake { events += "stop:latest" }
                    error("first exact-token compensation was ambiguous")
                }
                events += "reconciled"
                true
            },
            executeClaim = { claimed, _ ->
                events += "run:${claimed.taskId}"
                completed()
            },
        )

        coordinator.wake { events += "stop:first" }
        advanceUntilIdle()

        assertEquals(2, compensationAttempts)
        assertFalse(registry.isLive(TASK_1, firstToken))
        assertTrue(events.contains("reconciled"))
        assertTrue(events.contains("run:$TASK_2"))
        assertFalse(events.contains("stop:first"))
        assertEquals("stop:latest", events.last())
    }

    @Test
    fun `active claim reconciliation retries exact timeout before servicing retained wake`() =
        runTest {
            val registry = DataTaskOwnerRegistry()
            val events = mutableListOf<String>()
            var claimCalls = 0
            var timeoutAttempts = 0
            var provisionalAttempts = 0
            lateinit var coordinator: DataSyncCoordinator
            coordinator = coordinator(
                registry = registry,
                claimNext = { _, token ->
                    claimCalls += 1
                    when (claimCalls) {
                        1 -> claim(1).copy(claimToken = token)
                        2 -> claim(2).copy(claimToken = token)
                        else -> null
                    }
                },
                executeClaim = { claimed, _ ->
                    events += "run:${claimed.taskId}"
                    completed()
                },
                persistOutcome = { claimed, _ ->
                    if (claimed.taskId == TASK_1) error("outcome commit unavailable")
                    DataTaskSinkWrite.APPLIED
                },
                settleTimeout = { claim ->
                    assertEquals(TASK_1, claim.taskId)
                    timeoutAttempts += 1
                    if (timeoutAttempts == 1) {
                        coordinator.wake { events += "stop:latest" }
                        error("first exact active compensation was ambiguous")
                    }
                    events += "active-reconciled"
                    true
                },
                settleProvisionalClaim = {
                    provisionalAttempts += 1
                    true
                },
            )

            coordinator.wake { events += "stop:first" }
            advanceUntilIdle()

            assertEquals(2, timeoutAttempts)
            assertEquals(0, provisionalAttempts)
            assertTrue(events.contains("active-reconciled"))
            assertTrue(events.contains("run:$TASK_2"))
            assertFalse(events.contains("stop:first"))
            assertEquals("stop:latest", events.last())
        }

    @Test
    fun `false active settlement remains uncertain without task cleanup`() = runTest {
        val registry = DataTaskOwnerRegistry()
        var claimCalls = 0
        var settlementAttempts = 0
        var cleanupCalls = 0
        var firstToken = ""
        val coordinator = coordinator(
            registry = registry,
            claimNext = { _, token ->
                claimCalls += 1
                if (claimCalls == 1) {
                    claim(1).copy(claimToken = token).also { firstToken = token }
                } else {
                    null
                }
            },
            executeClaim = { _, _ -> completed() },
            persistOutcome = { _, _ -> error("outcome commit unavailable") },
            settleTimeout = {
                settlementAttempts += 1
                false
            },
            cleanupClaim = { cleanupCalls += 1 },
            claimReleaseIsPending = { true },
        )

        coordinator.wake {}
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(4, settlementAttempts)
        assertEquals(0, cleanupCalls)
        assertTrue(registry.isLive(TASK_1, firstToken))
        assertTrue(registry.hasUncertainClaimRelease(firstToken))
        assertEquals(1, coordinator.reconciliationJobCountForTest)
        coordinator.stopClaimsAndInterrupt()
    }

    @Test
    fun `false provisional settlement remains uncertain until absence is observed`() = runTest {
        val registry = DataTaskOwnerRegistry()
        var claimCalls = 0
        var settlementAttempts = 0
        var firstToken = ""
        val coordinator = coordinator(
            registry = registry,
            claimNext = { _, token ->
                claimCalls += 1
                if (claimCalls == 1) {
                    firstToken = token
                    error("claim response lost after commit")
                }
                null
            },
            settleProvisionalClaim = {
                settlementAttempts += 1
                false
            },
            claimReleaseIsPending = { true },
        )

        coordinator.wake {}
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(4, settlementAttempts)
        assertTrue(registry.isLive(TASK_1, firstToken))
        assertTrue(registry.hasUncertainClaimRelease(firstToken))
        assertEquals(1, coordinator.reconciliationJobCountForTest)
        coordinator.stopClaimsAndInterrupt()
    }

    @Test
    fun `lease recovery cleans a waiting task before transactional final empty`() = runTest {
        val registry = DataTaskOwnerRegistry()
        val events = mutableListOf<String>()
        var claimCalls = 0
        var settlementAttempts = 0
        var persistenceCalls = 0
        var executionCalls = 0
        var activeCleanupCalls = 0
        var recoveredCleanupCalls = 0
        var recoveryCalls = 0
        var finishCalls = 0
        var firstToken = ""
        lateinit var coordinator: DataSyncCoordinator
        coordinator = coordinator(
            registry = registry,
            recoverClaims = { _, isLive ->
                recoveryCalls += 1
                events += "recover:$recoveryCalls"
                if (recoveryCalls == 1) {
                    emptyList()
                } else {
                    assertFalse(isLive(TASK_1, firstToken))
                    events += "recovered-waiting"
                    listOf(TASK_1)
                }
            },
            cleanupRecoveredClaim = { taskId ->
                assertEquals(TASK_1, taskId)
                recoveredCleanupCalls += 1
                events += "cleanup-recovered"
            },
            claimNext = { _, token ->
                claimCalls += 1
                if (claimCalls == 1) {
                    claim(1).copy(claimToken = token).also { firstToken = token }
                } else {
                    events += "claim-empty"
                    null
                }
            },
            persistOutcome = { _, _ ->
                persistenceCalls += 1
                events += "settle-lost"
                DataTaskSinkWrite.OWNERSHIP_LOST
            },
            settleTimeout = {
                settlementAttempts += 1
                if (settlementAttempts == 1) {
                    coordinator.wake { events += "stop:latest" }
                }
                error("Room remains unavailable")
            },
            executeClaim = { claimed, _ ->
                executionCalls += 1
                events += "run:${claimed.claimToken}"
                completed()
            },
            cleanupClaim = { activeCleanupCalls += 1 },
            finishDrainIfEmpty = { onEmpty ->
                finishCalls += 1
                events += "finish-empty"
                onEmpty()
                true
            },
        )

        val firstGeneration = coordinator.wake { events += "stop:first" }
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(1L, firstGeneration)
        assertEquals(1, claimCalls)
        assertEquals(4, settlementAttempts)
        assertEquals(1, persistenceCalls)
        assertEquals(1, executionCalls)
        assertEquals(0, activeCleanupCalls)
        assertEquals(0, recoveredCleanupCalls)
        assertEquals(1, recoveryCalls)
        assertEquals(0, finishCalls)
        assertTrue(registry.isLive(TASK_1, firstToken))
        assertTrue(registry.hasUncertainClaimRelease(firstToken))
        assertEquals(1, coordinator.reconciliationJobCountForTest)
        assertFalse(events.any { it.startsWith("stop:") })

        advanceTimeBy(ForegroundTaskWakeLock.LEASE_MILLIS.milliseconds)
        advanceUntilIdle()

        assertEquals(2, claimCalls)
        assertEquals(1, persistenceCalls)
        assertEquals(1, executionCalls)
        assertEquals(0, activeCleanupCalls)
        assertEquals(1, recoveredCleanupCalls)
        assertEquals(2, recoveryCalls)
        assertEquals(1, finishCalls)
        assertTrue(events.indexOf("recovered-waiting") < events.indexOf("cleanup-recovered"))
        assertTrue(events.indexOf("cleanup-recovered") < events.indexOf("claim-empty"))
        assertTrue(events.indexOf("claim-empty") < events.indexOf("finish-empty"))
        assertTrue(events.indexOf("finish-empty") < events.indexOf("stop:latest"))
        assertEquals(1, events.count { it.startsWith("stop:") })
        assertFalse(events.contains("stop:first"))
        assertEquals("stop:latest", events.last())
        assertFalse(registry.isLive(TASK_1, firstToken))
        assertFalse(registry.hasUncertainClaimRelease(firstToken))
        assertEquals(0, coordinator.reconciliationJobCountForTest)
        assertEquals(2, coordinator.drainLaunchCountForTest)
        assertFalse(coordinator.hasDrainJobForTest)
    }

    @Test
    fun `recovered cancelled task is cleaned before transactional final empty`() = runTest {
        val events = mutableListOf<String>()
        var finishCalls = 0
        val coordinator = coordinator(
            recoverClaims = { _, _ ->
                events += "recover-cancelled"
                listOf(TASK_1)
            },
            cleanupRecoveredClaim = { taskId ->
                assertEquals(TASK_1, taskId)
                events += "cleanup-cancelled"
            },
            claimNext = { _, _ ->
                events += "claim-empty"
                null
            },
            finishDrainIfEmpty = { onEmpty ->
                finishCalls += 1
                events += "finish-empty"
                onEmpty()
                true
            },
        )

        coordinator.wake { events += "drained" }
        advanceUntilIdle()

        assertEquals(
            listOf(
                "recover-cancelled",
                "cleanup-cancelled",
                "claim-empty",
                "finish-empty",
                "drained",
            ),
            events,
        )
        assertEquals(1, finishCalls)
        assertFalse(coordinator.hasDrainJobForTest)
    }

    @Test
    fun `late recovery completion cleans and resumes without another wake or recovery`() = runTest {
        val recoveryEntered = CompletableDeferred<Unit>()
        val allowRecoveryReturn = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var recoveryCalls = 0
        var cleanupCalls = 0
        var claimCalls = 0
        var finishCalls = 0
        val coordinator = coordinator(
            recoverClaims = { _, _ ->
                recoveryCalls += 1
                recoveryEntered.complete(Unit)
                allowRecoveryReturn.await()
                events += "recovery-returned"
                listOf(TASK_1)
            },
            cleanupRecoveredClaim = { taskId ->
                assertEquals(TASK_1, taskId)
                cleanupCalls += 1
                events += "cleanup"
            },
            claimNext = { _, _ ->
                claimCalls += 1
                events += "claim-empty"
                null
            },
            finishDrainIfEmpty = { onEmpty ->
                finishCalls += 1
                events += "finish-empty"
                onEmpty()
                true
            },
        )

        coordinator.wake { events += "drained" }
        runCurrent()
        recoveryEntered.await()
        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(1, recoveryCalls)
        assertEquals(0, cleanupCalls)
        assertEquals(0, claimCalls)
        assertEquals(0, finishCalls)
        assertTrue(events.isEmpty())
        assertFalse(coordinator.hasDrainJobForTest)

        allowRecoveryReturn.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, recoveryCalls)
        assertEquals(1, cleanupCalls)
        assertEquals(1, claimCalls)
        assertEquals(1, finishCalls)
        assertEquals(
            listOf("recovery-returned", "cleanup", "claim-empty", "finish-empty", "drained"),
            events,
        )
        assertEquals(2, coordinator.drainLaunchCountForTest)
        assertFalse(coordinator.hasDrainJobForTest)
    }

    @Test
    fun `stop during recovery has bounded teardown while process cleanup continues`() = runTest {
        val recoveryEntered = CompletableDeferred<Unit>()
        val allowRecoveryReturn = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var finishCalls = 0
        val coordinator = coordinator(
            recoverClaims = { _, _ ->
                events += "recovery-entered"
                recoveryEntered.complete(Unit)
                allowRecoveryReturn.await()
                events += "recovery-returned"
                listOf(TASK_1)
            },
            cleanupRecoveredClaim = { taskId ->
                assertEquals(TASK_1, taskId)
                events += "cleanup"
            },
            finishDrainIfEmpty = { onEmpty ->
                finishCalls += 1
                onEmpty()
                true
            },
        )

        coordinator.wake { events += "drained" }
        runCurrent()
        recoveryEntered.await()
        val stopping = async { coordinator.stopClaimsAndInterrupt() }
        runCurrent()

        assertFalse(stopping.isCompleted)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(stopping.isCompleted)
        assertEquals(listOf("recovery-entered"), events)

        allowRecoveryReturn.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("recovery-entered", "recovery-returned", "cleanup"), events)
        assertEquals(0, finishCalls)
        assertFalse(coordinator.hasDrainJobForTest)
    }

    @Test
    fun `replacement coordinator joins timed out process recovery without overlap`() = runTest {
        val registry = DataTaskOwnerRegistry()
        val recoveryEntered = CompletableDeferred<Unit>()
        val allowRecoveryReturn = CompletableDeferred<Unit>()
        var roomRecoveryInFlight = false
        var overlapDetected = false
        var recoveryCalls = 0
        var replacementCallbacks = 0
        val recoverClaims: suspend (String, (UUID, String) -> Boolean) -> List<UUID> =
            { _, _ ->
                recoveryCalls += 1
                if (recoveryCalls == 1) {
                    roomRecoveryInFlight = true
                    recoveryEntered.complete(Unit)
                    allowRecoveryReturn.await()
                    roomRecoveryInFlight = false
                } else if (roomRecoveryInFlight) {
                    overlapDetected = true
                }
                emptyList()
            }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recoveryProcess = DataTaskRecoveryProcess(dispatcher, recoverClaims) {}
        val first = coordinator(
            dispatcher = dispatcher,
            registry = registry,
            recoverClaims = recoverClaims,
            recoveryProcess = recoveryProcess,
        )

        first.wake {}
        runCurrent()
        recoveryEntered.await()
        advanceTimeBy(2.seconds)
        runCurrent()
        first.stopClaimsAndInterrupt()

        val replacement = coordinator(
            dispatcher = dispatcher,
            registry = registry,
            recoverClaims = recoverClaims,
            recoveryProcess = recoveryProcess,
        )
        replacement.wake { replacementCallbacks += 1 }
        runCurrent()

        assertEquals(1, recoveryCalls)
        assertFalse(overlapDetected)
        assertEquals(0, replacementCallbacks)

        allowRecoveryReturn.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, recoveryCalls)
        assertFalse(overlapDetected)
        assertEquals(1, replacementCallbacks)
        assertFalse(replacement.hasDrainJobForTest)
    }

    @Test
    fun `wake joining recovery failure retries once while its drain is waiting`() = runTest {
        val recoveryEntered = CompletableDeferred<Unit>()
        val failFirstRecovery = CompletableDeferred<Unit>()
        val callbacks = mutableListOf<String>()
        var recoveryCalls = 0
        var concurrentRecoveries = 0
        var maxConcurrentRecoveries = 0
        var finishCalls = 0
        val recoverClaims: suspend (String, (UUID, String) -> Boolean) -> List<UUID> =
            { _, _ ->
                recoveryCalls += 1
                concurrentRecoveries += 1
                maxConcurrentRecoveries = maxOf(maxConcurrentRecoveries, concurrentRecoveries)
                try {
                    if (recoveryCalls == 1) {
                        recoveryEntered.complete(Unit)
                        failFirstRecovery.await()
                        error("room unavailable")
                    }
                    emptyList()
                } finally {
                    concurrentRecoveries -= 1
                }
            }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recoveryProcess = DataTaskRecoveryProcess(dispatcher, recoverClaims) {}
        val coordinator = coordinator(
            dispatcher = dispatcher,
            recoverClaims = recoverClaims,
            recoveryProcess = recoveryProcess,
            finishDrainIfEmpty = { onEmpty ->
                finishCalls += 1
                onEmpty()
                true
            },
        )

        coordinator.wake { callbacks += "first" }
        runCurrent()
        recoveryEntered.await()
        advanceTimeBy(2.seconds)
        runCurrent()

        coordinator.wake { callbacks += "second" }
        runCurrent()
        assertEquals(1, recoveryCalls)

        failFirstRecovery.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, recoveryCalls)
        assertEquals(1, maxConcurrentRecoveries)
        assertEquals(1, finishCalls)
        assertEquals(listOf("second"), callbacks)
        assertEquals(2, coordinator.drainLaunchCountForTest)
        assertFalse(recoveryProcess.hasUnconsumedSuccess())
        assertFalse(coordinator.hasDrainJobForTest)
    }

    @Test
    fun `failed retry after inherited recovery waits for another explicit wake`() = runTest {
        val recoveryEntered = CompletableDeferred<Unit>()
        val failFirstRecovery = CompletableDeferred<Unit>()
        val callbacks = mutableListOf<String>()
        var recoveryCalls = 0
        var finishCalls = 0
        val recoverClaims: suspend (String, (UUID, String) -> Boolean) -> List<UUID> =
            { _, _ ->
                recoveryCalls += 1
                when (recoveryCalls) {
                    1 -> {
                        recoveryEntered.complete(Unit)
                        failFirstRecovery.await()
                        error("first recovery unavailable")
                    }

                    2 -> error("retry unavailable")
                    else -> emptyList()
                }
            }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recoveryProcess = DataTaskRecoveryProcess(dispatcher, recoverClaims) {}
        val coordinator = coordinator(
            dispatcher = dispatcher,
            recoverClaims = recoverClaims,
            recoveryProcess = recoveryProcess,
            finishDrainIfEmpty = { onEmpty ->
                finishCalls += 1
                onEmpty()
                true
            },
        )

        coordinator.wake { callbacks += "first" }
        runCurrent()
        recoveryEntered.await()
        advanceTimeBy(2.seconds)
        runCurrent()
        coordinator.wake { callbacks += "second" }
        runCurrent()

        failFirstRecovery.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, recoveryCalls)
        assertEquals(0, finishCalls)
        assertTrue(callbacks.isEmpty())
        assertEquals(2, coordinator.drainLaunchCountForTest)
        assertFalse(coordinator.hasDrainJobForTest)

        coordinator.wake { callbacks += "third" }
        advanceUntilIdle()

        assertEquals(3, recoveryCalls)
        assertEquals(1, finishCalls)
        assertEquals(listOf("third"), callbacks)
        assertEquals(3, coordinator.drainLaunchCountForTest)
        assertFalse(recoveryProcess.hasUnconsumedSuccess())
        assertFalse(coordinator.hasDrainJobForTest)
    }

    @Test
    fun `wake joining recovery failure after timeout launches one retained retry drain`() = runTest {
        val cleanupEntered = CompletableDeferred<Unit>()
        val failFirstCleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var recoveryCalls = 0
        var concurrentRecoveries = 0
        var maxConcurrentRecoveries = 0
        var cleanupCalls = 0
        var finishCalls = 0
        val recoverClaims: suspend (String, (UUID, String) -> Boolean) -> List<UUID> =
            { _, _ ->
                recoveryCalls += 1
                concurrentRecoveries += 1
                maxConcurrentRecoveries = maxOf(maxConcurrentRecoveries, concurrentRecoveries)
                try {
                    events += "recover:$recoveryCalls"
                    if (recoveryCalls == 1) listOf(TASK_1) else emptyList()
                } finally {
                    concurrentRecoveries -= 1
                }
            }
        val cleanupRecoveredClaim: suspend (UUID) -> Unit = { taskId ->
            assertEquals(TASK_1, taskId)
            cleanupCalls += 1
            events += "cleanup:$cleanupCalls"
            if (cleanupCalls == 1) {
                cleanupEntered.complete(Unit)
                failFirstCleanup.await()
                error("cleanup unavailable")
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recoveryProcess = DataTaskRecoveryProcess(
            dispatcher,
            recoverClaims,
            cleanupRecoveredClaim,
        )
        val coordinator = coordinator(
            dispatcher = dispatcher,
            recoverClaims = recoverClaims,
            cleanupRecoveredClaim = cleanupRecoveredClaim,
            recoveryProcess = recoveryProcess,
            claimNext = { _, _ ->
                events += "claim-empty"
                null
            },
            finishDrainIfEmpty = { onEmpty ->
                finishCalls += 1
                events += "finish-empty"
                onEmpty()
                true
            },
        )

        coordinator.wake { events += "drained:first" }
        runCurrent()
        cleanupEntered.await()
        advanceTimeBy(2.seconds)
        runCurrent()

        coordinator.wake { events += "drained:second" }
        runCurrent()
        assertEquals(1, recoveryCalls)
        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(1, recoveryCalls)
        assertEquals(2, coordinator.drainLaunchCountForTest)
        assertEquals(0, finishCalls)
        assertFalse(events.any { it.startsWith("drained:") })
        assertFalse(coordinator.hasDrainJobForTest)

        failFirstCleanup.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, recoveryCalls)
        assertEquals(3, coordinator.drainLaunchCountForTest)
        assertEquals(1, maxConcurrentRecoveries)
        assertEquals(2, cleanupCalls)
        assertEquals(1, finishCalls)
        assertTrue(events.indexOf("cleanup:2") < events.indexOf("recover:2"))
        assertTrue(events.indexOf("recover:2") < events.indexOf("claim-empty"))
        assertEquals(listOf("drained:second"), events.filter { it.startsWith("drained:") })
        assertFalse(recoveryProcess.hasUnconsumedSuccess())
        assertFalse(coordinator.hasDrainJobForTest)
    }

    @Test
    fun `false sweep keeps inherited recovery retry cold until a later wake`() = runTest {
        val recoveryEntered = CompletableDeferred<Unit>()
        val failFirstRecovery = CompletableDeferred<Unit>()
        val callbacks = mutableListOf<String>()
        var sweepCalls = 0
        var recoveryCalls = 0
        var concurrentRecoveries = 0
        var maxConcurrentRecoveries = 0
        var finishCalls = 0
        val recoverClaims: suspend (String, (UUID, String) -> Boolean) -> List<UUID> =
            { _, _ ->
                recoveryCalls += 1
                concurrentRecoveries += 1
                maxConcurrentRecoveries = maxOf(maxConcurrentRecoveries, concurrentRecoveries)
                try {
                    if (recoveryCalls == 1) {
                        recoveryEntered.complete(Unit)
                        failFirstRecovery.await()
                        error("room unavailable")
                    }
                    emptyList()
                } finally {
                    concurrentRecoveries -= 1
                }
            }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recoveryProcess = DataTaskRecoveryProcess(dispatcher, recoverClaims) {}
        val coordinator = coordinator(
            dispatcher = dispatcher,
            awaitLaunchSweep = {
                sweepCalls += 1
                sweepCalls != 3
            },
            recoverClaims = recoverClaims,
            recoveryProcess = recoveryProcess,
            finishDrainIfEmpty = { onEmpty ->
                finishCalls += 1
                onEmpty()
                true
            },
        )

        coordinator.wake { callbacks += "first" }
        runCurrent()
        recoveryEntered.await()
        advanceTimeBy(2.seconds)
        runCurrent()

        coordinator.wake { callbacks += "second" }
        runCurrent()
        assertEquals(1, recoveryCalls)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertFalse(coordinator.hasDrainJobForTest)

        failFirstRecovery.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, recoveryCalls)
        assertEquals(0, concurrentRecoveries)
        assertEquals(3, sweepCalls)
        assertEquals(3, coordinator.drainLaunchCountForTest)
        assertEquals(0, finishCalls)
        assertEquals(listOf("second"), callbacks)
        assertFalse(recoveryProcess.hasUnconsumedSuccess())
        assertFalse(coordinator.hasDrainJobForTest)

        coordinator.wake { callbacks += "third" }
        advanceUntilIdle()

        assertEquals(2, recoveryCalls)
        assertEquals(1, maxConcurrentRecoveries)
        assertEquals(4, sweepCalls)
        assertEquals(4, coordinator.drainLaunchCountForTest)
        assertEquals(1, finishCalls)
        assertEquals(listOf("second", "third"), callbacks)
        assertFalse(recoveryProcess.hasUnconsumedSuccess())
        assertFalse(coordinator.hasDrainJobForTest)
    }

    @Test
    fun `replacement coordinator retries process scoped recovered cleanup before final empty`() =
        runTest {
            val registry = DataTaskOwnerRegistry()
            val events = mutableListOf<String>()
            var recoveryCalls = 0
            var cleanupCalls = 0
            var finishCalls = 0
            val recoverClaims: suspend (String, (UUID, String) -> Boolean) -> List<UUID> =
                { _, _ ->
                    recoveryCalls += 1
                    if (recoveryCalls == 1) listOf(TASK_1) else emptyList()
                }
            val cleanupRecoveredClaim: suspend (UUID) -> Unit = { taskId ->
                assertEquals(TASK_1, taskId)
                cleanupCalls += 1
                if (cleanupCalls == 1) error("process cleanup unavailable")
                events += "cleanup:$taskId"
            }
            val finishDrainIfEmpty: suspend (() -> Unit) -> Boolean = { onEmpty ->
                finishCalls += 1
                events += "finish-empty"
                onEmpty()
                true
            }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val recoveryProcess = DataTaskRecoveryProcess(
                dispatcher,
                recoverClaims,
                cleanupRecoveredClaim,
            )
            val first = coordinator(
                dispatcher = dispatcher,
                registry = registry,
                recoverClaims = recoverClaims,
                cleanupRecoveredClaim = cleanupRecoveredClaim,
                recoveryProcess = recoveryProcess,
                finishDrainIfEmpty = finishDrainIfEmpty,
            )

            first.wake { events += "drained:first" }
            advanceUntilIdle()

            assertEquals(1, recoveryCalls)
            assertEquals(1, cleanupCalls)
            assertEquals(0, finishCalls)
            assertTrue(events.isEmpty())
            assertFalse(first.hasDrainJobForTest)
            first.stopClaimsAndInterrupt()

            val replacement = coordinator(
                dispatcher = dispatcher,
                registry = registry,
                recoverClaims = recoverClaims,
                cleanupRecoveredClaim = cleanupRecoveredClaim,
                recoveryProcess = recoveryProcess,
                finishDrainIfEmpty = finishDrainIfEmpty,
            )
            replacement.wake { events += "drained:replacement" }
            advanceUntilIdle()

            assertEquals(2, recoveryCalls)
            assertEquals(2, cleanupCalls)
            assertEquals(1, finishCalls)
            assertEquals(
                listOf("cleanup:$TASK_1", "finish-empty", "drained:replacement"),
                events,
            )
            assertFalse(replacement.hasDrainJobForTest)
        }

    @Test
    fun `stale wake snapshot cannot resurrect a reconciled exact release`() = runTest {
        val registry = DataTaskOwnerRegistry()
        val releasePending = AtomicBoolean(true)
        val snapshotTaken = CountDownLatch(1)
        val resumeWake = CountDownLatch(1)
        var claimCalls = 0
        var recoveryCalls = 0
        var settlementAttempts = 0
        var activeCleanupCalls = 0
        var recoveredCleanupCalls = 0
        var finishCalls = 0
        val callbacks = mutableListOf<String>()
        var firstToken = ""
        val coordinator = coordinator(
            registry = registry,
            recoverClaims = { _, _ ->
                recoveryCalls += 1
                if (recoveryCalls == 2) listOf(TASK_1) else emptyList()
            },
            cleanupRecoveredClaim = { taskId ->
                assertEquals(TASK_1, taskId)
                recoveredCleanupCalls += 1
            },
            claimNext = { _, token ->
                claimCalls += 1
                if (claimCalls == 1) {
                    claim(1).copy(claimToken = token).also { firstToken = token }
                } else {
                    null
                }
            },
            executeClaim = { _, _ -> completed() },
            persistOutcome = { _, _ -> error("outcome commit unavailable") },
            settleTimeout = {
                settlementAttempts += 1
                false
            },
            claimReleaseIsPending = { releasePending.get() },
            cleanupClaim = { activeCleanupCalls += 1 },
            finishDrainIfEmpty = { onEmpty ->
                finishCalls += 1
                onEmpty()
                true
            },
        )
        coordinator.wake { callbacks += "first" }
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(4, settlementAttempts)
        assertTrue(registry.hasUncertainClaimRelease(firstToken))
        assertEquals(1, coordinator.reconciliationJobCountForTest)
        coordinator.beforeWakeLockForTest = {
            snapshotTaken.countDown()
            check(resumeWake.await(5, TimeUnit.SECONDS))
        }

        val executor = Executors.newSingleThreadExecutor()
        try {
            val staleWake = executor.submit<Long> { coordinator.wake { callbacks += "second" } }
            assertTrue(snapshotTaken.await(5, TimeUnit.SECONDS))
            releasePending.set(false)

            advanceTimeBy(ForegroundTaskWakeLock.LEASE_MILLIS.milliseconds)
            advanceUntilIdle()

            assertEquals(1, recoveredCleanupCalls)
            assertEquals(0, activeCleanupCalls)
            assertEquals(1, finishCalls)
            assertEquals(listOf("first"), callbacks)
            assertFalse(registry.hasUncertainClaimRelease(firstToken))
            assertEquals(0, coordinator.reconciliationJobCountForTest)
            assertFalse(coordinator.hasDrainJobForTest)

            resumeWake.countDown()
            assertEquals(2L, staleWake.get(5, TimeUnit.SECONDS))
            advanceUntilIdle()

            assertEquals(1, recoveredCleanupCalls + activeCleanupCalls)
            assertEquals(2, finishCalls)
            assertEquals(listOf("first", "second"), callbacks)
            assertEquals(3, coordinator.drainLaunchCountForTest)
            assertFalse(registry.hasUncertainClaimRelease(firstToken))
            assertEquals(0, coordinator.reconciliationJobCountForTest)
            assertFalse(coordinator.hasDrainJobForTest)
        } finally {
            resumeWake.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `coordinator teardown retires pending lease reconciliation without stale callback`() =
        runTest {
            val registry = DataTaskOwnerRegistry()
            val events = mutableListOf<String>()
            var claimCalls = 0
            var firstToken = ""
            val coordinator = coordinator(
                registry = registry,
                claimNext = { _, token ->
                    claimCalls += 1
                    firstToken = token
                    error("claim response lost after commit")
                },
                settleProvisionalClaim = { error("Room remains unavailable") },
            )

            coordinator.wake { events += "stop:stale" }
            runCurrent()
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(1, coordinator.reconciliationJobCountForTest)
            assertTrue(registry.isLive(TASK_1, firstToken))

            coordinator.stopClaimsAndInterrupt()
            runCurrent()
            assertEquals(0, coordinator.reconciliationJobCountForTest)
            assertFalse(registry.isLive(TASK_1, firstToken))
            assertTrue(registry.hasUncertainClaimRelease(firstToken))

            advanceTimeBy(ForegroundTaskWakeLock.LEASE_MILLIS.milliseconds)
            advanceUntilIdle()

            assertEquals(1, claimCalls)
            assertTrue(events.isEmpty())
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
            persistOutcome = { _, outcome ->
                settled += outcome
                DataTaskSinkWrite.APPLIED
            },
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
    fun `two wakes accepted before old drain starts survive recovery failure`() = runTest {
        val events = mutableListOf<String>()
        var recoveryCalls = 0
        val coordinator = coordinator(
            recoverClaims = { _, _ ->
                recoveryCalls += 1
                if (recoveryCalls == 1) error("room unavailable")
                emptyList()
            },
        )

        val firstGeneration = coordinator.wake { events += "stop:first" }
        val secondGeneration = coordinator.wake { events += "stop:second" }
        advanceUntilIdle()

        assertEquals(1L, firstGeneration)
        assertEquals(2L, secondGeneration)
        assertEquals(2, recoveryCalls)
        assertEquals(2, coordinator.drainLaunchCountForTest)
        assertEquals(listOf("stop:second"), events)
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
                emptyList()
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
    fun `post claim callback failure releases owner and continues the same drain`() = runTest {
        val registry = DataTaskOwnerRegistry()
        val events = mutableListOf<String>()
        var claimCalls = 0
        var firstReleased = false
        var firstToken = ""
        lateinit var wakeLatest: () -> Unit
        val coordinator = coordinator(
            registry = registry,
            claimNext = { _, token ->
                claimCalls += 1
                when (claimCalls) {
                    1 -> claim(1).copy(claimToken = token).also { firstToken = token }
                    2 -> if (firstReleased) claim(2).copy(claimToken = token) else null
                    else -> null
                }
            },
            settleTimeout = { claimed ->
                if (claimed.taskId == TASK_1) {
                    events += "released:${claimed.taskId}"
                    firstReleased = true
                }
                true
            },
            executeClaim = { claimed, _ ->
                events += "run:${claimed.taskId}"
                completed()
            },
        )

        wakeLatest = { coordinator.wake { events += "stop:latest" } }
        coordinator.wake(
            onClaimed = { taskId, _ ->
                if (taskId == TASK_1) {
                    wakeLatest()
                    error("notification update failed")
                }
            },
            onDrained = { events += "stop:first" },
        )
        advanceUntilIdle()

        assertTrue(firstReleased)
        assertFalse(registry.isLive(TASK_1, firstToken))
        assertTrue(events.contains("run:$TASK_2"))
        assertEquals("stop:latest", events.last())
        assertEquals(1, coordinator.drainLaunchCountForTest)
    }

    @Test
    fun `recovered cleanup failure concurrent with stop is not reported as drained`() = runTest {
        val cleanupEntered = CompletableDeferred<Unit>()
        val failCleanup = CompletableDeferred<Unit>()
        var cleanupCalls = 0
        var finishCalls = 0
        var callbacks = 0
        val coordinator = coordinator(
            recoverClaims = { _, _ -> listOf(TASK_1) },
            cleanupRecoveredClaim = { taskId ->
                assertEquals(TASK_1, taskId)
                cleanupCalls += 1
                cleanupEntered.complete(Unit)
                failCleanup.await()
                error("process cleanup unavailable")
            },
            finishDrainIfEmpty = { onEmpty ->
                finishCalls += 1
                onEmpty()
                true
            },
        )

        coordinator.wake { callbacks += 1 }
        runCurrent()
        cleanupEntered.await()
        val stopping = async { coordinator.stopClaimsAndInterrupt() }
        runCurrent()
        failCleanup.complete(Unit)
        stopping.await()
        advanceUntilIdle()

        assertEquals(1, cleanupCalls)
        assertEquals(0, finishCalls)
        assertEquals(0, callbacks)
        assertFalse(coordinator.hasDrainJobForTest)
    }

    @Test
    fun `recovery failure waits for an explicit wake without a false drain callback`() = runTest {
        val callbacks = mutableListOf<String>()
        var recoveryCalls = 0
        var finishCalls = 0
        val coordinator = coordinator(
            recoverClaims = { _, _ ->
                recoveryCalls += 1
                if (recoveryCalls == 1) error("room unavailable")
                emptyList()
            },
            finishDrainIfEmpty = { onEmpty ->
                finishCalls += 1
                onEmpty()
                true
            },
        )

        coordinator.wake { callbacks += "first" }
        advanceUntilIdle()

        assertEquals(1, recoveryCalls)
        assertEquals(0, finishCalls)
        assertTrue(callbacks.isEmpty())
        assertFalse(coordinator.hasDrainJobForTest)

        coordinator.wake { callbacks += "second" }
        advanceUntilIdle()

        assertEquals(2, recoveryCalls)
        assertEquals(1, finishCalls)
        assertEquals(listOf("second"), callbacks)
        assertFalse(coordinator.hasDrainJobForTest)
    }

    @Test
    fun `recovery always receives shared registry liveness lookup`() = runTest {
        val registry = DataTaskOwnerRegistry()
        registry.registerProvisional(CLAIM)
        registry.bindTask(TASK_1, CLAIM)
        var observed = false
        val coordinator = coordinator(
            registry = registry,
            recoverClaims = { _, isLive ->
                observed = isLive(TASK_1, CLAIM)
                emptyList()
            },
        )

        coordinator.wake {}
        advanceUntilIdle()

        assertTrue(observed)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        registry: DataTaskOwnerRegistry = DataTaskOwnerRegistry(),
        awaitLegacyDrain: suspend () -> Unit = {},
        awaitLaunchSweep: suspend () -> Boolean = { true },
        recoverClaims: suspend (String, (UUID, String) -> Boolean) -> List<UUID> =
            { _, _ -> emptyList() },
        cleanupRecoveredClaim: suspend (UUID) -> Unit = {},
        recoveryProcess: DataTaskRecoveryProcess = DataTaskRecoveryProcess(
            dispatcher,
            recoverClaims,
            cleanupRecoveredClaim,
        ),
        claimNext: suspend (String, String) -> DataSyncClaim? = { _, _ -> null },
        executeClaim: suspend (DataSyncClaim, DataTaskCheckpointSink) -> DataTaskRunOutcome = { _, _ ->
            completed()
        },
        settleTimeout: suspend (DataSyncClaim) -> Boolean = { true },
        settleProvisionalClaim: suspend (String) -> Boolean = { false },
        claimReleaseIsPending:
        suspend (DataTaskUncertainClaimRelease) -> Boolean = { false },
        persistOutcome: suspend (DataSyncClaim, DataTaskRunOutcome) -> DataTaskSinkWrite =
            { _, _ -> DataTaskSinkWrite.APPLIED },
        cleanupClaim: suspend (DataSyncClaim) -> Unit = {},
        finishDrainIfEmpty: suspend (() -> Unit) -> Boolean = { onEmpty ->
            onEmpty()
            true
        },
    ) = DataSyncCoordinator(
        dispatcher = dispatcher,
        ownerRegistry = registry,
        awaitLegacyDrain = awaitLegacyDrain,
        awaitLaunchSweep = awaitLaunchSweep,
        recoverClaims = recoverClaims,
        cleanupRecoveredClaim = cleanupRecoveredClaim,
        recoveryProcess = recoveryProcess,
        claimNext = claimNext,
        executeClaim = executeClaim,
        settleTimeout = settleTimeout,
        settleProvisionalClaim = settleProvisionalClaim,
        claimReleaseIsPending = claimReleaseIsPending,
        persistOutcome = persistOutcome,
        cleanupClaim = cleanupClaim,
        checkpointSink = { DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED } },
        finishDrainIfEmpty = finishDrainIfEmpty,
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
