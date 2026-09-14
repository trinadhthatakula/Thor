// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.dhizuku

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import com.valhalla.bypass.Bypass
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.backgroundRestrictionCommand
import com.valhalla.thor.data.source.local.isHiddenForUser
// The enable/disable rung machinery is privilege-agnostic; it lives in the `shizuku` package
// because that is where it was first needed, and it is imported rather than re-typed here so the
// two privilege modes cannot drift apart on "did the platform refuse?" — the one question whose
// wrong answer costs a user their app data. Same reason `Packages` is shared.
import com.valhalla.thor.data.source.local.shizuku.DisableOutcome
import com.valhalla.thor.data.source.local.shizuku.EnableRung
import com.valhalla.thor.data.source.local.shizuku.Packages
import com.valhalla.thor.data.source.local.shizuku.RUNG_REFLECTION
import com.valhalla.thor.data.source.local.shizuku.RUNG_SHELL
import com.valhalla.thor.data.source.local.shizuku.RUNG_UNPRIVILEGED
import com.valhalla.thor.data.source.local.shizuku.RungResult
import com.valhalla.thor.data.source.local.shizuku.SystemAppRemovalOutcome
import com.valhalla.thor.data.source.local.shizuku.firstRungThatSticks
import com.valhalla.thor.data.source.local.shizuku.isPolicyRefusal
import com.valhalla.thor.data.source.local.shizuku.shellRungResult
import com.valhalla.thor.data.source.local.thorUserId
import rikka.shizuku.SystemServiceHelper
import com.rosan.dhizuku.api.Dhizuku as DhizukuAPI
import com.valhalla.thor.util.Logger
import java.util.concurrent.TimeUnit

/**
 * What one availability probe learned: whether the client is bound, and whether Thor is authorised.
 *
 * Two fields because they are not the same answer and the caller latches only the first. `available`
 * is per-probe — the user can revoke in Dhizuku at any moment — while `initialised` is the binding,
 * which survives.
 */
internal data class DhizukuProbe(val initialised: Boolean, val available: Boolean)

/**
 * The probe's decision table, lifted out of [DhizukuHelper.isDhizukuAvailable] so it can be
 * exercised without a Dhizuku client — every call it makes is a static on `DhizukuAPI`, which is
 * why the logic and not the wiring is what gets tested.
 *
 * Two properties are the point, and both were wrong before:
 *
 * - **A failed [init] is not remembered.** It was never retried at all before this — the one attempt
 *   in `ThorApplication` ran at process start, which on a first run is before the user has
 *   authorised Thor. Latching that `false` would restore exactly the bug, one layer down.
 * - **A successful [init] is not repeated.** The probe runs on every privilege refresh, and every
 *   screen that shows a privilege chip; re-binding each time would be a service bind per probe.
 *
 * [isPermissionGranted] is consulted only once bound, because an unbound client answers `false` to
 * it regardless — which is what made the stale `false` look like a denied grant rather than a
 * missing connection.
 */
internal fun probeDhizuku(
    alreadyInitialised: Boolean,
    init: () -> Boolean,
    isPermissionGranted: () -> Boolean,
): DhizukuProbe {
    val initialised = try {
        alreadyInitialised || init()
    } catch (_: Exception) {
        return DhizukuProbe(initialised = alreadyInitialised, available = false)
    }
    // A throw here loses the authorisation answer, never the binding: the bind above already
    // succeeded, and forgetting it would make the next probe re-bind for nothing.
    val available = try {
        initialised && isPermissionGranted()
    } catch (_: Exception) {
        false
    }
    return DhizukuProbe(initialised = initialised, available = available)
}

/**
 * Helper to interact with Dhizuku service using the actual API.
 */
object DhizukuHelper {

    /**
     * Hang backstop for a single command: a stuck child is killed instead of pinning the caller
     * forever. Deliberately generous (5 min) because valid slow operations run through here —
     * notably `pm install` of large/split APKs on slow devices — and must not be killed. This
     * bounds infinite hangs, it does NOT enforce a tight SLA.
     */
    private const val EXECUTE_TIMEOUT_MS = 300_000L

    /** Grace period for reader threads to drain their streams after the process has exited/been destroyed. */
    private const val READER_JOIN_TIMEOUT_MS = 5_000L

