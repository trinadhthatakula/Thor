// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "AppLocale"

/** The mirror file. One key, written only by [AppLocale.record]. */
private const val MIRROR_FILE = "thor_applied_locale"
private const val MIRROR_KEY = "language_tag"

/**
 * Applies Thor's language choice on API 28–32 by wrapping every base context Thor owns.
 *
 * ### Why this exists
 *
 * On API 33+ the platform does this itself: `android.app.LocaleManager.setApplicationLocales`
 * records a per-app locale in `system/locale_settings.xml`, and `ActivityThread` merges it into the
 * configuration of every Context the app is handed, before any Thor code runs. Below 33 there is no
 * such thing. The previous fallback called `AppCompatDelegate.setApplicationLocales`, which applies
 * a locale by recreating the AppCompat activities it tracks and wrapping their base context —
 * `AppCompatDelegateImpl.attachBaseContext2`. Thor has no AppCompat activity for it to track
 * (`HomeActivity`, `PortableInstallerActivity` and `FreezerTileService` are plain framework
 * components; `FreezerLaunchActivity` extends `android.app.Activity`), so that call stored a static
 * `LocaleListCompat` and changed nothing observable. Four of the five shipped translations were
 * unreachable on Android 9 through 12L.
 *
 * ### The synchronous-read problem, and what it costs
 *
 * `attachBaseContext` runs on the main thread before `onCreate` and cannot suspend, but the
 * language preference lives in DataStore behind a `Flow`. Three ways out, and why this is the one:
 *
 * - **`runBlocking { userPreferences.first() }`** puts a proto-file open, read and parse on the
 *   activity-launch path, for every activity and service creation, on the main thread. Rejected:
 *   Thor already measured cold-start contention on this path (`docs/follow-ups/privilege-manager-cold-start.md`).
 * - **Read DataStore asynchronously in `ThorApplication.onCreate` and recreate afterwards.** The
 *   first activity attaches before that read lands, so every cold start with a non-default language
 *   renders a frame in the wrong language and then visibly recreates. Rejected on flicker.
 * - **This: a one-key `SharedPreferences` mirror, written in the same call that applies the
 *   locale.** [record] is the only writer and it is called from `LocaleManager.applyLocale`, so the
 *   mirror cannot say a locale is applied that was not; it is a cache of *what we did*, not a
 *   second copy of the user's preference. DataStore remains the source of truth for the preference.
 *
 * The cost is one `SharedPreferences` load of a file with at most one key, taken once per process
 * on the first [wrap] — which is `ThorApplication.attachBaseContext`, the first Context Thor gets.
 * Every later call is a volatile field read. On API 33+ [overridesConfiguration] is false and the
 * file is never opened at all, so devices on the majority API levels pay nothing.
 *
 * **First launch after install:** the mirror is absent, [read] returns `null`, nothing is wrapped —
 * which is correct, because a user who has never opened Settings has no language preference either.
 * "Mirror empty" and "no language chosen" coincide by construction.
 *
 * **First launch after a cloud restore** is the one case where they diverge. Thor's backup ruleset
 * is an allowlist naming exactly `datastore/thor_preferences.preferences_pb` (see
 * `PreferenceRepositoryImpl`), so the DataStore preference travels to a new device and this mirror
 * does not. That first cold start renders in the system locale; then `ThorApplication.onCreate`
 * calls `applyLocale(prefs.language)` as it always has, [record] writes the mirror, and the visible
 * activity is recreated in the right language. One flicker, once, and it self-heals — the
 * alternative was adding a `SharedPreferences` file to a backup allowlist whose whole design is
 * that it names one path.
 *
 * ### What is deliberately *not* done
 *
 * `Locale.setDefault` / `LocaleList.setDefault` are not called, and the reason is **not** the one
 * this comment used to give. It claimed `BackupIndex.fileStamp` would start emitting Arabic-Indic
 * digits into backup file names under an `ar` process default. That is false and was measured
 * false: `DateTimeFormatter.ofPattern(p)` is built by `DateTimeFormatterBuilder.toFormatter`, which
 * hard-codes `DecimalStyle.STANDARD` — the locale it captures selects month and era *text*, never
 * the digits. `DecimalStyle.of(Locale("ar")).zeroDigit` is indeed `U+0660`, but that object is
 * never reached unless a caller passes it to `withDecimalStyle`, and none does. The file names were
 * always ASCII. `LocalePolicyTest.backupFileStampsAreAsciiUnderAnArabicDefault` pins it.
 *
 * The real reasons are ordinary ones. `Locale.setDefault` is a **process-global** mutation: it
 * reaches every library in the process, `%d`/`%f` in any `String.format` that omits a locale (both
 * of which *are* localised — `"%02X"` is not, so the signature-digest paths in `ExtensionTrust` and
 * `StoreRepositoryImpl` are unaffected either way), and code Thor does not own. And it is not
 * needed: what the picker is *for* is resources, `createConfigurationContext` covers those, and the
 * two places that formatted through the process default — `MainViewModel.formatBytes` and
 * `AppInfoDetailsScreen.formatTime` — now take an explicit locale from [formattingLocale] and
 * [localeOf]. A scoped argument beats a global mutation for the same result.
 */
