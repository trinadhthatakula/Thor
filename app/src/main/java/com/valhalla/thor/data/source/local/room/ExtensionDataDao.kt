// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ExtensionDataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ExtensionDataEntity)

    @Query("SELECT value FROM extension_data WHERE extensionPackageName = :packageName AND `key` = :key")
    suspend fun getValue(packageName: String, key: String): String?

    @Query("DELETE FROM extension_data WHERE extensionPackageName = :packageName AND `key` = :key")
    suspend fun delete(packageName: String, key: String)

    @Query("SELECT `key` FROM extension_data WHERE extensionPackageName = :packageName")
    suspend fun getAllKeys(packageName: String): List<String>
}
