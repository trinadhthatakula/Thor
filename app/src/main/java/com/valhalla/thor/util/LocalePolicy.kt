// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import java.util.Locale

/**
 * The eight translations Thor ships, and the one entry that means "do not override anything".
 *
 * This list is the Kotlin-side twin of `res/xml/locales_config.xml`, which is what Android 13+
 * reads to populate its own per-app language screen (Settings → Apps → Thor → Language), and of
 * `translatedLocales` in `app/build.gradle.kts`, which is the set `localeFilters` keeps. All three
 * must agree: a tag here with no `values-<tag>` directory picks a locale with no translation behind
 * it, a `locales_config` entry missing here is a language the in-app picker cannot reach, and a
 * directory missing from `translatedLocales` is compiled and then filtered straight back out.
 *
 * [tag] is the *language* subtag alone **except where Thor ships more than one variant of a
 * language**, which today means Portuguese and only Portuguese.
 *
 * The region-less default is not an oversight to correct. `values-zh-rCN` carries a region and
 * plain `zh` still resolves to it: since API 24 `ResourcesImpl` resolves the requested locale
 * against the APK's locales with likely-subtag expansion (`zh` → `zh-Hans`, `zh-CN` →
 * `zh-Hans-CN`) and matches on script, so a region adds nothing and only invites a wrong one.
 *
 * Portuguese cannot use that spelling, because a region-less request has to *choose* between
 * `values-pt` and `values-pt-rBR` and the resolver's answer to "which one does `pt` mean" is not
 * Thor's to assume — CLDR's own answer is Brazil, while the directory a European user needs is the
 * region-less one. So both Portuguese entries name their region explicitly and neither relies on
 * the resolver breaking the tie. `pt-PT` selects `values-pt` (the resolver walks the requested
 * locale's parent chain, `pt-PT` → `pt`, and finds the region-less directory before the Brazilian
 * one); `pt-BR` selects `values-pt-rBR` on an exact region match. Neither is ambiguous, which is
 * the whole point.
 *
 * [Portuguese] precedes [PortugueseBrazil] deliberately: [languageForTag] falls back to a
 * language-only match in enum order, so a bare `pt` — from a system language screen, or a
 * Configuration that resolved to plain Portuguese — lands on European Portuguese, the same
 * directory such a request actually renders from.
 */
enum class AppLanguage(val tag: String?) {
    SystemDefault(null),
    English("en"),
    Chinese("zh"),
    French("fr"),
    Spanish("es"),
    Arabic("ar"),
    Portuguese("pt-PT"),
    PortugueseBrazil("pt-BR"),
    Polish("pl");

    /** The language subtag of [tag], `null` for [SystemDefault]. `he` normalises to `iw` here. */
    internal val languageSubtag: String? get() = tag?.let { Locale.forLanguageTag(it).language }

    /** The region subtag of [tag], empty for the entries that deliberately carry none. */
    internal val regionSubtag: String get() = tag?.let { Locale.forLanguageTag(it).country }.orEmpty()

    companion object {
        /** Picker order: "System default" first, then the shipped translations. */
        val PICKER_ORDER: List<AppLanguage> = entries.toList()

        /**
         * The languages Thor ships in more than one regional variant, so the ones where a region
         * subtag carries meaning rather than noise.
         *
         * Derived from [entries] rather than written down, because the whole failure this guards
         * against is a second variant being added to the enum and a hand-maintained list beside it
         * not being told.
         */
        internal val REGION_SENSITIVE: Set<String> =
            entries.mapNotNull { it.languageSubtag }
                .groupingBy { it }.eachCount()
                .filterValues { it > 1 }
                .keys
    }
}

