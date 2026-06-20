package com.valhalla.thor.data.source.local.dhizuku

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import com.valhalla.bypass.Bypass
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.shizuku.Packages
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import com.rosan.dhizuku.api.Dhizuku as DhizukuAPI
import com.valhalla.thor.util.Logger
import com.valhalla.thor.R

/**
 * Helper to interact with Dhizuku service using the actual API.
 */
object DhizukuHelper {

    fun isDhizukuAvailable(): Boolean {
        return try {
            DhizukuAPI.isPermissionGranted()
        } catch (_: Exception) {
            false
        }
    }

    fun getSystemService(serviceName: String): IBinder? {
        return try {
            val binder = SystemServiceHelper.getSystemService(serviceName)
            DhizukuAPI.binderWrapper(binder)
        } catch (_: Exception) {
            null
        }
    }

    private fun asInterface(className: String, original: IBinder): Any {
        val clazz = Class.forName("$className\$Stub")
        return Bypass.invoke(
            clazz,
            null,
            "asInterface",
            arrayOf(IBinder::class.java),
            ShizukuBinderWrapper(original)
        )
    }

    private fun asInterface(className: String, serviceName: String): Any? {
        val binder = getSystemService(serviceName) ?: return null
        return asInterface(className, binder)
    }

    fun forceStopApp(context: Context, packageName: String): Boolean {
        val userId = Packages(context).myUserId
        // 1. Try shell first
        val result = execute("am force-stop --user $userId $packageName")
        if (result.first == 0) return true

        // 2. Fallback to reflection
        val reflectionResult = runCatching {
            val am = asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE)
                ?: return@runCatching false
            Bypass.invoke<Any?>(
                am::class.java, am, "forceStopPackage", packageName, userId
            )
            true
        }.getOrElse {
            Logger.e(
                "DhizukuHelper",
                "forceStopApp reflection failed for $packageName",
                it
            )
            false
        }
        if (reflectionResult) return true

