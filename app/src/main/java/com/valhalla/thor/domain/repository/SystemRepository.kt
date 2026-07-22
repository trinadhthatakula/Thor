// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

interface SystemRepository {

    suspend fun isRootAvailable(): Boolean
    suspend fun isShizukuAvailable(): Boolean
    suspend fun isDhizukuAvailable(): Boolean

    // Core Actions
    suspend fun forceStopApp(packageName: String): Result<Unit>
    suspend fun clearCache(packageName: String): Result<Unit>
    suspend fun clearAppData(packageName: String): Result<Unit>
    suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit>
    suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit>
    suspend fun setAppRestricted(packageName: String, isRestricted: Boolean): Result<Unit>

    // Advanced
    suspend fun uninstallApp(packageName: String): Result<Unit>
    suspend fun rebootDevice(reason: String): Result<Unit>

    // Composite Actions
    suspend fun aggressiveCleanup(packageName: String): Result<Unit>
    suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit>
    suspend fun copyFileWithRoot(sourcePath: String, destinationPath: String): Result<Unit>
    suspend fun getAppPaths(packageName: String): Result<List<String>>
    suspend fun grantPermission(packageName: String, permissionName: String): Result<Unit>
    suspend fun revokePermission(packageName: String, permissionName: String): Result<Unit>

    // Raw shell execution via the active privilege gateway (used by extensions).
    suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>>
}