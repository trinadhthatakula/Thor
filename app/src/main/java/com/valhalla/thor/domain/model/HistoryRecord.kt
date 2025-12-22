package com.valhalla.thor.domain.model

import kotlinx.serialization.Serializable


@Serializable
enum class OperationType {
    INSTALL,
    UPDATE
}

@Serializable
data class HistoryRecord(
    val id: Long = 0,
    val packageName: String,
    val label: String,
    val version: String,
    val timestamp: Long,
    val type: OperationType,
    val path: String // Optional: Path to the file installed (for reference)
)
