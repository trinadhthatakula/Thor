// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "data_task_items",
    primaryKeys = ["task_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = DataTaskEntity::class,
            parentColumns = ["task_id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["task_id"]),
        Index(value = ["state"]),
    ],
)
data class DataTaskItemEntity(
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "ordinal")
    val ordinal: Int,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "display_label")
    val displayLabel: String?,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "claim_token")
    val claimToken: String?,
    @ColumnInfo(name = "claim_lease_expires_at_epoch_ms")
    val claimLeaseExpiresAtEpochMs: Long?,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "result_code")
    val resultCode: String?,
    @ColumnInfo(name = "deterministic_staging_identity")
    val deterministicStagingIdentity: String,
    @ColumnInfo(name = "started_at_epoch_ms")
    val startedAtEpochMs: Long?,
    @ColumnInfo(name = "finished_at_epoch_ms")
    val finishedAtEpochMs: Long?,
)
