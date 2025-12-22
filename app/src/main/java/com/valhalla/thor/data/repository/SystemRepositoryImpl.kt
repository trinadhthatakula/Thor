package com.valhalla.thor.data.repository

import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.gateway.ShizukuSystemGateway
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.domain.repository.SystemRepository

class SystemRepositoryImpl(
    private val rootGateway: RootSystemGateway,
    private val shizukuGateway: ShizukuSystemGateway,
) : SystemRepository {

    // Now a suspend function to match the Interface and Gateway
    override suspend fun isRootAvailable(): Boolean {
        return rootGateway.isRootAvailable()
    }

    override fun isShizukuAvailable(): Boolean = shizukuGateway.isShizukuAvailable()

    // Dynamic Resolution Strategy: Prefer Root -> Fallback to Shizuku -> Fail
    // Must be suspend because checking root is suspend
    private suspend fun getActiveGateway(): SystemGateway {
        return when {
            rootGateway.isRootAvailable() -> rootGateway
            shizukuGateway.isShizukuAvailable() -> shizukuGateway
            else -> throw IllegalStateException("No privileged gateway available (Root or Shizuku required)")
        }
    }

    override suspend fun forceStopApp(packageName: String): Result<Unit> =
        getActiveGateway().forceStopApp(packageName)

    override suspend fun clearCache(packageName: String): Result<Unit> =
        getActiveGateway().clearCache(packageName)

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> =
        getActiveGateway().setAppDisabled(packageName, isDisabled)

    override suspend fun uninstallApp(packageName: String): Result<Unit> =
        getActiveGateway().uninstallApp(packageName)

    override suspend fun rebootDevice(reason: String): Result<Unit> {
        // Shizuku cannot reboot, so strictly check Root (suspend)
        return if (rootGateway.isRootAvailable()) {
            rootGateway.rebootDevice(reason)
        } else {
            Result.failure(Exception("Reboot requires Root access"))
        }
    }

    override suspend fun aggressiveCleanup(packageName: String): Result<Unit> {
        return try {
            val gateway = getActiveGateway()
            // Try to force stop first
            gateway.forceStopApp(packageName)
            // Then clear cache
            gateway.clearCache(packageName)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> {
        return if (rootGateway.isRootAvailable()) {
            rootGateway.reinstallAppWithGoogle(packageName)
        } else {
            // If you want to try with Shizuku, you'd need to implement similar logic in ShizukuGateway.
            Result.failure(Exception("Root access is required for Google Reinstall hack"))
        }
    }

    override suspend fun copyFileWithRoot(
        sourcePath: String,
        destinationPath: String
    ): Result<Unit> {
        return if (rootGateway.isRootAvailable()) {
            try {
                rootGateway.copyFile(sourcePath, destinationPath)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            Result.failure(Exception("Root required for privileged copy"))
        }
    }

    override suspend fun getAppPaths(packageName: String): Result<List<String>> {
        return try {
            // Prefer Root for accuracy with splits/system apps, fall back if needed
            if (rootGateway.isRootAvailable()) {
                val paths = rootGateway.getAppPaths(packageName)
                if (paths.isNotEmpty()) Result.success(paths)
                else Result.failure(Exception("No paths found"))
            } else {
                // Fallback: Shizuku or PackageManager logic could go here
                Result.failure(Exception("Root required to fetch split paths reliably"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}