    /**
     * Whether Dhizuku is connected *and* has authorised Thor.
     *
     * Re-runs `DhizukuAPI.init` when no connection has been established yet, and that is the whole
     * point of taking a [context]. `ThorApplication` initialises once at process start, which on a
     * first run is *before* the user has authorised Thor in Dhizuku; that bind is refused and
     * nothing ever retried it, so [DhizukuAPI.isPermissionGranted] answered `false` for the rest of
     * the process lifetime. The Privilege Check dialog tells the user to grant access and press
     * **Refresh** — and Refresh re-probes through exactly this function, so without the retry the
     * documented recovery could not work and only a force-stop would. Observed on an Android 17
     * device: grant, Refresh, still red; force-stop and relaunch, `active=DHIZUKU`.
     *
     * That Refresh reaches here at all is the other half of the same fix — it used to reload the
     * app list and nothing else, so the probe never re-ran; see `HomeViewModel.refreshPrivileges`.
     *
     * Shizuku needs no equivalent because `PrivilegeManager` owns its binder and
     * permission-result listeners; Dhizuku 2.6.0 publishes no connection callback to register, so
     * the retry has to be pulled from the probe rather than pushed from an event.
     *
     * Only a successful init is latched, so a failed attempt is retried on the next probe rather
     * than being remembered as a permanent no.
     */
    fun isDhizukuAvailable(context: Context): Boolean {
        val probe = probeDhizuku(
            alreadyInitialised = clientInitialised,
            init = { DhizukuAPI.init(context.applicationContext) },
            isPermissionGranted = { DhizukuAPI.isPermissionGranted() },
        )
        clientInitialised = probe.initialised
        return probe.available
    }

    // Written from whichever IO thread probes first and read by every later probe; @Volatile is
    // for safe publication of that hand-off, not for making the check-then-set atomic. Two probes
    // racing both call init(), which is idempotent, and both land on the same value.
    @Volatile
    private var clientInitialised = false

    /**
     * Records the outcome of the one-shot init `ThorApplication` runs at process start, so a
     * successful early bind is not re-attempted on the first probe.
     */
    fun markClientInitialised(initialised: Boolean) {
        clientInitialised = initialised
    }

