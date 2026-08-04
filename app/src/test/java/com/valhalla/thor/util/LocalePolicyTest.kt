// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import com.valhalla.thor.domain.model.BackupIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale

/**
 * The decisions behind the in-app language picker, asserted off-device.
 *
 * None of the machinery that *applies* a locale is reachable from a JVM test — `AppLocale.wrap`
 * needs a real `Context`, a `Configuration` and a `SharedPreferences` file, `LocalizedResources`
 * needs a `Resources`, and there is no Robolectric here — so every judgement those classes make is
 * lifted into `LocalePolicy.kt` and asserted directly: which language a tag names, which `Locale` a
 * preference selects, whether that locale is right-to-left, what the Settings row is allowed to
 * say, which of the two stores wins at cold start, and which locale a date or byte count is
 * formatted with.
 *
 * What that leaves uncovered, stated plainly rather than implied: that `getResources()` on
 * `ThorApplication` and `FreezerTileService` actually re-resolves after `AppLocale.record`, that
 * `createConfigurationContext` produces the locale asked for, and that a `BroadcastReceiver`
 * context is the Application's *base* rather than the wrapped Application. All three are framework
 * behaviour and need an instrumented test or a device.
 */
class LocalePolicyTest {

    /**
     * The finding, as an assertion.
     *
     * On API 28–32 the old fallback called `AppCompatDelegate.setApplicationLocales`, which applies
     * a locale by recreating the AppCompat activities it tracks. Thor has none, so the call stored
     * a static and changed nothing — yet the row read `prefs.language` and displayed Français over
     * an English screen. Any implementation of [displayedLanguage] that lets this pass has
     * reintroduced the bug.
     */
    @Test
    fun rowShowsTheRenderedLanguage_notThePreference_whenTheOverrideDidNotTake() {
        assertEquals(
            AppLanguage.English,
            displayedLanguage(persistedTag = "fr", appliedTag = "en-US")
        )
    }

    /** The same inputs when the override *did* take: the row is then free to say Français. */
    @Test
    fun rowShowsFrench_whenTheConfigurationIsActuallyFrench() {
        assertEquals(
            AppLanguage.French,
            displayedLanguage(persistedTag = "fr", appliedTag = "fr")
        )
    }

    /**
     * A user who chose "System default" is told "System default", never the device's own locale.
     *
     * Without the `persistedTag == null` branch the row would read the rendering configuration
     * literally and announce "Français" to a French-phone user who had explicitly asked Thor to
     * follow the system — technically true, and a change to a row they never touched.
     */
    @Test
    fun systemDefaultStaysSystemDefault_evenOnALocalisedDevice() {
        assertEquals(
            AppLanguage.SystemDefault,
            displayedLanguage(persistedTag = null, appliedTag = "fr-FR")
        )
        assertEquals(
            AppLanguage.SystemDefault,
            displayedLanguage(persistedTag = null, appliedTag = "ar-EG")
        )
    }

    /**
     * Tags are matched on the language subtag alone.
     *
     * `LocaleManager.getApplicationLocales` on API 33+ and `Configuration.getLocales()[0]` both
     * hand back resolved locales, so what goes in as `fr` comes back as `fr-FR` and `zh` comes back
     * as `zh-Hans-CN`. Whole-tag equality would call those "not French" and "not Chinese" and drop
     * the row to "System default" on exactly the devices where the feature is working.
     */
    @Test
    fun regionAndScriptSubtagsDoNotDefeatTheMatch() {
        assertEquals(AppLanguage.French, languageForTag("fr-FR"))
        assertEquals(AppLanguage.Chinese, languageForTag("zh-Hans-CN"))
        assertEquals(AppLanguage.Chinese, languageForTag("zh-CN"))
        assertEquals(AppLanguage.Spanish, languageForTag("es-419"))
        assertEquals(AppLanguage.Arabic, languageForTag("ar-EG"))
        assertEquals(AppLanguage.English, languageForTag("en-GB"))
    }

    /** Nothing, blank, or a language Thor does not ship degrades to "System default", never throws. */
    @Test
    fun unknownAndEmptyTagsDegradeToSystemDefault() {
        assertEquals(AppLanguage.SystemDefault, languageForTag(null))
        assertEquals(AppLanguage.SystemDefault, languageForTag(""))
        assertEquals(AppLanguage.SystemDefault, languageForTag("   "))
        assertEquals(AppLanguage.SystemDefault, languageForTag("de"))
        assertEquals(AppLanguage.SystemDefault, languageForTag("not-a-tag-at-all"))
    }

