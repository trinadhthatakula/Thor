// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.presentation.settings.customization.NavigationCustomizationScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationCustomizationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var open by mutableStateOf(true)
    private var applied: NavigationBarPreferences? = null

    private fun label(id: Int) = rule.activity.getString(id)
    private fun option(group: Int, value: Int): SemanticsNodeInteraction {
        rule.onNodeWithContentDescription(label(group)).performScrollTo().assertIsDisplayed()
        return rule.onNodeWithText(label(value)).performScrollTo().assertIsDisplayed()
    }
    /** Mounts the editor with a recorded draft submission and a configurable persistence outcome. */
    private fun show(preferences: NavigationBarPreferences = NavigationBarPreferences(), saveSucceeds: Boolean = true) {
        rule.setContent {
            MaterialTheme {
                if (open) NavigationCustomizationScreen(preferences, { open = false }, {
                    applied = it
                    saveSucceeds
                })
            }
        }
    }

    @Test fun previewChangesDoNotSaveUntilApply() {
        show()
        option(R.string.navigation_placement, R.string.navigation_floating).performClick()
        option(R.string.navigation_appearance, R.string.navigation_segmented).performClick()
        rule.onNodeWithContentDescription(label(R.string.freezer_desc)).performClick().assertIsSelected()
        rule.runOnIdle { assertNull(applied) }
        rule.onNodeWithText(label(R.string.navigation_apply)).assertIsDisplayed().performClick()
        rule.waitUntil { !open }
        rule.runOnIdle {
            assertEquals(NavigationPlacement.FLOATING, applied?.placement)
            assertEquals(NavigationAppearance.SEGMENTED, applied?.appearance)
        }
    }

    @Test fun cancelDiscardsDraft() {
        show()
        option(R.string.navigation_placement, R.string.navigation_floating).performClick()
        rule.onNodeWithText(label(R.string.cancel)).performClick()
        rule.runOnIdle { assertNull(applied); open = true }
        option(R.string.navigation_placement, R.string.navigation_docked).assertIsOn()
    }

    @Test fun backArrowDiscardsDraft() {
        show()
        option(R.string.navigation_placement, R.string.navigation_floating).performClick()
        rule.onNodeWithContentDescription(label(R.string.cd_back)).performClick()
        rule.runOnIdle { assertFalse(open); assertNull(applied) }
    }

    @Test fun systemBackDiscardsDraft() {
        show()
        option(R.string.navigation_placement, R.string.navigation_floating).performClick()
        pressBack()
        rule.runOnIdle { assertFalse(open); assertNull(applied) }
    }

    @Test fun resetIsAlsoAPreviewUntilApply() {
        show(NavigationBarPreferences(placement = NavigationPlacement.FLOATING, appearance = NavigationAppearance.STANDARD))
        rule.onNodeWithText(label(R.string.navigation_reset)).performClick()
        option(R.string.navigation_placement, R.string.navigation_docked).assertIsOn()
        rule.runOnIdle { assertNull(applied) }
        rule.onNodeWithText(label(R.string.navigation_apply)).performClick()
        rule.waitUntil { !open }
        rule.runOnIdle { assertEquals(NavigationBarPreferences(), applied) }
    }

    @Test fun failedSaveKeepsTheDraftAvailableForRetry() {
        show(saveSucceeds = false)
        option(R.string.navigation_placement, R.string.navigation_floating).performClick()
        rule.onNodeWithText(label(R.string.navigation_apply)).performClick()
        rule.onNodeWithText(label(R.string.navigation_save_failed)).assertIsDisplayed()
        option(R.string.navigation_placement, R.string.navigation_floating).assertIsOn()
        rule.runOnIdle { assertTrue(open) }
    }

    @Test fun draftAndPreviewSelectionSurviveStateRestoration() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                NavigationCustomizationScreen(NavigationBarPreferences(), {}, { applied = it; true })
            }
        }
        option(R.string.navigation_placement, R.string.navigation_floating).performClick()
        rule.onNodeWithContentDescription(label(R.string.freezer_desc)).performClick()
        restoration.emulateSavedInstanceStateRestore()
        option(R.string.navigation_placement, R.string.navigation_floating).assertIsOn()
        rule.onNodeWithContentDescription(label(R.string.freezer_desc)).assertIsSelected()
        rule.runOnIdle { assertNull(applied) }
    }

    @Test fun togglingIconsRestoresTheOriginalAutomaticLabelMode() {
        show()
        option(R.string.navigation_labels, R.string.navigation_automatic).assertIsOn()
        rule.onNodeWithText(label(R.string.navigation_icons)).performScrollTo().assertIsOn().performClick()
        option(R.string.navigation_labels, R.string.navigation_all).assertIsOn().assertIsNotEnabled()
        rule.onNodeWithText(label(R.string.navigation_icons)).performScrollTo().assertIsOff().performClick()
        option(R.string.navigation_labels, R.string.navigation_automatic).assertIsOn()
        rule.runOnIdle { assertNull(applied) }
        rule.onNodeWithText(label(R.string.navigation_apply)).performClick()
        rule.waitUntil { !open }
        rule.runOnIdle { assertEquals(NavigationBarPreferences(), applied) }
    }

    @Test fun hiddenIconsDoNotLoseSelectedOnlyLabelsAcrossStateRestoration() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                NavigationCustomizationScreen(NavigationBarPreferences(labels = NavigationLabels.SELECTED), {}, { applied = it; false })
            }
        }
        rule.onNodeWithText(label(R.string.navigation_icons)).performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText(label(R.string.navigation_icons)).performScrollTo().assertIsOff()
        option(R.string.navigation_labels, R.string.navigation_all).assertIsOn().assertIsNotEnabled()
        rule.onNodeWithText(label(R.string.navigation_icons)).performScrollTo().performClick()
        option(R.string.navigation_labels, R.string.navigation_selected).assertIsOn()
        rule.onNodeWithText(label(R.string.navigation_apply)).performClick()
        rule.onNodeWithText(label(R.string.navigation_save_failed)).assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(NavigationLabels.SELECTED, applied?.labels)
            assertEquals(true, applied?.showIcons)
        }
    }
}
