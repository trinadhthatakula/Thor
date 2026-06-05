package com.valhalla.thor.data.source.local.root

import android.os.Build
import android.os.IBinder
import com.valhalla.thor.BuildConfig
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/**
 * Entry point for one-shot Root commands.
 * This runs in a separate process as root, bypassing Hidden API restrictions.
 */
object RootMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) return

        try {
            when (args[0]) {
                "suspend" -> {
                    val packageName = args[1]
                    val suspended = args[2].toBoolean()
                    setAppSuspended(packageName, suspended)
                }

                "clear-data" -> {
                    val packageName = args[1]
                    clearData(packageName)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            exitProcess(1)
        }
        exitProcess(0)
    }

    private fun setAppSuspended(packageName: String, suspended: Boolean) {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "package") as IBinder
        val pm = Class.forName("android.content.pm.IPackageManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
        val pmClass = Class.forName("android.content.pm.IPackageManager")

        // SuspendDialogInfo and its method overloads were introduced in API 29
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("suspend via reflection requires API 29+")
        }

        val dialogInfoClass = Class.forName("android.content.pm.SuspendDialogInfo")
        val dialogInfo = if (suspended) buildSuspendDialogInfo() else null

        try {
            callSetSuspended(
                pmClass,
                pm,
                dialogInfoClass,
                packageName,
                suspended,
                dialogInfo,
                BuildConfig.APPLICATION_ID
            )
        } catch (e: Exception) {
            // invoke() wraps the actual exception in InvocationTargetException; unwrap to check
            val cause = if (e is InvocationTargetException) e.cause else e
            if (cause is SecurityException) {
                // Some devices reject non-privileged callers even from UID 0; fall back to shell
                callSetSuspended(
                    pmClass,
                    pm,
                    dialogInfoClass,
                    packageName,
                    suspended,
                    dialogInfo,
                    "com.android.shell"
                )
            } else throw e
        }
    }

    private fun callSetSuspended(
        pmClass: Class<*>, pm: Any?, dialogInfoClass: Class<*>,
        packageName: String, suspended: Boolean, dialogInfo: Any?, caller: String
    ) {
        try {
            // Android 13+ (API 33): 8-arg — extra flags Int between dialogInfo and caller
            pmClass.getDeclaredMethod(
                "setPackagesSuspendedAsUser",
                Array<String>::class.java,
                Boolean::class.javaPrimitiveType,
                android.os.PersistableBundle::class.java,
                android.os.PersistableBundle::class.java,
                dialogInfoClass,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType
            ).invoke(pm, arrayOf(packageName), suspended, null, null, dialogInfo, 0, caller, 0)
        } catch (_: NoSuchMethodException) {
            // Android 10-12 (API 29-32): 7-arg
            pmClass.getDeclaredMethod(
                "setPackagesSuspendedAsUser",
                Array<String>::class.java, Boolean::class.javaPrimitiveType,
                android.os.PersistableBundle::class.java, android.os.PersistableBundle::class.java,
                dialogInfoClass, String::class.java, Int::class.javaPrimitiveType
            ).invoke(pm, arrayOf(packageName), suspended, null, null, dialogInfo, caller, 0)
        }
    }

    private fun buildSuspendDialogInfo(): Any? = try {
        val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
        val builder = builderClass.getDeclaredConstructor().newInstance()
        builderClass.getMethod("setTitle", CharSequence::class.java).invoke(builder, "Thor")
        builderClass.getMethod("setMessage", CharSequence::class.java)
            .invoke(builder, "This app has been suspended by Thor.")
        // Suppress the neutral button — prevents an unresolved intent from crashing SystemUI
        // BUTTON_ACTION_NO_ACTION = 2
        builderClass.getMethod("setNeutralButtonAction", Int::class.javaPrimitiveType)
            .invoke(builder, 2)
        builderClass.getMethod("build").invoke(builder)
    } catch (_: Exception) {
        null
    }

    private fun clearData(packageName: String) {
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
    }
}