    /**
     * `null` means "wrap nothing", and must survive as `null` rather than becoming
     * [Locale.getDefault].
     *
     * `AppLocale.wrap` returns the base context untouched on `null`. Substituting the default here
     * would instead pin the app to whatever the device's locale happened to be, permanently, which
     * looks like the feature working right up until the user changes their system language.
     */
    @Test
    fun systemDefaultSelectsNoLocale() {
        assertNull(localeForTag(null))
        assertNull(localeForTag(""))
        assertNull(localeForTag("   "))
    }

    /** `Locale.forLanguageTag("")` returns ROOT, whose language is empty — that is not a selection. */
    @Test
    fun anUnparseableTagSelectsNoLocale_ratherThanRoot() {
        assertNull(localeForTag("!!"))
        assertEquals(Locale.ROOT.language, Locale.forLanguageTag("!!").language)
    }

    /** Every shipped tag parses to a locale carrying the language subtag it was written with. */
    @Test
    fun everyShippedTagParses() {
        for (language in AppLanguage.entries) {
            val tag = language.tag ?: continue
            val locale = localeForTag(tag)
            assertEquals("$language should parse", tag, locale?.language)
            assertEquals("$language should round-trip", language, languageForTag(tag))
        }
    }

    /**
     * Arabic is right-to-left and the other four are not.
     *
     * `AppLocale.wrap` writes `Configuration.screenLayout`'s `SCREENLAYOUT_LAYOUTDIR_*` bits from
     * [isRtl], the same two bits `Configuration.setLayoutDirection` derives from ICU via
     * `TextUtils.getLayoutDirectionFromLocale`. A wrong answer here does not fail to translate — it
     * translates and then lays Arabic out left-to-right.
     *
     * The map is keyed by the enum and read with `getValue`, so adding a language to [AppLanguage]
     * without deciding its direction fails this test with `NoSuchElementException` instead of
     * shipping silently LTR.
     */
    @Test
    fun onlyArabicIsRightToLeft() {
        val expected = mapOf(
            AppLanguage.SystemDefault to false,
            AppLanguage.English to false,
            AppLanguage.Chinese to false,
            AppLanguage.French to false,
            AppLanguage.Spanish to false,
            AppLanguage.Arabic to true
        )
        for (language in AppLanguage.entries) {
            val locale = localeForTag(language.tag) ?: continue
            assertEquals("$language layout direction", expected.getValue(language), isRtl(locale))
        }
        assertTrue(isRtl(Locale.forLanguageTag("ar")))
        assertFalse(isRtl(Locale.forLanguageTag("en")))
    }

    /**
     * The legacy ISO 639 codes are covered too.
     *
     * [Locale.getLanguage] still normalises `he` to `iw` and `yi` to `ji`, so a set holding only the
     * modern spellings would report Hebrew as left-to-right. Nothing ships those today; this pins
     * the trap for whoever adds `values-he`.
     */
    @Test
    fun rtlDetectionSurvivesTheLegacyLanguageCodes() {
        assertTrue(isRtl(Locale.forLanguageTag("he")))
        assertTrue(isRtl(Locale.forLanguageTag("iw")))
        assertTrue(isRtl(Locale.forLanguageTag("fa")))
        assertTrue(isRtl(Locale.forLanguageTag("ur")))
    }

    /**
     * The picker offers "System default" first, then every shipped translation exactly once.
     *
     * The row and the sheet are now both driven off this list, so a language present in one and
     * absent from the other is no longer expressible — this asserts the list itself is whole.
     */
    @Test
    fun pickerOffersSystemDefaultFirstAndEveryShippedLanguageOnce() {
        assertEquals(AppLanguage.SystemDefault, AppLanguage.PICKER_ORDER.first())
        assertEquals(AppLanguage.entries.size, AppLanguage.PICKER_ORDER.size)
        assertEquals(
            AppLanguage.PICKER_ORDER.size,
            AppLanguage.PICKER_ORDER.distinct().size
        )
        assertEquals(
            listOf(null, "en", "zh", "fr", "es", "ar"),
            AppLanguage.PICKER_ORDER.map { it.tag }
        )
    }

