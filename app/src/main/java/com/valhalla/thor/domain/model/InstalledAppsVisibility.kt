// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * The package-visibility permission the Chinese association standard T/TAF 108-2022 added, honoured
 * by MIUI/HyperOS, ColorOS, OriginOS, MagicOS and xiaomi.eu.
 *
 * It is **in addition to** `QUERY_ALL_PACKAGES`, never a replacement: those ROMs still require the
 * AOSP one, and every other Android build requires it alone. What makes this permission different is
 * that it is a *runtime* permission with three states — allow / while-in-use / deny — so the answer
 * to "which packages exist" can change while the process is alive, which is not a thing AOSP package
 * visibility can do.
 *
 * Non-AOSP means a Pixel has never heard of it; see [installedAppsPermissionState] for why that has
 * to be detected rather than assumed.
 */
const val GET_INSTALLED_APPS_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

/**
 * The platform package.
 *
 * Google's automatic-visibility rules make this one of the handful of packages every app can see on
 * every Android version regardless of filtering, alongside the caller itself and its installer. That
 * is exactly what makes it usable as a canary: a scan that cannot see `android` is not a device with
 * few apps installed, it is a scan that was not allowed to answer.
 */
const val PLATFORM_PACKAGE = "android"

/**
 * How many consecutive suspect scans to tolerate before believing the shrinkage is real.
 *
 * The guard has to be able to *stop* guarding. A user who factory-resets their launcher's worth of
 * apps, or restores a much smaller backup, produces a genuinely tiny scan, and a cache that can only
 * grow would keep showing them rows for apps that are gone. Two independent scans agreeing, with no
 * permission gate to explain them, is the point where "the OS is lying to us" stops being the better
 * explanation.
 */
const val SUSPECT_SCAN_TOLERANCE = 2

/**
 * What this device says about [GET_INSTALLED_APPS_PERMISSION].
 *
 * [Unsupported] is deliberately not the same thing as [Denied]. It is the state of every AOSP device
 * Thor runs on, it can never change, and nothing may ever be shown to the user about it — the banner
 * and the prompt are both gated on [Denied] alone.
 */
sealed interface InstalledAppsPermission {

    /**
     * The ROM does not define the permission, or defines it with a protection level below
     * `dangerous`.
     *
     * Either way it can never be requested at runtime: `requestPermissions` for a permission the
     * platform does not know is a silent no-op, and a non-dangerous permission is granted (or not) at
     * install time with no dialog to show. Treating this as "denied" is the Pixel false-nag bug —
     * `shouldShowRequestPermissionRationale` also returns a hard `false` for an unknown permission,
     * so the usual "denied and no rationale means permanently denied, send them to Settings" recipe
     * would nag every AOSP user forever about a permission their device has never heard of.
     */
    data object Unsupported : InstalledAppsPermission

    /** Defined, dangerous, and not currently held — the one state worth prompting about. */
    data object Denied : InstalledAppsPermission

    /**
     * Held right now. Note that "while in use" reports granted too, and stops being true the moment
     * Thor is backgrounded, which is why the grant is re-read on every scan rather than cached.
     */
    data object Granted : InstalledAppsPermission
}

/**
 * Fold the two facts a caller can gather about the permission into the state the UI and the prune
 * guard both act on.
 *
 * [declared] `== null` is `getPermissionInfo` having thrown `NameNotFoundException`: the
 * authoritative "this OS has never heard of it", and the only honest way to tell a Pixel from a
 * HyperOS device. Sniffing `Build.MANUFACTURER` would be a guess about a permission table that OEMs
 * change between builds; asking the package manager is not.
 *
 * A permission that exists but is not `dangerous` is [InstalledAppsPermission.Unsupported] for the
 * same reason — it cannot be requested at runtime, so offering the user a button that does nothing
 * is worse than staying quiet. This mirrors the existing rule in [runtimeGroupFor]: the device is
 * asked first, and "declared" alone is never enough.
 *
 * Pure so the Pixel case is assertable on the JVM — `PackageManager` is abstract and `:app` has no
 * mocking library, so the binder calls stay in the checker and the decision lives here.
 */
