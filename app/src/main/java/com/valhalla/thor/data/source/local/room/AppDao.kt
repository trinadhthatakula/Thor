package com.valhalla.thor.data.source.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class PackageInstallSize(val packageName: String, val installSize: Long?)

@Dao
interface AppDao {
    @Query("SELECT * FROM apps")
    fun getAllAppsFlow(): Flow<List<AppEntity>>

    @Query("SELECT * FROM apps")
    suspend fun getAllApps(): List<AppEntity>

    @Query("SELECT * FROM apps WHERE packageName = :packageName")
    suspend fun getApp(packageName: String): AppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<AppEntity>)

    @Query("DELETE FROM apps WHERE packageName = :packageName")
    suspend fun deleteApp(packageName: String)

    @Query("SELECT packageName, installSize FROM apps WHERE packageName IN (:packages)")
    suspend fun getInstallSizes(packages: List<String>): List<PackageInstallSize>

    @Transaction
    suspend fun syncCache(toUpdate: List<AppEntity>, toDelete: List<String>) {
        if (toUpdate.isNotEmpty()) {
            // Scanned entities carry installSize = null (StorageStats is computed
            // lazily). Preserve any already-persisted size across the REPLACE so the
            // size cache survives re-syncs.
            // Chunk to stay under SQLite's IN(...) variable limit (999) on
            // devices with very large app counts.
            val existing = HashMap<String, Long?>(toUpdate.size)
            for (chunk in toUpdate.map { it.packageName }.chunked(999)) {
                for (item in getInstallSizes(chunk)) existing[item.packageName] = item.installSize
            }
            val merged = toUpdate.map {
                if (it.installSize == null) it.copy(installSize = existing[it.packageName]) else it
            }
            insertApps(merged)
        }
        toDelete.forEach { deleteApp(it) }
    }

    @Query("UPDATE apps SET installSize = :size WHERE packageName = :packageName")
    suspend fun updateInstallSize(packageName: String, size: Long?)

    @Transaction
    suspend fun updateInstallSizes(sizes: Map<String, Long>) {
        sizes.forEach { (pkg, size) -> updateInstallSize(pkg, size) }
    }

    @Query("DELETE FROM apps")
    suspend fun clearAll()
}
