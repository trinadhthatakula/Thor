package com.valhalla.thor

import android.app.Application
import com.valhalla.thor.core.ThorShellConfig
import com.valhalla.thor.di.commonModule
import com.valhalla.thor.di.coreModule
import com.valhalla.thor.di.installerModule
import com.valhalla.thor.di.preferenceModule
import com.valhalla.thor.di.presentationModule
import com.valhalla.thor.util.Logger
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androix.startup.KoinStartup
import org.koin.dsl.koinConfiguration

class ThorApplication : Application(), KoinStartup {

    override fun onKoinStartup() = koinConfiguration {
        androidContext(this@ThorApplication)
        androidLogger(Logger.koinLogLevel)
        modules(
            coreModule,
            installerModule,
            preferenceModule,
            commonModule,
            presentationModule
        )
    }

    override fun onCreate() {
        super.onCreate()
        ThorShellConfig.init()
    }

}
