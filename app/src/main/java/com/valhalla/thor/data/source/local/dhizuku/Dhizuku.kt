package com.valhalla.thor.data.source.local.dhizuku

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.shizuku.Packages
import com.valhalla.thor.data.source.local.shizuku.Targets
import org.lsposed.hiddenapibypass.HiddenApiBypass
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

    private fun asInterface(className: String, original: IBinder): Any =
        Class.forName("$className\$Stub").run {
            if (Targets.P) HiddenApiBypass.invoke(
                this,
                null,
                "asInterface",
                ShizukuBinderWrapper(original)
            )
            else getMethod("asInterface", IBinder::class.java).invoke(
                null,
                ShizukuBinderWrapper(original)
            )
        }

    private fun asInterface(className: String, serviceName: String): Any? {
        val binder = getSystemService(serviceName) ?: return null
        return asInterface(className, binder)
    }

    fun forceStopApp(context: Context, packageName: String): Boolean = runCatching {
        val am = asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE) ?: return false
        if (Targets.P) HiddenApiBypass.invoke(
            am::class.java, am, "forceStopPackage", packageName, Packages(context).myUserId
        ) else am::class.java.getMethod(
            "forceStopPackage", String::class.java, Int::class.java
        ).invoke(
            am, packageName, Packages(context).myUserId
        )
        true
    }.getOrElse {
        it.printStackTrace()
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
            pm::class.java.getMethod(
                "setApplicationEnabledSetting",
                String::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                String::class.java
            ).invoke(
                pm,
                packageName,
                newState,
                0,
                Packages(context).myUserId,
                BuildConfig.APPLICATION_ID
            )
        }.onFailure {
            it.printStackTrace()
        }
        return Packages(context).isAppDisabled(packageName) == disabled
    }

    fun uninstallApp(context: Context, packageName: String): Boolean {
        return execute("pm uninstall --user current $packageName").first == 0
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.invoke(
                    pm::class.java,
                    pm,
                    "deleteApplicationCacheFiles",
                    packageName,
                    null /* IPackageDataObserver */
                )
            } else {
                val method = pm::class.java.getMethod(
                    "deleteApplicationCacheFiles",
                    String::class.java,
                    Class.forName("android.content.pm.IPackageDataObserver")
                )
                method.invoke(pm, packageName, null)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}