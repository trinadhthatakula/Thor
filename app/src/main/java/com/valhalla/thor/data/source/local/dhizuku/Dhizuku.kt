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
import com.valhalla.thor.data.source.local.shizuku.Packages
import com.valhalla.thor.domain.model.SHELL_SUSPENDER_IDENTITY
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import com.rosan.dhizuku.api.Dhizuku as DhizukuAPI
import com.valhalla.thor.util.Logger
import com.valhalla.thor.R
import java.util.concurrent.TimeUnit

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

    fun isDhizukuAvailable(): Boolean {
        return try {
            DhizukuAPI.isPermissionGranted()
        } catch (_: Exception) {
            false
        }
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
        // 1. Try shell first
        val result = execute("am force-stop --user $userId $packageName")
        if (result.first == 0) return true

        // 2. Fallback to reflection
        val reflectionResult = runCatching {
            val am = asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE)
                ?: return@runCatching false
            Bypass.invoke<Any?>(
                am::class.java, am, "forceStopPackage", packageName, userId
            )
            true
        }.getOrElse {
            Logger.e(
                "DhizukuHelper",
                "forceStopApp reflection failed for $packageName",
                it
            )
            false
        }
        if (reflectionResult) return true

        // 3. Unprivileged fallback (re-query PM to observe post-mutation state)
        if (pkgs.isAppStopped(packageName)) return true
        runCatching {
            val am =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.killBackgroundProcesses(packageName)
        }
        return pkgs.isAppStopped(packageName)
    }

    fun setAppDisabled(context: Context, packageName: String, disabled: Boolean): Boolean {
        val pkgs = Packages(context)
        pkgs.getApplicationInfoOrNull(packageName) ?: return false
        val userId = pkgs.myUserId

        // 1. Try shell first
        val command = if (disabled) {
            "pm disable-user --user $userId $packageName"
        } else {
            "pm enable --user $userId $packageName"
        }
        val result = execute(command)
        if (result.first == 0 && pkgs.isAppDisabled(packageName) == disabled) {
            return true
        }

        // 2. Fallback to Bypass reflection
        val reflectionResult = runCatching {
            val pm =
                asInterface("android.content.pm.IPackageManager", "package") ?: return@runCatching false
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
            true
        }.onFailure {
            Logger.e(
                "DhizukuHelper",
                "setAppDisabled fallback reflection failed for $packageName",
                it
            )
        }.getOrDefault(false)

        if (reflectionResult && pkgs.isAppDisabled(packageName) == disabled) {
            return true
        }

        // 3. Unprivileged fallback (re-query PM to observe post-mutation state)
        if (pkgs.isAppDisabled(packageName) == disabled) {
            return true
        }
        val newState = if (disabled) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        runCatching {
            context.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
        }

        return pkgs.isAppDisabled(packageName) == disabled
    }

    // @Volatile for safe cross-thread publication (getCurrentUserId() may be called from IO
    // coroutines); only a successfully-resolved id is ever cached (it throws before assigning
    // on failure), matching the Shizuku/RootSystemGateway pattern (#41/#34).
    @Volatile
    private var cachedUserId: String? = null

    fun getCurrentUserId(): String {
        cachedUserId?.let { return it }
        val userResult = execute("am get-current-user")
        val output = userResult.second?.trim()
        if (userResult.first != 0 || output == null || !output.matches(Regex("^\\d+$"))) {
            throw IllegalStateException("Failed to determine current user ID: exitCode=${userResult.first}, output=$output")
        }
        cachedUserId = output
        return output
    }

    fun uninstallApp(packageName: String): Boolean {
        return try {
            val currentUser = getCurrentUserId()
            execute(
                "pm uninstall --user $currentUser ${
                    packageName.escapeForShell()
                }"
            ).first == 0
        } catch (_: Exception) {
            false
        }
    }

    fun reinstallApp(packageName: String): Boolean {
        return try {
            val currentUser = getCurrentUserId()
            execute(
                "pm install-existing --user $currentUser ${
                    packageName.escapeForShell()
                }"
            ).first == 0
        } catch (_: Exception) {
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

    // SdCardPath: absolute system paths are intentional for privileged/root file ops on app data
    // dirs (not app-scoped storage). PrivateApi: hidden-API reflection is the core privilege
    // mechanism, guarded by the :bypass VMRuntime unseal.
    @SuppressLint("PrivateApi", "SdCardPath")
    fun clearCache(packageName: String): Boolean {
        // 1. Try shell first
        val userId = android.os.Process.myUserHandle().hashCode()
        val paths = listOf(
            "/data/data/$packageName/cache",
            "/data/user/$userId/$packageName/cache",
            "/sdcard/Android/data/$packageName/cache"
        )
        val command = "rm -rf ${paths.joinToString(" ")}"
        val shellResult = execute(command)
        if (shellResult.first == 0) return true

        // 2. Fallback to reflection
        val reflectionResult = runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package") ?: return@runCatching false
            val observerClass = Class.forName("android.content.pm.IPackageDataObserver")

            try {
                Bypass.invoke<Any?>(
                    pm.javaClass,
                    pm,
                    "deleteApplicationCacheFiles",
                    arrayOf(String::class.java, observerClass),
                    packageName,
                    null /* IPackageDataObserver */
                )
            } catch (_: NoSuchMethodException) {
                Bypass.invoke(
                    pm.javaClass,
                    pm,
                    "deleteApplicationCacheFilesAsUser",
                    arrayOf(String::class.java, Int::class.javaPrimitiveType!!, observerClass),
                    packageName,
                    userId,
                    null
                )
            }
            true
        }.getOrDefault(false)

        return reflectionResult
    }

    // PrivateApi: hidden-API reflection is intentional — the core privilege mechanism, guarded by
    // the :bypass VMRuntime unseal.
    @SuppressLint("PrivateApi")
    fun clearAppData(packageName: String): Boolean {
        // 1. Try shell first
        val result = execute("pm clear $packageName")
        if (result.first == 0) return true

        // 2. Fallback to reflection
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
                android.os.Process.myUserHandle().hashCode()
            )
            true
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
     * The `@StringRes int` overloads are deliberately not used as a pre-31 fallback: the system
     * resolves such an id against the *suspending* package's resources, which in Dhizuku mode is
     * `com.android.shell`, not us. A missing title is better than a wrong one.
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

    fun setAppRestricted(context: Context, packageName: String, restricted: Boolean): Boolean {
        // 1. Try shell first
        val result =
            execute("appops set $packageName RUN_ANY_IN_BACKGROUND ${if (restricted) "ignore" else "allow"}")
        if (result.first == 0) return true

        // 2. Fallback to reflection
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
                Bypass.invoke<Int>(
                    android.app.AppOpsManager::class.java,
                    null,
                    "strOpToOp",
                    "android:run_any_in_background"
                ),
                uid,
                packageName,
                if (restricted) android.app.AppOpsManager.MODE_IGNORED else android.app.AppOpsManager.MODE_ALLOWED
            )
            true
        }.getOrElse { false }
    }
}
