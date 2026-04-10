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
        if (disabled) forceStopApp(context, packageName)
        runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package") ?: return false
            val newState = when {
                !disabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
            Bypass.invoke<Any?>(
                pm::class.java,
                pm,
                "setApplicationEnabledSetting",
                packageName,
                newState,
                0,
                Packages(context).myUserId,
                BuildConfig.APPLICATION_ID
            )
        }.onFailure {
            com.valhalla.thor.util.Logger.e("DhizukuHelper", "setAppDisabled failed for $packageName", it)
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
        return try {
            val pm = asInterface("android.content.pm.IPackageManager", "package") ?: return false

            Bypass.invoke<Any?>(
                pm::class.java,
                pm,
                "deleteApplicationCacheFiles",
                packageName,
                null /* IPackageDataObserver */
            )
            true
        } catch (e: Exception) {
            com.valhalla.thor.util.Logger.e("DhizukuHelper", "clearCache failed for $packageName", e)
            false
        }
    }
}
