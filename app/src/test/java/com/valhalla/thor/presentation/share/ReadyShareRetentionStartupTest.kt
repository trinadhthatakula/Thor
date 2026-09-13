// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.share

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadyShareRetentionStartupTest {
    @Test
    fun repeatedStartupCallsLaunchCleanupOnlyOnceInSuppliedApplicationScope() = runTest {
        val applicationJob = SupervisorJob()
        val scope = CoroutineScope(
            applicationJob + StandardTestDispatcher(testScheduler) + CoroutineName("application-owner"),
        )
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        var child: Job? = null
        var scopeName: String? = null
        val startup = ReadyShareRetentionStartup(ReadyShareRetentionCleanup {
            calls++
            child = currentCoroutineContext()[Job]
            scopeName = currentCoroutineContext()[CoroutineName]?.name
            finish.await()
        })
        try {
            repeat(3) { startup.start(scope) }
            assertEquals("startup must schedule work, not run it inline", 0, calls)
            runCurrent()

            assertEquals(1, calls)
            assertEquals("application-owner", scopeName)
            assertTrue("cleanup must be a child of the supplied application Job", child in applicationJob.children.toList())
            finish.complete(Unit)
            advanceUntilIdle()
            startup.start(scope)
            advanceUntilIdle()
            assertEquals("completion must not reset process-start idempotence", 1, calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelledApplicationScopeNeverStartsCleanup() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var calls = 0
        val startup = ReadyShareRetentionStartup(ReadyShareRetentionCleanup { calls++ })
        scope.cancel()

        startup.start(scope)
        advanceUntilIdle()

        assertEquals(0, calls)
    }

    @Test
    fun cancellingApplicationScopeCancelsInFlightCleanup() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        val startup = ReadyShareRetentionStartup(ReadyShareRetentionCleanup {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        })
        try {
            startup.start(scope)
            runCurrent()
            assertTrue(entered.isCompleted)

            scope.cancel()
            runCurrent()

            assertTrue("cleanup must not escape into an independently owned scope", cancelled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cleanupFailureDoesNotCrashApplicationOrCancelExistingStartupWork() = runTest {
        val applicationJob = SupervisorJob()
        val scope = CoroutineScope(applicationJob + StandardTestDispatcher(testScheduler))
        var attempts = 0
        var existingStartupCompleted = false
        val startup = ReadyShareRetentionStartup(ReadyShareRetentionCleanup {
            attempts++
            throw IOException("temporary cleanup failure")
        })
        try {
            startup.start(scope)
            scope.launch { existingStartupCompleted = true }
            advanceUntilIdle()

            assertEquals(1, attempts)
            assertTrue(existingStartupCompleted)
            assertTrue(applicationJob.isActive)
            startup.start(scope)
            advanceUntilIdle()
            assertEquals("failed startup cleanup retries next process, not a loop", 1, attempts)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aNewProcessStartupOwnerRetriesAfterPreviousProcessWasCancelled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val firstScope = CoroutineScope(SupervisorJob() + dispatcher)
        val secondScope = CoroutineScope(SupervisorJob() + dispatcher)
        var attempts = 0
        val cleanup = ReadyShareRetentionCleanup {
            attempts++
            if (attempts == 1) awaitCancellation()
        }
        try {
            ReadyShareRetentionStartup(cleanup).start(firstScope)
            runCurrent()
            assertEquals(1, attempts)
            firstScope.cancel()
            runCurrent()

            ReadyShareRetentionStartup(cleanup).start(secondScope)
            advanceUntilIdle()

            assertEquals("idempotence must be per process owner, not persisted globally", 2, attempts)
        } finally {
            firstScope.cancel()
            secondScope.cancel()
        }
    }

    @Test
    fun applicationSourceInvokesMandatoryRetentionHookExactlyOnceWithRetainedScope() {
        val source = productionSource("ThorApplication.kt")
        val onCreate = source.substringAfter("override fun onCreate()")
            .substringBefore("override fun onTerminate()")

        assertTrue("ThorApplication must resolve the retention startup owner", Regex(
            "private\\s+val\\s+readyShareRetentionStartup\\s*:\\s*ReadyShareRetentionStartup\\s+by\\s+inject\\(\\)",
        ).containsMatchIn(source))
        assertEquals("one process-start call must use the retained appScope", 1, Regex(
            "readyShareRetentionStartup\\.start\\(appScope\\)",
        ).findAll(onCreate).count())
        assertTrue("existing archive sweep/barrier must remain wired", onCreate.contains("launchSweepBarrier.markSwept()"))
        assertTrue("existing automatic freezer startup must remain wired", onCreate.contains("autoFreezeManager.startObserving()"))
        assertTrue("existing privilege initialization must remain wired", onCreate.contains("ThorShellConfig.init()"))
    }

    @Test
    fun startupSourceHasNoQueueWakeOrForegroundServiceDependency() {
        val source = productionSource("presentation/share/ReadyShareRetentionStartup.kt")
        listOf(
            "DataQueueWakeSignal",
            "PrivilegeQueueWakeSignal",
            "DataSyncService",
            "PrivilegeSweepService",
            "startForegroundService(",
            "startService(",
            "GlobalScope",
        ).forEach { forbidden ->
            assertFalse("retention startup must not depend on $forbidden", source.contains(forbidden))
        }
    }

    private fun productionSource(relative: String): String {
        val roots = listOf(File("src/main/java/com/valhalla/thor"), File("app/src/main/java/com/valhalla/thor"))
        return requireNotNull(roots.map { File(it, relative) }.firstOrNull(File::isFile)) {
            "Cannot find production source $relative"
        }.readText()
    }
}
