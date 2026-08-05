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
import com.valhalla.thor.data.source.local.DataClearOutcome
import com.valhalla.thor.data.source.local.awaitDataObserver
import com.valhalla.thor.domain.model.LEGACY_ROOT_SUSPENDER_IDENTITY
import com.valhalla.thor.domain.model.SHELL_SUSPENDER_IDENTITY
import com.valhalla.thor.domain.model.parseSuspendingPackages
import com.valhalla.thor.util.Logger
import java.lang.reflect.InvocationTargetException

/**
 * The user id the two suspend entry points that carry no user of their own fall back to.
 *
 * It is no longer what this file writes: the user arrives over the binder
 * ([IThorRootService.setAppSuspendedAsForUser]) and is threaded through every function below,
 * because the daemon cannot work it out for itself. This process runs as uid 0 in user 0, so
 * `Process.myUserHandle()` answers 0 here for a Thor sitting in a work profile just as it does for
 * one in the primary user — the same blindness that made `clearAppData` take a user id.
 *
 * The value survives for [IThorRootService.setAppSuspended] and [IThorRootService.setAppSuspendedAs],
 * which have no user to pass and are kept at their historical behaviour rather than quietly
 * re-pointed at the caller's user, exactly as [IThorRootService.clearAppData] is: a method that means
 * something different depending on which build is answering it is worse than one that means the same
 * wrong thing every time.
 *
 * Whichever number is in play, the write and the readback have to be that one number. Suspending
 * user 0 and then verifying against user 10's section of the same dump would confirm a state nobody
 * set, which is why the id below is passed down as a parameter rather than read again from here by
 * the functions that parse.
 */
private const val LEGACY_TARGET_USER_ID = 0

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
 *
 * `SoonBlockedPrivateApi` joined it when `app/src/main/aidl/android/content/pm/IPackageDataObserver.aidl`
 * was vendored, and the reason is worth recording because nothing about the flagged call changed.
 * Lint reports on `clearApplicationUserData` by resolving the argument classes of the
 * `getDeclaredMethod` call into a descriptor and looking that up in its non-SDK list; the second
 * argument is `Class.forName("android.content.pm.IPackageDataObserver")`, which lint could not
 * resolve before — no such class existed in `android.jar` or in this project — so it could not
 * build the descriptor and said nothing. The aidl gave it one. Measured as a single variable: an
 * `origin/dev` worktree lints clean, and the same worktree with only that aidl file added, and no
 * other change at all, reports this error on the identical line.
 *
 * Suppressed rather than worked around, on the argument already written out in [init]: this daemon
 * is a bare `app_process`, never zygote-specialised, so it never goes through
 * `ActivityThread.handleBindApplication` and the runtime's non-SDK enforcement is never switched on
 * for it. "Will throw an exception when targeting API 28 and above" is true of the app process and
 * false here — which is exactly why every reflective call in this file runs unexempted today, with
 * no `Bypass` anywhere, on a path `RootSystemGateway` has device-proven.
 */
@SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
class ThorRootService : RootService() {

    init {
        // This daemon runs in a separate :root (app_process) process where ThorApplication.onCreate
        // never executes, so Logger.isDebug — a runtime flag set there for the main process — would
        // stay false and silently drop this daemon's logs. Mirror it so root-side diagnostics are
        // visible in debug builds (Logger is Thor's own, gated on this flag; safe in release).
        Logger.isDebug = BuildConfig.DEBUG

        // No Bypass.setHiddenApiExemptions("Landroid/content/pm") here, deliberately, even though
        // clearAppData below now subclasses the hidden android.content.pm.IPackageDataObserver$Stub
        // and ThorApplication's Bypass.prepareThor() — which exempts that very prefix — never runs
        // in this process.
        //
        // It is not needed: a bare app_process is not zygote-specialised and never goes through
        // ActivityThread.handleBindApplication, so the runtime's hidden-API policy is never switched
        // on for it. The proof is in this file already — everything below reaches IPackageManager,
        // ServiceManager and SuspendDialogInfo with plain Class.forName + getDeclaredMethod +
        // invoke, no Bypass anywhere, and that is the device-proven path RootSystemGateway falls
        // back to. `Class.forName("android.content.pm.IPackageDataObserver")` is itself one of those
        // calls, so the class this daemon must subclass is demonstrably reachable here unexempted.
        //
        // And it would not be free. Bypass.setHiddenApiExemptions tries its Unsafe layer first,
        // which calls ensureUnsafeBypassReady() -> an mmap + dex parse of the boot-classpath core-oj
        // jar; Bypass's own init block refuses to do that at class-initialization time for exactly
        // that reason. Worse, Bypass.init(context) never ran here either, so there is no on-disk
        // offset cache to hit or to persist into — the scan would be paid on the construction path
        // of every single daemon start, to guard against an enforcement that is switched off.
        //
        // The app process needs nothing added either: prepareThor() already exempts
        // "Landroid/content/pm", and it runs in onCreate, long before any clear.
    }

