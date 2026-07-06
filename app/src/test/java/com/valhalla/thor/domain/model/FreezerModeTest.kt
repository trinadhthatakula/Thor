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
    fun `restore of a suspended app unsuspends`() {
        assertEquals(FreezerRestore.UNSUSPEND, restoreActionFor(enabled = true, isSuspended = true))
    }

    @Test
    fun `restore of a disabled app enables`() {
        assertEquals(FreezerRestore.ENABLE, restoreActionFor(enabled = false, isSuspended = false))
    }

    @Test
    fun `restore of an already-active app is a no-op`() {
        assertEquals(FreezerRestore.NONE, restoreActionFor(enabled = true, isSuspended = false))
    }

    @Test
    fun `default freezer mode is FREEZE`() {
        assertEquals(FreezerMode.FREEZE, FreezerMode.entries.first())
    }
}
