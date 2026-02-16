@file:Suppress("unused")

package com.valhalla.thor.data.source.local.shizuku

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.IPackageInstaller
import android.content.pm.IPackageManager
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.getSystemService
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.util.Logger
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

    private val packageManager: IPackageManager by lazy {
        // This is needed to access hidden methods in IPackageManager
        HiddenApiBypass.addHiddenApiExemptions(
            "Landroid/content/pm"
        )

        IPackageManager.Stub.asInterface(
            ShizukuBinderWrapper(
                SystemServiceHelper.getSystemService(
                    "package"
                )
            )
        )
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
                Logger.e("ShizukuReflector", "clearCache failed: ${e.message}")
        }
    }

    fun forceStop(packageName: String) {
        try {
            // Use the local Shizuku object helper
            Shizuku.forceStopApp(context, packageName)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("ShizukuReflector", "forceStop failed", e)
        }
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        try {
            // Use the local Shizuku object helper
            Shizuku.setAppDisabled(context, packageName, !enabled)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("ShizukuReflector", "setAppEnabled failed", e)
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
            try {
                HiddenApiBypass.getDeclaredMethod(clazz, name, *parameterTypes)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun packageUri(packageName: String) = "package:$packageName"

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

                HiddenApiBypass.invoke(
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
                    val stillHasUpdates =
                        (updatedPackageInfo.applicationInfo!!.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                } catch (e: Exception) {
                    e.printStackTrace()
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }



        return try {
            HiddenApiBypass.invoke(
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
            val downgradeFlag = if (canDowngrade) "-d" else ""
            val command = "pm install -r -g $downgradeFlag ${com.valhalla.superuser.ShellUtils.escapedString(apkPath)}"
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
            HiddenApiBypass.invoke(
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
        val root = rikka.shizuku.Shizuku.getUid() == 0
        val userId = if (root) android.os.Process.myUserHandle().hashCode() else 0

        // The reason for use "com.android.shell" as installer package under adb is that
        // getMySessions will check installer package's owner
        return ShizukuPackageInstallerUtils.createPackageInstaller(
            iPackageInstaller,
            "com.android.shell",
            userId
        )
    }

}