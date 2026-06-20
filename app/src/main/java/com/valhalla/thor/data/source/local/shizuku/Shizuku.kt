package com.valhalla.thor.data.source.local.shizuku

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.valhalla.bypass.Bypass
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.text.NumberFormat
import java.util.Locale
import com.valhalla.thor.util.Logger

object Shizuku {

    val isRoot get() = Shizuku.getUid() == 0

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

    private fun asInterface(className: String, serviceName: String): Any =
        asInterface(className, SystemServiceHelper.getSystemService(serviceName))

    val lockScreen
        get() = runCatching {
            execute("input keyevent 26").first == 0
        }.getOrElse {
            Logger.e("Shizuku", "lockScreen event trigger failed", it)
            false
        }

    fun forceStopApp(context: Context, packageName: String): Boolean {
        val userId = Packages(context).myUserId
        // 1. Try shell first
        val result = execute("am force-stop --user $userId $packageName")
        if (result.first == 0) return true

        // 2. Fallback to reflection
        val reflectionResult = runCatching {
            val am = asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE)
            Bypass.invoke<Any?>(
                am::class.java, am, "forceStopPackage", packageName, userId
            )
            true
        }.getOrElse {
            Logger.e("Shizuku", "forceStopApp reflection failed for $packageName", it)
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
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            val newState = when {
                !disabled -> android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                isRoot -> android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else -> android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
            val caller = if (isRoot) context.packageName else "com.android.shell"
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
                caller
            )
            true
        }.getOrDefault(false)

        if (reflectionResult && Packages(context).isAppDisabled(packageName) == disabled) {
            return true
        }

        // 3. Unprivileged fallback
        if (Packages(context).isAppDisabled(packageName) == disabled) {
            return true
        }
        val newState = if (disabled) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        runCatching {
            context.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
        }

        return Packages(context).isAppDisabled(packageName) == disabled
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
                val pm = asInterface("android.content.pm.IPackageManager", "package")
                val dialogInfoClass = Class.forName("android.content.pm.SuspendDialogInfo")
                val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
                val dialogInfo = if (suspended) {
                    Bypass.newInstance<Any>(builderClass).let { b ->
                        val title = context.getString(com.valhalla.thor.R.string.suspended_app_dialog_title)
                        val message = context.getString(com.valhalla.thor.R.string.suspended_app_dialog_message)
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

                val caller =
                    if (isRoot) com.valhalla.thor.BuildConfig.APPLICATION_ID else "com.android.shell"

                try {
                    // Try Android 13+ (8 args)
                    Bypass.invoke<Array<String>>(
                        pm.javaClass,
                        pm,
                        "setPackagesSuspendedAsUser",
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
                        pm.javaClass,
                        pm,
                        "setPackagesSuspendedAsUser",
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
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            val observerClass = Class.forName("android.content.pm.IPackageDataObserver")

            try {
                Bypass.invoke<Any?>(
                    pm.javaClass,
                    pm,
                    "deleteApplicationCacheFiles",
                    arrayOf(String::class.java, observerClass),
                    packageName,
                    null
                )
            } catch (_: NoSuchMethodException) {
                Bypass.invoke<Any?>(
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
            val pm = asInterface("android.content.pm.IPackageManager", "package")
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

    fun getTotalCacheSizeWithShizuku(): Long {
        var totalCacheBytes = 0L
        val result = execute("dumpsys diskstats")

        result.second?.lines()?.forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("Cache Size:")) {
                try {
                    val sizeString = trimmedLine.substringAfter(":").trim()
                    val bytes =
                        NumberFormat.getNumberInstance(Locale.US).parse(sizeString)?.toLong() ?: 0L
                    totalCacheBytes += bytes
                } catch (e: Exception) {
                    Logger.e("Shizuku", "Failed to parse cache size line: $trimmedLine", e)
                }
            }
        }
        return totalCacheBytes
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

    fun uninstallApp(context: Context, packageName: String): Boolean {
        val normally = Packages(context).canUninstallNormally(packageName)
        if (normally) {
            return execute("pm uninstall $packageName").first == 0
        }
        val currentUser = try {
            val userResult = execute("am get-current-user")
            userResult.second?.trim()?.takeIf { it.matches(Regex("^\\d+$")) } ?: "0"
        } catch (_: Exception) {
            "0"
        }
        return execute("pm uninstall --user $currentUser $packageName").first == 0
    }

    fun reinstallApp(packageName: String): Boolean {
        val currentUser = try {
            val userResult = execute("am get-current-user")
            userResult.second?.trim()?.takeIf { it.matches(Regex("^\\d+$")) } ?: "0"
        } catch (_: Exception) {
            "0"
        }
        return execute("pm install-existing --user $currentUser $packageName").first == 0
    }

    fun execute(command: String, root: Boolean = isRoot): Pair<Int, String?> = runCatching {
        val binder = Shizuku.getBinder() ?: return -1 to "Shizuku binder is null"
        IShizukuService.Stub.asInterface(binder)
            .newProcess(arrayOf(if (root) "su" else "sh"), null, null)
            .run {
                var output = ""
                var error = ""

                val outThread = Thread {
                    runCatching {
                        output = inputStream.text
                    }.onFailure { err ->
                        Logger.e("Shizuku", "Failed to read standard output", err)
                    }
                }

                val errThread = Thread {
                    runCatching {
                        error = errorStream.text
                    }.onFailure { err ->
                        Logger.e("Shizuku", "Failed to read error output", err)
                    }
                }

                try {
                    outThread.start()
                    errThread.start()

                    runCatching {
                        ParcelFileDescriptor.AutoCloseOutputStream(outputStream).use {
                            it.write((command + "\nexit\n").toByteArray())
                            it.flush()
                        }
                    }.onFailure { err ->
                        Logger.e("Shizuku", "Failed to write command to process outputStream", err)
                    }

                    val exitCode = waitFor()

                    outThread.join()
                    errThread.join()

                    exitCode to output.ifBlank { error }
                } finally {
                    destroy()
                }
            }
    }.getOrElse { err ->
        Logger.e("Shizuku", "Command execution failed: $command", err)
        -1 to err.stackTraceToString()
    }

    private val ParcelFileDescriptor.text
        get() = ParcelFileDescriptor.AutoCloseInputStream(this)
            .use { it.bufferedReader().readText() }
}
