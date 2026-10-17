// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey

@Entity(
    tableName = "export_task_details",
    foreignKeys = [
        ForeignKey(
            entity = DataTaskEntity::class,
            parentColumns = ["task_id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ExportTaskDetailEntity(
    @PrimaryKey
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "requested_format")
    val requestedFormat: String,
    @ColumnInfo(name = "destination_kind")
    val destinationKind: String?,
    @ColumnInfo(name = "destination_grant_identity")
    val destinationGrantIdentity: String?,
    @ColumnInfo(name = "naming_label")
    val namingLabel: String?,
    @ColumnInfo(name = "publication_policy")
    val publicationPolicy: String,
    @ColumnInfo(name = "deterministic_staging_identity")
    val deterministicStagingIdentity: String,
)
