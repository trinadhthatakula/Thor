// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.dhizuku

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import com.valhalla.bypass.Bypass
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.backgroundRestrictionCommand
import com.valhalla.thor.data.source.local.clearAppDataCommand
import com.valhalla.thor.data.source.local.clearCachePaths
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
import com.valhalla.thor.domain.model.SHELL_SUSPENDER_IDENTITY
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import com.rosan.dhizuku.api.Dhizuku as DhizukuAPI
import com.valhalla.thor.util.Logger
import com.valhalla.thor.R
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
            init = { DhizukuAPI.init(context) },
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

    fun getSystemService(serviceName: String): IBinder? {
        return try {
            val binder = SystemServiceHelper.getSystemService(serviceName)
            DhizukuAPI.binderWrapper(binder)
        } catch (_: Exception) {
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
            ShizukuBinderWrapper(original)
        )
    }

    private fun asInterface(className: String, serviceName: String): Any? {
        val binder = getSystemService(serviceName) ?: return null
        return asInterface(className, binder)
    }

    // KILL_BACKGROUND_PROCESSES is satisfied via elevated privilege (Dhizuku device-owner /
    // shell), not a manifest grant, so the framework permission check is not applicable here.
    @SuppressLint("MissingPermission")
    fun forceStopApp(context: Context, packageName: String): Boolean {
        val pkgs = Packages(context)
        val userId = pkgs.myUserId
        // 1. Shell first — and its exit code is not evidence of anything, in either direction.
        // `ActivityManagerShellCommand.runForceStop` ends in an unconditional `return 0`, so exit 0
        // says the command parsed, never that a process died. The honest verifier was already
        // written in this function — `pkgs.isAppStopped`, rung 3 below — and sat unreachable behind
        // this short-circuit. Do not "simplify" the readback back out.
        //
        // **Do not read this rung as the live one.** `execute` runs `am` inside the *Dhizuku* app:
        // `DhizukuAPI.newProcess` is an AIDL call to `IDhizuku.remoteProcess`, so the child is
        // spawned by the device-owner app at its own ordinary app uid. AMS gates what that child
        // then asks for — `ActivityManagerService.forceStopPackage` opens on
        // `checkCallingPermission(FORCE_STOP_PACKAGES)`, a `signature|privileged` permission that
        // holding device owner does not confer — so it throws SecurityException,
        // `ShellCommand.exec` prints that to stderr and leaves its `res` at -1, and `am` exits 255
        // without `runForceStop` ever reaching its `return 0`. The `&&` below therefore
        // short-circuits, and `pkgs.isAppStopped` is not called on this rung at all.
        //
        // That chain is AOSP-derived rather than measured for `force-stop` itself; its identity
        // half is measured on device. The same binary at the same Dhizuku uid is recorded further
        // down this file being refused `am get-current-user` — `Permission Denial … uid=10231`,
        // exit 255 — which is this exact shape one command over.
        //
        // The readback stays anyway: it costs nothing on a rung that short-circuits, and it is the
        // guard for the one case that would otherwise lie — an exit 0 from a transport that killed
        // nothing, which is what a ROM or a Dhizuku build that does hold the permission would
        // produce. Nothing is lost when it never runs, because rung 3 re-reads FLAG_STOPPED
        // unconditionally: one verifier, reached whichever rung did the work.
        //
        // The `newProcess` identity fact lives in [setAppDisabledDetailed]'s **KDoc**, where it is
        // a caveat — neither of its rungs reaches `PackageManagerService` as uid 2000, which is
        // precisely why nothing there trusts an exit code — and not in its reflection rung, whose
        // note says the opposite about *itself*: double-wrapped binder, dead on a Dhizuku-only
        // device.
        val result = execute("am force-stop --user $userId $packageName")
        if (result.first == 0 && pkgs.isAppStopped(packageName)) return true

        // 2. Fallback to reflection, and nothing reads its result any more. All it could ever report
        // was that the invoke did not throw, which through `asInterface`'s double-wrapped binder is
        // not a statement about ActivityManagerService at all — fixing rung 1 and leaving
        // `if (reflectionResult) return true` underneath it would have been the same defect one line
        // lower. The line is deleted rather than gated because rung 3 already re-reads FLAG_STOPPED
        // unconditionally: one verifier, reached whichever rung did the work.
        runCatching {
            val am = asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE)
            if (am != null) {
                Bypass.invoke<Any?>(
                    am::class.java, am, "forceStopPackage", packageName, userId
                )
            }
        }.onFailure {
            Logger.e(
                "DhizukuHelper",
                "forceStopApp reflection failed for $packageName",
                it
            )
        }

        // 3. Unprivileged fallback (re-query PM to observe post-mutation state)
        if (pkgs.isAppStopped(packageName)) return true
        runCatching {
            val am =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.killBackgroundProcesses(packageName)
        }
        return pkgs.isAppStopped(packageName)
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
     * What is deliberately *not* shared is which rungs may report a refusal at all. Only the shell
     * rung may here — see the reflection rung's own note for why its exceptions describe a
     * transport rather than a policy.
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
                // FAILED however loudly it throws, and unlike its Shizuku twin — for the same reason
                // the unprivileged rung below is FAILED. This rung does not reach
                // PackageManagerService as the device owner: `asInterface` double-wraps the binder,
                // Dhizuku's own wrapper and then `ShizukuBinderWrapper` on top of it, so on a
                // Dhizuku-only device the call dies in a transport that belongs to a privilege mode
                // the user has not set up. Some of those deaths *are* SecurityExceptions, and
                // reading one as "PackageManagerService refuses this device" would hand
                // `uninstallFreezeFallbackAllowed` a green light for `pm uninstall -k` on the
                // strength of Shizuku not being installed.
                //
                // Nothing diagnostic is lost. A genuine refusal of the device-owner identity shows
                // up one rung earlier — the shell rung runs `pm` inside the device-owner app via
                // DhizukuAPI.newProcess, so PMS's SecurityException reaches us as `pm`'s own output
                // and a non-zero exit — and `firstRungThatSticks` keeps that refusal sticky for the
                // rest of the chain.
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

        // Shell first, the order Dhizuku has always used. Shizuku's freeze path flips to
        // reflection-first for the reason `EnableRungOrder` gives, which is not about identity:
        // both of its rungs reach PackageManagerService as the same uid, and so do both of these.
        // What it buys there is skipping a `pm` round trip — ordinary userspace an OEM is free to
        // modify or lock down — in favour of a call that names its target state and its caller
        // explicitly. That argument transfers here unchanged, so the order is kept only because
        // flipping it would be a behaviour change with nothing measured behind it and no Dhizuku
        // device to measure on.
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

    /**
     * The user-facing "uninstall this app" action: removes [packageName] for the current user
     * **and its data with it**, which is what somebody who asked to uninstall an app wants.
     *
     * No `-k` here, deliberately. [freezeSystemAppForUser] is the one that keeps the data, and the
     * two are separate functions precisely so neither flag can drift onto the other path. Until
     * this change they were the same function: the system-app freeze called *this* one, so every
     * Dhizuku freeze of a preinstalled app destroyed its data, on every release, silently.
     *
     * Returns what `pm` said rather than a Boolean. The exit code is reported, never judged — the
     * caller re-reads FLAG_INSTALLED, because `pm uninstall` is not a reliable narrator of whether
     * the package is still installed for this user in either direction.
     */
    fun uninstallApp(packageName: String): SystemAppRemovalOutcome =
        removeForUser(packageName, keepData = false)

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

    /**
     * The one `pm uninstall` invocation, with the one flag that separates the two callers above.
     *
     * Shared rather than duplicated so the two paths cannot drift in anything *but* [keepData] —
     * the parameter has no default for the same reason. Nothing here interprets the result: both
     * callers re-read the package state, since the exit code lies in both directions (`pm` can exit
     * 0 having changed nothing, and can exit non-zero having done the work).
     */
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
                exitCode to (output.get().ifBlank { error.get() })
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
                -1 to "Command timed out after ${EXECUTE_TIMEOUT_MS}ms".let { msg ->
                    output.get().ifBlank { error.get() }.ifBlank { msg }
                }
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

    /**
     * Deletes [packageName]'s cache directories **for [thorUserId]** — shell first, then a
     * hidden-API `IPackageManager` call.
     *
     * The reflection overloads are tried `…AsUser` first for the same reason as
     * [com.valhalla.thor.data.source.local.shizuku.Shizuku.clearCache], whose KDoc carries the full
     * argument: `deleteApplicationCacheFiles(String, IPackageDataObserver)` is declared on every
     * release this app runs on (28 through 37), so the `NoSuchMethodException` meant to reach the
     * `…AsUser` branch never fires. Ordered the other way round, the only branch that names a user
     * is unreachable and the branch that runs names none — `PackageManagerService` implements the
     * no-user overload as `deleteApplicationCacheFilesAsUser(packageName, getCallingUserId(),
     * observer)`, resolving the user from the *caller*. Here the caller is the Dhizuku app holding
     * device owner, so from a work profile the reachable rung cleared the cache of whichever user
     * Dhizuku itself runs as, for a package the user picked in another profile.
     *
     * The fallback is gated on `NoSuchMethodException` alone, never `Exception`: a *refusal* of the
     * per-user call must not fall through to one that clears a different user's cache and reports it
     * as this user's success.
     *
     * How often this rung runs at all is a separate question, and the answer is probably never:
     * `asInterface` wraps the binder twice — Dhizuku's own wrapper, then `ShizukuBinderWrapper` on
     * top — so on a Dhizuku-only device the call dies in a transport belonging to a privilege mode
     * the user has not set up. `setAppDisabledDetailed` carries that argument in full. It is a
     * reason not to expect the reorder to change behaviour today, not a reason to keep the order
     * wrong: a rung that is dead now is still the rung a future transport fix would wake up.
     *
     * **A `true` from this function now comes from the shell rung or from nowhere.** Only that rung
     * is honest — `rm -rf` exits 0 or it does not — and the reflective one has stopped claiming
     * otherwise. Its `true` was never evidence: the call is asynchronous, PMS reports through the
     * `IPackageDataObserver` passed as `null` here, and on a Dhizuku-only device it does not reach
     * PMS at all. It is still issued, because on a device that also has Shizuku it may genuinely
     * run; what changed is that "the binder call returned" is no longer allowed to reach the user as
     * "the cache is gone".
     */
    // SdCardPath: absolute system paths are intentional for privileged/root file ops on app data
    // dirs (not app-scoped storage). PrivateApi: hidden-API reflection is the core privilege
    // mechanism, guarded by the :bypass VMRuntime unseal.
    @SuppressLint("PrivateApi", "SdCardPath")
    fun clearCache(packageName: String): Boolean {
        // 1. Try shell first. The `/data/data/<pkg>/cache` and `/sdcard/Android/data/<pkg>/cache`
        // aliases that used to sit either side of these two are gone: they are not extra coverage,
        // they are the same directories *for user 0*, so from a secondary user they deleted another
        // user's cache. At user 0 they resolve to exactly what remains.
        val command =
            "rm -rf ${clearCachePaths(packageName.escapeForShell(), thorUserId).joinToString(" ")}"
        val shellResult = execute(command)
        if (shellResult.first == 0) return true

        // 2. Fallback to reflection — issued, and deliberately never believed.
        //
        // `asInterface` wraps the binder twice, Dhizuku's own wrapper and then
        // `ShizukuBinderWrapper` on top, so on a Dhizuku-only device this call dies inside a
        // transport belonging to a privilege mode the user never set up; [setAppDisabledDetailed]'s
        // reflection rung carries that argument in full. The call stays — it costs nothing, and on a
        // device that *also* has Shizuku it may genuinely run — but the `false` at the end of the
        // block replaces a `true` that has never meant the cache was cleared. Even on a live
        // transport it could not mean that: `deleteApplicationCacheFiles*` reports through the
        // `IPackageDataObserver` passed as `null` here, so the old `true` only ever said "the binder
        // call returned".
        //
        // An observer is deliberately not wired up in its place. On the device this rung actually
        // runs on the call never reaches PackageManagerService, so a real observer would buy one
        // guaranteed timeout per package — an always-red answer that teaches the user nothing and
        // costs seconds each on a bulk clear. Honest and fast beats verified and impossible. If the
        // transport is ever fixed, *that* is the change that earns an observer here.
        val reflectionResult = runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package") ?: return@runCatching false
            val observerClass = Class.forName("android.content.pm.IPackageDataObserver")

            try {
                Bypass.invoke<Any?>(
                    pm.javaClass,
                    pm,
                    "deleteApplicationCacheFilesAsUser",
                    arrayOf(String::class.java, Int::class.javaPrimitiveType!!, observerClass),
                    packageName,
                    thorUserId,
                    null /* IPackageDataObserver */
                )
            } catch (_: NoSuchMethodException) {
                // No user id to give: this overload derives one from the calling uid. Reached only
                // if a release stops declaring the AsUser variant, which none in 28..37 does.
                Bypass.invoke<Any?>(
                    pm.javaClass,
                    pm,
                    "deleteApplicationCacheFiles",
                    arrayOf(String::class.java, observerClass),
                    packageName,
                    null
                )
            }
            Logger.w(
                "DhizukuHelper",
                "clearCache($packageName): the reflection rung was issued but can confirm nothing, " +
                    "so it reports failure — the shell rung above is the only one that clears a cache"
            )
            false
        }.getOrDefault(false)

        return reflectionResult
    }

    /**
     * Wipes [packageName]'s data **for [thorUserId]** — `pm clear` first, then a hidden-API
     * `IPackageManager` call.
     *
     * **A `true` from this function comes from the shell rung or from nowhere**, the same contract
     * [clearCache] states and for the same reasons. `pm clear` blocks on its own observer inside
     * `PackageManagerShellCommand` and exits non-zero when the wipe fails, so it can be believed;
     * the reflection rung below can not be, and no longer says otherwise. It used to return `true`
     * whenever the invoke did not throw, which for the single most destructive operation Thor
     * performs meant the user was told their data was gone on the strength of a binder call that,
     * on a Dhizuku-only device, never reached PackageManagerService.
     *
     * Reporting failure here is the conservative direction: clearing data twice costs nothing, so a
     * user who retries loses nothing, while a false "done" loses them the chance to try a privilege
     * mode that would have worked.
     */
    // PrivateApi: hidden-API reflection is intentional — the core privilege mechanism, guarded by
    // the :bypass VMRuntime unseal.
    @SuppressLint("PrivateApi")
    fun clearAppData(packageName: String): Boolean {
        // 1. Try shell first, naming the same user the reflection rung below hands to
        // clearApplicationUserData. `pm clear` with no `--user` seeds USER_SYSTEM, so from a work
        // profile this rung wiped the primary user's copy and exited 0 — and the reflection rung
        // that would have targeted the right user never ran.
        val result = execute(clearAppDataCommand(packageName.escapeForShell(), thorUserId))
        if (result.first == 0) return true

        // 2. Fallback to reflection — issued, and deliberately never believed. Same argument as
        // [clearCache]'s rung 2, one step worse in consequence: `clearApplicationUserData` returns
        // `void`, so the verdict only ever arrives on the `IPackageDataObserver` that is `null`
        // here, and `asInterface`'s double-wrapped binder means that on a Dhizuku-only device the
        // call dies in a Shizuku transport before PackageManagerService sees it — the argument
        // [setAppDisabledDetailed]'s reflection rung records in full. This rung therefore reported
        // "your data is gone" for a call that could not have deleted anything, unconditionally, on
        // every release.
        //
        // The call stays because on a device that also has Shizuku it may genuinely run; only the
        // claim is withdrawn. An observer is not wired up in its place for the reason [clearCache]
        // gives — a guaranteed 15-second timeout per package on the device this rung actually runs
        // on is a worse answer than an immediate honest one.
        return runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package") ?: return@runCatching false
            val observerClass = Class.forName("android.content.pm.IPackageDataObserver")
            Bypass.invoke<Any?>(
                pm.javaClass,
                pm,
                "clearApplicationUserData",
                arrayOf(String::class.java, observerClass, Int::class.javaPrimitiveType!!),
                packageName,
                null,
                thorUserId
            )
            Logger.w(
                "DhizukuHelper",
                "clearAppData($packageName): the reflection rung was issued but can confirm nothing, " +
                    "so it reports failure — `pm clear` above is the only rung that can wipe this data"
            )
            false
        }.getOrElse { false }
    }

    /**
     * Suspends or unsuspends [packageName], reporting success only when a readback agrees.
     *
     * Dhizuku is the one privilege mode with **no suspender readback at all**. `dumpsys package` is
     * gated on `android.permission.DUMP` (`PackageManagerService.dump` →
     * `DumpUtils.checkDumpAndUsageStatsPermission`, android-16 `PackageManagerService.java:6689`)
     * and Dhizuku's commands run as the device-owner app rather than as shell, so there is no
     * process here that may dump and nothing for
     * [com.valhalla.thor.domain.model.parseSuspendingPackages] to parse. Do not add one: a dump this
     * process is allowed to take does not exist, and a fabricated "verification" that always says
     * yes is worse than none.
     *
     * What this process *can* read is `ApplicationInfo.FLAG_SUSPENDED`, and for the direction that
     * strands apps that is enough. From API 30 on, `PackageSettingBase.removeSuspension(callingPackage)`
     * (android-11.0.0_r1 `PackageSettingBase.java:443-452`, carried into `SuspendPackageHelper` on
     * 13-16) removes only the caller's own entry and leaves `suspended` true while anybody else's
     * remains — so a flag that is *still set* after an unsuspend is exactly the "another privilege
     * owns this suspension" signal. We cannot name the owner without DUMP, but we can refuse to
     * claim we lifted it.
     *
     * Every success exit is therefore gated on that flag, and an unreadable flag fails **closed**.
     * Both of the paths this replaces reported success without ever looking:
     * - `pm unsuspend` exits 0 even when it changed nothing. Lifting a suspension you do not own
     *   leaves `oldSuspendParams == null == newSuspendParams` → `changed == false`, which the
     *   platform logs as "No change is needed" and omits from the returned failure array, so the
     *   command, the reflection call and every caller above them all read success.
     * - the final PM re-query defaulted an unresolvable `ApplicationInfo` to "not suspended", which
     *   on the unsuspend path reads as "it worked".
     *
     * Known limit, unfixable without DUMP: `FLAG_SUSPENDED` is the *aggregate* state, not our own
     * entry in it. On the suspend direction a pre-existing foreign suspension therefore satisfies
     * the readback even if our own call did nothing. That errs toward the state the user asked for;
     * the unsuspend direction, the one that leaves an app permanently unusable, errs closed.
     */
    // PrivateApi: hidden-API reflection is intentional — the core privilege mechanism, guarded by
    // the :bypass VMRuntime unseal.
    @SuppressLint("PrivateApi")
    fun setAppSuspended(context: Context, packageName: String, suspended: Boolean): Boolean {
        val pkgs = Packages(context)
        pkgs.getApplicationInfoOrNull(packageName) ?: return false
        val userId = pkgs.myUserId

        // The suspended state the platform reports *now*, or null when the ApplicationInfo cannot
        // be read. null is deliberately not false — "I could not read it" collapsing into "not
        // suspended" is precisely how an unsuspend that did nothing used to report success. Same
        // shape as RootSystemGateway.readEffectivelyEnabled.
        fun readSuspended(): Boolean? = pkgs.getApplicationInfoOrNull(packageName)?.run {
            (flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
        }

        // Unknown compares equal to neither true nor false, so an unreadable flag is "not verified"
        // in both directions. Re-querying PackageManager is sound even though the mutation happened
        // in another process: PMS invalidates the app-side ApplicationInfo cache as part of the same
        // commit, so it is already stale-free by the time the call that changed it returns.
        fun verified(): Boolean = readSuspended() == suspended

        // 1. Try shell first
        val command = if (suspended) {
            "pm suspend --user $userId $packageName"
        } else {
            "pm unsuspend --user $userId $packageName"
        }
        val shellResult = execute(command)
        if (shellResult.first != 0) {
            Logger.w(
                "DhizukuHelper",
                "'$command' exited ${shellResult.first}: ${shellResult.second}"
            )
        }
        // Checked even on a non-zero exit, and it is the *only* thing checked on a zero one: the
        // goal is the state, not the exit code. `pm unsuspend` exits 0 when it changed nothing, and
        // a command that failed on an app already in the requested state left nothing to do.
        if (verified()) return true

        // 2. Fallback to reflection
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            runCatching {
                // Thrown rather than returned: an unreachable IPackageManager is a diagnosable
                // failure, and the onFailure below is the only place it gets said out loud.
                val pm = asInterface("android.content.pm.IPackageManager", "package")
                    ?: throw IllegalStateException("IPackageManager is unreachable through Dhizuku")
                val dialogInfoClass = Class.forName("android.content.pm.SuspendDialogInfo")
                val dialogInfo = if (suspended) buildSuspendDialogInfo(context) else null
                val failed = callSetPackagesSuspended(
                    pm = pm,
                    dialogInfoClass = dialogInfoClass,
                    packageName = packageName,
                    suspended = suspended,
                    dialogInfo = dialogInfo,
                    caller = BuildConfig.APPLICATION_ID,
                    userId = userId
                )
                // Logged, never trusted: see callSetPackagesSuspended on why an empty array is not
                // a success signal. The verified() below is what decides.
                if (failed?.contains(packageName) == true) {
                    Logger.w(
                        "DhizukuHelper",
                        "setPackagesSuspendedAsUser reported $packageName in its failure list"
                    )
                }
            }.onFailure {
                Logger.e("DhizukuHelper", "setAppSuspended reflection failed for $packageName", it)
            }

            if (verified()) return true
        }

        // 3. Neither path could be confirmed. Report the failure instead of inventing a success:
        // the caller turns this into a Result.failure the user actually sees.
        Logger.w(
            "DhizukuHelper",
            "setAppSuspended($packageName, suspended=$suspended) unconfirmed — FLAG_SUSPENDED reads " +
                "${readSuspended()}. From API 30 a suspension can only be lifted by the identity that " +
                "recorded it, and without DUMP this process cannot read which identity that is: the " +
                "shell rung's `pm` names $SHELL_SUSPENDER_IDENTITY, the reflection rung names " +
                "${BuildConfig.APPLICATION_ID}, and neither is confirmed against what the platform " +
                "actually recorded. A suspension recorded by Thor's root mode needs root mode to clear it."
        )
        return false
    }

    /**
     * Invokes whichever `IPackageManager.setPackagesSuspendedAsUser` overload this platform has, and
     * returns the packages it claims it could **not** change.
     *
     * Newest first, because each signature *replaced* its predecessor rather than joining it: only
     * one exists on any given device, so a wrong guess throws `NoSuchMethodException` instead of
     * mis-dispatching.
     * - **API 35+ (9 args)** — the `UserPackage` rework split the single `userId` into
     *   `suspendingUserId` (the user the *suspending* package lives in) and `targetUserId`. This
     *   lookup was absent, so on 35+ both of the attempts below missed and the entire reflection
     *   fallback was dead code that could only ever return "failed".
     * - **API 33-34 (8 args)** — adds the `flags` argument that carries `FLAG_SUSPEND_QUARANTINED`.
     * - **API 29-32 (7 args)** — the original `SuspendDialogInfo` form.
     *
     * **An empty return is not proof of success.** Naming a `callingPackage` that owns no entry for
     * the package leaves `oldSuspendParams == null == newSuspendParams`, so nothing changed, nothing
     * failed, and the package appears in neither list. Only a `FLAG_SUSPENDED` readback settles it.
     */
    @SuppressLint("PrivateApi")
    private fun callSetPackagesSuspended(
        pm: Any,
        dialogInfoClass: Class<*>,
        packageName: String,
        suspended: Boolean,
        dialogInfo: Any?,
        caller: String,
        userId: Int
    ): Array<String>? {
        try {
            // Android 15+ (API 35+): 9 args
            return Bypass.invoke<Array<String>?>(
                pm.javaClass, pm, "setPackagesSuspendedAsUser",
                arrayOf(
                    Array<String>::class.java,
                    Boolean::class.javaPrimitiveType!!,
                    android.os.PersistableBundle::class.java,
                    android.os.PersistableBundle::class.java,
                    dialogInfoClass,
                    Int::class.javaPrimitiveType!!,   // flags
                    String::class.java,               // callingPackage
                    Int::class.javaPrimitiveType!!,   // suspendingUserId
                    Int::class.javaPrimitiveType!!    // targetUserId
                ),
                arrayOf(packageName),
                suspended,
                null, null,
                dialogInfo,
                0,
                caller,
                userId,
                userId
            )
        } catch (_: NoSuchMethodException) {
            Logger.d("DhizukuHelper", "No 9-arg setPackagesSuspendedAsUser on this platform")
        }

        try {
            // Android 13-14 (API 33-34): 8 args
            return Bypass.invoke<Array<String>?>(
                pm.javaClass, pm, "setPackagesSuspendedAsUser",
                arrayOf(
                    Array<String>::class.java,
                    Boolean::class.javaPrimitiveType!!,
                    android.os.PersistableBundle::class.java,
                    android.os.PersistableBundle::class.java,
                    dialogInfoClass,
                    Int::class.javaPrimitiveType!!,   // flags
                    String::class.java,               // callingPackage
                    Int::class.javaPrimitiveType!!    // userId
                ),
                arrayOf(packageName),
                suspended,
                null, null,
                dialogInfo,
                0,
                caller,
                userId
            )
        } catch (_: NoSuchMethodException) {
            Logger.d("DhizukuHelper", "No 8-arg setPackagesSuspendedAsUser on this platform")
        }

        // Android 10-12 (API 29-32): 7 args. Last resort, so a miss here propagates rather than
        // being swallowed — the caller logs it and the readback fails the operation anyway.
        return Bypass.invoke<Array<String>?>(
            pm.javaClass, pm, "setPackagesSuspendedAsUser",
            arrayOf(
                Array<String>::class.java,
                Boolean::class.javaPrimitiveType!!,
                android.os.PersistableBundle::class.java,
                android.os.PersistableBundle::class.java,
                dialogInfoClass,
                String::class.java,               // callingPackage
                Int::class.javaPrimitiveType!!    // userId
            ),
            arrayOf(packageName),
            suspended,
            null, null,
            dialogInfo,
            caller,
            userId
        )
    }

    /**
     * Thor's custom text for the system's "app is paused" dialog, or null to let the system use its
     * own default.
     *
     * Null is a fully supported argument to `setPackagesSuspendedAsUser`, so a dialog that cannot be
     * assembled must not take the suspension down with it — which is exactly what it used to do:
     * [Bypass.invoke]'s vararg form resolves the overload from the runtime type of the argument, and
     * `SuspendDialogInfo.Builder.setTitle(String)` only exists from API 31 (the API 29 overload takes
     * a `@StringRes int`). On API 29-30 that lookup threw `NoSuchMethodException` out of the caller's
     * `runCatching` and killed the whole reflection path before it ever reached the suspend call.
     *
     * The `@StringRes int` overloads are deliberately not used as a pre-31 fallback: the system's
     * `SuspendedAppActivity` resolves such an id against the resources of whichever package the
     * platform *recorded* as the suspender. This helper's only caller is [setAppSuspended]'s
     * reflection rung, which names `BuildConfig.APPLICATION_ID`, so the id would ordinarily land on
     * Thor's own resources — but Dhizuku is the one privilege mode that cannot read the suspension
     * record back at all (no `DUMP`; see [setAppSuspended]), so "ordinarily" is the strongest claim
     * this file can make about where it lands. A literal string is right whoever renders it, and a
     * missing title is better than a wrong one. (`com.android.shell` is
     * `Shizuku.buildSuspendDialogInfo`'s answer, where the caller really is shell uid 2000; it does
     * not transfer here.)
     */
    @SuppressLint("PrivateApi")
    private fun buildSuspendDialogInfo(context: Context): Any? = runCatching {
        val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
        val builder = Bypass.newInstance<Any>(builderClass)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val title = context.getString(R.string.suspended_app_dialog_title)
            Bypass.invoke<Any>(builderClass, builder, "setTitle", title)
        }
        // setMessage(String) exists from API 29, and this is only reached on Q+.
        val message = context.getString(R.string.suspended_app_dialog_message)
        Bypass.invoke<Any>(builderClass, builder, "setMessage", message)
        Bypass.invoke<Any>(builderClass, builder, "build")
    }.onFailure {
        // Warn rather than swallow: this failing silently is why dialogInfo was always null.
        Logger.w(
            "DhizukuHelper",
            "SuspendDialogInfo unavailable, falling back to the system's default dialog: $it"
        )
    }.getOrNull()

    /**
     * Restricts or unrestricts background execution for [packageName], for the user Thor runs as.
     *
     * The `--user` the shell rung now carries is not the `pm` story told elsewhere in this file:
     * nothing here defaults to user 0 and nothing here fans out to all users.
     * `AppOpsService.Shell.parseUserPackageOp` seeds `UserHandle.USER_CURRENT` and resolves it with
     * `ActivityManager.getCurrentUser()` — evaluated inside system_server — so the bare command
     * targeted the **globally foreground user**, which is neither the caller's user nor user 0. On a
     * managed profile the foreground user is the parent, so a restriction set from the work profile
     * landed on the personal profile's copy; in a Xiaomi Second Space the space you switched into
     * *is* foreground, so the same command happened to be right. The defect was therefore the
     * dependence on foreground state, not a fixed wrong target, and it could differ between one call
     * and the next while Thor stayed alive.
     *
     * The reflection rung below never had the problem: it resolves the op against the package's own
     * uid, which carries the user in its high bits, so it was already per-user. The two rungs now
     * agree on which app op they are setting for whom.
     *
     * Both are also read back now, with `IAppOpsService.checkOperation`, and the two rungs treat an
     * *unreadable* readback differently on purpose — see [readBackgroundMode]. The shell rung's own
     * report is already honest, so an unreadable readback leaves it standing; the reflection rung's
     * never was, so an unreadable readback leaves it with nothing to stand on.
     */
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

        // 1. Try shell first. Unlike the other shell rungs in this file this one is not a liar:
        // `appops set` returns -1 for an unknown package or op, so a 0 is a real statement about a
        // real op. The readback is added on top of that rather than in place of it, which is why an
        // unreadable readback must not sink it — turning "I could not check" into a reported failure
        // here would be a regression, unlike at the clear-data sites where fail-closed is right.
        val result = execute(
            backgroundRestrictionCommand(packageName.escapeForShell(), thorUserId, restricted)
        )
        if (result.first == 0) {
            val mode = readBackgroundMode(context, packageName)
            if (mode == null || mode == expectedMode) return true
            Logger.w(
                "DhizukuHelper",
                "setAppRestricted($packageName, restricted=$restricted): `appops set` exited 0 but " +
                    "RUN_ANY_IN_BACKGROUND reads mode $mode, not $expectedMode — trying reflection"
            )
        }

        // 2. Fallback to reflection. The call is kept for the reason [clearCache]'s rung 2 keeps
        // its own — `asInterface` double-wraps the binder, so on a Dhizuku-only device this dies in
        // a Shizuku transport, but on a device that also has Shizuku it may genuinely run. What is
        // refused is this rung's *self-report*: "the invoke did not throw" is not a mode. Unlike
        // clearCache and clearAppData, an app op can actually be read back, so this rung reports
        // what `checkOperation` says rather than a flat `false` — a state the platform confirms is
        // not a lie, and answering `false` over a confirmed change would strand the user retrying an
        // operation that already worked. An unreadable readback still means `false` here, because
        // there is nothing else left to believe.
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

    /**
     * The mode `IAppOpsService` reports for [packageName]'s RUN_ANY_IN_BACKGROUND op right now, or
     * `null` for "could not read it".
     *
     * `null` is deliberately not "some other mode": the two callers in [setAppRestricted] treat them
     * differently, and collapsing them is exactly how a readback stops being a verifier and becomes
     * a second failure mode.
     *
     * **Expected to answer `null` on a Dhizuku-only device, and that is not a defect.** This rides
     * the same double-wrapped binder as the reflection rung — `asInterface` puts
     * `ShizukuBinderWrapper` on top of Dhizuku's own wrapper — so where that rung is dead this is
     * dead with it.
     *
     * What a `null` costs depends on which rung asked, and the two are opposites:
     * - **Shell rung** — not load-bearing. `appops set` exits non-zero for an unknown package or
     *   op, so that rung's own report is already honest and a `null` leaves it standing. This
     *   readback can only *add* confirmation there, which is what makes failing open safe rather
     *   than optimistic.
     * - **Reflection rung** — the sole verdict. "The invoke did not throw" is not a mode, so with
     *   no readback there is nothing left to believe and a `null` forces `false` — which is what
     *   that rung's own comment says. Fail-closed, as at the clear-data sites.
     *
     * **Known blind spot: a uid-level mode hides the package-level one this asks about.**
     * `AppOpsService.checkOperationUnchecked` consults
     * `mAppOpsCheckingService.getUidMode(uidState.uid, persistentDeviceId, code)` first and returns
     * straight away whenever that differs from `AppOpsManager.opToDefaultMode(code)` — before the
     * package entry is consulted at all. Both write paths in [setAppRestricted] are *package*-level
     * (`appops set <pkg>` and `IAppOpsService.setMode(op, uid, packageName, mode)`), so wherever a
     * uid-level mode exists for `OP_RUN_ANY_IN_BACKGROUND` this reports something unrelated to
     * whether the write landed. `checkOperationRaw` is not the fix: it drops `evalMode`, not the
     * uid short-circuit. The mechanism is AOSP-verified; what is *not* established is that anything
     * writes this op at uid level — Settings' Battery ▸ Restricted uses the package-level form — so
     * this is a known blind spot rather than an observed bug.
     *
     * `checkOperation(int, int, String)` is the signature this project has *not* verified across
     * 28..37 — it has been stable in `IAppOpsService` for as long as anyone has needed it, but that
     * is a recollection and not a measurement. A drifted signature arrives as a
     * `NoSuchMethodException`, which lands here as `null` and therefore costs nothing, and that is
     * the whole reason the uncertainty is tolerable instead of blocking.
     */
    // The type argument is spelled out because the block has two exits of different types — an early
    // `null` and an `Int` — and an unreadable op must arrive as `null`, never as a mode.
    private fun readBackgroundMode(context: Context, packageName: String): Int? = runCatching<Int?> {
        val appops = asInterface("com.android.internal.app.IAppOpsService", Context.APP_OPS_SERVICE)
            ?: return@runCatching null
        Bypass.invoke<Int>(
            appops::class.java,
            appops,
            "checkOperation",
            arrayOf(
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                String::class.java
            ),
            runAnyInBackgroundOp(),
            Packages(context).packageUid(packageName),
            packageName
        )
    }.getOrElse {
        Logger.d(
            "DhizukuHelper",
            "setAppRestricted($packageName): RUN_ANY_IN_BACKGROUND is unreadable, so the rung's own " +
                "report stands: $it"
        )
        null
    }
}
