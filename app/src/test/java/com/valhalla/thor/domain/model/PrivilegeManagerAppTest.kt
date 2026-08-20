// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeManagerAppTest {

    @Test
    fun `every registered privilege manager has valid metadata`() {
        for (manager in PrivilegeManagerApp.entries) {
            assertTrue("Display name must not be empty", manager.displayName.isNotBlank())
            assertTrue("Package names must not be empty", manager.packageNames.isNotEmpty())
            assertTrue("Mode must be valid", manager.mode != PrivilegeMode.NONE)
            for (pkg in manager.packageNames) {
                assertTrue("Package name must contain a dot", pkg.contains('.'))
            }
        }
    }

    @Test
    fun `findInstalledManagers returns only installed manager packages`() {
        val installedPackages = setOf("me.weishu.kernelsu", "moe.shizuku.privileged.api")

        val installed = PrivilegeManagerApp.findInstalledManagers { pkg ->
            pkg in installedPackages
        }

        val installedApps = installed.map { it.app }
        assertEquals(2, installed.size)
        assertTrue(installedApps.contains(PrivilegeManagerApp.KERNEL_SU))
        assertTrue(installedApps.contains(PrivilegeManagerApp.SHIZUKU))
        assertTrue(!installedApps.contains(PrivilegeManagerApp.DHIZUKU))
    }
}
