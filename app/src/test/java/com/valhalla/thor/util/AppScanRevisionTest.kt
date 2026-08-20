// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppScanRevision] is a process-wide singleton, so nothing here may assert an absolute value —
 * every assertion is relative to a baseline read at the start of the test.
 */
class AppScanRevisionTest {

    @Test
    fun `a bump made with no collector is not lost`() {
        // The regression this guards: as a `MutableSharedFlow(replay = 0)` the bump raised by the
        // self-grant during ThorApplication startup was dropped whenever it beat the first
        // ViewModel to the flow, leaving a Chinese-ROM user's list holding only Thor.
        val before = AppScanRevision.revision.value

        AppScanRevision.bump()

        assertEquals(before + 1, AppScanRevision.revision.value)
    }

    @Test
    fun `a bump raised during the initial scan reaches a watcher that subscribes afterwards`() =
        runTest {
            // The repository's scan worker runs on a multi-threaded dispatcher, so it can already
            // be inside getInstalledPackages() when a self-grant opens package visibility, and its
            // watcher may not have subscribed yet. This is that ordering, deterministically: the
            // baseline is taken, a bump lands with *nobody collecting*, and only then does the
            // watcher arrive. Under the previous `revision.drop(1)` the bump was discarded as the
            // replayed value and no rescan was ever scheduled.
            val baseline = AppScanRevision.snapshot()

            AppScanRevision.bump()

            assertEquals(baseline + 1, AppScanRevision.requestsAfter(baseline).first())

            // And the proof that this is a real fix rather than a rename: with nothing further
            // bumped, the same late subscription under the old `revision.drop(1)` never emits at
            // all. runTest virtual time, so the timeout costs no wall clock.
            assertNull(
                "revision.drop(1) emitted; this test would no longer discriminate",
                withTimeoutOrNull(1_000) { AppScanRevision.revision.drop(1).first() }
            )
        }

    @Test
    fun `a watcher that subscribes with nothing bumped stays quiet`() = runTest {
        // The other half of the contract: no redundant second scan on top of the initial one the
        // caller runs for itself.
        val baseline = AppScanRevision.snapshot()

        var signals = 0
        val watcher = launch { AppScanRevision.requestsAfter(baseline).collect { signals++ } }
        yield()

        assertEquals(0, signals)
        watcher.cancel()
    }

    @Test
    fun `a live watcher sees every later bump`() = runTest {
        val baseline = AppScanRevision.snapshot()
        val watcher = async { AppScanRevision.requestsAfter(baseline).first() }
        yield()

        AppScanRevision.bump()

        assertEquals(baseline + 1, watcher.await())
    }

    @Test
    fun `bumps that beat the watcher and bumps that follow it are both delivered`() = runTest {
        // Combines the two orderings in one stream: the value carries the pre-subscribe bump, and
        // the post-subscribe bump arrives as a change. Two triggers, never zero.
        val baseline = AppScanRevision.snapshot()
        AppScanRevision.bump()

        val watcher = async { AppScanRevision.requestsAfter(baseline).take(2).toList() }
        yield()

        AppScanRevision.bump()

        assertEquals(listOf(baseline + 1, baseline + 2), watcher.await())
    }

    @Test
    fun `the revision only ever moves forward`() {
        val readings = (1..5).map {
            AppScanRevision.bump()
            AppScanRevision.revision.value
        }

        assertTrue(
            "$readings is not strictly increasing",
            readings.zipWithNext().all { it.first < it.second }
        )
    }
}
