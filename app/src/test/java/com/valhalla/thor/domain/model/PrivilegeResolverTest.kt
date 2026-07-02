package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeResolverTest {

    @Test
    fun preferred_isUsed_whenAvailable() {
        assertEquals(
            PrivilegeMode.SHIZUKU,
            resolvePrivilegeMode(PrivilegeMode.SHIZUKU, root = true, shizuku = true, dhizuku = false)
        )
    }

    @Test
    fun preferred_fallsBack_whenUnavailable() {
        // Preferred SHIZUKU not available -> auto fallback picks ROOT (highest available).
        assertEquals(
            PrivilegeMode.ROOT,
            resolvePrivilegeMode(PrivilegeMode.SHIZUKU, root = true, shizuku = false, dhizuku = true)
        )
    }

    @Test
    fun noPreference_usesFallbackChain_rootFirst() {
        assertEquals(
            PrivilegeMode.ROOT,
            resolvePrivilegeMode(null, root = true, shizuku = true, dhizuku = true)
        )
    }

    @Test
    fun noPreference_shizukuBeforeDhizuku() {
        assertEquals(
            PrivilegeMode.SHIZUKU,
            resolvePrivilegeMode(null, root = false, shizuku = true, dhizuku = true)
        )
    }

    @Test
    fun noneAvailable_isNone() {
        assertEquals(
            PrivilegeMode.NONE,
            resolvePrivilegeMode(PrivilegeMode.ROOT, root = false, shizuku = false, dhizuku = false)
        )
    }

    @Test
    fun preferredNone_isTreatedAsAuto() {
        assertEquals(
            PrivilegeMode.DHIZUKU,
            resolvePrivilegeMode(PrivilegeMode.NONE, root = false, shizuku = false, dhizuku = true)
        )
    }
}
