// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.di

import android.content.Context
import android.content.pm.PackageManager
import androidx.room.Room
import com.valhalla.superuser.ktx.RealShellRepository
import com.valhalla.superuser.ktx.ShellRepository
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.room.AppDao
import com.valhalla.thor.data.source.local.room.AppDatabase
import com.valhalla.thor.data.source.local.room.FreezerDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Module
@ComponentScan("com.valhalla.thor")
@Configuration
class AppModule {

    // Named CoroutineDispatcher bindings so IO/CPU-bound work can inject a dispatcher
    // instead of hardcoding Dispatchers.*, keeping call sites testable and swappable.
    @Single
    @Named("io")
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Single
    @Named("default")
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Single
    @Named("main")
    fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Single
    fun packageManager(context: Context): PackageManager = context.packageManager

    @Single
    fun appDatabase(context: Context): AppDatabase {
        val builder = Room.databaseBuilder(context, AppDatabase::class.java, "thor_database")
            .addMigrations(AppDatabase.MIGRATION_1_2)

        if (BuildConfig.DEBUG) {
            builder.fallbackToDestructiveMigration(dropAllTables = true)
        }
        return builder.build()
    }

    @Single
    fun appDao(appDatabase: AppDatabase): AppDao = appDatabase.appDao()

    @Single
    fun freezerDao(appDatabase: AppDatabase): FreezerDao = appDatabase.freezerDao()

    @Single
    fun extensionDataDao(appDatabase: AppDatabase) = appDatabase.extensionDataDao()

    // RealShellRepository lives in :suCore (com.valhalla.superuser.ktx), outside the scan scope
    @Single
    fun shellRepository(): ShellRepository = RealShellRepository()
}
