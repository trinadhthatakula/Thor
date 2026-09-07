// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "sweep_targets",
    primaryKeys = ["request_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = SweepRequestEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["request_id"]),
        Index(value = ["state"]),
    ],
)
data class SweepTargetEntity(
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "ordinal")
    val ordinal: Int,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "state")
    val state: String = "PENDING",
    @ColumnInfo(name = "claim_token")
    val claimToken: String? = null,
    @ColumnInfo(name = "claim_lease_expires_at_epoch_ms")
    val claimLeaseExpiresAtEpochMs: Long? = null,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "started_at_epoch_ms")
    val startedAtEpochMs: Long? = null,
    @ColumnInfo(name = "finished_at_epoch_ms")
    val finishedAtEpochMs: Long? = null,
    @ColumnInfo(name = "result_code")
    val resultCode: String? = null,
    @ColumnInfo(name = "root_lane_degraded")
    val rootLaneDegraded: Boolean = false,
)