/**
 * Which [AppLanguage] a BCP-47 tag names — **most specific match first, then language alone**.
 *
 * The tags this has to swallow do not all come from the picker. `LocaleManager.getApplicationLocales`
 * on API 33+ hands back whatever the platform recorded, and `Configuration.getLocales()[0]` hands
 * back a fully resolved locale — so `fr` goes in and `fr-FR`, or `zh-Hans-CN`, comes back out.
 * Matching on whole tags alone would call those "not French" and "not Chinese", which is why the
 * language-only pass exists and why it has to be the *fallback* rather than the only rule.
 *
 * It cannot be the only rule any more. `pt-PT` and `pt-BR` share a language subtag, so a
 * language-only match answers the same entry for both and a Brazilian user's Settings row would
 * name European Portuguese while the screen renders Brazilian. The exact-region pass fixes that
 * without disturbing anything else: no other shipped tag carries a region, so for every one of them
 * the first pass simply finds nothing and the second decides, exactly as before.
 *
 * An unrecognised language degrades to [AppLanguage.SystemDefault] rather than throwing. That is
 * the conservative direction: it can only ever under-claim, and the one thing the language row
 * must never do is claim a language that is not in effect.
 */
fun languageForTag(tag: String?): AppLanguage {
    val locale = localeForTag(tag) ?: return AppLanguage.SystemDefault
    return AppLanguage.entries.firstOrNull {
        it.languageSubtag == locale.language && it.regionSubtag == locale.country
    } ?: AppLanguage.entries.firstOrNull { it.languageSubtag == locale.language }
    ?: AppLanguage.SystemDefault
}

/**
 * Whether applying [requestedTag] while [inEffectTag] is already in force would change nothing, so
 * the caller can return without touching the platform.
 *
 * The comparison is on the **language subtag**, and on the region only for the languages Thor ships
 * twice ([AppLanguage.REGION_SENSITIVE], which today is Portuguese alone). The tag that comes back
 * is rarely the tag that went in: `fr` requested against the `fr-FR` the system language screen
 * recorded, or `zh` against a resolved `zh-Hans-CN`, is the same language, and re-applying it below
 * API 33 would fire [AppLocale.appliedTag] and recreate the visible activity for no change. Both
 * sides go through [localeForTag], so the legacy normalisation applies to each identically — `he`
 * against `iw` is one language, not two.
 *
 * Region-blindness stops being harmless the moment two directories share a language. `pt-PT`
 * against an in-effect `pt-BR` is one language and two translations, and calling that request
 * redundant is how the picker's Português (Brasil) row becomes a tap that does nothing. So for a
 * region-sensitive language the two sides are resolved through [languageForTag] and compared as
 * *entries* — which is safe precisely here and nowhere else, because both sides have already been
 * established to name a language Thor ships, so neither can be the degraded
 * [AppLanguage.SystemDefault] answer that the paragraph below is about.
 *
 * **Not [languageForTag] in the general case, which is the obvious spelling and the wrong one.**
 * That function degrades anything Thor does not ship to [AppLanguage.SystemDefault], which is the
 * right conservative answer for a row that must not claim a language it is not rendering — but it
 * gives the same answer for "the user asked for no override" and "a language with no translation
 * behind it is applied right now". Comparing those two answers reports agreement between a request
 * to *clear* the override and the override still being there, so the request that exists to undo it
 * returns having done nothing. The explicit `null` handling below keeps `null` distinct from every
 * language while still treating `de` against `de-AT` as the no-op it is.
 */
fun isRedundantLanguageRequest(requestedTag: String?, inEffectTag: String?): Boolean {
    val requested = localeForTag(requestedTag)
    val inEffect = localeForTag(inEffectTag)
    if (requested == null || inEffect == null) return requested == null && inEffect == null
    if (requested.language != inEffect.language) return false
    if (requested.language !in AppLanguage.REGION_SENSITIVE) return true
    return languageForTag(requested.toLanguageTag()) == languageForTag(inEffect.toLanguageTag())
}

/**
 * The [Locale] a persisted preference selects, or `null` for "leave the system locale alone".
 *
 * `null` is not a failure value to be substituted for — it is the instruction to wrap nothing, and
 * every caller has to honour it rather than fall back to [Locale.getDefault]. Substituting the
 * default would pin the app to whatever locale the device happened to have the first time the user
 * opened Settings, which is a different bug wearing the same shape as the one this file exists to
 * fix.
 *
 * A blank or unparseable tag also yields `null`: `Locale.forLanguageTag("")` returns
 * [Locale.ROOT], whose `language` is the empty string, and a Configuration carrying ROOT resolves
 * to the default `values/` directory in a way that looks deliberate but never was.
 */
