// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import com.valhalla.thor.data.source.local.dhizuku.DhizukuHelper
import com.valhalla.thor.data.source.local.dhizuku.DhizukuReflector
import com.valhalla.thor.domain.gateway.SystemGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import com.valhalla.thor.util.Logger
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.flow.first

private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")
private val USER_ID_REGEX = Regex("^\\d+$")

@Single
class DhizukuSystemGateway(
    private val reflector: DhizukuReflector,
    private val preferenceRepository: PreferenceRepository
) : SystemGateway {

    override suspend fun isRootAvailable() = false

    override suspend fun isShizukuAvailable(): Boolean = false

    // DhizukuHelper.isDhizukuAvailable() performs blocking binder IPC (DhizukuAPI); confine it
    // to IO at the gateway boundary so this probe is main-safe regardless of the caller's dispatcher.
    override suspend fun isDhizukuAvailable(): Boolean = withContext(Dispatchers.IO) {
        DhizukuHelper.isDhizukuAvailable()
    }

    override suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>> {
        // Runs through Dhizuku's device-owner process (DhizukuAPI.newProcess).
        return runCatching { DhizukuHelper.execute(command) }
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
        val isSystem = reflector.isSystemApp(packageName)
        return if (isSystem) {
            if (isDisabled) {
                if (reflector.uninstallApp(packageName)) Result.success(Unit)
                else Result.failure(Exception("Dhizuku: Failed to uninstall system app $packageName"))
            } else {
                val appInfo = reflector.getApplicationInfoOrNull(packageName)
                val isInstalled = appInfo?.let { (it.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0 } ?: false
                val isAppDisabled = appInfo?.enabled == false
                if (isAppDisabled && isInstalled) {
                    reflector.setAppEnabled(packageName, true)
                }
                if (reflector.reinstallExistingApp(packageName)) Result.success(Unit)
                else Result.failure(Exception("Dhizuku: Failed to reinstall system app $packageName"))
            }
        } else {
            if (reflector.setAppEnabled(packageName, !isDisabled)) Result.success(Unit)
            else Result.failure(Exception("Dhizuku: Set enabled state failed. Shell and reflection both failed."))
        }
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
        val installerArg = preferenceRepository.getInstallerArg()
        
        val result = DhizukuHelper.execute(
            "pm install -r -g${if (canDowngrade) " -d" else ""}$installerArg ${
                apkPath.escapeForShell()
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
            val escapedPackageName = packageName.escapeForShell()
            // 1. Get the APK path(s)
            val pathResult = DhizukuHelper.execute("pm path $escapedPackageName")
            val paths = pathResult.second?.lines()
                ?.filter { it.isNotBlank() }
                ?.map { it.removePrefix("package:").trim() } ?: emptyList()

            if (paths.isEmpty()) {
                return Result.failure(Exception("Dhizuku: Could not find APK path for $packageName"))
            }

            val combinedPath = paths.joinToString(" ") { it.escapeForShell() }

            // 2. Get Current User ID
            val currentUser = DhizukuHelper.getCurrentUserId()

            // 3. Execute the reinstallation command
            val command =
                "pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 $combinedPath"
            val result = DhizukuHelper.execute(command)
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Dhizuku: Reinstall failed: ${result.second}"))
        } catch (e: Exception) {
            Logger.e("DhizukuSystemGateway", "Reinstall with Google failed for $packageName", e)
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

    override suspend fun grantPermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX) || !permissionName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        val escapedPackageName = packageName.escapeForShell()
        val escapedPermissionName = permissionName.escapeForShell()
        return try {
            val result = DhizukuHelper.execute("pm grant $escapedPackageName $escapedPermissionName")
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Dhizuku: pm grant failed with exit code ${result.first}: ${result.second}"))
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
        val escapedPackageName = packageName.escapeForShell()
        val escapedPermissionName = permissionName.escapeForShell()
        return try {
            val result = DhizukuHelper.execute("pm revoke $escapedPackageName $escapedPermissionName")
            if (result.first == 0) Result.success(Unit)
            else Result.failure(Exception("Dhizuku: pm revoke failed with exit code ${result.first}: ${result.second}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

}
