// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppScanRevision] is a process-wide singleton, so nothing here may assert an absolute value —
 * every assertion is relative to the revision read at the start of the test.
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
    fun `a collector arriving after a bump does not re-scan for it`() = runTest {
        // `.drop(1)` is the collector contract: the value already carries the earlier bump, and the
        // collector's own scan-on-subscribe covers it.
        AppScanRevision.bump()

        var signals = 0
        val watcher = launch { AppScanRevision.revision.drop(1).collect { signals++ } }
        yield()

        assertEquals(0, signals)
        watcher.cancel()
    }

    @Test
    fun `a live collector sees every later bump`() = runTest {
        val watcher = async { AppScanRevision.revision.drop(1).first() }
        yield()

        val before = AppScanRevision.revision.value
        AppScanRevision.bump()

        assertEquals(before + 1, watcher.await())
    }

    @Test
    fun `the revision only ever moves forward`() {
        val readings = (1..5).map {
            AppScanRevision.bump()
            AppScanRevision.revision.value
        }

        assertTrue("$readings is not strictly increasing", readings.zipWithNext().all { it.first < it.second })
    }
}
