// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.os.Environment
import android.os.SystemClock
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.gateway.DhizukuSystemGateway
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.gateway.ShizukuSystemGateway
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.gateway.ComponentEnabledState
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionException
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.capabilityProbeCommand
import com.valhalla.thor.domain.model.classSizeCommand
import com.valhalla.thor.domain.model.dataClassRoot
import com.valhalla.thor.domain.model.measuredExclusions
import com.valhalla.thor.domain.model.parseCapabilityProbe
import com.valhalla.thor.domain.model.parseClassSize
import com.valhalla.thor.domain.model.sharedDataCapabilityProbeCommand
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.StorageStatsProvider
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

internal fun Throwable.rethrowIfPrivilegeExecutionFailure() {
    if (this is CancellationException || this is PrivilegeExecutionException) throw this
}

internal fun obbProbeFromExecutionResult(
    result: Result<Pair<Int, String?>>,
): ObbProbe = result.fold(
    onSuccess = { (exitCode, output) -> parseObbProbe(exitCode, output) },
    onFailure = { failure ->
        failure.rethrowIfPrivilegeExecutionFailure()
        ObbProbe.Undetermined(failure.message ?: "no privileged shell is available")
    },
)

internal suspend inline fun <T> resultPreservingCancellation(
    action: suspend () -> Result<T>,
): Result<T> = try {
    val result = action()
    val failure = result.exceptionOrNull()
    if (failure is CancellationException) throw failure
    result
} catch (cancelled: CancellationException) {
    // CancellationException is an Exception in Kotlin and must not be swallowed. Bulk operations
    // need a cancelled command to remain unresolved rather than becoming an ordinary failed result.
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

@Single(binds = [SystemRepository::class, AppDataProbe::class])
class SystemRepositoryImpl(
    private val rootGateway: RootSystemGateway,
    private val shizukuGateway: ShizukuSystemGateway,
    private val dhizukuGateway: DhizukuSystemGateway,
    private val preferenceRepository: PreferenceRepository,
    private val storageStats: StorageStatsProvider,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : SystemRepository, AppDataProbe {

    override suspend fun isRootAvailable(
        execution: PrivilegeExecutionContext,
    ): Boolean = withContext(ioDispatcher) {
        rootGateway.isRootAvailable(execution)
    }

    // The gateway probes confine their blocking binder IPC to IO themselves, so no extra
    // withContext(IO) hop is needed here (that would only double-dispatch).
    override suspend fun isShizukuAvailable(): Boolean = shizukuGateway.isShizukuAvailable()

    override suspend fun isDhizukuAvailable(): Boolean = dhizukuGateway.isDhizukuAvailable()

    // Short-lived cache of the resolved gateway. Batch operations (bulk freeze/unfreeze) call
    // getActiveGateway() once per app, and every resolution does a DataStore read plus one or
    // more availability binder-IPC probes. Caching the resolved gateway for a few seconds lets a
    // whole batch reuse a single resolution instead of re-probing for each app. The short TTL
    // keeps staleness bounded: a privilege-mode change is picked up within the TTL, and a gateway
    // that dies mid-batch still fails its individual action gracefully.
    @Volatile
    private var cachedGateway: SystemGateway? = null

    @Volatile
    private var cachedGatewayExpiryMs: Long = 0L

    // Cached entry point. Returns the cached gateway while fresh, otherwise resolves and (on
    // success) refreshes the cache. Resolution semantics live in resolveActiveGateway().
    private suspend fun getActiveGateway(
        execution: PrivilegeExecutionContext,
    ): Result<SystemGateway> {
        val now = SystemClock.elapsedRealtime()
        cachedGateway?.let { cached ->
            if (now < cachedGatewayExpiryMs) return Result.success(cached)
        }

        val result = resolveActiveGateway(execution)
        result.fold(
            onSuccess = {
                cachedGateway = it
                cachedGatewayExpiryMs = now + GATEWAY_CACHE_TTL_MS
            },
            onFailure = { cachedGateway = null }
        )
        return result
    }

    // Dynamic Resolution Strategy: Respect user preference if available, else auto-detect.
    // Must be suspend because checking root and reading preferences are suspend operations.
    //
    // Probes are evaluated lazily and short-circuit in Root -> Shizuku -> Dhizuku order so a
    // privileged action only pays for the probes it actually needs (a root user hits one probe,
    // not three). The root probe in particular can spawn a shell, so avoiding the eager
    // "probe all three every call" pattern removes redundant IPC on every batched app action.
    // Selection semantics are identical to probing all three up front.
    private suspend fun resolveActiveGateway(
        execution: PrivilegeExecutionContext,
    ): Result<SystemGateway> {
        val prefs = preferenceRepository.userPreferences.first()

        // 1. Try User Preference — probe only the preferred source.
        when (prefs.preferredPrivilegeMode) {
            PrivilegeMode.ROOT -> if (rootGateway.isRootAvailable(execution)) return Result.success(
                rootGateway
            )

            PrivilegeMode.SHIZUKU -> if (shizukuGateway.isShizukuAvailable()) return Result.success(
                shizukuGateway
            )

            PrivilegeMode.DHIZUKU -> if (dhizukuGateway.isDhizukuAvailable()) return Result.success(
                dhizukuGateway
            )
            // NONE is never persisted as a preference; null means "auto". Both fall through.
            PrivilegeMode.NONE, null -> Unit
        }

        // 2. Fallback to Auto-Detection — stop at the first available source.
        return when {
            rootGateway.isRootAvailable(execution) -> Result.success(rootGateway)
            shizukuGateway.isShizukuAvailable() -> Result.success(shizukuGateway)
            dhizukuGateway.isDhizukuAvailable() -> Result.success(dhizukuGateway)
            else -> Result.failure(IllegalStateException("No privileged gateway available (Root, Shizuku or Dhizuku required)"))
        }
    }

    private suspend inline fun <T> runGatewayAction(
        execution: PrivilegeExecutionContext,
        crossinline action: suspend (SystemGateway) -> Result<T>
    ): Result<T> {
        return getActiveGateway(execution).fold(
            onSuccess = { gateway ->
                resultPreservingCancellation { action(gateway) }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun forceStopApp(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction(execution) { it.forceStopApp(packageName, execution) }
    }

    // Root-gated rather than routed through runGatewayAction, and deliberately so: no other
    // privilege mode can clear one app's cache. `SystemGateway.clearAllCaches` records the
    // measurements behind that; the short version is that `INTERNAL_DELETE_CACHE_FILES` is
    // signature-level, so PackageManagerService answers Shizuku's call by logging that it is
    // silently ignoring it. This follows `copyFileWithRoot` and `getAppPaths`, the two operations
    // that were already root-only for a reason of their own.
    override suspend fun clearCache(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Long?> = withContext(ioDispatcher) {
        if (!rootGateway.isRootAvailable(execution)) {
            return@withContext Result.failure(
                Exception("Clearing one app's cache requires Root. Shizuku and Dhizuku can only clear every app's cache at once.")
            )
        }
        measuringCacheFreed({ storageStats.cacheBytes(packageName) }) {
            rootGateway.clearCache(packageName, execution)
        }
    }

    override suspend fun clearAllCaches(
        execution: PrivilegeExecutionContext,
    ): Result<Long?> = withContext(ioDispatcher) {
        measuringCacheFreed({ storageStats.totalCacheBytes() }) { before ->
            // The trim target is built from the *same* reading the freed figure is computed from,
            // which is why the before-value is handed down here rather than measured again. A
            // second `queryStatsForUser` would be a full walk of every app's data directory on a
            // device without filesystem quota support, taken microseconds after the first, for a
            // number that could differ only by whatever an app wrote in between.
            //
            // `null` is the no-usage-access case. Root goes on to sweep the directories by name;
            // Shizuku has no such rung and fails with a message naming the permission.
            val target = before?.let { storageStats.cacheTrimTargetBytes(it) }
            runGatewayAction(execution) { it.clearAllCaches(target, execution) }
        }
    }

    /**
     * Runs [clear] between two [measure] readings and reports the difference.
     *
     * The subtraction is the only way to answer "how much did that free". `pm trim-caches` prints
     * nothing on success and picks its own victims, and `rm -rf` prints nothing either, so neither
     * rung can report a byte count of its own.
     *
     * [clear] receives the before-reading because the whole-volume path needs it twice — once as the
     * baseline of the subtraction and once as the cache half of the `pm trim-caches` target — and
     * the two must be the same number rather than two measurements of it. The per-app path ignores
     * the argument.
     *
     * Three things it refuses to do. It does not measure when the clear failed — a failure's delta
     * is noise, and attaching a number to it invites a caller to show it. It answers `null`, not 0,
     * when either reading is missing, because "no usage access" and "there was no cache" are
     * different facts. And it clamps a negative delta to `null` rather than to 0: cache that an app
     * rebuilt between the two readings can make the after-value larger, and that is a measurement
     * Thor could not take, not a clear that freed nothing.
     */
    private suspend inline fun measuringCacheFreed(
        measure: () -> Long?,
        clear: (before: Long?) -> Result<Unit>
    ): Result<Long?> {
        val before = measure()
        val result = clear(before)
        if (result.isFailure) return Result.failure(
            result.exceptionOrNull() ?: Exception("Cache clear failed")
        )
        val after = measure()
        if (before == null || after == null) return Result.success(null)
        val freed = before - after
        return Result.success(if (freed >= 0) freed else null)
    }

    override suspend fun clearAppData(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction(execution) { it.clearAppData(packageName, execution) }
    }

    override suspend fun setAppDisabled(
        packageName: String,
        isDisabled: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction(execution) { it.setAppDisabled(packageName, isDisabled, execution) }
    }

    override suspend fun setAppSuspended(
        packageName: String,
        isSuspended: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction(execution) { it.setAppSuspended(packageName, isSuspended, execution) }
    }

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction(execution) { it.setAppRestricted(packageName, isRestricted, execution) }
    }

    override suspend fun uninstallApp(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction(execution) { it.uninstallApp(packageName, execution) }
    }

    override suspend fun rebootDevice(
        reason: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        if (rootGateway.isRootAvailable(execution)) {
            rootGateway.rebootDevice(reason, execution)
        } else {
            Result.failure(Exception("Reboot requires Root access"))
        }
    }

    // `aggressiveCleanup` used to sit here: force-stop then clear-cache, both `Result`s discarded,
    // then an unconditional `Result.success(Unit)`. It had no production caller — its only
    // references were the interface declaration and three interface-forced test overrides — so it
    // is deleted rather than taught to honour the results it was throwing away. A composite that
    // reports success no matter what its two steps did is worse than no composite: the caller that
    // would eventually be written for it inherits a guarantee nothing checks. The two operations
    // remain available individually as `forceStopApp` and `clearCache`, each returning its own
    // real `Result`.

    override suspend fun reinstallAppWithGoogle(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction(execution) { it.reinstallAppWithGoogle(packageName, execution) }
    }

    override suspend fun copyFileWithRoot(
        sourcePath: String,
        destinationPath: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        if (rootGateway.isRootAvailable(execution)) {
            try {
                rootGateway.copyFile(sourcePath, destinationPath, execution)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            Result.failure(Exception("Root required for privileged copy"))
        }
    }

    override suspend fun getAppPaths(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Result<List<String>> = withContext(ioDispatcher) {
        try {
            if (rootGateway.isRootAvailable(execution)) {
                val paths = rootGateway.getAppPaths(packageName, execution)
                if (paths.isNotEmpty()) Result.success(paths)
                else Result.failure(Exception("No paths found"))
            } else {
                Result.failure(Exception("Root required to fetch split paths reliably"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun grantPermission(
        packageName: String,
        permissionName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction(execution) { it.grantPermission(packageName, permissionName, execution) }
    }

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction(execution) { it.revokePermission(packageName, permissionName, execution) }
    }

    // --- Per-component control -------------------------------------------------------------
    //
    // Routed, not root-gated. `clearCache` above short-circuits on `isRootAvailable()` because there
    // Shizuku and Dhizuku genuinely have nothing to try; here a Shizuku *can* succeed — when it was
    // started as root — and a pre-gate on `isRootAvailable()` would refuse that working
    // configuration on a device with no `su` binary at all. The gateway is where the uid is known,
    // so the gateway is where the refusal is made, and `componentCapability` is what stops the UI
    // from offering the control in the first place.
    //
    // [thorUserId] rather than 0: `pm`'s enable/disable/default-state trio seeds
    // `UserHandle.USER_SYSTEM`, so an unqualified command issued from a work profile edits the
    // personal profile's copy of the package and exits 0. Same trap the clear/suspend paths already
    // carry, same fix.

    override suspend fun setComponentEnabled(
        packageName: String,
        className: String,
        state: ComponentEnabledState,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction(execution) {
            it.setComponentEnabled(packageName, className, state, thorUserId, execution)
        }
    }

    override suspend fun forceLaunchActivity(
        packageName: String,
        className: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction(execution) {
            it.forceLaunchActivity(
                packageName,
                className,
                thorUserId,
                execution
            )
        }
    }

    override suspend fun stopService(
        packageName: String,
        className: String,
        execution: PrivilegeExecutionContext,
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction(execution) {
            it.stopService(
                packageName,
                className,
                thorUserId,
                execution
            )
        }
    }

    override suspend fun executeShellCommand(
        command: String,
        execution: PrivilegeExecutionContext,
    ): Result<Pair<Int, String?>> = withContext(ioDispatcher) {
        runGatewayAction(execution) { it.executeShellCommand(command, execution) }
    }

    /**
     * Deliberately built on [executeShellCommand] rather than on `runGatewayAction` directly: the
     * probe and the copy that follows it must run through the *same* privileged surface, or a
     * successful probe stops being evidence that the files can actually be captured — which is
     * what lets the export sheet leave the `.xapk` chip enabled on a `Present` result.
     *
     * No `withContext(ioDispatcher)` here: [executeShellCommand] already makes that hop, and
     * everything either side of it is pure string work.
     */
    override suspend fun probeObb(
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): ObbProbe {
        // Two distinct refusals, named separately: obbProbeCommand returns null for an unusable
        // package name *or* an unusable storage root, and folding them together reported the package
        // name as the cause of a missing external volume. The reason is the diagnostic, so it has to
        // name the thing that actually failed.
        val externalRoot = Environment.getExternalStorageDirectory()?.absolutePath
            ?: return ObbProbe.Undetermined("this device's shared storage is unavailable")
        val command = obbProbeCommand(externalRoot, packageName)
            ?: return ObbProbe.Undetermined(
                if (isUsablePackageName(packageName)) {
                    "\"$externalRoot\" is not a usable storage path"
                } else {
                    "\"$packageName\" is not a usable package name"
                }
            )

        val probeExecution = execution.copy(commandClass = OBB_PROBE)
        val verdict = obbProbeFromExecutionResult(
            executeShellCommand(command, probeExecution),
        )
        // The verdict is the only thing a user or a bug report can see about this, and until now it
        // reached them as a UI state with the reason thrown away — "Thor can't read this app's game
        // data" for a shell failure, a truncated reply and a genuinely unreadable directory alike.
        // One line at the single exit, on the reason string the sealed type already carries, is what
        // makes an Undetermined on a device answerable rather than guessable. Logged for every
        // verdict, not just the bad one: "we probed and got None" and "we never probed" are the two
        // states a silent log cannot tell apart.
        Logger.d(
            "SystemRepo",
            "obb probe for $packageName: " + when (verdict) {
                is ObbProbe.None -> "None"
                is ObbProbe.Present ->
                    "Present(${verdict.files.size} obb, ${verdict.otherEntryCount} other)"

                is ObbProbe.Undetermined -> "Undetermined(${verdict.reason})"
            }
        )
        return verdict
    }

    override suspend fun probePrivateDataCapability(): Boolean {
        val command = capabilityProbeCommand(BuildConfig.APPLICATION_ID, thorUserId) ?: return false
        return executeShellCommand(command).fold(
            onSuccess = { (exitCode, output) -> parseCapabilityProbe(exitCode, output) },
            onFailure = { false }
        )
    }

    override suspend fun probeDataArchiveCapability(): Boolean {
        if (probePrivateDataCapability()) return true
        val externalRoot = Environment.getExternalStorageDirectory()?.absolutePath.orEmpty()
        val command = sharedDataCapabilityProbeCommand(externalRoot) ?: return false
        return executeShellCommand(command).fold(
            onSuccess = { (exitCode, output) -> parseCapabilityProbe(exitCode, output) },
            onFailure = { false }
        )
    }

    override suspend fun measureDataClass(
        packageName: String,
        dataClass: DataClass
    ): DataClassSize {
        // Empty string rather than a bail-out when shared storage is unavailable: CE and DE do not
        // use it, and `dataClassRoot` refuses the two external classes on an unquotable root anyway.
        val externalRoot = Environment.getExternalStorageDirectory()?.absolutePath.orEmpty()
        val root = dataClassRoot(dataClass, packageName, thorUserId, externalRoot)
            ?: return DataClassSize.Undetermined
        // The exclusions are not an optimisation. This number is shown as the class size *and* is what
        // the space check refuses on, so measuring a cache the archive then drops refuses a backup that
        // would have fitted. `measuredExclusions` derives them from the same constant the backup filter
        // uses, so the two cannot drift.
        val command = classSizeCommand(root, measuredExclusions(dataClass))
            ?: return DataClassSize.Undetermined
        val execution = PrivilegeExecutionContext(
            lane = PrivilegeExecutionLane.ARCHIVE,
            commandClass = ARCHIVE_MEASURE,
            packageName = packageName,
        )
        return executeShellCommand(command, execution).fold(
            onSuccess = { (exitCode, output) -> parseClassSize(exitCode, output) },
            onFailure = { DataClassSize.Undetermined }
        )
    }

    private companion object {
        // TTL for the resolved-gateway cache; ~3s comfortably covers a single batch operation
        // while keeping privilege/availability staleness bounded.
        private const val GATEWAY_CACHE_TTL_MS = 3_000L
        val OBB_PROBE = PrivilegeCommandClass("obb.probe")
        private val ARCHIVE_MEASURE = PrivilegeCommandClass("archive.measure")
    }
}