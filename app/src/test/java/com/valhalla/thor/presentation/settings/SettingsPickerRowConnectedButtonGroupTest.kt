// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.thor.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class SettingsPickerRowConnectedButtonGroupTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun labeledPickerRendersAndForwardsSelectionChanges() {
        val selectedIndex = mutableIntStateOf(0)
        val selections = mutableListOf<Int>()

        rule.setContent {
            MaterialTheme {
                SettingsPickerRow(
                    icon = R.drawable.theme_panel,
                    title = "Theme",
                    subtitle = "Choose Thor's appearance",
                    items = listOf(
                        ConnectedButtonGroupItem.Label("Light"),
                        ConnectedButtonGroupItem.Label("Dark"),
                    ),
                    selectedIndex = selectedIndex.intValue,
                    onItemSelected = {
                        selections += it
                        selectedIndex.intValue = it
                    },
                )
            }
        }

        rule.onNodeWithText("Dark").assertIsDisplayed().performClick()
        rule.onNodeWithText("Light").assertIsDisplayed().performClick()

        rule.runOnIdle {
            assertEquals(listOf(1, 0), selections)
            assertEquals(0, selectedIndex.intValue)
        }
    }
}
