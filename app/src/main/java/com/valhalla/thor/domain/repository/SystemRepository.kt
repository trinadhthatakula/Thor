package com.valhalla.thor.domain.repository

interface SystemRepository {

    suspend fun isRootAvailable(): Boolean
    fun isShizukuAvailable(): Boolean
    fun isDhizukuAvailable(): Boolean

    // Core Actions
    suspend fun forceStopApp(packageName: String): Result<Unit>
    suspend fun clearCache(packageName: String): Result<Unit>
    suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit>

    // Advanced
    suspend fun uninstallApp(packageName: String): Result<Unit>
    suspend fun rebootDevice(reason: String): Result<Unit>

    // Composite Actions
    suspend fun aggressiveCleanup(packageName: String): Result<Unit>
    suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit>
    suspend fun copyFileWithRoot(sourcePath: String, destinationPath: String): Result<Unit>
    suspend fun getAppPaths(packageName: String): Result<List<String>>
}