    override fun onBind(intent: Intent): IBinder {
        return object : IThorRootService.Stub() {
            override fun setAppSuspended(packageName: String, suspended: Boolean): Boolean {
                this@ThorRootService.enforceCaller()
                // Historical, user-0-only entry point; see [LEGACY_TARGET_USER_ID].
                return this@ThorRootService.setAppSuspendedAs(
                    packageName,
                    suspended,
                    null,
                    LEGACY_TARGET_USER_ID
                )
            }

            override fun setAppSuspendedAs(
                packageName: String,
                suspended: Boolean,
                suspendingPackage: String?
            ): Boolean {
                this@ThorRootService.enforceCaller()
                // Likewise user-0-only: this overload picked up the *identity* to act as, not the
                // user to act in. Superseded by setAppSuspendedAsForUser and kept only so a stale
                // daemon's numbering stays honest.
                return this@ThorRootService.setAppSuspendedAs(
                    packageName,
                    suspended,
                    suspendingPackage,
                    LEGACY_TARGET_USER_ID
                )
            }

            override fun setAppSuspendedAsForUser(
                packageName: String,
                suspended: Boolean,
                suspendingPackage: String?,
                userId: Int
            ): Boolean {
                this@ThorRootService.enforceCaller()
                return this@ThorRootService.setAppSuspendedAs(
                    packageName,
                    suspended,
                    suspendingPackage,
                    userId
                )
            }

            override fun dumpPackage(packageName: String): String? {
                this@ThorRootService.enforceCaller()
                return this@ThorRootService.dumpPackage(packageName)
            }

            override fun clearAppData(packageName: String): Boolean {
                this@ThorRootService.enforceCaller()
                // The historical, user-0-only entry point. Kept at its old behaviour rather than
                // quietly re-pointed at the caller's user: this process cannot see the caller's
                // user, and a method that means something different depending on which build is
                // answering it is worse than one that means the same wrong thing every time.
                return this@ThorRootService.clearAppData(packageName, 0)
            }

            override fun clearAppDataForUser(packageName: String, userId: Int): Boolean {
                this@ThorRootService.enforceCaller()
                return this@ThorRootService.clearAppData(packageName, userId)
            }
        }
    }

