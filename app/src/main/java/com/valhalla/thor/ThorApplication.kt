package com.valhalla.thor

import android.app.Application
import com.valhalla.thor.core.ThorShellConfig
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.util.LocaleManager
import com.valhalla.thor.di.commonModule
import com.valhalla.thor.di.coreModule
import com.valhalla.thor.di.installerModule
import com.valhalla.thor.di.preferenceModule
import com.valhalla.thor.di.presentationModule
import com.valhalla.thor.di.roomModule
import com.valhalla.thor.util.Logger
import com.valhalla.bypass.Bypass
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androix.startup.KoinStartup
import org.koin.dsl.koinConfiguration
import com.rosan.dhizuku.api.Dhizuku

class ThorApplication : Application(), KoinStartup {

    private val preferenceRepository: PreferenceRepository by inject()
    private val localeManager: LocaleManager by inject()

    override fun onKoinStartup() = koinConfiguration {
        androidContext(this@ThorApplication)
        androidLogger(Logger.koinLogLevel)
        modules(
            coreModule,
            installerModule,
            preferenceModule,
            commonModule,
            presentationModule,
            roomModule
        )
    }

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Bypass with custom logging
        Bypass.setLogger { message, throwable ->
            Logger.e("Bypass", message, throwable)
        }
        Bypass.prepareThor()
        ThorShellConfig.init()
        
        try {
            Dhizuku.init(this)
        } catch (e: Exception) {
            Logger.e("ThorApp", "Dhizuku init failed", e)
        }

        // Apply saved language on startup
        MainScope().launch {
            val prefs = preferenceRepository.userPreferences.first()
            localeManager.applyLocale(prefs.language)
        }
    }

}
