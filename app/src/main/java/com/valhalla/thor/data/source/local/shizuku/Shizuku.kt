// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.valhalla.bypass.Bypass
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.data.source.local.DataClearOutcome
import com.valhalla.thor.data.source.local.awaitDataObserver
import com.valhalla.thor.data.source.local.backgroundRestrictionCommand
import com.valhalla.thor.data.source.local.clearAppDataCommand
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.data.source.local.uninstallCommand
import com.valhalla.thor.domain.model.SHELL_SUSPENDER_IDENTITY
import com.valhalla.thor.domain.model.canLiftSuspension
import com.valhalla.thor.domain.model.parseSuspendingPackages
import com.valhalla.thor.domain.model.thorSuspenderIdentities
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.valhalla.thor.util.Logger

/**
 * Which privileged rung `Shizuku.setAppDisabled` tries first. (Plain text, not a KDoc link: the
 * explicit `rikka.shizuku.Shizuku` import at the top of this file outranks the same-named object
 * declared below it, so `[Shizuku]` here would point at the wrong class.)
 *
 * [SHELL_FIRST] is the historical order and stays the default. For an ordinary user app
 * `pm disable --user N` is the cheapest thing that works, and that is not the path that broke.
 *
 * [REFLECTION_FIRST] exists for *preinstalled* apps. Both rungs end up inside the same
 * `PackageManagerService.setEnabledSetting`, but they do not arrive there identically: the shell
 * rung pays for a `newProcess` + `pm` round trip and can only report `pm`'s exit code, and `pm` is
 * ordinary userspace that an OEM is free to modify or lock down, whereas the reflection rung calls
 * `IPackageManager` straight through the Shizuku binder and names its target state
 * (`COMPONENT_ENABLED_STATE_DISABLED_USER`) and its caller ("com.android.shell") explicitly rather
 * than inheriting whatever this device's `pm` chose. Flipping the order costs nothing when both
 * work and gives the direct call the first attempt when one of them does not.
 */
enum class EnableRungOrder { SHELL_FIRST, REFLECTION_FIRST }

/**
 * The outcome of a privileged enable/disable attempt.
 *
 * [refusedByPolicy] is the field that exists for exactly one caller: the preinstalled-app freeze,
 * whose fallback removes the package for the current user. It separates "this device refuses to
 * disable system packages" — an OEM restriction there is no way around — from "that did not work
 * just now", which is a bug report, not a licence to change mechanic behind the user's back.
 *
 * It is only ever meaningful when [succeeded] is false.
 */
data class DisableOutcome(val succeeded: Boolean, val refusedByPolicy: Boolean)

/**
 * What a rung reported about *itself*. Only [REFUSED_BY_POLICY] ever changes a decision; whether a
 * rung believes it succeeded is not evidence of anything (see [firstRungThatSticks]).
 */
internal enum class RungResult {
    /** The rung ran without complaint. Proves nothing on its own — the post-read decides. */
    RAN,

    /** The rung failed in a way that carries no information: non-zero exit, timeout, null binder. */
    FAILED,

    /**
     * `PackageManagerService` **refused**. This is the one outcome that means "this device will
     * not let us do this", as opposed to "that did not work just now", and it is what
     * `uninstallFreezeFallbackAllowed` keys the uninstall-for-user fallback on.
     */
    REFUSED_BY_POLICY,
}

/**
 * Did this failure come from the platform refusing, rather than from anything transient?
 *
 * Matches both refusals Thor can actually meet: AOSP's own
 * `SecurityException("Shell cannot change component state for <pkg> to 2")` and Xiaomi's vendor
 * `SecurityException("Cannot disable system packages.")` out of `PackageManagerServiceImpl`. The
 * shell rung only ever sees these as text on stderr, so text is what this reads.
 */
internal fun isPolicyRefusal(text: String?): Boolean =
    text != null && text.contains("SecurityException", ignoreCase = true)

/** As [isPolicyRefusal], for a rung that threw instead of printing. Walks the whole cause chain. */
internal fun isPolicyRefusal(error: Throwable?): Boolean {
    var e = error
    var hops = 0
    while (e != null && hops++ < 10) {
        if (e is SecurityException) return true
        if (e.javaClass.name.endsWith("SecurityException")) return true
        e = e.cause
    }
    return false
}

/**
 * How a shell rung reads its own `(exitCode, output)` pair. Shared by both chains, and pure, so the
 * one judgement that can arm a destructive fallback is reachable from a plain JVM test.
 *
 * The `exitCode > 0` guard is the whole point and is not defensive padding. Both `execute`
 * helpers fold a *thrown* failure into `-1 to err.stackTraceToString()`, so on that path the text
 * being classified is **Thor's own stack trace**, not a word `pm` said — and the exceptions that
 * land there are precisely the plumbing ones: Shizuku's permission not granted, Dhizuku's client
 * not authorised, a dead binder. Several of those are themselves `SecurityException`s, so a
 * text match would read "the transport is not set up" as "`PackageManagerService` refuses this
 * device" — and [com.valhalla.thor.domain.model.uninstallFreezeFallbackAllowed] spends that
 * distinction on whether Thor may fall back to `pm uninstall -k`, which clears `FLAG_INSTALLED`
 * for the user. A transient failure must never buy that.
 *
 * -1 is `execute`'s documented "no exit code to read at all" sentinel — thrown exception, null
 * binder, timeout (see [SystemAppRemovalOutcome.exitCode]). Its timeout branch does carry `pm`'s
 * real output, but a timeout is a mechanical failure too, so the same answer is right for both
 * halves of the sentinel. A refusal is only ever a refusal when `pm` ran, spoke, and exited
 * non-zero of its own accord.
 */
internal fun shellRungResult(exitCode: Int, output: String?): RungResult = when {
    exitCode == 0 -> RungResult.RAN
    // `pm` reports a refusal by printing the SecurityException and exiting non-zero, so the exit
    // code alone cannot tell a refusal from a failure — but it can tell `pm` spoke at all.
    exitCode > 0 && isPolicyRefusal(output) -> RungResult.REFUSED_BY_POLICY
    else -> RungResult.FAILED
}

/**
 * What `pm uninstall -k --user N` actually said about a preinstalled app.
 *
 * A bare `Boolean` used to come back from here, and the string next to it was thrown away — which
 * is how the single most useful sentence in the whole freeze flow,
 * `Failure [only root can delete system app for a particular user]`, became the user-facing
 * "Action failed. This may happen if reflection is blocked or shell lacks permissions." That
 * generic sentence names the wrong cause: nothing was blocked and no permission was missing, the
 * platform simply reserved the operation for uid 0.
 *
 * [platformMessage] is whatever `pm` printed, verbatim and untranslated, or null when it printed
 * nothing at all. Note that `pm` prints its `Failure [...]` line on **stdout**, not stderr
 * (verified on an Android 17 emulator with `2>&1 1>/dev/null`, which printed nothing) — so
 * [Shizuku.execute]'s stdout-preferring `ifBlank` fold is what makes it reachable here.
 *
 * [exitCode] is -1 when there was no exit code to read at all — a thrown exception, a null binder,
 * a timeout — matching the same convention [Shizuku.execute] uses.
 */
data class SystemAppRemovalOutcome(
    val succeeded: Boolean,
    val exitCode: Int,
    val platformMessage: String?,
)

/**
 * Did the platform refuse this removal because it reserves it for root?
 *
 * `PackageManagerShellCommand.java:2281-2293` on android17-release requires
 * `Binder.getCallingUid() == Process.ROOT_UID` before it will honour `--user` on a `FLAG_SYSTEM`
 * package, and answers everyone else with
 * `Failure [only root can delete system app for a particular user]`. That guard is absent from
 * every android16 branch, which is why the identical command succeeds on API 36.
 *
 * Deliberately narrow: it matches that one sentence and nothing else. A looser test ("requires
 * root", "permission denied") would relabel ordinary failures as a platform limit and point the
 * user at Root mode for a problem root cannot fix. Everything this does not match still reaches the
 * user *with `pm`'s own words attached*, so an unrecognised refusal is a worse message, not a lost
 * one.
 *
 * This is **not** [isPolicyRefusal]. That one reads a `SecurityException` out of
 * `setEnabledSetting` and decides whether the destructive fallback may be *reached*; this one reads
 * the destructive fallback's own refusal and decides what to *say*. The `Failure [...]` line
 * carries no "SecurityException" text, so `isPolicyRefusal` answers false for it — correctly.
 */
internal fun isRootOnlySystemAppRemoval(text: String?): Boolean =
    text != null && text.contains("only root can delete system app", ignoreCase = true)

