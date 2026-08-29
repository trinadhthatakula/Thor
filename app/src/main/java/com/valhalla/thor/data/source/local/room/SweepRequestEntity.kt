// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sweep_requests",
    indices = [Index(value = ["work_id"], unique = true)],
)
data class SweepRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "work_id")
    val workId: String,
    @ColumnInfo(name = "operation")
    val operation: String,
    @ColumnInfo(name = "freezer_mode")
    val freezerMode: String?,
    @ColumnInfo(name = "user_id")
    val userId: Int,
    @ColumnInfo(name = "source_surface")
    val sourceSurface: String,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "terminal_state")
    val terminalState: String?,
    @ColumnInfo(name = "succeeded")
    val succeeded: Int?,
    @ColumnInfo(name = "failed")
    val failed: Int?,
    @ColumnInfo(name = "busy")
    val busy: Int?,
    @ColumnInfo(name = "unresolved")
    val unresolved: Int?,
    @ColumnInfo(name = "terminal_at_epoch_ms")
    val terminalAtEpochMs: Long?,
    @ColumnInfo(name = "retain_until_epoch_ms")
    val retainUntilEpochMs: Long?,
)