        // 3. Unprivileged fallback
        if (Packages(context).isAppStopped(packageName)) return true
        runCatching {
            val am =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.killBackgroundProcesses(packageName)
        }
        return Packages(context).isAppStopped(packageName)
    }

    fun setAppDisabled(context: Context, packageName: String, disabled: Boolean): Boolean {
        Packages(context).getApplicationInfoOrNull(packageName) ?: return false
        val userId = Packages(context).myUserId

        // 1. Try shell first
        val command = if (disabled) {
            "pm disable-user --user $userId $packageName"
        } else {
            "pm enable --user $userId $packageName"
        }
        val result = execute(command)
        if (result.first == 0 && Packages(context).isAppDisabled(packageName) == disabled) {
            return true
        }

        // 2. Fallback to Bypass reflection
        val reflectionResult = runCatching {
            val pm =
                asInterface("android.content.pm.IPackageManager", "package") ?: return@runCatching false
            val newState = when {
                !disabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
            Bypass.invoke<Any?>(
                pm.javaClass,
                pm,
                "setApplicationEnabledSetting",
                arrayOf(
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    String::class.java
                ),
                packageName,
                newState,
                0,
                userId,
                BuildConfig.APPLICATION_ID
            )
            true
        }.onFailure {
            Logger.e(
                "DhizukuHelper",
                "setAppDisabled fallback reflection failed for $packageName",
                it
            )
        }.getOrDefault(false)

        if (reflectionResult && Packages(context).isAppDisabled(packageName) == disabled) {
            return true
        }

        // 3. Unprivileged fallback
        if (Packages(context).isAppDisabled(packageName) == disabled) {
            return true
        }
        val newState = if (disabled) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        runCatching {
            context.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
        }

        return Packages(context).isAppDisabled(packageName) == disabled
    }

    fun uninstallApp(packageName: String): Boolean {
        val currentUser = try {
            val userResult = execute("am get-current-user")
            userResult.second?.trim()?.takeIf { it.matches(Regex("^\\d+$")) } ?: "0"
        } catch (_: Exception) {
            "0"
        }
        return execute(
            "pm uninstall --user $currentUser ${
                com.valhalla.superuser.ShellUtils.escapedString(
                    packageName
                )
            }"
        ).first == 0
    }

    fun reinstallApp(packageName: String): Boolean {
        val currentUser = try {
            val userResult = execute("am get-current-user")
            userResult.second?.trim()?.takeIf { it.matches(Regex("^\\d+$")) } ?: "0"
        } catch (_: Exception) {
            "0"
        }
        return execute(
            "pm install-existing --user $currentUser ${
                com.valhalla.superuser.ShellUtils.escapedString(
                    packageName
                )
            }"
        ).first == 0
    }

    fun execute(command: String): Pair<Int, String?> = runCatching {
        // Dhizuku 2.x supports newProcess for shell commands
        val process = DhizukuAPI.newProcess(arrayOf("sh", "-c", command), null, null)
        var output = ""
        var error = ""

        val outThread = Thread {
            runCatching {
                output = process.inputStream.bufferedReader().use { it.readText() }
            }.onFailure { err ->
                Logger.e("Dhizuku", "Failed to read standard output", err)
            }
        }

        val errThread = Thread {
            runCatching {
                error = process.errorStream.bufferedReader().use { it.readText() }
            }.onFailure { err ->
                Logger.e("Dhizuku", "Failed to read error output", err)
            }
        }

        try {
            outThread.start()
            errThread.start()

            val exitCode = process.waitFor()

            outThread.join()
            errThread.join()

            exitCode to (output.ifBlank { error })
        } finally {
            process.destroy()
        }
    }.getOrElse { err ->
        Logger.e("Dhizuku", "Command execution failed: $command", err)
        -1 to err.stackTraceToString()
    }

    @SuppressLint("PrivateApi")
    fun clearCache(packageName: String): Boolean {
        // 1. Try shell first
        val userId = android.os.Process.myUserHandle().hashCode()
        val paths = listOf(
            "/data/data/$packageName/cache",
            "/data/user/$userId/$packageName/cache",
            "/sdcard/Android/data/$packageName/cache"
        )
        val command = "rm -rf ${paths.joinToString(" ")}"
        val shellResult = execute(command)
        if (shellResult.first == 0) return true

        // 2. Fallback to reflection
        val reflectionResult = runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package") ?: return@runCatching false
            val observerClass = Class.forName("android.content.pm.IPackageDataObserver")

            try {
                Bypass.invoke<Any?>(
                    pm.javaClass,
                    pm,
                    "deleteApplicationCacheFiles",
                    arrayOf(String::class.java, observerClass),
                    packageName,
                    null /* IPackageDataObserver */
                )
            } catch (_: NoSuchMethodException) {
                Bypass.invoke(
                    pm.javaClass,
                    pm,
                    "deleteApplicationCacheFilesAsUser",
                    arrayOf(String::class.java, Int::class.javaPrimitiveType!!, observerClass),
                    packageName,
                    userId,
                    null
                )
            }
            true
        }.getOrDefault(false)

        return reflectionResult
    }

    fun clearAppData(packageName: String): Boolean {
        // 1. Try shell first
        val result = execute("pm clear $packageName")
        if (result.first == 0) return true

        // 2. Fallback to reflection
        return runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package") ?: return@runCatching false
            val observerClass = Class.forName("android.content.pm.IPackageDataObserver")
            Bypass.invoke<Any?>(
                pm.javaClass,
                pm,
                "clearApplicationUserData",
                arrayOf(String::class.java, observerClass, Int::class.javaPrimitiveType!!),
                packageName,
                null,
                android.os.Process.myUserHandle().hashCode()
            )
            true
        }.getOrElse { false }
    }

    fun setAppSuspended(context: Context, packageName: String, suspended: Boolean): Boolean {
        Packages(context).getApplicationInfoOrNull(packageName) ?: return false
        val userId = Packages(context).myUserId

        // 1. Try shell first
        val command = if (suspended) {
            "pm suspend --user $userId $packageName"
        } else {
            "pm unsuspend --user $userId $packageName"
        }
        val shellResult = execute(command)
        if (shellResult.first == 0) return true

        // 2. Fallback to reflection
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val reflectionResult = runCatching {
                val pm =
                    asInterface("android.content.pm.IPackageManager", "package") ?: return@runCatching false
                val dialogInfoClass = Class.forName("android.content.pm.SuspendDialogInfo")
                val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
                val dialogInfo = if (suspended) {
                    Bypass.newInstance<Any>(builderClass).let { b ->
                        val title = context.getString(R.string.suspended_app_dialog_title)
                        val message = context.getString(R.string.suspended_app_dialog_message)
                        Bypass.invoke<Any>(builderClass, b, "setTitle", title)
                        Bypass.invoke<Any>(
                            builderClass,
                            b,
                            "setMessage",
                            message
                        )
                        Bypass.invoke<Any>(builderClass, b, "build")
                    }
                } else {
                    null
                }

                val caller = BuildConfig.APPLICATION_ID

                try {
                    // Try Android 13+ (8 args)
                    Bypass.invoke<Array<String>>(
                        pm.javaClass, pm, "setPackagesSuspendedAsUser",
                        arrayOf(
                            Array<String>::class.java,
                            Boolean::class.javaPrimitiveType!!,
                            android.os.PersistableBundle::class.java,
                            android.os.PersistableBundle::class.java,
                            dialogInfoClass,
                            Int::class.javaPrimitiveType!!,
                            String::class.java,
                            Int::class.javaPrimitiveType!!
                        ),
                        arrayOf(packageName),
                        suspended,
                        null, null,
                        dialogInfo,
                        0,
                        caller,
                        userId
                    )
                } catch (_: NoSuchMethodException) {
                    // Try Android 10-12 (7 args)
                    Bypass.invoke<Array<String>>(
                        pm.javaClass, pm, "setPackagesSuspendedAsUser",
                        arrayOf(
                            Array<String>::class.java,
                            Boolean::class.javaPrimitiveType!!,
                            android.os.PersistableBundle::class.java,
                            android.os.PersistableBundle::class.java,
                            dialogInfoClass,
                            String::class.java,
                            Int::class.javaPrimitiveType!!
                        ),
                        arrayOf(packageName),
                        suspended,
                        null, null,
                        dialogInfo,
                        caller,
                        userId
                    )
                }
                true
            }.getOrDefault(false)

            if (reflectionResult) return true
        }

        // 3. Unprivileged fallback
        val currentSuspended = Packages(context).getApplicationInfoOrNull(packageName)?.run {
            (flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
        } ?: false
        return currentSuspended == suspended
    }

    fun setAppRestricted(context: Context, packageName: String, restricted: Boolean): Boolean {
        // 1. Try shell first
        val result =
            execute("appops set $packageName RUN_ANY_IN_BACKGROUND ${if (restricted) "ignore" else "allow"}")
        if (result.first == 0) return true

        // 2. Fallback to reflection
        return runCatching {
            val appops =
                asInterface("com.android.internal.app.IAppOpsService", Context.APP_OPS_SERVICE)
                    ?: return@runCatching false
            val uid = Packages(context).packageUid(packageName)
            Bypass.invoke<Any?>(
                appops::class.java,
                appops,
                "setMode",
                arrayOf(
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                    Int::class.javaPrimitiveType!!
                ),
                Bypass.invoke<Int>(
                    android.app.AppOpsManager::class.java,
                    null,
                    "strOpToOp",
                    "android:run_any_in_background"
                ),
                uid,
                packageName,
                if (restricted) android.app.AppOpsManager.MODE_IGNORED else android.app.AppOpsManager.MODE_ALLOWED
            )
            true
        }.getOrElse { false }
    }
}
