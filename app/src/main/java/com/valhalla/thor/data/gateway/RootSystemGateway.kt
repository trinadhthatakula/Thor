package com.valhalla.thor.data.gateway

import com.valhalla.superuser.ktx.ShellRepository
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.bypass.Bypass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Modern implementation of SystemGateway using the reactive ShellRepository.
 * No more static blocking calls.
 */
class RootSystemGateway(
    private val shellRepository: ShellRepository
) : SystemGateway {

    // A root check is strictly asynchronous. Blocking the thread for this is unacceptable.
    override suspend fun isRootAvailable(): Boolean {
        return shellRepository.isRootGranted()
    }

    override fun isShizukuAvailable(): Boolean = false
    override fun isDhizukuAvailable(): Boolean = false

    override suspend fun forceStopApp(packageName: String): Result<Unit> {
        return runCommand("am force-stop $packageName")
    }

    override suspend fun clearCache(packageName: String): Result<Unit> {
        val command = "rm -rf /data/data/$packageName/cache /sdcard/Android/data/$packageName/cache"
        return runCommand(command)
    }

    override suspend fun clearAppData(packageName: String): Result<Unit> {
        return runCommand("pm clear $packageName")
    }

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> {
        val state = if (isDisabled) "disable" else "enable"
        return runCommand("pm $state $packageName")
    }

    override suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit> {
        val userId = android.os.Process.myUserHandle().hashCode()

        // Try reflection first to show proper branding
        if (isSuspended && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val reflectionResult = runCatching {
                // Using standard PackageManager for Root if bypass is ready
                val pmClass = Class.forName("android.content.pm.IPackageManager")
                val pmStub = Class.forName("android.content.pm.IPackageManager\$Stub")
                val serviceManager = Class.forName("android.os.ServiceManager")
                val binder = Bypass.invoke<android.os.IBinder>(serviceManager, null, "getService", "package")
                val pm = Bypass.invoke<Any>(pmStub, null, "asInterface", binder)

                val dialogInfoClass = Class.forName("android.content.pm.SuspendDialogInfo")
                val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
                val dialogInfo = Bypass.newInstance<Any>(builderClass).let { b ->
                    Bypass.invoke<Any>(builderClass, b, "setTitle", "Thor")
                    Bypass.invoke<Any>(builderClass, b, "setMessage", "This app has been suspended by Thor.")
                    Bypass.invoke<Any>(builderClass, b, "build")
                }

                val caller = BuildConfig.APPLICATION_ID

                try {
                    // Try Android 13+ (8 args)
                    Bypass.invoke<Array<String>>(
                        pmClass, pm, "setPackagesSuspendedAsUser",
                        arrayOf(Array<String>::class.java, Boolean::class.javaPrimitiveType!!, android.os.PersistableBundle::class.java, android.os.PersistableBundle::class.java, dialogInfoClass, Int::class.javaPrimitiveType!!, String::class.java, Int::class.javaPrimitiveType!!),
                        arrayOf(packageName), true, null, null, dialogInfo, 0, caller, userId
                    )
                } catch (_: NoSuchMethodException) {
                    // Try Android 10-12 (7 args)
                    Bypass.invoke<Array<String>>(
                        pmClass, pm, "setPackagesSuspendedAsUser",
                        arrayOf(Array<String>::class.java, Boolean::class.javaPrimitiveType!!, android.os.PersistableBundle::class.java, android.os.PersistableBundle::class.java, dialogInfoClass, String::class.java, Int::class.javaPrimitiveType!!),
                        arrayOf(packageName), true, null, null, dialogInfo, caller, userId
                    )
                }
                true
            }.getOrDefault(false)

            if (reflectionResult) return Result.success(Unit)
        }

        val state = if (isSuspended) "suspend" else "unsuspend"
        return runCommand("pm $state $packageName")
    }

    override suspend fun setAppRestricted(packageName: String, isRestricted: Boolean): Result<Unit> {
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
        val command = "pm install -r -g${if (canDowngrade) " -d" else ""} ${com.valhalla.superuser.ShellUtils.escapedString(apkPath)}"
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