object AppLocale {

    /**
     * True only where Thor has to override the configuration itself.
     *
     * The `< TIRAMISU` gate is not merely an optimisation. On API 33+ the user can change Thor's
     * language from **system** Settings → Apps → Thor → Language, which updates the platform's
     * per-app locale and never touches this mirror. Wrapping on 33+ would therefore let a stale
     * mirror value silently override a choice the user made in the system UI. Below 33 that screen
     * does not exist, so the mirror is the only source and cannot be contradicted.
     */
    val overridesConfiguration: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    private val _appliedTag = MutableStateFlow<String?>(null)

    /**
     * The tag currently being applied by [wrap], for activities that must recreate when it changes.
     *
     * Only meaningful when [overridesConfiguration] is true. On API 33+ the platform recreates
     * activities itself when `setApplicationLocales` is called, so nothing collects this.
     */
    val appliedTag: StateFlow<String?> = _appliedTag.asStateFlow()

    @Volatile
    private var cachedTag: String? = null

    @Volatile
    private var loaded = false

    /** The tag [wrap] would use for [context] — `null` on API 33+ and when nothing is overridden. */
    fun tagFor(context: Context): String? = if (overridesConfiguration) read(context) else null

    /**
     * The base context a Thor component should attach, carrying the chosen locale and its layout
     * direction.
     *
     * `Configuration(base.resources.configuration)` copies the *whole* incoming configuration and
     * changes only the locale, rather than starting from a blank `Configuration`: everything else
     * on it — density, screen size, night mode, `screenLayout` size bits — is what the base context
     * was already resolving with, and a blank one would drop all of it.
     *
     * The layout-direction write is what makes `values-ar` render right-to-left rather than merely
     * translated. `Configuration.setLocales` already calls `setLayoutDirection(locale)` for the
     * first locale, which routes through `TextUtils.getLayoutDirectionFromLocale` and ICU; this
     * writes the same two `screenLayout` bits from [isRtl] immediately afterwards so the property
     * is stated in code a reviewer can see and a JVM test can assert, instead of riding on a side
     * effect of a setter named for something else.
     *
     * Copying the whole configuration has one consequence worth knowing, because
     * [LocalizedResources] is built on it. `createConfigurationContext` takes a *delta*:
     * `ResourcesManager.generateConfig` starts from the process configuration and calls
     * `Configuration.updateFrom(override)`, which applies every field the override has set. A full
     * copy sets them all, so the returned context pins density, night mode and font scale at the
     * values they had when it was created, and a later system configuration change does not move
     * them. That is the right trade anyway — a *blank* `Configuration` is worse, not better,
     * because `Configuration()` calls `setToDefaults()` and `setToDefaults()` sets `fontScale = 1`,
     * which `updateFrom` then applies, silently resetting a user's display font size. Callers that
     * outlive a configuration change re-create the wrap; see [LocalizedResources.invalidate].
     *
     * Failure returns [base] unwrapped. This runs inside `ThorApplication.attachBaseContext`,
     * before `Logger` is even configured — a throw here is an app that cannot start at all, and the
     * fallback is exactly the pre-fix behaviour rather than a crash loop.
     */
    fun wrap(base: Context): Context {
        if (!overridesConfiguration) return base
        val locale = localeForTag(read(base)) ?: return base
        return runCatching {
            val configuration = Configuration(base.resources.configuration)
            configuration.setLocales(LocaleList(locale))
            configuration.screenLayout =
                (configuration.screenLayout and Configuration.SCREENLAYOUT_LAYOUTDIR_MASK.inv()) or
                    if (isRtl(locale)) Configuration.SCREENLAYOUT_LAYOUTDIR_RTL
                    else Configuration.SCREENLAYOUT_LAYOUTDIR_LTR
            base.createConfigurationContext(configuration)
        }.getOrDefault(base)
    }

