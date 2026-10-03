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

    @Test
    fun `KernelSU forks retain their own manager names and launch packages`() {
        val packages = setOf("me.weishu.kernelsu", "com.resukisu.resukisu", "com.sukisu.ultra")

        val installed = PrivilegeManagerApp.findInstalledManagers { it in packages }

        assertEquals(
            listOf(
                InstalledManagerInfo(PrivilegeManagerApp.KERNEL_SU, "me.weishu.kernelsu"),
                InstalledManagerInfo(PrivilegeManagerApp.RE_SUKI_SU, "com.resukisu.resukisu"),
                InstalledManagerInfo(PrivilegeManagerApp.SUKI_SU_ULTRA, "com.sukisu.ultra"),
            ),
            installed,
        )
        assertEquals(listOf("KernelSU", "ReSukiSU", "SukiSU Ultra"), installed.map { it.app.displayName })
        assertTrue(installed.all { it.app.mode == PrivilegeMode.ROOT })
    }

    @Test
    fun `each package identifies at most one registered manager`() {
        val packages = PrivilegeManagerApp.entries.flatMap { it.packageNames }

        assertEquals(packages.size, packages.toSet().size)
    }

    @Test
    fun `unknown or randomized package names are not inferred to be root managers`() {
        val packages = setOf("zaqxsw.edcrfv.tgbyhn", "com.resukisu.resukisu.clone", "com.sukisu.ultra.clone")

        assertTrue(PrivilegeManagerApp.findInstalledManagers { it in packages }.isEmpty())
    }
}
