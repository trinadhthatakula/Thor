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
        val result = execute("am force-stop --user $userId $packageName")
        if (result.first == 0) return true

        // Fallback to reflection
        return runCatching {
            val am = asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE)
                ?: return false
            Bypass.invoke<Any?>(
                am::class.java, am, "forceStopPackage", packageName, userId
            )
            true
        }.getOrElse {
            com.valhalla.thor.util.Logger.e(
                "DhizukuHelper",
                "forceStopApp failed for $packageName",
                it
            )
            false
        }
    }

    fun setAppDisabled(context: Context, packageName: String, disabled: Boolean): Boolean {
        Packages(context).getApplicationInfoOrNull(packageName) ?: return false
        val userId = Packages(context).myUserId
        val command = if (disabled) {
            "pm disable-user --user $userId $packageName"
        } else {
            "pm enable --user $userId $packageName"
        }
        val result = execute(command)

        if (result.first != 0) {
            // Fallback to Bypass reflection
            runCatching {
                val pm =
                    asInterface("android.content.pm.IPackageManager", "package") ?: return false
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
            }.onFailure {
                com.valhalla.thor.util.Logger.e(
                    "DhizukuHelper",
                    "setAppDisabled fallback failed for $packageName",
                    it
                )
            }
        }
        return Packages(context).isAppDisabled(packageName) == disabled
    }

    fun uninstallApp(packageName: String): Boolean {
        return execute(
            "pm uninstall --user current ${
                com.valhalla.superuser.ShellUtils.escapedString(
                    packageName
                )
            }"
        ).first == 0
    }

    fun execute(command: String): Pair<Int, String?> = runCatching {
        // Dhizuku 2.x supports newProcess for shell commands
        val process = DhizukuAPI.newProcess(arrayOf("sh", "-c", command), null, null)
        val exitCode = process.waitFor()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        exitCode to (output.ifBlank { error })
    }.getOrElse { -1 to it.stackTraceToString() }

    @SuppressLint("PrivateApi")
    fun clearCache(packageName: String): Boolean {
        val reflectionResult = runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package") ?: return false
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
                    android.os.Process.myUserHandle().hashCode(),
                    null
                )
            }
            true
        }.getOrDefault(false)

        if (reflectionResult) return true

        // Fallback to shell rm -rf on common cache paths
        val userId = android.os.Process.myUserHandle().hashCode()
        val paths = listOf(
            "/data/data/$packageName/cache",
            "/data/user/$userId/$packageName/cache",
            "/sdcard/Android/data/$packageName/cache"
        )
        val command = "rm -rf ${paths.joinToString(" ")}"
        return execute(command).first == 0
    }

    fun clearAppData(packageName: String): Boolean {
        val result = execute("pm clear $packageName")
        if (result.first == 0) return true

        // Fallback to reflection
        return runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package") ?: return false
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

        // Try reflection first through Dhizuku's binder wrapper to show proper branding
        if (suspended && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val reflectionResult = runCatching {
                val pm =
                    asInterface("android.content.pm.IPackageManager", "package") ?: return false
                val dialogInfoClass = Class.forName("android.content.pm.SuspendDialogInfo")
                val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
                val dialogInfo = Bypass.newInstance<Any>(builderClass).let { b ->
                    Bypass.invoke<Any>(builderClass, b, "setTitle", "Thor")
                    Bypass.invoke<Any>(
                        builderClass,
                        b,
                        "setMessage",
                        "This app has been suspended by Thor."
                    )
                    Bypass.invoke<Any>(builderClass, b, "build")
                }

                // In Dhizuku mode, we can try to use Thor's package name since it's a device owner proxy
                val caller = com.valhalla.thor.BuildConfig.APPLICATION_ID

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
                        arrayOf(packageName), true, null, null, dialogInfo, 0, caller, userId
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
                        arrayOf(packageName), true, null, null, dialogInfo, caller, userId
                    )
                }
                true
            }.getOrDefault(false)

            if (reflectionResult) return true
        }

        val command = if (suspended) {
            "pm suspend --user $userId $packageName"
        } else {
            "pm unsuspend --user $userId $packageName"
        }
        val result = execute(command)
        return result.first == 0
    }

    fun setAppRestricted(context: Context, packageName: String, restricted: Boolean): Boolean {
        val result =
            execute("appops set $packageName RUN_ANY_IN_BACKGROUND ${if (restricted) "ignore" else "allow"}")
        if (result.first == 0) return true

        // Fallback to reflection
        return runCatching {
            val appops =
                asInterface("com.android.internal.app.IAppOpsService", Context.APP_OPS_SERVICE)
                    ?: return false
            val userId = Packages(context).myUserId
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
