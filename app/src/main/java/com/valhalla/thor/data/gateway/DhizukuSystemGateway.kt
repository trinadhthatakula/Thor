package com.valhalla.thor.data.gateway

import com.valhalla.thor.data.source.local.dhizuku.DhizukuHelper
import com.valhalla.thor.data.source.local.dhizuku.DhizukuReflector
import com.valhalla.thor.domain.gateway.SystemGateway

class DhizukuSystemGateway(
    private val reflector: DhizukuReflector
) : SystemGateway {

    override suspend fun isRootAvailable() = false

    override fun isShizukuAvailable(): Boolean = false

    override fun isDhizukuAvailable(): Boolean {
        return DhizukuHelper.isDhizukuAvailable()
    }

    override suspend fun forceStopApp(packageName: String): Result<Unit> {
        return if (reflector.forceStop(packageName)) Result.success(Unit)
        else Result.failure(Exception("Dhizuku: Force stop failed. Shell command and reflection both denied."))
    }

    override suspend fun clearCache(packageName: String): Result<Unit> {
        return if (reflector.clearCache(packageName)) Result.success(Unit)
        else Result.failure(Exception("Dhizuku: Clear cache failed. System reflection and shell rm -rf both failed."))
    }

    override suspend fun clearAppData(packageName: String): Result<Unit> {
        return if (reflector.clearData(packageName)) Result.success(Unit)
        else Result.failure(Exception("Dhizuku: Clear data failed. Shell pm clear and reflection both failed."))
    }

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> {
        return if (reflector.setAppEnabled(packageName, !isDisabled)) Result.success(Unit)
        else Result.failure(Exception("Dhizuku: Set enabled state failed. Shell and reflection both failed."))
    }

    override suspend fun rebootDevice(reason: String): Result<Unit> {
        return Result.failure(Exception("Dhizuku: Reboot not supported directly. Use Root mode instead."))
    }

    override suspend fun uninstallApp(packageName: String): Result<Unit> {
        return if (reflector.uninstallApp(packageName)) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Dhizuku: Uninstall failed."))
        }
    }

    override suspend fun installApp(apkPath: String, canDowngrade: Boolean): Result<Unit> {
        val result = DhizukuHelper.execute(
            "pm install -r -g${if (canDowngrade) " -d" else ""} ${
                com.valhalla.superuser.ShellUtils.escapedString(apkPath)
            }"
        )
        return if (result.first == 0) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Dhizuku: Install failed: ${result.second}"))
        }
    }

    override suspend fun getAppCacheSize(packageName: String): Long {
        return 0L
    }

    override suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> {
        if (packageName == com.valhalla.thor.BuildConfig.APPLICATION_ID)
            return Result.failure(Exception("Cannot reinstall Thor"))

        return try {
            // 1. Get the APK path(s)
            val pathResult = DhizukuHelper.execute("pm path $packageName")
            val paths = pathResult.second?.lines()
                ?.filter { it.isNotBlank() }
                ?.map { it.removePrefix("package:").trim() } ?: emptyList()

            if (paths.isEmpty()) {
                return Result.failure(Exception("Dhizuku: Could not find APK path for $packageName"))
            }

            val combinedPath = paths.joinToString(" ") { "\"$it\"" }

            // 2. Get Current User ID
            val userResult = DhizukuHelper.execute("am get-current-user")
            val currentUser = userResult.second?.trim()
                ?: return Result.failure(Exception("Dhizuku: Could not determine current user"))

            // 3. Execute the reinstallation command
            val command =
                "pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 $combinedPath"
            val result = DhizukuHelper.execute(command)
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Dhizuku: Reinstall failed: ${result.second}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit> {
        return if (reflector.setAppSuspended(packageName, isSuspended)) Result.success(Unit)
        else Result.failure(Exception("Dhizuku: Set suspended state failed."))
    }

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean
    ): Result<Unit> {
        return if (reflector.setAppRestricted(packageName, isRestricted)) Result.success(Unit)
        else Result.failure(Exception("Dhizuku: Set restricted state failed."))
    }

    override suspend fun grantPermission(packageName: String, permissionName: String): Result<Unit> {
        return try {
            val result = DhizukuHelper.execute("pm grant $packageName $permissionName")
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Dhizuku: pm grant failed with exit code ${result.first}: ${result.second}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun revokePermission(packageName: String, permissionName: String): Result<Unit> {
        return try {
            val result = DhizukuHelper.execute("pm revoke $packageName $permissionName")
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Dhizuku: pm revoke failed with exit code ${result.first}: ${result.second}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
