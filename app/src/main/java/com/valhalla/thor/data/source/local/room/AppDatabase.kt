package com.valhalla.thor.data.source.local.room

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AppEntity::class, FreezerEntity::class, ExtensionDataEntity::class],
    version = 4,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4)
    ],
    exportSchema = true
)
@TypeConverters(AppTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao
    abstract fun freezerDao(): FreezerDao
    abstract fun extensionDataDao(): ExtensionDataDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE apps ADD COLUMN isSuspended INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
