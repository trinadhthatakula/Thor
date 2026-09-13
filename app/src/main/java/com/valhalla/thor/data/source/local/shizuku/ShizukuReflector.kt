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
import com.valhalla.thor.data.source.local.SessionApk
import com.valhalla.thor.data.source.local.installViaSessionCommand
import com.valhalla.thor.data.source.local.privileged.PrivilegedInstallerTransport
import com.valhalla.thor.data.source.local.privileged.PrivilegedPackageInstallers
import com.valhalla.thor.data.source.local.privileged.SHELL_INSTALLER_PACKAGE_NAME
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.util.Logger
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single
import kotlin.time.Duration.Companion.milliseconds
import java.io.File

@SuppressLint("PrivateApi")
@Single
class ShizukuReflector(
    val context: Context
) {

    /**
     * There is no per-package `clearCache` here any more, and its absence is the finding rather than
     * a tidy-up: `INTERNAL_DELETE_CACHE_FILES` is `signature`-level, so no `pm grant` and no Shizuku
     * delegation can obtain it, and PMS answers the call by logging that it is silently ignoring it.
     * [Shizuku.trimCaches] is the whole-device operation that shell privilege *can* perform.
     */
    fun trimCaches(targetFreeBytes: Long): Boolean {
        return try {
            Shizuku.trimCaches(targetFreeBytes)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("ShizukuReflector", "trimCaches failed: ${e.message}")
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

    /**
     * [rungOrder] is passed straight through to [Shizuku.setAppDisabled] and defaults to the
     * historical shell-first order, so every existing call site keeps its behaviour. Only the
     * preinstalled-app freeze in `ShizukuSystemGateway` asks for [EnableRungOrder.REFLECTION_FIRST].
     */
    fun setAppEnabled(
        packageName: String,
        enabled: Boolean,
        rungOrder: EnableRungOrder = EnableRungOrder.SHELL_FIRST
    ): Boolean = setAppEnabledDetailed(packageName, enabled, rungOrder).succeeded

    /**
     * [setAppEnabled], plus whether the platform *refused* rather than merely failed.
     *
     * A thrown exception is reported as `refusedByPolicy = false`: everything that reaches this
     * catch got past the per-rung handling inside [Shizuku.setAppDisabledDetailed], so it is a
     * Shizuku-binder or reflection-plumbing problem rather than `PackageManagerService` saying no.
     * Guessing "refused" here would let a dead binder authorise deleting an app's data.
     */
    fun setAppEnabledDetailed(
        packageName: String,
        enabled: Boolean,
        rungOrder: EnableRungOrder = EnableRungOrder.SHELL_FIRST
    ): DisableOutcome {
        return try {
            Shizuku.setAppDisabledDetailed(context, packageName, !enabled, rungOrder)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG)
                Logger.e("ShizukuReflector", "setAppEnabled failed", e)
            DisableOutcome(succeeded = false, refusedByPolicy = false)
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

    // isAppDisabled() and isAppHidden() used to sit here. Neither had a caller, and both were wrong
    // for the freeze path that would eventually have been the one to call them — which is the worse
    // half: a dead helper that answers plausibly is a trap, not merely weight.
    //
    // isAppDisabled() read `enabled` and nothing else, so a system app frozen with
    // `pm uninstall -k --user N` — this build's gated fallback, and every uninstall-only build
    // before it — read back as *not* disabled. The test that survives is the conjunction, in
    // Packages.isAppDisabled and AppFreezeStateReader.candidateOf.
    //
    // isAppHidden() tested PRIVATE_FLAG_HIDDEN, which nothing in Thor can set: hiding a package is
    // DevicePolicyManager.setApplicationHidden, and there is not one DevicePolicyManager call in
    // app/src under any privilege mode. It could only ever answer false. The same pair was deleted
    // from DhizukuReflector; the definition of "frozen" now has one home per privilege mode.

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

    /**
     * The last rung of the system-app freeze: remove for this user, **keep the data**.
     *
     * Shell-only, with no reflection fallback, and that is the point. The `PackageInstaller`
     * fallback in [uninstallApp] cannot express `DELETE_KEEP_DATA` from here, so falling back to it
     * would silently turn a data-preserving freeze into a data-destroying one — precisely the bug
     * this whole change exists to remove. If the shell rung cannot do it, the freeze fails.
     *
     * Unlike every other method on this class it does not collapse to a `Boolean`. The reason it
     * failed is the only thing the caller can turn into a sentence worth showing — see
     * [SystemAppRemovalOutcome] — so it is passed through rather than reduced here.
     */
    fun freezeSystemAppForUser(packageName: String): SystemAppRemovalOutcome =
        Shizuku.freezeSystemAppForUser(packageName)

    suspend fun uninstallApp(packageName: String, resetToFactory: Boolean = false): Boolean {
        // 1. Try shell first
        val shellResult = runCatching {
            Shizuku.uninstallApp(packageName)
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
            // Read through, don't assert. [getInfoForPackage] now also answers for packages that
            // are not installed for this user — that widening is the whole reason this rung is
            // reachable for them — and an *archived* record is synthesised by PackageManager from
            // the archive state rather than parsed from an APK, so its ApplicationInfo is not the
            // same guaranteed object an ordinary lookup returns. A `!!` that throws here is caught
            // by the surrounding runCatching and reported as `false`, which would defeat the rung
            // on the very packages the widening was for. Absent flags mean "no evidence this is a
            // system app": isSystem and hasUpdates both read false, and the delete flags below fall
            // to 0 — a plain removal for the session's user, which is the safe reading of an
            // unknown.
            val appFlags = packageInfo.applicationInfo?.flags ?: 0
            val isSystem = (appFlags and ApplicationInfo.FLAG_SYSTEM) != 0
            val hasUpdates = (appFlags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

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
            //
            // No flag at all for an ordinary user app, where this used to set DELETE_ALL_USERS.
            // `PackageInstaller.uninstall` passes the installer's own `mUserId` — which
            // [getPackageInstaller] now binds to [thorUserId] — and DELETE_ALL_USERS overrides it,
            // so the corrected user id was inert for exactly the packages people uninstall. It also
            // put this rung at odds with the shell rung above it, which names the user: two rungs of
            // one operation removing the app for different sets of users depending on which one the
            // device happened to allow. Zero leaves the session's user in charge. On a single-user
            // device nothing changes — PMS removes a package outright once no other user holds it.
            //
            // DELETE_ALL_USERS stays on the reset path, where it is not a default but the operation:
            // the update APK it removes lives in /data/app and is shared by every user, so rolling
            // one user back to the shipped version is not a thing the platform can do.
            val flags = when {
                shouldReset -> 0x00000002
                isSystem -> 0x00000004
                else -> 0
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
     * Installs an APK through a `PackageInstaller` session via Shizuku, **for [thorUserId]**.
     * The file at [apkPath] must be readable by the shell user (e.g. `/sdcard/`).
     *
     * The command is built by [installViaSessionCommand] rather than written here. This one had no
     * caller when the user id was swept through the gateways, which is exactly why it is worth
     * naming a user in: a bare `pm install` is not "install for the shell's user" —
     * `makeInstallParams` leaves `params.userId = UserHandle.USER_ALL` and the session is created
     * with `INSTALL_ALL_USERS`, so the first person to call this would have installed the APK for
     * every user on the device without anything in the exit code saying so.
     *
     * It is also why the shape matters. This used to issue `pm install <path>`, and the note above
     * used to say shell-readable was the requirement. It is not the whole requirement: a path
     * argument is opened *for system_server*, so it must clear the shell's permissions and
     * system_server's SELinux domain. Streaming the bytes in leaves only the first.
     *
     * @param apkPath Absolute path to the APK file.
     * @param canDowngrade Whether to allow downgrade.
     * @param grantAllPermissions Whether to hand the package every runtime permission it declares,
     *   at install time, without asking — `pm install-create -g`, the GH#445 flag. Required, with
     *   no default, and that is the point: this class is a reflection layer with no
     *   `PreferenceRepository` and no `suspend` to read one from, so it cannot resolve the question
     *   itself. A default here would answer it silently for whoever calls this first. There is no
     *   such caller yet, so the cost of requiring an answer is zero and the cost of defaulting is
     *   an install that quietly disagrees with the user's setting.
     * @return true if installation command exited with 0 (Success).
     */
    fun installPackage(
        apkPath: String,
        canDowngrade: Boolean = false,
        grantAllPermissions: Boolean,
    ): Boolean {
        return try {
            val file = File(apkPath)
            val command = installViaSessionCommand(
                apks = listOf(
                    SessionApk(path = apkPath, sizeBytes = file.length(), name = file.name)
                ),
                userId = thorUserId,
                canDowngrade = canDowngrade,
                grantAllPermissions = grantAllPermissions,
            )
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
                    PrivilegedPackageInstallers.privilegedPackageInstaller(
                        PrivilegedInstallerTransport.SHIZUKU
                    ),
                    "installExistingPackage",
                    packageName,
                    installFlags,
                    installReason,
                    intent.intentSender,
                    // The userId argument, and the whole point of this rung being reached at all:
                    // it is the fallback for the `pm install-existing --user N` shell rung, so it
                    // has to restore the package for the same user that rung named. A literal 0
                    // here silently restored (and, in the uninstall mirror of this call, removed)
                    // the primary user's copy whenever Thor runs in a work profile.
                    thorUserId,
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

    /**
     * A privileged `PackageInstaller` for the **sessionless** operations — `uninstall` and friends,
     * which transact through the wrapped `IPackageInstaller` itself.
     *
     * Do not open a session on this. `PackageInstaller.openSession` hands the session binder to
     * `Session` unwrapped, so the writes leave as Thor's own uid while the session belongs to the
     * transport's; [com.valhalla.thor.data.source.local.privileged.PrivilegedPackageInstallers.handleFor]
     * is the entry point that pairs an installer with an opener that wraps.
     */
    fun getPackageInstaller(): PackageInstaller =
        PrivilegedPackageInstallers.packageInstaller(
            transport = PrivilegedInstallerTransport.SHIZUKU,
            // The reason for using the shell package as installer package under adb is that
            // getMySessions will check installer package's owner.
            installerPackageName = SHELL_INSTALLER_PACKAGE_NAME,
            // Thor's own user, unconditionally. This used to be `if (Shizuku.getUid() == 0) <this
            // user> else 0`, which asked what privilege Shizuku holds when the question is which
            // user the session acts on — and the two are unrelated. The `else 0` branch is the
            // normal setup (Shizuku at shell uid 2000), so on a work-profile device every operation
            // this installer carries was scoped to the primary user: `uninstall` with
            // DELETE_SYSTEM_APP and no DELETE_ALL_USERS bit removed a system app for user 0, the
            // profile the user never touched.
            userId = thorUserId,
        )
}
