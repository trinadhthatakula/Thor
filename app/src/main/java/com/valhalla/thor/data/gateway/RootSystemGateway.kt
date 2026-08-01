// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.valhalla.superuser.ipc.RootService
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.rootservice.IThorRootService
import com.valhalla.superuser.ktx.ShellRepository
import com.valhalla.superuser.ktx.ShellResult
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.shizuku.isPolicyRefusal
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.uninstallFreezeFallbackAllowed
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single
import java.io.File
import kotlin.coroutines.resume

private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")
private val USER_ID_REGEX = Regex("^\\d+$")

// Upper bound for the RootService bind handshake. A null binder or a callback that never
// arrives must not pin connectionMutex forever and deadlock every later privileged op (H2).
private const val ROOT_SERVICE_BIND_TIMEOUT_MS = 10_000L

/**
 * Modern implementation of SystemGateway using the reactive ShellRepository.
 * No more static blocking calls.
 */
@Single
class RootSystemGateway(
    private val context: Context,
    private val shellRepository: ShellRepository,
    private val preferenceRepository: PreferenceRepository
) : SystemGateway {

    private var rootService: IThorRootService? = null
    private val connectionMutex = Mutex()
    private var isDaemonReset = false
    private var activeConnection: ServiceConnection? = null

    private suspend fun getRootService(): IThorRootService? = connectionMutex.withLock {
        if (!isDaemonReset) {
            isDaemonReset = true
            // Kill any old daemon so the newly compiled root service is loaded and executed
            runCatching {
                shellRepository.exec("pkill -f ${context.packageName}:root")
            }
        }

        rootService?.let { binder ->
            if (binder.asBinder().isBinderAlive) {
                return binder
            } else {
                rootService = null
                activeConnection?.let { oldConn ->
                    runCatching { RootService.unbind(oldConn) }
                    activeConnection = null
                }
            }
        }

        // Clean up any stale connection before creating a new one
        activeConnection?.let { oldConn ->
            runCatching {
                RootService.unbind(oldConn)
            }
            activeConnection = null
        }

        // Bind under a timeout so a null binder or a callback that never arrives can't hold
        // connectionMutex forever (H2). withTimeoutOrNull RETURNS null on timeout — it does not
        // throw — so on every path (success, null-binding, or timeout) withLock unwinds and the
        // mutex is released. On timeout the child coroutine is cancelled, which fires
        // invokeOnCancellation below to unbind the stale connection; the caller then falls back.
        withTimeoutOrNull(ROOT_SERVICE_BIND_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val intent = Intent(context, com.valhalla.thor.rootservice.ThorRootService::class.java)
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            val binder = IThorRootService.Stub.asInterface(service)
                            // Publish/resume only if the bind hasn't already timed out. A late
                            // connect (continuation cancelled by withTimeoutOrNull) would otherwise
                            // cache a service whose ServiceConnection is about to be unbound by
                            // invokeOnCancellation, leaving rootService dangling (-> intermittent
                            // DeadObjectException on the next call).
                            if (continuation.isActive) {
                                rootService = binder
                                continuation.resume(binder)
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            rootService = null
                            // A dead service can no longer answer '--user <id>'; drop the cached
                            // user id so a reconnect re-reads the (possibly switched) user (#34).
                            cachedUserId = null
                            userIdGeneration++
                            if (activeConnection === this) {
                                activeConnection = null
                            }
                        }

                        // The root process returned a null binder — the service refused to bind.
                        // Resume with null (and unbind) instead of hanging until the timeout fires.
                        override fun onNullBinding(name: ComponentName?) {
                            rootService = null
                            runCatching { RootService.unbind(this) }
                            if (activeConnection === this) {
                                activeConnection = null
                            }
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }

                    activeConnection = conn

                    continuation.invokeOnCancellation {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            runCatching {
                                RootService.unbind(conn)
                            }
                            if (activeConnection === conn) {
                                activeConnection = null
                            }
                        }
                    }

                    RootService.bind(intent, conn)
                }
            }
        }
    }

    // A root check is strictly asynchronous. Blocking the thread for this is unacceptable.
    override suspend fun isRootAvailable(): Boolean {
        return shellRepository.isRootGranted()
    }

    override suspend fun isShizukuAvailable(): Boolean = false
    override suspend fun isDhizukuAvailable(): Boolean = false

    // killBackgroundProcesses' KILL_BACKGROUND_PROCESSES is satisfied via elevated privilege
    // (root shell) rather than a manifest grant.
    @SuppressLint("MissingPermission")
    override suspend fun forceStopApp(packageName: String): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val escapedPackage = packageName.escapeForShell()
        val shellResult = runCommand("am force-stop $escapedPackage")
        if (shellResult.isSuccess) return shellResult

        // Unprivileged check/fallback
        val isStopped = getApplicationInfoCompat(packageName)?.run {
            (flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
        } ?: false
        if (isStopped) return Result.success(Unit)

        runCatching {
            val am =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.killBackgroundProcesses(packageName)
        }

        val postCheck = getApplicationInfoCompat(packageName)?.run {
            (flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
        } ?: false
        if (postCheck) return Result.success(Unit)

        return Result.failure(Exception("Root force stop failed. Shell command failed and app is still running."))
    }

    override suspend fun clearCache(packageName: String): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val escapedPackage = packageName.escapeForShell()
        val command = "rm -rf /data/data/$escapedPackage/cache /sdcard/Android/data/$escapedPackage/cache"
        return runCommand(command)
    }

    override suspend fun clearAppData(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val escapedPackage = packageName.escapeForShell()
        val shellResult = runCommand("pm clear $escapedPackage")
        if (shellResult.isSuccess) return@withContext shellResult

        // Fallback to ThorRootService AIDL daemon
        val service = getRootService()
        if (service != null) {
            val aidlCleared = runCatching {
                service.clearAppData(packageName)
            }.onFailure { e ->
                Logger.e("RootSystemGateway", "AIDL clearAppData failed", e)
            }.getOrDefault(false)
            if (aidlCleared) {
                return@withContext Result.success(Unit)
            }
        }

        return@withContext Result.failure(Exception("Root clear app data failed. Shell command and AIDL both failed."))
    }

    /**
     * Freeze ([isDisabled] = true) or unfreeze a package.
     *
     * **User apps** keep the single `pm disable` / `pm enable` rung they always had, with the
     * unprivileged `setApplicationEnabledSetting` as a last resort. It works; it is untouched.
     *
     * **System apps** run a two-rung chain, in this order — the order matters because the two rungs
     * have wildly different consequences for the user's data:
     *
     *  1. `pm disable` — the *data-preserving* rung, and the whole reason this chain exists. The
     *     package stays installed for the user, so app data, accounts and permissions all survive
     *     and unfreezing hands the app back exactly as it was. With root this should almost always
     *     be the rung that takes; it was simply never tried before.
     *  2. `pm uninstall -k --user N` — the historical rung, kept but **gated behind
     *     [uninstallFreezeFallbackAllowed]**, which answers `false` for [PrivilegeMode.ROOT] on
     *     every release: root can disable any package, so a refusal to disable is a real failure
     *     worth surfacing, not a platform gap worth working around. `-k` is `DELETE_KEEP_DATA`, so
     *     this rung keeps the app's files, settings and granted permissions too; what it does *not*
     *     keep is the per-user installed bit, which is why a package frozen this way reads as gone
     *     to anything querying without `MATCH_UNINSTALLED_PACKAGES`. Under root this rung is
     *     unreachable today and a failed rung 1 returns `Result.failure`. The code stays because
     *     the gate — not this gateway — owns that decision, and a device check could legitimately
     *     flip the root branch later.
     *
     * Unfreeze has to undo **both** mechanics, in the order install-existing → enable. Devices in
     * the field carry apps frozen by the uninstall-only builds and must still thaw after this ships;
     * and a single freeze can even leave a package *both* disabled (rung 1 took but was not observed
     * to) *and* uninstalled (rung 2 ran anyway). `pm install-existing` restores the per-user
     * installed bit but does not clear a disabled enabled-setting, so `pm enable` has to follow it.
     *
     * Every rung is verified by **re-reading `ApplicationInfo`**, never by the shell exit code: `pm`
     * happily prints Success for a no-op and returns non-zero from commands that did take effect.
     */
    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val appInfo = getApplicationInfoCompat(packageName)
        val isSystem = appInfo != null && (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val escapedPackage = packageName.escapeForShell()

        if (isSystem) {
            // Only the system path needs a user id, so the `am get-current-user` round trip stays
            // off the user-app path entirely.
            val currentUser = getCurrentUserId()
            return if (isDisabled) {
                freezeSystemApp(packageName, escapedPackage, currentUser)
            } else {
                unfreezeSystemApp(packageName, escapedPackage, currentUser)
            }
        }

        val state = if (isDisabled) "disable" else "enable"
        val shellResult = runCommand("pm $state $escapedPackage")

        if (shellResult.isSuccess) return shellResult

        // Check if already in the target state
        if (appInfo != null) {
            val currentInstalled = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0
            val currentEnabled = appInfo.enabled && currentInstalled
            val currentDisabled = !currentEnabled
            if (currentDisabled == isDisabled) return Result.success(Unit)
        }

        // Try unprivileged API as fallback. Still "only for non-system apps" — that used to be an
        // `if (!isSystem)` here; system apps now return above, before this point, so the guard is
        // the early return rather than a second test of the same flag.
        val newState = if (isDisabled) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        val unprivilegedResult = runCatching {
            context.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
        }
        if (unprivilegedResult.isSuccess) {
            val postAppInfo = getApplicationInfoCompat(packageName)
            if (postAppInfo != null) {
                val postInstalled = (postAppInfo.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0
                val postEnabled = postAppInfo.enabled && postInstalled
                val postDisabled = !postEnabled
                if (postDisabled == isDisabled) return Result.success(Unit)
            }
        }

        return Result.failure(Exception("Root setAppDisabled failed."))
    }

    /**
     * Rung 1 → (gated) rung 2 of the system-app freeze, with a state re-read between them.
     *
     * Rung 1 (`pm disable`) preserves the app's data and leaves it installed. Rung 2
     * (`pm uninstall -k --user`) preserves the data too — `-k` is `DELETE_KEEP_DATA` — but clears
     * the per-user installed bit, so it is the rung with the visible consequence, and it is only
     * reached when [uninstallFreezeFallbackAllowed] says this privilege mode has no other way to
     * freeze a system app — which for root is never. Nothing here trusts a shell exit code: the
     * rung that "succeeded" is the one the platform agrees changed the package state.
     */
    private suspend fun freezeSystemApp(
        packageName: String,
        escapedPackage: String,
        currentUser: String,
    ): Result<Unit> {
        // Already frozen — by us, by an older build, or by another tool. Short-circuit before any
        // rung runs: re-freezing a package that is merely disabled must never walk down into the
        // uninstall rung just because the first command reported nothing to do.
        if (readEffectivelyEnabled(packageName) == false) {
            Logger.i("RootSystemGateway", "freeze $packageName: already frozen, no rung run")
            return Result.success(Unit)
        }

        // --- Rung 1: pm disable. Keeps the package installed for the user, so its data survives.
        // The result is kept rather than discarded because the gate below turns on *why* a rung
        // failed: runCommand folds stderr into the exception message, which is where a
        // PackageManagerService SecurityException lands.
        val disableResult = runCommand("pm disable --user $currentUser $escapedPackage")
        when (readEffectivelyEnabled(packageName)) {
            false -> {
                Logger.i(
                    "RootSystemGateway",
                    "freeze $packageName: rung 1 `pm disable --user $currentUser` took effect — app data preserved"
                )
                return Result.success(Unit)
            }

            null -> {
                // The package resolved a moment ago (isSystem was derived from it) and `pm disable`
                // cannot make it unresolvable — getApplicationInfoCompat carries
                // MATCH_UNINSTALLED_PACKAGES, and a disabled application is still returned. So this
                // is a failed *read*, not a known state, and the next rung would uninstall the
                // package for this user on a state nobody can confirm. Fail closed: a retry
                // re-reads and, if rung 1 actually landed, short-circuits above.
                val e = java.io.IOException(
                    "Root freeze of $packageName: `pm disable --user $currentUser` ran but the package " +
                        "state could no longer be read, so `pm uninstall -k --user $currentUser` was NOT " +
                        "attempted — it would uninstall the package for this user on a state we cannot confirm."
                )
                Logger.e("RootSystemGateway", e.message.orEmpty(), e)
                return Result.failure(e)
            }

            true -> Unit // Rung 1 did not take. Consider the uninstall rung — see the gate below.
        }

        // --- Rung 2: pm uninstall -k --user. `-k` is DELETE_KEEP_DATA, so the app's data survives
        // and `pm install-existing` hands it back as it was; what does not survive is the per-user
        // installed bit, which is the whole visible consequence of this rung. Gated, not merely
        // last: the policy — not this gateway — owns the question of whether a privilege mode has
        // any other way to freeze a system app.
        // Passing PrivilegeMode.ROOT literally is correct rather than lazy: this class *is* the root
        // implementation, and reading the user's configured mode here would let a fallback chain
        // that landed on root apply Shizuku's escalation rules. Under root the gate answers false
        // whatever the refusal flag says — every refusal observed in the wild, AOSP's own and
        // Xiaomi's alike, keys on the shell uid (2000), and root is uid 0 — so the rung below is
        // unreachable today and the failure is surfaced instead. The flag is still computed and
        // passed honestly rather than hardcoded to false, so the two gateways call the gate the
        // same way and a future decision to let root escalate is a change in the policy, not here.
        if (!uninstallFreezeFallbackAllowed(
                isSystem = true,
                privilegeMode = PrivilegeMode.ROOT,
                disableRefusedByPolicy = isPolicyRefusal(
                    disableResult.exceptionOrNull()?.message
                ),
            )
        ) {
            val refused = java.io.IOException(
                "Root freeze of $packageName failed: `pm disable --user $currentUser` did not take " +
                    "effect and the package is still enabled. Root can disable any package, so this is " +
                    "a real refusal rather than a platform limit — the `pm uninstall -k --user " +
                    "$currentUser` fallback is not permitted here, and the package was left installed."
            )
            Logger.e("RootSystemGateway", refused.message.orEmpty(), refused)
            return Result.failure(refused)
        }

        // `-k` (DELETE_KEEP_DATA) is not optional here: without it this line deletes
        // /data/user/N/<pkg> and an unfreeze returns the app factory-fresh, which is the bug this
        // whole change exists to remove. With it, the data directories keep the same inodes across
        // uninstall → install-existing (measured).
        runCommand("pm uninstall -k --user $currentUser $escapedPackage")
        // Nothing is left to try, so anything but "definitely still enabled" is the state we asked
        // for: a null read can now only mean the package is no longer resolvable for this user,
        // which is precisely what `pm uninstall -k --user` produces.
        if (readEffectivelyEnabled(packageName) != true) {
            Logger.w(
                "RootSystemGateway",
                "freeze $packageName: rung 1 `pm disable` had no effect; fell back to rung 2 " +
                    "`pm uninstall -k --user $currentUser` — data directories survive; the package " +
                    "stops resolving without MATCH_UNINSTALLED_PACKAGES"
            )
            return Result.success(Unit)
        }

        val failure = java.io.IOException(
            "Root freeze of $packageName failed: neither `pm disable --user $currentUser` nor " +
                "`pm uninstall -k --user $currentUser` changed the package's state."
        )
        Logger.e("RootSystemGateway", failure.message.orEmpty(), failure)
        return Result.failure(failure)
    }

    /**
     * Undoes *either* freeze mechanic, install-existing first and enable second.
     *
     * The two are not alternatives to choose between — a package can need both, because a freeze
     * that escalated to the uninstall rung on top of a `pm disable` that had already landed is
     * left disabled *and* uninstalled-for-user. Root no longer produces that state, but it is asked
     * to undo states it did not create: everything the uninstall-only builds froze, and anything
     * Shizuku froze on a device that refuses to disable system packages. So this walks the state
     * machine ([RootFreezeChain.unfreezeStep]) instead of branching once: not installed → restore
     * it; installed but disabled → enable it; installed and enabled → done.
     */
    private suspend fun unfreezeSystemApp(
        packageName: String,
        escapedPackage: String,
        currentUser: String,
    ): Result<Unit> {
        val tried = mutableListOf<String>()

        fun unreadable(): Result<Unit> {
            val e = java.io.IOException(
                "Root unfreeze of $packageName failed: the package could not be read back" +
                    (if (tried.isEmpty()) "." else " after ${tried.joinToString(", ") { "`$it`" }}.")
            )
            Logger.e("RootSystemGateway", e.message.orEmpty(), e)
            return Result.failure(e)
        }

        var step = readUnfreezeStep(packageName) ?: return unreadable()

        // --- Rung 1: the app was frozen by uninstalling it for this user (every build before the
        // `pm disable` rung existed, plus any package that still falls back to it). FLAG_INSTALLED
        // is clear, so put the package back for the user first.
        if (step == RootFreezeChain.UnfreezeStep.INSTALL_EXISTING) {
            runCommand("pm install-existing --user $currentUser $escapedPackage")
            tried += "pm install-existing --user $currentUser"
            step = readUnfreezeStep(packageName) ?: return unreadable()
        }

        // --- Rung 2: the app is installed but disabled — either frozen with `pm disable`, or just
        // restored above with its old disabled enabled-setting intact. install-existing does not
        // clear that setting, which is why this runs *after* rung 1 rather than instead of it.
        if (step == RootFreezeChain.UnfreezeStep.ENABLE) {
            runCommand("pm enable --user $currentUser $escapedPackage")
            tried += "pm enable --user $currentUser"
            step = readUnfreezeStep(packageName) ?: return unreadable()
        }

        // --- Rung 3: verify the end state is installed *and* enabled, from the platform's answer.
        if (step == RootFreezeChain.UnfreezeStep.VERIFIED) {
            Logger.i(
                "RootSystemGateway",
                "unfreeze $packageName: installed and enabled" +
                    (if (tried.isEmpty()) " already, no rung run"
                    else " via ${tried.joinToString(", ") { "`$it`" }}")
            )
            return Result.success(Unit)
        }

        val failure = java.io.IOException(
            "Root unfreeze of $packageName failed: still ${
                when (step) {
                    RootFreezeChain.UnfreezeStep.INSTALL_EXISTING -> "not installed for user $currentUser"
                    else -> "disabled"
                }
            } after ${tried.joinToString(", ") { "`$it`" }.ifBlank { "no command" }}."
        )
        Logger.e("RootSystemGateway", failure.message.orEmpty(), failure)
        return Result.failure(failure)
    }

    /**
     * The app's effective enabled state as the platform reports it *now*, or `null` when the package
     * could not be resolved at all.
     *
     * `null` is deliberately distinct from `false`. The freeze chain's second rung deletes user data,
     * so "I could not read it" must never collapse into either "not frozen yet" (→ delete) or
     * "frozen" (→ report a success that never happened).
     */
    private fun readEffectivelyEnabled(packageName: String): Boolean? =
        getApplicationInfoCompat(packageName)?.let {
            RootFreezeChain.isEffectivelyEnabled(it.enabled, it.flags)
        }

    /** The next unfreeze rung for the package's live state, or `null` if it cannot be read. */
    private fun readUnfreezeStep(packageName: String): RootFreezeChain.UnfreezeStep? =
        getApplicationInfoCompat(packageName)?.let {
            RootFreezeChain.unfreezeStep(it.enabled, it.flags)
        }

    override suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val hasReflection = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        val escapedPackage = packageName.escapeForShell()

        fun isCurrentlySuspended() = getApplicationInfoCompat(packageName)?.run {
            (flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
        } ?: false

        if (isSuspended) {
            // SUSPEND via the reflection path only: setPackagesSuspendedAsUser(caller = our app id).
            // A root-shell `pm suspend` (uid 0) records the suspender as "root", a non-existent
            // package, so tapping the paused app crashes SuspendedAppActivity
            // ("IllegalArgumentException: Package root does not exist"). We never fall back to the
            // shell for suspend — a broken suspension is worse than a reported failure. GH#239.
            if (hasReflection) {
                val service = getRootService()
                if (service != null) {
                    val taskResult = runCatching {
                        service.setAppSuspended(packageName, true)
                    }.onFailure { e ->
                        Logger.e("RootSystemGateway", "AIDL suspend failed", e)
                    }.getOrDefault(false)
                    if (taskResult || isCurrentlySuspended()) return@withContext Result.success(Unit)
                }
                return@withContext Result.failure(Exception("Root suspend failed via AIDL for $packageName."))
            }
            // API < 29 has no SuspendDialogInfo reflection path.
            val shell = runCommand("pm suspend $escapedPackage")
            return@withContext if (shell.isSuccess) shell
            else Result.failure(Exception("Root suspend failed for $packageName."))
        }

        // UNSUSPEND: a suspension can only be lifted by the package that set it, so clear BOTH
        // possible owners — our own app (reflection, for suspensions this app set) AND
        // "root"/"shell" (shell `pm unsuspend`, for any legacy suspension left by older builds).
        // A root-shell `pm unsuspend` alone reports success yet leaves an app suspended by us still
        // suspended. GH#239.
        var cleared = false
        if (hasReflection) {
            val service = getRootService()
            if (service != null) {
                cleared = runCatching {
                    service.setAppSuspended(packageName, false)
                }.onFailure { e ->
                    Logger.e("RootSystemGateway", "AIDL unsuspend failed", e)
                }.getOrDefault(false)
            }
        }
        val shell = runCommand("pm unsuspend $escapedPackage")
        cleared = cleared || shell.isSuccess
        return@withContext if (cleared || !isCurrentlySuspended()) Result.success(Unit)
        else Result.failure(Exception("Root unsuspend failed for $packageName."))
    }

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val escapedPackage = packageName.escapeForShell()
        val state = if (isRestricted) "ignore" else "allow"
        return runCommand("appops set $escapedPackage RUN_ANY_IN_BACKGROUND $state")
    }

    override suspend fun rebootDevice(reason: String): Result<Unit> {
        val escapedReason = reason.escapeForShell()
        // executeResult returns success if ANY of the commands succeed in the chain logic
        return runCommand("svc power reboot $escapedReason || reboot $escapedReason")
    }

    override suspend fun uninstallApp(packageName: String): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val currentUser = getCurrentUserId()
        val escapedPackage = packageName.escapeForShell()
        return runCommand("pm uninstall --user $currentUser $escapedPackage")
    }

    override suspend fun installApp(apkPath: String, canDowngrade: Boolean): Result<Unit> {
        return installViaSession(listOf(apkPath), canDowngrade)
    }

    suspend fun installMultipleApks(apkPaths: List<String>, canDowngrade: Boolean): Result<Unit> {
        return installViaSession(apkPaths, canDowngrade)
    }

    /**
     * Install one or more APKs through a PackageInstaller *session*, streaming each
     * file's bytes into the session over stdin (`cat <apk> | pm install-write … -`).
     *
     * The old `pm install <path>` / `pm install-multiple <paths>` handed the system
     * installer a path under the app's private cache (`/data/data/…`). On modern
     * Android the installer can't read another app's private files, so `pm` aborted
     * with exit 255 (GH#159). Here the root shell's own `cat` reads the app-private
     * temp file and pipes the bytes in, so neither `pm` nor `installd` ever opens a
     * `/data/data` path — the install works regardless of where the temp APKs live.
     * On failure the real `pm` reason is routed to stderr so it surfaces in the error.
     */
    private suspend fun installViaSession(
        apkPaths: List<String>,
        canDowngrade: Boolean
    ): Result<Unit> {
        if (apkPaths.isEmpty()) {
            return Result.failure(Exception("No APK paths provided for install"))
        }
        // Abort before opening a session if any APK is missing/unreadable: otherwise a
        // 0-byte File.length() below would stream `-S 0` into pm install-write and only
        // fail later at commit with a cryptic reason.
        apkPaths.firstOrNull { File(it).length() == 0L }?.let {
            return Result.failure(Exception("APK file is missing or empty: $it"))
        }
        val currentUser = getCurrentUserId()
        val downgrade = if (canDowngrade) " -d" else ""
        
        val installerArg = preferenceRepository.getInstallerArg()

        val sb = StringBuilder()
        // Run the whole thing in a subshell so our `exit` codes exit the SUBSHELL, not
        // libsu's long-lived root shell. Exiting the parent shell would kill it before
        // libsu appends its end-marker, leaving it unable to read the real exit code
        // (it then falls back to code 1) — and would break every later root command.
        sb.append("(\n")
        // pipefail so a failed `cat` (missing/unreadable APK) in the install-write
        // pipeline below propagates to the || abort branch instead of being masked by
        // pm install-write's exit code.
        sb.append("set -o pipefail\n")
        // Create the session (targeting the current user, like every other pm command in
        // this gateway); capture stdout+stderr so a failure reason isn't lost, then pull
        // the numeric id out of "…created install session [<id>]".
        sb.append("CREATE_OUT=\$(pm install-create -r -g").append(installerArg).append(" --user ").append(currentUser).append(downgrade).append(" 2>&1)\n")
        sb.append("SID=\$(printf '%s\\n' \"\$CREATE_OUT\" | sed -n 's/.*\\[\\([0-9]*\\)\\].*/\\1/p')\n")
        sb.append("if [ -z \"\$SID\" ]; then echo \"pm install-create failed: \$CREATE_OUT\" 1>&2; exit 101; fi\n")
        // Stream each APK's bytes into the session via stdin.
        for (path in apkPaths) {
            val size = File(path).length()
            val escPath = path.escapeForShell()
            val escName = File(path).name.escapeForShell()
            sb.append("WERR=\$(cat ").append(escPath)
                .append(" | pm install-write -S ").append(size)
                .append(" \"\$SID\" ").append(escName).append(" - 2>&1 1>/dev/null)")
                .append(" || { pm install-abandon \"\$SID\" 2>/dev/null;")
                .append(" echo \"pm install-write failed: \$WERR\" 1>&2; exit 102; }\n")
        }
        // Commit; anything but a Success line is a failure — surface pm's reason.
        sb.append("COMMIT=\$(pm install-commit \"\$SID\" 2>&1)\n")
        sb.append("case \"\$COMMIT\" in\n")
        sb.append("  *Success*) exit 0 ;;\n")
        sb.append("  *) pm install-abandon \"\$SID\" 2>/dev/null;")
            .append(" echo \"pm install-commit failed: \$COMMIT\" 1>&2; exit 103 ;;\n")
        sb.append("esac\n")
        sb.append(")\n")
        return runCommand(sb.toString())
    }

    override suspend fun getAppCacheSize(packageName: String): Long {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return 0L
        }
        return try {
            val escapedPackage = packageName.escapeForShell()
            val result = shellRepository.exec("du -s /data/data/$escapedPackage/cache")
            val outputLine = (if (result.isSuccess) result.stdout.firstOrNull() else null) ?: return 0L

            // Output format is usually "12345   /path/to/file"
            // We parse this in Kotlin, not using brittle 'awk' or 'cut'
            val sizeInBlocks =
                outputLine.substringBefore('\t').substringBefore(' ').toLongOrNull() ?: 0L

            // du usually returns 1k blocks
            sizeInBlocks * 1024
        } catch (e: Exception) {
            Logger.e("RootSystemGateway", "Failed to get app cache size for $packageName", e)
            0L
        }
    }

    /**
     * Modernized Reinstall Logic.
     * Replaces the 'sed' and 'tr' pipes with proper Kotlin string manipulation.
     */
    override suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> {
        if (packageName == BuildConfig.APPLICATION_ID)
            return Result.failure(Exception("Cannot reinstall Thor"))
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }

        return withContext(Dispatchers.IO) {
            try {
                // 1. Get the APK path(s)
                val paths = getAppPaths(packageName)
                if (paths.isEmpty()) {
                    return@withContext Result.failure(Exception("Could not find APK path for $packageName"))
                }

                val combinedPath = paths.joinToString(" ") { it.escapeForShell() }

                // 2. Get Current User ID
                val currentUser = getCurrentUserId()

                // 3. Execute the reinstallation command
                val command =
                    "pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 $combinedPath"
                runCommand(command)
            } catch (e: Exception) {
                Logger.e("RootSystemGateway", "Reinstall with Google failed for $packageName", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Copies a file using Root privileges.
     */
    suspend fun copyFile(source: String, destination: String) {
        val escapedSource = source.escapeForShell()
        val escapedDest = destination.escapeForShell()
        val command = "cp $escapedSource $escapedDest"
        val result = runCommand(command)

        if (result.isFailure) {
            throw Exception("Root copy failed: $command")
        }
    }

    /**
     * Retrieves all APK paths (Base + Splits) for a package.
     */
    suspend fun getAppPaths(packageName: String): List<String> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return emptyList()
        }
        val escapedPackage = packageName.escapeForShell()
        val result = shellRepository.exec("pm path $escapedPackage")
        val lines = if (result.isSuccess) result.stdout else emptyList()

        return lines
            .filter { it.isNotBlank() }
            .map { it.removePrefix("package:").trim() }
    }


    override suspend fun grantPermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX) || !permissionName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        val userId = getPackageUserId(packageName)
            ?: return Result.failure(Exception("Cannot resolve the Android user for $packageName; refusing to grant on user 0."))
        val escapedPackage = packageName.escapeForShell()
        val escapedPerm = permissionName.escapeForShell()
        return runCommand("pm grant --user $userId $escapedPackage $escapedPerm")
    }

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX) || !permissionName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        val userId = getPackageUserId(packageName)
            ?: return Result.failure(Exception("Cannot resolve the Android user for $packageName; refusing to revoke on user 0."))
        val escapedPackage = packageName.escapeForShell()
        val escapedPerm = permissionName.escapeForShell()
        return runCommand("pm revoke --user $userId $escapedPackage $escapedPerm")
    }

    /**
     * The Android user the package actually lives in.
     *
     * `PackageManagerShellCommand.runGrantRevokePermission` seeds `userId = UserHandle.USER_SYSTEM`,
     * so a bare `pm grant`/`pm revoke` always lands on user 0 no matter which user Thor runs as. In a
     * work profile or a Xiaomi Second Space — both ordinary secondary users — that either fails
     * outright or silently mutates the primary user's same-named package.
     *
     * Deliberately derived from the *package*, not from `am get-current-user`: the foreground user of
     * a work-profile device is the parent (0) while the profile's packages live in 10.
     * `getApplicationInfoCompat` already carries MATCH_UNINSTALLED_PACKAGES, so a frozen system app
     * still resolves here.
     *
     * Null means the package could not be resolved at all; callers must fail rather than fall back to
     * user 0, which is the original bug.
     */
    private fun getPackageUserId(packageName: String): Int? =
        getApplicationInfoCompat(packageName)?.let { userIdOf(it.uid) }

    /**
     * Raw shell execution for extensions, via the root shell.
     */
    override suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>> {
        val result = shellRepository.exec(command)
        // JOB_NOT_EXECUTED (-1) means the shell could not run at all (lost root/session). Surface
        // that as a real failure so callers (e.g. ThorShellExecutor) map it to (-1, msg), rather
        // than masquerading as a command that ran. Any command that DID run returns its real exit
        // code (0 or non-zero) — matching the Shizuku/Dhizuku gateways which already pass the real
        // code through — so extensions can finally see it (this path used to hard-code 0).
        if (result.code == ShellResult.JOB_NOT_EXECUTED) {
            return Result.failure(
                java.io.IOException(result.stderr.joinToString("\n").ifBlank { "Root shell unavailable" })
            )
        }
        val output = result.stdout.joinToString("\n").ifBlank { result.stderr.joinToString("\n") }
        return Result.success(result.code to output)
    }

    /**
     * Helper to bridge ShellRepository's Result<List<String>> to Result<Unit>
     */
    private suspend fun runCommand(cmd: String): Result<Unit> {
        val result = shellRepository.exec(cmd)
        return if (result.isSuccess) {
            Result.success(Unit)
        } else {
            val message = result.stderr.joinToString("\n")
                .ifBlank { "Shell command failed with code ${result.code}: $cmd" }
            val exception = java.io.IOException(message)
            Logger.e("RootSystemGateway", "Command execution failed: $cmd", exception)
            Result.failure(exception)
        }
    }

    private fun getApplicationInfoCompat(packageName: String): android.content.pm.ApplicationInfo? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                packageName,
                android.content.pm.PackageManager.ApplicationInfoFlags.of(android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
            )
        } else {
            context.packageManager.getApplicationInfo(
                packageName,
                android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
            )
        }
    }.getOrNull()

    // @Volatile guarantees safe publication across threads: getCurrentUserId() runs on the IO
    // dispatcher while onServiceDisconnected() invalidates the cache on the main thread, so a
    // '--user <id>' read always sees a consistent value and a foreground-user switch is picked up
    // after the next reconnect (#34).
    @Volatile
    private var cachedUserId: String? = null

    // Bumped on every cache invalidation (onServiceDisconnected). getCurrentUserId() captures it
    // before the shell read and only commits the result if it is unchanged, so an invalidation that
    // races an in-flight lookup can't be silently overwritten by the stale value the lookup read.
    @Volatile
    private var userIdGeneration = 0

    private suspend fun getCurrentUserId(): String {
        cachedUserId?.let { return it }
        val gen = userIdGeneration
        val userResult = shellRepository.exec("am get-current-user")
        val currentUser = if (userResult.isSuccess) userResult.stdout.firstOrNull()?.trim() else null
        return if (currentUser != null && currentUser.matches(USER_ID_REGEX)) {
            // Only cache a *successfully* resolved id (caching the "0" fallback would let a transient
            // shell/daemon blip persist the wrong user, so later '--user' commands would target user
            // 0 on multi-user devices), AND only if no invalidation raced this lookup — a user switch
            // that fired onServiceDisconnected mid-read must not re-cache the stale value.
            currentUser.also { if (userIdGeneration == gen) cachedUserId = it }
        } else {
            "0"
        }
    }
}

