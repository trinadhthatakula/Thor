// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.rootservice

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import com.valhalla.superuser.ipc.RootService
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.LEGACY_ROOT_SUSPENDER_IDENTITY
import com.valhalla.thor.domain.model.SHELL_SUSPENDER_IDENTITY
import com.valhalla.thor.domain.model.parseSuspendingPackages
import com.valhalla.thor.util.Logger
import java.lang.reflect.InvocationTargetException

/**
 * The user id every suspend call in this file writes and every readback in it parses.
 *
 * The two numbers have to be the same one. Suspending user 0 and then verifying against user 10's
 * section of the dump would confirm a state nobody set, so the constant exists to keep the write and
 * the read from drifting apart the next time one of them is edited.
 */
private const val TARGET_USER_ID = 0

/**
 * The platform's own identity — what a device-owner/DPM suspension is recorded under.
 *
 * Kept last in the suspend fallback order on purpose: a suspension attributed to `android` shows the
 * user a system-owned pause dialog with no hint that Thor is responsible, so it is a last resort for
 * writing and mainly a name Thor has to be able to *clear*.
 */
private const val PLATFORM_SUSPENDER_IDENTITY = "android"

/**
 * A highly-stable, persistent root-level daemon service implementing privileged actions.
 *
 * The whole daemon deliberately reaches hidden framework APIs (IPackageManager, ServiceManager,
 * SuspendDialogInfo) via reflection — that is the entire point of running in the privileged :root
 * process — so PrivateApi is suppressed class-wide rather than method-by-method.
 */
@SuppressLint("PrivateApi")
class ThorRootService : RootService() {

    init {
        // This daemon runs in a separate :root (app_process) process where ThorApplication.onCreate
        // never executes, so Logger.isDebug — a runtime flag set there for the main process — would
        // stay false and silently drop this daemon's logs. Mirror it so root-side diagnostics are
        // visible in debug builds (Logger is Thor's own, gated on this flag; safe in release).
        Logger.isDebug = BuildConfig.DEBUG
    }

    override fun onBind(intent: Intent): IBinder {
        return object : IThorRootService.Stub() {
            override fun setAppSuspended(packageName: String, suspended: Boolean): Boolean {
                this@ThorRootService.enforceCaller()
                return this@ThorRootService.setAppSuspendedAs(packageName, suspended, null)
            }

            override fun setAppSuspendedAs(
                packageName: String,
                suspended: Boolean,
                suspendingPackage: String?
            ): Boolean {
                this@ThorRootService.enforceCaller()
                return this@ThorRootService.setAppSuspendedAs(
                    packageName,
                    suspended,
                    suspendingPackage
                )
            }

            override fun dumpPackage(packageName: String): String? {
                this@ThorRootService.enforceCaller()
                return this@ThorRootService.dumpPackage(packageName)
            }

            override fun clearAppData(packageName: String): Boolean {
                this@ThorRootService.enforceCaller()
                return this@ThorRootService.clearAppData(packageName)
            }
        }
    }

    /**
     * Suspends or unsuspends [packageName] as [suspendingPackage], and returns whether the
     * platform's own record agrees afterwards.
     *
     * The return value used to be `runCatching { … }.isSuccess` over a loop that broke as soon as
     * `setPackagesSuspendedAsUser` returned an empty failure array. That array is not evidence:
     * naming a suspender that owns nothing leaves `oldSuspendParams == null == newSuspendParams`, so
     * `changed == false`, so the package is logged "No change is needed" and is deliberately left
     * *out* of the returned array. A root-mode Thor asking to lift a Shizuku-era suspension (recorded
     * as [SHELL_SUSPENDER_IDENTITY]) therefore removed nothing, reported success, and left the app
     * stuck suspended with no error anywhere. Every success below is now a re-read of the record via
     * [readSuspenders] instead.
     */
    private fun setAppSuspendedAs(
        packageName: String,
        suspended: Boolean,
        suspendingPackage: String?
    ): Boolean {
        Logger.i(
            "Odin",
            "setAppSuspendedAs: packageName=$packageName, suspended=$suspended, " +
                    "as=${suspendingPackage ?: "<unspecified>"}"
        )
        return runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "package") as IBinder
            val pm = Class.forName("android.content.pm.IPackageManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            val pmClass = Class.forName("android.content.pm.IPackageManager")

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw UnsupportedOperationException("suspend via reflection requires API 29+")
            }

