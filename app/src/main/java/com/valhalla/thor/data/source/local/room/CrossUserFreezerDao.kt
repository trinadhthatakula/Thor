// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "scoped_freezer_apps", primaryKeys = ["userSerial", "packageName"])
data class ScopedFreezerAppEntity(val userSerial: Int, val packageName: String)

@Entity(tableName = "scoped_freezer_disables", primaryKeys = ["userSerial", "packageName"])
data class ScopedFreezerDisableEntity(val userSerial: Int, val packageName: String, val previousSetting: Int)

@Entity(
    tableName = "scoped_freezer_profiles",
    indices = [Index(value = ["userSerial", "name"], unique = true)],
)
data class ScopedFreezerProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userSerial: Int,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val packagesJson: String,
    val icon: String = "freezer",
)

@Dao
interface CrossUserFreezerDao {
    @Query("SELECT packageName FROM scoped_freezer_apps WHERE userSerial = :serial")
    suspend fun watchlist(serial: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToWatchlist(app: ScopedFreezerAppEntity)

    @Query("DELETE FROM scoped_freezer_apps WHERE userSerial = :serial AND packageName = :packageName")
    suspend fun removeFromWatchlist(serial: Int, packageName: String)

    @Query("SELECT packageName FROM scoped_freezer_disables WHERE userSerial = :serial")
    suspend fun ownedDisables(serial: Int): List<String>

    @Query("SELECT previousSetting FROM scoped_freezer_disables WHERE userSerial = :serial AND packageName = :packageName")
    suspend fun previousSetting(serial: Int, packageName: String): Int?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun rememberDisable(app: ScopedFreezerDisableEntity)

    @Query("DELETE FROM scoped_freezer_disables WHERE userSerial = :serial AND packageName = :packageName")
    suspend fun forgetDisable(serial: Int, packageName: String)

    @Query("SELECT * FROM scoped_freezer_profiles WHERE userSerial = :serial ORDER BY name COLLATE NOCASE, id")
    suspend fun profiles(serial: Int): List<ScopedFreezerProfileEntity>

    @Insert
    suspend fun createProfile(profile: ScopedFreezerProfileEntity)

    @Query("UPDATE scoped_freezer_profiles SET name = :name, packagesJson = :packagesJson, icon = :icon WHERE userSerial = :serial AND id = :id")
    suspend fun updateProfile(serial: Int, id: Long, name: String, packagesJson: String, icon: String): Int

    @Query("DELETE FROM scoped_freezer_profiles WHERE userSerial = :serial AND id = :id")
    suspend fun deleteProfile(serial: Int, id: Long)
}