fun localeForTag(tag: String?): Locale? {
    val trimmed = tag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val locale = Locale.forLanguageTag(trimmed)
    return locale.takeIf { it.language.isNotEmpty() }
}

/**
 * Languages written right-to-left, by ISO 639 code as [Locale.getLanguage] reports it.
 *
 * `he` and `yi` are listed under both their modern and their legacy codes because
 * [Locale.getLanguage] still normalises them to the 1989 forms (`iw` and `ji`) — a `Locale`
 * built from the tag `he` answers `iw`, so a set holding only `he` would miss it. Indonesian
 * carries the same legacy normalisation (`id` → `in`) and is deliberately **not** here: it is
 * written left-to-right, so neither of its codes belongs in this set.
 */
private val RTL_LANGUAGES = setOf(
    "ar", "ckb", "dv", "fa", "he", "iw", "ji", "ps", "sd", "ug", "ur", "yi"
)

/**
 * Whether [locale] lays out right-to-left.
 *
 * Duplicates the answer `TextUtils.getLayoutDirectionFromLocale` derives from ICU's
 * `ULocale.getCharacterOrientation` script data, in a form a JVM test can assert without an
 * Android runtime. It does not have to be right for every locale on earth — only for the tags in
 * [AppLanguage], which `LocalePolicyTest` pins — but the extra entries are cheap and stop a future
 * `values-fa` from shipping silently LTR.
 *
 * Kept as a value the wrapper sets explicitly rather than left to `Configuration.setLocales`, which
 * does call `setLayoutDirection(locale)` for the first locale: relying on that side effect makes
 * RTL a property nobody can see in the code and nobody can test off-device. See
 * [com.valhalla.thor.util.AppLocale.wrap].
 */
fun isRtl(locale: Locale): Boolean =
    locale.language.lowercase(Locale.ROOT) in RTL_LANGUAGES

/**
 * What the Settings language row is allowed to say, given what the user *chose* and what the UI is
 * actually *being drawn in*.
 *
 * This is the finding written as a function. Before it, the row read `prefs.language` alone, so on
 * API 28–32 — where the old `AppCompatDelegate.setApplicationLocales` call changed nothing, there
 * being no AppCompat activity for it to recreate — picking Français persisted `fr`, rendered
 * English, and displayed "Français". The row confirmed a change that had not happened.
 *
 * The rule:
 * - [persistedTag] `null` means the user asked to follow the system. There is no override to fail,
 *   so the row says "System default" and never names the device's own locale back at them.
 * - Otherwise the row shows [appliedTag], which callers pass from the rendering `Configuration`,
 *   not from anything Thor believes it applied. If the override did not take, the two disagree and
 *   the row sides with the pixels.
 */
fun displayedLanguage(persistedTag: String?, appliedTag: String?): AppLanguage =
    if (persistedTag == null) AppLanguage.SystemDefault else languageForTag(appliedTag)

/**
 * What a cold start must do when Thor's stored preference and the locale actually in force
 * disagree.
 *
 * There are two stores and only one of them is Thor's. Below API 33 the locale in force is
 * whatever `AppLocale`'s mirror says, and that mirror is written *only* by Thor — so a
 * disagreement can only mean the mirror is behind the preference (a cloud restore, a cleared
 * app-data), and the preference must be pushed out. On API 33+ the locale in force is
 * `android.app.LocaleManager.getApplicationLocales()`, which the user can change **from outside
 * Thor** in Settings → Apps → Thor → Language. There, a disagreement usually means the user just
 * used that screen, and the only safe reading is that the platform is right.
 */
sealed interface LocaleSync {

    /** The two name the same language. Touch neither. */
    data object InAgreement : LocaleSync

