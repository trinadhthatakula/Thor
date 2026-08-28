// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.valhalla.superuser.Shell
import com.valhalla.superuser.ipc.RootService
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.rootservice.IThorRootService
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.asComponentState
import com.valhalla.thor.data.source.local.backgroundRestrictionCommand
import com.valhalla.thor.data.source.local.clearAppDataCommand
import com.valhalla.thor.data.source.local.ComponentCommandKind
import com.valhalla.thor.data.source.local.componentCommandFailure
import com.valhalla.thor.data.source.local.escapedComponentSpecOrNull
import com.valhalla.thor.data.source.local.setComponentStateCommand
import com.valhalla.thor.data.source.local.startActivityCommand
import com.valhalla.thor.data.source.local.stopServiceCommand
import com.valhalla.thor.data.source.local.clearCachePaths
import com.valhalla.thor.data.source.local.forceStopCommand
import com.valhalla.thor.data.source.local.SessionApk
import com.valhalla.thor.data.source.local.installViaSessionCommand
import com.valhalla.thor.data.source.local.installedAppsAppOpGrantCommands
import com.valhalla.thor.data.source.local.installedAppsAppOpRevokeCommands
import com.valhalla.thor.data.source.local.pmPathCommand
import com.valhalla.thor.data.source.local.setAppEnabledCommand
import com.valhalla.thor.data.source.local.shizuku.isPolicyRefusal
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.data.source.local.uninstallCommand
import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.RootCommandExecutor
import com.valhalla.thor.data.gateway.root.RootCommandResult
import com.valhalla.thor.domain.gateway.ComponentEnabledState
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.domain.model.GET_INSTALLED_APPS_PERMISSION
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionException
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.parseSuspendingPackages
import com.valhalla.thor.domain.model.uninstallFreezeFallbackAllowed
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File
import kotlin.coroutines.resume

private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")

// Upper bound for the RootService bind handshake. A null binder or a callback that never
// arrives must not pin connectionMutex forever and deadlock every later privileged op (H2).
private const val ROOT_SERVICE_BIND_TIMEOUT_MS = 10_000L

internal fun PrivilegeExecutionContext.forRootCommand(
    commandClass: PrivilegeCommandClass,
): PrivilegeExecutionContext = copy(commandClass = commandClass)

/**
 * The Android user every suspend and unsuspend in this gateway writes, verifies, and reads the
 * platform's suspension record for — Thor's own, and no longer a pinned 0.
 *
 * One operation used to name three different users, and the third is the one that made the pinning
 * indefensible. The *write* was user 0 (`ThorRootService`'s own constant, plus an API-28 `pm suspend`
 * that named no user at all, which `PackageManagerShellCommand.runSuspend` seeds to
 * `UserHandle.USER_SYSTEM`). [readSuspenders] parsed user 0 to match it, so those two agreed. But
 * [readSuspendedFlag] is `context.packageManager.getApplicationInfo`, an in-process query that can
 * only ever answer for **Thor's** user — and it is what decides the outcome on all four paths below,
 * including the early return that skips the unsuspend entirely.
 *
 * With Thor in a work profile or a Xiaomi Second Space those numbers differ, and the result was a
 * false success in both directions. A suspend paused the personal profile's copy of an app the user
 * never selected, verified it against a user-0 dump, and reported success while the copy they were
 * looking at stayed running. The unsuspend that should have undone it read `FLAG_SUSPENDED` for
 * Thor's user, found it false because nothing was ever suspended there, and returned success having
 * run no rung — leaving the suspension it had made unliftable from inside Thor.
 *
 * The user id crosses the binder now ([IThorRootService.setAppSuspendedAsForUser]), so write, dump
 * parse and flag read finally name one user. It keeps its own symbol rather than being spelled
 * [thorUserId] at each site so that the set of places which must agree stays one grep.
 */
// internal, not private — reached from another class here; see SyntheticAccessor in app/lint.xml.
internal val SUSPEND_USER_ID: Int get() = thorUserId

/**
 * Root implementation whose commands all cross [RootCommandExecutor].
 */