    /**
     * Picking a language and having it applied leaves the row saying the same thing it said before
     * the user opened the sheet — no round-trip through a region-qualified tag changes the answer.
     */
    @Test
    fun everyShippedLanguageRoundTripsThroughTheRow() {
        for (language in AppLanguage.entries) {
            val tag = language.tag ?: continue
            val applied = localeForTag(tag)?.toLanguageTag()
            assertEquals(language, displayedLanguage(persistedTag = tag, appliedTag = applied))
        }
    }

    // --- startupLocaleSync -------------------------------------------------------------------

    /**
     * The finding: a cold start used to overwrite the platform's per-app locale.
     *
     * On API 33+ the user can set Thor's language in **system** Settings → Apps → Thor → Language,
     * a screen Thor gets no callback from and never writes its own preference for. Thor then
     * started, read a `null` preference, and pushed `LocaleList.getEmptyLocaleList()` into the
     * platform — reverting that choice on every single launch. Any rule that answers
     * [LocaleSync.ApplyPreference] here has reintroduced it.
     */
    @Test
    fun aLanguageSetInSystemSettingsSurvivesTheNextColdStart() {
        assertEquals(
            LocaleSync.AdoptInEffect("fr-FR"),
            startupLocaleSync(
                persistedTag = null,
                inEffectTag = "fr-FR",
                platformOwnsLocale = true,
            )
        )
    }

    /**
     * The same when Thor's own preference names a *different* language, not merely no language.
     *
     * This is the case a whole-tag-versus-language-subtag comparison cannot tell apart from the
     * one above, and the reason the fix could not be another comparison tweak: `es` and `fr`
     * disagree under either comparison, so the only question left is which store wins.
     */
    @Test
    fun theSystemScreenBeatsAStalePreference_notJustAnAbsentOne() {
        assertEquals(
            LocaleSync.AdoptInEffect("fr"),
            startupLocaleSync(
                persistedTag = "es",
                inEffectTag = "fr",
                platformOwnsLocale = true,
            )
        )
    }

    /**
     * Below API 33 the preference is the only decision anyone made, so it is pushed outwards.
     *
     * `AppLocale`'s mirror is written *only* by Thor, so a disagreement there cannot mean "the user
     * changed this somewhere else" — it means the mirror is behind, which is exactly what a cloud
     * restore leaves (the DataStore preference is in Thor's backup allowlist; the mirror file is
     * not).
     */
    @Test
    fun below33ARestoredPreferenceIsPushedOutRatherThanDiscarded() {
        assertEquals(
            LocaleSync.ApplyPreference("fr"),
            startupLocaleSync(
                persistedTag = "fr",
                inEffectTag = null,
                platformOwnsLocale = false,
            )
        )
    }

    /**
     * And the reverse below 33: a preference of "System default" over a mirror still holding `fr`
     * clears the override rather than adopting it. Adoption would silently convert a stale mirror
     * into a preference the user never expressed.
     */
    @Test
    fun below33SystemDefaultClearsAStaleMirror() {
        assertEquals(
            LocaleSync.ApplyPreference(null),
            startupLocaleSync(
                persistedTag = null,
                inEffectTag = "fr",
                platformOwnsLocale = false,
            )
        )
    }

    /**
     * Region and script subtags are not a disagreement.
     *
     * `fr` goes into the platform and `fr-FR` comes back out; `zh` comes back as `zh-Hans-CN`.
     * Treating that as a disagreement would make every cold start either re-apply the preference
     * (below 33: a pointless activity recreate on every launch) or adopt the resolved tag (33+: the
     * preference silently narrowing from `fr` to `fr-FR`, which is the flattening the short-circuit
     * in `LocaleManager.applyLocale` already exists to prevent).
     */
    @Test
    fun aResolvedTagIsNotADisagreement() {
        for (platformOwnsLocale in listOf(true, false)) {
            assertEquals(
                LocaleSync.InAgreement,
                startupLocaleSync("fr", "fr-FR", platformOwnsLocale)
            )
            assertEquals(
                LocaleSync.InAgreement,
                startupLocaleSync("zh", "zh-Hans-CN", platformOwnsLocale)
            )
            assertEquals(
                LocaleSync.InAgreement,
                startupLocaleSync(null, null, platformOwnsLocale)
            )
        }
    }

