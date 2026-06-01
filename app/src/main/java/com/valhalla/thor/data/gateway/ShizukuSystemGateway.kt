package com.valhalla.thor.data.gateway

import android.content.pm.PackageManager
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.shizuku.ShizukuReflector
import com.valhalla.thor.domain.gateway.SystemGateway
import rikka.shizuku.Shizuku
import com.valhalla.thor.data.source.local.shizuku.Shizuku as ShizukuHelper

class ShizukuSystemGateway(
    private val reflector: ShizukuReflector
) : SystemGateway {

    override suspend fun isRootAvailable() = false

    override fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED && Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    override fun isDhizukuAvailable(): Boolean = false

    override suspend fun forceStopApp(packageName: String): Result<Unit> {
        return runAction { reflector.forceStop(packageName) }
    }

    override suspend fun clearCache(packageName: String): Result<Unit> {
        return runAction { reflector.clearCache(packageName) }
    }

    override suspend fun clearAppData(packageName: String): Result<Unit> {
        return runAction { reflector.clearData(packageName) }
    }

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> {
        return runAction { reflector.setAppEnabled(packageName, !isDisabled) }
    }

    override suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit> {
        return runAction { reflector.setAppSuspended(packageName, isSuspended) }
    }

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean
    ): Result<Unit> {
        return runAction { reflector.setAppRestricted(packageName, isRestricted) }
    }

    override suspend fun rebootDevice(reason: String): Result<Unit> {
        return Result.failure(Exception("Reboot requires Root. Shizuku cannot perform this action."))
    }

    override suspend fun uninstallApp(packageName: String): Result<Unit> {
        return if (reflector.uninstallApp(packageName)) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Uninstall failed"))
        }
    }

    override suspend fun installApp(apkPath: String, canDowngrade: Boolean): Result<Unit> {
        return if (reflector.installPackage(apkPath, canDowngrade)) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Shizuku install failed. Ensure the file path is readable by Shell/ADB."))
        }
    }

    override suspend fun getAppCacheSize(packageName: String): Long {
        return 0L // Requires specialized logic
    }

    override suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> {
        if (packageName == BuildConfig.APPLICATION_ID)
            return Result.failure(Exception("Cannot reinstall Thor"))

        return try {
            // 1. Get the APK path(s)
            val pathResult = ShizukuHelper.execute("pm path $packageName")
            val paths = pathResult.second?.lines()
                ?.filter { it.isNotBlank() }
                ?.map { it.removePrefix("package:").trim() } ?: emptyList()

            if (paths.isEmpty()) {
                return Result.failure(Exception("Could not find APK path for $packageName"))
            }

            val combinedPath = paths.joinToString(" ") { "\"$it\"" }

            // 2. Get Current User ID
            val userResult = ShizukuHelper.execute("am get-current-user")
            val currentUser = userResult.second?.trim()
                ?: return Result.failure(Exception("Could not determine current user"))

            // 3. Execute the reinstallation command
            val command =
                "pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 $combinedPath"
            val result = ShizukuHelper.execute(command)
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Shizuku reinstall failed: ${result.second}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun grantPermission(packageName: String, permissionName: String): Result<Unit> {
        return try {
            val result = ShizukuHelper.execute("pm grant $packageName $permissionName")
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Shizuku: pm grant failed with exit code ${result.first}: ${result.second}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun revokePermission(packageName: String, permissionName: String): Result<Unit> {
        return try {
            val result = ShizukuHelper.execute("pm revoke $packageName $permissionName")
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Shizuku: pm revoke failed with exit code ${result.first}: ${result.second}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Standardizes error handling for reflection and shell actions.
     */
    private inline fun runAction(action: () -> Boolean): Result<Unit> {
        if (!isShizukuAvailable()) {
            return Result.failure(Exception("Shizuku is not available or permission denied."))
        }
        return try {
            if (action()) Result.success(Unit)
            else Result.failure(Exception("Action failed. This may happen if reflection is blocked or shell lacks permissions."))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                e.printStackTrace()
            Result.failure(e)
        }
    }
}