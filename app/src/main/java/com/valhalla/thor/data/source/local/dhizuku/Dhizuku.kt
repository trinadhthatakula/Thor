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

    fun forceStopApp(context: Context, packageName: String): Boolean = runCatching {
        val am = asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE) ?: return false
        Bypass.invoke<Any?>(
            am::class.java, am, "forceStopPackage", packageName, Packages(context).myUserId
        )
        true
    }.getOrElse {
        com.valhalla.thor.util.Logger.e("DhizukuHelper", "forceStopApp failed for $packageName", it)
        false
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
                val pm = asInterface("android.content.pm.IPackageManager", "package") ?: return false
                val newState = when {
                    !disabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                }
                Bypass.invoke<Any?>(
                    pm.javaClass,
                    pm,
                    "setApplicationEnabledSetting",
                    arrayOf(String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java),
                    packageName,
                    newState,
                    0,
                    userId,
                    BuildConfig.APPLICATION_ID
                )
            }.onFailure {
                com.valhalla.thor.util.Logger.e("DhizukuHelper", "setAppDisabled fallback failed for $packageName", it)
            }
        }
        return Packages(context).isAppDisabled(packageName) == disabled
    }

    fun uninstallApp( packageName: String): Boolean {
        return execute("pm uninstall --user current ${com.valhalla.superuser.ShellUtils.escapedString(packageName)}").first == 0
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

    fun clearAppData(packageName: String): Boolean =
        execute("pm clear $packageName").first == 0
}
