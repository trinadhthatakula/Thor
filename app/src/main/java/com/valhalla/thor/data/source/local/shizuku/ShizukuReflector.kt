@file:Suppress("unused")

package com.valhalla.thor.data.source.local.shizuku

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.IPackageInstaller
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.valhalla.bypass.Bypass
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.util.Logger

@SuppressLint("PrivateApi")
class ShizukuReflector(
    private val context: Context
) {

    fun clearCache(packageName: String): Boolean {
        return try {
            Shizuku.clearCache(packageName)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("ShizukuReflector", "clearCache failed: ${e.message}")
            false
        }
    }

    fun clearData(packageName: String): Boolean {
        return try {
            Shizuku.clearAppData(packageName)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("ShizukuReflector", "clearData failed: ${e.message}")
            false
        }
    }

    fun forceStop(packageName: String): Boolean {
        return try {
            Shizuku.forceStopApp(context, packageName)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("ShizukuReflector", "forceStop failed", e)
            false
        }
    }

    fun setAppEnabled(packageName: String, enabled: Boolean): Boolean {
        return try {
            Shizuku.setAppDisabled(context, packageName, !enabled)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("ShizukuReflector", "setAppEnabled failed", e)
            false
        }
    }

    fun packageUid(packageName: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.packageManager.getPackageUid(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
        ) else context.packageManager.getPackageUid(
            packageName,
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        )


    fun getApplicationInfoOrNull(
        packageName: String, flags: Int = PackageManager.MATCH_UNINSTALLED_PACKAGES
    ) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.packageManager.getApplicationInfo(
            packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong())
        )
        else context.packageManager.getApplicationInfo(packageName, flags)
    }.getOrNull()

    fun isAppDisabled(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.enabled?.not() ?: false

    fun isAppHidden(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        (Bypass.getField<Int>(it, "privateFlags")) and 1 == 1
    } ?: false

    fun isAppStopped(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.run { flags and ApplicationInfo.FLAG_STOPPED == ApplicationInfo.FLAG_STOPPED }
            ?: false

    fun isAppUninstalled(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.run { flags and ApplicationInfo.FLAG_INSTALLED != ApplicationInfo.FLAG_INSTALLED }
            ?: true

    fun isPrivilegedApp(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        (Bypass.getField<Int>(it, "privateFlags")) and 8 == 8
    } ?: false

    fun setAppRestricted(packageName: String, restricted: Boolean): Boolean = 
        Shizuku.setAppRestricted(packageName, restricted)

    fun uninstallApp(packageName: String, resetToFactory: Boolean = false): Boolean {
        val packageInfo = context.packageManager.getInfoForPackage(packageName) ?: return false
        val isSystem = (packageInfo.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val hasUpdates =
            (packageInfo.applicationInfo!!.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        val shouldReset = resetToFactory && isSystem && hasUpdates
        val broadcastIntent = Intent("io.github.samolego.canta.UNINSTALL_RESULT_ACTION")
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            broadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val packageInstaller = getPackageInstaller()

        // 0x00000004 = PackageManager.DELETE_SYSTEM_APP
        // 0x00000002 = PackageManager.DELETE_ALL_USERS
        val flags = if (isSystem) 0x00000004 else 0x00000002

        if (shouldReset) {
            try {

                Bypass.invoke<Any?>(
                    PackageInstaller::class.java,
                    packageInstaller,
                    "uninstall",
                    packageName,
                    flags,
                    intent.intentSender
                )

                try {
                    val updatedPackageInfo =
                        context.packageManager.getInfoForPackage(packageName) ?: return false
                    // Check if package still has updates; value intentionally ignored here
                    (updatedPackageInfo.applicationInfo!!.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                } catch (e: Exception) {
                    e.printStackTrace()
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }



        return try {
            Bypass.invoke<Any?>(
                PackageInstaller::class.java,
                packageInstaller,
                "uninstall",
                packageName,
                flags,
                intent.intentSender
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Installs an APK using the 'pm install' command via Shizuku.
     * Note: The file at [apkPath] must be readable by the shell user (e.g. /sdcard/).
     *
     * @param apkPath Absolute path to the APK file.
     * @param canDowngrade Whether to allow downgrade.
     * @return true if installation command exited with 0 (Success).
     */
    fun installPackage(apkPath: String, canDowngrade: Boolean = false): Boolean {
        return try {
            val command = "pm install -r -g${if (canDowngrade) " -d" else ""} ${com.valhalla.superuser.ShellUtils.escapedString(apkPath)}"
            val result = Shizuku.execute(command)
            result.first == 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Reinstalls app using Shizuku. See <a
     * href="https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/pm/PackageManagerShellCommand.java;drc=bcb2b436bde55ee40050400783a9c083e77ce2fe;l=1408>PackageManagerShellCommand.java</a>
     * @param packageName package name of the app to reinstall (must pre-install on the phone)
     */
    private fun reinstallApp(packageName: String): Boolean {
        val broadcastIntent = Intent("io.github.samolego.canta.INSTALL_RESULT_ACTION")
        val intent =
            PendingIntent.getBroadcast(
                context,
                0,
                broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )


        // PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS
        val installFlags = 0x00400000

        return try {
            val installReason = PackageManager.INSTALL_REASON_UNKNOWN
            Bypass.invoke<Any?>(
                IPackageInstaller::class.java,
                ShizukuPackageInstallerUtils.getPrivilegedPackageInstaller(),
                "installExistingPackage",
                packageName,
                installFlags,
                installReason,
                intent.intentSender,
                0,
                null
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getPackageInstaller(): PackageInstaller {
        val iPackageInstaller = ShizukuPackageInstallerUtils.getPrivilegedPackageInstaller()
        val root = try { rikka.shizuku.Shizuku.getUid() == 0 } catch (_: Exception) { false }
        val userId = if (root) android.os.Process.myUserHandle().hashCode() else 0

        // The reason for use "com.android.shell" as installer package under adb is that
        // getMySessions will check installer package's owner
        return ShizukuPackageInstallerUtils.createPackageInstaller(
            iPackageInstaller,
            "com.android.shell",
            userId
        )
    }

    /**
     * Create a privileged PackageInstaller using the provided installer package name.
     * This mirrors `getPackageInstaller()` but allows specifying the installer package
     * (so sessions can be created as belonging to the app's package).
     */
    fun createPackageInstallerFor(installerPackageName: String): PackageInstaller {
        val iPackageInstaller = ShizukuPackageInstallerUtils.getPrivilegedPackageInstaller()
        val root = try { rikka.shizuku.Shizuku.getUid() == 0 } catch (_: Exception) { false }
        val userId = if (root) android.os.Process.myUserHandle().hashCode() else 0

        return ShizukuPackageInstallerUtils.createPackageInstaller(
            iPackageInstaller,
            installerPackageName,
            userId
        )
    }
}