    /**
     * Suspends or unsuspends [packageName] **for [userId]** as [suspendingPackage], and returns
     * whether the platform's own record for that same user agrees afterwards.
     *
     * The return value used to be `runCatching { … }.isSuccess` over a loop that broke as soon as
     * `setPackagesSuspendedAsUser` returned an empty failure array. That array is not evidence:
     * naming a suspender that owns nothing leaves `oldSuspendParams == null == newSuspendParams`, so
     * `changed == false`, so the package is logged "No change is needed" and is deliberately left
     * *out* of the returned array. A root-mode Thor asking to lift a Shizuku-era suspension (recorded
     * as [SHELL_SUSPENDER_IDENTITY]) therefore removed nothing, reported success, and left the app
     * stuck suspended with no error anywhere. Every success below is now a re-read of the record via
     * [readSuspenders] instead.
     *
     * [userId] is threaded all the way down for the same reason the identity is: it is the caller's
     * knowledge, not this process's. The daemon is uid 0 in user 0 and cannot see which user bound
     * it, so a hardcoded 0 here wrote the primary user's copy of a package while the gateway's
     * `FLAG_SUSPENDED` check — an in-process read that answers for Thor's user — judged a copy the
     * write never touched.
     */
    private fun setAppSuspendedAs(
        packageName: String,
        suspended: Boolean,
        suspendingPackage: String?,
        userId: Int
    ): Boolean {
        Logger.i(
            "Odin",
            "setAppSuspendedAs: packageName=$packageName, suspended=$suspended, " +
                    "as=${suspendingPackage ?: "<unspecified>"}, user=$userId"
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
                    identities = suspendIdentities(suspendingPackage),
                    userId = userId
                )
            } else {
                unsuspendAllOf(
                    pmClass, pm, dialogInfoClass, packageName,
                    identities = unsuspendIdentities(packageName, suspendingPackage, userId),
                    userId = userId
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
     * The identities to clear when unsuspending [targetPackage] for [userId].
     *
     * An explicit [suspendingPackage] is again the only entry touched: the gateway reads the record
     * and calls once per recorded suspender, so each call stays a single well-defined removal.
     *
     * With none given — the identity-less entry points — the record itself is the list, read for
     * [userId] so that a work profile's suspenders are never lifted off the primary user's section of
     * the dump. Guessing is exactly what caused the bug: a Shizuku-era suspension is recorded as
     * [SHELL_SUSPENDER_IDENTITY], and a root-mode Thor that only ever named its own package removed
     * nothing. When the record cannot be read at all we still make a best-effort pass over every name
     * Thor has written across its history, including the pre-GH#239
     * [LEGACY_ROOT_SUSPENDER_IDENTITY]; that cannot turn into a false success, because the post-write
     * readback in [unsuspendAllOf] will be just as unreadable and will fail closed.
     */
    private fun unsuspendIdentities(
        targetPackage: String,
        suspendingPackage: String?,
        userId: Int
    ): List<String> {
        if (suspendingPackage != null) return listOf(suspendingPackage)
        // A readable empty set is a real answer — nothing is recorded, so there is nothing to remove
        // and [unsuspendAllOf]'s readback confirms it. Only an unreadable dump falls through.
        readSuspenders(targetPackage, userId)?.let { return it.toList() }
        return listOf(
            this@ThorRootService.packageName,
            SHELL_SUSPENDER_IDENTITY,
            PLATFORM_SUSPENDER_IDENTITY,
            LEGACY_ROOT_SUSPENDER_IDENTITY
        )
    }

    /**
     * Suspends [targetPackage] for [userId] as the first of [identities] the platform actually ends
     * up recording.
     *
     * The loop reads the record back after each attempt rather than trusting
     * [tryCallSetSuspended]'s return value, so "the call was accepted" and "the suspension exists"
     * stay separate facts. It reads back the same [userId] it just wrote — parsing another user's
     * section would report an empty suspender set for a package that is very much suspended, and the
     * loop would then walk on to the next identity and stack a second entry. An unreadable record
     * aborts for that same reason: without knowing whether the previous attempt landed, trying
     * another name risks a second suspension entry that only a second unsuspend could remove.
     */
    private fun suspendAsAnyOf(
        pmClass: Class<*>, pm: Any?, dialogInfoClass: Class<*>,
        targetPackage: String, dialogInfo: Any?, identities: List<String>, userId: Int
    ): Boolean {
        for (caller in identities) {
            val accepted = tryCallSetSuspended(
                pmClass, pm, dialogInfoClass, targetPackage, true, dialogInfo, caller, userId
            )
            if (!accepted) continue

            val recorded = readSuspenders(targetPackage, userId)
            if (recorded == null) {
                Logger.w(
                    "Odin",
                    "Cannot read $targetPackage's suspenders for user $userId after suspending as " +
                            "$caller; refusing to report a success we did not verify"
                )
                return false
            }
            if (caller in recorded) {
                Logger.i(
                    "Odin",
                    "Suspended $targetPackage for user $userId; platform recorded suspender $caller"
                )
                return true
            }
            Logger.w(
                "Odin",
                "setPackagesSuspendedAsUser reported no failure for $targetPackage as $caller, " +
                        "but the platform records $recorded for user $userId — that identity owns " +
                        "nothing"
            )
        }
        return false
    }

    /**
     * Removes every one of [identities] from [targetPackage]'s suspension record for [userId].
     *
     * Deliberately no break on the first accepted call. From API 30 `PackageUserState.suspendParams`
     * is a map, so a package can carry several suspension entries at once and `suspended` stays true
     * while any of them survives — stopping early is how one gets left behind.
     *
     * Success means the identities we were asked to remove are gone **from that user's record**. The
     * removal and the readback name one [userId] on purpose: lifting user 10's entries and then
     * parsing user 0's section reports an empty set and would call that a success while the app the
     * caller is looking at stays paused.
     *
     * A suspension owned by somebody else is not this call's to lift and is reported at warn level so
     * the caller can name the owner to the user rather than leaving them with an app that quietly
     * stays paused.
     */
    private fun unsuspendAllOf(
        pmClass: Class<*>, pm: Any?, dialogInfoClass: Class<*>,
        targetPackage: String, identities: List<String>, userId: Int
    ): Boolean {
        for (caller in identities) {
            tryCallSetSuspended(
                pmClass, pm, dialogInfoClass, targetPackage, false, null, caller, userId
            )
        }

        val recorded = readSuspenders(targetPackage, userId)
        if (recorded == null) {
            Logger.w(
                "Odin",
                "Cannot read $targetPackage's suspenders for user $userId after unsuspending; " +
                        "refusing to report a success we did not verify"
            )
            return false
        }
        val remaining = identities.filter { it in recorded }
        if (remaining.isNotEmpty()) {
            Logger.w(
                "Odin",
                "Unsuspend of $targetPackage left $remaining recorded as suspenders for user $userId"
            )
            return false
        }
        if (recorded.isEmpty()) {
            Logger.i(
                "Odin",
                "Unsuspend of $targetPackage verified for user $userId; nothing is recorded as " +
                        "suspending it"
            )
        } else {
            Logger.w(
                "Odin",
                "Removed $identities from $targetPackage for user $userId, but $recorded still own " +
                        "entries — it stays suspended until whoever owns those lifts them"
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
        packageName: String, suspended: Boolean, dialogInfo: Any?, caller: String, userId: Int
    ): Boolean = try {
        callSetSuspended(
            pmClass, pm, dialogInfoClass, packageName, suspended, dialogInfo, caller, userId
        )
    } catch (e: Exception) {
        val cause = if (e is InvocationTargetException) e.cause else e
        Logger.w(
            "Odin",
            "setPackagesSuspendedAsUser threw for $packageName as $caller on user $userId: " +
                    cause?.message
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
        packageName: String, suspended: Boolean, dialogInfo: Any?, caller: String, userId: Int
    ): Boolean {
        // Android 15+ (API 35+): 9-arg signature. Not 34 — the shape itself says so. This overload
        // is where a suspension became cross-user: the suspender key turned into a UserPackage,
        // which is exactly why it carries a suspendingUserId *and* a targetUserId, and that landed
        // in 15. Gating it on 34 would only mean asking a 34 device for a method it does not have.
        //
        // Both user arguments are [userId], and they are not the same question: suspendingUserId is
        // the user [caller] lives in, targetUserId the user [packageName] is paused for. Thor lists
        // and acts on the packages of its own user, so both are that user — and the API 35 dump
        // prints the suspender key as `<suspendingUserId>package`, which `parseSuspendingPackages`
        // filters on, so a mismatch here would read back as no suspender at all.
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
                userId, userId
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
                userId
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
                pm, arrayOf(packageName), suspended, null, null, dialogInfo, caller, userId
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
     * The identities the platform currently records as suspending [targetPackage] for [userId], or
     * `null` when the dump could not be trusted.
     *
     * [userId] is the caller's, never this process's: `dumpsys package` prints every user's section
     * and `parseSuspendingPackages` picks one, so passing the wrong number here turns a suspension
     * that exists into an empty set — the exact shape of "unknown" the `null` below is here to keep
     * separate from "nothing is recorded".
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
    private fun readSuspenders(targetPackage: String, userId: Int): Set<String>? {
        val dump = dumpPackage(targetPackage) ?: return null
        if (!dump.contains("Package [$targetPackage]")) {
            Logger.w(
                "Odin",
                "dumpsys package $targetPackage returned no package block; suspender state unknown"
            )
            return null
        }
        return parseSuspendingPackages(dump, userId)
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

    /**
     * Wipes [packageName]'s data **for [userId]**, which the caller has to name.
     *
     * This process runs as uid 0 in user 0, so it cannot ask the platform which user its client
     * belongs to — `Process.myUserHandle()` here answers 0 for a Thor sitting in a work profile just
     * as it does for one in the primary user. The user id therefore arrives over the binder
     * ([IThorRootService.clearAppDataForUser]) and is passed straight through to
     * `clearApplicationUserData`, whose third argument is exactly this number. It used to be the
     * literal 0, which is the difference between wiping the app the user tapped and wiping the
     * primary user's same-named app — irreversibly, and reported as a success.
     *
     * **The return value is now a confirmation, not a dispatch receipt.** This is the one site in
     * Thor where a destructive privileged operation had no verifier of any kind: the daemon has no
     * `PackageManager` for the caller's user and no readback to compare against, so
     * `runCatching { … }.isSuccess` over a `void` method was all there was, and it reported success
     * for every wipe `PackageManagerService` accepted and then declined. `true` now means
     * `onRemoveCompleted` arrived saying so; a refusal, a timeout and a broken lookup all return
     * `false` and are told apart in the log rather than in the return type.
     */
    private fun clearAppData(packageName: String, userId: Int): Boolean {
        val outcome = runCatching {
            val pmStub = Class.forName("android.content.pm.IPackageManager\$Stub")
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "package") as IBinder
            val asInterface = pmStub.getMethod("asInterface", IBinder::class.java)
            val pm = asInterface.invoke(null, binder)
            val pmClass = Class.forName("android.content.pm.IPackageManager")

            // Still looked up by name rather than as IPackageDataObserver::class.java. Both resolve
            // to the same framework class while parent-first delegation holds, and if it ever stops
            // holding this spelling keeps the failure where it belongs — a lookup that cannot find
            // the framework's method, rather than an invoke that silently takes our shadow copy.
            val method = pmClass.getDeclaredMethod(
                "clearApplicationUserData",
                String::class.java,
                Class.forName("android.content.pm.IPackageDataObserver"),
                Int::class.javaPrimitiveType
            )
            // clearApplicationUserData returns void: the verdict only ever arrives asynchronously on
            // IPackageDataObserver.onRemoveCompleted, so a real observer is what makes it readable.
            // The observer is constructed before the invoke, inside awaitDataObserver, so the
            // callback cannot land before there is something to receive it.
            awaitDataObserver("Odin", packageName) { observer ->
                method.invoke(pm, packageName, observer, userId)
            }
        }.getOrElse { e ->
            // Only the reflective lookup can land here — awaitDataObserver already absorbs whatever
            // the invoke itself throws and answers UNVERIFIED for it.
            Logger.e("Odin", "Failed to look up clearApplicationUserData for $packageName", e)
            DataClearOutcome.UNVERIFIED
        }

        when (outcome) {
            DataClearOutcome.CLEARED ->
                Logger.d("Odin", "clearAppData($packageName, user $userId): confirmed by the observer")

            DataClearOutcome.REFUSED ->
                Logger.w(
                    "Odin",
                    "clearAppData($packageName, user $userId): PackageManagerService refused the " +
                        "wipe — the data is still there"
                )

            DataClearOutcome.UNVERIFIED ->
                Logger.w(
                    "Odin",
                    "clearAppData($packageName, user $userId): issued but never confirmed — the " +
                        "data may or may not be gone, so this reports failure"
                )
        }

        return outcome == DataClearOutcome.CLEARED
    }
}
