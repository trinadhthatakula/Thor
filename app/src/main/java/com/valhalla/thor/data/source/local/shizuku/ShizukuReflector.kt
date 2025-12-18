package com.valhalla.thor.data.source.local.shizuku

import android.annotation.SuppressLint
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.valhalla.thor.BuildConfig
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

@SuppressLint("PrivateApi")
class ShizukuReflector() {

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
        val amBinder = SystemServiceHelper.getSystemService("activity")
        val shizukuBinder = ShizukuBinderWrapper(amBinder)

        try {
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")

            // 1. Get Interface
            val asInterface = try {
                stubClass.getMethod("asInterface", IBinder::class.java)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    HiddenApiBypass.getDeclaredMethod(stubClass, "asInterface", IBinder::class.java)
                } else throw e
            }
            val am = asInterface.invoke(null, shizukuBinder)!!

            // 2. Find forceStopPackage
            val method = findMethod(
                am.javaClass,
                "forceStopPackage",
                String::class.java,
                Integer.TYPE
            ) ?: throw NoSuchMethodException("forceStopPackage not found")

            // 3. Invoke
            method.invoke(am, packageName, myUserId)

        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Log.e("ShizukuReflector", "forceStop failed", e)
        }
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        try {
            val newState = if (enabled) 1 else 2 // ENABLED vs DISABLED

            // Signature: setApplicationEnabledSetting(String appPackageName, int newState, int flags, int userId, String callingPackage)
            val method = findMethod(
                packageManager.javaClass,
                "setApplicationEnabledSetting",
                String::class.java,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                String::class.java
            ) ?: findMethod( // Fallback for older APIs
                packageManager.javaClass,
                "setApplicationEnabledSetting",
                String::class.java,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE
            ) ?: throw NoSuchMethodException("setApplicationEnabledSetting not found")

            if (method.parameterCount == 5) {
                method.invoke(
                    packageManager,
                    packageName,
                    newState,
                    0,
                    myUserId,
                    BuildConfig.APPLICATION_ID
                )
            } else {
                method.invoke(packageManager, packageName, newState, 0, myUserId)
            }
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
}