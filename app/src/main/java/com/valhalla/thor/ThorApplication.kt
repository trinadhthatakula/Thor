package com.valhalla.thor

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.rosan.dhizuku.api.Dhizuku
import com.valhalla.bypass.Bypass
import com.valhalla.thor.core.ThorShellConfig
import com.valhalla.thor.data.service.AutoFreezeManager
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.presentation.utils.AppIconFetcher
import com.valhalla.thor.presentation.utils.AppIconKeyer
import com.valhalla.thor.util.LocaleManager
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication
class ThorApplication : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(AppIconKeyer())
                add(AppIconFetcher.Factory(context))
            }
            .crossfade(true)
            .build()
    }

    private val preferenceRepository: PreferenceRepository by inject()
    private val localeManager: LocaleManager by inject()
    private val autoFreezeManager: AutoFreezeManager by inject()

    override fun onCreate() {
        super.onCreate()

        startKoin<ThorApplication> {
            androidContext(this@ThorApplication)
            androidLogger(Logger.koinLogLevel)
        }

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

        autoFreezeManager.startObserving()

        MainScope().launch {
            val prefs = preferenceRepository.userPreferences.first()
            withContext(Dispatchers.Main) {
                localeManager.applyLocale(prefs.language)
            }
        }
    }
}
