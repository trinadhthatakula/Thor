package com.valhalla.thor.data.source.local.shizuku

import android.annotation.SuppressLint
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import com.valhalla.bypass.Bypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Taken from <a href="https://github.com/depau/fdroid_shizuku_privileged_extension/blob/main/app/src/main/java/org/fdroid/fdroid/privileged/ShizukuPackageInstallerUtils.kt">FDroid Priv</a>.
 *
 * Intentionally resolves hidden framework classes (IPackageManager / IPackageInstaller) via
 * Class.forName so it can drive the privileged installer through Shizuku — PrivateApi is suppressed
 * object-wide as this reflection is the whole purpose of the helper.
 */
@SuppressLint("PrivateApi")
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

    // Returns the privileged IPackageInstaller as an opaque android.os.IInterface handle rather
    // than the framework's hidden android.content.pm.IPackageInstaller type. The concrete
    // interface is never called directly here — the handle is only passed on to reflective
    // (Bypass) calls — so referencing it by a bundled compile-time shadow is unnecessary and, in
    // release builds, actively harmful: R8 renames the shadow and rewrites `IPackageInstaller`
    // type/`::class` references to it, but at runtime the real bootclasspath class wins parent-first
    // → reflection against the renamed shadow fails (NoSuchMethod/NoSuchField). Using IInterface +
    // Class.forName strings keeps every reference resolving to the genuine framework class.
    fun getPrivilegedPackageInstaller(): IInterface {
        val pmBinder = SystemServiceHelper.getSystemService("package")
        val pm = asInterface("android.content.pm.IPackageManager", pmBinder)

        val packageInstallerProxy = Bypass.invoke<Any>(
            pm.javaClass,
            pm,
            "getPackageInstaller"
        )

        val binder = (packageInstallerProxy as IInterface).asBinder()
        return asInterface("android.content.pm.IPackageInstaller", binder) as IInterface
    }

    /**
     * Taken from https://github.com/RikkaApps/Shizuku-API/blob/01e08879d58a5cb11a333535c6ddce9f7b7c88ff/demo/src/main/java/rikka/shizuku/demo/util/PackageInstallerUtils.java#L15
     * @author RikkaW
     */
    fun createPackageInstaller(
        installer: IInterface?,
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