fun installedAppsPermissionState(
    declared: DeclaredPermission?,
    isGranted: Boolean,
): InstalledAppsPermission = when {
    declared == null -> InstalledAppsPermission.Unsupported
    !declared.isDangerous -> InstalledAppsPermission.Unsupported
    isGranted -> InstalledAppsPermission.Granted
    else -> InstalledAppsPermission.Denied
}

/** Why a scan was not believed, in the order [scanVerdict] tests for them. */
enum class RetainReason {
    /** The scan came back with nothing at all. */
    EmptyScan,

    /** The scan is missing [PLATFORM_PACKAGE], which no honest scan ever is. */
    PlatformPackageMissing,

    /** The scan lost more than half the cached rows in one go. */
    Collapsed,
}

/** Whether a package scan is trustworthy enough to prune the cache against. */
sealed interface ScanVerdict {

    /** Believe the scan: anything the cache holds and the scan does not is genuinely uninstalled. */
    data object Accept : ScanVerdict

    /** Keep the cached rows this scan did not see, and say why. */
    data class Retain(val reason: RetainReason) : ScanVerdict
}

/**
 * Whether [scannedPackageNames] may be used to delete cached rows.
 *
 * **This is the fix for the actual defect.** With `GET_INSTALLED_APPS` granted "while in use",
 * backgrounding Thor collapses `getInstalledPackages()` to a near-empty list. The scan loop then
 * reads every absent package as uninstalled, `syncCache` deletes the Room rows *and* their cached
 * icon PNGs, and the user comes back to a list containing only Thor — damage that outlives the
 * permission blip and survives until a full rescan. Requesting the permission cures the cause; this
 * function is what stops a scan taken through a closed gate from being destructive in the meantime.
 *
 * **Why identity and not a count.** Field reports include a truncated-but-large scan — 68 packages
 * out of 175 — so a "lost more than half" threshold alone lets the worst real case straight through,
 * while a blunt "never shrink" rule would break genuine uninstalls forever. Automatic-visibility
 * guarantees mean the platform package is visible to every app on every version no matter what the
 * filtering says, so its *absence* is proof about the scan rather than evidence about the device.
 *
 * The rules run in a fixed order, and rule 3 landing before rule 4 is load-bearing:
 * 1. An empty cache accepts anything. There is nothing to protect, and refusing here would strand a
 *    fresh install on an empty list forever, because the cache could never take its first rows.
 * 2. Work out whether the scan is suspect at all, first match wins; if it is not, accept.
 * 3. [InstalledAppsPermission.Denied] retains **unconditionally, with no tolerance**. We have a
 *    named cause for the shrinkage, so no number of repeats makes it evidence of an uninstall —
 *    repeating a question through a closed gate just gets the same wrong answer again. Letting the
 *    tolerance fire here is precisely the bug coming back.
 * 4. Only with no permission gate to explain it does [SUSPECT_SCAN_TOLERANCE] apply, and then the
 *    shrinkage is believed. Without this the cache could never shrink again and rows for genuinely
 *    removed apps would persist forever.
 * 5. Otherwise retain, and let the caller count this scan towards the tolerance.
 *
 * [consecutiveSuspectScans] is the count of suspect scans *before* this one, which the caller keeps
 * for the life of one collection and resets on the first accepted scan. Deliberately not persisted:
 * a retained cache self-heals on the next good scan without an app restart, so there is no degraded
 * state to remember.
 */
fun scanVerdict(
    scannedPackageNames: Set<String>,
    cachedCount: Int,
    consecutiveSuspectScans: Int,
    permission: InstalledAppsPermission,
): ScanVerdict {
    if (cachedCount == 0) return ScanVerdict.Accept

    val reason = when {
        scannedPackageNames.isEmpty() -> RetainReason.EmptyScan
        PLATFORM_PACKAGE !in scannedPackageNames -> RetainReason.PlatformPackageMissing
        scannedPackageNames.size * 2 < cachedCount -> RetainReason.Collapsed
        else -> return ScanVerdict.Accept
    }

    // Before the tolerance check, never after it. See rule 3 above.
    if (permission == InstalledAppsPermission.Denied) return ScanVerdict.Retain(reason)

    if (consecutiveSuspectScans >= SUSPECT_SCAN_TOLERANCE) return ScanVerdict.Accept

    return ScanVerdict.Retain(reason)
}
