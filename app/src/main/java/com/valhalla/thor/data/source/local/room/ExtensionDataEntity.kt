package com.valhalla.thor.data.source.local.room

import androidx.room.Entity

@Entity(tableName = "extension_data", primaryKeys = ["extensionPackageName", "key"])
data class ExtensionDataEntity(
    val extensionPackageName: String,
    val key: String,
    val value: String
)
