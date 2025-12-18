package com.valhalla.thor.data.source.local.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import com.valhalla.thor.BuildConfig
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import kotlin.jvm.java

/**
 * ISOLATION CHAMBER
 * * This class handles all the ugly reflection and AIDL stub wrapping required
 * to make system calls via Shizuku.
 * * If code breaks because Android 16 changed an API, YOU FIX IT HERE ONLY.
 */
class ShizukuReflector(private val context: Context) {

    private val myUserId: Int
        get() = android.os.Process.myUserHandle().hashCode()

    // --- Helpers to get System Services via Shizuku ---

    private fun getPackageManager(): Any {
        val binder = SystemServiceHelper.getSystemService("package")
        val pmBinder = ShizukuBinderWrapper(binder)
        // IPackageManager.Stub.asInterface(pmBinder)
        return Class.forName("android.content.pm.IPackageManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, pmBinder)!!
    }

    private fun getActivityManager(): Any {
        val binder = SystemServiceHelper.getSystemService("activity")
        val amBinder = ShizukuBinderWrapper(binder)
        // IActivityManager.Stub.asInterface(amBinder)
        return Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, amBinder)!!
    }

    // --- Implementation of Actions ---

    fun forceStop(packageName: String) {
        val am = getActivityManager()

        // Method signature changed in newer Android versions if I recall,
        // but your old code used a consistent one. We stick to reflection to be safe.

        // void forceStopPackage(String packageName, int userId)
        val method = am.javaClass.getMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
        method.invoke(am, packageName, myUserId)
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        val pm = getPackageManager()
        val newState = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER

        // setApplicationEnabledSetting(String appPackageName, int newState, int flags, int userId, String callingPackage)
        // Note: The signature varies slightly by Android version regarding the callingPackage or userId order.
        // Your old code handled this via "Targets". I'm simplifying to the standard modern signature.

        try {
            // Try standard signature first
            val method = pm.javaClass.getMethod(
                "setApplicationEnabledSetting",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            method.invoke(pm, packageName, newState, 0, myUserId, BuildConfig.APPLICATION_ID)
        } catch (e: NoSuchMethodException) {
            // Fallback for older APIs (Pre-Android 10/Q often lacked the callingPackage or had different order)
            val method = pm.javaClass.getMethod(
                "setApplicationEnabledSetting",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.invoke(pm, packageName, newState, 0, myUserId)
        }
    }

    @SuppressLint("PrivateApi")
    fun clearCache(packageName: String) {
        val pm = getPackageManager()

        // 1. Get the Hidden Class Reference
        val observerClass = try {
            Class.forName("android.content.pm.IPackageDataObserver")
        } catch (e: ClassNotFoundException) {
            // Should never happen on standard Android, but good to know
            throw RuntimeException("Target Android version removed IPackageDataObserver")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // 2. Look up the method using the Class reference, NOT the type
                val method = pm.javaClass.getMethod(
                    "deleteApplicationCacheFiles",
                    String::class.java,
                    observerClass // <--- This was your error. Use the class object we looked up.
                )
                // 3. Invoke it. We pass 'null' for the observer because we don't need the callback yet.
                method.invoke(pm, packageName, null)
            } catch (e: Exception) {
                // Fallback to Bypass if strict reflection fails
                HiddenApiBypass.invoke(
                    pm.javaClass,
                    pm,
                    "deleteApplicationCacheFiles",
                    packageName,
                    null
                )
            }
        } else {
            // Older versions logic
            val method = pm.javaClass.getMethod(
                "deleteApplicationCacheFiles",
                String::class.java,
                observerClass
            )
            method.invoke(pm, packageName, null)
        }
    }
}