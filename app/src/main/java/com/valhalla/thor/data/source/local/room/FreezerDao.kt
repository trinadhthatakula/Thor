// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FreezerDao {
    @Query("SELECT * FROM freezer_apps")
    fun getAll(): Flow<List<FreezerEntity>>

    @Query("SELECT packageName FROM freezer_apps")
    suspend fun getAllPackageNames(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM freezer_apps WHERE packageName = :packageName)")
    suspend fun contains(packageName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FreezerEntity)

    @Query("DELETE FROM freezer_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    /**
     * Drop several rows in one statement.
     *
     * Not a loop over [delete]: the caller is the package scan's prune, which runs on every trusted
     * rescan, and one statement is one write-ahead-log entry instead of N. Room refuses an empty
     * `IN ()`, so callers must not pass an empty collection — the pruner's own gate already
     * guarantees that, and a silent no-op here would hide the day it stops.
     */
    @Query("DELETE FROM freezer_apps WHERE packageName IN (:packageNames)")
    suspend fun deleteAll(packageNames: Collection<String>)
}
