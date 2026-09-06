// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.share

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadyShareAccessLockTest {

    @Test
    fun `access is serialized until the current owner leaves`() = runTest {
        val lock = ReadyShareAccessLock()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val first = launch {
            lock.withLock {
                events += "first-entered"
                firstEntered.complete(Unit)
                releaseFirst.await()
                events += "first-leaving"
            }
        }
        firstEntered.await()
        val second = launch {
            lock.withLock {
                events += "second-entered"
            }
        }

        runCurrent()
        assertEquals(listOf("first-entered"), events)

        releaseFirst.complete(Unit)
        joinAll(first, second)
        assertEquals(
            listOf("first-entered", "first-leaving", "second-entered"),
            events,
        )
    }

    @Test
    fun `failure releases access for the next owner`() = runTest {
        val lock = ReadyShareAccessLock()
        var secondEntered = false

        try {
            lock.withLock<Unit> { error("expected") }
        } catch (_: IllegalStateException) {
            // The next acquisition is the assertion: a leaked lock would suspend forever.
        }
        lock.withLock { secondEntered = true }

        assertTrue(secondEntered)
    }
}
