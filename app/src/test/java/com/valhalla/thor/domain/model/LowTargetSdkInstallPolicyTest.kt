// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import com.valhalla.thor.domain.repository.InstallMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowTargetSdkInstallPolicyTest {
    @Test
    fun `Android 14 blocks below 23 and Android 15 and 16 below 24`() {
        assertNull(minimumInstallTargetSdk(33))
        assertEquals(23, minimumInstallTargetSdk(34))
        assertEquals(24, minimumInstallTargetSdk(35))
        assertEquals(24, minimumInstallTargetSdk(36))
        assertTrue(requiresLowTargetSdkBypass(22, 34))
        assertFalse(requiresLowTargetSdkBypass(23, 34))
        for (deviceSdk in 35..36) {
            assertTrue(requiresLowTargetSdkBypass(23, deviceSdk))
            assertFalse(requiresLowTargetSdkBypass(24, deviceSdk))
        }
    }

    @Test
    fun `unknown and negative target SDK never enable consent`() {
        for (deviceSdk in 33..36) {
            assertFalse(requiresLowTargetSdkBypass(null, deviceSdk))
            assertFalse(requiresLowTargetSdkBypass(-1, deviceSdk))
        }
        assertFalse(requiresLowTargetSdkBypass(22, 33))
    }

    @Test
    fun `parsed target zero is a legacy target not unknown metadata`() {
        for (deviceSdk in 34..36) {
            assertTrue(requiresLowTargetSdkBypass(0, deviceSdk))
        }
    }

    @Test
    fun `only Root and Shizuku on Android 14 or later support the override`() {
        for (deviceSdk in 24..36) {
            for (mode in InstallMode.entries) {
                assertEquals(
                    "$mode on API $deviceSdk",
                    deviceSdk >= 34 && mode in listOf(InstallMode.ROOT, InstallMode.SHIZUKU),
                    supportsLowTargetSdkBypass(mode, deviceSdk),
                )
            }
        }
    }
}