    /**
     * The locale [context] is *actually* resolving its resources with.
     *
     * Read off the `Configuration` rather than off the mirror, so it cannot disagree with the
     * strings the same context is producing beside it — which is the whole reason the Settings row
     * reads `LocalConfiguration` too. This is also what `android.text.format.Formatter`'s own
     * `formatShortFileSize` does with the Context it is handed, so a date formatted through this
     * and a size formatted through the platform helper agree by construction.
     */
    fun localeOf(context: Context): Locale =
        context.resources.configuration.locales
            .takeIf { !it.isEmpty }
            ?.get(0)
            ?: Locale.getDefault()

    /**
     * The same answer for a caller that deliberately holds no `Context`.
     *
     * On API 33+ the platform merges the per-app locale into the process default, so
     * `Locale.getDefault()` is already the app language and [overridesConfiguration] short-circuits
     * to it. Below 33 nothing does that, so the applied tag is consulted. See [formattingLocale].
     */
    fun formattingLocale(): Locale =
        if (overridesConfiguration) {
            formattingLocale(_appliedTag.value, Locale.getDefault())
        } else {
            Locale.getDefault()
        }

    /**
     * Records the tag [wrap] must use from now on, and wakes anything watching [appliedTag].
     *
     * The in-memory cache is updated under the same lock that guards [read]'s one-shot load, and
     * *before* the disk write, so a recreate triggered by [appliedTag] on the very next main-loop
     * turn attaches with the new value even though the `apply()` has not reached disk yet.
     *
     * `apply()` rather than `commit()`: this runs on the main thread (`SettingsViewModel.setLanguage`
     * is a `viewModelScope` launch, and `ThorApplication` hops to Main for it), where `commit()` is
     * a disk write on the UI thread and lint's `ApplySharedPref` says so. Durability across a
     * process death in the window between the two is handled by `QueuedWork`, which the framework
     * drains at the next `Activity.onPause` / service lifecycle transition.
     */
    fun record(context: Context, tag: String?) {
        synchronized(this) {
            cachedTag = tag
            loaded = true
        }
        runCatching {
            context.getSharedPreferences(MIRROR_FILE, Context.MODE_PRIVATE).edit {
                if (tag == null) remove(MIRROR_KEY) else putString(MIRROR_KEY, tag)
            }
        }.onFailure { Logger.e(TAG, "Could not persist the applied locale mirror", it) }
        _appliedTag.value = tag
    }

    /**
     * Recreates [activity] when the applied tag stops matching the one it attached with.
     *
     * Both Compose entry points call this with the value [tagFor] returned during their own
     * `attachBaseContext`, so the comparison is against what this instance is *actually rendering*,
     * not against a guess. After `recreate()` the new instance attaches with the new tag, the two
     * agree, and nothing recreates again — the loop terminates on its own.
     *
     * A no-op on API 33+, where `LocaleManager.setApplicationLocales` makes the platform relaunch
     * the activity for us; collecting here as well would race that relaunch.
     */
    fun recreateOnChange(activity: ComponentActivity, attachedTag: String?) {
        if (!overridesConfiguration) return
        activity.lifecycleScope.launch {
            appliedTag.collect { tag ->
                if (tag != attachedTag) activity.recreate()
            }
        }
    }

