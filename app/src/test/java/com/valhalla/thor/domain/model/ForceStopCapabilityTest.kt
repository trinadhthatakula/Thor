// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceStopCapabilityTest {
    @Test
    fun `Dhizuku stays unsupported when other transports are available`() {
        val state = PrivilegeState(
            root = true, shizuku = true, dhizuku = true,
            active = PrivilegeMode.DHIZUKU, isReady = true,
        )
        assertFalse(canForceStopApps(state))
        assertTrue(canForceStopApps(state.copy(active = PrivilegeMode.ROOT)))
        assertTrue(canForceStopApps(state.copy(active = PrivilegeMode.SHIZUKU)))
        assertFalse(canForceStopApps(state.copy(active = PrivilegeMode.NONE)))
    }

    @Test
    fun `an unsettled probe never offers force stop`() {
        for (mode in PrivilegeMode.entries) {
            assertFalse(canForceStopApps(PrivilegeState(active = mode, isReady = false)))
        }
    }
}
