package com.valhalla.thor.data.source.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ExtensionDataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: ExtensionDataEntity)

    @Query("SELECT value FROM extension_data WHERE extensionPackageName = :packageName AND `key` = :key")
    fun getValue(packageName: String, key: String): String?

    @Query("DELETE FROM extension_data WHERE extensionPackageName = :packageName AND `key` = :key")
    fun delete(packageName: String, key: String)

    @Query("SELECT `key` FROM extension_data WHERE extensionPackageName = :packageName")
    fun getAllKeys(packageName: String): List<String>
}
