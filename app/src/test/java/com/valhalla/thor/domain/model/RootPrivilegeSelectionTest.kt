// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RootPrivilegeSelectionTest {
    @Test
    fun `automatic and preferred root follow the last root confirmation`() {
        for (confirmation in RootConfirmation.entries) {
            for (status in RootRefreshStatus.entries) {
                val root = RootAvailabilityState(confirmation = confirmation, refreshStatus = status)
                val expected = if (confirmation == RootConfirmation.NON_ROOT) {
                    PrivilegeMode.SHIZUKU
                } else PrivilegeMode.ROOT
                for (preferred in listOf(null, PrivilegeMode.NONE, PrivilegeMode.ROOT)) {
                    assertEquals(expected, resolvePrivilegeMode(preferred, root, shizuku = true, dhizuku = true))
                }
            }
        }
    }

    @Test
    fun `automatic Shizuku survives unresolved refresh until root is confirmed`() {
        var root = RootAvailabilityState(confirmation = RootConfirmation.NON_ROOT)
        assertEquals(PrivilegeMode.SHIZUKU, resolvePrivilegeMode(null, root, true, true))

        for (status in listOf(RootRefreshStatus.CHECKING, RootRefreshStatus.BUSY, RootRefreshStatus.TIMED_OUT, RootRefreshStatus.FAILED)) {
            root = root.copy(refreshStatus = status)
            assertEquals(PrivilegeMode.SHIZUKU, resolvePrivilegeMode(null, root, true, true))
        }

        root = root.copy(confirmation = RootConfirmation.ROOT, refreshStatus = RootRefreshStatus.IDLE)
        assertEquals(PrivilegeMode.ROOT, resolvePrivilegeMode(null, root, true, true))
    }

    @Test
    fun `explicit available alternatives win while root is unresolved`() {
        for (status in RootRefreshStatus.entries) {
            val root = RootAvailabilityState(refreshStatus = status)
            assertEquals(PrivilegeMode.SHIZUKU, resolvePrivilegeMode(PrivilegeMode.SHIZUKU, root, true, true))
            assertEquals(PrivilegeMode.DHIZUKU, resolvePrivilegeMode(PrivilegeMode.DHIZUKU, root, true, true))
        }
    }

    @Test
    fun `confirmed non-root preserves fallback order and original preference`() {
        val root = RootAvailabilityState(confirmation = RootConfirmation.NON_ROOT)
        assertEquals(PrivilegeMode.DHIZUKU, resolvePrivilegeMode(PrivilegeMode.ROOT, root, false, true))
        assertEquals(PrivilegeMode.NONE, resolvePrivilegeMode(PrivilegeMode.ROOT, root, false, false))
        assertEquals(
            PrivilegeMode.ROOT,
            resolvePrivilegeMode(PrivilegeMode.ROOT, root.copy(confirmation = RootConfirmation.ROOT), true, true),
        )
    }
}
