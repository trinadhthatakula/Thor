// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.manager

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import com.valhalla.thor.domain.repository.StorageStatsProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * Every `StorageStatsManager` read Thor makes: per-package install size, the whole-user cache
 * total, and the free-space target a bounded `pm trim-caches` needs.
 *
 * Requires the GET_USAGE_STATS app-op for anything about other packages (see UsageAccessManager) —
 * a per-package failure is skipped, never thrown, and the two aggregate reads answer `null` rather
 * than 0 so a missing op is never rendered as a real measurement of zero.
 */
@Single(binds = [StorageStatsProvider::class])
class StorageStatsHelper(
    private val context: Context,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : StorageStatsProvider {

    private val statsManager = context.getSystemService(StorageStatsManager::class.java)
    private val pm = context.packageManager
    private val user = Process.myUserHandle()

    override suspend fun installSizes(packages: List<String>): Map<String, Long> =
        withContext(ioDispatcher) {
            val manager = statsManager ?: return@withContext emptyMap()
            // One Binder call for all ApplicationInfo instead of one per package.
            // (queryStatsForPackage below still costs one IPC each — there is no batch
            // API for it — so this only removes the cheaper per-package lookup.)
            val appInfos = runCatching {
                installedApplications().associateBy { it.packageName }
            }.getOrNull().orEmpty()

            val out = HashMap<String, Long>(packages.size)
            for (pkg in packages) {
                val size = runCatching {
                    val ai = appInfos[pkg] ?: applicationInfo(pkg)
                    val stats = manager.queryStatsForPackage(ai.storageUuid, pkg, user)
                    stats.appBytes + stats.dataBytes + stats.cacheBytes
                }.getOrNull()
                if (size != null) out[pkg] = size
            }
            out
        }

    // Both reads below are one binder call each and both answer for Thor's own user, so neither
    // needs INTERACT_ACROSS_USERS: `StorageStatsService` enforces that permission only on the
    // `userId != UserHandle.getCallingUserId()` branch.
    //
    // `runCatching` and not a typed catch because the three failure modes want the same answer.
    // SecurityException means the usage-access op is not held, IOException means the volume is not
    // present, and IllegalStateException has been seen from `findPathForUuid` on OEM builds with an
    // adopted-storage quirk. All three mean "no number", which is what `null` says.
    override suspend fun cacheBytes(packageName: String): Long? = withContext(ioDispatcher) {
        val manager = statsManager ?: return@withContext null
        runCatching {
            val ai = applicationInfo(packageName)
            val stats = manager.queryStatsForPackage(ai.storageUuid, packageName, user)
            stats.cacheBytes
        }.getOrNull()
    }

    override suspend fun totalCacheBytes(): Long? = withContext(ioDispatcher) {
        val manager = statsManager ?: return@withContext null
        runCatching { manager.queryStatsForUser(StorageManager.UUID_DEFAULT, user).cacheBytes }
            .getOrNull()
    }

    // Lint's UsableSpace check says to prefer `getAllocatableBytes`, "which will consider clearable
    // cached data". That advice is sound for its stated case — deciding whether a file will fit —
    // and is exactly the mistake this method exists to undo. `getAllocatableBytes` and
    // `StorageStatsManager.getFreeBytes` both add back the cache the system would clear on request;
    // `freeStorage` does not, and it is `freeStorage`'s bare `getUsableSpace()` that decides whether
    // a trim runs. Taking lint's advice here reproduces the v1.94 no-op verbatim.
    @Suppress("UsableSpace")
    override suspend fun cacheTrimTargetBytes(): Long? = withContext(ioDispatcher) {
        val cache = totalCacheBytes() ?: return@withContext null
        // Environment.getDataDirectory() and not a StorageStatsManager call: this has to be the
        // *same* number PMS compares against, and `StorageManager.findPathForUuid(UUID_PRIVATE_
        // INTERNAL)` resolves to exactly this file. Reading free space through any other API risks
        // a different view of the same volume, and the whole defect being fixed here was a target
        // that sat a hair below what PMS measured.
        val usable = runCatching { Environment.getDataDirectory().usableSpace }.getOrNull()
            ?: return@withContext null
        cacheTrimTarget(usableBytes = usable, totalCacheBytes = cache)
    }

    private fun installedApplications(): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

    // Fallback for a package missing from the bulk list (e.g. installed/removed mid-scan).
    private fun applicationInfo(pkg: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }
}

/**
 * The number `pm trim-caches` has to be given to reclaim [totalCacheBytes] of cache.
 *
 * Split out of [StorageStatsHelper] because it is the whole of what went wrong in v1.94 and it is
 * two framework calls away from being untestable. The arithmetic is the contract: PMS compares the
 * target against `getUsableSpace()` and returns immediately when the target is already met, so the
 * only targets that do anything are the ones **strictly greater** than [usableBytes].
 *
 * A zero or negative cache total deliberately yields exactly [usableBytes] — a target PMS is
 * guaranteed to refuse. That is the honest instruction for "there is nothing to reclaim": the trim
 * no-ops, the before/after measurement subtracts to 0, and the sheet says there was no cache left.
 * Clamping up to "free something anyway" would spend rungs 5 and 7 — shared libraries and instant
 * apps — to satisfy a request for zero bytes of cache.
 */
internal fun cacheTrimTarget(usableBytes: Long, totalCacheBytes: Long): Long =
    if (totalCacheBytes > 0) usableBytes + totalCacheBytes else usableBytes
