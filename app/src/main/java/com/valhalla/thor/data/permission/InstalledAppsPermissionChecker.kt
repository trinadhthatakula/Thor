// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.permission

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.valhalla.thor.domain.model.DeclaredPermission
import com.valhalla.thor.domain.model.GET_INSTALLED_APPS_PERMISSION
import com.valhalla.thor.domain.model.InstalledAppsPermission
import com.valhalla.thor.domain.model.installedAppsPermissionState
import com.valhalla.thor.domain.repository.InstalledAppsPermissionGate
import org.koin.core.annotation.Single

/**
 * Asks the running device the two questions [installedAppsPermissionState] needs about
 * [GET_INSTALLED_APPS_PERMISSION], and nothing else.
 *
 * The split is the same one [com.valhalla.thor.data.repository.PermissionRepositoryImpl] makes for
 * permission groups: the binder calls live here, the rule lives in a pure function that a JVM test
 * can drive. That is what makes "a Pixel must never be prompted" assertable — `PackageManager` is
 * abstract, `:app` has no mocking library by policy, and a device that has never heard of the
 * permission is just `declared = null` in a test.
 *
 * No gateway, no shell, no reflection: this is an ordinary runtime permission requested with
 * `ActivityResultContracts.RequestPermission()`, and nothing here needs privilege.
 */
@Single(binds = [InstalledAppsPermissionGate::class])
class InstalledAppsPermissionChecker(
    private val context: Context,
    private val packageManager: PackageManager,
) : InstalledAppsPermissionGate {

    /**
     * Cached, because the ROM's permission table is fixed for the life of the OS image — whether
     * `com.android.permission.GET_INSTALLED_APPS` exists cannot change under a running process, and
     * re-asking on every scan would put a binder call on the app-list hot path for an answer that is
     * a build-time property of the device.
     */
    private val declared: DeclaredPermission? by lazy { declaredPermission() }

    /**
     * The current state, re-reading the *grant* every time.
     *
     * The grant very much does change at runtime — that is the whole point of this permission being
     * three-state. A "while in use" grant reads as granted in the foreground and stops being true the
     * moment Thor is backgrounded, so caching this would report a permission Thor no longer has for
     * exactly the scans that get truncated by not having it.
     */
    override fun state(): InstalledAppsPermission = installedAppsPermissionState(
        declared = declared,
        isGranted = context.checkSelfPermission(GET_INSTALLED_APPS_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED,
    )

    /** What this device says about the permission, or null if it does not define it. */
    @Suppress("DEPRECATION")
    private fun declaredPermission(): DeclaredPermission? {
        val info = try {
            // Throws NameNotFoundException on every AOSP build, which is the authoritative answer
            // rather than an error: T/TAF 108-2022 is a Chinese-market standard and most devices
            // Thor runs on have simply never heard of this permission. Catching Exception rather
            // than NameNotFoundException on purpose — the ROMs that *do* define it are also the ones
            // that throw other things out of the package manager.
            packageManager.getPermissionInfo(GET_INSTALLED_APPS_PERMISSION, 0)
        } catch (_: Exception) {
            return null
        }
        return DeclaredPermission(
            isDangerous = (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
                    PermissionInfo.PROTECTION_DANGEROUS,
            group = info.group
        )
    }
}