/**
 * The one line of [platformMessage][SystemAppRemovalOutcome.platformMessage] worth putting in front
 * of a user.
 *
 * `pm`'s `Failure [...]` is a single line, but neither `execute` hands this back cleanly: both
 * `Shizuku.execute` and `DhizukuHelper.execute` fold a thrown failure into `stackTraceToString()`,
 * and a stack trace in a snackbar helps nobody. Its first line is the exception and its message,
 * which is the part worth reading; the full text is already in the log line the helper wrote, so
 * nothing is lost. When `pm` printed nothing at all there is still the exit code, which is more
 * than the "Action failed. This may happen if reflection is blocked or shell lacks permissions."
 * this whole change exists to retire ever carried.
 *
 * **Display only.** Every classifier — [isRootOnlySystemAppRemoval], [isPolicyRefusal] — reads the
 * *whole* message, because `pm` can print its `Failure [...]` line behind linker noise and a
 * first-line-only classifier would drop an Android 17 user into the generic branch. Callers must
 * classify first and reach for this second.
 *
 * Shared by both gateways rather than written out in each. It was written out in each, briefly, and
 * only one of the two copies trimmed — so the Dhizuku freeze could hand a whole stack trace to the
 * snackbar while its KDoc claimed to be doing what the Shizuku one did.
 */
internal fun SystemAppRemovalOutcome.displayLine(): String =
    platformMessage
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() }
        ?: "exit code $exitCode"

/** One privileged attempt, plus the label that names it in the log line when it is the one that stuck. */
internal class EnableRung(val label: String, val attempt: () -> RungResult)

internal const val RUNG_REFLECTION = "IPackageManager reflection"
internal const val RUNG_SHELL = "pm shell"
internal const val RUNG_UNPRIVILEGED = "unprivileged PackageManager"

/**
 * The result of running the whole chain: which rung (if any) actually moved the state, and whether
 * anything along the way was refused by the platform rather than merely failing.
 */
internal data class ChainOutcome(val winner: String?, val refusedByPolicy: Boolean)

/**
 * Runs [rungs] in order and reports the first one after which [verify] says the state has actually
 * changed — or a null winner if none of them moved it.
 *
 * A rung's claim to have *succeeded* is deliberately ignored. `pm` exits 0 for a disable that
 * `PackageManagerService` refused, and a `Bypass.invoke` that threw nothing has still proven
 * nothing, so the post-read is the only evidence that counts. It is equally deliberate that a rung
 * reporting failure is still verified before moving on: `Shizuku.execute` returns -1 whenever it
 * cannot read an exit code at all (null binder, timeout), and that is not the same as "the state
 * did not change".
 *
 * A rung's claim to have been *refused* is the one thing that is carried out, because it is the
 * only outcome that distinguishes "this device will not do this" from "that did not work just
 * now" — and the caller spends that distinction on whether it may switch freeze mechanic.
 * [refusedByPolicy][ChainOutcome.refusedByPolicy] is sticky across rungs: a refusal from the
 * reflection rung still counts when a later rung merely fails, since the refusal is a fact about
 * the device either way.
 *
 * Pure on purpose — no Android types, no logging — so the ordering, the short-circuit and the
 * refusal bookkeeping are all reachable from a plain JVM unit test. The caller does the logging.
 */
internal fun firstRungThatSticks(rungs: List<EnableRung>, verify: () -> Boolean): ChainOutcome {
    var refused = false
    for (rung in rungs) {
        if (rung.attempt() == RungResult.REFUSED_BY_POLICY) refused = true
        if (verify()) return ChainOutcome(rung.label, refused)
    }
    return ChainOutcome(null, refused)
}

/**
 * The rung order itself, as a pure function so the one decision this change is actually about is
 * reachable from a test.
 *
 * [unprivileged] is always last and is never reordered: it is the only rung that cannot possibly
 * outrank a privileged one (Thor holds no `CHANGE_COMPONENT_ENABLED_STATE`, so for any package but
 * its own it can only throw), and putting it anywhere else would spend a `SecurityException` before
 * trying something that works.
 */
internal fun orderRungs(
    rungOrder: EnableRungOrder,
    shell: EnableRung,
    reflection: EnableRung,
    unprivileged: EnableRung,
): List<EnableRung> = when (rungOrder) {
    EnableRungOrder.SHELL_FIRST -> listOf(shell, reflection, unprivileged)
    EnableRungOrder.REFLECTION_FIRST -> listOf(reflection, shell, unprivileged)
}

object Shizuku {

    /**
     * Hang backstop for a single privileged command: a stuck child is killed instead of pinning
     * the caller forever. Deliberately generous (5 min) because valid slow operations run through
     * here — notably `pm install` of large/split APKs on slow devices — and must not be killed.
     * This bounds infinite hangs, it does NOT enforce a tight SLA.
     */
    private const val EXECUTE_TIMEOUT_MS = 300_000L

    /** Grace period for reader threads to drain their streams after the process has exited/been destroyed. */
    private const val READER_JOIN_TIMEOUT_MS = 5_000L

    val isRoot get() = Shizuku.getUid() == 0

    /**
     * The uid Shizuku's service runs as, or `null` if it could not be read.
     *
     * [isRoot] throws when the binder has gone since the last availability check — `Shizuku.getUid()`
     * is a live transaction, not a cached field — so a caller that has to *decide* something on the
     * uid, rather than merely branch inside an already-privileged operation, needs the failure to be
     * a value it can reason about. Every such caller here reads `null` as "not root", because the
     * alternative is offering a control that throws a `SecurityException` on every press.
     */
    fun uidOrNull(): Int? = runCatching { Shizuku.getUid() }.getOrNull()

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

    private fun asInterface(className: String, serviceName: String): Any =
        asInterface(className, SystemServiceHelper.getSystemService(serviceName))

    val lockScreen
        get() = runCatching {
            execute("input keyevent 26").first == 0
        }.getOrElse {
            Logger.e("Shizuku", "lockScreen event trigger failed", it)
            false
        }

