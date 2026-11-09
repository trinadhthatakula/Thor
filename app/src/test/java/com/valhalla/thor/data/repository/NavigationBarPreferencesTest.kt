// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.valhalla.thor.data.repository.PreferenceRepositoryImpl.Keys
import com.valhalla.thor.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class NavigationBarPreferencesTest {
    @Test fun existingInstallsUseDefaultNavigation() {
        assertEquals(NavigationBarPreferences(), emptyPreferences().toUserPreferences().navigationBar)
    }

    @Test fun customizedNavigationSurvivesStorageRoundTripAlongsideOtherSettings() {
        val style = NavigationBarPreferences(
            placement = NavigationPlacement.FLOATING, appearance = NavigationAppearance.SEGMENTED,
            labels = NavigationLabels.SELECTED, containerShape = NavigationShape.SOFT,
            indicatorShape = NavigationShape.ROUND, spacing = 12, floatingMargin = 24,
            elevation = 12, contentPadding = 16,
        )
        val prefs = preferencesOf(Keys.NAVIGATION_BAR to NavigationPreferencesCodec.encode(style), Keys.DEFAULT_TAB to DefaultTab.FREEZER.name).toUserPreferences()
        assertEquals(style, prefs.navigationBar)
        assertEquals(DefaultTab.FREEZER, prefs.defaultTab)
    }

    /** Invalid JSON, incompatible shapes and unknown enum values must restore safe defaults. */
    @Test fun corruptedAndUnknownEnumSnapshotsCannotCrashStartup() {
        for (raw in listOf("not JSON", "[]", "{\"appearance\":\"future\"}", "{\"spacing\":null}")) {
            assertEquals(NavigationBarPreferences(), NavigationPreferencesCodec.decode(raw))
        }
    }

    @Test fun futureFieldsAndPartialSavedStylesRetainKnownChoices() {
        val prefs = NavigationPreferencesCodec.decode("{\"placement\":\"FLOATING\",\"futureOption\":true}")
        assertEquals(NavigationPlacement.FLOATING, prefs.placement)
        assertEquals(NavigationAppearance.EXPANDING, prefs.appearance)
    }

    @Test fun importedOutOfBoundsSizesAreClampedBeforeRendering() {
        val prefs = NavigationPreferencesCodec.decode("{\"spacing\":-10,\"elevation\":999,\"contentPadding\":999,\"floatingMargin\":-1}")
        assertEquals(0, prefs.spacing)
        assertEquals(16, prefs.elevation)
        assertEquals(24, prefs.contentPadding)
        assertEquals(0, prefs.floatingMargin)
    }

    @Test fun textOnlyNavigationAlwaysKeepsDestinationNamesVisible() {
        val prefs = NavigationPreferencesCodec.decode("{\"showIcons\":false,\"labels\":\"HIDDEN\"}")
        assertFalse(prefs.showIcons)
        assertEquals(NavigationLabels.HIDDEN, prefs.labels)
        assertEquals(NavigationLabels.ALWAYS, prefs.effectiveLabels)
    }

    /** Every stored label policy must survive hiding icons, saving and restoring icon visibility. */
    @Test fun savingTextOnlyNavigationPreservesEveryLabelChoiceForWhenIconsReturn() {
        for (labels in NavigationLabels.entries) {
            val original = NavigationBarPreferences(labels = labels)
            val saved = NavigationPreferencesCodec.decode(
                NavigationPreferencesCodec.encode(original.copy(showIcons = false).normalized()),
            )
            assertFalse(saved.showIcons)
            assertEquals(labels, saved.labels)
            assertEquals(NavigationLabels.ALWAYS, saved.effectiveLabels)
            val restored = saved.copy(showIcons = true).normalized()
            assertEquals(original, restored)
            assertEquals(labels, restored.effectiveLabels)
        }
    }
}
