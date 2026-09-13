// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sweep_requests",
    indices = [
        Index(value = ["work_id"], unique = true),
        Index(value = ["queue_sequence"]),
    ],
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
    val succeeded: Int = 0,
    @ColumnInfo(name = "failed")
    val failed: Int = 0,
    @ColumnInfo(name = "busy")
    val busy: Int = 0,
    @ColumnInfo(name = "unresolved")
    val unresolved: Int = 0,
    @ColumnInfo(name = "terminal_at_epoch_ms")
    val terminalAtEpochMs: Long?,
    @ColumnInfo(name = "retain_until_epoch_ms")
    val retainUntilEpochMs: Long?,
    @ColumnInfo(name = "payload_schema_version")
    val payloadSchemaVersion: Int = 1,
    @ColumnInfo(name = "queue_sequence")
    val queueSequence: Long = 0L,
    @ColumnInfo(name = "state")
    val state: String = terminalState ?: "QUEUED",
    @ColumnInfo(name = "execution_id")
    val executionId: String = requestId,
    @ColumnInfo(name = "service_session_token")
    val serviceSessionToken: String? = null,
    @ColumnInfo(name = "claim_token")
    val claimToken: String? = null,
    @ColumnInfo(name = "claim_lease_expires_at_epoch_ms")
    val claimLeaseExpiresAtEpochMs: Long? = null,
    @ColumnInfo(name = "claimed_at_epoch_ms")
    val claimedAtEpochMs: Long? = null,
    @ColumnInfo(name = "started_at_epoch_ms")
    val startedAtEpochMs: Long? = null,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long = createdAtEpochMs,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "cancel_requested_at_epoch_ms")
    val cancelRequestedAtEpochMs: Long? = null,
    @ColumnInfo(name = "block_reason")
    val blockReason: String? = null,
    @ColumnInfo(name = "acknowledged_at_epoch_ms")
    val acknowledgedAtEpochMs: Long? = null,
    @ColumnInfo(name = "add_to_freezer", defaultValue = "0")
    val addToFreezer: Boolean = false,
)

/** Temporary source-compatible bridge for the aggregate-null schema 8 callers removed in Task 4. */
fun SweepRequestEntity(
    requestId: String,
    workId: String,
    operation: String,
    freezerMode: String?,
    userId: Int,
    sourceSurface: String,
    createdAtEpochMs: Long,
    terminalState: String?,
    succeeded: Int?,
    failed: Int?,
    busy: Int?,
    unresolved: Int?,
    terminalAtEpochMs: Long?,
    retainUntilEpochMs: Long?,
): SweepRequestEntity = SweepRequestEntity(
    requestId = requestId,
    workId = workId,
    operation = operation,
    freezerMode = freezerMode,
    userId = userId,
    sourceSurface = sourceSurface,
    createdAtEpochMs = createdAtEpochMs,
    terminalState = terminalState,
    succeeded = succeeded ?: 0,
    failed = failed ?: 0,
    busy = busy ?: 0,
    unresolved = unresolved ?: 0,
    terminalAtEpochMs = terminalAtEpochMs,
    retainUntilEpochMs = retainUntilEpochMs,
)
