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
import com.valhalla.thor.presentation.settings.BillingProcessor
import com.valhalla.thor.presentation.utils.AppIconFetcher
import com.valhalla.thor.presentation.utils.AppIconKeyer
import com.valhalla.thor.util.LocaleManager
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.koinLogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val freezerShortcutManager: com.valhalla.thor.data.launcher.FreezerShortcutManager by inject()

    // Retained, cancellable application-lifetime scope. A SupervisorJob keeps one failing
    // child from cancelling the others, and holding the reference lets us cancel it in
    // onTerminate so launched work doesn't outlive the process.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Keep the Lazy handle so we can tear the billing client down only if it was actually
    // created this run — resolving the delegate would otherwise spin up a billing connection at
    // shutdown, the opposite of what we want.
    private val billingProcessorLazy = inject<BillingProcessor>()
    private val billingProcessor by billingProcessorLazy

    override fun onCreate() {
        super.onCreate()
        com.valhalla.thor.extension.api.Logger.isDebug = BuildConfig.DEBUG

        startKoin<ThorApplication> {
            androidContext(this@ThorApplication)
            androidLogger(Logger.koinLogLevel)
        }

        Bypass.setLogger { message, throwable ->
            Logger.e("Bypass", message, throwable)
        }
        // Install the on-disk offset cache BEFORE the first bypass use (prepareThor below). This
        // lets the expensive core-oj dex scan be persisted on first launch and reloaded on every
        // later cold start, instead of re-running the mmap + dex parse each time.
        Bypass.init(this)
        Bypass.prepareThor()
        ThorShellConfig.init()

        try {
            Dhizuku.init(this)
        } catch (e: Exception) {
            Logger.e("ThorApp", "Dhizuku init failed", e)
        }

        autoFreezeManager.startObserving()

        appScope.launch {
            runCatching {
                val prefs = preferenceRepository.userPreferences.first()
                freezerShortcutManager.syncDynamicShortcuts(prefs.addFreezerToLauncher)
                withContext(Dispatchers.Main) {
                    localeManager.applyLocale(prefs.language)
                }
            }.onFailure { throwable ->
                Logger.e("ThorApp", "Startup preference sync failed", throwable)
            }
        }
    }

    override fun onTerminate() {
        // Tear down the app-lifetime billing client + coroutine scope so the Play billing
        // service binding and scope don't outlive the process. onTerminate is only guaranteed
        // on emulators, but it is the correct application-lifetime teardown hook.
        // Only close if the singleton was already created this run; touching the delegate
        // otherwise would initialize billing at shutdown.
        if (billingProcessorLazy.isInitialized()) {
            billingProcessor.close()
        }
        appScope.cancel()
        super.onTerminate()
    }
}