    /**
     * The mirror, loaded at most once per process.
     *
     * Double-checked against the `@Volatile loaded` flag because [wrap] is called from every
     * component attach, including ones the system may bring up concurrently (a `TileService` bind
     * racing an activity launch), and the file is worth opening exactly once.
     */
    private fun read(context: Context): String? {
        if (loaded) return cachedTag
        return synchronized(this) {
            if (loaded) return@synchronized cachedTag
            val tag = runCatching {
                context.getSharedPreferences(MIRROR_FILE, Context.MODE_PRIVATE)
                    .getString(MIRROR_KEY, null)
            }.getOrNull()
            cachedTag = tag
            loaded = true
            _appliedTag.value = tag
            tag
        }
    }
}

/**
 * `Resources` that keep following the applied language, for a component that can only attach a
 * base context **once**.
 *
 * ### Why this is needed at all
 *
 * `ContextWrapper.attachBaseContext` throws `IllegalStateException("Base context already set")` on
 * a second call, so the wrap an `Application` or a `Service` performs at process start is the only
 * one it will ever get. [AppLocale.record] updates the mirror, the cache and
 * [AppLocale.appliedTag] — it cannot reach back into a context that is already attached. An
 * Activity does not care, because a language change recreates it and the new instance attaches
 * afresh (`AppLocale.recreateOnChange`); an Application has no such event.
 *
 * That gap was user-visible. Koin's `androidContext()` binds the `Application`, so every `@Single`
 * taking a bare `Context` — `ShizukuSystemGateway` and `DhizukuSystemGateway` (their
 * `freeze_system_app_requires_root` refusals, surfaced by `FreezerViewModel` as a toast),
 * `AppBundleFileStoreImpl` (export destination names), `BulkResultNotifier`,
 * `FreezerShortcutManager` — resolved strings through a context frozen at the locale the process
 * started in, for the rest of that process's life. An English sentence toasted over a French
 * screen. On API 33+ this never happened: `ActivityThread.handleConfigurationChanged` pushes the
 * new per-app configuration into the Application's own `ResourcesImpl`. Below 33 nothing does, and
 * that is exactly the range this file exists for.
 *
 * ### Shape
 *
 * The owner overrides `getResources()` to return [current]. `Context.getString`, `getText`,
 * `getDrawable` and `getColor` are `final` methods on `Context` that all route through
 * `getResources()`, and `ContextWrapper` does not override them — so one override moves every
 * string the component resolves, with no consumer changing anything. The base handed to the
 * constructor must be the **raw** context the framework passed to `attachBaseContext`, not
 * `getBaseContext()`: re-wrapping an already-wrapped context compounds the configuration pinning
 * described on [AppLocale.wrap], while the raw one is the `ContextImpl` the framework keeps
 * rebased on the current system configuration.
 *
 * [invalidate] exists because of that same pinning: the wrap freezes density, night mode and font
 * scale alongside the locale, so a component that sees `onConfigurationChanged` calls this and the
 * next [current] rebuilds from a base the framework has already updated.
 *
 * On API 33+ this is inert by construction — [AppLocale.appliedTag] is never written there and
 * [AppLocale.wrap] returns its argument, so [current] hands back the base's own `Resources`, which
 * is what `getResources()` would have returned anyway.
 */
class LocalizedResources(private val base: Context) {

    @Volatile
    private var cached: Resources? = null

    @Volatile
    private var cachedTag: String? = null

    /** `Resources` carrying the tag that is in force *now*, rebuilding only when it changes. */
    fun current(): Resources {
        val wanted = AppLocale.appliedTag.value
        cached?.let { if (cachedTag == wanted) return it }
        return rebuild(wanted)
    }

    /** Drop the cache; call from `onConfigurationChanged`. The next [current] rebuilds. */
    fun invalidate() {
        synchronized(this) { cached = null }
    }

    /**
     * Falls back to the base's own `Resources` rather than propagating, for the same reason
     * [AppLocale.wrap] swallows: this can be reached from `getResources()` during startup, where a
     * throw is an app that cannot draw a frame. Untranslated beats dead.
     */
    private fun rebuild(wanted: String?): Resources = synchronized(this) {
        cached?.let { if (cachedTag == wanted) return it }
        val built = runCatching { AppLocale.wrap(base).resources }.getOrElse { base.resources }
        cached = built
        cachedTag = wanted
        built
    }
}
