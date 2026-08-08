// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.valhalla.thor.data.repository.PreferenceRepositoryImpl.Keys
import com.valhalla.thor.data.repository.PreferenceRepositoryImpl.LocalKeys
import com.valhalla.thor.domain.model.DefaultTab
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `Preferences` -> `UserPreferences` mapping, and specifically **which file each value is
 * allowed to come out of**.
 *
 * Thor keeps two Preferences stores because exactly one of them is in the Auto Backup allowlist.
 * Settings restore; facts about the un-backed-up database must not, or they arrive describing state
 * that did not come with them. That distinction is invisible at the call site — both sides are just
 * a `Preferences` — so it is pinned here.
 */
class ToUserPreferencesTest {

    /**
     * The bug this split exists for.
     *
     * `has_shown_disabled_apps_prompt` means "we already offered to import the frozen apps we
     * found", which is a claim about the freezer watchlist. The watchlist lives in the Room
     * database and is deliberately excluded from backup; the settings file is not. Reinstall Thor
     * onto the same device — where `pm disable` survived the uninstall, so apps really are still
     * frozen — and the restored flag switched off the one prompt built to rebuild the watchlist,
     * with nothing said. A value in the settings snapshot must therefore never reach this field,
     * whether it got there by restore or by predating the move.
     */
    @Test
    fun `a restored prompt flag in the settings file is not read`() {
        val restored = preferencesOf(Keys.LEGACY_DISABLED_APPS_PROMPT to true)

        assertFalse(
            "the settings file is the backed-up one — its copy is exactly the stale value",
            restored.toUserPreferences().hasShownDisabledAppsPrompt
        )
        assertFalse(
            "and an empty local store does not change that",
            restored.toUserPreferences(emptyPreferences()).hasShownDisabledAppsPrompt
        )
    }

    @Test
    fun `the prompt flag is read from the per-install store`() {
        val local = preferencesOf(LocalKeys.HAS_SHOWN_DISABLED_APPS_PROMPT to true)

        assertTrue(emptyPreferences().toUserPreferences(local).hasShownDisabledAppsPrompt)
    }

    /**
     * The two stores use the *same* key name, since only the file changed. That makes a
     * copy-paste between them silent rather than a compile error, so the fact that each side reads
     * only its own file is worth stating both ways round.
     */
    @Test
    fun `neither store can answer for the other`() {
        val settingsOnly = preferencesOf(Keys.LEGACY_DISABLED_APPS_PROMPT to true)
        val localOnly = preferencesOf(LocalKeys.HAS_SHOWN_DISABLED_APPS_PROMPT to true)

        assertFalse(settingsOnly.toUserPreferences(emptyPreferences()).hasShownDisabledAppsPrompt)
        assertTrue(settingsOnly.toUserPreferences(localOnly).hasShownDisabledAppsPrompt)
    }

    /** Everything else still comes from the settings file, and an absent local store is not fatal. */
    @Test
    fun `settings are unaffected by the split`() {
        val settings = preferencesOf(
            Keys.SORT_BY to SortBy.SIZE.name,
            Keys.SORT_ORDER to SortOrder.DESCENDING.name,
            Keys.FILTER_TYPE to "STATE",
            Keys.THEME_MODE to ThemeMode.DARK.name,
            Keys.BIOMETRIC_LOCK to true,
            Keys.HAS_SHOWN_SUPPORT_DEVELOPER_PROMPT to true
        )

        val prefs = settings.toUserPreferences()

        assertEquals(SortBy.SIZE, prefs.appSortBy)
        assertEquals(SortOrder.DESCENDING, prefs.appSortOrder)
        assertEquals(FilterType.State, prefs.appFilterType)
        assertEquals(ThemeMode.DARK, prefs.themeMode)
        assertTrue(prefs.biometricLockEnabled)
        // The other "have we asked?" flag stays put on purpose: it describes the *user*, not the
        // database, so restoring it onto a new install is the correct behaviour.
        assertTrue(prefs.hasShownSupportDeveloperPrompt)
    }

    /** An install that has written nothing yet must read as the documented defaults, not crash. */
    @Test
    fun `an empty pair of stores gives the defaults`() {
        val prefs = emptyPreferences().toUserPreferences(emptyPreferences())

        assertEquals(SortBy.NAME, prefs.appSortBy)
        assertEquals(SortOrder.ASCENDING, prefs.appSortOrder)
        assertEquals(FilterType.Source, prefs.appFilterType)
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode)
        assertEquals(DefaultTab.HOME, prefs.defaultTab)
        assertFalse(prefs.hasShownDisabledAppsPrompt)
        assertFalse(prefs.biometricLockEnabled)
    }

    /** Every entry round-trips, so a rename of one is caught here rather than on a user's device. */
    @Test
    fun `each default tab survives the write-read round trip`() {
        for (tab in DefaultTab.entries) {
            val settings = preferencesOf(Keys.DEFAULT_TAB to tab.name)

            assertEquals(tab, settings.toUserPreferences().defaultTab)
        }
    }

    /**
     * Downgrade safety, and worth more here than for the other enums in this file.
     *
     * This value is read once, before the first frame, and decides which screen the app opens on: a
     * settings file written by a newer Thor — or restored from one by Auto Backup — must degrade to
     * Home rather than throw, because a throw on this path is a launch crash, not a wrong-looking
     * setting.
     */
    @Test
    fun `an unknown default tab degrades to Home`() {
        val fromTheFuture = preferencesOf(Keys.DEFAULT_TAB to "EXTENSIONS")

        assertEquals(DefaultTab.HOME, fromTheFuture.toUserPreferences().defaultTab)
    }
}
