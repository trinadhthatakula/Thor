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
    ],
    version = 6,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        // 5 → 6 adds the two freeze-profile tables and touches nothing that already exists, so
        // Room can generate it: a shipped database gets the new tables and keeps its watchlist.
        AutoMigration(from = 5, to = 6),
    ],
    exportSchema = true
)
@TypeConverters(AppTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao
    abstract fun freezerDao(): FreezerDao
    abstract fun extensionDataDao(): ExtensionDataDao
    abstract fun freezeProfileDao(): FreezeProfileDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE apps ADD COLUMN isSuspended INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
