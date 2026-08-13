// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchSweepBarrierTest {

    @Test
    fun `a waiter already suspended when the sweep ends is released`() = runTest {
        // UNDISPATCHED so the waiter genuinely reaches its suspension point before markSwept runs.
        // On the default StandardTestDispatcher the child would not start until this coroutine
        // suspends, and the test would silently degrade into the already-marked case below — the
        // ordering it exists to pin is the one where the worker got there first.
        val barrier = LaunchSweepBarrier()
        var swept = false

        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { swept = barrier.awaitSwept() }
        assertFalse(swept)

        barrier.markSwept()
        waiter.join()

        assertTrue(swept)
    }

    @Test
    fun `waiting after the sweep has already run returns immediately`() = runTest {
        // The ordinary case by a wide margin: the sweep is over milliseconds into the process and the
        // user taps export minutes later. If this ever blocked, every export would pay the timeout.
        val barrier = LaunchSweepBarrier()
        barrier.markSwept()

        assertTrue(barrier.awaitSwept())
    }

    @Test
    fun `a sweep that never finishes times out rather than pinning the job forever`() = runTest {
        // runTest's scheduler skips the delay, so this asserts the outcome and not the duration.
        val barrier = LaunchSweepBarrier()

        assertFalse(barrier.awaitSwept())
    }

    @Test
    fun `a timeout is reported as false and not raised as an exception`() = runTest {
        // withTimeout would throw and be caught by ThorJobWorker's generic handler, which reports
        // "unknown error" — the one sentence that tells the user nothing. withTimeoutOrNull is what
        // lets the worker word this itself.
        val barrier = LaunchSweepBarrier()

        val outcome = runCatching { barrier.awaitSwept(1L) }

        assertNull(outcome.exceptionOrNull())
        assertEquals(false, outcome.getOrNull())
    }

    @Test
    fun `marking twice is not an error`() = runTest {
        // ThorApplication marks from a `finally`, and a future caller adding a success-path mark would
        // otherwise be a crash at startup rather than a no-op.
        val barrier = LaunchSweepBarrier()

        barrier.markSwept()
        barrier.markSwept()

        assertTrue(barrier.awaitSwept())
    }

    @Test
    fun `a sweep that threw still releases the waiters`() = runTest {
        // The `finally` placement, stated as a test. A sweep that failed has stopped deleting, so
        // holding an export behind it protects nothing and costs two minutes.
        val barrier = LaunchSweepBarrier()

        runCatching {
            try {
                throw IOException("sweep failed")
            } finally {
                barrier.markSwept()
            }
        }

        assertTrue(barrier.awaitSwept())
    }
}
