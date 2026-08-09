// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

interface SystemRepository {

    suspend fun isRootAvailable(): Boolean
    suspend fun isShizukuAvailable(): Boolean
    suspend fun isDhizukuAvailable(): Boolean

    // Core Actions
    suspend fun forceStopApp(packageName: String): Result<Unit>

    /**
     * Clears one app's cache. **Root only** — see [com.valhalla.thor.domain.gateway.SystemGateway]
     * `clearAllCaches` for why Shizuku and Dhizuku cannot do this and must not pretend to.
     *
     * The `Long?` is bytes freed, measured either side of the clear through `StorageStatsProvider`,
     * and it is nullable because the measurement needs usage access that the *clear* does not: a
     * success carrying `null` means the cache is gone and Thor cannot say how big it was. A caller
     * must not render that as "0 B freed".
     */
    suspend fun clearCache(packageName: String): Result<Long?>

    /**
     * Clears **every** app's cache — system and user apps alike — under any privilege mode that can.
     * The `Long?` is bytes freed, on the same terms as [clearCache].
     */
    suspend fun clearAllCaches(): Result<Long?>

    suspend fun clearAppData(packageName: String): Result<Unit>
    suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit>
    suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit>
    suspend fun setAppRestricted(packageName: String, isRestricted: Boolean): Result<Unit>

    // Advanced
    suspend fun uninstallApp(packageName: String): Result<Unit>
    suspend fun rebootDevice(reason: String): Result<Unit>

    // Composite Actions
    // `aggressiveCleanup(packageName)` was declared here and is deliberately gone; see the note at
    // its old implementation site in `SystemRepositoryImpl`. Do not re-add it without a caller.
    suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit>
    suspend fun copyFileWithRoot(sourcePath: String, destinationPath: String): Result<Unit>
    suspend fun getAppPaths(packageName: String): Result<List<String>>
    suspend fun grantPermission(packageName: String, permissionName: String): Result<Unit>
    suspend fun revokePermission(packageName: String, permissionName: String): Result<Unit>

    // Raw shell execution via the active privilege gateway (used by extensions).
    suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>>
}