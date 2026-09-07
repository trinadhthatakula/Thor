// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "archive_task_details",
    foreignKeys = [
        ForeignKey(
            entity = DataTaskEntity::class,
            parentColumns = ["task_id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ArchiveTaskDetailEntity(
    @PrimaryKey
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "data_class_ids_json")
    val dataClassIdsJson: String,
    @ColumnInfo(name = "include_bundle")
    val includeBundle: Boolean?,
    @ColumnInfo(name = "kdf_salt_base64")
    val kdfSaltBase64: String?,
    @ColumnInfo(name = "destination_kind")
    val destinationKind: String?,
    @ColumnInfo(name = "destination_grant_identity")
    val destinationGrantIdentity: String?,
    @ColumnInfo(name = "restore_obb")
    val restoreObb: Boolean?,
    @ColumnInfo(name = "restore_source_kind")
    val restoreSourceKind: String?,
    @ColumnInfo(name = "restore_source_grant_identity")
    val restoreSourceGrantIdentity: String?,
    @ColumnInfo(name = "restore_source_private_relative_path")
    val restoreSourcePrivateRelativePath: String?,
    @ColumnInfo(name = "deterministic_staging_identity")
    val deterministicStagingIdentity: String,
    @ColumnInfo(name = "destructive_started")
    val destructiveStarted: Boolean,
    @ColumnInfo(name = "mutation_package_name")
    val mutationPackageName: String?,
    @ColumnInfo(name = "mutation_app_label")
    val mutationAppLabel: String?,
    @ColumnInfo(name = "mutation_started_at_epoch_ms")
    val mutationStartedAtEpochMs: Long?,
)