    /** Push Thor's stored preference outwards, into whatever applies a locale on this API level. */
    data class ApplyPreference(val tag: String?) : LocaleSync

    /** Take what is already in force and store it as Thor's preference. */
    data class AdoptInEffect(val tag: String?) : LocaleSync
}

/**
 * Reconciles the two stores at process start.
 *
 * ### The bug this exists to stop
 *
 * `ThorApplication.onCreate` calls into `LocaleManager` on every cold start. On API 33+ that call
 * used to be an unconditional `setApplicationLocales(persistedTag)`, which **overwrites** the
 * platform's per-app locale — so a user who set Thor to French in *system* Settings, having never
 * touched Thor's own picker, had that choice reverted to "system default" the next time Thor
 * started, silently and forever. Comparing on the language subtag first (rather than on the whole
 * tag) narrowed that to "the two name different languages", which is exactly the case where the
 * user did something deliberate in the system UI — the comparison stopped `fr` from flattening
 * `fr-FR`, and left the actual overwrite untouched.
 *
 * ### The rule
 *
 * [platformOwnsLocale] is true on API 33+, where the platform both persists the per-app locale and
 * offers the user a screen to change it. There Thor's stored preference is a *cache* of a decision
 * the platform owns, so it loses every disagreement: [AdoptInEffect] writes the platform's answer
 * back into the preference, which also keeps the Settings row honest — the row reads
 * [displayedLanguage], and a `null` preference makes it say "System default" no matter what the
 * screen is actually rendering in.
 *
 * Below 33 there is no such screen and no platform store; the preference is the only decision
 * anyone made, so it is pushed out with [ApplyPreference].
 *
 * ### What "platform wins" costs, and why it is still right
 *
 * On 33+ a device restored from a cloud backup could in principle arrive with Thor's DataStore
 * preference restored and the platform's per-app locale not, in which case adopting would discard
 * the language. It does not, because Android 13 backs the per-app locale up itself — the same
 * `LocaleManager` that owns it also owns its restore, including the delayed restore for an app
 * that is installed after the backup lands. The preference is not the only copy on that path, and
 * treating it as authoritative would trade a rare restore glitch for a reproducible one-tap bug.
 */
fun startupLocaleSync(
    persistedTag: String?,
    inEffectTag: String?,
    platformOwnsLocale: Boolean,
): LocaleSync {
    val persisted = persistedTag?.trim()?.takeIf { it.isNotEmpty() }
    if (languageForTag(persisted) == languageForTag(inEffectTag)) return LocaleSync.InAgreement
    return if (platformOwnsLocale) {
        LocaleSync.AdoptInEffect(inEffectTag)
    } else {
        LocaleSync.ApplyPreference(persisted)
    }
}

/**
 * The [Locale] a user-visible date or number must be formatted with.
 *
 * The scoped answer to a problem `Locale.setDefault` would have solved globally and badly. On API
 * 33+ the platform already merges the per-app locale into the process default, so
 * [systemDefault] — `Locale.getDefault()` — *is* the app language and there is nothing to do.
 * Below 33 nothing sets it, which is how "the screen is French but the file size reads `1.5 GB`
 * instead of `1,5 Go`" happens: the sentence comes from `values-fr` and the number comes from the
 * device locale, in the same line of text.
 *
 * Callers that hold a `Context` should prefer `AppLocale.localeOf(context)`, which reads the locale
 * off the `Configuration` the caller is actually resolving resources with, and so cannot disagree
 * with the strings beside it. This overload is for the ones that deliberately hold no Context —
 * `MainViewModel` formats byte counts into `UiText` arguments and has none by design.
 *
 * [appliedTag] `null` means "nothing is overridden", which includes every API 33+ caller, and the
 * answer is then [systemDefault] rather than any substitute: see [localeForTag] for why `null` has
 * to survive as `null` here.
 */
fun formattingLocale(appliedTag: String?, systemDefault: Locale): Locale =
    localeForTag(appliedTag) ?: systemDefault
