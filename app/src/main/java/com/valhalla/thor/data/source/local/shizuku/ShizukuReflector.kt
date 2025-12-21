package com.valhalla.thor.data.source.local.shizuku

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.valhalla.thor.BuildConfig
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

@SuppressLint("PrivateApi")
class ShizukuReflector(
    private val context: Context
) {

    private val myUserId: Int
        get() = android.os.Process.myUserHandle().hashCode()

    // Cache the IPackageManager instance
    private val packageManager: Any by lazy {
        val binder = SystemServiceHelper.getSystemService("package")
        val shizukuBinder = ShizukuBinderWrapper(binder)

        try {
            val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
            // Try standard reflection first
            val method = try {
                stubClass.getMethod("asInterface", IBinder::class.java)
            } catch (e: Exception) {
                // If hidden, find via Bypass
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    HiddenApiBypass.getDeclaredMethod(stubClass, "asInterface", IBinder::class.java)
                } else throw e
            }
            method.invoke(null, shizukuBinder)!!
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Log.e("ShizukuReflector", "Failed to create IPackageManager proxy", e)
        }
    }

    @SuppressLint("PrivateApi")
    fun clearCache(packageName: String) {
        try {
            val observerClass = Class.forName("android.content.pm.IPackageDataObserver")

            // 1. Find Method (using Bypass if needed)
            val method = findMethod(
                packageManager.javaClass,
                "deleteApplicationCacheFiles",
                String::class.java,
                observerClass
            ) ?: findMethod( // Fallback for some ROMs
                packageManager.javaClass,
                "deleteApplicationCacheFilesAsUser",
                String::class.java,
                Integer.TYPE, // Replaced Int::class.javaPrimitiveType
                observerClass
            ) ?: throw NoSuchMethodException("deleteApplicationCacheFiles not found")

            // 2. Invoke (Standard Java Invoke)
            if (method.parameterCount == 2) {
                method.invoke(packageManager, packageName, null)
            } else {
                method.invoke(packageManager, packageName, myUserId, null)
            }

        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Log.e("ShizukuReflector", "clearCache failed: ${e.message}")
        }
    }

    fun forceStop(packageName: String) {

        try {
            Shizuku.forceStopApp(context, packageName)

        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Log.e("ShizukuReflector", "forceStop failed", e)
        }
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        try {
            Shizuku.setAppDisabled(context, packageName, !enabled)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Log.e("ShizukuReflector", "setAppEnabled failed", e)
        }
    }

    // Helper to find method using standard reflection OR Bypass
    private fun findMethod(
        clazz: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? {
        return try {
            clazz.getMethod(name, *parameterTypes)
        } catch (_: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    HiddenApiBypass.getDeclaredMethod(clazz, name, *parameterTypes)
                } catch (_: Exception) {
                    null
                }
            } else null
        }
    }

    fun packageUri(packageName: String) = "package:$packageName"

    fun packageUid(packageName: String) = if (Build.VERSION.SDK_INT>= Build.VERSION_CODES.TIRAMISU) context.packageManager.getPackageUid(
        packageName, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
    ) else context.packageManager.getPackageUid(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)


    fun getApplicationInfoOrNull(
        packageName: String, flags: Int =PackageManager.MATCH_UNINSTALLED_PACKAGES
    ) = runCatching {
        if (Build.VERSION.SDK_INT>= Build.VERSION_CODES.TIRAMISU) context.packageManager.getApplicationInfo(
            packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong())
        )
        else context.packageManager.getApplicationInfo(packageName, flags)
    }.getOrNull()

    fun isAppDisabled(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.enabled?.not() ?: false

    fun isAppHidden(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        (ApplicationInfo::class.java.getField("privateFlags").get(it) as Int) and 1 == 1
    } ?: false

    fun isAppStopped(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.run { flags and ApplicationInfo.FLAG_STOPPED == ApplicationInfo.FLAG_STOPPED }
            ?: false

    fun isAppUninstalled(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.run { flags and ApplicationInfo.FLAG_INSTALLED != ApplicationInfo.FLAG_INSTALLED }
            ?: true

    fun isPrivilegedApp(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        (ApplicationInfo::class.java.getField("privateFlags").get(it) as Int) and 8 == 8
    } ?: false

    fun setAppDisabled(packageName: String, disabled: Boolean): Boolean {
        runCatching {
            val newState = when {
                !disabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
        }.onFailure {
            it.printStackTrace()
        }
        return isAppDisabled(packageName) == disabled
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean): Boolean = runCatching {
        context.getSystemService<AppOpsManager>()?.let {
            HiddenApiBypass.invoke(
                it::class.java,
                it,
                "setMode",
                "android:run_any_in_background",
                packageUid(packageName),
                packageName,
                if (restricted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
            )
        }
        true
    }.getOrElse {
        it.printStackTrace()
        false
    }

}