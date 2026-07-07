// CorePatchArmStateHolderTest.kt
package com.valhalla.thor.data.corepatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorePatchArmStateHolderTest {
    private var now = 0L
    private val holder = CorePatchArmStateHolder(clock = { now })

    @Test fun `starts disarmed`() = assertFalse(holder.current().armed)

    @Test fun `arm then current is armed with fields`() {
        holder.arm("com.foo", "AABB", "sig", ttlMillis = 100)
        val s = holder.current()
        assertTrue(s.armed)
        assertEquals("com.foo", s.pkg)
        assertEquals("AABB", s.signerSha256)
        assertEquals("sig", s.capability)
        assertEquals(100L, s.deadlineMillis)
    }

    @Test fun `expires after deadline via lazy clock`() {
        holder.arm("com.foo", "AABB", "sig", ttlMillis = 100)
        now = 101
        assertFalse(holder.current().armed)
    }

    @Test fun `disarm clears immediately`() {
        holder.arm("com.foo", "AABB", "sig", ttlMillis = 100)
        holder.disarm()
        assertFalse(holder.current().armed)
    }

    @Test fun `bundle map matches contract when armed`() {
        holder.arm("com.foo", "AABB", "sig", ttlMillis = 100)
        val m = holder.toBundleMap()
        assertEquals(true, m["armed"])
        assertEquals("com.foo", m["pkg"])
        assertEquals("AABB", m["signerSha256"])
        assertEquals("sig", m["capability"])
        assertEquals(100L, m["deadlineMillis"])
    }

    @Test fun `bundle map is disarmed-only when not armed`() {
        val m = holder.toBundleMap()
        assertEquals(false, m["armed"])
        assertFalse(m.containsKey("pkg"))
    }
}
