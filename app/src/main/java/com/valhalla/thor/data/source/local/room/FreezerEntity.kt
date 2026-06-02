package com.valhalla.thor.data.source.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "freezer_apps")
data class FreezerEntity(
    @PrimaryKey val packageName: String
)
