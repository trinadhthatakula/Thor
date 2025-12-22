package com.valhalla.thor.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.valhalla.thor.domain.model.HistoryRecord
import com.valhalla.thor.domain.model.OperationType

@Entity(tableName = "installation_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val label: String,
    val version: String,
    val timestamp: Long,
    val type: String, // Stored as String for simplicity
    val path: String
) {
    fun toDomain() = HistoryRecord(
        id = id,
        packageName = packageName,
        label = label,
        version = version,
        timestamp = timestamp,
        type = OperationType.valueOf(type),
        path = path
    )
}

fun HistoryRecord.toEntity() = HistoryEntity(
    id = id,
    packageName = packageName,
    label = label,
    version = version,
    timestamp = timestamp,
    type = type.name,
    path = path
)