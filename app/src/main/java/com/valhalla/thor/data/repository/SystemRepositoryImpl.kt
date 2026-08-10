// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.os.Environment
import android.os.SystemClock
import com.valhalla.thor.data.gateway.DhizukuSystemGateway
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.gateway.ShizukuSystemGateway
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.StorageStatsProvider
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single(binds = [SystemRepository::class])
class SystemRepositoryImpl(
    private val rootGateway: RootSystemGateway,
    private val shizukuGateway: ShizukuSystemGateway,
    private val dhizukuGateway: DhizukuSystemGateway,
    private val preferenceRepository: PreferenceRepository,
    private val storageStats: StorageStatsProvider,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : SystemRepository {

    override suspend fun isRootAvailable(): Boolean = withContext(ioDispatcher) {
        rootGateway.isRootAvailable()
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
    private suspend fun getActiveGateway(): Result<SystemGateway> {
        val now = SystemClock.elapsedRealtime()
        cachedGateway?.let { cached ->
            if (now < cachedGatewayExpiryMs) return Result.success(cached)
        }

        val result = resolveActiveGateway()
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
    private suspend fun resolveActiveGateway(): Result<SystemGateway> {
        val prefs = preferenceRepository.userPreferences.first()

        // 1. Try User Preference — probe only the preferred source.
        when (prefs.preferredPrivilegeMode) {
            PrivilegeMode.ROOT -> if (rootGateway.isRootAvailable()) return Result.success(rootGateway)
            PrivilegeMode.SHIZUKU -> if (shizukuGateway.isShizukuAvailable()) return Result.success(shizukuGateway)
            PrivilegeMode.DHIZUKU -> if (dhizukuGateway.isDhizukuAvailable()) return Result.success(dhizukuGateway)
            // NONE is never persisted as a preference; null means "auto". Both fall through.
            PrivilegeMode.NONE, null -> Unit
        }

        // 2. Fallback to Auto-Detection — stop at the first available source.
        return when {
            rootGateway.isRootAvailable() -> Result.success(rootGateway)
            shizukuGateway.isShizukuAvailable() -> Result.success(shizukuGateway)
            dhizukuGateway.isDhizukuAvailable() -> Result.success(dhizukuGateway)
            else -> Result.failure(IllegalStateException("No privileged gateway available (Root, Shizuku or Dhizuku required)"))
        }
    }

    private suspend inline fun <T> runGatewayAction(
        crossinline action: suspend (SystemGateway) -> Result<T>
    ): Result<T> {
        return getActiveGateway().fold(
            onSuccess = { gateway ->
                try {
                    action(gateway)
                } catch (e: CancellationException) {
                    // CancellationException is an Exception in Kotlin and must not be swallowed.
                    // BulkFreezeRunner's per-package workers rely on this rethrow: without it a
                    // deadline-cancelled worker returns Result.failure(CancellationException)
                    // instead of throwing, and the package is counted as failed rather than
                    // unresolved.
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun forceStopApp(packageName: String): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction { it.forceStopApp(packageName) }
    }

    // Root-gated rather than routed through runGatewayAction, and deliberately so: no other
    // privilege mode can clear one app's cache. `SystemGateway.clearAllCaches` records the
    // measurements behind that; the short version is that `INTERNAL_DELETE_CACHE_FILES` is
    // signature-level, so PackageManagerService answers Shizuku's call by logging that it is
    // silently ignoring it. This follows `copyFileWithRoot` and `getAppPaths`, the two operations
    // that were already root-only for a reason of their own.
    override suspend fun clearCache(packageName: String): Result<Long?> = withContext(ioDispatcher) {
        if (!rootGateway.isRootAvailable()) {
            return@withContext Result.failure(
                Exception("Clearing one app's cache requires Root. Shizuku and Dhizuku can only clear every app's cache at once.")
            )
        }
        measuringCacheFreed({ storageStats.cacheBytes(packageName) }) {
            rootGateway.clearCache(packageName)
        }
    }

    override suspend fun clearAllCaches(): Result<Long?> = withContext(ioDispatcher) {
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
            runGatewayAction { it.clearAllCaches(target) }
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
        if (result.isFailure) return Result.failure(result.exceptionOrNull() ?: Exception("Cache clear failed"))
        val after = measure()
        if (before == null || after == null) return Result.success(null)
        val freed = before - after
        return Result.success(if (freed >= 0) freed else null)
    }

    override suspend fun clearAppData(packageName: String): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction { it.clearAppData(packageName) }
    }

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> =
        withContext(ioDispatcher) {
            runGatewayAction { it.setAppDisabled(packageName, isDisabled) }
        }

    override suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit> =
        withContext(ioDispatcher) {
            runGatewayAction { it.setAppSuspended(packageName, isSuspended) }
        }

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction { it.setAppRestricted(packageName, isRestricted) }
    }

    override suspend fun uninstallApp(packageName: String): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction { it.uninstallApp(packageName) }
    }

    override suspend fun rebootDevice(reason: String): Result<Unit> = withContext(ioDispatcher) {
        if (rootGateway.isRootAvailable()) {
            rootGateway.rebootDevice(reason)
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

    override suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction { it.reinstallAppWithGoogle(packageName) }
    }

    override suspend fun copyFileWithRoot(
        sourcePath: String,
        destinationPath: String
    ): Result<Unit> = withContext(ioDispatcher) {
        if (rootGateway.isRootAvailable()) {
            try {
                rootGateway.copyFile(sourcePath, destinationPath)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            Result.failure(Exception("Root required for privileged copy"))
        }
    }

    override suspend fun getAppPaths(packageName: String): Result<List<String>> = withContext(ioDispatcher) {
        try {
            if (rootGateway.isRootAvailable()) {
                val paths = rootGateway.getAppPaths(packageName)
                if (paths.isNotEmpty()) Result.success(paths)
                else Result.failure(Exception("No paths found"))
            } else {
                Result.failure(Exception("Root required to fetch split paths reliably"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun grantPermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction { it.grantPermission(packageName, permissionName) }
    }

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> = withContext(ioDispatcher) {
        runGatewayAction { it.revokePermission(packageName, permissionName) }
    }

    override suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>> =
        withContext(ioDispatcher) {
            runGatewayAction { it.executeShellCommand(command) }
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
    override suspend fun probeObb(packageName: String): ObbProbe {
        val command = obbProbeCommand(
            Environment.getExternalStorageDirectory()?.absolutePath.orEmpty(),
            packageName
        ) ?: return ObbProbe.Undetermined("\"$packageName\" is not a usable package name")

        return executeShellCommand(command).fold(
            onSuccess = { (exitCode, output) -> parseObbProbe(exitCode, output) },
            onFailure = { ObbProbe.Undetermined(it.message ?: "no privileged shell is available") }
        )
    }

    private companion object {
        // TTL for the resolved-gateway cache; ~3s comfortably covers a single batch operation
        // while keeping privilege/availability staleness bounded.
        private const val GATEWAY_CACHE_TTL_MS = 3_000L
    }
}