@Single
class RootSystemGateway internal constructor(
    private val context: Context,
    private val rootCommands: RootCommandExecutor,
    private val preferenceRepository: PreferenceRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : SystemGateway {

    private var rootService: IThorRootService? = null
    internal var userIdProvider: () -> Int = { thorUserId }
    private val connectionMutex = Mutex()
    private var isDaemonReset = false
    private var activeConnection: ServiceConnection? = null

    /**
     * Drop a stale [ServiceConnection], on the main thread because Odin requires it.
     *
     * `RootService.unbind` is `@MainThread` and enforces that at runtime — `RootServiceManager`
     * opens it with `enforceMainThread()`, which throws `IllegalStateException` unless
     * `Looper.myLooper()` is the main looper. [getRootService]'s callers arrive under
     * `withContext(ioDispatcher)`, so both cleanup sites below were throwing that exception into a
     * `runCatching` that discarded it, then nulling `activeConnection` regardless: the binding was
     * never actually released and the reference to it was thrown away, which is a leak that
     * survives until the process dies. `invokeOnCancellation` already got this right by posting to
     * the main looper; the two cleanup paths never had the same treatment applied.
     *
     * Suspending rather than posting, so the unbind is ordered strictly before the rebind that
     * follows it instead of racing that rebind from a queued Runnable. A failure is now logged
     * rather than silently swallowed — if this ever throws again it should be visible.
     *
     * Bounded for the same reason the bind below is (H2): this runs inside `connectionMutex`, so a
     * main looper that never gets round to us must not pin that mutex and deadlock every later
     * privileged op. `withTimeoutOrNull` returns rather than throws, so on timeout the caller
     * carries on to the bind.
     *
     * That timeout cannot simply give up, though, which is why there is a fallback below. It
     * cancels the main-dispatched block, so `conn` is never handed back to `RootServiceManager`,
     * and the caller then nulls `activeConnection` and drops the last reference to it. What that
     * strands is not a bare object leak: `RootServiceManager.connections` is refcounted per
     * `ServiceConnection`, and `services` is keyed by intent rather than by connection, so a
     * record that is never removed holds the service's `refCount` above zero for the life of the
     * process — after which no unbind of any *later* connection can reach the `refCount == 0`
     * branch that actually releases the service inside the root process.
     */
    private suspend fun unbindStaleConnection(conn: ServiceConnection) {
        val unbound = withTimeoutOrNull(ROOT_SERVICE_BIND_TIMEOUT_MS) {
            withContext(Dispatchers.Main) { unbindOnMain(conn) }
        }
        if (unbound == true) return

        // Not observed to have happened, so hand it to the looper to do whenever it catches up.
        // This keeps the ordering claim above intact rather than reintroducing the race it warns
        // about: the post is enqueued here, *before* the bind that follows queues its own main
        // dispatch, so a looper that recovers still runs this unbind first.
        //
        // Deliberately does not touch `activeConnection` — by the time this runs, that field may
        // legitimately hold a newer connection, and clearing it would strand that one instead.
        //
        // Also covers the (near-unreachable) throwing path rather than only the timeout, so this
        // does not rest on the order of statements inside Odin's `unbind`; a redundant retry there
        // is a no-op, since removing an absent connection does nothing.
        android.os.Handler(android.os.Looper.getMainLooper()).post { unbindOnMain(conn) }
    }

    /** The unbind itself, factored out only so the timeout fallback above can reuse it. */
    private fun unbindOnMain(conn: ServiceConnection): Boolean =
        runCatching { RootService.unbind(conn) }
            .onFailure {
                Logger.w(
                    "RootSystemGateway",
                    "unbind of stale root connection failed: ${it.message.orEmpty()}"
                )
            }
            .isSuccess

    private suspend fun getRootService(execution: PrivilegeExecutionContext): IThorRootService? =
        connectionMutex.withLock {
            if (!isDaemonReset) {
                isDaemonReset = true
                // Kill any old daemon so the newly compiled root service is loaded and executed
                try {
                    execute(
                        "pkill -f ${context.packageName}:root",
                        execution.forRootCommand(ROOT_SERVICE_RESET),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A stale daemon is optional; binding below remains the source of truth.
                }
            }

        rootService?.let { binder ->
            if (binder.asBinder().isBinderAlive) {
                return binder
            } else {
                rootService = null
                activeConnection?.let { oldConn ->
                    unbindStaleConnection(oldConn)
                    activeConnection = null
                }
            }
        }

        // Clean up any stale connection before creating a new one
        activeConnection?.let { oldConn ->
            unbindStaleConnection(oldConn)
            activeConnection = null
        }

        // Bind under a timeout so a null binder or a callback that never arrives can't hold
        // connectionMutex forever (H2). withTimeoutOrNull RETURNS null on timeout — it does not
        // throw — so on every path (success, null-binding, or timeout) withLock unwinds and the
        // mutex is released. On timeout the child coroutine is cancelled, which fires
        // invokeOnCancellation below to unbind the stale connection; the caller then falls back.
        withTimeoutOrNull(ROOT_SERVICE_BIND_TIMEOUT_MS) {
            // Hardcoded, and the one place in this class that must stay so. Odin's RootService.bind
            // is @MainThread and enforces it at runtime — RootServiceManager.bindInternal opens with
            // enforceMainThread(), which throws IllegalStateException unless Looper.myLooper() is
            // the main looper. An injectable "main" here would look like a test seam while being
            // the opposite: any dispatcher a test substituted would throw rather than bind.
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

    // A root check is strictly asynchronous. Invalidate any cached non-root shell so fresh su grants are recognized.
    override suspend fun isRootAvailable(
        execution: PrivilegeExecutionContext,
    ): Boolean {
        try {
            val cached = Shell.cachedShell
            if (cached != null && !cached.isRoot) {
                cached.close()
            }
        } catch (_: Throwable) {
        }
        val result = execute(
            "id -u",
            execution.forRootCommand(ROOT_AVAILABILITY),
        )
        return result.exitCode == 0 &&
            result.stdout.singleOrNull { it.isNotBlank() }?.trim() == ROOT_UID
    }

    override suspend fun isShizukuAvailable(): Boolean = false
    override suspend fun isDhizukuAvailable(): Boolean = false

    // killBackgroundProcesses' KILL_BACKGROUND_PROCESSES is satisfied via elevated privilege
    // (root shell) rather than a manifest grant.
    @SuppressLint("MissingPermission")
    override suspend fun forceStopApp(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val escapedPackage = packageName.escapeForShell()

        // What "stopped" means here, asked of the platform rather than of an exit code. All three
        // rungs below ask this one question, so it is one function: three hand-written copies of a
        // verifier are how one of them quietly stops matching the others.
        //
        // An unreadable package answers false, and that collapse is safe in the one direction that
        // matters — the opposite of the `null` [readSuspendedFlag] is careful to preserve. Every
        // caller here treats false as "not proven stopped" and does more work, ending at the
        // failure below; so "could not read" costs a wasted killBackgroundProcesses and a reported
        // failure, never a success that did not happen.
        //
        // What it does cost is a *sentence*. "FLAG_STOPPED is clear" and "the package could not be
        // read" are the same `false` here, so the failure message must not speak for both — see the
        // last read below, which keeps its `ApplicationInfo` for exactly that.
        fun isStoppedNow(): Boolean = getApplicationInfoCompat(packageName)?.run {
            (flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
        } ?: false

        // The two halves of this function have to name the same user. Everything below the shell
        // rung is in-process — killBackgroundProcesses and the FLAG_STOPPED reads both answer for
        // Thor's own user — while a bare `am force-stop` reads USER_ALL and kills the package
        // everywhere. Naming [thorUserId] is what makes the post-check evidence about the process
        // the command was aimed at.
        val shellResult = runCommand(
            forceStopCommand(escapedPackage, userIdProvider()), execution, FORCE_STOP,
        )
        // The exit code alone decided this, and it cannot say no:
        // `ActivityManagerShellCommand.runForceStop` ends in an unconditional `return 0`, so it
        // reports that the command parsed, never that a process died. `shellResult.isSuccess` was
        // therefore true on every device for every package, which made the fallback's FLAG_STOPPED
        // readback — [isStoppedNow], already written, already correct — dead code from the shell
        // rung's point of view: the verifier existed and was skipped by a rung that could not fail.
        // Do not simplify this back to `if (shellResult.isSuccess)`; that restores an unfalsifiable
        // success and takes the only evidence this function has with it.
        //
        // The readback is fresh because `am force-stop` is synchronous — AMS has committed the
        // stopped state before the shell command returns — and it is *about the same app* because
        // the command names [thorUserId] and `getApplicationInfo` can only ever answer for Thor's
        // own user. A shell 0 with a package that did not stop now falls through to the
        // unprivileged rung rather than being reported as done.
        if (shellResult.isSuccess && isStoppedNow()) return shellResult

        // Unprivileged check/fallback
        if (isStoppedNow()) return Result.success(Unit)

        runCatching {
            val am =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.killBackgroundProcesses(packageName)
        }

        // The last read is spelled out rather than asked for through [isStoppedNow], because the
        // failure message below has to say *which* of that function's two `false`s this is, and
        // re-reading to find out would describe a different moment than the one that decided.
        val postKillInfo = getApplicationInfoCompat(packageName)
        if (postKillInfo != null &&
            (postKillInfo.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
        ) {
            return Result.success(Unit)
        }

        // Two ways to arrive here now, and a bug report has to be able to tell them apart: the
        // shell command itself failed, or it exited 0 and the app kept running anyway. The old
        // message asserted the first unconditionally, which the guard above has just made false.
        val shellVerdict = if (shellResult.isSuccess) {
            "the shell force-stop exited 0"
        } else {
            "the shell command failed"
        }
        // The same defect one level down, and the reason this is not simply "it is still running":
        // that claim rests on [isStoppedNow], where an unreadable `ApplicationInfo` and a genuinely
        // clear FLAG_STOPPED are indistinguishable. A bug report generated from "Thor could not
        // read the package" must not read as "the kill did not work" — they need different fixes.
        val stateVerdict = if (postKillInfo == null) {
            "the package's ApplicationInfo could not be read back after killBackgroundProcesses, " +
                "so whether it is still running is unknown"
        } else {
            "FLAG_STOPPED is still clear after killBackgroundProcesses, so it is still running"
        }
        return Result.failure(
            Exception("Root force stop of $packageName failed: $shellVerdict, and $stateVerdict.")
        )
    }

    /**
     * Deletes [packageName]'s cache directories for [thorUserId].
     *
     * **Not an override.** This is the only privilege mode that can clear one app's cache, so it
     * sits off [SystemGateway] rather than on it — the same shape `copyFile` and `getAppPaths`
     * already have, and `SystemRepositoryImpl` reaches it the same way, behind `isRootAvailable()`.
     * The Shizuku and Dhizuku implementations that used to satisfy an interface method here are
     * gone; [SystemGateway.clearAllCaches] records why they could not have worked.
     *
     * Deliberately unverified, and deliberately staying that way: as uid 0 `rm -rf` is honest about
     * its own post-condition — exit 0 means those paths are gone or were never there, non-zero means
     * a real error — so there is no asynchronous framework call here and no `IPackageDataObserver`
     * to wait on. A readback would re-`stat` what the shell already reported on.
     */
    suspend fun clearCache(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val escapedPackage = packageName.escapeForShell()
        val command =
            "rm -rf ${clearCachePaths(escapedPackage, userIdProvider()).joinToString(" ")}"
        return runCommand(command, execution, CACHE_CLEAR)
    }

    /**
     * Root's whole-volume clear: `pm trim-caches`, and then a direct sweep **regardless of what the
     * trim said**.
     *
     * The two rungs are not a fallback chain, and treating them as one is what broke this in v1.94.
     * `pm trim-caches` cannot report a result — `PackageManagerShellCommand.runTrimCaches` waits on
     * its observer and returns 0 with no regard for how many bytes moved — so there is no success to
     * fall back *from*. The sweep is the rung that answers for itself, and it always runs.
     *
     * The trim still goes first, because it is `PackageManagerService` doing the deleting: it knows
     * about volumes Thor's globs do not name, it reaches every Android user rather than just this
     * one, and it leaves PMS's accounting current instead of stale. As uid 0 its `CLEAR_APP_CACHE`
     * check passes unconditionally (`ActivityManager.checkComponentPermission` short-circuits
     * `ROOT_UID`). Its failure is worth a log line and nothing more.
     *
     * The sweep is also why [targetFreeBytes] being `null` is survivable in this mode: it names the
     * same three directories per package that [clearCache] does, with the package component globbed,
     * so root clears caches on a device that has never granted usage access. Shizuku has no such
     * rung and genuinely cannot proceed without the number.
     *
     * **The two rungs do not have the same reach, and the narrower one is the deliberate part.**
     * `pm trim-caches` is volume-wide, so it takes every Android user with it; the sweep is scoped to
     * [thorUserId] and leaves a work profile's or a Second Space's caches alone. That is not an
     * oversight to be widened later — `PerUserCommands` exists because deleting another user's data
     * from a command that names no user is the defect this codebase kept finding, and an `rm -rf`
     * across every user's tree would be the largest instance of it. A user's other profiles are not
     * Thor's to empty on a tile tap taken in this one.
     */
    override suspend fun clearAllCaches(
        targetFreeBytes: Long?,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        if (targetFreeBytes != null) {
            // The verdict is logged and then dropped on the floor, and that is the fix. This used to
            // `return trim` on success, which made the sweep below unreachable: `runTrimCaches` waits
            // on its observer and then `return 0`s unconditionally, so `pm trim-caches` exits 0
            // whether it reclaimed gigabytes or bailed out on `freeStorage`'s first line. Root
            // therefore reported success on a no-op and never ran the rung that works — measured on
            // four devices, all answering "there was no cache left to clear".
            val trim = runCommand(
                "pm trim-caches $targetFreeBytes", execution, CACHE_TRIM,
            )
            if (trim.exceptionOrNull() is PrivilegeExecutionException) return trim
            if (trim.isFailure) {
                Logger.w(
                    "RootSystemGateway",
                    "Root cache trim did not complete"
                )
            }
        }
        // The glob is expanded by the shell running as uid 0, not by Thor, so an app whose package
        // name would need escaping is covered too — `*` never matches a path separator, so this
        // cannot reach outside the three parents.
        val sweep =
            clearCachePaths(escapedPackage = "*", userId = userIdProvider()).joinToString(" ")
        return runCommand("rm -rf $sweep", execution, CACHE_SWEEP)
    }

    override suspend fun clearAppData(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val escapedPackage = packageName.escapeForShell()
        val shellResult = runCommand(
            clearAppDataCommand(escapedPackage, thorUserId), execution, CLEAR_APP_DATA,
        )
        if (shellResult.isSuccess) return@withContext shellResult

        // Fallback to ThorRootService AIDL daemon. `clearAppDataForUser` and not the older
        // `clearAppData`: the daemon runs as uid 0 in user 0, so it cannot read Thor's user for
        // itself and the one-argument entry point wipes user 0 unconditionally. A daemon left over
        // from an older build has no such transaction code and answers false, which lands on the
        // failure below — the right way round for a call that destroys data.
        val service = getRootService(execution)
        // The failure below now names *which* way the AIDL rung produced nothing. "AIDL failed" —
        // the whole of what it used to say — folded three different diagnoses into one sentence of
        // a bug report about data that is still there: no daemon at all (the bind was refused or
        // timed out), a daemon that could not be reached (dead binder, `:root` killed mid-call),
        // and a daemon that answered no. The third covers both a PMS refusal and the older-build
        // case the paragraph above describes — `clearAppDataForUser` hands back a bare boolean, so
        // this side cannot separate those two and the string does not pretend to.
        //
        // What a `true` is, since it still returns a success here: `ThorRootService.clearAppData`
        // now hands `clearApplicationUserData` a real `IPackageDataObserver` and waits for
        // `onRemoveCompleted`, so `true` means a verdict of "cleared" actually arrived rather than
        // that the void call was dispatched without throwing. That is the whole point of the
        // observer, and it is why this rung's success is worth returning.
        //
        // What a `false` is has correspondingly widened, and the string below is deliberately vague
        // about it. `clearAppDataForUser` hands back a bare boolean, so REFUSED (PMS said no) and
        // UNVERIFIED (nothing came back inside the daemon's own wait) reach this side as the same
        // value. The daemon logs which one it was; this process cannot know, so "answered no"
        // rather than "refused" is the strongest claim available here.
        val daemonVerdict: String
        if (service != null) {
            val aidlCall = runCatching {
                service.clearAppDataForUser(packageName, userIdProvider())
            }.onFailure { e ->
                Logger.e("RootSystemGateway", "AIDL clearAppData failed", e)
            }
            if (aidlCall.getOrDefault(false)) {
                return@withContext Result.success(Unit)
            }
            daemonVerdict = aidlCall.fold(
                onSuccess = { "the root daemon answered no" },
                onFailure = { "the root daemon could not confirm the wipe (${it.javaClass.simpleName})" },
            )
        } else {
            daemonVerdict = "the root daemon would not bind, so it was never asked"
        }

        return@withContext Result.failure(
            Exception("Root clear app data of $packageName failed: the shell step failed and $daemonVerdict.")
        )
    }

    /**
     * Freeze ([isDisabled] = true) or unfreeze a package.
     *
     * **User apps** run the single `pm disable` / `pm enable` rung they always had, with the
     * unprivileged `setApplicationEnabledSetting` as a last resort. Two things about it are new, and
     * the second can report a failure where this path used to report a success, so both are worth
     * stating rather than leaving to be rediscovered:
     *
     *  - The rung now names [thorUserId]. That changes nothing on a single-user device — `pm
     *    disable` seeds `UserHandle.USER_SYSTEM`, so the bare command and `--user 0` are the same
     *    operation, byte for byte — and everything on a secondary user, where the bare form moved
     *    the parent profile's copy while the re-read below, which can only see Thor's own user,
     *    watched a package that never changed.
     *  - The rung is now verified by re-reading `ApplicationInfo`, which is this function's own
     *    stated rule (see the last paragraph) finally applied to the one path that never followed
     *    it. `pm` exits 0 for a command it accepted and did not act on, and that is not theoretical
     *    at user 0: an app some other freezer removed with `pm uninstall -k --user N` still has a
     *    `PackageSetting`, so `pm enable` on it exits 0, writes an enabled-setting nothing consults
     *    and leaves `FLAG_INSTALLED` clear. The app stays frozen and the old code called that
     *    success. A **null** read is not evidence in either direction — the package could not be
     *    resolved at all — so it keeps the exit code's answer instead of manufacturing a failure.
     *
     * The read is fresh, and the evidence for that is not an argument: it is the same
     * `getApplicationInfo(…, MATCH_UNINSTALLED_PACKAGES)` call, in the same process, that two
     * already-shipped paths depend on for exactly this — [freezeSystemApp]'s rung-1 short-circuit,
     * device-tested for GH#316, and `Shizuku.setAppDisabledDetailed`, whose
     * `firstRungThatSticks { isDisabledNow() == disabled }` has been the *only* judge of a freeze in
     * that gateway since it shipped, for ordinary user apps as well as preinstalled ones — the same
     * kind of package this rung handles. Mechanically, `ApplicationPackageManager` answers from a
     * cache keyed on a nonce system_server bumps when it commits a package-state change, so a read
     * issued after the shell command has returned cannot be served from a pre-command entry; on API
     * 28, which has no such cache, the call is a plain binder round trip to PMS and there is nothing
     * to be stale.
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
     *     to anything querying without `MATCH_UNINSTALLED_PACKAGES`. This rung is unreachable today
     *     and a failed rung 1 returns `Result.failure`. The code stays because the gate — not this
     *     gateway — owns that decision, and the deferred "remove it for this user anyway" path is
     *     what will re-open it.
     *
     *     Root reached that shape first. The gate now answers `false` for the other two privilege
     *     modes as well, and both their gateways end a refused disable the way this one does — an
     *     `IOException` naming what was tried, and the package left installed.
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
    override suspend fun setAppDisabled(
        packageName: String,
        isDisabled: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val appInfo = getApplicationInfoCompat(packageName)
        val isSystem = appInfo != null && (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val escapedPackage = packageName.escapeForShell()

        val currentUser = userIdProvider()

        if (isSystem) {
            return if (isDisabled) {
                freezeSystemApp(packageName, escapedPackage, currentUser, execution)
            } else {
                unfreezeSystemApp(packageName, escapedPackage, currentUser, execution)
            }
        }

        val shellResult = runCommand(
            setAppEnabledCommand(escapedPackage, currentUser, isDisabled),
            execution,
            APP_ENABLED_STATE,
        )

        // Not a bare `if (isSuccess) return it` — the exit code is not the judge here; see the KDoc.
        // `enabled != isDisabled` reads oddly and is the whole test: it is "the state we asked for
        // was reached", since reaching it means `enabled == !isDisabled`. A null read is neither, so
        // the exit code keeps its answer.
        if (shellResult.isSuccess) {
            val enabled = readEffectivelyEnabled(packageName)
            if (enabled == null || enabled != isDisabled) return shellResult
        }

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
        currentUser: Int,
        execution: PrivilegeExecutionContext,
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
        // failed: its typed result is used only to decide whether policy allows the next rung.
        val disableResult = runCommand(
            setAppEnabledCommand(escapedPackage, currentUser, isDisabled = true),
            execution, APP_ENABLED_STATE,
        )
        when (readEffectivelyEnabled(packageName)) {
            false -> {
                Logger.i(
                    "RootSystemGateway",
                    "freeze $packageName: disable step took effect — app data preserved"
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
                    "Root freeze of $packageName: the disable step ran but the package state could " +
                            "no longer be read, so the uninstall fallback was NOT attempted — it would " +
                            "uninstall the package for this user on a state we cannot confirm."
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
        // passed honestly rather than hardcoded to false, so all three gateways call the gate the
        // same way and a future decision to let any of them escalate is a change in the policy,
        // not here.
        if (!uninstallFreezeFallbackAllowed(
                isSystem = true,
                privilegeMode = PrivilegeMode.ROOT,
                disableRefusedByPolicy = isPolicyRefusal(
                    disableResult.exceptionOrNull()?.message
                ),
            )
        ) {
            val refused = java.io.IOException(
                "Root freeze of $packageName failed: the disable step did not take effect and the " +
                        "package is still enabled. Root can disable any package, so this is a real refusal " +
                        "rather than a platform limit — the uninstall fallback is not permitted here, and " +
                        "the package was left installed."
            )
            Logger.e("RootSystemGateway", refused.message.orEmpty(), refused)
            return Result.failure(refused)
        }

        // `-k` (DELETE_KEEP_DATA) is not optional here: without it this line deletes
        // /data/user/N/<pkg> and an unfreeze returns the app factory-fresh, which is the bug this
        // whole change exists to remove. With it, the data directories keep the same inodes across
        // uninstall → install-existing (measured).
        runCommand(
            "pm uninstall -k --user $currentUser $escapedPackage", execution, UNINSTALL,
        )
        // Nothing is left to try, so anything but "definitely still enabled" is the state we asked
        // for: a null read can now only mean the package is no longer resolvable for this user,
        // which is precisely what `pm uninstall -k --user` produces.
        if (readEffectivelyEnabled(packageName) != true) {
            Logger.w(
                "RootSystemGateway",
                "freeze $packageName: disable had no effect; the uninstall fallback took effect — " +
                        "data directories survive; the package stops resolving without " +
                        "MATCH_UNINSTALLED_PACKAGES"
            )
            return Result.success(Unit)
        }

        val failure = java.io.IOException(
            "Root freeze of $packageName failed: neither the disable step nor the uninstall " +
                    "fallback changed the package's state."
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
        currentUser: Int,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        var attemptedSteps = 0

        fun unreadable(): Result<Unit> {
            val e = java.io.IOException(
                "Root unfreeze of $packageName failed: the package could not be read back" +
                        (if (attemptedSteps == 0) "." else " after $attemptedSteps recovery step(s).")
            )
            Logger.e("RootSystemGateway", e.message.orEmpty(), e)
            return Result.failure(e)
        }

        var step = readUnfreezeStep(packageName) ?: return unreadable()

        // --- Rung 1: the app was frozen by uninstalling it for this user (every build before the
        // `pm disable` rung existed, plus any package that still falls back to it). FLAG_INSTALLED
        // is clear, so put the package back for the user first.
        if (step == RootFreezeChain.UnfreezeStep.INSTALL_EXISTING) {
            runCommand(
                "pm install-existing --user $currentUser $escapedPackage",
                execution, INSTALL_EXISTING,
            )
            attemptedSteps++
            step = readUnfreezeStep(packageName) ?: return unreadable()
        }

        // --- Rung 2: the app is installed but disabled — either frozen with `pm disable`, or just
        // restored above with its old disabled enabled-setting intact. install-existing does not
        // clear that setting, which is why this runs *after* rung 1 rather than instead of it.
        if (step == RootFreezeChain.UnfreezeStep.ENABLE) {
            runCommand(
                setAppEnabledCommand(escapedPackage, currentUser, isDisabled = false),
                execution, APP_ENABLED_STATE,
            )
            attemptedSteps++
            step = readUnfreezeStep(packageName) ?: return unreadable()
        }

        // --- Rung 3: verify the end state is installed *and* enabled, from the platform's answer.
        if (step == RootFreezeChain.UnfreezeStep.VERIFIED) {
            Logger.i(
                "RootSystemGateway",
                "unfreeze $packageName: installed and enabled" +
                        (if (attemptedSteps == 0) " already, no recovery step run"
                        else " after $attemptedSteps recovery step(s)")
            )
            return Result.success(Unit)
        }

        val failure = java.io.IOException(
            "Root unfreeze of $packageName failed: still ${
                when (step) {
                    RootFreezeChain.UnfreezeStep.INSTALL_EXISTING -> "not installed for user $currentUser"
                    else -> "disabled"
                }
            } after $attemptedSteps recovery step(s)."
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

    /**
     * Whether the platform reports [packageName] as suspended right now, or `null` when its
     * `ApplicationInfo` could not be read at all.
     *
     * `null` is deliberately not `false`, for the same reason [readEffectivelyEnabled] is shaped
     * this way. The predecessor of this function ended in `?: false`, so a package Thor could not
     * read reported "not suspended" — and the unsuspend path read *that* as "the suspension is
     * gone, we succeeded". An unreadable package is an unverified one; only a positive `false` is
     * evidence that anything was lifted.
     *
     * `FLAG_SUSPENDED` is a public `ApplicationInfo` flag, so unlike the suspender *names* (which
     * need `dumpsys` and `android.permission.DUMP`) it can be read from the app process. It answers
     * "is this app still paused" but never "who owns it", which is why both reads exist.
     *
     * It is also the one read here that cannot be told which user to answer for: `getApplicationInfo`
     * answers for the process's own, always. That made it the reader which silently disagreed with
     * the writer for as long as the write was pinned to user 0 — see [SUSPEND_USER_ID], which is now
     * the same number by construction rather than by luck.
     */
    private fun readSuspendedFlag(packageName: String): Boolean? =
        getApplicationInfoCompat(packageName)?.let {
            (it.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
        }

    /**
     * Suspends or unsuspends [packageName], reporting only what a re-read of the platform's own
     * record can prove.
     *
     * ### The unsuspend side reads before it writes
     *
     * Android keys a suspension on the suspending package name captured at suspend time, and from
     * API 30 `PackageSettingBase.removeSuspension(callingPackage)` (android-11.0.0_r1
     * `PackageSettingBase.java:443-452`, carried into `SuspendPackageHelper` on 13-16) drops only
     * the caller's own entry, leaving `suspended` true while anyone else's survives. A suspension
     * made in Shizuku mode is recorded as `com.android.shell`, so the root path that only ever
     * named `com.valhalla.thor` removed nothing — and was told nothing, because naming a suspender
     * that owns no entry leaves `oldSuspendParams == null == newSuspendParams`, so `changed` is
     * false, so the package is logged "No change is needed" and left *out* of the failure array the
     * API returns. Switching to root to rescue such an app is the reported bug, and reading the
     * record and issuing one removal per recorded owner is what fixes it.
     *
     * Root can do that and no other privilege can:
     * `PackageManagerService.enforceCanSetPackagesSuspendedAsUser` (android-17.0.0_r1
     * `PackageManagerService.java:3354-3358`) unconditionally early-returns for `Process.ROOT_UID`
     * *before* any suspender-name validation, unchanged from API 28 to main, so a uid-0 call naming
     * `com.android.shell` is accepted verbatim. The shell branch of the same check is
     * `callingUid == SHELL_UID && isCallerSameApp(...)`, which is why Shizuku cannot do the reverse.
     *
     * ### Nothing here trusts an exit code
     *
     * `pm unsuspend` exits 0 for precisely the no-op above, so the old `cleared || shell.isSuccess`
     * turned "removed nothing" into a success; and the old flag read ended in `?: false`, so a
     * package whose `ApplicationInfo` could not be read reported "not suspended", which read as "we
     * succeeded". Both are gone. Every success below is either a record that no longer names anyone
     * or a `FLAG_SUSPENDED` that positively reads false; "could not tell" is a failure, and the
     * failure names the owner so the user learns *which* privilege holds the app.
     */
    override suspend fun setAppSuspended(
        packageName: String,
        isSuspended: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val hasReflection = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        val escapedPackage = packageName.escapeForShell()

        if (isSuspended) {
            // SUSPEND via the reflection path only: setPackagesSuspendedAsUser(caller = our app id).
            // A root-shell `pm suspend` (uid 0) records the suspender as "root", a non-existent
            // package, so tapping the paused app crashes SuspendedAppActivity
            // ("IllegalArgumentException: Package root does not exist"). We never fall back to the
            // shell for suspend — a broken suspension is worse than a reported failure. GH#239.
            //
            // The identity is still left to the daemon — hence the null third argument: the system
            // builds the user-visible "managed by Thor" line out of the recorded suspender name, so
            // what this writes as *who* is deliberately unchanged.
            //
            // What this writes as *where* is not. `setAppSuspendedAsForUser` and not the userless
            // `setAppSuspended`: the daemon runs as uid 0 in user 0 and cannot read Thor's user for
            // itself, so the old entry point suspended the primary user's copy while
            // [readSuspendedFlag] below — the only thing that can contradict the daemon — asked
            // Thor's. A daemon left over from an older build has no transaction code for this and
            // answers false, which lands on the failure below rather than on another user's app.
            if (hasReflection) {
                val service = getRootService(execution)
                if (service != null) {
                    val taskResult = runCatching {
                        service.setAppSuspendedAsForUser(packageName, true, null, SUSPEND_USER_ID)
                    }.onFailure { e ->
                        Logger.e("RootSystemGateway", "AIDL suspend failed", e)
                    }.getOrDefault(false)
                    // `== true` and not a bare call: readSuspendedFlag answers null when the package
                    // cannot be read, and "could not tell" must not stand in for the daemon's own
                    // verified success.
                    if (taskResult || readSuspendedFlag(packageName) == true) {
                        return@withContext Result.success(Unit)
                    }
                }
                return@withContext Result.failure(Exception("Root suspend failed via AIDL for $packageName."))
            }
            // API < 29 has no SuspendDialogInfo reflection path, so the shell is the whole rung and
            // it has to name the user itself. `PackageManagerShellCommand.runSuspend` seeds
            // `UserHandle.USER_SYSTEM`, so the bare form this replaces suspended user 0 whichever
            // user Thor runs as, while [readSuspendedFlag] — the only judge of the result — read
            // Thor's own and saw nothing change. On a single-user device the two commands are the
            // same operation byte for byte; on a secondary user they are two different apps.
            //
            // Its exit code is not the judge either, and never was entitled to be:
            // `PackageManagerShellCommand.runSuspend` returns 0 whenever `setPackagesSuspendedAsUser`
            // did not throw, discarding the failure array that names the packages it declined to
            // suspend — an exempt package, or one not installed for this user, comes back as a clean
            // exit. [readSuspendedFlag] already decides every other branch of this function (the AIDL
            // suspend above, the early return in [unsuspendPackage]); this rung was the one place a
            // `pm` exit code still stood in for it.
            //
            // `== true` and not a bare call, for the reason the AIDL branch above spells out:
            // [readSuspendedFlag] answers `null` for "could not read the package at all", and "could
            // not tell" must never stand in for success. The readback is legitimate here only because
            // [SUSPEND_USER_ID] is [thorUserId]: `--user` and `getApplicationInfo` — which can answer
            // for Thor's own user and nothing else — name one user by construction. Pin either back
            // to 0 and this guard starts asking about a different copy of the app than the command
            // changed. It is also fresh by the same argument [setAppDisabled] makes, only stronger:
            // this rung runs on API 28 alone, which has no `ApplicationInfo` cache, so the read is a
            // plain binder round trip to PMS.
            val shell = runCommand(
                "pm suspend --user $SUSPEND_USER_ID $escapedPackage", execution, APP_SUSPEND,
            )
            return@withContext if (shell.isSuccess && readSuspendedFlag(packageName) == true) shell
            else Result.failure(
                Exception(
                    if (shell.isSuccess) {
                        "Root suspend of $packageName is unverified: the shell step exited 0 but " +
                            "FLAG_SUSPENDED does not read back as set for user $SUSPEND_USER_ID."
                    } else {
                        "Root suspend failed for $packageName."
                    }
                )
            )
        }

        return@withContext unsuspendPackage(packageName, escapedPackage, hasReflection, execution)
    }

    /**
     * Lifts every suspension recorded against [packageName], and says so only when a readback agrees.
     *
     * Two shapes, picked by whether the platform's record could be read at all:
     *
     *  1. **Record readable** — one `setAppSuspendedAsForUser(…, owner, …)` per recorded owner, then
     *     a second read that has to come back empty. This is the rescue path for the user's
     *     Shizuku-era suspensions and the only one that can name an identity Thor never wrote.
     *  2. **Record unknown, empty included** — sweep the identities Thor could have written and let
     *     `FLAG_SUSPENDED` be the sole judge. Reached when the daemon will not bind, when the dump
     *     is denied or in a shape the parser has never seen, and on API 28, where there is no
     *     reflection overload to name an owner with and none is needed: `setSuspended(false)` there
     *     clears the single suspension slot whoever set it (android-9.0.0_r1
     *     `PackageSettingBase.java:399-407`).
     */
    private suspend fun unsuspendPackage(
        packageName: String,
        escapedPackage: String,
        hasReflection: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        // Already unsuspended — by us, by another tool, or never suspended at all. A *positive*
        // false, not the fail-open shortcut this change deletes: an unreadable flag is null, which
        // is neither true nor false and falls through to the full path. It also keeps a bulk
        // unfreeze from paying a `dumpsys package` round trip per app that was never suspended.
        if (readSuspendedFlag(packageName) == false) {
            Logger.i("RootSystemGateway", "unsuspend $packageName: not suspended, no rung run")
            return Result.success(Unit)
        }

        // The daemon is the only thing here that can read the record — `dumpsys package` is gated on
        // android.permission.DUMP via DumpUtils.checkDumpAndUsageStatsPermission (android-16
        // `PackageManagerService.java:6689`), which the app process does not hold — and the only
        // thing that can name an arbitrary owner, since the reflective overload it calls does not
        // exist before API 29.
        val service = if (hasReflection) getRootService(execution) else null

        // Past the early return above, the package is either suspended or unreadable — so a parse
        // that names nobody contradicts the flag and cannot be taken at face value. A dump in a
        // shape this parser has never seen (an OEM that dropped the token, a format newer than this
        // build) also parses to empty, and reading that as "nothing to remove, we are done" is the
        // same empty-means-success lie one layer down. Empty is therefore unknown *here*, and falls
        // through to the sweep rather than to a fabricated success.
        val recorded = service?.let { readSuspenders(it, packageName) }?.takeIf { it.isNotEmpty() }

        if (service != null && recorded != null) {
            // One removal per recorded owner, and deliberately no break on the first accepted call:
            // from API 30 `PackageUserState.suspendParams` is a map, so a package can carry several
            // entries at once and stays suspended while any one of them survives. Each removal names
            // the user the owners were read for, so what is lifted is what [readSuspenders] listed
            // rather than user 0's same-named entries.
            for (owner in recorded) {
                val accepted = runCatching {
                    service.setAppSuspendedAsForUser(packageName, false, owner, SUSPEND_USER_ID)
                }.onFailure { e ->
                    Logger.e("RootSystemGateway", "AIDL unsuspend of $packageName for one recorded owner failed", e)
                }.getOrDefault(false)
                if (!accepted) {
                    Logger.w(
                        "RootSystemGateway",
                        "unsuspend $packageName: the daemon could not confirm one owner removal"
                    )
                }
            }

            val remaining = readSuspenders(service, packageName) ?: return unsuspendFailure(
                "Root unsuspend of $packageName is unverified: the platform's suspension record " +
                    "could not be read back after asking to remove ${recorded.size} record(s), so " +
                    "Thor will not report a success it cannot see."
            )
            if (remaining.isNotEmpty()) {
                return unsuspendFailure(
                    "Root unsuspend of $packageName failed: ${remaining.size} suspension record(s) " +
                        "remain after Thor asked to remove ${recorded.size}, so the app stays paused."
                )
            }
            // The record names nobody, so the flag may only veto, never vouch: null here means the
            // package could not be read, which the record has already answered for.
            if (readSuspendedFlag(packageName) == true) {
                return unsuspendFailure(
                    "Root unsuspend of $packageName failed: removing ${recorded.size} suspender " +
                        "record(s) left nothing recorded for user $SUSPEND_USER_ID, yet the " +
                        "package still reports FLAG_SUSPENDED."
                )
            }
            Logger.i(
                "RootSystemGateway",
                "unsuspend $packageName: verified — ${recorded.size} suspender record(s) removed"
            )
            return Result.success(Unit)
        }

        // Unknown record. Sweep rather than guess: passing a null identity asks the daemon to clear
        // every name Thor has written across its history, and the root shell's `pm unsuspend` clears
        // the "root" entry left by a pre-GH#239 build or by the API-28 suspend path above
        // (PackageManagerShellCommand passes "root" as the calling package for uid 0). Neither is
        // allowed to *report* anything — an exit code of 0 is what the no-op returns — so the flag
        // read below is the only judge, and both rungs therefore have to act on the user that flag
        // answers for. That is what `--user $SUSPEND_USER_ID` and the fourth argument below are
        // doing; `runSuspend` would otherwise seed `USER_SYSTEM` and the daemon would otherwise
        // default to 0, neither of which is Thor's user in a work profile.
        if (service != null) {
            runCatching {
                service.setAppSuspendedAsForUser(packageName, false, null, SUSPEND_USER_ID)
            }.onFailure { e ->
                Logger.e("RootSystemGateway", "AIDL unsuspend failed", e)
            }
        }
        runCommand(
            "pm unsuspend --user $SUSPEND_USER_ID $escapedPackage", execution, APP_UNSUSPEND,
        )
        return when (readSuspendedFlag(packageName)) {
            false -> {
                Logger.i(
                    "RootSystemGateway",
                    "unsuspend $packageName: verified via FLAG_SUSPENDED; the suspender record was " +
                        "unreadable, so who owned it is unknown"
                )
                Result.success(Unit)
            }

            true -> unsuspendFailure(
                "Root unsuspend of $packageName failed: it is still suspended after the direct " +
                        "shell step and a sweep of every identity Thor records, and the platform's " +
                    "record could not be read to find out which one owns it."
            )

            null -> unsuspendFailure(
                "Root unsuspend of $packageName is unverified: neither the platform's suspension " +
                    "record nor the package's own ApplicationInfo could be read back."
            )
        }
    }

    /**
     * The identities the platform records as suspending [packageName] for [SUSPEND_USER_ID], or
     * `null` when the record could not be trusted.
     *
     * The `null` is the whole point of the wrapper. `parseSuspendingPackages` cannot tell a package
     * with no suspenders from a dump that was truncated, denied, or in a shape nobody has seen — all
     * three parse to an empty set — so "did we get a real dump?" is answered here, before anything
     * reads meaning into that emptiness. `dumpsys package <pkg>` always prints a `Package [<pkg>]
     * (…):` block for an installed package; a caller without `android.permission.DUMP` gets a
     * `Permission Denial:` line instead, and a truncated dump gets neither.
     *
     * A daemon still running from an older build predates `dumpPackage` entirely. Binder answers an
     * unknown transaction code with an empty reply parcel, which the generated proxy reads back as
     * `null`, so that degrades into "unknown" here rather than into a mis-dispatch.
     */
    private fun readSuspenders(service: IThorRootService, packageName: String): Set<String>? {
        val dump = runCatching {
            service.dumpPackage(packageName)
        }.onFailure { e ->
            Logger.e("RootSystemGateway", "AIDL dumpPackage failed for $packageName", e)
        }.getOrNull() ?: return null

        if (!dump.contains("Package [$packageName]")) {
            Logger.w(
                "RootSystemGateway",
                "Package suspender state could not be read for $packageName"
            )
            return null
        }
        return parseSuspendingPackages(dump, SUSPEND_USER_ID)
    }

    /**
     * Logs [reason] and returns it as the failure the UI renders verbatim.
     *
     * `AppInfoDetailsViewModel.toggleSuspendState` (and every other caller of
     * `ManageAppUseCase.setAppSuspended`) puts `e.message` straight into `R.string.error_format`, so
     * these sentences are user-facing: they name the recorded suspender because that is the only
     * thing that tells someone *which* privilege still holds their app.
     */
    private fun unsuspendFailure(reason: String): Result<Unit> {
        val failure = java.io.IOException(reason)
        Logger.e("RootSystemGateway", reason, failure)
        return Result.failure(failure)
    }

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val escapedPackage = packageName.escapeForShell()
        // Not the `pm` trap: `appops` seeds USER_CURRENT and resolves it, inside system_server, to
        // the *foreground* user. So the bare form did not land on user 0 and did not fan out to
        // every user — it landed on whoever happened to be in the foreground when the shell ran,
        // which on a work-profile device is the parent while Thor and the app it is restricting
        // live in the profile. See [backgroundRestrictionCommand] for the AOSP path.
        return runCommand(
            backgroundRestrictionCommand(escapedPackage, userIdProvider(), isRestricted),
            execution, BACKGROUND_RESTRICTION,
        )
    }

    override suspend fun rebootDevice(
        reason: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        val escapedReason = reason.escapeForShell()
        // executeResult returns success if ANY of the commands succeed in the chain logic
        return runCommand(
            "svc power reboot $escapedReason || reboot $escapedReason",
            execution, DEVICE_REBOOT,
        )
    }

    override suspend fun uninstallApp(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val escapedPackage = packageName.escapeForShell()
        // Through the shared builder rather than the byte-identical string this used to hold: the
        // `DELETE_ALL_USERS` trap a bare `pm uninstall` falls into is documented once, on
        // [uninstallCommand], and `Shizuku.uninstallApp` already reaches it the same way. Two
        // gateways spelling the destructive command themselves is how one of them keeps the `--user`
        // and the other loses it.
        return runCommand(
            uninstallCommand(escapedPackage, userIdProvider()), execution, UNINSTALL,
        )
    }

    override suspend fun installApp(
        apkPath: String,
        canDowngrade: Boolean,
        grantAllPermissions: Boolean?,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        return installViaSession(listOf(apkPath), canDowngrade, grantAllPermissions, execution)
    }

    suspend fun installMultipleApks(
        apkPaths: List<String>,
        canDowngrade: Boolean,
        grantAllPermissions: Boolean? = null,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Result<Unit> {
        return installViaSession(apkPaths, canDowngrade, grantAllPermissions, execution)
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
     * Command output remains inside the execution boundary; callers receive a generic failure.
     *
     * The script itself now comes from [installViaSessionCommand], shared with the Shizuku and
     * Dhizuku shell rungs. Those two were written *after* this method and after GH#159 was fixed
     * here, and they were written the old way — naming a path to `pm`, and reaching for a
     * `pm install-multiple` verb that no Android implements. A fix that lives inside one gateway is
     * a fix the next gateway has to be told about; this one is now spelled once. What stays here is
     * only what needs the filesystem.
     */
    private suspend fun installViaSession(
        apkPaths: List<String>,
        canDowngrade: Boolean,
        grantAllPermissions: Boolean?,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        if (apkPaths.isEmpty()) {
            return Result.failure(Exception("No APK paths provided for install"))
        }
        // Abort before opening a session if any APK is missing/unreadable: otherwise a
        // 0-byte File.length() below would stream `-S 0` into pm install-write and only
        // fail later at commit with a cryptic reason.
        if (apkPaths.any { File(it).length() == 0L }) {
            return Result.failure(Exception("An APK file is missing or empty"))
        }
        val staged = apkPaths.map { path ->
            val file = File(path)
            SessionApk(path = path, sizeBytes = file.length(), name = file.name)
        }
        return runCommand(
            installViaSessionCommand(
                apks = staged,
                userId = userIdProvider(),
                canDowngrade = canDowngrade,
                // The caller's answer if it has one, the saved setting otherwise. Not
                // `grantAllPermissions == true`: that would read a missing answer as "no" and
                // override a user who had turned the setting on.
                grantAllPermissions = grantAllPermissions
                    ?: preferenceRepository.shouldGrantAllPermissionsOnInstall(),
                installerArg = preferenceRepository.getInstallerArg(),
            ),
            execution,
            INSTALL_SESSION,
        )
    }

    // getAppCacheSize() used to sit here, on all three gateways, with no caller in the app. It was
    // also wrong: it sized only `/data/user/N/<pkg>/cache`, one of the three directories a cache
    // clear deletes, so any number it produced under-reported by whatever sat in the DE and external
    // caches. `StorageStatsProvider.cacheBytes` answers the same question from
    // `StorageStatsManager`, which counts external cache too, needs no privilege beyond the usage
    // access Thor already manages, and gives the same answer in every privilege mode.

    /**
     * Modernized Reinstall Logic.
     * Replaces the 'sed' and 'tr' pipes with proper Kotlin string manipulation.
     */
    override suspend fun reinstallAppWithGoogle(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        if (packageName == BuildConfig.APPLICATION_ID)
            return Result.failure(Exception("Cannot reinstall Thor"))
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }

        return withContext(ioDispatcher) {
            try {
                // 1. Get the APK path(s)
                val paths = getAppPaths(packageName, execution)
                if (paths.isEmpty()) {
                    return@withContext Result.failure(Exception("Could not find APK path for $packageName"))
                }

                val combinedPath = paths.joinToString(" ") { it.escapeForShell() }

                // 2. The user to reinstall for — Thor's own, matching every other `--user` here.
                val currentUser = userIdProvider()

                // 3. Execute the reinstallation command
                val command =
                    "pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 $combinedPath"
                runCommand(command, execution, REINSTALL)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Logger.e("RootSystemGateway", "Reinstall with Google failed for $packageName", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Copies a file using Root privileges.
     */
    suspend fun copyFile(
        source: String,
        destination: String,
        execution: PrivilegeExecutionContext,
    ) {
        val escapedSource = source.escapeForShell()
        val escapedDest = destination.escapeForShell()
        val command = "cp $escapedSource $escapedDest"
        val result = runCommand(command, execution, COPY_FILE)

        result.getOrThrow()
    }

    /**
     * Every APK path (base + splits) of [packageName] **as [thorUserId] sees it**, or an empty list
     * when the package has no record for that user.
     *
     * The user id is not decoration. `pm path` seeds `UserHandle.USER_SYSTEM`, so the bare form
     * answered for user 0's record whichever user Thor runs as, and [reinstallAppWithGoogle] — this
     * function's only in-app caller — feeds the answer straight into a `pm install … --user
     * $thorUserId`. Read one user, write another, and every command in the chain exits 0: Fix Store
     * would reinstall a secondary user's app off the primary user's record and report success. An
     * empty list now means "this user has no such package", which is what the caller already
     * assumed it meant when it turns it into "Could not find APK path".
     */
    suspend fun getAppPaths(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): List<String> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return emptyList()
        }
        val escapedPackage = packageName.escapeForShell()
        val result = execute(
            pmPathCommand(escapedPackage, userIdProvider()),
            execution.forRootCommand(APP_PATHS),
        )
        val lines = if (result.exitCode == 0) result.stdout else emptyList()

        return lines
            .filter { it.isNotBlank() }
            .map { it.removePrefix("package:").trim() }
    }


    override suspend fun grantPermission(
        packageName: String,
        permissionName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX) || !permissionName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        val userId = getPackageUserId(packageName)
            ?: return Result.failure(Exception("Cannot resolve the Android user for $packageName; refusing to grant on user 0."))
        val escapedPackage = packageName.escapeForShell()
        val escapedPerm = permissionName.escapeForShell()
        val res = runCommand(
            "pm grant --user $userId $escapedPackage $escapedPerm", execution, PERMISSION_GRANT,
        )
        if (permissionName != GET_INSTALLED_APPS_PERMISSION) return res

        // The app-ops are a *parallel route* to package visibility, not a follow-up to the grant,
        // so they run whatever `pm grant` returned. On the ROMs this permission exists for —
        // MIUI/HyperOS, ColorOS, OriginOS — the AOSP `pm grant` of a vendor-defined permission
        // frequently exits non-zero while the app-op is the thing that actually opens the package
        // list, which is why installedAppsAppOpGrantCommands fires three spellings of it. Gating
        // them on the grant succeeding is what made a Chinese-ROM install come back with Thor as
        // the only visible app: the grant failed, the app-op was never set, and nothing else in
        // the app knows how to open that gate.
        //
        // `runProbe`, not `runCommand`: at most one of the three spellings exists on any given
        // device, so two of them failing is the *success* path. `filter` and not `any`, so all three
        // are still issued — short-circuiting on the first that lands would change what Thor writes.
        // Keep only an aggregate count: the individual command text may contain package-sensitive
        // shell arguments and must not cross the execution boundary into logs.
        val appOpGrants = installedAppsAppOpGrantCommands(escapedPackage, userId)
        val acceptedGrants = appOpGrants.filter {
            runProbe(it, execution, PERMISSION_APP_OP_GRANT)
        }
        val appOpsTaken = acceptedGrants.size
        Logger.d(
            "RootSystemGateway",
            "GET_INSTALLED_APPS app-op grant for $packageName (user $userId): " +
                    "$appOpsTaken of ${appOpGrants.size} spellings accepted"
        )

        // The report follows the gate that actually opened, for every package and not just Thor's
        // own. Restricting this fold to self-grants put a one-way door in the permission manager:
        // this method is not self-only — PermissionManagerScreen -> TogglePermissionUseCase reaches
        // it for arbitrary third-party packages — so on the ROMs where `pm grant` refuses and the
        // app-op is what opens the package list, a third-party grant wrote the op, returned
        // failure, and left the row reading OFF, because `AppPermission.isGranted` comes from
        // REQUESTED_PERMISSION_GRANTED and an app-op does not move it. The only gesture the screen
        // then offers is *grant* again, and revokePermission — the sole issuer of
        // `appops set … default` — is never reached. Thor had opened a gate, reported that it had
        // not, and could not close it.
        //
        // The cost is the known one and it is the lesser: PermissionManagerScreen flips the row
        // optimistically, so it reads granted while PackageManager.checkPermission still says
        // denied until something refreshes it. That disagreement is with the runtime permission,
        // not with what the app can now do, and unlike the alternative it leaves the toggle able to
        // undo itself.
        return if (res.isSuccess || appOpsTaken > 0) {
            Result.success(Unit)
        } else {
            res
        }
    }

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX) || !permissionName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        val userId = getPackageUserId(packageName)
            ?: return Result.failure(Exception("Cannot resolve the Android user for $packageName; refusing to revoke on user 0."))
        val escapedPackage = packageName.escapeForShell()
        val escapedPerm = permissionName.escapeForShell()
        val res = runCommand(
            "pm revoke --user $userId $escapedPackage $escapedPerm", execution, PERMISSION_REVOKE,
        )
        if (permissionName != GET_INSTALLED_APPS_PERMISSION) return res

        // The revoke half of the parallel route, and the reason it cannot be left out: the app-op
        // grant above outlives `pm revoke`, so a revoke that only ran `pm revoke` reported success
        // while package visibility stayed open — and nothing else in the app could close it. Issued
        // whatever the revoke returned, for the same reason the grant is: on these ROMs the shell's
        // verdict on a vendor permission is not the state of the gate.
        // `runProbe` for the same reason as the grant: `filter` issues all three either way, and the
        // failures are not errors — see the aggregate count below.
        val appOpResets = installedAppsAppOpRevokeCommands(escapedPackage, userId)
        val acceptedResets = appOpResets.filter {
            runProbe(it, execution, PERMISSION_APP_OP_RESET)
        }
        Logger.d(
            "RootSystemGateway",
            "GET_INSTALLED_APPS app-op reset for $packageName (user $userId): " +
                    "${acceptedResets.size} of ${appOpResets.size} spellings accepted"
        )

        // And unlike the grant, the fold stays narrow: `pm revoke` is the verdict. All three app-op
        // resets failing is the ordinary outcome on any device that does not define this op, so
        // reading that as a failed revoke would report one on every AOSP device.
        return res
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

    // --- Per-component control -------------------------------------------------------------
    //
    // Root is the mode all three of these were written for: uid 0 is the only uid PMS and AMS will
    // accept them from. See the block comment on SystemGateway for why there is no second rung to
    // fall back to when they fail.

    override suspend fun setComponentEnabled(
        packageName: String,
        className: String,
        state: ComponentEnabledState,
        userId: Int,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        val spec = escapedComponentSpecOrNull(packageName, className)
            ?: return Result.failure(
                IllegalArgumentException("Invalid component: $packageName/$className")
            )
        return runComponentCommand(
            setComponentStateCommand(spec, userId, state.asComponentState()),
            execution, COMPONENT_STATE,
        )
    }

    override suspend fun forceLaunchActivity(
        packageName: String,
        className: String,
        userId: Int,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        val spec = escapedComponentSpecOrNull(packageName, className)
            ?: return Result.failure(
                IllegalArgumentException("Invalid component: $packageName/$className")
            )
        return runComponentCommand(
            startActivityCommand(spec, userId), execution, ACTIVITY_LAUNCH,
        )
    }

    override suspend fun stopService(
        packageName: String,
        className: String,
        userId: Int,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> {
        val spec = escapedComponentSpecOrNull(packageName, className)
            ?: return Result.failure(
                IllegalArgumentException("Invalid component: $packageName/$className")
            )
        return runComponentCommand(
            stopServiceCommand(spec, userId),
            execution,
            SERVICE_STOP,
            ComponentCommandKind.STOP_SERVICE,
        )
    }

    /**
     * Run a component command and judge it by its *output*, not only by its exit code.
     *
     * Separate from [runCommand] because component commands need output-aware interpretation; for `am start`
     * that is very nearly a constant: a launch refused for a permission denial still exits 0 on most
     * releases while printing `Security exception:` and a stack trace — while `am stopservice` exits
     * 255 even when it worked. [componentCommandFailure] holds both rules, so that the rules are one
     * function and are JVM-testable.
     *
     * `stdout + stderr` and not one of them: on Android 17 `am` writes the outcome of every one of
     * these commands to **stderr** and leaves stdout with nothing but the echo of the intent.
     */
    private suspend fun runComponentCommand(
        command: String,
        execution: PrivilegeExecutionContext,
        commandClass: PrivilegeCommandClass,
        kind: ComponentCommandKind = ComponentCommandKind.STANDARD,
    ): Result<Unit> {
        val result = try {
            execute(command, execution.forRootCommand(commandClass))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Result.failure(failure)
        }
        val output = (result.stdout + result.stderr).joinToString("\n")
        val failure = componentCommandFailure(result.exitCode, output, kind)
        return if (failure == null) {
            Result.success(Unit)
        } else {
            Logger.e("RootSystemGateway", "Component command failed")
            Result.failure(java.io.IOException("Root component command failed"))
        }
    }

    /** Raw shell execution for extensions, via the routed root shell. */
    override suspend fun executeShellCommand(
        command: String,
        execution: PrivilegeExecutionContext,
    ): Result<Pair<Int, String?>> {
        val routedExecution =
            if (execution.commandClass.value == DEFAULT_COMMAND_CLASS) {
                execution.forRootCommand(RAW_SHELL)
            } else {
                execution
            }
        val result = try {
            execute(command, routedExecution)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Result.failure(failure)
        }
        val output = result.stdout.joinToString("\n").ifBlank { result.stderr.joinToString("\n") }
        return Result.success(result.exitCode to output)
    }

    private suspend fun runCommand(
        command: String,
        execution: PrivilegeExecutionContext,
        commandClass: PrivilegeCommandClass,
    ): Result<Unit> {
        val result = try {
            execute(command, execution.forRootCommand(commandClass))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Result.failure(failure)
        }
        return if (result.exitCode == 0) {
            Result.success(Unit)
        } else {
            val exception =
                java.io.IOException("Root command failed with exit code ${result.exitCode}")
            Logger.e("RootSystemGateway", "Root command failed", exception)
            Result.failure(exception)
        }
    }

    private suspend fun runProbe(
        command: String,
        execution: PrivilegeExecutionContext,
        commandClass: PrivilegeCommandClass,
    ): Boolean = execute(
        command,
        execution.forRootCommand(commandClass),
    ).exitCode == 0

    private suspend fun execute(
        command: String,
        context: PrivilegeExecutionContext,
    ): RootCommandResult = rootCommands.execute(RootCommand(command, context))

    private companion object {
        const val ROOT_UID = "0"
        const val DEFAULT_COMMAND_CLASS = "interactive.command"
        val ROOT_SERVICE_RESET = PrivilegeCommandClass("root.service.reset")
        val ROOT_AVAILABILITY = PrivilegeCommandClass("root.availability")
        val FORCE_STOP = PrivilegeCommandClass("package.force-stop")
        val CACHE_CLEAR = PrivilegeCommandClass("package.cache-clear")
        val CACHE_TRIM = PrivilegeCommandClass("cache.trim")
        val CACHE_SWEEP = PrivilegeCommandClass("cache.sweep")
        val CLEAR_APP_DATA = PrivilegeCommandClass("package.clear-data")
        val APP_ENABLED_STATE = PrivilegeCommandClass("package.enabled-state")
        val INSTALL_EXISTING = PrivilegeCommandClass("package.install-existing")
        val APP_SUSPEND = PrivilegeCommandClass("package.suspend")
        val APP_UNSUSPEND = PrivilegeCommandClass("package.unsuspend")
        val BACKGROUND_RESTRICTION = PrivilegeCommandClass("package.background-restriction")
        val DEVICE_REBOOT = PrivilegeCommandClass("device.reboot")
        val UNINSTALL = PrivilegeCommandClass("package.uninstall")
        val INSTALL_SESSION = PrivilegeCommandClass("package.install-session")
        val REINSTALL = PrivilegeCommandClass("package.reinstall")
        val COPY_FILE = PrivilegeCommandClass("file.copy")
        val APP_PATHS = PrivilegeCommandClass("package.paths")
        val PERMISSION_GRANT = PrivilegeCommandClass("permission.grant")
        val PERMISSION_APP_OP_GRANT = PrivilegeCommandClass("permission.app-op-grant")
        val PERMISSION_REVOKE = PrivilegeCommandClass("permission.revoke")
        val PERMISSION_APP_OP_RESET = PrivilegeCommandClass("permission.app-op-reset")
        val COMPONENT_STATE = PrivilegeCommandClass("component.state")
        val ACTIVITY_LAUNCH = PrivilegeCommandClass("component.activity-launch")
        val SERVICE_STOP = PrivilegeCommandClass("component.service-stop")
        val RAW_SHELL = PrivilegeCommandClass("extension.raw-shell")
    }

    private fun getApplicationInfoCompat(packageName: String): android.content.pm.ApplicationInfo? =
        runCatching {
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

    // getCurrentUserId(), its @Volatile cache and the generation counter that guarded the cache all
    // lived here. Every one of them existed to make `am get-current-user` — a shell round trip whose
    // answer can change while the process runs — safe to reuse. It answered the wrong question:
    // the *foreground* user, which on a work-profile device is the parent (0) while Thor and the
    // packages it lists live in 10. So `pm uninstall --user 0` deleted the personal profile's copy
    // of a package the user never selected, and `pm disable --user 0` disabled a copy the verifying
    // re-read (which looks at Thor's user) could never see change. Its "0" fallback made a failed
    // read indistinguishable from a real answer.
    //
    // [thorUserId] is a process-lifetime constant read in-process, so there is nothing left to cache
    // and nothing left to invalidate — which is why onServiceDisconnected no longer resets anything.
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