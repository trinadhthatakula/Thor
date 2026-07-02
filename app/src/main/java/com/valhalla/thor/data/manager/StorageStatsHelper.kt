package com.valhalla.thor.data.manager

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * Computes total install size (app + data + cache) per package via
 * StorageStatsManager. Requires the GET_USAGE_STATS app-op for other packages
 * (see UsageAccessManager) — a per-package failure is skipped, never thrown.
 */
@Single
class StorageStatsHelper(private val context: Context) {

    private val statsManager = context.getSystemService(StorageStatsManager::class.java)
    private val pm = context.packageManager
    private val user = Process.myUserHandle()

    suspend fun installSizes(packages: List<String>): Map<String, Long> =
        withContext(Dispatchers.IO) {
            val manager = statsManager ?: return@withContext emptyMap()
            val out = HashMap<String, Long>(packages.size)
            for (pkg in packages) {
                val size = runCatching {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    val stats = manager.queryStatsForPackage(ai.storageUuid, pkg, user)
                    stats.appBytes + stats.dataBytes + stats.cacheBytes
                }.getOrNull()
                if (size != null) out[pkg] = size
            }
            out
        }
}