/**
 * The state arithmetic behind the system-app freeze chain, taken as `(enabled, flags)` so it stays a
 * plain JVM unit under test: a `PackageManager` cannot be faked in a unit test, and *which rung the
 * chain picks* — one of which deletes the user's data — is the half worth pinning. Same shape as
 * [com.valhalla.thor.data.provider.isFrozenAppInfo], which exists for the same reason.
 *
 * Namespaced in an object rather than left as top-level functions: `com.valhalla.thor.data.gateway`
 * holds three gateways that all reason about these same flags, and a top-level `isEffectivelyEnabled`
 * here would be a redeclaration the moment the next one wants the same name.
 */
object RootFreezeChain {

    /**
     * Thor's one definition of "this app is up and running", the fold
     * `AppFreezeStateReader.candidateOf` applies and that `AppInfoMapper` and `AppRepositoryImpl`
     * repeat: FLAG_INSTALLED is not optional once MATCH_UNINSTALLED_PACKAGES is in the query flags,
     * because the lookup then *succeeds* for a package uninstalled for this user and reports
     * `enabled == true`.
     */
    fun isEffectivelyEnabled(enabled: Boolean, flags: Int): Boolean =
        enabled && (flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0

    /** The rungs of an unfreeze, in the order they have to be attempted. */
    enum class UnfreezeStep {
        /** Not installed for this user — frozen with `pm uninstall --user N`. */
        INSTALL_EXISTING,

        /** Installed but disabled — frozen with `pm disable`, or just restored by rung 1. */
        ENABLE,

        /** Installed *and* enabled: nothing left to do. */
        VERIFIED,
    }

    /**
     * The next rung for a package in state `(enabled, flags)`.
     *
     * Installed-ness is tested first on purpose. A package can be both uninstalled-for-user *and*
     * disabled: any freeze that escalated to the uninstall rung on top of a `pm disable` that had
     * already landed — an older build, or Shizuku on a device that refuses to disable system
     * packages, both of which root may be the privilege asked to undo. `pm enable` on a package that is not
     * installed for the user does not bring it back, while
     * `pm install-existing` on a disabled package does not enable it. Only install → enable clears
     * both; the caller re-reads between the two, so this being called twice is the normal path.
     */
    fun unfreezeStep(enabled: Boolean, flags: Int): UnfreezeStep = when {
        (flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) == 0 -> UnfreezeStep.INSTALL_EXISTING
        !enabled -> UnfreezeStep.ENABLE
        else -> UnfreezeStep.VERIFIED
    }
}