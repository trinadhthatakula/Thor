package com.valhalla.thor.data.gateway

import com.valhalla.superuser.Shell
import com.valhalla.thor.data.source.local.ShellDataSource
import com.valhalla.thor.domain.gateway.SystemGateway

class RootSystemGateway(
    private val shellDataSource: ShellDataSource
) : SystemGateway {

    override fun isRootAvailable(): Boolean {
        // We can't suspend here if the interface is blocking,
        // but checking Shell.rootAccess() is generally fast/cached.
        // Ideally, change your Interface to 'suspend' for this check,
        // or rely on LibSu's internal cache.
        return Shell.isAppGrantedRoot?: Shell.shell.isRoot
    }

    override fun isShizukuAvailable(): Boolean = false

    override suspend fun forceStopApp(packageName: String): Result<Unit> {
        return runCommand("am force-stop $packageName")
    }

    override suspend fun clearCache(packageName: String): Result<Unit> {
        return runCommand("rm -rf /data/data/$packageName/cache")
    }

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> {
        val state = if (isDisabled) "disable" else "enable"
        return runCommand("pm $state $packageName")
    }

    override suspend fun rebootDevice(reason: String): Result<Unit> {
        // Clean reboot command chain
        return runCommand("svc power reboot $reason || reboot $reason")
    }

    override suspend fun uninstallApp(packageName: String): Result<Unit> {
        return runCommand("pm uninstall --user 0 $packageName")
    }

    override suspend fun installApp(apkPath: String): Result<Unit> {
        return runCommand("pm install -r -g '$apkPath'")
    }

    override suspend fun getAppCacheSize(packageName: String): Long {
        return try {
            val output = shellDataSource.executeRootCommandWithOutput("du -s /data/data/$packageName/cache")
            // Output format is usually "12345   /path/to/file"
            val sizeInBlocks = output.split("\\s+".toRegex()).firstOrNull()?.toLongOrNull() ?: 0L
            // du usually returns 1k blocks
            sizeInBlocks * 1024
        } catch (_: Exception) {
            0L
        }
    }

    private suspend fun runCommand(cmd: String): Result<Unit> {
        return if (shellDataSource.executeRootCommand(cmd)) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Shell command failed: $cmd"))
        }
    }

    suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> {
        return try {
            // 1. Get the APK path(s)
            // "pm path" returns "package:/path/to/base.apk". We strip "package:" and join splits.
            val pathOutput = shellDataSource.executeRootCommandWithOutput(
                "pm path \"$packageName\" | sed 's/package://' | tr '\\n' ' '"
            )
            val combinedPath = pathOutput.trim()

            if (combinedPath.isBlank()) {
                return Result.failure(Exception("Could not find APK path for $packageName"))
            }

            // 2. Get Current User ID
            val userOutput = shellDataSource.executeRootCommandWithOutput("am get-current-user")
            val currentUser = userOutput.trim()

            // 3. Execute the trick command
            // -r: Reinstall
            // -d: Downgrade (allow version downgrade)
            // -i: Installer Package Name (This is the key!)
            val command = "pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 \"$combinedPath\""

            runCommand(command)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Copies a file using Root privileges.
     * Essential for accessing APKs in protected directories (like /data/app)
     * where standard File I/O might fail due to permission denial.
     */
    suspend fun copyFile(source: String, destination: String) {
        // We use quotes to handle paths with spaces safely
        val command = "cp \"$source\" \"$destination\""

        val success = shellDataSource.executeRootCommand(command)

        if (!success) {
            throw Exception("Root copy failed: $command")
        }
    }

    /**
     * Retrieves all APK paths (Base + Splits) for a package.
     * Replaces the old "pm path" shell command logic.
     */
    suspend fun getAppPaths(packageName: String): List<String> {
        // Output format:
        // package:/data/app/com.example/base.apk
        // package:/data/app/com.example/split_config.xx.apk
        val output = shellDataSource.executeRootCommandWithOutput("pm path \"$packageName\"")

        return output.lines()
            .filter { it.isNotBlank() }
            .map { it.removePrefix("package:").trim() }
    }

}