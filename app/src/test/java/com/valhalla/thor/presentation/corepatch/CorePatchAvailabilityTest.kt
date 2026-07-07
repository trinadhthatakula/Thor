package com.valhalla.thor.presentation.corepatch

import com.valhalla.thor.domain.model.PrivilegeMode
import org.junit.Assert.*
import org.junit.Test

class CorePatchAvailabilityTest {
    @Test
    fun `available only for root + lsposed`() {
        assertTrue(corePatchAvailable(PrivilegeMode.ROOT, true))
        assertFalse(corePatchAvailable(PrivilegeMode.ROOT, false))
        assertFalse(corePatchAvailable(PrivilegeMode.SHIZUKU, true))
        assertFalse(corePatchAvailable(PrivilegeMode.DHIZUKU, true))
        assertFalse(corePatchAvailable(PrivilegeMode.NONE, true))
    }
}
