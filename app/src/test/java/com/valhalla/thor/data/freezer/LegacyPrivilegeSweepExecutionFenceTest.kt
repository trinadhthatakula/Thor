// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPrivilegeSweepExecutionFenceTest {

    @Test
    fun `closing admission rejects racing worker and waits for registered body`() = runTest {
        val fence = LegacyPrivilegeSweepExecutionFence()
        val registration = fence.tryRegister()
        assertNotNull(registration)

        fence.closeAdmission()
        val quiescence = async { fence.awaitQuiescence() }
        runCurrent()

        assertFalse(quiescence.isCompleted)
        assertNull(fence.tryRegister())
        assertEquals(1, fence.activeExecutionsForTest())

        registration!!.close()
        quiescence.await()
        assertEquals(0, fence.activeExecutionsForTest())
    }

    @Test
    fun `registration release and admission closure are idempotent`() = runTest {
        val fence = LegacyPrivilegeSweepExecutionFence()
        val registration = checkNotNull(fence.tryRegister())

        registration.close()
        registration.close()
        fence.closeAdmission()
        fence.closeAdmission()
        fence.awaitQuiescence()

        assertFalse(fence.isAdmissionOpenForTest())
        assertEquals(0, fence.activeExecutionsForTest())
        assertNull(fence.tryRegister())
    }

    @Test
    fun `closing an idle fence is immediately quiescent`() = runTest {
        val fence = LegacyPrivilegeSweepExecutionFence()

        fence.closeAdmission()
        fence.awaitQuiescence()

        assertTrue(!fence.isAdmissionOpenForTest())
    }
}
