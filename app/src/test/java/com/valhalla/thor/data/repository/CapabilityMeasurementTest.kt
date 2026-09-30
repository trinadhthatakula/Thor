// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityMeasurementTest {
    @Test fun `completed supported and unsupported measurements remain cacheable`() {
        assertTrue(measuredCapability(Result.success(0 to "THOR_OK")))
        assertFalse(measuredCapability(Result.success(1 to "")))
    }

    @Test fun `transport failure cancellation and malformed replies never become unsupported`() {
        for (failure in listOf(IllegalStateException("transport failure"), CancellationException("cancelled"))) {
            assertSame(failure, runCatching { measuredCapability(Result.failure(failure)) }.exceptionOrNull())
        }
        for (result in listOf(-1 to "lost shell", 0 to "missing marker", 127 to "missing command")) {
            assertTrue(runCatching { measuredCapability(Result.success(result)) }.isFailure)
        }
    }
}
