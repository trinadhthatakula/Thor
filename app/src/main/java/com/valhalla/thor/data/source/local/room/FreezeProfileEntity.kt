// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * One named freeze profile.
 *
 * [name] carries `COLLATE NOCASE` so the unique index below is case-insensitive: "Games" and
 * "games" are the same profile as far as a user is concerned, and letting both exist would make
 * the picker unusable. SQLite's NOCASE folds ASCII only, so a non-Latin script gets
 * case-sensitive uniqueness — acceptable, because the alternative is hand-rolling Unicode case
 * folding into an index.
 */
@Entity(
    tableName = "freeze_profiles",
    indices = [Index(value = ["name"], unique = true)],
)
data class FreezeProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val createdAt: Long,
)

/**
 * Membership of a package in a profile.
 *
 * A join table rather than a serialised list column so a profile's contents can be queried —
 * `FreezerBridgeProvider` has to ask "is this package in *any* profile?" on the launcher's hot
 * path, and that is one indexed lookup here versus deserialising every profile.
 *
 * `onDelete = CASCADE` because a membership row is meaningless without its profile; Room turns
 * foreign-key enforcement on for the whole database, so deleting a profile really does clear
 * these rather than leaving orphans behind.
 */
@Entity(
    tableName = "freeze_profile_apps",
    primaryKeys = ["profileId", "packageName"],
    foreignKeys = [
        ForeignKey(
            entity = FreezeProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    // The composite primary key already indexes profileId (it is the leading column), so only
    // the reverse lookup — "which profiles contain this package?" — needs one of its own.
    indices = [Index(value = ["packageName"])],
)
data class FreezeProfileAppEntity(
    val profileId: Long,
    val packageName: String,
)

/** A profile with its membership, read in one `@Transaction` so the two cannot disagree. */
data class FreezeProfileWithApps(
    @Embedded val profile: FreezeProfileEntity,
    @Relation(parentColumn = "id", entityColumn = "profileId")
    val apps: List<FreezeProfileAppEntity>,
)