    // killBackgroundProcesses' KILL_BACKGROUND_PROCESSES permission is satisfied via the elevated
    // Shizuku privilege (root shell / Shizuku), not a manifest grant.
    @SuppressLint("MissingPermission")
    fun forceStopApp(context: Context, packageName: String): Boolean {
        val pkgs = Packages(context)
        val userId = pkgs.myUserId
        // 1. Try shell first — but exit 0 is not an answer to "is it stopped?", which is why it is
        // no longer the whole condition. `ActivityManagerShellCommand.runForceStop` calls
        // `mInterface.forceStopPackage(pkg, userId)` and then ends in an unconditional `return 0`;
        // its only non-zero exits are an unknown command-line option and an exception thrown out of
        // AMS. AMS refuses nothing here — a package nothing is running for, or one not installed
        // for `--user N` at all, produces exactly the same 0 as a real kill. So this rung reported
        // success for every force-stop Thor has ever issued, and FLAG_STOPPED re-read from
        // PackageManager — `Packages.isAppStopped`, the post-condition the caller actually means —
        // sat twice below, unreachable behind that 0. Do not simplify this back to the exit code.
        val result = execute("am force-stop --user $userId $packageName")
        if (result.first == 0 && pkgs.isAppStopped(packageName)) return true

        // 2. Fallback to reflection, and nothing reads its result any more. It is the same shape of
        // claim rung 1 just stopped making: `IActivityManager.forceStopPackage` returns void, so all
        // the old `if (reflectionResult) return true` could report was that the binder call did not
        // throw — and verifying rung 1 alone would have moved that false success one rung down
        // rather than removed it. The line is deleted rather than gated because rung 3 already
        // re-reads FLAG_STOPPED unconditionally: `reflectionResult && pkgs.isAppStopped(...)` can
        // only return true where the very next line returns true anyway, at the cost of a second
        // PackageManager round trip. One verifier, reached whichever rung did the work — the same
        // shape `DhizukuHelper.forceStopApp` carries.
        runCatching {
            val am = asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE)
            Bypass.invoke<Any?>(
                am::class.java, am, "forceStopPackage", packageName, userId
            )
        }.onFailure {
            Logger.e("Shizuku", "forceStopApp reflection failed for $packageName", it)
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
     * Enable/disable a package for the current user, trying every mechanism Shizuku can reach.
     *
     * [rungOrder] chooses which privileged rung goes first; see [EnableRungOrder]. The default is
     * the historical shell-first order, so existing callers are untouched — only the preinstalled-app
     * freeze path in `ShizukuSystemGateway` asks for [EnableRungOrder.REFLECTION_FIRST].
     *
     * Every rung is verified by re-reading `ApplicationInfo`, never by its own exit code; see
     * [firstRungThatSticks]. Returns true only when the package really is in the requested state.
     */
    fun setAppDisabled(
        context: Context,
        packageName: String,
        disabled: Boolean,
        rungOrder: EnableRungOrder = EnableRungOrder.SHELL_FIRST
    ): Boolean = setAppDisabledDetailed(context, packageName, disabled, rungOrder).succeeded

    /**
     * [setAppDisabled], plus *why* it failed when it did.
     *
     * Only the preinstalled-app freeze needs this. It is the one caller whose next move depends on
     * the difference between "the platform refused" and "that did not work", because its next move
     * is `pm uninstall -k --user N` — which keeps the app's data but clears its
     * installed-for-this-user bit, and so is worth reaching only where the platform left no
     * alternative. (This used to say "and its runtime permission grants" as well; that was a guess
     * and it measured false — see [freezeSystemAppForUser].)
     */
    fun setAppDisabledDetailed(
        context: Context,
        packageName: String,
        disabled: Boolean,
        rungOrder: EnableRungOrder = EnableRungOrder.SHELL_FIRST
    ): DisableOutcome {
        val pkgs = Packages(context)
        pkgs.getApplicationInfoOrNull(packageName)
            ?: return DisableOutcome(succeeded = false, refusedByPolicy = false)
        val userId = pkgs.myUserId
        // Escaped for the shell rung only; the reflection rung passes the raw name over binder.
        val escapedPackage = packageName.escapeForShell()

        // The canonical freeze test, the same one AppFreezeStateReader.candidateOf uses: a package
        // is "not disabled" only when it is BOTH enabled AND installed for this user. `enabled` on
        // its own — which is all this function used to check, via Packages.isAppDisabled — is wrong
        // in the direction that matters here. The gateway's last-resort `pm uninstall --user N`
        // clears FLAG_INSTALLED and leaves `enabled` true, so an app frozen by an older build reads
        // back as "not disabled" and an unfreeze could never be verified as complete.
        //
        // MATCH_UNINSTALLED_PACKAGES (the Packages default) is what makes such a package readable
        // at all. MATCH_DISABLED_COMPONENTS is deliberately NOT added: getApplicationInfo does not
        // filter on the enabled setting, which AppFreezeStateReader.MATCH_FLAGS documents for the
        // same reason, so it would buy nothing here while changing a default other callers share.
        fun isDisabledNow(): Boolean = pkgs.getApplicationInfoOrNull(packageName)?.let {
            !(it.enabled && (it.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0)
        } ?: true // stopped resolving entirely: gone is at least as disabled as disabled

        // `pm disable-user` (COMPONENT_ENABLED_STATE_DISABLED_USER) and not `pm disable`: since
        // API 25 and still on 37, a shell-uid caller may only move a whole package between DEFAULT,
        // ENABLED and DISABLED_USER, so `pm disable` throws SecurityException on every release.
        // Verified on an AOSP API 36 emulator: `pm disable` → "Shell cannot change component state
        // for null to 2"; `pm disable-user` → exit 0, enabled=3, installed=true.
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
                setApplicationEnabledSettingViaBypass(context, packageName, disabled, userId)
                RungResult.RAN
            }.getOrElse { e ->
                // Logged rather than swallowed (it used to be a bare getOrDefault(false)): when
                // this rung is the one that is supposed to work, its SecurityException/
                // NoSuchMethodException is the single most useful line in a bug report.
                Logger.e(
                    "Shizuku",
                    "setApplicationEnabledSetting reflection failed for $packageName (disabled=$disabled)",
                    e
                )
                // Bypass.invoke reflects, so the platform's SecurityException arrives wrapped in an
                // InvocationTargetException — hence the cause walk rather than an `is` check.
                if (isPolicyRefusal(e)) RungResult.REFUSED_BY_POLICY else RungResult.FAILED
            }
        }

        // Always last, and barely a rung: Thor holds no CHANGE_COMPONENT_ENABLED_STATE (see
        // AndroidManifest.xml), so for any package but its own this throws SecurityException and is
        // swallowed. It stays because it is free on the rare device that does grant it, and because
        // reaching it at all still buys one more post-read before we give up.
        val unprivilegedRung = EnableRung(RUNG_UNPRIVILEGED) {
            val newState = if (disabled) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            runCatching {
                context.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
                RungResult.RAN
            // FAILED and never REFUSED_BY_POLICY, however loudly this throws. Thor holds no
            // CHANGE_COMPONENT_ENABLED_STATE, so for any package but its own this rung throws
            // SecurityException on *every* device — reporting that as a policy refusal would hand
            // the destructive fallback a permanent green light and undo the whole gate.
            }.getOrElse { RungResult.FAILED }
        }

        val ordered = orderRungs(rungOrder, shellRung, reflectionRung, unprivilegedRung)

        val outcome = firstRungThatSticks(ordered) { isDisabledNow() == disabled }
        return if (outcome.winner != null) {
            Logger.d(
                "Shizuku",
                "setAppDisabled($packageName, disabled=$disabled): ${outcome.winner} changed the state"
            )
            DisableOutcome(succeeded = true, refusedByPolicy = false)
        } else {
            Logger.e(
                "Shizuku",
                "setAppDisabled($packageName, disabled=$disabled): all rungs ran " +
                    "(${ordered.joinToString { it.label }}) and the state did not change" +
                    if (outcome.refusedByPolicy) " — the platform REFUSED (SecurityException)" else ""
            )
            DisableOutcome(succeeded = false, refusedByPolicy = outcome.refusedByPolicy)
        }
    }

