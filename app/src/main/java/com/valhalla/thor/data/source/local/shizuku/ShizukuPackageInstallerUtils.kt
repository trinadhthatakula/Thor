package com.valhalla.thor.data.source.local.shizuku

import android.content.pm.IPackageInstaller
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import com.valhalla.bypass.Bypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Taken from <a href="https://github.com/depau/fdroid_shizuku_privileged_extension/blob/main/app/src/main/java/org/fdroid/fdroid/privileged/ShizukuPackageInstallerUtils.kt">FDroid Priv</a>.
 */
object ShizukuPackageInstallerUtils {

    private fun asInterface(className: String, binder: IBinder): Any {
        val clazz = Class.forName("$className\$Stub")
        return Bypass.invoke(
            clazz,
            null,
            "asInterface",
            arrayOf(IBinder::class.java),
            ShizukuBinderWrapper(binder)
        )
    }

    fun getPrivilegedPackageInstaller(): IPackageInstaller {
        val pmBinder = SystemServiceHelper.getSystemService("package")
        val pm = asInterface("android.content.pm.IPackageManager", pmBinder)

        val packageInstallerProxy = Bypass.invoke<Any>(
            pm.javaClass,
            pm,
            "getPackageInstaller"
        )

        val binder = (packageInstallerProxy as IInterface).asBinder()
        return asInterface("android.content.pm.IPackageInstaller", binder) as IPackageInstaller
    }

    /**
     * Taken from https://github.com/RikkaApps/Shizuku-API/blob/01e08879d58a5cb11a333535c6ddce9f7b7c88ff/demo/src/main/java/rikka/shizuku/demo/util/PackageInstallerUtils.java#L15
     * @author RikkaW
     */
    fun createPackageInstaller(
        installer: IPackageInstaller?,
        installerPackageName: String?,
        userId: Int
    ): PackageInstaller {
        val iPackageInstallerClass = Class.forName("android.content.pm.IPackageInstaller")
        return if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            Bypass.newInstance(
                PackageInstaller::class.java,
                arrayOf(
                    iPackageInstallerClass,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType!!
                ),
                installer, installerPackageName, null, userId
            )
        } else {
            Bypass.newInstance(
                PackageInstaller::class.java,
                arrayOf(iPackageInstallerClass, String::class.java, Int::class.javaPrimitiveType!!),
                installer, installerPackageName, userId
            )
        }
    }
}
