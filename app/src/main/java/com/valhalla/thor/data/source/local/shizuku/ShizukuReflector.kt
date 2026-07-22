// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

@file:Suppress("unused")

package com.valhalla.thor.data.source.local.shizuku

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.valhalla.bypass.Bypass
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.util.Logger
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("PrivateApi")
@Single
class ShizukuReflector(
    val context: Context
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
        Shizuku.setAppRestricted(context, packageName, restricted)

    fun setAppSuspended(packageName: String, suspended: Boolean): Boolean =
        Shizuku.setAppSuspended(context, packageName, suspended)

    suspend fun uninstallApp(packageName: String, resetToFactory: Boolean = false): Boolean {
        // 1. Try shell first
        val shellResult = runCatching {
            Shizuku.uninstallApp(context, packageName)
        }.getOrElse {
            if (BuildConfig.DEBUG) {
                Logger.e("ShizukuReflector", "Shizuku.uninstallApp failed, trying fallbacks", it)
            }
            false
        }
        if (shellResult) return true

        // 2. Fallback to reflection
        val reflectionResult = runCatching {
            val packageInfo = context.packageManager.getInfoForPackage(packageName) ?: return false
            val isSystem =
                (packageInfo.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val hasUpdates =
                (packageInfo.applicationInfo!!.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            val shouldReset = resetToFactory && isSystem && hasUpdates
            // Namespace the result action + PendingIntent requestCode by the TARGET package so that
            // concurrent uninstalls of different packages (e.g. a multi-select bulk uninstall) each
            // register their own receiver / IntentSender and can't cross-deliver another package's
            // status (a shared constant action + requestCode 0 let the first result complete every
            // pending await, misreporting success/failure).
            val action = "${context.packageName}.UNINSTALL_RESULT_ACTION.$packageName"
            val broadcastIntent = Intent(action).apply {
                setPackage(context.packageName)
            }
            // The status receiver PendingIntent must be MUTABLE so PackageInstaller can fill in
            // EXTRA_STATUS at send time; an immutable PendingIntent drops those fill-in extras and
            // every uninstall would look like a failure. Pre-API 31 PendingIntents are mutable by
            // default, so no explicit flag is needed there.
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = PendingIntent.getBroadcast(
                context,
                packageName.hashCode(),
                broadcastIntent,
                pendingIntentFlags
            )
            val packageInstaller = getPackageInstaller()

            // PackageManager delete flags:
            //   0x00000002 = DELETE_ALL_USERS
            //   0x00000004 = DELETE_SYSTEM_APP (removes the pre-installed system version itself,
            //                not just its installed updates)
            // Reset-to-factory means rolling a system app back to its shipped version, which is a
            // plain removal of the installed updates, so DELETE_SYSTEM_APP must NOT be set there.
            val flags = when {
                shouldReset -> 0x00000002
                isSystem -> 0x00000004
                else -> 0x00000002
            }

            // Fire exactly one async uninstall (previously this ran twice for the reset path) and
            // observe its real outcome via the IntentSender broadcast instead of assuming success,
            // so a refusal (device policy / protected package) is no longer reported as success.
            awaitInstallerResult(action) {
                Bypass.invoke<Any?>(
                    PackageInstaller::class.java,
                    packageInstaller,
                    "uninstall",
                    packageName,
                    flags,
                    intent.intentSender
                )
            }
        }.getOrElse {
            // Don't let runCatching swallow coroutine cancellation (e.g. the ViewModel scope was
            // cancelled while awaiting the async uninstall result) and fall through to the
            // unprivileged ACTION_DELETE dialog — propagate it so the operation unwinds cleanly.
            if (it is CancellationException) throw it
            false
        }

        if (reflectionResult) return true

        // 3. Unprivileged fallback
        if (Packages(context).isAppUninstalled(packageName)) return true

        val launched = runCatching {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = "package:$packageName".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        if (!launched) return false

        // Poll for up to 20 seconds to see if the app gets uninstalled by the user
        for (i in 0 until 40) {
            kotlinx.coroutines.delay(500.milliseconds)
            if (Packages(context).isAppUninstalled(packageName)) {
                return true
            }
        }
        return false
    }

    /**
     * Registers a one-shot broadcast receiver for [action], runs [fire] (which must trigger an
     * async PackageInstaller/IPackageInstaller operation that reports back through the matching
     * IntentSender), then suspends until the result broadcast arrives or [timeoutMillis] elapses.
     * The receiver is always registered before [fire] runs so the async result can never be missed.
     *
     * @return true only when the operation reports [PackageInstaller.STATUS_SUCCESS]; false on
     * failure, refusal (e.g. device policy / protected package), pending user action, or timeout.
     */
    private suspend fun awaitInstallerResult(
        action: String,
        timeoutMillis: Long = 15_000L,
        fire: () -> Unit
    ): Boolean {
        val resultDeferred = CompletableDeferred<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, resultIntent: Intent) {
                resultDeferred.complete(
                    resultIntent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE
                    )
                )
            }
        }
        // The action is app-private (explicit package + custom action), hence RECEIVER_NOT_EXPORTED.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        return try {
            fire()
            withTimeoutOrNull(timeoutMillis.milliseconds) { resultDeferred.await() } ==
                PackageInstaller.STATUS_SUCCESS
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
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
            val command = "pm install -r -g${if (canDowngrade) " -d" else ""} ${
                apkPath.escapeForShell()
            }"
            val result = Shizuku.execute(command)
            result.first == 0
        } catch (e: Exception) {
            Logger.e("ShizukuReflector", "installPackage failed for $apkPath", e)
            false
        }
    }

    suspend fun reinstallExistingApp(packageName: String): Boolean {
        // 1. Try shell first
        if (Shizuku.reinstallApp(packageName)) return true

        // 2. Fallback to reflection
        return reinstallApp(packageName)
    }

    fun isSystemApp(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } ?: false

    /**
     * Reinstall app using Shizuku. See <a
     * href="https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/pm/PackageManagerShellCommand.java;drc=bcb2b436bde55ee40050400783a9c083e77ce2fe;l=1408>PackageManagerShellCommand.java</a>
     * @param packageName package name of the app to reinstall (must pre-install on the phone)
     */
    suspend fun reinstallApp(packageName: String): Boolean {
        // Namespace the result action + PendingIntent requestCode by the target package so
        // concurrent reinstalls don't cross-deliver each other's status (same as uninstallApp).
        val action = "${context.packageName}.INSTALL_RESULT_ACTION.$packageName"
        val broadcastIntent = Intent(action).apply {
            setPackage(context.packageName)
        }
        // MUTABLE so PackageInstaller can fill in EXTRA_STATUS at send time; an immutable
        // PendingIntent drops those fill-in extras, so the awaited result would always look failed.
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = PendingIntent.getBroadcast(
            context,
            packageName.hashCode(),
            broadcastIntent,
            pendingIntentFlags
        )

        // PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS
        val installFlags = 0x00400000
        val installReason = PackageManager.INSTALL_REASON_UNKNOWN

        return try {
            // Observe the REAL async outcome via the IntentSender instead of an immediate
            // isAppUninstalled() re-query: installExistingPackageAsUser runs on PackageManager-
            // Service's handler thread, so an immediate re-query races the state update and could
            // report a genuine success as a failure. awaitInstallerResult returns true only on
            // STATUS_SUCCESS (false on failure / refusal / timeout).
            awaitInstallerResult(action) {
                Bypass.invoke<Any?>(
                    // Resolve the real bootclasspath IPackageInstaller by name (not a bundled
                    // shadow type): R8 would rename a shadow `::class.java` ref in release, but the
                    // runtime uses the genuine framework class parent-first, so the reflected
                    // installExistingPackage lookup must target that same class.
                    Class.forName("android.content.pm.IPackageInstaller"),
                    ShizukuPackageInstallerUtils.getPrivilegedPackageInstaller(),
                    "installExistingPackage",
                    packageName,
                    installFlags,
                    installReason,
                    intent.intentSender,
                    0,
                    null
                )
            }
        } catch (e: Exception) {
            // Never swallow coroutine cancellation — it breaks cooperative cancellation of the caller.
            if (e is CancellationException) throw e
            Logger.e("ShizukuReflector", "reinstallApp failed for $packageName", e)
            false
        }
    }

    fun getPackageInstaller(): PackageInstaller {
        val iPackageInstaller = ShizukuPackageInstallerUtils.getPrivilegedPackageInstaller()
        val root = try {
            rikka.shizuku.Shizuku.getUid() == 0
        } catch (_: Exception) {
            false
        }
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
        val root = try {
            rikka.shizuku.Shizuku.getUid() == 0
        } catch (_: Exception) {
            false
        }
        val userId = if (root) android.os.Process.myUserHandle().hashCode() else 0

        return ShizukuPackageInstallerUtils.createPackageInstaller(
            iPackageInstaller,
            installerPackageName,
            userId
        )
    }
}
