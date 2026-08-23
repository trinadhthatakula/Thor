// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import android.content.pm.PackageManager

/**
 * Registry of known Android privilege manager applications (Root, Shizuku, Dhizuku).
 *
 * Used to detect installed management apps, deep-link to them so users can grant permissions
 * directly without searching their app drawer, and display tailored privilege dialog states.
 */
enum class PrivilegeManagerApp(
    val displayName: String,
    val mode: PrivilegeMode,
    val packageNames: Set<String>
) {
    // Shizuku
    SHIZUKU(
        displayName = "Shizuku",
        mode = PrivilegeMode.SHIZUKU,
        packageNames = setOf("moe.shizuku.privileged.api")
    ),

    // Dhizuku
    DHIZUKU(
        displayName = "Dhizuku",
        mode = PrivilegeMode.DHIZUKU,
        packageNames = setOf("com.rosan.dhizuku")
    ),

    // Kernel-level Root Managers
    KERNEL_SU(
        displayName = "KernelSU",
        mode = PrivilegeMode.ROOT,
        packageNames = setOf("me.weishu.kernelsu")
    ),
    KERNEL_SU_NEXT(
        displayName = "KernelSU Next",
        mode = PrivilegeMode.ROOT,
        packageNames = setOf("com.rifsxd.ksunext")
    ),
    WILD_KSU(
        displayName = "Wild KSU",
        mode = PrivilegeMode.ROOT,
        packageNames = setOf("com.wild.ksu")
    ),
    APATCH(
        displayName = "APatch",
        mode = PrivilegeMode.ROOT,
        packageNames = setOf("me.bmax.apatch")
    ),

    // Userspace Root Managers
    MAGISK(
        displayName = "Magisk",
        mode = PrivilegeMode.ROOT,
        packageNames = setOf("com.topjohnwu.magisk")
    ),
    MAGISK_ALPHA(
        displayName = "Magisk Alpha",
        mode = PrivilegeMode.ROOT,
        packageNames = setOf("io.github.vvb2060.magisk", "io.github.vvb2060.magisk.lite")
    ),
    KITSUNE_MASK(
        displayName = "Kitsune Mask",
        mode = PrivilegeMode.ROOT,
        packageNames = setOf("io.github.huskydg.magisk")
    ),
    SUPERSU(
        displayName = "SuperSU",
        mode = PrivilegeMode.ROOT,
        packageNames = setOf("eu.chainfire.supersu")
    );

    companion object {
        /**
         * Pure functional resolver that identifies which managers match the given package lookup function.
         */
        fun findInstalledManagers(
            isPackageInstalled: (String) -> Boolean
        ): List<InstalledManagerInfo> {
            val installed = mutableListOf<InstalledManagerInfo>()
            for (app in entries) {
                for (pkg in app.packageNames) {
                    if (isPackageInstalled(pkg)) {
                        installed.add(
                            InstalledManagerInfo(
                                app = app,
                                installedPackageName = pkg
                            )
                        )
                        break // one package match per manager entry
                    }
                }
            }
            return installed
        }

        /**
         * Detects all known privilege manager applications currently installed on the device via PackageManager.
         */
        fun findInstalledManagers(pm: PackageManager): List<InstalledManagerInfo> =
            findInstalledManagers { pkg ->
                try {
                    pm.getPackageInfo(pkg, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
    }
}

/**
 * Information about a detected privilege manager app installed on the device.
 */
data class InstalledManagerInfo(
    val app: PrivilegeManagerApp,
    val installedPackageName: String
)
