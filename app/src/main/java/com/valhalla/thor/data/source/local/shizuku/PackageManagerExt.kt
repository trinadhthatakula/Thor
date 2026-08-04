// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.shizuku

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Build

/**
 * [PackageInfo] for [packageName] — including the records whose `PackageUserState.installed` bit is
 * false for this user.
 *
 * The match flags are the load-bearing part, and the reason they are wide is the one caller:
 * `ShizukuReflector.uninstallApp`'s reflection rung, which exists to cover for the shell rung above
 * it. Those two rungs used to share a blind spot. `PackageManagerShellCommand.runUninstall`, once a
 * user is named, first does
 * `getPackageInfo(pkg, MATCH_STATIC_SHARED_AND_SDK_LIBRARIES, userId)` and prints
 * `Failure [not installed for N]` with exit 1 when that returns null — flags that carry neither
 * `MATCH_UNINSTALLED_PACKAGES` nor `MATCH_ARCHIVED_PACKAGES`. With `GET_META_DATA` alone this
 * lookup answered null on exactly the same condition, so the fallback returned false without ever
 * reaching `PackageInstaller.uninstall`: one condition, both rungs, no removal and no explanation.
 *
 * Thor puts an uninstall button in front of precisely those packages, because its app sweep queries
 * with `MATCH_UNINSTALLED_PACKAGES`. Two ways in:
 * - a Play-Store-**archived** app on API 35+ — `PackageArchiver` removes the APK with
 *   `DELETE_KEEP_DATA | DELETE_ARCHIVE`, which clears the per-user installed bit while the launcher
 *   entry stays on screen;
 * - any app another tool removed for this user with `pm uninstall -k`, which is the same state
 *   Thor's own system-app freeze produces via `freezeSystemAppForUser`.
 *
 * Both flags are needed and neither substitutes for the other. `MATCH_UNINSTALLED_PACKAGES` (folded
 * into `MATCH_KNOWN_PACKAGES`, which is what the availability check actually tests) covers the
 * second case, where the parsed package still exists and only the user state says otherwise. It
 * cannot cover the first: an archived package has no `AndroidPackage` left to generate info from, so
 * only the `MATCH_ARCHIVED_PACKAGES` branch in `Computer.getPackageInfoInternal` can answer for it.
 *
 * `MATCH_ARCHIVED_PACKAGES` is a `long` (`1L << 32`), not an `int`, so it does not fit the
 * `getPackageInfo(String, Int)` overload at all — it can only ride the `PackageInfoFlags` path,
 * which itself starts at API 33. That is why the two version guards below are separate conditions
 * rather than one: 33 is where the wide-flag overload exists, 35 is where the constant does.
 *
 * Widening this changes nothing else — the uninstall fallback is its only call site — and it does
 * not weaken the caller: `PackageInstaller.uninstall` carries no "installed for this user"
 * precondition of its own, so a non-null answer here is enough for the removal to go through for
 * the session's user. Callers that need "is this installed for me?" must read
 * `ApplicationInfo.FLAG_INSTALLED` (as `ShizukuReflector.isAppUninstalled` does) rather than read it
 * out of a null returned by this function.
 */
fun PackageManager.getInfoForPackage(
    packageName: String,
): PackageInfo? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            var flags = PackageManager.GET_META_DATA.toLong() or
                PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                flags = flags or PackageManager.MATCH_ARCHIVED_PACKAGES
            }
            this.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(flags)
            )
        } else {
            // No archived packages to miss here: archiving is an API 35 feature, and every device
            // on this branch is below API 33.
            this.getPackageInfo(
                packageName,
                PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES
            )
        }
    } catch (e: NameNotFoundException) {
        null
    }
}