    /** A blank preference is "System default", not a language, on either side of 33. */
    @Test
    fun aBlankPreferenceIsTreatedAsSystemDefault() {
        assertEquals(LocaleSync.InAgreement, startupLocaleSync("   ", null, true))
        assertEquals(LocaleSync.InAgreement, startupLocaleSync("", null, false))
        assertEquals(LocaleSync.ApplyPreference(null), startupLocaleSync("  ", "fr", false))
    }

    /**
     * The rule stated as a property, over every pairing of the tags Thor can hold.
     *
     * Below 33 nothing may ever be adopted — there is no second store to adopt *from* — and on 33+
     * nothing may ever be pushed out, which is the whole finding. A future refactor that gets one
     * disagreement right and another wrong fails here rather than on one device.
     */
    @Test
    fun theStoreThatWinsDependsOnlyOnWhoOwnsTheLocale() {
        val tags = listOf(null, "en", "fr", "fr-FR", "zh", "ar", "de")
        for (persisted in tags) {
            for (inEffect in tags) {
                val below33 = startupLocaleSync(persisted, inEffect, platformOwnsLocale = false)
                assertFalse(
                    "below 33, $persisted/$inEffect must not adopt",
                    below33 is LocaleSync.AdoptInEffect
                )
                val from33 = startupLocaleSync(persisted, inEffect, platformOwnsLocale = true)
                assertFalse(
                    "on 33+, $persisted/$inEffect must not overwrite the platform",
                    from33 is LocaleSync.ApplyPreference
                )
            }
        }
    }

    // --- formattingLocale --------------------------------------------------------------------

    /**
     * A date or a byte count follows the picked language, not the device's.
     *
     * Below API 33 nothing makes the process default follow the in-app picker, so a screen drawn
     * from `values-fr` used to carry `1.5 GB` and an English `Aug 4, 2026` inside it. The scoped
     * answer is this function, deliberately rather than `Locale.setDefault` — see
     * `AppLocale`'s KDoc for why a process-global mutation was the wrong tool for a
     * resources-shaped problem.
     */
    @Test
    fun formattingFollowsThePickedLanguage() {
        val device = Locale.forLanguageTag("en-GB")
        assertEquals(Locale.forLanguageTag("fr"), formattingLocale("fr", device))
        assertEquals(Locale.forLanguageTag("ar"), formattingLocale("ar", device))
    }

    /**
     * "System default" and API 33+ are the same input — nothing is overridden — and both answer
     * with the device locale rather than with any substitute.
     */
    @Test
    fun formattingFallsBackToTheDeviceWhenNothingIsOverridden() {
        val device = Locale.forLanguageTag("en-GB")
        assertEquals(device, formattingLocale(null, device))
        assertEquals(device, formattingLocale("", device))
        assertEquals(device, formattingLocale("   ", device))
        assertEquals(device, formattingLocale("!!", device))
    }

    // --- the rationale that was not true -----------------------------------------------------

    /**
     * `AppLocale` used to justify not calling `Locale.setDefault` by claiming it would put
     * Arabic-Indic digits into backup file names. It would not, and this is the measurement.
     *
     * `DateTimeFormatter.ofPattern(p)` is assembled by `DateTimeFormatterBuilder.toFormatter`,
     * which hard-codes [DecimalStyle.STANDARD]; the locale it captures selects month and era
     * *text*, never the digits. The claim was plausible — CLDR really does give `ar` the
     * Arabic-Indic zero — but that `DecimalStyle` is unreachable without an explicit
     * `withDecimalStyle`, and nothing in Thor calls it.
     *
     * This is pinned rather than deleted because the *conclusion* (do not call `Locale.setDefault`)
     * is still right for other reasons, and a future reader is entitled to know which of the two
     * arguments for it actually holds.
     */
    @Test
    fun backupFileStampsAreAsciiUnderAnArabicDefault() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))

            val name = BackupIndex.fileNameFor(1_754_300_000_000L)
            assertTrue("expected ASCII, got $name", name.all { it.code < 128 })
            assertTrue(name.startsWith(BackupIndex.FILE_NAME_PREFIX))
            assertTrue(name.endsWith(BackupIndex.FILE_NAME_SUFFIX))

            // Why: the pattern formatter never consults a locale-derived DecimalStyle...
            assertEquals(
                '0',
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").decimalStyle.zeroDigit
            )
            // ...even though the locale-derived one really is Arabic-Indic.
            assertEquals('٠', DecimalStyle.of(Locale.forLanguageTag("ar")).zeroDigit)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
