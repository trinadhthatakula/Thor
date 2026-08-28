// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.gateway

import com.valhalla.thor.domain.model.PrivilegeExecutionContext

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
    suspend fun forceStopApp(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit>

    suspend fun clearAppData(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit>

    suspend fun setAppDisabled(
        packageName: String,
        isDisabled: Boolean,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit>

    suspend fun setAppSuspended(
        packageName: String,
        isSuspended: Boolean,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit>

    suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit>

    suspend fun rebootDevice(reason: String): Result<Unit>

    // Advanced
    suspend fun uninstallApp(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit>

    /**
     * @param grantAllPermissions the answer for THIS install to "grant every runtime permission
     *   the package declares, without asking" (`pm install-create -g`, GH#445). `null` — the
     *   default — means "no answer for this install, use the saved setting", which is what every
     *   caller that has no user in front of it wants. Deliberately not defaulting to `false`: that
     *   would silently override a user who had turned the setting on.
     */
    suspend fun installApp(
        apkPath: String,
        canDowngrade: Boolean = false,
        grantAllPermissions: Boolean? = null,
    ): Result<Unit>

    suspend fun reinstallAppWithGoogle(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit>

    suspend fun grantPermission(
        packageName: String,
        permissionName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit>

    suspend fun revokePermission(
        packageName: String,
        permissionName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit>

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

    // Per-component control
    //
    // All three verbs below need the privileged transport to be executing at **uid 0**, and all
    // three are therefore implemented for real only by Root and by a Shizuku that was itself started
    // as root. This is not an accident of Thor's plumbing:
    //
    //  - `PackageManagerService.setEnabledSetting` lets `Process.SHELL_UID` through a carve-out that
    //    requires `className == null`; with a class name it throws
    //    `SecurityException("Shell cannot change component state for …")`. Reaching
    //    `IPackageManager.setComponentEnabledSetting` by reflection lands on the same check with the
    //    same calling uid, so there is no second rung to fall back to — which is the one thing that
    //    makes these different from every other verb on this interface.
    //  - `ActivityManager.canAccessUnexportedComponents` waives the export and permission checks for
    //    `ROOT_UID` and `SYSTEM_UID` only. `START_ANY_ACTIVITY` is `signature` and is not declared by
    //    `packages/Shell` in any release from 9 to 16.
    //  - Dhizuku's `DevicePolicyManager` has no component-enabled API at all.
    //
    // A mode that cannot do one of these must fail with a message naming the reason. Reporting
    // success it has no evidence for is the failure mode `clearAllCaches` above was written to
    // document.

    /**
     * Sets one component's enabled state for [userId].
     *
     * @param state the `pm` sub-command to use — `enable`, `disable`, or `default-state`. `default`
     * is not a synonym for `enable`: it removes the override, which puts a component that ships
     * disabled back to disabled. See `ComponentCommands.enableStateFor`.
     */
    suspend fun setComponentEnabled(
        packageName: String,
        className: String,
        state: ComponentEnabledState,
        userId: Int,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit>

    /**
     * Launches one activity for [userId], ignoring whether it is exported or permission-guarded.
     *
     * Only for the components Thor cannot launch with an ordinary `startActivity` — an exported,
     * unguarded activity should never reach here, because a plain `Intent` needs no privilege, shows
     * the app's own task animation, and cannot fail on a device with no root.
     */
    suspend fun forceLaunchActivity(
        packageName: String,
        className: String,
        userId: Int,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit>

    /**
     * Stops one running service for [userId].
     *
     * Transient by construction: nothing stops the app from starting it again a moment later. It is
     * the "stop now" half of the pair, and the persistent half is [setComponentEnabled].
     */
    suspend fun stopService(
        packageName: String,
        className: String,
        userId: Int,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit>

    /**
     * Runs a raw shell command through this privilege mechanism and returns the
     * (exitCode, output) pair. Used by the extension ShellExecutor so extensions
     * execute with the same Root/Shizuku/Dhizuku privilege as in-app actions.
     * Returns failure only when the command could not be executed at all.
     */
    suspend fun executeShellCommand(
        command: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Pair<Int, String?>>
}

/**
 * The three states a component can be put in, as this interface names them.
 *
 * A domain mirror of `ComponentCommands.ComponentState`, which is `internal` to the data layer and
 * carries the `pm` verb. The duplication is the price of the rule in this file's header: the gateway
 * contract has no Android *and* no data-layer dependencies, and a shell verb string is a data-layer
 * detail.
 */
enum class ComponentEnabledState { ENABLED, DISABLED, DEFAULT }