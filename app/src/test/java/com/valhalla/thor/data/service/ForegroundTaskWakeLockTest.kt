// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.service

import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ForegroundTaskWakeLockTest {

    @Test
    fun `owners create distinct non-reference-counted partial wake locks`() {
        val dataFactory = RecordingWakeLockFactory()
        val sweepFactory = RecordingWakeLockFactory()

        ForegroundTaskWakeLock(ForegroundTaskOwner.DATA_SYNC, dataFactory)
        ForegroundTaskWakeLock(ForegroundTaskOwner.PRIVILEGE_SWEEP, sweepFactory)

        assertEquals(PowerManager.PARTIAL_WAKE_LOCK, dataFactory.levelAndFlags)
        assertEquals("Thor:DataSync", dataFactory.tag)
        assertEquals(false, dataFactory.lock.referenceCounted)
        assertEquals(PowerManager.PARTIAL_WAKE_LOCK, sweepFactory.levelAndFlags)
        assertEquals("Thor:PrivilegeSweep", sweepFactory.tag)
        assertEquals(false, sweepFactory.lock.referenceCounted)
    }

    @Test
    fun `claimed execution acquires a ten minute lease immediately before the runner`() = runTest {
        val events = mutableListOf<String>()
        val factory = RecordingWakeLockFactory(events)
        val owner = ForegroundTaskWakeLock(ForegroundTaskOwner.DATA_SYNC, factory)
        events += "claim"

        owner.withClaimedExecution {
            events += "runner"
            assertTrue(factory.lock.isHeld)
        }

        assertEquals(
            listOf("claim", "acquire:600000", "runner", "release"),
            events,
        )
        assertEquals(listOf(600_000L), factory.lock.acquisitionTimeouts)
    }

    @Test
    fun `lease renews only after a durable checkpoint`() = runTest {
        val events = mutableListOf<String>()
        val factory = RecordingWakeLockFactory(events)
        val owner = ForegroundTaskWakeLock(ForegroundTaskOwner.DATA_SYNC, factory)

        owner.withClaimedExecution {
            assertEquals(listOf(600_000L), factory.lock.acquisitionTimeouts)
            events += "checkpoint-persisted"
            renewAfterPersistedCheckpoint()
            assertEquals(listOf(600_000L, 600_000L), factory.lock.acquisitionTimeouts)
        }

        assertEquals(
            listOf(
                "acquire:600000",
                "checkpoint-persisted",
                "release",
                "acquire:600000",
                "release",
            ),
            events,
        )
    }

    @Test
    fun `renewal outside active claimed execution is rejected`() {
        val factory = RecordingWakeLockFactory()
        val owner = ForegroundTaskWakeLock(ForegroundTaskOwner.DATA_SYNC, factory)

        assertThrows(IllegalStateException::class.java) {
            owner.renewAfterPersistedCheckpoint()
        }
        assertTrue(factory.lock.acquisitionTimeouts.isEmpty())
    }

    @Test
    fun `claimed execution always releases after success`() = runTest {
        val factory = RecordingWakeLockFactory()
        val owner = ForegroundTaskWakeLock(ForegroundTaskOwner.DATA_SYNC, factory)

        val value = owner.withClaimedExecution { "complete" }

        assertEquals("complete", value)
        assertEquals(1, factory.lock.releaseCount)
        assertFalse(factory.lock.isHeld)
    }

    @Test
    fun `claimed execution always releases after failure`() {
        val factory = RecordingWakeLockFactory()
        val owner = ForegroundTaskWakeLock(ForegroundTaskOwner.DATA_SYNC, factory)
        val failure = IllegalStateException("runner failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            runTest {
                owner.withClaimedExecution { throw failure }
            }
        }

        assertSame(failure, thrown)
        assertEquals(1, factory.lock.releaseCount)
        assertFalse(factory.lock.isHeld)
    }

    @Test
    fun `claimed execution always releases after cancellation`() {
        val factory = RecordingWakeLockFactory()
        val owner = ForegroundTaskWakeLock(ForegroundTaskOwner.PRIVILEGE_SWEEP, factory)

        assertThrows(CancellationException::class.java) {
            runTest {
                owner.withClaimedExecution { throw CancellationException("cancelled") }
            }
        }

        assertEquals(1, factory.lock.releaseCount)
        assertFalse(factory.lock.isHeld)
    }

    private class RecordingWakeLockFactory(
        events: MutableList<String> = mutableListOf(),
    ) : ForegroundWakeLockFactory {
        val lock = RecordingWakeLock(events)
        var levelAndFlags: Int? = null
        var tag: String? = null

        override fun create(levelAndFlags: Int, tag: String): ForegroundWakeLock {
            this.levelAndFlags = levelAndFlags
            this.tag = tag
            return lock
        }
    }

    private class RecordingWakeLock(
        private val events: MutableList<String>,
    ) : ForegroundWakeLock {
        override var isHeld: Boolean = false
            private set
        var referenceCounted: Boolean? = null
            private set
        val acquisitionTimeouts = mutableListOf<Long>()
        var releaseCount = 0
            private set

        override fun setReferenceCounted(value: Boolean) {
            referenceCounted = value
        }

        override fun acquire(timeoutMillis: Long) {
            acquisitionTimeouts += timeoutMillis
            events += "acquire:$timeoutMillis"
            isHeld = true
        }

        override fun release() {
            events += "release"
            releaseCount += 1
            isHeld = false
        }
    }
}
