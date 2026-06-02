package com.valhalla.thor.di

import android.content.Context
import android.content.pm.PackageManager
import androidx.room.Room
import com.valhalla.superuser.ktx.RealShellRepository
import com.valhalla.superuser.ktx.ShellRepository
import com.valhalla.thor.data.source.local.room.AppDao
import com.valhalla.thor.data.source.local.room.AppDatabase
import com.valhalla.thor.data.source.local.room.FreezerDao
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
@ComponentScan("com.valhalla.thor")
@Configuration
class AppModule {

    @Single
    fun packageManager(context: Context): PackageManager = context.packageManager

    @Single
    fun appDatabase(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "thor_database")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Single
    fun appDao(appDatabase: AppDatabase): AppDao = appDatabase.appDao()

    @Single
    fun freezerDao(appDatabase: AppDatabase): FreezerDao = appDatabase.freezerDao()

    // RealShellRepository lives in :suCore (com.valhalla.superuser.ktx), outside the scan scope
    @Single
    fun shellRepository(): ShellRepository = RealShellRepository()
}
