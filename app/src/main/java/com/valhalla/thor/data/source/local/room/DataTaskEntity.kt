// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "data_tasks",
    indices = [Index(value = ["queue_sequence"], unique = true)],
)
data class DataTaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "queue_sequence")
    val queueSequence: Long,
    @ColumnInfo(name = "payload_schema_version")
    val payloadSchemaVersion: Int,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "target_key")
    val targetKey: String,
    @ColumnInfo(name = "service_session_token")
    val serviceSessionToken: String?,
    @ColumnInfo(name = "claim_token")
    val claimToken: String?,
    @ColumnInfo(name = "claim_lease_expires_at_epoch_ms")
    val claimLeaseExpiresAtEpochMs: Long?,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "cancel_requested_at_epoch_ms")
    val cancelRequestedAtEpochMs: Long?,
    @ColumnInfo(name = "stage")
    val stage: String?,
    @ColumnInfo(name = "interruption")
    val interruption: String?,
    @ColumnInfo(name = "completed")
    val completed: Long,
    @ColumnInfo(name = "total")
    val total: Long,
    @ColumnInfo(name = "result_code")
    val resultCode: String?,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "claimed_at_epoch_ms")
    val claimedAtEpochMs: Long?,
    @ColumnInfo(name = "started_at_epoch_ms")
    val startedAtEpochMs: Long?,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
    @ColumnInfo(name = "terminal_at_epoch_ms")
    val terminalAtEpochMs: Long?,
    @ColumnInfo(name = "retain_until_epoch_ms")
    val retainUntilEpochMs: Long?,
    @ColumnInfo(name = "acknowledged_at_epoch_ms")
    val acknowledgedAtEpochMs: Long?,
)
