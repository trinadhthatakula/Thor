package com.valhalla.thor.data.gateway

import android.content.Context
import com.valhalla.superuser.ktx.ShellRepository
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.gateway.SystemGateway
import org.koin.core.annotation.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Modern implementation of SystemGateway using the reactive ShellRepository.
 * No more static blocking calls.
 */
@Single
class RootSystemGateway(
    private val context: Context,
    private val shellRepository: ShellRepository
) : SystemGateway {

    // A root check is strictly asynchronous. Blocking the thread for this is unacceptable.
    override suspend fun isRootAvailable(): Boolean {
        return shellRepository.isRootGranted()
    }

    override fun isShizukuAvailable(): Boolean = false
    override fun isDhizukuAvailable(): Boolean = false

    override suspend fun forceStopApp(packageName: String): Result<Unit> {
        val shellResult = runCommand("am force-stop $packageName")
        if (shellResult.isSuccess) return shellResult

        // Unprivileged check/fallback
        val isStopped = runCatching {
            val appInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
            } else {
                context.packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
        }.getOrDefault(false)
        if (isStopped) return Result.success(Unit)

        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.killBackgroundProcesses(packageName)
        }

        val postCheck = runCatching {
            val appInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
            } else {
                context.packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
        }.getOrDefault(false)
        if (postCheck) return Result.success(Unit)

        return Result.failure(Exception("Root force stop failed. Shell command failed and app is still running."))
    }

    override suspend fun clearCache(packageName: String): Result<Unit> {
        val command = "rm -rf /data/data/$packageName/cache /sdcard/Android/data/$packageName/cache"
        return runCommand(command)
    }

    override suspend fun clearAppData(packageName: String): Result<Unit> {
        val shellResult = runCommand("pm clear $packageName")
        if (shellResult.isSuccess) return shellResult

        // Fallback to reflection via RootMain
        val taskResult = runRootTask("clear-data", packageName)
        if (taskResult.isSuccess) return Result.success(Unit)

        return Result.failure(Exception("Root clear app data failed. Shell command and reflection both failed."))
    }

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> {
        val state = if (isDisabled) "disable" else "enable"
        val shellResult = runCommand("pm $state $packageName")
        if (shellResult.isSuccess) return shellResult

        // Check if already in the target state
        val currentDisabled = runCatching {
            val appInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
            } else {
                context.packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }
            !appInfo.enabled
        }.getOrDefault(false)
        if (currentDisabled == isDisabled) return Result.success(Unit)

        // Try unprivileged API as fallback
        val newState = if (isDisabled) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        val unprivilegedResult = runCatching {
            context.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
        }
        if (unprivilegedResult.isSuccess) {
            val postCheck = runCatching {
                val appInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
                } else {
                    context.packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)
                }
                !appInfo.enabled
            }.getOrDefault(false)
            if (postCheck == isDisabled) return Result.success(Unit)
        }

        return Result.failure(Exception("Root setAppDisabled failed."))
    }

    override suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit> {
        // 1. Try shell first
        val state = if (isSuspended) "suspend" else "unsuspend"
        val shellResult = runCommand("pm $state $packageName")
        if (shellResult.isSuccess) return shellResult

        // 2. Fallback to reflection via RootMain
        if (isSuspended && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val taskResult = runRootTask("suspend", packageName, isSuspended.toString())
            if (taskResult.isSuccess) return Result.success(Unit)
        }

        // 3. Check if already in the target state
        val currentSuspended = runCatching {
            val appInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
            } else {
                context.packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
        }.getOrDefault(false)
        if (currentSuspended == isSuspended) return Result.success(Unit)

        return Result.failure(Exception("Root setAppSuspended failed. Shell command and reflection both failed."))
    }

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean
    ): Result<Unit> {
        val state = if (isRestricted) "ignore" else "allow"
        return runCommand("appops set $packageName RUN_ANY_IN_BACKGROUND $state")
    }

    override suspend fun rebootDevice(reason: String): Result<Unit> {
        // executeResult returns success if ANY of the commands succeed in the chain logic
        return runCommand("svc power reboot $reason || reboot $reason")
    }

    override suspend fun uninstallApp(packageName: String): Result<Unit> {
        return runCommand("pm uninstall --user 0 $packageName")
    }

    override suspend fun installApp(apkPath: String, canDowngrade: Boolean): Result<Unit> {
        val command = "pm install -r -g${if (canDowngrade) " -d" else ""} ${
            com.valhalla.superuser.ShellUtils.escapedString(apkPath)
        }"
        return runCommand(command)
    }

    suspend fun installMultipleApks(apkPaths: List<String>, canDowngrade: Boolean): Result<Unit> {
        if (apkPaths.isEmpty()) {
            return Result.failure(Exception("No APK paths provided for multi-install"))
        }
        val escapedPaths = apkPaths.joinToString(" ") {
            com.valhalla.superuser.ShellUtils.escapedString(it)
        }
        val command = "pm install-multiple -r -g${if (canDowngrade) " -d" else ""} $escapedPaths"
        return runCommand(command)
    }

    override suspend fun getAppCacheSize(packageName: String): Long {
        return try {
            val result = shellRepository.runCommand("du -s /data/data/$packageName/cache")
            val outputLine = result.getOrNull()?.firstOrNull() ?: return 0L

            // Output format is usually "12345   /path/to/file"
            // We parse this in Kotlin, not using brittle 'awk' or 'cut'
            val sizeInBlocks =
                outputLine.substringBefore('\t').substringBefore(' ').toLongOrNull() ?: 0L

            // du usually returns 1k blocks
            sizeInBlocks * 1024
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Modernized Reinstall Logic.
     * Replaces the 'sed' and 'tr' pipes with proper Kotlin string manipulation.
     */
    override suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> {
        if (packageName == BuildConfig.APPLICATION_ID)
            return Result.failure(Exception("Cannot reinstall Thor"))

        return withContext(Dispatchers.IO) {
            try {
                // 1. Get the APK path(s)
                val paths = getAppPaths(packageName)
                if (paths.isEmpty()) {
                    return@withContext Result.failure(Exception("Could not find APK path for $packageName"))
                }

                val combinedPath = paths.joinToString(" ") { "\"$it\"" }

                // 2. Get Current User ID
                val userResult = shellRepository.runCommand("am get-current-user")
                val currentUser = userResult.getOrNull()?.firstOrNull()?.trim()
                    ?: return@withContext Result.failure(Exception("Could not determine current user"))

                // 3. Execute the reinstallation command
                val command =
                    "pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 $combinedPath"
                runCommand(command)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Copies a file using Root privileges.
     */
    suspend fun copyFile(source: String, destination: String) {
        val command = "cp \"$source\" \"$destination\""
        val result = runCommand(command)

        if (result.isFailure) {
            throw Exception("Root copy failed: $command")
        }
    }

    /**
     * Retrieves all APK paths (Base + Splits) for a package.
     */
    suspend fun getAppPaths(packageName: String): List<String> {
        val result = shellRepository.runCommand("pm path \"$packageName\"")
        val lines = result.getOrNull() ?: emptyList()

        return lines
            .filter { it.isNotBlank() }
            .map { it.removePrefix("package:").trim() }
    }

    /**
     * Executes a Root command in a separate process using app_process.
     */
    private suspend fun runRootTask(action: String, vararg args: String): Result<Unit> {
        val apkPath = context.packageCodePath
        val className = "com.valhalla.thor.data.source.local.root.RootMain"
        val cmd = "export CLASSPATH=$apkPath && app_process /system/bin $className $action ${args.joinToString(" ")}"
        return runCommand(cmd)
    }

    override suspend fun grantPermission(packageName: String, permissionName: String): Result<Unit> {
        if (!packageName.matches(Regex("^[a-zA-Z0-9._]+$")) || !permissionName.matches(Regex("^[a-zA-Z0-9._]+$"))) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        return runCommand("pm grant $packageName $permissionName")
    }

    override suspend fun revokePermission(packageName: String, permissionName: String): Result<Unit> {
        if (!packageName.matches(Regex("^[a-zA-Z0-9._]+$")) || !permissionName.matches(Regex("^[a-zA-Z0-9._]+$"))) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        return runCommand("pm revoke $packageName $permissionName")
    }

    /**
     * Helper to bridge ShellRepository's Result<List<String>> to Result<Unit>
     */
    private suspend fun runCommand(cmd: String): Result<Unit> {
        val result = shellRepository.runCommand(cmd)
        return if (result.isSuccess) {
            Result.success(Unit)
        } else {
            // Forward the exception from the repository or create a new one
            Result.failure(result.exceptionOrNull() ?: Exception("Shell command failed: $cmd"))
        }
    }
}