            val dialogInfoClass = Class.forName("android.content.pm.SuspendDialogInfo")

            if (suspended) {
                suspendAsAnyOf(
                    pmClass, pm, dialogInfoClass, packageName,
                    dialogInfo = buildSuspendDialogInfo(),
                    identities = suspendIdentities(suspendingPackage)
                )
            } else {
                unsuspendAllOf(
                    pmClass, pm, dialogInfoClass, packageName,
                    identities = unsuspendIdentities(packageName, suspendingPackage)
                )
            }
        }.onFailure { e ->
            Logger.e("Odin", "Failed to set app suspended for $packageName", e)
        }.getOrDefault(false)
    }

    /**
     * The identities to try, in order, when suspending.
     *
     * An explicit [suspendingPackage] is the *only* one tried — the caller read the record and knows
     * which identity it means; falling back to another name behind its back would write a second
     * suspension entry nobody asked for.
     *
     * With none given, this keeps the historical order, and Thor's own applicationId stays first for
     * a reason: the system builds the user-visible "managed by Thor" line on the paused-app dialog
     * out of the recorded suspender name. That is the whole point of root writing its own name rather
     * than borrowing the shell's, it works today, and this change must not disturb it.
     */
    private fun suspendIdentities(suspendingPackage: String?): List<String> =
        suspendingPackage?.let(::listOf) ?: listOf(
            this@ThorRootService.packageName,
            SHELL_SUSPENDER_IDENTITY,
            PLATFORM_SUSPENDER_IDENTITY
        )

    /**
     * The identities to clear when unsuspending [targetPackage].
     *
     * An explicit [suspendingPackage] is again the only entry touched: the gateway reads the record
     * and calls once per recorded suspender, so each call stays a single well-defined removal.
     *
     * With none given — the legacy two-argument `setAppSuspended` entry point — the record itself is
     * the list. Guessing is exactly what caused the bug: a Shizuku-era suspension is recorded as
     * [SHELL_SUSPENDER_IDENTITY], and a root-mode Thor that only ever named its own package removed
     * nothing. When the record cannot be read at all we still make a best-effort pass over every name
     * Thor has written across its history, including the pre-GH#239
     * [LEGACY_ROOT_SUSPENDER_IDENTITY]; that cannot turn into a false success, because the post-write
     * readback in [unsuspendAllOf] will be just as unreadable and will fail closed.
     */
    private fun unsuspendIdentities(
        targetPackage: String,
        suspendingPackage: String?
    ): List<String> {
        if (suspendingPackage != null) return listOf(suspendingPackage)
        // A readable empty set is a real answer — nothing is recorded, so there is nothing to remove
        // and [unsuspendAllOf]'s readback confirms it. Only an unreadable dump falls through.
        readSuspenders(targetPackage)?.let { return it.toList() }
        return listOf(
            this@ThorRootService.packageName,
            SHELL_SUSPENDER_IDENTITY,
            PLATFORM_SUSPENDER_IDENTITY,
            LEGACY_ROOT_SUSPENDER_IDENTITY
        )
    }

    /**
     * Suspends [targetPackage] as the first of [identities] the platform actually ends up recording.
     *
     * The loop reads the record back after each attempt rather than trusting
     * [tryCallSetSuspended]'s return value, so "the call was accepted" and "the suspension exists"
     * stay separate facts. An unreadable record aborts instead of moving on to the next identity:
     * without knowing whether the previous attempt landed, trying another name risks stacking a
     * second suspension entry on the package that only a second unsuspend could remove.
     */
    private fun suspendAsAnyOf(
        pmClass: Class<*>, pm: Any?, dialogInfoClass: Class<*>,
        targetPackage: String, dialogInfo: Any?, identities: List<String>
    ): Boolean {
        for (caller in identities) {
            val accepted = tryCallSetSuspended(
                pmClass, pm, dialogInfoClass, targetPackage, true, dialogInfo, caller
            )
            if (!accepted) continue

            val recorded = readSuspenders(targetPackage)
            if (recorded == null) {
                Logger.w(
                    "Odin",
                    "Cannot read $targetPackage's suspenders after suspending as $caller; " +
                            "refusing to report a success we did not verify"
                )
                return false
            }
            if (caller in recorded) {
                Logger.i("Odin", "Suspended $targetPackage; platform recorded suspender $caller")
                return true
            }
            Logger.w(
                "Odin",
                "setPackagesSuspendedAsUser reported no failure for $targetPackage as $caller, " +
                        "but the platform records $recorded — that identity owns nothing"
            )
        }
        return false
    }

    /**
     * Removes every one of [identities] from [targetPackage]'s suspension record.
     *
     * Deliberately no break on the first accepted call. From API 30 `PackageUserState.suspendParams`
     * is a map, so a package can carry several suspension entries at once and `suspended` stays true
     * while any of them survives — stopping early is how one gets left behind.
     *
     * Success means the identities we were asked to remove are gone. A suspension owned by somebody
     * else is not this call's to lift and is reported at warn level so the caller can name the owner
     * to the user rather than leaving them with an app that quietly stays paused.
     */
    private fun unsuspendAllOf(
        pmClass: Class<*>, pm: Any?, dialogInfoClass: Class<*>,
        targetPackage: String, identities: List<String>
    ): Boolean {
        for (caller in identities) {
            tryCallSetSuspended(
                pmClass, pm, dialogInfoClass, targetPackage, false, null, caller
            )
        }

        val recorded = readSuspenders(targetPackage)
        if (recorded == null) {
            Logger.w(
                "Odin",
                "Cannot read $targetPackage's suspenders after unsuspending; " +
                        "refusing to report a success we did not verify"
            )
            return false
        }
        val remaining = identities.filter { it in recorded }
        if (remaining.isNotEmpty()) {
            Logger.w(
                "Odin",
                "Unsuspend of $targetPackage left $remaining recorded as suspenders"
            )
            return false
        }
        if (recorded.isEmpty()) {
            Logger.i(
                "Odin",
                "Unsuspend of $targetPackage verified; nothing is recorded as suspending it"
            )
        } else {
            Logger.w(
                "Odin",
                "Removed $identities from $targetPackage, but $recorded still own entries — it " +
                        "stays suspended until whoever owns those lifts them"
            )
        }
        return true
    }

    /**
     * [callSetSuspended] with its exceptions turned into `false`.
     *
     * Every identity in a fallback list is a guess about a platform we cannot interrogate, so one
     * throwing must not abandon the rest. The failure is logged with the `InvocationTargetException`
     * unwrapped, because the wrapper's own message is always the useless "null".
     */
    private fun tryCallSetSuspended(
        pmClass: Class<*>, pm: Any?, dialogInfoClass: Class<*>,
        packageName: String, suspended: Boolean, dialogInfo: Any?, caller: String
    ): Boolean = try {
        callSetSuspended(
            pmClass, pm, dialogInfoClass, packageName, suspended, dialogInfo, caller
        )
    } catch (e: Exception) {
        val cause = if (e is InvocationTargetException) e.cause else e
        Logger.w(
            "Odin",
            "setPackagesSuspendedAsUser threw for $packageName as $caller: " + cause?.message
        )
        false
    }

    /**
     * Invokes `IPackageManager.setPackagesSuspendedAsUser` through whichever overload this platform
     * exposes, and reports whether the call was **accepted**.
     *
     * That is not the same as "it worked", and the difference is the bug this file was carrying. The
     * returned array holds only packages the platform actively refused; a package whose state did not
     * change — because the named suspender owned no entry — is not in it, so an empty array is
     * equally consistent with "done" and "silently did nothing". Callers must confirm the outcome
     * with [readSuspenders]; this value only says the reflection found a signature and the framework
     * did not reject the request outright.
     */
    private fun callSetSuspended(
        pmClass: Class<*>, pm: Any?, dialogInfoClass: Class<*>,
        packageName: String, suspended: Boolean, dialogInfo: Any?, caller: String
    ): Boolean {
        // Android 15+ (API 35+): 9-arg signature. Not 34 — the shape itself says so. This overload
        // is where a suspension became cross-user: the suspender key turned into a UserPackage,
        // which is exactly why it carries a suspendingUserId *and* a targetUserId, and that landed
        // in 15. Gating it on 34 would only mean asking a 34 device for a method it does not have.
        try {
            Logger.i("Odin", "Trying API 35+ 9-arg signature with caller=$caller")
            val method = pmClass.getDeclaredMethod(
                "setPackagesSuspendedAsUser",
                Array<String>::class.java,
                Boolean::class.javaPrimitiveType,
                PersistableBundle::class.java,
                PersistableBundle::class.java,
                dialogInfoClass,
                Int::class.javaPrimitiveType,   // flags
                String::class.java,             // callingPackage
                Int::class.javaPrimitiveType,   // suspendingUserId
                Int::class.javaPrimitiveType    // targetUserId
            )
            val result = method.invoke(
                pm, arrayOf(packageName), suspended, null, null, dialogInfo, 0, caller,
                TARGET_USER_ID, TARGET_USER_ID
            ) as? Array<*>
            val failedList = result?.filterIsInstance<String>() ?: emptyList()
            Logger.i("Odin", "Successfully invoked API 35+ 9-arg signature. Failed packages: $failedList")
            return !failedList.contains(packageName)
        } catch (e: NoSuchMethodException) {
            Logger.d("Odin", "API 35+ signature not found: " + e.message)
        } catch (e: Exception) {
            Logger.e("Odin", "API 35+ signature invocation error", e)
            throw e
        }

        // Some API 33 builds: 8-arg signature
        try {
            Logger.i("Odin", "Trying API 33 8-arg signature with caller=$caller")
            val method = pmClass.getDeclaredMethod(
                "setPackagesSuspendedAsUser",
                Array<String>::class.java,
                Boolean::class.javaPrimitiveType,
                PersistableBundle::class.java,
                PersistableBundle::class.java,
                dialogInfoClass,
                Int::class.javaPrimitiveType,   // flags
                String::class.java,             // callingPackage
                Int::class.javaPrimitiveType    // userId
            )
            val result = method.invoke(
                pm, arrayOf(packageName), suspended, null, null, dialogInfo, 0, caller,
                TARGET_USER_ID
            ) as? Array<*>
            val failedList = result?.filterIsInstance<String>() ?: emptyList()
            Logger.i("Odin", "Successfully invoked API 33 8-arg signature. Failed packages: $failedList")
            return !failedList.contains(packageName)
        } catch (e: NoSuchMethodException) {
            Logger.d("Odin", "API 33 signature not found: " + e.message)
        } catch (e: Exception) {
            Logger.e("Odin", "API 33 signature invocation error", e)
            throw e
        }

        // Android 10-13 (API 29-33): 7-arg signature
        try {
            Logger.i("Odin", "Trying API 29-33 7-arg signature with caller=$caller")
            val method = pmClass.getDeclaredMethod(
                "setPackagesSuspendedAsUser",
                Array<String>::class.java, Boolean::class.javaPrimitiveType,
                PersistableBundle::class.java, PersistableBundle::class.java,
                dialogInfoClass, String::class.java, Int::class.javaPrimitiveType
            )
            val result = method.invoke(
                pm, arrayOf(packageName), suspended, null, null, dialogInfo, caller, TARGET_USER_ID
            ) as? Array<*>
            val failedList = result?.filterIsInstance<String>() ?: emptyList()
            Logger.i("Odin", "Successfully invoked API 29-33 7-arg signature. Failed packages: $failedList")
            return !failedList.contains(packageName)
        } catch (e: Exception) {
            Logger.e("Odin", "API 29-33 signature invocation error", e)
            throw e
        }
    }

    /**
     * The identities the platform currently records as suspending [targetPackage] for
     * [TARGET_USER_ID], or `null` when the dump could not be trusted.
     *
     * The `null` is the entire point of this wrapper. `parseSuspendingPackages` cannot distinguish a
     * package with no suspenders from a dump that was truncated, denied, or in a format nobody has
     * seen — all three parse to an empty set — so "did we get a real dump?" has to be answered here,
     * before anything interprets that emptiness. Without the header check an unreadable dump would
     * parse empty and [unsuspendAllOf] would read it as "nothing left, we succeeded": the same
     * empty-means-success lie this change exists to remove, one layer further down.
     *
     * `dumpsys package <pkg>` always prints a `Package [<pkg>] (…):` block for an installed package;
     * a caller without `android.permission.DUMP` gets a `Permission Denial:` line instead, and a
     * truncated dump gets neither.
     */
    private fun readSuspenders(targetPackage: String): Set<String>? {
        val dump = dumpPackage(targetPackage) ?: return null
        if (!dump.contains("Package [$targetPackage]")) {
            Logger.w(
                "Odin",
                "dumpsys package $targetPackage returned no package block; suspender state unknown"
            )
            return null
        }
        return parseSuspendingPackages(dump, TARGET_USER_ID)
    }

    /**
     * Raw `dumpsys package <targetPackage>` output, or `null` when it could not be read.
     *
     * This lives on the root side because `PackageManagerService.dump` gates on
     * `android.permission.DUMP` through `DumpUtils.checkDumpAndUsageStatsPermission`
     * (android-16 `PackageManagerService.java:6689`), which the app process does not hold.
     *
     * The daemon is already `app_process` running as uid 0, so a plain [ProcessBuilder] inherits root
     * — going back out through `su` from in here would fork a second privileged shell for no reason.
     * The command is exec'd as an argv array rather than through `sh -c`, so [targetPackage] is one
     * argument and cannot be quoted out of; no escaping is applied because none would do anything.
     */
    private fun dumpPackage(targetPackage: String): String? = runCatching {
        val process = ProcessBuilder("dumpsys", "package", targetPackage)
            .redirectErrorStream(true)
            .start()
        val output = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } finally {
            // This daemon outlives any single call, so the unused stdin pipe is closed here rather
            // than left to finalization — one leaked fd per dump adds up over a long session.
            process.outputStream.close()
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            Logger.w("Odin", "dumpsys package $targetPackage exited $exitCode")
            return@runCatching null
        }
        output.takeIf { it.isNotBlank() }
    }.onFailure { e ->
        Logger.e("Odin", "Failed to dump package $targetPackage", e)
    }.getOrNull()

    /**
     * A `SuspendDialogInfo` carrying Thor's title and message, or `null` when this platform's builder
     * does not expose the setters it needs.
     *
     * **This never worked.** Both lookups asked for `CharSequence` overloads that do not exist —
     * `SuspendDialogInfo.Builder` takes `String` (and `@StringRes int`) — so the very first
     * `getMethod` threw `NoSuchMethodException` into a bare `catch` that returned `null`, and every
     * suspension Thor has ever made carried a null `dialogInfo`. Hence the [Logger.w] below: a
     * reflective lookup that degrades in silence is indistinguishable from one that works, and that
     * is how this hid for as long as it did.
     *
     * Worth knowing while reading this: the user-visible "managed by Thor" line on the paused-app
     * dialog is **not** produced here. The system derives that from the suspending package name,
     * which the root path already records correctly; this builder only adds a custom title and
     * message on top of it.
     */
    private fun buildSuspendDialogInfo(): Any? = try {
        val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
        val builder = builderClass.getDeclaredConstructor().newInstance()
        // setMessage(String) has been there since API 29, but setTitle(String) only arrived in API
        // 31 — before that a title had to be a @StringRes int, and one of *our* resource ids would
        // not resolve inside the system's dialog anyway. Asking for it unguarded on 29/30 throws and
        // costs us the message too, since a single catch covers the whole builder.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builderClass.getMethod("setTitle", String::class.java).invoke(builder, "Thor")
        }
        builderClass.getMethod("setMessage", String::class.java)
            .invoke(builder, "This app has been suspended by Thor.")
        // No setNeutralButtonAction call: its only valid arguments are BUTTON_ACTION_MORE_DETAILS (0)
        // and BUTTON_ACTION_UNSUSPEND (1), and the one this replaced passed 2. MORE_DETAILS is
        // already the platform default and the button is hidden when nothing handles the intent, so
        // leaving it unset is both correct and the smallest change.
        builderClass.getMethod("build").invoke(builder)
    } catch (e: Exception) {
        Logger.w("Odin", "SuspendDialogInfo.Builder unusable, suspending without a dialog: $e")
        null
    }

    private fun clearAppData(packageName: String): Boolean {
        return runCatching {
            val pmStub = Class.forName("android.content.pm.IPackageManager\$Stub")
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "package") as IBinder
            val asInterface = pmStub.getMethod("asInterface", IBinder::class.java)
            val pm = asInterface.invoke(null, binder)
            val pmClass = Class.forName("android.content.pm.IPackageManager")

            val method = pmClass.getDeclaredMethod(
                "clearApplicationUserData",
                String::class.java,
                Class.forName("android.content.pm.IPackageDataObserver"),
                Int::class.javaPrimitiveType
            )
            // clearApplicationUserData returns void — the real success/failure is delivered
            // asynchronously via IPackageDataObserver.onRemoveCompleted, which we deliberately do
            // not wire up. So a clean reflective invocation is the strongest signal available: a
            // thrown SecurityException / missing-package / bad-signature error propagates as
            // failure (via runCatching below), while a successfully dispatched wipe reports success.
            method.invoke(pm, packageName, null, 0)
        }.onFailure { e ->
            Logger.e("Odin", "Failed to clear app data for $packageName", e)
        }.isSuccess
    }
}
