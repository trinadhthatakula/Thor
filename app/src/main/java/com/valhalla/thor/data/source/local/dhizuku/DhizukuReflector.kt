// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.dhizuku

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.shizuku.DisableOutcome
import com.valhalla.thor.data.source.local.shizuku.SystemAppRemovalOutcome
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

    fun setAppEnabled(packageName: String, enabled: Boolean): Boolean =
        setAppEnabledDetailed(packageName, enabled).succeeded

    /**
     * [setAppEnabled], plus whether the platform *refused* rather than merely failed.
     *
     * A thrown exception is reported as `refusedByPolicy = false`: everything that reaches this
     * catch got past the per-rung handling inside [DhizukuHelper.setAppDisabledDetailed], so it is
     * a Dhizuku-binder or reflection-plumbing problem rather than `PackageManagerService` saying
     * no. Guessing "refused" here would let a dead binder authorise removing an app for the user.
     */
    fun setAppEnabledDetailed(packageName: String, enabled: Boolean): DisableOutcome {
        return try {
            DhizukuHelper.setAppDisabledDetailed(context, packageName, !enabled)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("DhizukuReflector", "setAppEnabled failed", e)
            DisableOutcome(succeeded = false, refusedByPolicy = false)
        }
    }

    /** The user-facing uninstall: removes the app for this user **and its data**. No `-k`. */
    fun uninstallApp(packageName: String): SystemAppRemovalOutcome {
        return try {
            DhizukuHelper.uninstallApp(packageName)
        } catch (e: Exception) {
            Logger.e("DhizukuReflector", "uninstallApp failed", e)
            SystemAppRemovalOutcome(succeeded = false, exitCode = -1, platformMessage = e.message)
        }
    }

    /**
     * The last rung of the system-app freeze: remove for this user, **keep the data** (`-k`).
     *
     * Does not collapse to a `Boolean`, unlike most of this class. Why it failed is the only thing
     * the caller can turn into a sentence worth showing — on Android 17 the answer is
     * `Failure [only root can delete system app for a particular user]`, which names a cause the
     * generic "reflection is blocked or shell lacks permissions" actively contradicts.
     */
    fun freezeSystemAppForUser(packageName: String): SystemAppRemovalOutcome {
        return try {
            DhizukuHelper.freezeSystemAppForUser(packageName)
        } catch (e: Exception) {
            Logger.e("DhizukuReflector", "freezeSystemAppForUser failed", e)
            SystemAppRemovalOutcome(succeeded = false, exitCode = -1, platformMessage = e.message)
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

    // isAppDisabled() used to sit here, reading `enabled` and nothing else. It had no callers, and
    // it was wrong in the direction that matters: a system app frozen with `pm uninstall --user N`
    // keeps `enabled == true`, so it reported such an app as *not* disabled. The freeze test that
    // survives is the conjunction — see Packages.isAppDisabled and AppFreezeStateReader — and it is
    // reached through [isAppInstalled] plus `enabled` at the call sites that need it.

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
