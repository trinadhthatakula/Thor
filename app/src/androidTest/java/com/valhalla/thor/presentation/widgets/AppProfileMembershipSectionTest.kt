// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.FreezeProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppProfileMembershipSectionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun namesCountAndManagementStatusFollowCurrentMembership() {
        var profiles by mutableStateOf(listOf(profile(1, "Games")))
        var inFreezer by mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                AppProfileMembershipSection(profiles, inFreezer)
            }
        }
        rule.onNodeWithText(str(R.string.app_info_profiles_count, 1)).assertExists()
        rule.onNodeWithText(str(R.string.app_info_profiles_only)).assertExists()
        rule.onNodeWithText("Games").assert(hasClickAction().not())

        rule.runOnIdle {
            profiles = listOf(profile(1, "Renamed games"), profile(2, "Travel"))
            inFreezer = true
        }
        rule.onNodeWithText("Games").assertDoesNotExist()
        rule.onNodeWithText("Renamed games").assertExists()
        rule.onNodeWithText("Travel").assertExists()
        rule.onNodeWithText(str(R.string.app_info_profiles_count, 2)).assertExists()
        rule.onNodeWithText(str(R.string.app_info_freezer_and_profiles)).assertExists()
        rule.onNodeWithText(str(R.string.app_info_profiles_only)).assertDoesNotExist()
    }

    @Test
    fun noSectionRemainsAfterTheLastMembershipIsRemoved() {
        var profiles by mutableStateOf(listOf(profile(1, "Games")))
        rule.setContent {
            MaterialTheme { AppProfileMembershipSection(profiles, isInFreezer = true) }
        }
        rule.onNodeWithText("Games").assertExists()

        rule.runOnIdle { profiles = emptyList() }

        rule.onNodeWithText("Games").assertDoesNotExist()
        rule.onNodeWithText(str(R.string.app_info_profiles_count, 1)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.app_info_profiles_count, 0)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.app_info_freezer_and_profiles)).assertDoesNotExist()
    }

    @Test
    fun longProfileNamesWrapWithoutTruncationOrAnInteractiveAction() {
        val longName = "A long profile name that remains readable"
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(180.dp)) {
                    AppProfileMembershipSection(listOf(profile(1, longName)), isInFreezer = false)
                }
            }
        }
        val layout = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(longName)
            .assert(hasClickAction().not())
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        rule.runOnIdle {
            assertTrue(layout.single().lineCount > 1)
            assertFalse(layout.single().hasVisualOverflow)
        }
    }

    private fun profile(id: Long, name: String) =
        FreezeProfile(id, name, listOf("com.example.app"))

    private fun str(id: Int, vararg args: Any) = rule.activity.getString(id, *args)
}
