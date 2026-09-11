// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FixStoreRouteTest {
    @Test
    fun `no privilege and Dhizuku use legacy only through SDK32`() {
        for (mode in listOf(PrivilegeMode.NONE, PrivilegeMode.DHIZUKU)) {
            for (sdk in 28..32) assertEquals(FixStoreRoute.LEGACY, fixStoreRoute(sdk, mode))
            for (sdk in listOf(27, 33, 36, 37)) {
                assertEquals(FixStoreRoute.UNAVAILABLE, fixStoreRoute(sdk, mode))
            }
        }
    }

    @Test
    fun `Root and Shizuku retain privileged installation on all supported versions`() {
        for (mode in listOf(PrivilegeMode.ROOT, PrivilegeMode.SHIZUKU)) {
            for (sdk in 28..37) assertEquals(FixStoreRoute.PRIVILEGED, fixStoreRoute(sdk, mode))
        }
    }
}
