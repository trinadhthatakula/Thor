// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.manager

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * Computes total install size (app + data + cache) per package via
 * StorageStatsManager. Requires the GET_USAGE_STATS app-op for other packages
 * (see UsageAccessManager) — a per-package failure is skipped, never thrown.
 */
@Single
class StorageStatsHelper(private val context: Context) {

    private val statsManager = context.getSystemService(StorageStatsManager::class.java)
    private val pm = context.packageManager
    private val user = Process.myUserHandle()

    suspend fun installSizes(packages: List<String>): Map<String, Long> =
        withContext(Dispatchers.IO) {
            val manager = statsManager ?: return@withContext emptyMap()
            // One Binder call for all ApplicationInfo instead of one per package.
            // (queryStatsForPackage below still costs one IPC each — there is no batch
            // API for it — so this only removes the cheaper per-package lookup.)
            val appInfos = runCatching {
                installedApplications().associateBy { it.packageName }
            }.getOrNull().orEmpty()

            val out = HashMap<String, Long>(packages.size)
            for (pkg in packages) {
                val size = runCatching {
                    val ai = appInfos[pkg] ?: applicationInfo(pkg)
                    val stats = manager.queryStatsForPackage(ai.storageUuid, pkg, user)
                    stats.appBytes + stats.dataBytes + stats.cacheBytes
                }.getOrNull()
                if (size != null) out[pkg] = size
            }
            out
        }

    private fun installedApplications(): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

    // Fallback for a package missing from the bulk list (e.g. installed/removed mid-scan).
    private fun applicationInfo(pkg: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }
}
