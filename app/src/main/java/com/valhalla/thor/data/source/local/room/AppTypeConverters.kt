package com.valhalla.thor.data.source.local.room

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json

class AppTypeConverters {
    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        return value?.let { Json.encodeToString(it) }
    }

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        return value?.let { Json.decodeFromString(it) }
    }
}
