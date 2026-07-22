// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decision logic for Freezer freeze/suspend mode (GH#239). No Android deps. */
class FreezerModeTest {

    @Test
    fun `enabled non-suspended app is active and not frozen`() {
        assertTrue(isActive(enabled = true, isSuspended = false))
        assertFalse(isFrozen(enabled = true, isSuspended = false))
    }

    @Test
    fun `disabled app is frozen`() {
        assertTrue(isFrozen(enabled = false, isSuspended = false))
        assertFalse(isActive(enabled = false, isSuspended = false))
    }

    @Test
    fun `suspended app is frozen even while still enabled`() {
        assertTrue(isFrozen(enabled = true, isSuspended = true))
        assertFalse(isActive(enabled = true, isSuspended = true))
    }

    @Test
    fun `restore of a suspended-only app just unsuspends`() {
        assertEquals(
            RestorePlan(unsuspend = true, enable = false),
            restorePlanFor(enabled = true, isSuspended = true)
        )
    }

    @Test
    fun `restore of a disabled-only app just enables`() {
        assertEquals(
            RestorePlan(unsuspend = false, enable = true),
            restorePlanFor(enabled = false, isSuspended = false)
        )
    }

    @Test
    fun `restore of a disabled AND suspended app clears both dimensions`() {
        assertEquals(
            RestorePlan(unsuspend = true, enable = true),
            restorePlanFor(enabled = false, isSuspended = true)
        )
    }

    @Test
    fun `restore of an already-active app is a no-op`() {
        assertEquals(
            RestorePlan(unsuspend = false, enable = false),
            restorePlanFor(enabled = true, isSuspended = false)
        )
    }

    @Test
    fun `default freezer mode is FREEZE`() {
        assertEquals(FreezerMode.FREEZE, FreezerMode.entries.first())
    }
}
