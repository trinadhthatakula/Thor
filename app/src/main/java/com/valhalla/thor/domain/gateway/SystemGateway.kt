package com.valhalla.thor.domain.gateway

/**
 * The Contract: This defines every privileged action Thor can perform.
 * No Android dependencies (Context, Toast, Intent) allowed here.
 */
interface SystemGateway {

    // Status Checks
    suspend fun isRootAvailable(): Boolean
    fun isShizukuAvailable(): Boolean
    fun isDhizukuAvailable(): Boolean

    // Core Actions
    suspend fun forceStopApp(packageName: String): Result<Unit>
    suspend fun clearCache(packageName: String): Result<Unit>
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

    // Metrics
    suspend fun getAppCacheSize(packageName: String): Long
}