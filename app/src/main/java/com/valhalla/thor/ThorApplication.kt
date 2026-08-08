// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.rosan.dhizuku.api.Dhizuku
import com.valhalla.bypass.Bypass
import com.valhalla.thor.core.ThorShellConfig
import com.valhalla.thor.data.service.AutoFreezeManager
import com.valhalla.thor.data.source.local.dhizuku.DhizukuHelper
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.presentation.settings.BillingProcessor
import com.valhalla.thor.presentation.utils.AppIconFetcher
import com.valhalla.thor.presentation.utils.AppIconKeyer
import com.valhalla.thor.util.AppLocale
import com.valhalla.thor.util.LocaleManager
import com.valhalla.thor.util.LocaleRevision
import com.valhalla.thor.util.LocalizedResources
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.koinLogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
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

    /**
     * The application context's locale, at process start **and afterwards**.
     *
     * [attachBaseContext] is the only wrap this object will ever get —
     * `ContextWrapper.attachBaseContext` throws `IllegalStateException("Base context already set")`
     * on a second call — so it alone freezes the application context at whatever the mirror said
     * when the process started. [getResources] is what makes it track a later change; see
     * [LocalizedResources] for why one `getResources()` override moves every `getString` in the
     * process.
     *
     * ### What tracks a runtime language change, and what does not
     *
     * **Tracks it, immediately.** Everything that resolves a string through the Koin
     * `androidContext()` — which is this object — reads [getResources] at the moment it resolves,
     * so it is correct from the next call onwards: `ShizukuSystemGateway` and `DhizukuSystemGateway`
     * (`freeze_system_app_requires_root` and `freeze_system_app_removal_failed`, toasted by
     * `FreezerViewModel`), `AppBundleFileStoreImpl` (export destination names), the suspended-app
     * dialog strings in `Shizuku.kt` / `Dhizuku.kt`, `FreezerShortcutManager.disableAppShortcut`,
     * and `BulkResultNotifier`'s notification title.
     *
     * **Tracks it at the next publish, not at the change.** `BulkResultNotifier`'s *channel* name:
     * `ensureChannel` runs on every `post()` and `createNotificationChannel` updates the name of an
     * existing channel, so it is corrected the next time a bulk run reports — not the moment the
     * user picks a language.
     *
     * **Only tracks it because something re-publishes.** The Freeze-all / Unfreeze-all *dynamic*
     * shortcut labels are a copy held by the launcher, and `getResources()` cannot reach a copy. It
     * is [onCreate]'s `appliedTag` collector that re-runs `syncDynamicShortcuts`, and only for the
     * dynamic pair. A bulk shortcut the user has **pinned** keeps its old label until they pin it
     * again: `ShortcutManager` rate-limits background updates and re-pushing every pinned id on a
     * language change would spend that budget on cosmetics.
     *
     * **Never tracks it, on any API level.** The QS tile's manifest `android:label`, read by
     * SystemUI out of Thor's APK with SystemUI's own resources — see `FreezerTileService`.
     *
     * A no-op above API 32, where `ActivityThread` merges the platform's per-app locale into this
     * context's own configuration and there is nothing left to do — see
     * [AppLocale.overridesConfiguration].
     */
    override fun attachBaseContext(base: Context) {
        // The RAW base, deliberately, not getBaseContext() afterwards: LocalizedResources must
        // rebuild from the ContextImpl the framework keeps current, not from a wrap of a wrap.
        localizedResources = LocalizedResources(base)
        systemLocaleTag = firstLocaleTag(base.resources.configuration)
        super.attachBaseContext(AppLocale.wrap(base))
    }

    /** Rebuilt on a language change; see [attachBaseContext]. Never read before it is assigned. */
    @Volatile
    private var localizedResources: LocalizedResources? = null

    /**
     * The **system** locale as last seen, which is not necessarily the one Thor renders in.
     *
     * Seeded from the raw base in [attachBaseContext] so that the first [onConfigurationChanged] —
     * which may well be a night-mode or font-scale change — is not mistaken for a language change.
     */
    @Volatile
    private var systemLocaleTag: String? = null

    override fun getResources(): Resources =
        localizedResources?.current() ?: super.getResources()

    /**
     * Two jobs, both of them consequences of holding a context the framework cannot re-attach.
     *
     * `AppLocale.wrap` pins the whole configuration and not only the locale (see its KDoc), so the
     * cached `Resources` must be dropped whenever the system's configuration moves. Cheap: this
     * fires on device-language, night-mode, density and font-scale changes, all of them user-driven,
     * and the rebuild is one `createConfigurationContext`.
     *
     * The locale comparison then covers the language changes that [AppLocale.appliedTag] cannot see:
     * a change made in *system* Settings → Apps → Thor → Language on API 33+, and a device-wide
     * language change for a user who left Thor on "System default" on any API level. Neither writes
     * Thor's mirror, and a device-wide language change since API 24 recreates activities without
     * restarting the process — so without this, the launcher labels would keep the old language
     * until the next cold start.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        localizedResources?.invalidate()
        val tag = firstLocaleTag(newConfig)
        if (tag != systemLocaleTag) {
            systemLocaleTag = tag
            republishLocalisedLabels()
        }
    }

    private fun firstLocaleTag(configuration: Configuration): String? =
        configuration.locales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()

    /**
     * Re-resolves the localised strings that have already been **copied** somewhere [getResources]
     * cannot reach.
     *
     * [getResources] fixes every string Thor resolves *on demand*. A copy is a different problem,
     * and there are two kinds:
     *
     * - **In this process.** The app-label cache in Room holds a label per row, resolved at scan
     *   time. [LocaleRevision] is how the repository hears about the change; see its KDoc for the
     *   one half of the problem it deliberately leaves to `ACTION_LOCALE_CHANGED`.
     * - **In someone else's process.** A launcher shortcut label is a copy `ShortcutManager` handed
     *   the launcher at publish time. Only the dynamic Freeze-all / Unfreeze-all pair is re-pushed:
     *   `syncDynamicShortcuts` is idempotent and takes the enabled flag from the preference, so this
     *   publishes nothing a user has turned off. Pinned per-app shortcuts are deliberately left —
     *   re-pushing every pinned id on a language change spends `ShortcutManager`'s background update
     *   budget on cosmetics.
     *
     * Both call sites below are language changes this process can actually observe, which is why
     * this is the only place that emits on [LocaleRevision].
     */
    private fun republishLocalisedLabels() {
        LocaleRevision.bump()
        appScope.launch {
            runCatching {
                val prefs = preferenceRepository.userPreferences.first()
                freezerShortcutManager.syncDynamicShortcuts(prefs.addFreezerToLauncher)
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                Logger.e("ThorApp", "Shortcut label refresh failed", throwable)
            }
        }
    }

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
        // Logger gates every level on this flag, `e` included, so a build with it false emits no
        // Thor logcat at all. PRIVILEGE_TRACE is OR-ed in because the benchmark build type is
        // release-shaped (DEBUG == false) and would otherwise take its startup timings and print
        // none of them. See docs/follow-ups/release-builds-emit-no-thor-logcat.md.
        com.valhalla.thor.extension.api.Logger.isDebug =
            BuildConfig.DEBUG || BuildConfig.PRIVILEGE_TRACE

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

        // Hand the outcome to DhizukuHelper rather than dropping it. This init runs once, at process
        // start — which for a first-run user is *before* they have authorised Thor in Dhizuku, so it
        // returns false and there is nothing here to retry it. Recording the answer is what lets the
        // later probe tell "already bound, just ask about permission" apart from "never bound, bind
        // now" instead of asking an unbound client whether it has permission and always hearing no.
        try {
            DhizukuHelper.markClientInitialised(Dhizuku.init(this))
        } catch (e: Exception) {
            Logger.e("ThorApp", "Dhizuku init failed", e)
            DhizukuHelper.markClientInitialised(false)
        }

        autoFreezeManager.startObserving()

        appScope.launch {
            runCatching {
                val prefs = preferenceRepository.userPreferences.first()
                // Reconcile BEFORE publishing shortcuts, not after: on the one start where the two
                // stores disagree — a cloud restore, where the DataStore preference travels and
                // AppLocale's mirror does not — this is what makes the labels below resolve in the
                // restored language instead of the device's.
                val reconciled = withContext(Dispatchers.Main) {
                    localeManager.reconcileOnStartup(prefs.language)
                }
                if (reconciled != prefs.language) {
                    // Only when the platform's per-app locale won (API 33+). Writing it back is what
                    // stops the Settings row saying "System default" over a screen the system
                    // language picker put in French.
                    //
                    // Logged rather than surfaced, unlike the two ViewModel callers: nobody asked
                    // for this write. It reconciles two stores behind the user's back during
                    // startup, so there is no action of theirs to report on and no screen to report
                    // it to. The cost of it failing is the mismatched Settings row above, which the
                    // next start attempts to fix again.
                    if (!preferenceRepository.setLanguage(reconciled)) {
                        Logger.w(
                            "ThorApp",
                            "Locale reconciled to $reconciled but the preference write was dropped"
                        )
                    }
                }
                freezerShortcutManager.syncDynamicShortcuts(prefs.addFreezerToLauncher)
            }.onFailure { throwable ->
                // runCatching also catches CancellationException; rethrow it so appScope.cancel()
                // (onTerminate) isn't logged as a failure and cooperative cancellation is preserved.
                if (throwable is CancellationException) throw throwable
                Logger.e("ThorApp", "Startup preference sync failed", throwable)
            }
        }

        // Below API 33 nothing else notices a language change: the platform has no per-app locale to
        // broadcast, so no configuration change is dispatched and onConfigurationChanged above never
        // fires for it. AppLocale.appliedTag is the only signal. Inert on 33+, where that flow is
        // never written and the configuration path covers the same ground.
        appScope.launch {
            AppLocale.appliedTag.drop(1).collect { republishLocalisedLabels() }
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
