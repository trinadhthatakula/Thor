package com.valhalla.thor.rootservice

import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import com.valhalla.superuser.ipc.RootService
import com.valhalla.superuser.utils.Logger
import com.valhalla.thor.BuildConfig
import java.lang.reflect.InvocationTargetException

/**
 * A highly-stable, persistent root-level daemon service implementing privileged actions.
 */
class ThorRootService : RootService() {

    override fun onBind(intent: Intent): IBinder {
        return object : IThorRootService.Stub() {
            override fun setAppSuspended(packageName: String, suspended: Boolean) {
                this@ThorRootService.enforceCaller()
                this@ThorRootService.setAppSuspended(packageName, suspended)
            }

            override fun clearAppData(packageName: String) {
                this@ThorRootService.enforceCaller()
                this@ThorRootService.clearAppData(packageName)
            }
        }
    }

    private fun setAppSuspended(packageName: String, suspended: Boolean) {
        Logger.i("Odin", "setAppSuspended: packageName=$packageName, suspended=$suspended")
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "package") as IBinder
            val pm = Class.forName("android.content.pm.IPackageManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            val pmClass = Class.forName("android.content.pm.IPackageManager")

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw UnsupportedOperationException("suspend via reflection requires API 29+")
            }

            val dialogInfoClass = Class.forName("android.content.pm.SuspendDialogInfo")
            val dialogInfo = if (suspended) buildSuspendDialogInfo() else null

            // Try different caller packages in order of preference to find one that succeeds.
            // Prioritize Thor's own package name so that the OS displays "Managed by Thor" on clicking a suspended app.
            val callers = listOf(
                this@ThorRootService.packageName,
                BuildConfig.APPLICATION_ID,
                "com.android.shell",
                "android"
            )
            var success = false

            for (caller in callers) {
                try {
                    if (callSetSuspended(pmClass, pm, dialogInfoClass, packageName, suspended, dialogInfo, caller)) {
                        Logger.i("Odin", "setAppSuspended successfully suspended $packageName using caller $caller")
                        success = true
                        break
                    } else {
                        Logger.w("Odin", "setAppSuspended failed for $packageName using caller $caller (returned in failed list)")
                    }
                } catch (e: Exception) {
                    val cause = if (e is InvocationTargetException) e.cause else e
                    Logger.w("Odin", "setAppSuspended threw exception for $packageName using caller $caller: " + cause?.message)
                }
            }

            if (!success) {
                throw RuntimeException("All caller package strategies failed to suspend $packageName")
            }
        }.onFailure { e ->
            Logger.e("Odin", "Failed to set app suspended for $packageName", e)
        }
    }

    private fun callSetSuspended(
        pmClass: Class<*>, pm: Any?, dialogInfoClass: Class<*>,
        packageName: String, suspended: Boolean, dialogInfo: Any?, caller: String
    ): Boolean {
        // Android 14+ (API 34+): 9-arg signature
        try {
            Logger.i("Odin", "Trying API 34+ 9-arg signature with caller=$caller")
            val method = pmClass.getDeclaredMethod(
                "setPackagesSuspendedAsUser",
                Array<String>::class.java,
                Boolean::class.javaPrimitiveType,
                PersistableBundle::class.java,
                PersistableBundle::class.java,
                dialogInfoClass,
                Int::class.javaPrimitiveType,   // flags
                String::class.java,             // callingPackage
                Int::class.javaPrimitiveType,   // suspendingUserId
                Int::class.javaPrimitiveType    // targetUserId
            )
            val result = method.invoke(pm, arrayOf(packageName), suspended, null, null, dialogInfo, 0, caller, 0, 0) as? Array<*>
            val failedList = result?.filterIsInstance<String>() ?: emptyList()
            Logger.i("Odin", "Successfully invoked API 34+ 9-arg signature. Failed packages: $failedList")
            return !failedList.contains(packageName)
        } catch (e: NoSuchMethodException) {
            Logger.d("Odin", "API 34+ signature not found: " + e.message)
        } catch (e: Exception) {
            Logger.e("Odin", "API 34+ signature invocation error", e)
            throw e
        }

        // Some API 33 builds: 8-arg signature
        try {
            Logger.i("Odin", "Trying API 33 8-arg signature with caller=$caller")
            val method = pmClass.getDeclaredMethod(
                "setPackagesSuspendedAsUser",
                Array<String>::class.java,
                Boolean::class.javaPrimitiveType,
                PersistableBundle::class.java,
                PersistableBundle::class.java,
                dialogInfoClass,
                Int::class.javaPrimitiveType,   // flags
                String::class.java,             // callingPackage
                Int::class.javaPrimitiveType    // userId
            )
            val result = method.invoke(pm, arrayOf(packageName), suspended, null, null, dialogInfo, 0, caller, 0) as? Array<*>
            val failedList = result?.filterIsInstance<String>() ?: emptyList()
            Logger.i("Odin", "Successfully invoked API 33 8-arg signature. Failed packages: $failedList")
            return !failedList.contains(packageName)
        } catch (e: NoSuchMethodException) {
            Logger.d("Odin", "API 33 signature not found: " + e.message)
        } catch (e: Exception) {
            Logger.e("Odin", "API 33 signature invocation error", e)
            throw e
        }

        // Android 10-13 (API 29-33): 7-arg signature
        try {
            Logger.i("Odin", "Trying API 29-33 7-arg signature with caller=$caller")
            val method = pmClass.getDeclaredMethod(
                "setPackagesSuspendedAsUser",
                Array<String>::class.java, Boolean::class.javaPrimitiveType,
                PersistableBundle::class.java, PersistableBundle::class.java,
                dialogInfoClass, String::class.java, Int::class.javaPrimitiveType
            )
            val result = method.invoke(pm, arrayOf(packageName), suspended, null, null, dialogInfo, caller, 0) as? Array<*>
            val failedList = result?.filterIsInstance<String>() ?: emptyList()
            Logger.i("Odin", "Successfully invoked API 29-33 7-arg signature. Failed packages: $failedList")
            return !failedList.contains(packageName)
        } catch (e: Exception) {
            Logger.e("Odin", "API 29-33 signature invocation error", e)
            throw e
        }
    }

    private fun buildSuspendDialogInfo(): Any? = try {
        val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
        val builder = builderClass.getDeclaredConstructor().newInstance()
        builderClass.getMethod("setTitle", CharSequence::class.java).invoke(builder, "Thor")
        builderClass.getMethod("setMessage", CharSequence::class.java)
            .invoke(builder, "This app has been suspended by Thor.")
        builderClass.getMethod("setNeutralButtonAction", Int::class.javaPrimitiveType)
            .invoke(builder, 2)
        builderClass.getMethod("build").invoke(builder)
    } catch (_: Exception) {
        null
    }

    private fun clearAppData(packageName: String) {
        runCatching {
            val pmStub = Class.forName("android.content.pm.IPackageManager\$Stub")
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "package") as IBinder
            val asInterface = pmStub.getMethod("asInterface", IBinder::class.java)
            val pm = asInterface.invoke(null, binder)
            val pmClass = Class.forName("android.content.pm.IPackageManager")

            val method = pmClass.getDeclaredMethod(
                "clearApplicationUserData",
                String::class.java,
                Class.forName("android.content.pm.IPackageDataObserver"),
                Int::class.javaPrimitiveType
            )
            method.invoke(pm, packageName, null, 0)
        }.onFailure { e ->
            Logger.e("Odin", "Failed to clear app data for $packageName", e)
        }
    }
}
