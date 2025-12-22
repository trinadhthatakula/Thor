package com.valhalla.thor.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [HistoryEntity::class], version = 1, exportSchema = false)
abstract class ThorDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}