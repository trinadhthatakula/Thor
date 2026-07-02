package com.valhalla.thor.data.source.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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

    @Transaction
    suspend fun syncCache(toUpdate: List<AppEntity>, toDelete: List<String>) {
        if (toUpdate.isNotEmpty()) insertApps(toUpdate)
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
