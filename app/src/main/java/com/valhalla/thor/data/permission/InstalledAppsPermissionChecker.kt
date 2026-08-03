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
     * Cached once resolved, because the ROM's permission table is fixed for the life of the OS
     * image — whether `com.android.permission.GET_INSTALLED_APPS` exists cannot change under a
     * running process, and re-asking on every scan would put a binder call on the app-list hot path
     * for an answer that is a build-time property of the device.
     *
     * Deliberately *not* `by lazy`, because only a **definitive** answer may be cached. A caught
     * `NameNotFoundException` is definitive — the device does not define the permission — and so is
     * a successful lookup. Anything else is a failed question rather than an answer, and memoizing
     * it would be self-defeating: as [declaredPermission] notes, the ROMs that define this
     * permission are the same ones that throw unexpected things out of the package manager, so one
     * unlucky probe would pin [InstalledAppsPermission.Unsupported] for the rest of the process and
     * silently disable the banner, the prompt, and the unconditional-retain path in `scanVerdict` —
     * on exactly the devices this exists for. A transient failure is left uncached and re-asked on
     * the next [state] call instead.
     *
     * `@Volatile` for publication, not mutual exclusion: two threads racing the first probe may both
     * make the binder call, which is wasteful for one round trip and otherwise harmless, since the
     * answer they write is the same.
     */
    @Volatile
    private var resolved: Resolution? = null

    /** A definitive answer about this device. [permission] is null when the device does not define it. */
    private class Resolution(val permission: DeclaredPermission?)

    /**
     * The current state, re-reading the *grant* every time.
     *
     * The grant very much does change at runtime — that is the whole point of this permission being
     * three-state. A "while in use" grant reads as granted in the foreground and stops being true the
     * moment Thor is backgrounded, so caching this would report a permission Thor no longer has for
     * exactly the scans that get truncated by not having it.
     */
    override fun state(): InstalledAppsPermission = installedAppsPermissionState(
        declared = declaredPermission(),
        isGranted = context.checkSelfPermission(GET_INSTALLED_APPS_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED,
    )

    /**
     * What this device says about the permission, or null if it does not define it — or if asking
     * failed, in which case the next call asks again rather than trusting this one.
     */
    @Suppress("DEPRECATION")
    private fun declaredPermission(): DeclaredPermission? {
        resolved?.let { return it.permission }

        val info = try {
            packageManager.getPermissionInfo(GET_INSTALLED_APPS_PERMISSION, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            // The authoritative answer rather than an error, and the one every AOSP build gives:
            // T/TAF 108-2022 is a Chinese-market standard and most devices Thor runs on have simply
            // never heard of this permission. Definitive, so it is cached.
            resolved = Resolution(null)
            return null
        } catch (_: Exception) {
            // Something other than "no such permission" — a package-manager hiccup, not a verdict
            // about the device. The ROMs that define this permission are also the ones most likely
            // to throw here, so this answer is thrown away rather than cached: returning
            // Unsupported once is a banner that does not appear, but caching it is the fix
            // disabling itself permanently.
            return null
        }

        val permission = DeclaredPermission(
            isDangerous = (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
                    PermissionInfo.PROTECTION_DANGEROUS,
            group = info.group
        )
        resolved = Resolution(permission)
        return permission
    }
}
