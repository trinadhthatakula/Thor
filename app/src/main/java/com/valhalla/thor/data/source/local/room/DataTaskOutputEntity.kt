// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "data_task_outputs",
    foreignKeys = [
        ForeignKey(
            entity = DataTaskEntity::class,
            parentColumns = ["task_id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DataTaskItemEntity::class,
            parentColumns = ["task_id", "ordinal"],
            childColumns = ["task_id", "item_ordinal"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["task_id", "item_ordinal"])],
)
data class DataTaskOutputEntity(
    @PrimaryKey
    @ColumnInfo(name = "output_id")
    val outputId: String,
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "item_ordinal")
    val itemOrdinal: Int,
    @ColumnInfo(name = "private_relative_path")
    val privateRelativePath: String?,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "byte_size")
    val byteSize: Long,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "expires_at_epoch_ms")
    val expiresAtEpochMs: Long?,
)