    /**
     * Requests authorization from Dhizuku if installed and bound.
     *
     * Synchronous binder IPC — `DhizukuAPI.init` binds a service and `isPermissionGranted` is a
     * round-trip to another process — so callers must be off the main thread. The dialog itself is
     * asynchronous: [onResult] arrives on whichever thread Dhizuku's listener fires on.
     *
     * [context] is normalised to the application context at both `init` sites in this object,
     * because `DhizukuAPI` retains what it is handed in a static field for the life of the process.
     * A caller passing `LocalContext.current` — which the Privilege Check dialog does — would
     * otherwise pin an Activity there past every rotation.
     */
    fun requestPermission(context: Context, onResult: (Boolean) -> Unit) {
        try {
            if (!clientInitialised) {
                clientInitialised = DhizukuAPI.init(context.applicationContext)
            }
            if (DhizukuAPI.isPermissionGranted()) {
                onResult(true)
                return
            }
            DhizukuAPI.requestPermission(object : com.rosan.dhizuku.api.DhizukuRequestPermissionListener() {
                override fun onRequestPermission(grantResult: Int) {
                    onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            })
        } catch (e: Exception) {
            Logger.e("DhizukuHelper", "requestPermission failed", e)
            onResult(false)
        }
    }

    fun getSystemService(serviceName: String): IBinder? {
        return try {
            val binder = SystemServiceHelper.getSystemService(serviceName)
            DhizukuAPI.binderWrapper(binder)
        } catch (e: Exception) {
            Logger.e("DhizukuHelper", "Cannot obtain $serviceName through Dhizuku", e)
            null
        }
    }

    private fun asInterface(className: String, original: IBinder): Any {
        val clazz = Class.forName("$className\$Stub")
        return Bypass.invoke(
            clazz,
            null,
            "asInterface",
            arrayOf(IBinder::class.java),
            original
        )
    }

    private fun asInterface(className: String, serviceName: String): Any? {
        val binder = getSystemService(serviceName) ?: return null
        return asInterface(className, binder)
    }

    /**
     * Device-owner freeze keeps the APK and app data, but makes the package unavailable.
     * Dhizuku's app uid cannot disable other packages through pm/IPackageManager. Use its
     * device-policy binder directly; routing it through Shizuku requires a different service.
     */
    fun setAppHidden(context: Context, packageName: String, hidden: Boolean): Boolean {
        if (Packages(context).getApplicationInfoOrNull(packageName) == null) return false
        return try {
            val owner = DhizukuAPI.getOwnerComponent()
            val manager = devicePolicyManager(context)
            manager.setApplicationHidden(owner, packageName, hidden)
            // DPM can report "hidden" for a package that disappeared. Require a fresh package
            // read too, so absence cannot masquerade as a successful freeze.
            val verified = manager.isApplicationHidden(owner, packageName) == hidden &&
                Packages(context).getApplicationInfoOrNull(packageName)?.isHiddenForUser == hidden
            if (!verified) {
                Logger.w("DhizukuHelper", "setAppHidden($packageName, hidden=$hidden): state unchanged")
            }
            verified
        } catch (e: Exception) {
            Logger.e("DhizukuHelper", "setAppHidden($packageName, hidden=$hidden) failed", e)
            false
        }
    }

    // Intentional hidden-API bridge through :bypass; the public DPM methods keep version-specific
    // AIDL signatures in the framework. No cached Android service is modified.
    @SuppressLint("PrivateApi")
    internal fun devicePolicyManager(context: Context): DevicePolicyManager {
        val owner = DhizukuAPI.getOwnerComponent()
        val ownerContext = context.applicationContext.createPackageContext(owner.packageName, 0)
        val serviceClass = Class.forName("android.app.admin.IDevicePolicyManager")
        val binder = getSystemService(Context.DEVICE_POLICY_SERVICE)
            ?: throw IllegalStateException("Device policy service is unavailable through Dhizuku")
        val service = Bypass.invoke<Any>(
            Class.forName("android.app.admin.IDevicePolicyManager\$Stub"),
            null,
            "asInterface",
            arrayOf(IBinder::class.java),
            binder,
        )
        // A separate manager avoids changing Android's cached service. Its public methods
        // handle AIDL signature differences across Android versions. The context must name
        // the owner because DPM forwards its package name alongside the admin component.
        val manager = Bypass.newInstance<DevicePolicyManager>(
            DevicePolicyManager::class.java,
            arrayOf(Context::class.java, serviceClass),
            ownerContext,
            service,
        )
        return manager
    }

    /** Restore legacy system-app freezes through the device-owner API, retaining app data. */
    fun restoreSystemApp(context: Context, packageName: String): Boolean = try {
        devicePolicyManager(context).enableSystemApp(DhizukuAPI.getOwnerComponent(), packageName)
        Packages(context).getApplicationInfoOrNull(packageName)?.let {
            (it.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0
        } == true
    } catch (e: Exception) {
        Logger.e("DhizukuHelper", "restoreSystemApp($packageName) failed", e)
        false
    }

    /**
     * Enable/disable [packageName] for the current user, verified by re-reading the package state.
     *
     * Boolean for the callers that only need "did it work". The system-app freeze asks
     * [setAppDisabledDetailed] instead, because its next move turns on *why* this failed.
     */
    fun setAppDisabled(context: Context, packageName: String, disabled: Boolean): Boolean =
        setAppDisabledDetailed(context, packageName, disabled).succeeded

    /**
     * [setAppDisabled], plus whether the platform *refused* rather than merely failed.
     *
     * Only the preinstalled-app freeze needs the distinction, and it needs it because its next rung
     * is `pm uninstall -k --user N` — which keeps the app's data but clears its installed-for-this-
     * user bit, and so is worth reaching only where the platform left no alternative. See
     * `uninstallFreezeFallbackAllowed`.
     *
     * The three rungs are the ones this function always had; what changed is that each is now
     * verified by a re-read instead of by its own report, and that a `SecurityException` is carried
     * out of the chain instead of being flattened into "false". The chain machinery
     * ([EnableRung], [firstRungThatSticks], [RungResult], [DisableOutcome], [isPolicyRefusal],
     * [shellRungResult]) is shared with the Shizuku path rather than re-typed here: it is
     * privilege-agnostic and lives in the `shizuku` package only because that is where it was first
     * needed. Two copies of "did the platform refuse?" is exactly how the two privilege modes would
     * drift apart on the one decision that can cost a user their data.
     *
     * Only the shell rung may authorize policy-refusal escalation. A generic reflection failure
     * can still originate in transport setup rather than the platform's package policy.
     *
     * **Whose identity these rungs run as is not shell's.** `DhizukuAPI.newProcess` spawns `pm`
     * inside the device-owner app, and the reflection rung goes through the same app's binder
     * wrapper, so neither arrives at `PackageManagerService` as uid 2000. Measurements taken at
     * shell uid — including the Android 17 ones that say `pm disable-user` still works there — do
     * not transfer. That is precisely why nothing here trusts an exit code: the readback is the
     * only statement about this device that Thor can actually make.
     */
    fun setAppDisabledDetailed(
        context: Context,
        packageName: String,
        disabled: Boolean
    ): DisableOutcome {
        val pkgs = Packages(context)
        pkgs.getApplicationInfoOrNull(packageName)
            ?: return DisableOutcome(succeeded = false, refusedByPolicy = false)
        val userId = pkgs.myUserId
        // Escaped for the shell rung only; the reflection rung passes the raw name over binder.
        val escapedPackage = packageName.escapeForShell()

        // `pm disable-user` (COMPONENT_ENABLED_STATE_DISABLED_USER) and not `pm disable`: an
        // unprivileged caller may only move a whole package between DEFAULT, ENABLED and
        // DISABLED_USER, and the device-owner app is unprivileged in that sense — it holds no
        // CHANGE_COMPONENT_ENABLED_STATE either.
        val shellRung = EnableRung(RUNG_SHELL) {
            val command = if (disabled) {
                "pm disable-user --user $userId $escapedPackage"
            } else {
                "pm enable --user $userId $escapedPackage"
            }
            val (code, output) = execute(command)
            shellRungResult(code, output)
        }

        val reflectionRung = EnableRung(RUNG_REFLECTION) {
            runCatching {
                // Thrown rather than returned: an unreachable IPackageManager is a diagnosable
                // failure, and the getOrElse below is the only place it gets said out loud.
                val pm = asInterface("android.content.pm.IPackageManager", "package")
                    ?: throw IllegalStateException("IPackageManager is unreachable through Dhizuku")
                val newState = when {
                    !disabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                }
                Bypass.invoke<Any?>(
                    pm.javaClass,
                    pm,
                    "setApplicationEnabledSetting",
                    arrayOf(
                        String::class.java,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        String::class.java
                    ),
                    packageName,
                    newState,
                    0,
                    userId,
                    BuildConfig.APPLICATION_ID
                )
                RungResult.RAN
            }.getOrElse { e ->
                Logger.e(
                    "DhizukuHelper",
                    "setAppDisabled fallback reflection failed for $packageName (disabled=$disabled)",
                    e
                )
                // A Binder or reflection failure is not evidence authorizing a destructive
                // fallback. The shell rung separately records explicit platform refusals.
                RungResult.FAILED
            }
        }

        // Always last, and barely a rung: Thor holds no CHANGE_COMPONENT_ENABLED_STATE, so for any
        // package but its own this throws SecurityException and is swallowed. FAILED and never
        // REFUSED_BY_POLICY however loudly it throws — reporting *this* rung's refusal as a policy
        // refusal would hand the destructive fallback a permanent green light and undo the gate.
        val unprivilegedRung = EnableRung(RUNG_UNPRIVILEGED) {
            val newState = if (disabled) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            runCatching {
                context.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
                RungResult.RAN
            }.getOrElse { RungResult.FAILED }
        }

        // Keep the established recovery order; each rung still needs a package-state readback.
        val ordered = listOf(shellRung, reflectionRung, unprivilegedRung)

        // `Packages.isAppDisabled` folds FLAG_INSTALLED into `enabled`, so a package already frozen
        // by the uninstall rung reads as disabled here rather than as untouched. Unreadable answers
        // "not disabled", which fails this comparison in the freeze direction — the direction whose
        // failure can escalate to removing the package for the user.
        val outcome = firstRungThatSticks(ordered) { pkgs.isAppDisabled(packageName) == disabled }
        return if (outcome.winner != null) {
            Logger.d(
                "DhizukuHelper",
                "setAppDisabled($packageName, disabled=$disabled): ${outcome.winner} changed the state"
            )
            DisableOutcome(succeeded = true, refusedByPolicy = false)
        } else {
            Logger.e(
                "DhizukuHelper",
                "setAppDisabled($packageName, disabled=$disabled): all rungs ran " +
                    "(${ordered.joinToString { it.label }}) and the state did not change" +
                    if (outcome.refusedByPolicy) " — the platform REFUSED (SecurityException)" else ""
            )
            DisableOutcome(succeeded = false, refusedByPolicy = outcome.refusedByPolicy)
        }
    }

    // The user id every `--user` below names is [thorUserId], read in process.
    //
    // This helper used to shell out to `am get-current-user` here, and under Dhizuku that could
    // never work: `DhizukuAPI.newProcess` runs the command inside the **device-owner app**, and
    // `ActivityManager.getCurrentUser()` requires INTERACT_ACROSS_USERS, which that app does not
    // hold. Measured on an Android 17 device (Dhizuku at uid 10231):
    //
    //   SecurityException: Permission Denial: getCurrentUser() from pid=5209, uid=10231
    //     requires android.permission.INTERACT_ACROSS_USERS               -> exit 255
    //
    // The throw landed before `pm` ever ran, so rung 2 of the system-app freeze, the user-facing
    // uninstall and unfreeze/reinstall were all dead under Dhizuku — the last one silently, since
    // reinstallApp swallowed the cause. It predates the rung chain rather than regressing from it.
    //
    // Even where the shell call is permitted it answers the wrong question, and no cache is needed
    // for the answer that replaces it; both reasons are in [thorUserId]'s KDoc.

    /** Removes only the current user's app, awaiting the owner installer's actual result. */
    suspend fun uninstallApp(
        context: Context,
        packageName: String,
        timeoutMillis: Long = OWNER_OPERATION_TIMEOUT_MS,
    ): SystemAppRemovalOutcome = uninstallWithDeviceOwner(context, packageName, timeoutMillis)

    /**
     * Removes a **preinstalled** app for the current user *without* deleting its data — the last
     * rung of the system-app freeze, and deliberately not the same function as [uninstallApp].
     *
     * `-k` sets `DELETE_KEEP_DATA`, which leaves `/data/user/N/<pkg>` and `/data/user_de/N/<pkg>`
     * in place instead of having `installd` destroy them; measured on the Shizuku path, an
     * uninstall-with-`-k` followed by `pm install-existing` returns the app with byte-identical
     * `ceDataInode` and `deDataInode`. What it still costs unconditionally is `FLAG_INSTALLED`, so
     * the package stops resolving for this user unless the query carries
     * `MATCH_UNINSTALLED_PACKAGES`. That is why this is the *last* rung and not the first one.
     *
     * Kept separate from [uninstallApp] on purpose: adding `-k` there would silently make the
     * user-facing uninstall leave data behind on every app it removes. This is the same split
     * `Shizuku.freezeSystemAppForUser` already made, for the same reason.
     *
     * **Whose uid this runs as matters.** `DhizukuAPI.newProcess` spawns `pm` inside the
     * device-owner app, not inside shell and not as root, so on Android 17 it meets the same
     * `Binder.getCallingUid() == Process.ROOT_UID` guard in `PackageManagerShellCommand` that
     * refuses the shell uid, and answers with the same
     * `Failure [only root can delete system app for a particular user]`. That sentence is the most
     * useful string in the whole flow, so it is passed back to the caller rather than reduced to
     * false — see [SystemAppRemovalOutcome].
     *
     * Both claims above are now **measured**, on an Android 17 device with Dhizuku as device owner,
     * freezing `com.android.egg`. Rung 1 is refused for the device-owner uid while the same
     * `pm disable-user --user 0` exits 0 at shell uid on that very device, so the refusal belongs to
     * the identity and not to the platform version. This rung then ran and answered exactly as
     * predicted:
     * ```
     * `pm uninstall -k --user 0 com.android.egg` exited 1:
     *   Failure [only root can delete system app for a particular user]
     * ```
     * — the same sentence the shell uid gets, reaching the user as the Root-mode message. The
     * package was left untouched: `installed=true enabled=0`, `ceDataInode` unchanged.
     */
    fun freezeSystemAppForUser(packageName: String): SystemAppRemovalOutcome =
        removeForUser(packageName, keepData = true)

    /** Legacy data-preserving system-app removal; user-facing uninstall uses PackageInstaller. */
    private fun removeForUser(packageName: String, keepData: Boolean): SystemAppRemovalOutcome = try {
        val currentUser = thorUserId
        val keepDataFlag = if (keepData) "-k " else ""
        val (code, output) = execute(
            "pm uninstall $keepDataFlag--user $currentUser ${packageName.escapeForShell()}"
        )
        if (code != 0) {
            Logger.w(
                "DhizukuHelper",
                "`pm uninstall ${keepDataFlag}--user $currentUser $packageName` exited $code: $output"
            )
        }
        SystemAppRemovalOutcome(
            succeeded = code == 0,
            exitCode = code,
            platformMessage = output?.trim()?.takeIf { it.isNotBlank() },
        )
    } catch (e: Exception) {
        Logger.e("DhizukuHelper", "removeForUser($packageName, keepData=$keepData) failed", e)
        SystemAppRemovalOutcome(succeeded = false, exitCode = -1, platformMessage = e.message)
    }

    /**
     * The unfreeze half: restores a package that was removed for this user, data and all where `-k`
     * kept it.
     *
     * Still returns a `Boolean` — the caller re-reads `FLAG_INSTALLED` and reports on *that*, so
     * there is no message to carry out the way [removeForUser] carries one. But every way this can
     * answer `false` is now logged with its reason, which is the part that was missing. Measured on
     * an Android 17 Dhizuku device before the fix, the whole user-visible failure was
     * `unfreeze(com.android.egg): install-existing reported success=false` — the `SecurityException`
     * that actually caused it was swallowed by a bare `catch { false }` and appeared nowhere.
     */
    fun reinstallApp(packageName: String): Boolean {
        return try {
            val (code, output) = execute(
                "pm install-existing --user $thorUserId ${packageName.escapeForShell()}"
            )
            if (code != 0) {
                Logger.w(
                    "DhizukuHelper",
                    "`pm install-existing --user $thorUserId $packageName` exited $code: $output"
                )
            }
            code == 0
        } catch (e: Exception) {
            Logger.e("DhizukuHelper", "reinstallApp($packageName) failed", e)
            false
        }
    }

    fun execute(command: String): Pair<Int, String?> = runCatching {
        // Dhizuku 2.x supports newProcess for shell commands
        val process = DhizukuAPI.newProcess(arrayOf("sh", "-c", command), null, null)
        // Volatile via AtomicReference: the reader threads publish into these, and the timeout
        // path may read them after a join() that timed out (no happens-before), so a plain local
        // var could observe a stale/torn value.
        val output = java.util.concurrent.atomic.AtomicReference("")
        val error = java.util.concurrent.atomic.AtomicReference("")

        // Daemon so a stuck read on a hung child can never keep the process/VM alive.
        val outThread = Thread {
            runCatching {
                output.set(process.inputStream.bufferedReader().use { it.readText() })
            }.onFailure { err ->
                Logger.e("Dhizuku", "Failed to read standard output", err)
            }
        }.apply { isDaemon = true }

        val errThread = Thread {
            runCatching {
                error.set(process.errorStream.bufferedReader().use { it.readText() })
            }.onFailure { err ->
                Logger.e("Dhizuku", "Failed to read error output", err)
            }
        }.apply { isDaemon = true }

        var timedOut = false
        try {
            outThread.start()
            errThread.start()

            // Bounded wait: DhizukuRemoteProcess.waitFor(timeout, unit) delegates to a synchronous
            // binder transact() that does NOT respond to Thread.interrupt(). The ONLY bound is
            // EXECUTE_TIMEOUT_MS; this is a hang backstop, not coroutine-cancellation-interruptible.
            // The InterruptedException catch below is harmless defensive code, not a live path.
            val exited = try {
                process.waitFor(EXECUTE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Logger.e("Dhizuku", "Command wait interrupted: $command", e)
                false
            }

            if (exited) {
                val exitCode = process.exitValue()
                // Give the readers a bounded window to drain, then stop waiting on them.
                outThread.join(READER_JOIN_TIMEOUT_MS)
                errThread.join(READER_JOIN_TIMEOUT_MS)
                exitCode to combineProcessOutput(output.get(), error.get())
            } else {
                timedOut = true
                Logger.e(
                    "Dhizuku",
                    "Command timed out after ${EXECUTE_TIMEOUT_MS}ms, destroying process: $command"
                )
                // Close the FDs first: this unblocks the reader threads immediately, even if
                // destroyForcibly() (a binder call) later hangs. Killing before closing would
                // block the whole timeout path on a stuck destroy while the readers stay stuck.
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
                outThread.interrupt()
                errThread.interrupt()
                outThread.join(READER_JOIN_TIMEOUT_MS)
                errThread.join(READER_JOIN_TIMEOUT_MS)
                // Readers are already free; now request the (possibly slow) forcible kill.
                runCatching { process.destroyForcibly() }
                -1 to combineProcessOutput(
                    output.get(),
                    error.get(),
                    "Command timed out after ${EXECUTE_TIMEOUT_MS}ms",
                )
            }
        } finally {
            // Always tear the process down (idempotent even if already destroyed on timeout).
            if (!timedOut) runCatching { process.destroy() }
            // Close the FDs explicitly (each guarded): releases file descriptors and unblocks the
            // reader threads even if destroy()/destroyForcibly() (a binder call) hangs or fails.
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }.getOrElse { err ->
        Logger.e("Dhizuku", "Command execution failed: $command", err)
        -1 to err.stackTraceToString()
    }

    /** The public owner API supplies a real completion callback; issuing the request is not success. */
    suspend fun clearAppData(
        context: Context,
        packageName: String,
        timeoutMillis: Long = OWNER_OPERATION_TIMEOUT_MS,
    ): Boolean {
        return awaitPackageOperation<Boolean>(packageName, timeoutMillis) { completed ->
            devicePolicyManager(context).clearApplicationUserData(
                DhizukuAPI.getOwnerComponent(),
                packageName,
                java.util.concurrent.Executor { it.run() },
            ) { clearedPackage, succeeded -> completed(clearedPackage, succeeded) }
        } == true
    }

    /** Device-owner suspension is supported from API 24, including Thor's API 28 minimum. */
    fun setAppSuspended(context: Context, packageName: String, suspended: Boolean): Boolean = try {
        val packages = Packages(context)
        requireNotNull(packages.getApplicationInfoOrNull(packageName)) { "Package $packageName is unavailable" }
        val failed = devicePolicyManager(context).setPackagesSuspended(
            DhizukuAPI.getOwnerComponent(), arrayOf(packageName), suspended,
        )
        fun verified(): Boolean = packages.getApplicationInfoOrNull(packageName)?.let {
            (it.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED != 0) == suspended
        } == true
        if (packageName in failed) {
            false
        } else if (verified()) {
            true
        } else if (!suspended) {
            // Older Thor versions recorded suspension via pm as com.android.shell. Only lift that
            // legacy entry when DPM has removed the owner's entry but the aggregate flag remains.
            execute("pm unsuspend --user $thorUserId ${packageName.escapeForShell()}")
            verified()
        } else {
            false
        }
    } catch (e: Exception) {
        Logger.e("DhizukuHelper", "setAppSuspended($packageName, suspended=$suspended) failed", e)
        false
    }

    /** Policy grants are checked separately from the app's effective runtime grant. */
    fun setRuntimePermission(
        context: Context,
        packageName: String,
        permissionName: String,
        granted: Boolean,
    ): Boolean = try {
        val manager = devicePolicyManager(context)
        val owner = DhizukuAPI.getOwnerComponent()
        val state = if (granted) DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            else DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
        setAndVerifyPermissionState(
            granted = granted,
            setPolicy = { manager.setPermissionGrantState(owner, packageName, permissionName, state) },
            readPolicyMatches = { manager.getPermissionGrantState(owner, packageName, permissionName) == state },
            readGranted = {
                context.packageManager.checkPermission(permissionName, packageName) ==
                    PackageManager.PERMISSION_GRANTED
            },
        )
    } catch (e: Exception) {
        Logger.e("DhizukuHelper", "setRuntimePermission($packageName, $permissionName, $granted) failed", e)
        false
    }

    /** Require the effective app-op to match, including any overriding UID-level policy. */
    fun setAppRestricted(context: Context, packageName: String, restricted: Boolean): Boolean {
        // One expression for the mode both rungs write and the readback compares against, so a
        // future edit cannot set one thing and check for another. `allow` is `MODE_ALLOWED` and not
        // `MODE_DEFAULT`, and RUN_ANY_IN_BACKGROUND's platform default is `MODE_ALLOWED` too, so a
        // lifted restriction reads back as `MODE_ALLOWED` whether AppOpsService kept the entry or
        // dropped it as redundant.
        val expectedMode = if (restricted) {
            android.app.AppOpsManager.MODE_IGNORED
        } else {
            android.app.AppOpsManager.MODE_ALLOWED
        }

        val result = execute(
            backgroundRestrictionCommand(packageName.escapeForShell(), thorUserId, restricted)
        )
        if (result.first == 0) {
            val mode = readBackgroundMode(context, packageName)
            if (mode == expectedMode) return true
            Logger.w(
                "DhizukuHelper",
                "setAppRestricted($packageName, restricted=$restricted): `appops set` exited 0 but " +
                    "RUN_ANY_IN_BACKGROUND reads mode $mode, not $expectedMode — trying reflection"
            )
        }

        // Set only the package mode: clearing a UID override would also affect sibling apps.
        return runCatching {
            val appops =
                asInterface("com.android.internal.app.IAppOpsService", Context.APP_OPS_SERVICE)
                    ?: return@runCatching false
            val uid = Packages(context).packageUid(packageName)
            Bypass.invoke<Any?>(
                appops::class.java,
                appops,
                "setMode",
                arrayOf(
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                    Int::class.javaPrimitiveType!!
                ),
                runAnyInBackgroundOp(),
                uid,
                packageName,
                expectedMode
            )
            readBackgroundMode(context, packageName) == expectedMode
        }.getOrElse { false }
    }

    /**
     * `android:run_any_in_background` resolved to its op code.
     *
     * Lifted out of the `setMode` call it used to sit inside so that the rung that writes the op and
     * the readback that checks it cannot end up naming two different ops — which is the one way a
     * readback can turn from a verifier into a fabricated verdict. `strOpToOp` throws for a name the
     * platform does not know rather than answering a wrong code, so both callers' `runCatching`
     * still see a failure instead of a plausible number.
     */
    private fun runAnyInBackgroundOp(): Int = Bypass.invoke(
        android.app.AppOpsManager::class.java,
        null,
        "strOpToOp",
        "android:run_any_in_background"
    )

    private fun readBackgroundMode(context: Context, packageName: String): Int? =
        readAppOpMode(context, packageName, "android:run_any_in_background")

    /** Read through Dhizuku directly; AppOpsManager's process-wide cache uses an unwrapped service. */
    @SuppressLint("PrivateApi")
    internal fun readAppOpMode(context: Context, packageName: String, operation: String): Int? = try {
        val service = requireNotNull(asInterface("com.android.internal.app.IAppOpsService", Context.APP_OPS_SERVICE)) {
            "AppOps service is unavailable through Dhizuku"
        }
        val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
        val code = operation.toIntOrNull() ?: Bypass.invoke<Int>(
            AppOpsManager::class.java, null,
            if (operation.startsWith("android:")) "strOpToOp" else "strDebugOpToOp",
            arrayOf(String::class.java), operation,
        )
        val intType = Int::class.javaPrimitiveType!!
        val legacyTypes = arrayOf(intType, intType, String::class.java)
        val deviceTypes = arrayOf(intType, intType, String::class.java, intType)
        val attributionTypes = arrayOf(intType, intType, String::class.java, String::class.java, intType)
        val methods = Class.forName("com.android.internal.app.IAppOpsService").methods
        val legacy = methods.firstOrNull {
            it.name == "checkOperation" && it.parameterTypes.contentEquals(legacyTypes)
        }
        val attributed = methods.firstOrNull {
            it.name == "checkOperationForDevice" && it.parameterTypes.contentEquals(attributionTypes)
        }
        val device = methods.firstOrNull {
            it.name == "checkOperationForDevice" && it.parameterTypes.contentEquals(deviceTypes)
        }
        // Discover first and invoke only a matching signature. A missing platform method must not
        // send the reflection helper into its ART fallback; an unreadable result stays a failure.
        when {
            legacy != null -> legacy.invoke(service, code, uid, packageName) as Int
            attributed != null -> attributed.invoke(service, code, uid, packageName, null, 0) as Int
            device != null -> device.invoke(service, code, uid, packageName, 0) as Int
            else -> null
        }
    } catch (e: Exception) {
        Logger.d("DhizukuHelper", "Cannot verify app-op $operation for $packageName: $e")
        null
    }
}