    /**
     * The one and only copy of the `IPackageManager.setApplicationEnabledSetting` reflection.
     *
     * Both conditionals are load-bearing and must not be "simplified":
     *
     * - **newState.** `COMPONENT_ENABLED_STATE_DISABLED` (2) is the framework's "an admin turned
     *   this off" state; `COMPONENT_ENABLED_STATE_DISABLED_USER` (3) is "the user turned this off",
     *   and it is the state a shell-uid caller is allowed to set on somebody else's package — it is
     *   also exactly what `pm disable-user` sets. A root Shizuku runs as uid 0 and may use the
     *   stronger one.
     * - **caller.** `PackageManagerService` validates the callingPackage argument against the
     *   calling uid and throws SecurityException when the name does not belong to it. The call
     *   arrives with the *Shizuku process's* uid: 2000 under the ordinary shell-uid Shizuku, whose
     *   package is "com.android.shell", and 0 under a root Shizuku, which owns no package at all —
     *   hence Thor's own name there.
     *
     * Returns Unit and throws on failure rather than returning a boolean: "the reflection did not
     * blow up" is not evidence that the state moved, and a boolean here would invite a caller to
     * treat it as one instead of doing the post-read.
     */
    private fun setApplicationEnabledSettingViaBypass(
        context: Context,
        packageName: String,
        disabled: Boolean,
        userId: Int
    ) {
        val pm = asInterface("android.content.pm.IPackageManager", "package")
        val newState = when {
            !disabled -> android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            isRoot -> android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else -> android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        }
        val caller = if (isRoot) context.packageName else "com.android.shell"
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
            caller
        )
    }

    /**
     * Suspend or unsuspend a package, reporting only what a post-read can prove.
     *
     * ### Why the exit code was not enough
     *
     * This used to be `if (shellResult.first == 0) return true`. `pm unsuspend` exits 0 even when it
     * removed nothing: lifting a suspension you do not own leaves
     * `oldSuspendParams == null == newSuspendParams`, so `changed` is false, the package is logged
     * "No change is needed" and is left *out* of the array of failures the API returns. That empty
     * array — and the 0 the shell prints because of it — is exactly what every caller read as
     * success while the user's app stayed suspended forever.
     *
     * ### Ownership, and the one thing a shell-uid Shizuku genuinely cannot do
     *
     * From API 30 a suspension is keyed on the suspending package name and only that name may remove
     * it (`PackageSettingBase.removeSuspension`, android-11.0.0_r1 :443-452, carried into
     * `SuspendPackageHelper` on 13-16). `PackageManagerService.enforceCanSetPackagesSuspendedAsUser`
     * (android-17.0.0_r1 :3354-3358) early-returns for `Process.ROOT_UID` *before* any
     * suspender-name validation, but shell gets no such exemption —
     * `allowedShell = callingUid == SHELL_UID && isCallerSameApp(suspendingPackage, callingUid)` — so
     * a Shizuku at uid 2000 can only ever act as [SHELL_SUSPENDER_IDENTITY]. A suspension recorded by
     * root-mode Thor (`com.valhalla.thor`) is therefore **unliftable from here**, permanently, and
     * the only honest move is to say so: [canLiftSuspension] decides and the throw names the owner
     * and points at root mode. Attempting it anyway would take the "No change is needed" path above
     * and report success, which is the bug.
     *
     * A Shizuku *started as root* is a different animal: uid 0 is exempt, so it names each recorded
     * owner verbatim and performs the same rescue `RootSystemGateway` does.
     *
     * ### Why it throws instead of returning false
     *
     * `ShizukuSystemGateway.runAction` turns a thrown exception into `Result.failure(e)` and the UI
     * renders `e.message`; a bare `false` becomes the generic "Action failed. This may happen if
     * reflection is blocked…", which is precisely the sentence that would send a user hunting for a
     * problem that does not exist. The owner's name is the whole point of the failure, so it has to
     * ride an exception to survive the `Boolean` this function is stuck returning.
     */
    fun setAppSuspended(context: Context, packageName: String, suspended: Boolean): Boolean {
        val pkgs = Packages(context)
        pkgs.getApplicationInfoOrNull(packageName) ?: return false
        val userId = pkgs.myUserId
        // Escaped for the shell rungs only; the reflection rung passes the raw name over binder.
        val escapedPackage = packageName.escapeForShell()
        val sdkInt = android.os.Build.VERSION.SDK_INT

        // A package that is not suspended is already in the requested state, so there is nothing to
        // lift and no owner to read. This is a *positive* read of the end state, not the fail-open
        // "could not tell, call it success" this change exists to delete — an unreadable flag is
        // null, which is neither true nor false and falls through to the full path below. It also
        // keeps a bulk unfreeze from paying a `dumpsys` round trip per app that was never suspended.
        if (!suspended && isSuspendedNow(pkgs, packageName) == false) return true

        // Read the owner BEFORE writing. `dumpsys package` needs android.permission.DUMP
        // (`PackageManagerService.dump` → `DumpUtils.checkDumpAndUsageStatsPermission`,
        // android-16 :6689); the shell uid Shizuku runs commands as holds it and the app process
        // does not, which is why this read can only live on this side of the binder.
        val recorded = if (suspended) emptySet() else recordedSuspenders(escapedPackage, userId)
        refuseUnliftableSuspension(context, packageName, recorded, sdkInt)

        // Which identity each rung acts as. Only root may name someone else's.
        val callers: List<String> = when {
            // Suspending records a new entry, and the name it records is the one the system turns
            // into the user-visible "managed by …" line on the pause dialog. Unchanged on purpose.
            suspended -> listOf(if (isRoot) context.packageName else SHELL_SUSPENDER_IDENTITY)
            // Unsuspending at uid 0: name every recorded owner verbatim. This is the rescue path —
            // it is what lets a root Shizuku lift a suspension some other privilege wrote.
            isRoot && recorded.isNotEmpty() -> recorded.toList()
            // uid 0, but the dump was unreadable. Sweep every identity Thor has ever written
            // (its own name, the legacy "root", and shell) rather than guessing one.
            isRoot -> (thorSuspenderIdentities(context.packageName) + SHELL_SUSPENDER_IDENTITY).toList()
            // Shell uid can only ever name itself; refuseUnliftableSuspension has already turned
            // away everything else, so this is the only name left that can do anything.
            else -> listOf(SHELL_SUSPENDER_IDENTITY)
        }

        // `pm suspend` / `pm unsuspend` act as whoever the shell is — "com.android.shell" at uid
        // 2000, the literal "root" at uid 0 (PackageManagerShellCommand picks the name from the
        // calling uid, which is where legacy "root"-owned suspensions came from). Cheapest rung and
        // the one that covers the ordinary case, so it stays first.
        val shellRung = EnableRung(RUNG_SHELL) {
            val verb = if (suspended) "suspend" else "unsuspend"
            val (code, _) = execute("pm $verb --user $userId $escapedPackage")
            if (code == 0) RungResult.RAN else RungResult.FAILED
        }

        val reflectionRung = EnableRung(RUNG_REFLECTION) {
            if (sdkInt < android.os.Build.VERSION_CODES.Q) {
                // `SuspendDialogInfo` does not exist before API 29 — the API 28 overload takes a
                // String dialogMessage in its place — so there is nothing to reflect on P.
                RungResult.FAILED
            } else {
                var ran = false
                // Every caller, not the first that works: on the unsuspend path each recorded owner
                // is a separate entry in the suspendParams map and each one has to be named to be
                // removed. Stopping early would leave the package suspended by the rest.
                for (caller in callers) {
                    runCatching {
                        setPackagesSuspendedViaBypass(context, packageName, suspended, caller, userId)
                        ran = true
                    }.onFailure { e ->
                        Logger.e(
                            "Shizuku",
                            "setPackagesSuspendedAsUser reflection failed for $packageName " +
                                "(suspended=$suspended, caller=$caller)",
                            e
                        )
                    }
                }
                if (ran) RungResult.RAN else RungResult.FAILED
            }
        }

        // The post-read is the only evidence that counts; see firstRungThatSticks, whose types are
        // named for their first caller (the enable/disable chain) rather than for that chain alone.
        val outcome = firstRungThatSticks(listOf(shellRung, reflectionRung)) {
            reachedSuspendState(pkgs, packageName, escapedPackage, userId, suspended)
        }
        if (outcome.winner != null) {
            Logger.d(
                "Shizuku",
                "setAppSuspended($packageName, suspended=$suspended): ${outcome.winner} changed the state"
            )
            return true
        }

        // Nothing moved. Re-read the owner: the pre-read can come back empty because the dump was
        // truncated or denied, not because nobody owned the package, and this second look is the one
        // chance to turn "that did not work" into the specific "root mode owns it" the user can act
        // on. Empty is still "unknown", so this can only ever add information, never remove it.
        val remaining = if (suspended) emptySet() else recordedSuspenders(escapedPackage, userId)
        refuseUnliftableSuspension(context, packageName, remaining, sdkInt)
        Logger.e(
            "Shizuku",
            "setAppSuspended($packageName, suspended=$suspended): both rungs ran as " +
                "${callers.joinToString()} and the state did not change" +
                if (remaining.isEmpty()) "" else " (still suspended by ${remaining.joinToString()})"
        )
        return false
    }

    /**
     * Is [packageName] suspended right now — or `null` when that cannot be read at all?
     *
     * Three-valued and never `?: false`, which is the shape `readEffectivelyEnabled` uses for the
     * same reason: an unreadable `ApplicationInfo` collapsed to "not suspended" reads as "the
     * unsuspend worked", so the one state where Thor knows least would be the one it is loudest
     * about. Callers treat null as "not verified".
     */
    private fun isSuspendedNow(pkgs: Packages, packageName: String): Boolean? =
        pkgs.getApplicationInfoOrNull(packageName)
            ?.let { (it.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0 }

    /**
     * Has [packageName] actually reached [suspended]?
     *
     * `FLAG_SUSPENDED` is asked first because it is a binder call rather than a shell round trip,
     * and because it is the same bit the dump prints (`PackageInfoUtils.generateApplicationInfo`
     * sets it from `PackageUserState.isSuspended()`, which is just "the suspendParams map is not
     * empty"). Every rung's failure therefore costs one cheap read and no `dumpsys` at all.
     *
     * The dump is then asked on the unsuspend path only, and only once the flag has already said
     * "not suspended". That ordering is what makes the dump safe to use here: an empty parse means
     * "unknown" on its own — a denied or truncated dump looks identical to a clean one — and it is
     * the flag's independent *positive* answer that turns this pair into a confirmation instead of
     * two absences of evidence. Asking it at all is worth the round trip because the flag is one bit
     * and cannot say who is still holding the package.
     */
    private fun reachedSuspendState(
        pkgs: Packages,
        packageName: String,
        escapedPackage: String,
        userId: Int,
        suspended: Boolean
    ): Boolean {
        // null != true and null != false, so an unreadable package is never "verified".
        if (isSuspendedNow(pkgs, packageName) != suspended) return false
        if (suspended) return true
        return recordedSuspenders(escapedPackage, userId).isEmpty()
    }

    /**
     * The packages the platform records as suspending [escapedPackage] for [userId].
     *
     * Empty means **unknown**, not "nothing" — a package that is not suspended, a dump denied for
     * want of `android.permission.DUMP`, a truncated read and an OEM format nobody has seen all land
     * here identically. [parseSuspendingPackages] documents the same rule; every caller in this file
     * either pairs it with a positive `FLAG_SUSPENDED` read or treats it as "no information".
     */
    private fun recordedSuspenders(escapedPackage: String, userId: Int): Set<String> {
        val (code, output) = execute("dumpsys package $escapedPackage")
        if (code != 0 || output.isNullOrBlank()) {
            Logger.w(
                "Shizuku",
                "dumpsys package $escapedPackage failed (exit=$code): suspender ownership is unknown"
            )
            return emptySet()
        }
        return parseSuspendingPackages(output, userId)
    }

    /**
     * Throws when [recorded] holds an identity this privilege cannot lift, naming it.
     *
     * At uid 0 [canLiftSuspension] is always true and this is a no-op, as is every call below API 30
     * where `setSuspended(false)` clears the single slot regardless of who set it (android-9.0.0_r1
     * `PackageSettingBase.java:399-407`). It bites in exactly one place: a shell-uid Shizuku facing a
     * suspension that root-mode Thor recorded under its own name on API 30+. That is genuinely
     * unfixable from here, and the requirement is that Thor says so — the alternative, which is what
     * shipped, is a call that removes nothing, returns an empty failure array and reports success.
     */
    private fun refuseUnliftableSuspension(
        context: Context,
        packageName: String,
        recorded: Set<String>,
        sdkInt: Int
    ) {
        val blocked = recorded.filterNot { canLiftSuspension(it, isRoot, sdkInt) }
        if (blocked.isEmpty()) return
        val owners = blocked.joinToString()
        Logger.e(
            "Shizuku",
            "setAppSuspended($packageName, suspended=false): suspension is owned by $owners and " +
                "this Shizuku acts as ${if (isRoot) "root" else SHELL_SUSPENDER_IDENTITY}; refusing " +
                "to attempt a removal the platform would silently drop"
        )
        throw IllegalStateException(
            context.getString(
                com.valhalla.thor.R.string.suspend_owned_by_other_privilege,
                packageName,
                owners
            )
        )
    }

    /**
     * The one and only copy of the `IPackageManager.setPackagesSuspendedAsUser` reflection.
     *
     * All three overloads are needed and the 9-argument one was missing, which is why this rung was
     * dead on every Android 15+ device: only the 8-arg and 7-arg lookups existed, both threw
     * `NoSuchMethodException` there, and the outer `runCatching` swallowed it into a plain `false`.
     *
     * | Args | Platform | Shape |
     * |---|---|---|
     * | 9 | API 35+ | `…, int flags, String callingPackage, int suspendingUserId, int targetUserId` |
     * | 8 | API 33-34 | `…, int flags, String callingPackage, int userId` |
     * | 7 | API 29-32 | `…, String callingPackage, int userId` |
     *
     * `suspendingUserId` is the user the *suspending* package lives in and is what API 35 prints as
     * the `<0>` prefix on the dump's `UserPackage` key; both ids are [userId] here because Thor
     * suspends its own user's packages as an identity in that same user.
     *
     * Returns Unit and throws on failure rather than returning a boolean, for the same reason
     * [setApplicationEnabledSettingViaBypass] does — and here the reason is sharper still: the
     * boolean it *could* return is `!failedPackages.contains(packageName)`, and an empty
     * `failedPackages` is precisely the lie this whole change exists to stop believing. The returned
     * array is deliberately discarded; the post-read decides.
     */
    @SuppressLint("PrivateApi")
    private fun setPackagesSuspendedViaBypass(
        context: Context,
        packageName: String,
        suspended: Boolean,
        caller: String,
        userId: Int
    ) {
        val pm = asInterface("android.content.pm.IPackageManager", "package")
        val dialogInfoClass = Class.forName("android.content.pm.SuspendDialogInfo")
        val dialogInfo = if (suspended) buildSuspendDialogInfo(context) else null
        val targets = arrayOf(packageName)

        val stringArrayType = Array<String>::class.java
        val boolType = Boolean::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!
        val bundleType = android.os.PersistableBundle::class.java
        val stringType = String::class.java

        // API 35+ — 9 args.
        try {
            Bypass.invoke<Any?>(
                pm.javaClass,
                pm,
                "setPackagesSuspendedAsUser",
                arrayOf(
                    stringArrayType, boolType, bundleType, bundleType, dialogInfoClass,
                    intType, stringType, intType, intType
                ),
                targets, suspended, null, null, dialogInfo, 0, caller, userId, userId
            )
            return
        } catch (_: NoSuchMethodException) {
            // Older platform; fall through.
        }

        // API 33-34 — 8 args.
        try {
            Bypass.invoke<Any?>(
                pm.javaClass,
                pm,
                "setPackagesSuspendedAsUser",
                arrayOf(
                    stringArrayType, boolType, bundleType, bundleType, dialogInfoClass,
                    intType, stringType, intType
                ),
                targets, suspended, null, null, dialogInfo, 0, caller, userId
            )
            return
        } catch (_: NoSuchMethodException) {
            // Older platform; fall through.
        }

        // API 29-32 — 7 args. Uncaught on purpose: there is no overload left to try, so a
        // NoSuchMethodException here is real news and belongs to the caller's log line.
        Bypass.invoke<Any?>(
            pm.javaClass,
            pm,
            "setPackagesSuspendedAsUser",
            arrayOf(
                stringArrayType, boolType, bundleType, bundleType, dialogInfoClass,
                stringType, intType
            ),
            targets, suspended, null, null, dialogInfo, caller, userId
        )
    }

    /**
     * The custom pause dialog, or null when this platform will not build one.
     *
     * Null is a supported argument — the system falls back to its own generic dialog — so a failure
     * here must never fail the suspend itself. It is logged rather than swallowed all the same,
     * because a silently-null dialogInfo is invisible until a user asks why the dialog is generic.
     *
     * The overloads are picky and the wrong one throws `NoSuchMethodException` for the whole
     * builder: `setMessage(String)` exists from API 29, `setTitle(String)` only from API 31 — asking
     * for `setTitle` on 29 or 30 used to take the entire reflection rung down with it. The
     * `@StringRes int` overloads are deliberately not used even though they go back to 29: the
     * dialog is rendered by the system's `SuspendedAppActivity` against the *suspending* package's
     * resources, which for a shell-uid Shizuku is `com.android.shell` — Thor's resource ids mean
     * nothing there.
     */
    @SuppressLint("PrivateApi")
    private fun buildSuspendDialogInfo(context: Context): Any? = runCatching {
        val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
        val builder = Bypass.newInstance<Any>(builderClass)
        Bypass.invoke<Any?>(
            builderClass,
            builder,
            "setMessage",
            arrayOf(String::class.java),
            context.getString(com.valhalla.thor.R.string.suspended_app_dialog_message)
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Bypass.invoke<Any?>(
                builderClass,
                builder,
                "setTitle",
                arrayOf(String::class.java),
                context.getString(com.valhalla.thor.R.string.suspended_app_dialog_title)
            )
        }
        Bypass.invoke<Any>(builderClass, builder, "build")
    }.getOrElse { e ->
        Logger.e("Shizuku", "SuspendDialogInfo.Builder failed; suspending without a custom dialog", e)
        null
    }

    /**
     * Trims every app's cache until the volume reports [targetFreeBytes] free, via `pm trim-caches`.
     *
     * This replaced a per-package `deleteApplicationCacheFilesAsUser`, and the replacement is not a
     * refactor — the per-package call cannot work here at all. `PackageManagerService` guards it with
     * `INTERNAL_DELETE_CACHE_FILES`, a `signature`-level permission that only a platform-signed
     * package can hold; `pm grant` refuses it as "not a changeable permission type" and
     * `com.android.shell` never requests it, so there is nothing for Shizuku to delegate. The call
     * arrives at PMS as uid 2000 and is answered with `Calling uid 2000 does not have
     * android.permission.INTERNAL_DELETE_CACHE_FILES, silently ignoring` — accepted, then dropped.
     * That is why the observer rung existed and why it only ever timed out.
     *
     * `pm trim-caches` takes the other door: it calls `freeStorage`, which PMS gates on
     * `CLEAR_APP_CACHE` — a permission shell *does* hold. The cost is that the caller no longer picks
     * the victim. PMS evicts by LRU across every app on the volume, system and user alike, until the
     * target is met, so this is a whole-device operation and the UI must say so before running it.
     *
     * [targetFreeBytes] must come from `StorageStatsProvider.cacheTrimTargetBytes` and not from a
     * round number. `freeStorage` is an escalating ladder on which app cache is only rungs 4 and 8; a
     * target it cannot satisfy walks on to prune unused static shared libraries and to uninstall
     * instant apps, neither of which is cache. The provider's KDoc carries the full argument.
     *
     * Returns whether the command exited 0. Not a byte count — `pm trim-caches` prints nothing on
     * success, so a caller that wants a number must re-measure `totalCacheBytes` either side.
     */
    fun trimCaches(targetFreeBytes: Long): Boolean =
        execute("pm trim-caches $targetFreeBytes").first == 0

    /**
     * Wipes [packageName]'s data **for [thorUserId]** — `pm clear` first, then a hidden-API
     * `IPackageManager` call.
     *
     * **Both rungs answer for themselves, which the second one did not used to.** `pm clear` blocks
     * on its own `ClearDataObserver` inside `PackageManagerShellCommand` and exits non-zero when the
     * wipe fails, so its exit code can be believed. `clearApplicationUserData` returns `void`: the
     * verdict only ever arrives on an `IPackageDataObserver`, and that argument was `null`, so the
     * old `true` meant nothing more than "the binder call did not throw". For the single most
     * destructive operation Thor performs, that is the worst place in the app to be optimistic — the
     * user was told their data was gone on the strength of a dispatch receipt. `awaitDataObserver`
     * now supplies a real observer and waits for it.
     *
     * `true` therefore means a verdict of "cleared" arrived. A refusal, a timeout and a dead
     * transport all give `false` and are distinguished in the log rather than in the return type.
     * That direction is deliberate: wiping data twice costs a user nothing, a false "done" costs
     * them the chance to try a privilege mode that would have worked.
     */
    // Hidden-API reflection (IPackageDataObserver) is intentional: it is the core privilege
    // mechanism, guarded by the :bypass VMRuntime unseal.
    @SuppressLint("PrivateApi")
    fun clearAppData(packageName: String): Boolean {
        // 1. Try shell first. Both rungs name the same user: the reflection rung below already
        // passed thorUserId, while this one passed none at all — and `pm clear` with no `--user`
        // seeds USER_SYSTEM, so from a work profile the shell rung wiped the primary user's copy
        // and exited 0, and the reflection rung that would have done the right thing never ran.
        val result = execute(clearAppDataCommand(packageName.escapeForShell(), thorUserId))
        if (result.first == 0) return true

        // 2. Fallback to reflection. The observer is no longer null: clearApplicationUserData
        // returns void, so this is the only channel the verdict can arrive on, and without it the
        // most destructive rung in the app was also its least honest one.
        val outcome = runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            val observerClass = Class.forName("android.content.pm.IPackageDataObserver")
            awaitDataObserver("Shizuku", packageName) { observer ->
                Bypass.invoke<Any?>(
                    pm.javaClass,
                    pm,
                    "clearApplicationUserData",
                    arrayOf(String::class.java, observerClass, Int::class.javaPrimitiveType!!),
                    packageName,
                    observer,
                    thorUserId
                )
            }
        }.getOrElse { e ->
            // Only the binder lookup can land here; awaitDataObserver absorbs whatever the invoke
            // itself throws and answers UNVERIFIED for it.
            Logger.e("Shizuku", "clearAppData($packageName): could not reach IPackageManager", e)
            DataClearOutcome.UNVERIFIED
        }

        return outcome == DataClearOutcome.CLEARED
    }

    fun getTotalCacheSizeWithShizuku(): Long {
        var totalCacheBytes = 0L
        val result = execute("dumpsys diskstats")

        result.second?.lines()?.forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("Cache Size:")) {
                try {
                    val sizeString = trimmedLine.substringAfter(":").trim()
                    val bytes =
                        NumberFormat.getNumberInstance(Locale.US).parse(sizeString)?.toLong() ?: 0L
                    totalCacheBytes += bytes
                } catch (e: Exception) {
                    Logger.e("Shizuku", "Failed to parse cache size line: $trimmedLine", e)
                }
            }
        }
        return totalCacheBytes
    }

    fun setAppRestricted(context: Context, packageName: String, restricted: Boolean): Boolean {
        // `ignore`/MODE_IGNORED restricts and `allow`/MODE_ALLOWED lifts, matching the shell rung's
        // own pair. This is also what the read-back below expects to find afterwards.
        val expectedMode =
            if (restricted) android.app.AppOpsManager.MODE_IGNORED
            else android.app.AppOpsManager.MODE_ALLOWED

        // The op code and the target uid are resolved once, here, instead of inside the reflection
        // rung where the `strOpToOp` call used to sit: the read-back needs both, and a write and a
        // read that each resolve their own op are two chances to name different ones. Null means
        // "could not resolve", which the read-back answers as "could not tell" rather than as a
        // disagreement.
        //
        // The uid is also what makes the read address the same user as the write:
        // `Packages.packageUid` goes through Thor's own PackageManager, so it carries Thor's user —
        // the one the shell rung below spells out as `--user thorUserId`.
        val opCode = runCatching {
            Bypass.invoke<Int>(
                android.app.AppOpsManager::class.java,
                null,
                "strOpToOp",
                "android:run_any_in_background"
            )
        }.getOrElse { e ->
            Logger.e("Shizuku", "strOpToOp(android:run_any_in_background) failed", e)
            null
        }
        val uid = runCatching { Packages(context).packageUid(packageName) }.getOrElse { e ->
            Logger.e("Shizuku", "packageUid failed for $packageName", e)
            null
        }

        // The read-back both rungs are judged by. Three answers, and the third is the point: true
        // (the op is in the requested mode), false (it is not), null (could not tell) — never false
        // for "could not tell".
        //
        // It reads through the privileged binder rather than the in-process AppOpsManager, and the
        // usual justification for that covers only the bottom of Thor's range.
        // `AppOpsService.checkOperation` does call `verifyIncomingUid` — which throws
        // SecurityException at a caller asking about a uid other than its own without
        // UPDATE_APP_OPS_STATS — but only on API 28, where `checkOperation` calls it directly,
        // and 29, where `checkOperationImpl` does. API 30 dropped it, and current AOSP states the
        // split outright: `validateOpRequest(..., shouldVerifyUid, ...)` is passed `false` by
        // `checkOperation` and `true` by `noteOperation`/`startOperation`. On 30..37 — nearly all
        // of Thor's range — an in-process read is not refused by that check.
        //
        // Routing it here is still right, for reasons that do cover the range. From API 31
        // `checkOperationImpl` also calls `verifyIncomingPackage`, which refuses a package the
        // caller cannot see, and a Thor reading across a user boundary is exactly that caller. And
        // `checkOperationUnchecked` turns its own `verifyAndGetBypass` SecurityException into
        // `opToDefaultMode(code)`, so a read that goes wrong in process answers a plausible mode
        // rather than throwing — worse than answering null. Reading with the identity that
        // performed the write is also what makes this a check of that write and not of some other
        // caller's view of it.
        //
        // What comes back is the mode actually in force, not a record of what was written — with
        // one blind spot worth naming. `checkOperationUnchecked` consults the *uid*-level mode
        // before it ever reaches the package entry (`mAppOpsCheckingService.getUidMode(...)` on
        // recent releases, the `uidState.opModes` lookup on 30..33) and returns it whenever it
        // differs from the op's default. Both of Thor's writes are package-level — `appops set
        // <pkg> android:run_any_in_background ...` and `setMode(code, uid, pkg, mode)` — so any
        // uid-level mode on RUN_ANY_IN_BACKGROUND makes this read report a value unrelated to
        // whether Thor's write landed. `checkOperationRaw` would not help: `raw` only drops the
        // foreground evaluation, and the uid branch returns the uid mode either way. The masking is
        // read straight out of AppOpsService; what is missing is a writer. Settings' battery
        // "Restricted" setting uses the package-level
        // `setMode(OP_RUN_ANY_IN_BACKGROUND, uid, packageName, mode)`, and the only uid-level
        // writer identified is `appops set --uid`, which Thor never issues. Recorded as a known
        // blind spot, not as an observed bug.
        fun opReadsBackAsExpected(): Boolean? {
            if (opCode == null || uid == null) return null
            return runCatching {
                val appops =
                    asInterface("com.android.internal.app.IAppOpsService", Context.APP_OPS_SERVICE)
                Bypass.invoke<Int>(
                    appops::class.java,
                    appops,
                    "checkOperation",
                    arrayOf(
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        String::class.java
                    ),
                    opCode,
                    uid,
                    packageName
                ) == expectedMode
            }.getOrElse { e ->
                // `null` and not `false`: `checkOperation(int, int, String)` has been on
                // IAppOpsService for a long time, but it has NOT been verified on every release in
                // 28..37, and a NoSuchMethodException here means "this device's signature differs",
                // not "the restriction failed".
                //
                // What each rung does with that null is one rule, stated here once:
                //
                //   A rung may fail OPEN on an unreadable read-back only if it has a self-report
                //   that is evidence independent of the read-back. With no such evidence it fails
                //   CLOSED.
                //
                // Rung 1 has that evidence and so fails open: `appops set` exits non-zero for an
                // unknown package or op, so its 0 is the platform's own statement about a real op,
                // and demoting it because a second opinion was unavailable would report failures
                // for restrictions that were applied.
                //
                // Rung 2 has none and so fails closed. `IAppOpsService.setMode` returns void, so
                // the only thing the reflective call can report is that it did not throw — which
                // is not a mode, and is not even reliably "not denied". Read across 28..37, the
                // one refusal that reaches the caller is `enforceManageAppOpsModes` (no
                // MANAGE_APP_OPS_MODES), which sits ahead of the try on every release. The
                // "this package is not under that uid" refusal never does: before API 30
                // `getOpsRawLocked` met a mismatch with `Slog.w("Bad call: specified package … but
                // it is really …")` and a null, so `setMode` changed nothing and returned
                // normally; from API 30 the check does throw, but from inside `verifyAndGetBypass`,
                // where `setMode` catches it (`Slog.e(TAG, "Cannot setMode", e); return;`, and the
                // same through `logVerifyAndGetBypassFailure` on API 35+). Current AOSP adds a
                // second silent return ahead of that one for `!isIncomingPackageValid(...)`. So
                // "setMode throws when it is denied" — the claim this comment used to rest on — is
                // true nowhere in Thor's range.
                //
                // The clear-data sites fail closed under the same rule and for the same reason:
                // "the reflective invoke did not throw" is their entire evidence too.
                Logger.e("Shizuku", "checkOperation read-back unavailable for $packageName", e)
                null
            }
        }

        // 1. Try shell first. The user this names is not the `pm` story retold: `appops` seeds
        // UserHandle.USER_CURRENT and system_server resolves it with ActivityManager.getCurrentUser(),
        // so the bare line landed on whoever was in the *foreground* at the moment it ran — the
        // parent profile for a Thor sitting in a work profile, and a value free to change between
        // this write and the read-back that is supposed to confirm it. The reflection rung below
        // never had that problem: it addresses the app by uid, and Packages.packageUid resolves that
        // through Thor's own PackageManager, so it already carried Thor's user. Naming thorUserId
        // here is what makes the two rungs of this one operation agree on a target.
        // The package is escaped for the same reason it is everywhere else in this object (#40).
        val result = execute(
            backgroundRestrictionCommand(packageName.escapeForShell(), thorUserId, restricted)
        )
        // `!= false`, not `== true`: this is the open side of the rule stated at the read-back, and
        // the independent evidence it rests on is this rung's own exit code. Null is "could not
        // read the op back" and leaves that exit code standing. Only a definite disagreement —
        // the op is not in the mode just requested — falls through to rung 2 rather than reporting
        // a success nobody confirmed.
        if (result.first == 0 && opReadsBackAsExpected() != false) return true

        // 2. Fallback to reflection. Without an op code or a uid there is nothing to call setMode
        // with, which is the same `false` the surrounding runCatching used to produce when
        // `strOpToOp` or `packageUid` threw inside it.
        if (opCode == null || uid == null) return false
        val reflectionRan = runCatching {
            val appops =
                asInterface("com.android.internal.app.IAppOpsService", Context.APP_OPS_SERVICE)
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
                opCode,
                uid,
                packageName,
                expectedMode
            )
            true
        }.getOrElse { e ->
            // Logged rather than swallowed: when this rung is the one that has to work, its
            // SecurityException is the line a bug report needs.
            Logger.e("Shizuku", "setMode(RUN_ANY_IN_BACKGROUND) failed for $packageName", e)
            false
        }
        // The closed side of the rule: this rung's verdict is the read-back and nothing else, so an
        // unreadable read-back is `false` here where it is survivable at rung 1. `!= false` in this
        // position was the bug — on API 33 a `setMode` AppOpsService refuses is swallowed there
        // rather than thrown (`reflectionRan` is still true) and an unreadable `checkOperation`
        // answers null, so `true && (null != false)` reported a success with nothing restricted.
        //
        // `reflectionRan` stays in the expression as a guard, not as evidence: for
        // `restricted = false` the expected mode is MODE_ALLOWED, which is also
        // RUN_ANY_IN_BACKGROUND's platform default, so an op nobody ever wrote reads back as
        // expected. Without the conjunct a `setMode` that threw would be reported as a success by
        // the default mode alone.
        //
        // DhizukuHelper.setAppRestricted's rung 2 lands on the same fail-closed answer by a
        // different route, and both reasons are load-bearing. There the binder is double-wrapped
        // — Dhizuku's own wrapper from `DhizukuAPI.binderWrapper`, with `ShizukuBinderWrapper` put
        // on top of it — so that rung is transport-dead on a Dhizuku-only device and has nothing to
        // report either way. Here the binder is wrapped once and the call is live; this rung fails
        // closed because a void return is not evidence, not because it cannot run. A later edit
        // that "unifies" the two by keeping one reason would leave the wrong one behind.
        return reflectionRan && opReadsBackAsExpected() == true
    }

    // The user id every `--user` below names is [thorUserId], read in process.
    //
    // This used to shell out to `am get-current-user` and cache the answer. Nothing was broken at
    // shell uid — the call is permitted there and returns the same number on a single-user device
    // — but it answered a different question from the one the disable rungs ask. Those target
    // `Packages.myUserId`, so on a work-profile device (Thor in profile 10, parent 0 in the
    // foreground) rung 1 disabled for 10 while the `pm uninstall -k` fallback removed for 0: a
    // fallback acting on a package the rung it is covering for never touched. Both now resolve
    // through the one symbol, for the reason its KDoc gives, and the cache goes with the shell
    // call — [thorUserId] is a process-lifetime constant.
    //
    // The same swap on the Dhizuku side fixes an outright failure rather than a latent mismatch;
    // see the note in DhizukuHelper.

    /**
     * The user-facing uninstall: removes [packageName] for [thorUserId], data included.
     *
     * This used to name the user only for system packages. Ordinary user apps — everything the
     * uninstall button is normally pressed on — took a bare `pm uninstall`, selected by a
     * `canUninstallNormally` predicate that read `FLAG_SYSTEM == 0` and nothing else. That was not
     * the harmless default it looked like: `PackageManagerShellCommand.runUninstall` seeds
     * `userId = UserHandle.USER_ALL` and converts it to `DELETE_ALL_USERS`, so from a work profile
     * the line removed the app **and its data for every user on the device** and exited 0.
     *
     * Both the predicate and the branch are gone rather than corrected, because there is no version
     * of "system apps get `--user`, ordinary ones do not" that is right; [uninstallCommand] carries
     * the reasoning. The root gateway and the Dhizuku helper already named their user on this same
     * operation — this was the one site of the class that did not.
     *
     * **Naming the user is stricter than the bare form, not merely narrower.** [uninstallCommand]'s
     * "on a single-user device this changes nothing" is a claim about *which users are affected*,
     * and it does not extend to *whether the command succeeds*. Once `userId != USER_ALL`,
     * `runUninstall` first does
     * `getPackageInfo(pkg, MATCH_STATIC_SHARED_AND_SDK_LIBRARIES, userId)` and stops with
     * `Failure [not installed for N]` and exit 1 when that is null. Those flags carry neither
     * `MATCH_UNINSTALLED_PACKAGES` nor `MATCH_ARCHIVED_PACKAGES`, so every package whose
     * `PackageUserState.installed` bit is false for this user is refused here, at user 0, on a
     * device that has only one user — where the bare form took the `USER_ALL` path and skipped the
     * precondition altogether. Thor lists exactly those packages, because its app sweep queries
     * with `MATCH_UNINSTALLED_PACKAGES`: a Play-Store-archived app on API 35+, and anything another
     * tool (or Thor's own [freezeSystemAppForUser]) removed with `pm uninstall -k`.
     *
     * The rung that covers the difference is the reflection fallback in
     * `ShizukuReflector.uninstallApp`, which reads `false` here as "try `PackageInstaller` instead"
     * — and `PackageInstaller.uninstall` has no equivalent precondition. That fallback was defeated
     * by the same condition until its `getInfoForPackage` lookup was widened to the two match flags
     * `pm` omits; that widening is what makes this trade-off survivable, so the two are one change.
     * The answer is not to drop `--user` again: that trades a failure the ladder recovers from for
     * a silent removal on every user of the device.
     *
     * Takes no `Context`, unlike its neighbours in this object: the only thing it needed one for
     * was constructing the `Packages` that answered the predicate.
     */
    fun uninstallApp(packageName: String): Boolean {
        // Escape the package identifier before interpolating it into the shell command, mirroring
        // the Dhizuku helper (#40). thorUserId is an Int, so it needs no escaping.
        val escapedPackage = packageName.escapeForShell()
        return try {
            execute(uninstallCommand(escapedPackage, thorUserId)).first == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Removes a **preinstalled** app for the current user *without* deleting its data — the last
     * rung of the system-app freeze, and deliberately not the same function as [uninstallApp].
     *
     * `-k` sets `DELETE_KEEP_DATA`, which leaves `/data/user/N/<pkg>` and `/data/user_de/N/<pkg>`
     * in place instead of having `installd` destroy them. Measured on the HyperOS device that
     * refuses to disable system packages at all: `pm uninstall -k --user 0` then
     * `pm install-existing --user 0` returned the app with **byte-identical `ceDataInode` and
     * `deDataInode`**. For a system app the data survives indefinitely — the package record never
     * goes away, because the APK is still on the read-only partition and `pm uninstall --user` only
     * clears `PackageUserState.installed` for one user.
     *
     * **Runtime permission grants survived the round trip that was measured.** This was previously
     * documented here as a likely cost — "the app may come back having to ask for its permissions
     * again" — and that guess measured false. On a stock AOSP API 36 emulator at uid 2000: `pm
     * grant` a revocable runtime permission, round-trip through `pm uninstall -k` /
     * `pm install-existing`, and the permission returns `granted=true` with its flags unchanged.
     * The grant was made from the shell rather than by a user tapping Allow, and app-ops were not
     * tested, so this retires the old claim without establishing its opposite. Note what `-k` does
     * *not* do: it keeps the data directories, not the whole `PackageUserState` — `installed` still
     * goes false, which is the sentence two paragraphs above.
     *
     * **This rung does not exist at shell uid on API 37 — and it is the only thing that does not.**
     * Measured on two Android 17 builds (`CP31.260623.005`, and `CE2A.260420.019` on
     * `com.android.wallpaperbackup`), at uid 2000, restoring state afterwards:
     * ```
     * pm uninstall -k --user 0 <system pkg>
     *   API 36 -> Success, exit 0
     *   API 37 -> Failure [only root can delete system app for a particular user], exit 1
     * pm disable-user --user 0 <system pkg>   API 37 -> "new state: disabled-user", enabled=3
     * pm suspend      --user 0 <system pkg>   API 37 -> "new suspended state: true", suspended=true
     * ```
     * So Android 17 did **not** close the shell uid out of freezing preinstalled apps; it closed it
     * out of *removing* them for one user. `PackageManagerShellCommand.java:2281-2293` on
     * android17-release requires `Binder.getCallingUid() == Process.ROOT_UID` before honouring
     * `--user` on a `FLAG_SYSTEM` package, and that guard exists in no android16 branch.
     * Nothing else moved: `setEnabledSetting` is untouched, and
     * `Flags.protectSystemRequiredPackages()` is not live on either build —
     * `device_config get package_manager_service protect_system_required_packages` reads null.
     *
     * That matters for who can actually reach this line. On stock Android 17 rung 2 succeeds, so
     * the chain never gets here at all. The devices that do get here are the ones whose *OEM*
     * refuses to let the shell uid disable a system package (Xiaomi HyperOS; see
     * `uninstallFreezeFallbackAllowed`), and on Android 17 those users have no mechanic left at
     * shell uid. The package is left untouched, so the caller sees an honest failure rather than a
     * wrong state — and the failure now carries `pm`'s own sentence so it can name root as the way
     * out. A Shizuku started as root (uid 0) clears the guard and is unaffected. This is a platform
     * restriction, not something to work around — do not add a rung below it.
     *
     * The adb client's scary "there is no way to remove the remaining data" warning about `-k` is
     * about orphaned data left behind by a *full* uninstall of a user app. It does not apply here,
     * and it did not appear on the device.
     *
     * Kept separate from [uninstallApp] on purpose: adding `-k` there would silently make the
     * user-facing "uninstall this app" feature leave data behind on every app it removes.
     *
     * Returns [SystemAppRemovalOutcome] rather than a `Boolean` because the string beside the exit
     * code is the whole point of this rung's failure; see that class for what dropping it cost.
     */
    fun freezeSystemAppForUser(packageName: String): SystemAppRemovalOutcome = try {
        val currentUser = thorUserId
        val (code, output) = execute(
            "pm uninstall -k --user $currentUser ${packageName.escapeForShell()}"
        )
        val platformMessage = output?.trim()?.ifBlank { null }
        if (code != 0) {
            Logger.e(
                "Shizuku",
                "freezeSystemAppForUser($packageName): `pm uninstall -k --user $currentUser` " +
                    "exited $code — ${platformMessage ?: "no output"}"
            )
        }
        SystemAppRemovalOutcome(
            succeeded = code == 0,
            exitCode = code,
            platformMessage = platformMessage,
        )
    } catch (e: Exception) {
        Logger.e("Shizuku", "freezeSystemAppForUser($packageName) failed", e)
        // exitCode -1: there was no exit code to read, matching what execute() reports for the
        // same situation. The message is still carried out — a binder or user-id failure is as
        // worth showing as a platform refusal.
        SystemAppRemovalOutcome(succeeded = false, exitCode = -1, platformMessage = e.message)
    }

    fun reinstallApp(packageName: String): Boolean {
        return try {
            // Escape the package identifier before interpolating it (#40).
            val escapedPackage = packageName.escapeForShell()
            execute("pm install-existing --user $thorUserId $escapedPackage").first == 0
        } catch (_: Exception) {
            false
        }
    }

    fun execute(command: String, root: Boolean = isRoot): Pair<Int, String?> = runCatching {
        val binder = Shizuku.getBinder() ?: return -1 to "Shizuku binder is null"
        IShizukuService.Stub.asInterface(binder)
            .newProcess(arrayOf(if (root) "su" else "sh"), null, null)
            .run {
                // Volatile via AtomicReference: the reader threads publish into these, and the
                // timeout path may read them after a join() that timed out (no happens-before),
                // so a plain local var could observe a stale/torn value.
                val output = java.util.concurrent.atomic.AtomicReference("")
                val error = java.util.concurrent.atomic.AtomicReference("")

                // Daemon so a stuck read on a hung child can never keep the process/VM alive.
                val outThread = Thread {
                    runCatching {
                        output.set(inputStream.text)
                    }.onFailure { err ->
                        Logger.e("Shizuku", "Failed to read standard output", err)
                    }
                }.apply { isDaemon = true }

                val errThread = Thread {
                    runCatching {
                        error.set(errorStream.text)
                    }.onFailure { err ->
                        Logger.e("Shizuku", "Failed to read error output", err)
                    }
                }.apply { isDaemon = true }

                var timedOut = false
                try {
                    outThread.start()
                    errThread.start()

                    runCatching {
                        ParcelFileDescriptor.AutoCloseOutputStream(outputStream).use {
                            it.write((command + "\nexit\n").toByteArray())
                            it.flush()
                        }
                    }.onFailure { err ->
                        Logger.e("Shizuku", "Failed to write command to process outputStream", err)
                    }

                    // Bounded wait: waitForTimeout is a synchronous binder transact() that does
                    // NOT respond to Thread.interrupt(). The ONLY bound is EXECUTE_TIMEOUT_MS; this
                    // is a hang backstop, not coroutine-cancellation-interruptible. The
                    // InterruptedException catch below is harmless defensive code, not a live path.
                    val exited = try {
                        waitForTimeout(EXECUTE_TIMEOUT_MS, TimeUnit.MILLISECONDS.name)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        Logger.e("Shizuku", "Command wait interrupted: $command", e)
                        false
                    }

                    if (exited) {
                        val exitCode = waitFor()
                        // Give the readers a bounded window to drain, then stop waiting on them.
                        outThread.join(READER_JOIN_TIMEOUT_MS)
                        errThread.join(READER_JOIN_TIMEOUT_MS)
                        exitCode to output.get().ifBlank { error.get() }
                    } else {
                        timedOut = true
                        Logger.e(
                            "Shizuku",
                            "Command timed out after ${EXECUTE_TIMEOUT_MS}ms, destroying process: $command"
                        )
                        // Close the FDs first: this unblocks the reader threads immediately,
                        // even if destroy() (a binder call) later hangs. Killing before closing
                        // would block the timeout path on a stuck destroy while readers stay stuck.
                        runCatching { inputStream.close() }
                        runCatching { errorStream.close() }
                        runCatching { outputStream.close() }
                        outThread.interrupt()
                        errThread.interrupt()
                        outThread.join(READER_JOIN_TIMEOUT_MS)
                        errThread.join(READER_JOIN_TIMEOUT_MS)
                        // Readers are already free; now request the (possibly slow) kill.
                        runCatching { destroy() }
                        -1 to "Command timed out after ${EXECUTE_TIMEOUT_MS}ms".let { msg ->
                            output.get().ifBlank { error.get() }.ifBlank { msg }
                        }
                    }
                } finally {
                    // Always tear the process down (idempotent even if already destroyed on timeout).
                    if (!timedOut) runCatching { destroy() }
                    // Close the FDs explicitly (each guarded): releases file descriptors and
                    // unblocks the reader threads even if destroy() (a binder call) hangs or fails.
                    runCatching { inputStream.close() }
                    runCatching { errorStream.close() }
                    runCatching { outputStream.close() }
                }
            }
    }.getOrElse { err ->
        Logger.e("Shizuku", "Command execution failed: $command", err)
        -1 to err.stackTraceToString()
    }

    private val ParcelFileDescriptor.text
        get() = ParcelFileDescriptor.AutoCloseInputStream(this)
            .use { it.bufferedReader().readText() }
}
