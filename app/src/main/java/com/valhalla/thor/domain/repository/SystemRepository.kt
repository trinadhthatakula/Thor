// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.gateway.ComponentEnabledState
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PrivilegeExecutionContext

interface SystemRepository {

    suspend fun isRootAvailable(): Boolean
    suspend fun isShizukuAvailable(): Boolean
    suspend fun isDhizukuAvailable(): Boolean

    // Core Actions
    suspend fun forceStopApp(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<Unit>

    /**
     * Clears one app's cache. **Root only** — see [com.valhalla.thor.domain.gateway.SystemGateway]
     * `clearAllCaches` for why Shizuku and Dhizuku cannot do this and must not pretend to.
     *
     * The `Long?` is bytes freed, measured either side of the clear through `StorageStatsProvider`,
     * and it is nullable because the measurement needs usage access that the *clear* does not: a
     * success carrying `null` means the cache is gone and Thor cannot say how big it was. A caller
     * must not render that as "0 B freed".
     */
    suspend fun clearCache(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<Long?>

    /**
     * Clears **every** app's cache — system and user apps alike — under any privilege mode that can.
     * The `Long?` is bytes freed, on the same terms as [clearCache].
     */
    suspend fun clearAllCaches(): Result<Long?>

    suspend fun clearAppData(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<Unit>

    suspend fun setAppDisabled(
        packageName: String,
        isDisabled: Boolean,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<Unit>

    suspend fun setAppSuspended(
        packageName: String,
        isSuspended: Boolean,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<Unit>

    suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<Unit>

    // Advanced
    suspend fun uninstallApp(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<Unit>

    suspend fun rebootDevice(reason: String): Result<Unit>

    // Composite Actions
    // `aggressiveCleanup(packageName)` was declared here and is deliberately gone; see the note at
    // its old implementation site in `SystemRepositoryImpl`. Do not re-add it without a caller.
    suspend fun reinstallAppWithGoogle(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<Unit>

    suspend fun copyFileWithRoot(
        sourcePath: String,
        destinationPath: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<Unit>

    suspend fun getAppPaths(
        packageName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<List<String>>

    suspend fun grantPermission(
        packageName: String,
        permissionName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<Unit>

    suspend fun revokePermission(
        packageName: String,
        permissionName: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<Unit>

    // Per-component control
    //
    // Routed through the active gateway like everything else, but with one difference worth stating
    // once: the fallback chain does not help here. Root, and a Shizuku that was itself started as
    // root, are the only transports the platform accepts these from — see the block comment on
    // `SystemGateway`. A device with Shizuku at the shell uid gets a refusal that names the reason,
    // and the UI is expected to have asked `componentCapability` first so that the refusal is a
    // backstop rather than the normal path.

    /**
     * Sets one component's enabled state for the Android user Thor is running in.
     *
     * @param state `DEFAULT` removes the override and lets `android:enabled` decide again; it is not
     * a synonym for `ENABLED`, and for a component that ships disabled it switches it back off.
     */
    suspend fun setComponentEnabled(
        packageName: String,
        className: String,
        state: ComponentEnabledState,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit>

    /**
     * Launches an activity that an ordinary `startActivity` cannot reach — unexported, or guarded by
     * a permission Thor does not hold.
     *
     * Callers must not route an exported, unguarded activity here. That one needs no privilege at
     * all, and sending it down this path makes a launch that works on every device fail on most of
     * them.
     */
    suspend fun forceLaunchActivity(
        packageName: String,
        className: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<Unit>

    /**
     * Stops one running service. Transient: nothing stops the app starting it again immediately.
     */
    suspend fun stopService(
        packageName: String,
        className: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<Unit>

    /**
     * Raw shell execution via the active privilege gateway (used by extensions).
     *
     * **A multi-line [command] that can `exit` must wrap itself in `( … )`.** This is the seam every
     * command builder in the app crosses, and it is the one place the rule can be stated once.
     *
     * The transports are not alike and the difference is invisible from the call site. Shizuku and
     * Dhizuku spawn a fresh `sh` per command, so a top-level `exit` only ends a process that was
     * about to end anyway. Root does not: `RootSystemGateway` writes into Odin's single long-lived
     * `su` session, which every privileged command in the app shares. A top-level `exit` there kills
     * that session mid-script — libsu never appends its end marker, so it cannot read the real exit
     * code and falls back to 1, and the *next*, unrelated privileged command fails with "Root shell
     * unavailable".
     *
     * Both halves of that are silent. The caller sees a plausible non-zero exit code, and the damage
     * lands on whatever runs next. `ObbProbeParser.obbProbeCommand` shipped with six bare `exit 0`s
     * and presented as "OBB detection is broken for most apps"; the fix was one pair of parentheses.
     * `RootSystemGateway.installViaSession` and `integrityGuardedInstall` are the wrap to copy.
     *
     * @return the exit code and combined output, or a failure when no privileged shell is available.
     */
    suspend fun executeShellCommand(
        command: String,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext()
    ): Result<Pair<Int, String?>>

    /**
     * Look at `Android/obb/<packageName>` through the active privilege gateway.
     *
     * Not root-only: this goes through [executeShellCommand], which `SystemRepositoryImpl` routes
     * via `runGatewayAction`, so root and Shizuku both answer it. The Dhizuku device-owner process
     * cannot see another package's external directories and gets [ObbProbe.Undetermined].
     *
     * Never throws. Every failure — bad package name, gateway error, truncated reply — is
     * `Undetermined`, which callers must not collapse into `None`.
     */
    suspend fun probeObb(packageName: String): ObbProbe
}