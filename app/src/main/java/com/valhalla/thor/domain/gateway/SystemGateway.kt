package com.valhalla.thor.domain.gateway

import kotlinx.coroutines.flow.Flow

/**
 * The Contract: This defines every privileged action Thor can perform.
 * No Android dependencies (Context, Toast, Intent) allowed here.
 */
interface SystemGateway {

    // Status Checks
    val isRootAvailable: Boolean
    fun isShizukuAvailable(): Boolean

    // Core Actions
    suspend fun forceStopApp(packageName: String): Result<Unit>
    suspend fun clearCache(packageName: String): Result<Unit>
    suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit>
    suspend fun rebootDevice(reason: String): Result<Unit>

    // Advanced
    suspend fun uninstallApp(packageName: String): Result<Unit>
    suspend fun installApp(apkPath: String): Result<Unit>

    // Metrics
    suspend fun getAppCacheSize(packageName: String): Long
}