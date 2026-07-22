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
}
