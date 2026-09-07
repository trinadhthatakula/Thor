// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.service

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DataSyncServiceTimeoutTest {

    @Test
    fun `suspended timeout settlement still stops matching service within hard deadline`() =
        runTest {
            val events = mutableListOf<String>()
            val settlementStarted = CompletableDeferred<Unit>()
            val job = launch {
                boundedDataSyncTimeoutUnwind(
                    timeoutMillis = 3_000L,
                    settle = {
                        events += "settle"
                        settlementStarted.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    },
                    finish = {
                        finishDataSyncServiceGeneration(
                            startId = 41,
                            stopSelfResult = { id -> events += "stop:$id"; true },
                            removeForeground = { events += "foreground-removed" },
                        )
                    },
                )
            }
            runCurrent()
            settlementStarted.await()

            advanceTimeBy(3_001.milliseconds)
            runCurrent()
            job.join()

            assertEquals(listOf("settle", "stop:41", "foreground-removed"), events)
        }

    @Test
    fun `older generation never removes foreground for a newer accepted start`() {
        var removed = false

        val stopped = finishDataSyncServiceGeneration(
            startId = 41,
            stopSelfResult = { false },
            removeForeground = { removed = true },
        )

        assertFalse(stopped)
        assertFalse(removed)
    }

    @Test
    fun `foreground removal completes before a newer main dispatcher start can run`() = runTest {
        val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val stopEntered = CompletableDeferred<Unit>()
        val releaseStop = CountDownLatch(1)
        try {
            val finishing = launch {
                serializeDataSyncServiceLifecycle(mainDispatcher) {
                    finishDataSyncServiceGeneration(
                        startId = 41,
                        stopSelfResult = {
                            events += "stop:$it"
                            stopEntered.complete(Unit)
                            releaseStop.await()
                            true
                        },
                        removeForeground = { events += "foreground-removed" },
                    )
                }
            }
            stopEntered.await()
            val newerStart = launch {
                withContext(mainDispatcher) { events += "start:42" }
            }

            releaseStop.countDown()
            finishing.join()
            newerStart.join()

            assertEquals(listOf("stop:41", "foreground-removed", "start:42"), events)
        } finally {
            releaseStop.countDown()
            mainDispatcher.close()
        }
    }

    @Test
    fun `newer start during older timeout is fenced then owns final stop`() {
        val fence = DataSyncServiceGenerationFence()
        val stopIds = mutableListOf<Int>()
        var removed = 0

        assertTrue(fence.onPromotedStart(41))
        assertTrue(fence.onTimeoutStarted(41))
        assertFalse(fence.onPromotedStart(42))

        assertFalse(
            finishDataSyncServiceGeneration(
                startId = 41,
                stopSelfResult = { id -> stopIds += id; false },
                removeForeground = { removed += 1 },
            )
        )
        assertEquals(null, fence.onTimeoutFinished(41))
        val finalStartId = fence.onBlockedPersistenceFinished(42)
        assertEquals(42, finalStartId)
        finishDataSyncServiceGeneration(
            startId = requireNotNull(finalStartId),
            stopSelfResult = { id -> stopIds += id; id == 42 },
            removeForeground = { removed += 1 },
        )

        assertEquals(listOf(41, 42), stopIds)
        assertEquals(1, removed)
    }

    @Test
    fun `start delivered after timed out generation finished cannot reuse retired coordinator`() {
        val fence = DataSyncServiceGenerationFence()

        assertTrue(fence.onPromotedStart(41))
        assertTrue(fence.onTimeoutStarted(41))
        assertEquals(41, fence.onTimeoutFinished(41))

        // ActivityManager already knew about start 42, so stopSelfResult(41) returned false, but the
        // framework did not deliver that start to this service instance until timeout teardown ended.
        assertFalse(fence.onPromotedStart(42))
        assertFalse(fence.beginBlockedPersistence(41))
        assertTrue(fence.beginBlockedPersistence(42))
        assertEquals(42, fence.onBlockedPersistenceFinished(42))
    }

    @Test
    fun `blocked persistence loses mutation authority when a later promoted start supersedes it`() {
        val fence = DataSyncServiceGenerationFence()

        assertTrue(fence.onBlockedStart(41))
        assertTrue(fence.onPromotedStart(42))

        assertFalse(fence.beginBlockedPersistence(41))
        assertEquals(null, fence.onBlockedPersistenceFinished(41))
        assertEquals(42, fence.onDrained(42))
    }

    @Test
    fun `later promoted start is rejected while blocked Room mutation is reserved`() {
        val fence = DataSyncServiceGenerationFence()

        assertTrue(fence.onBlockedStart(41))
        assertTrue(fence.beginBlockedPersistence(41))
        assertFalse(fence.onPromotedStart(42))
        assertTrue(fence.beginBlockedPersistence(42))

        assertEquals(null, fence.onBlockedPersistenceFinished(41))
        assertEquals(42, fence.onBlockedPersistenceFinished(42))
    }

    @Test
    fun `blocked start cannot stop active or following successful generation`() {
        val fence = DataSyncServiceGenerationFence()

        assertTrue(fence.onPromotedStart(41))
        fence.onBlockedStart(42)
        assertEquals(null, fence.onBlockedPersistenceFinished(42))
        assertTrue(fence.onPromotedStart(43))
        assertEquals(null, fence.onDrained(41))
        assertEquals(43, fence.onDrained(43))
    }

    @Test
    fun `framework timeout after blocked newer start retires the accepted owner`() {
        val fence = DataSyncServiceGenerationFence()
        assertTrue(fence.onPromotedStart(41))
        fence.onBlockedStart(42)
        assertEquals(null, fence.onBlockedPersistenceFinished(42))

        assertFalse(fence.onTimeoutStarted(41))
        assertTrue(fence.onTimeoutStarted(42))
        assertFalse(fence.onTimeoutStarted(42))
        assertEquals(null, fence.onDrained(41))
        assertEquals(42, fence.onTimeoutFinished(42))
        assertEquals(null, fence.onTimeoutFinished(42))
        assertEquals(null, fence.onDrained(41))
        assertFalse(fence.onPromotedStart(43))
    }

    @Test
    fun `timeout of rejected latest start waits for blocked persistence and stops through newest id`() {
        val fence = DataSyncServiceGenerationFence()
        assertTrue(fence.onPromotedStart(41))
        fence.onBlockedStart(42)
        assertTrue(fence.onTimeoutStarted(42))
        assertTrue(fence.beginBlockedPersistence(42))
        assertFalse(fence.onPromotedStart(43))
        assertEquals(null, fence.onTimeoutFinished(41))
        assertEquals(null, fence.onTimeoutFinished(42))
        assertEquals(null, fence.onBlockedPersistenceFinished(42))
        assertEquals(43, fence.onBlockedPersistenceFinished(43))
        assertEquals(null, fence.onDrained(41))
    }

    @Test
    fun `Room timeout settlement failure still interrupts owner and finishes framework id`() =
        runTest {
            val fence = DataSyncServiceGenerationFence()
            val events = mutableListOf<String>()
            fence.onPromotedStart(41)
            fence.onBlockedStart(42)
            fence.onBlockedPersistenceFinished(42)
            assertTrue(fence.onTimeoutStarted(42))
            boundedDataSyncTimeoutUnwind(
                timeoutMillis = 3_000L,
                settle = { events += "interrupt:41"; error("Room unavailable") },
                finish = { events += "stop:${fence.onTimeoutFinished(42)}" },
            )
            assertEquals(listOf("interrupt:41", "stop:42"), events)
        }

    @Test
    fun `promotion failure persistence cannot exceed shutdown bound`() = runTest {
        val persistenceStarted = CompletableDeferred<Unit>()
        var finished = false
        val job = launch {
            boundedDataSyncPromotionFailure(
                timeoutMillis = 2_000L,
                persist = {
                    persistenceStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                },
                finish = { finished = true },
            )
        }
        runCurrent()
        persistenceStarted.await()

        advanceTimeBy(2_001.milliseconds)
        runCurrent()
        job.join()

        assertTrue(finished)
    }

    @Test
    fun `matching generation removes foreground after stop is accepted`() {
        var removed = false

        val stopped = finishDataSyncServiceGeneration(
            startId = 42,
            stopSelfResult = { id -> id == 42 },
            removeForeground = { removed = true },
        )

        assertTrue(stopped)
        assertTrue(removed)
    }
}
