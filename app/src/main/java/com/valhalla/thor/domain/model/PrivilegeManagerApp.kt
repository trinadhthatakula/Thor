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
    val packageNames: Set<String>,
    val launcherClassNames: Set<String> = emptySet(),
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
    RE_SUKI_SU(
        displayName = "ReSukiSU",
        mode = PrivilegeMode.ROOT,
        packageNames = setOf("com.resukisu.resukisu"),
        launcherClassNames = setOf(
            "com.resukisu.resukisu.ui.MainActivity",
            "com.resukisu.resukisu.ui.MainActivityAlias",
        ),
    ),
    SUKI_SU_ULTRA(
        displayName = "SukiSU Ultra",
        mode = PrivilegeMode.ROOT,
        packageNames = setOf("com.sukisu.ultra"),
        launcherClassNames = setOf(
            "com.sukisu.ultra.ui.MainActivity",
            "com.sukisu.ultra.ui.MainActivityAlias",
        ),
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
         * Finds usable manager shortcuts, including renamed builds that retain a known launcher.
         * Package and launcher names are discovery hints only; neither establishes root access.
         */
        fun findInstalledManagers(pm: PackageManager): List<InstalledManagerInfo> {
            val installed = findInstalledManagers { RootManagerShortcuts.resolve(pm, it) != null }
                .associateByTo(mutableMapOf()) { it.app }
            val usedPackages = installed.values.mapTo(mutableSetOf()) { it.installedPackageName }
            val launchers = try {
                RootManagerShortcuts.launcherActivities(pm)
                    .sortedWith(compareBy({ it.packageName }, { it.name }))
            } catch (_: RuntimeException) {
                // Discovery is optional; a failed package query must not stop root-state updates.
                emptyList()
            }
            for (activity in launchers) {
                if (!activity.enabled || !activity.exported) continue
                val manager = entries.firstOrNull {
                    it !in installed && activity.name in it.launcherClassNames
                } ?: continue
                val packageName = activity.packageName ?: continue
                if (packageName in usedPackages) continue
                if (RootManagerShortcuts.resolve(pm, packageName) == null) continue
                installed[manager] = InstalledManagerInfo(manager, packageName)
                usedPackages += packageName
            }
            return entries.mapNotNull { installed[it] }
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
