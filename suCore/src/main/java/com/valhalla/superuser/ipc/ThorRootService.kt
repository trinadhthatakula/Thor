package com.valhalla.superuser.ipc

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import com.valhalla.superuser.ipc.RootService
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * A highly-stable, persistent root-level daemon service implementing privileged actions.
 */
class ThorRootService : RootService() {

    override fun onBind(intent: Intent): IBinder {
        return object : IThorRootService.Stub() {
            private fun enforceCaller() {
                val callingUid = getCallingUid()
                val authorizedUid = com.valhalla.superuser.internal.RootServiceServer.getInstanceOrNull()?.authorizedUid ?: -1
                if (callingUid != 0 && callingUid != 1000 && callingUid != authorizedUid) {
                    throw SecurityException("Access denied: UID $callingUid is not authorized.")
                }
            }

            override fun setAppSuspended(packageName: String, suspended: Boolean) {
                enforceCaller()
                this@ThorRootService.setAppSuspended(packageName, suspended)
            }

            override fun clearAppData(packageName: String) {
                enforceCaller()
                this@ThorRootService.clearAppData(packageName)
            }
        }
    }

    private fun setAppSuspended(packageName: String, suspended: Boolean) {
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

            val myPkg = packageName // Use the target package itself or current package context
            try {
                callSetSuspended(
                    pmClass,
                    pm,
                    dialogInfoClass,
                    packageName,
                    suspended,
                    dialogInfo,
                    myPkg
                )
            } catch (e: Exception) {
                val cause = if (e is InvocationTargetException) e.cause else e
                if (cause is SecurityException) {
                    // Some devices reject non-privileged callers; fallback to com.android.shell
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
        }.onFailure { e ->
            android.util.Log.e("Odin", "Failed to set app suspended for $packageName", e)
        }
    }

    private fun callSetSuspended(
        pmClass: Class<*>, pm: Any?, dialogInfoClass: Class<*>,
        packageName: String, suspended: Boolean, dialogInfo: Any?, caller: String
    ) {
        // Android 14+ (API 34+): 9-arg signature
        try {
            pmClass.getDeclaredMethod(
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
            ).invoke(pm, arrayOf(packageName), suspended, null, null, dialogInfo, 0, caller, 0, 0)
            return
        } catch (_: NoSuchMethodException) {
            // fall through
        }

        // Some API 33 builds: 8-arg signature
        try {
            pmClass.getDeclaredMethod(
                "setPackagesSuspendedAsUser",
                Array<String>::class.java,
                Boolean::class.javaPrimitiveType,
                PersistableBundle::class.java,
                PersistableBundle::class.java,
                dialogInfoClass,
                Int::class.javaPrimitiveType,   // flags
                String::class.java,             // callingPackage
                Int::class.javaPrimitiveType    // userId
            ).invoke(pm, arrayOf(packageName), suspended, null, null, dialogInfo, 0, caller, 0)
            return
        } catch (_: NoSuchMethodException) {
            // fall through
        }

        // Android 10-13 (API 29-33): 7-arg signature
        pmClass.getDeclaredMethod(
            "setPackagesSuspendedAsUser",
            Array<String>::class.java, Boolean::class.javaPrimitiveType,
            PersistableBundle::class.java, PersistableBundle::class.java,
            dialogInfoClass, String::class.java, Int::class.javaPrimitiveType
        ).invoke(pm, arrayOf(packageName), suspended, null, null, dialogInfo, caller, 0)
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
            android.util.Log.e("Odin", "Failed to clear app data for $packageName", e)
        }
    }
}
