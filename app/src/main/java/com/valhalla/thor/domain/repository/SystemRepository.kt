package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.AppInfo

interface SystemRepository {
    // Privilege Checks
    val isRootAvailable: Boolean
    fun isShizukuAvailable(): Boolean

    // App Actions
    suspend fun forceStopApp(packageName: String): Result<Unit>
    suspend fun clearCache(packageName: String): Result<Unit>
    suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit>
    suspend fun uninstallApp(packageName: String): Result<Unit>

    // Advanced
    suspend fun rebootDevice(reason: String): Result<Unit>

    // Smart Action: Tries to clean up as much as possible
    suspend fun aggressiveCleanup(packageName: String): Result<Unit>

    suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit>

    suspend fun copyFileWithRoot(sourcePath: String, destinationPath: String): Result<Unit>

    suspend fun getAppPaths(packageName: String): Result<List<String>>

}