// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import android.content.Context
import android.os.Build
import android.os.LocaleList
import org.koin.core.annotation.Single
import android.app.LocaleManager as AndroidLocaleManager

/**
 * The one place a language choice becomes an applied locale.
 *
 * Two mechanisms, split at API 33:
 *
 * - **33+** — `android.app.LocaleManager.setApplicationLocales`. The platform persists the per-app
 *   locale itself, merges it into every Context the app is handed, and relaunches the running
 *   activities. Nothing else is needed and nothing else is done here.
 * - **28–32** — [AppLocale], which records the tag and has each Thor component wrap its own base
 *   context in a `Configuration` carrying it. This replaces `AppCompatDelegate.setApplicationLocales`,
 *   which applies a locale only by recreating the AppCompat activities it tracks; Thor has none, so
 *   that call was a silent no-op and every shipped translation but English was unreachable below
 *   Android 13. See [AppLocale] for why the replacement is shaped the way it is.
 */
@Single
class LocaleManager(private val context: Context) {

    /**
     * Applies [languageCode] — a BCP-47 tag such as `fr`, or `null` for the system default.
     *
     * **This is the deliberate-choice path only**: the picker in Settings. A cold start must go
     * through [reconcileOnStartup] instead, which is the difference between "the user just asked
     * for this" and "this is what we had written down last time".
     *
     * The short-circuit is [isRedundantLanguageRequest], which compares on the **language subtag**
     * rather than the whole tag — except for the languages Thor ships twice, where the region
     * decides too, so that Português → Português (Brasil) is not mistaken for a no-op. Below 33,
     * re-recording an equal tag would fire [AppLocale.appliedTag] and recreate the visible activity
     * for no change. On 33+ it stops a `fr` request from flattening the `fr-FR` the system language
     * screen handed back, which is a real narrowing — but only that one. It does **not** protect a
     * *different* language chosen in that screen; a whole-tag comparison and a language-subtag
     * comparison both report `es` and `fr` as a disagreement, and this method resolves every
     * disagreement in the preference's favour because that is what its one caller now means.
     * [reconcileOnStartup] is where the other reading lives.
     *
     * A request for "System default" is deliberately *not* decided by that comparison — see
     * [isRedundantLanguageRequest] for why a bare subtag comparison would swallow it.
     */
    fun applyLocale(languageCode: String?) {
        val tag = languageCode?.trim()?.takeIf { it.isNotEmpty() }
        if (isRedundantLanguageRequest(requestedTag = tag, inEffectTag = appliedLanguageTag())) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val platform = context.getSystemService(Context.LOCALE_SERVICE) as AndroidLocaleManager
            platform.applicationLocales = if (tag == null) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(tag)
            }
        } else {
            AppLocale.record(context, tag)
        }
    }

    /**
     * The tag Thor's own surfaces are currently rendering with, or `null` for the system default.
     *
     * Truthful on both paths, which the version this replaced was not: its `< 33` branch returned
     * `AppCompatDelegate.getApplicationLocales()`, which echoes back the static that
     * `setApplicationLocales` had just stored — so it reported success for a call that changed
     * nothing. Here the `< 33` answer is [AppLocale.tagFor], and that value is the *input* to every
     * `attachBaseContext` wrap in the app; if it says `fr`, the contexts were built from `fr`.
     *
     * Used for the short-circuit above, not for the Settings row. The row reads the `Configuration`
     * it is being composed in (see `SettingsScreen`), because that is the only source that can
     * *disagree* with what Thor believes it applied — and being able to disagree is exactly what a
     * row that must not confirm an unapplied change needs.
     */
    fun appliedLanguageTag(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val platform = context.getSystemService(Context.LOCALE_SERVICE) as AndroidLocaleManager
            platform.applicationLocales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
        } else {
            AppLocale.tagFor(context)
        }

    /**
     * The cold-start path. Returns the tag Thor should now have persisted, which the caller stores
     * if it differs from what it read.
     *
     * A startup is not a choice, and treating it as one is what let every cold start overwrite the
     * platform's per-app locale on API 33+ — reverting a language the user set in Settings → Apps →
     * Thor → Language, a screen Thor never sees a callback from. [startupLocaleSync] holds the rule
     * and the argument for it; this method is only the two side effects it selects between.
     *
     * Runs on Main like [applyLocale], because below 33 [AppLocale.record] fires
     * [AppLocale.appliedTag] and the collector on the other end recreates the visible activity.
     */
    fun reconcileOnStartup(persistedTag: String?): String? {
        val persisted = persistedTag?.trim()?.takeIf { it.isNotEmpty() }
        val action = startupLocaleSync(
            persistedTag = persisted,
            inEffectTag = appliedLanguageTag(),
            // The same predicate, read the same way, as everything else that splits at 33.
            platformOwnsLocale = !AppLocale.overridesConfiguration,
        )
        return when (action) {
            LocaleSync.InAgreement -> persisted
            is LocaleSync.AdoptInEffect -> action.tag
            is LocaleSync.ApplyPreference -> {
                applyLocale(action.tag)
                persisted
            }
        }
    }
}
