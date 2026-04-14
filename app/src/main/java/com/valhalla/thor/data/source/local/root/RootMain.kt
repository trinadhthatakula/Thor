package com.valhalla.thor.data.source.local.root

import android.os.Build
import android.os.IBinder
import com.valhalla.thor.BuildConfig

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
            System.exit(1)
        }
        System.exit(0)
    }

    private fun setAppSuspended(packageName: String, suspended: Boolean) {
        val pmStub = Class.forName("android.content.pm.IPackageManager\$Stub")
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "package") as IBinder
        val asInterface = pmStub.getMethod("asInterface", IBinder::class.java)
        val pm = asInterface.invoke(null, binder)
        val pmClass = Class.forName("android.content.pm.IPackageManager")

        val userId = 0 // Root

        if (suspended && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val dialogInfoClass = Class.forName("android.content.pm.SuspendDialogInfo")
            val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            
            builderClass.getMethod("setTitle", CharSequence::class.java).invoke(builder, "Thor")
            builderClass.getMethod("setMessage", CharSequence::class.java).invoke(builder, "This app has been suspended by Thor.")
            val dialogInfo = builderClass.getMethod("build").invoke(builder)

            val caller = "com.android.shell" // Use shell identity for better compatibility from root

            try {
                // Android 13+ (8 args)
                val method = pmClass.getDeclaredMethod(
                    "setPackagesSuspendedAsUser",
                    Array<String>::class.java,
                    Boolean::class.javaPrimitiveType,
                    android.os.PersistableBundle::class.java,
                    android.os.PersistableBundle::class.java,
                    dialogInfoClass,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                method.invoke(pm, arrayOf(packageName), true, null, null, dialogInfo, 0, caller, userId)
            } catch (e: NoSuchMethodException) {
                // Android 10-12 (7 args)
                val method = pmClass.getDeclaredMethod(
                    "setPackagesSuspendedAsUser",
                    Array<String>::class.java,
                    Boolean::class.javaPrimitiveType,
                    android.os.PersistableBundle::class.java,
                    android.os.PersistableBundle::class.java,
                    dialogInfoClass,
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                method.invoke(pm, arrayOf(packageName), true, null, null, dialogInfo, caller, userId)
            }
        } else {
            // Unsuspend logic
            val suspendDialogInfoClass = Class.forName("android.content.pm.SuspendDialogInfo")
            val method = pmClass.getDeclaredMethod(
                "setPackagesSuspendedAsUser",
                Array<String>::class.java,
                Boolean::class.javaPrimitiveType,
                android.os.PersistableBundle::class.java,
                android.os.PersistableBundle::class.java,
                suspendDialogInfoClass,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(pm, arrayOf(packageName), suspended, null, null, null, "com.android.shell", userId)
        }
    }

    private fun clearData(packageName: String) {
        val pmStub = Class.forName("android.content.pm.IPackageManager\$Stub")
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "package") as IBinder
        val asInterface = pmStub.getMethod("asInterface", IBinder::class.java)
        val pm = asInterface.invoke(null, binder)
        val pmClass = Class.forName("android.content.pm.IPackageManager")

        val method = pmClass.getDeclaredMethod("clearApplicationUserData", String::class.java, Class.forName("android.content.pm.IPackageDataObserver"), Int::class.javaPrimitiveType)
        method.invoke(pm, packageName, null, 0)
    }
}
