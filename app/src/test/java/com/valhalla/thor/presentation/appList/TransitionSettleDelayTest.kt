// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import com.valhalla.thor.domain.model.AnimationIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class TransitionSettleDelayTest {

    @Test
    fun low_doesNotWaitAtAll() {
        // MainScreen gives LOW a snap() spec, i.e. no animation runs on entry. Anything above zero
        // here is pure dead time in front of the package scan.
        assertEquals(Duration.ZERO, settleDelayFor(AnimationIntensity.LOW))
    }

    @Test
    fun mediumAndHighWaitForTheirAnimations() {
        assertEquals(400.milliseconds, settleDelayFor(AnimationIntensity.MEDIUM))
        assertEquals(800.milliseconds, settleDelayFor(AnimationIntensity.HIGH))
    }

    @Test
    fun delayIncreasesWithIntensity() {
        // HIGH additionally runs shared-element transitions, so it must never settle faster than
        // MEDIUM, which must never settle faster than LOW.
        val low = settleDelayFor(AnimationIntensity.LOW)
        val medium = settleDelayFor(AnimationIntensity.MEDIUM)
        val high = settleDelayFor(AnimationIntensity.HIGH)
        assertTrue("MEDIUM must not settle faster than LOW", medium >= low)
        assertTrue("HIGH must not settle faster than MEDIUM", high >= medium)
    }

    @Test
    fun refreshIndicatorFloorIsActuallyVisible() {
        // The whole point of the floor is that a manual refresh stays legible even though
        // isLoading clears on the Room-cache emission. Zero would silently reinstate the flash.
        assertTrue(
            "the refresh indicator floor must be > 0",
            REFRESH_INDICATOR_MIN_VISIBLE > Duration.ZERO
        )
    }

    @Test
    fun refreshIndicatorFloorDoesNotScaleWithIntensity() {
        // A refresh is direct manipulation the user is waiting on; its feedback must not be tied
        // to the decorative-motion preference the way the entry settle delay is.
        assertTrue(
            "the refresh floor must stay legible even at LOW, where the settle delay is zero",
            REFRESH_INDICATOR_MIN_VISIBLE > settleDelayFor(AnimationIntensity.LOW)
        )
    }

    @Test
    fun everyIntensityIsMappedAndNonNegative() {
        // Guards a future enum entry being added without a matching delay decision.
        AnimationIntensity.entries.forEach { intensity ->
            assertTrue("$intensity maps to a negative delay", settleDelayFor(intensity) >= Duration.ZERO)
        }
    }
}
