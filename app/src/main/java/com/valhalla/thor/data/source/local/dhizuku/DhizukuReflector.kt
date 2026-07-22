// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.dhizuku

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.util.Logger
import org.koin.core.annotation.Single

@Single
class DhizukuReflector(
    private val context: Context
) {

    fun forceStop(packageName: String): Boolean {
        return try {
            DhizukuHelper.forceStopApp(context, packageName)
        } catch (e: Exception) {
            Logger.e("DhizukuReflector", "forceStop failed", e)
            false
        }
    }

    fun clearCache(packageName: String): Boolean {
        return try {
            DhizukuHelper.clearCache(packageName)
        } catch (e: Exception) {
            Logger.e("DhizukuReflector", "clearCache failed", e)
            false
        }
    }

    fun clearData(packageName: String): Boolean {
        return try {
            DhizukuHelper.clearAppData(packageName)
        } catch (e: Exception) {
            Logger.e("DhizukuReflector", "clearData failed", e)
            false
        }
    }

    fun setAppEnabled(packageName: String, enabled: Boolean): Boolean {
        return try {
            DhizukuHelper.setAppDisabled(context, packageName, !enabled)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("DhizukuReflector", "setAppEnabled failed", e)
            false
        }
    }

    fun uninstallApp(packageName: String): Boolean {
        return try {
            DhizukuHelper.uninstallApp(packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun reinstallExistingApp(packageName: String): Boolean {
        return try {
            DhizukuHelper.reinstallApp(packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun getApplicationInfoOrNull(packageName: String): ApplicationInfo? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
                )
            } else {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isAppDisabled(packageName: String): Boolean {
        val appInfo = getApplicationInfoOrNull(packageName) ?: return false
        return !appInfo.enabled
    }

    fun isAppInstalled(packageName: String): Boolean {
        val appInfo = getApplicationInfoOrNull(packageName) ?: return false
        return (appInfo.flags and ApplicationInfo.FLAG_INSTALLED) != 0
    }

    fun isSystemApp(packageName: String): Boolean {
        val appInfo = getApplicationInfoOrNull(packageName) ?: return false
        return (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    fun setAppSuspended(packageName: String, suspended: Boolean): Boolean {
        return try {
            DhizukuHelper.setAppSuspended(context, packageName, suspended)
        } catch (e: Exception) {
            Logger.e("DhizukuReflector", "setAppSuspended failed", e)
            false
        }
    }

    fun setAppRestricted(packageName: String, restricted: Boolean): Boolean {
        return try {
            DhizukuHelper.setAppRestricted(context, packageName, restricted)
        } catch (e: Exception) {
            Logger.e("DhizukuReflector", "setAppRestricted failed", e)
            false
        }
    }
}
