package com.valhalla.thor

import android.app.Application
import com.valhalla.thor.di.appGrabber
import com.valhalla.thor.di.commonModule
import com.valhalla.thor.di.shizukuModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androix.startup.KoinStartup
import org.koin.dsl.koinConfiguration

class ThorApplication : Application(), KoinStartup {

    override fun onKoinStartup() = koinConfiguration {
        androidContext(this@ThorApplication)
        androidLogger()
        modules(
            appGrabber,
            shizukuModule,
            commonModule
        )
    }

}
