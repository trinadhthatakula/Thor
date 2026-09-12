// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FixStoreRouteTest {
    @Test
    fun `no privilege and Dhizuku cannot fix store`() {
        for (mode in listOf(PrivilegeMode.NONE, PrivilegeMode.DHIZUKU)) {
            assertEquals(FixStoreRoute.UNAVAILABLE, fixStoreRoute(mode))
        }
    }

    @Test
    fun `Root and Shizuku retain privileged installation`() {
        for (mode in listOf(PrivilegeMode.ROOT, PrivilegeMode.SHIZUKU)) {
            assertEquals(FixStoreRoute.PRIVILEGED, fixStoreRoute(mode))
        }
    }
}
