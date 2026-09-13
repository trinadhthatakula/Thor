// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "sweep_request_sources",
    primaryKeys = ["request_id", "source_surface"],
    foreignKeys = [
        ForeignKey(
            entity = SweepRequestEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["request_id"])],
)
data class SweepRequestSourceEntity(
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "source_surface")
    val sourceSurface: String,
    @ColumnInfo(name = "associated_at_epoch_ms")
    val associatedAtEpochMs: Long,
)
