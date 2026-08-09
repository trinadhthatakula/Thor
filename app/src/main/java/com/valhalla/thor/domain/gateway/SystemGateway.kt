// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.gateway

/**
 * The Contract: This defines every privileged action Thor can perform.
 * No Android dependencies (Context, Toast, Intent) allowed here.
 */
interface SystemGateway {

    // Status Checks
    suspend fun isRootAvailable(): Boolean
    suspend fun isShizukuAvailable(): Boolean
    suspend fun isDhizukuAvailable(): Boolean

    // Core Actions
    suspend fun forceStopApp(packageName: String): Result<Unit>
    suspend fun clearAppData(packageName: String): Result<Unit>
    suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit>
    suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit>
    suspend fun setAppRestricted(packageName: String, isRestricted: Boolean): Result<Unit>
    suspend fun rebootDevice(reason: String): Result<Unit>

    // Advanced
    suspend fun uninstallApp(packageName: String): Result<Unit>
    suspend fun installApp(apkPath: String, canDowngrade: Boolean = false): Result<Unit>
    suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit>
    suspend fun grantPermission(packageName: String, permissionName: String): Result<Unit>
    suspend fun revokePermission(packageName: String, permissionName: String): Result<Unit>

    /**
     * Clears **every** app's cache on the primary volume — system and user apps alike — until the
     * volume reports [targetFreeBytes] free.
     *
     * There is deliberately no per-package `clearCache` on this interface. Clearing one app's cache
     * needs `INTERNAL_DELETE_CACHE_FILES`, which is `signature`-level: `pm grant` refuses it as "not
     * a changeable permission type", `com.android.shell` never requests it, and PMS answers the call
     * from uid 2000 by logging that it is *silently ignoring* it. Shizuku cannot delegate a
     * permission shell does not hold and Dhizuku's device-owner API has no equivalent, so both had a
     * per-package rung that could only ever report failure — or, before it was corrected, report a
     * success it had no evidence for. Root is the only mode that can clear one app's cache, and it
     * does so through `RootSystemGateway.clearCache`, off this interface, the way `copyFile` and
     * `getAppPaths` already sit off it. See `docs/discussions/` for the measurements.
     *
     * What every privileged mode *can* reach is `pm trim-caches`, which goes through
     * `PackageManagerService.freeStorage` and is gated on `CLEAR_APP_CACHE` instead. The cost is
     * that the caller no longer chooses the victim: PMS evicts by LRU across the whole volume. Hence
     * the name, and hence the confirmation the UI must show first.
     *
     * @param targetFreeBytes from `StorageStatsProvider.cacheTrimTargetBytes`, or `null` when the
     * volume's free space could not be read. **Never a round number** — `freeStorage` is an
     * escalating ladder on which app cache is only rungs 4 and 8, and an unsatisfiable target walks
     * on to prune static shared libraries and uninstall instant apps. `null` means a mode that can
     * only express this as a trim must fail; Root has a direct sweep it can fall back on.
     */
    suspend fun clearAllCaches(targetFreeBytes: Long?): Result<Unit>

    /**
     * Runs a raw shell command through this privilege mechanism and returns the
     * (exitCode, output) pair. Used by the extension ShellExecutor so extensions
     * execute with the same Root/Shizuku/Dhizuku privilege as in-app actions.
     * Returns failure only when the command could not be executed at all.
     */
    suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>>
}