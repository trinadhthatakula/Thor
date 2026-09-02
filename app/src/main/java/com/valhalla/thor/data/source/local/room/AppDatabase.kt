// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppEntity::class,
        FreezerEntity::class,
        ExtensionDataEntity::class,
        FreezeProfileEntity::class,
        FreezeProfileAppEntity::class,
        ComponentOverrideEntity::class,
        SweepRequestEntity::class,
        SweepTargetEntity::class,
        SweepRequestSourceEntity::class,
    ],
    version = 8,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        // 5 → 6 adds the two freeze-profile tables and touches nothing that already exists, so
        // Room can generate it: a shipped database gets the new tables and keeps its watchlist.
        AutoMigration(from = 5, to = 6),
        // 6 → 7 is the same shape: one new table, `component_overrides`, and no change to any
        // existing column. No `spec =` because there is nothing for a spec to describe — a
        // pure table-add needs no @DeleteColumn/@RenameTable hint.
        AutoMigration(from = 6, to = 7),
        // 7 → 8 adds only the durable sweep request, target, and source-association tables.
        AutoMigration(from = 7, to = 8),
    ],
    exportSchema = true
)
@TypeConverters(AppTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao
    abstract fun freezerDao(): FreezerDao
    abstract fun extensionDataDao(): ExtensionDataDao
    abstract fun freezeProfileDao(): FreezeProfileDao
    abstract fun componentOverrideDao(): ComponentOverrideDao
    abstract fun privilegeSweepDao(): PrivilegeSweepDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE apps ADD COLUMN isSuspended INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
