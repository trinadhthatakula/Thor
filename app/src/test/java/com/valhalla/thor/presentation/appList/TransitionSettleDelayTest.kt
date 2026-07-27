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
    fun refreshIndicatorFloorStaysPerceptible() {
        // Pinned exactly, like MEDIUM/HIGH above. A `> Duration.ZERO` bound looks like it protects
        // this value but does not: 1.milliseconds satisfies it while reinstating the very indicator
        // flash the floor exists to prevent.
        assertEquals(600.milliseconds, REFRESH_INDICATOR_MIN_VISIBLE)
    }

    @Test
    fun everyIntensityMapsToANonNegativeDelay() {
        // Note this does NOT guard against a new enum entry: settleDelayFor is an expression-body
        // `when` with no `else`, so an unmapped entry is a compile error, not a test failure. What
        // it does catch is a negative literal, which delay() would treat as zero and silently skip.
        AnimationIntensity.entries.forEach { intensity ->
            assertTrue("$intensity maps to a negative delay", settleDelayFor(intensity) >= Duration.ZERO)
        }
    }
}
