package com.valhalla.thor.data.gateway

import android.content.pm.PackageManager
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.shizuku.ShizukuReflector
import com.valhalla.thor.domain.gateway.SystemGateway
import org.koin.core.annotation.Single
import rikka.shizuku.Shizuku
import com.valhalla.thor.data.source.local.shizuku.Shizuku as ShizukuHelper
import com.valhalla.thor.util.Logger
import com.valhalla.superuser.ShellUtils

private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")
private val USER_ID_REGEX = Regex("^\\d+$")

@Single
class ShizukuSystemGateway(
    private val reflector: ShizukuReflector
) : SystemGateway {

    override suspend fun isRootAvailable() = false

    override suspend fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED && Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun isDhizukuAvailable(): Boolean = false

    override suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>> {
        // Runs through Shizuku's privileged process (shell uid), same path as in-app actions.
        return runCatching { ShizukuHelper.execute(command) }
    }

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
        val isSystem = reflector.isSystemApp(packageName)
        return if (isSystem) {
            if (isDisabled) {
                runAction { reflector.uninstallApp(packageName) }
            } else {
                val appInfo = reflector.getApplicationInfoOrNull(packageName)
                val isInstalled = appInfo?.let { (it.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0 } ?: false
                val isAppDisabled = appInfo?.enabled == false
                if (isAppDisabled && isInstalled) {
                    reflector.setAppEnabled(packageName, true)
                }
                runAction { reflector.reinstallExistingApp(packageName) }
            }
        } else {
            runAction { reflector.setAppEnabled(packageName, !isDisabled) }
        }
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
            val escapedPackageName = ShellUtils.escapedString(packageName)
            // 1. Get the APK path(s)
            val pathResult = ShizukuHelper.execute("pm path $escapedPackageName")
            val paths = pathResult.second?.lines()
                ?.filter { it.isNotBlank() }
                ?.map { it.removePrefix("package:").trim() } ?: emptyList()

            if (paths.isEmpty()) {
                return Result.failure(Exception("Could not find APK path for $packageName"))
            }

            val combinedPath = paths.joinToString(" ") { ShellUtils.escapedString(it) }

            // 2. Get Current User ID
            val currentUser = ShizukuHelper.getCurrentUserId()

            // 3. Execute the reinstallation command
            val command =
                "pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 $combinedPath"
            val result = ShizukuHelper.execute(command)
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Shizuku reinstall failed: ${result.second}"))
        } catch (e: Exception) {
            Logger.e("ShizukuSystemGateway", "Reinstall with Google failed for $packageName", e)
            Result.failure(e)
        }
    }

    override suspend fun grantPermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX) || !permissionName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        val escapedPackageName = ShellUtils.escapedString(packageName)
        val escapedPermissionName = ShellUtils.escapedString(permissionName)
        return try {
            val result = ShizukuHelper.execute("pm grant $escapedPackageName $escapedPermissionName")
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Shizuku: pm grant failed with exit code ${result.first}: ${result.second}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX) || !permissionName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        val escapedPackageName = ShellUtils.escapedString(packageName)
        val escapedPermissionName = ShellUtils.escapedString(permissionName)
        return try {
            val result = ShizukuHelper.execute("pm revoke $escapedPackageName $escapedPermissionName")
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Shizuku: pm revoke failed with exit code ${result.first}: ${result.second}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Standardizes error handling for reflection and shell actions.
     */
    private suspend inline fun runAction(action: suspend () -> Boolean): Result<Unit> {
        if (!isShizukuAvailable()) {
            return Result.failure(Exception("Shizuku is not available or permission denied."))
        }
        return try {
            if (action()) Result.success(Unit)
            else Result.failure(Exception("Action failed. This may happen if reflection is blocked or shell lacks permissions."))
        } catch (e: Exception) {
            Logger.e("ShizukuSystemGateway", "Action execution failed", e)
            Result.failure(e)
        }
    }
}