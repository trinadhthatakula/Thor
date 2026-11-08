// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Consumer coverage for the shared Asgard API, including behavior Thor's selection guard hides. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class ConnectedButtonGroupMultiSelectTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun independentSelectionsCanReachEmptyWithoutMutatingTheInputSet() {
        val original = setOf(0, 1)
        val selection = mutableStateOf(original)
        val changes = mutableListOf<Set<Int>>()
        rule.setContent {
            MaterialTheme {
                ConnectedButtonGroup(
                    items = listOf(
                        ConnectedButtonGroupItem.Label("User"),
                        ConnectedButtonGroupItem.Label("System"),
                    ),
                    selectedIndices = selection.value,
                    onSelectionChanged = { changes += it; selection.value = it },
                )
            }
        }

        rule.onNodeWithText("User").assertIsOn().performClick().assertIsOff()
        rule.onNodeWithText("System").assertIsOn().performClick().assertIsOff()
        rule.onNodeWithText("User").performClick().assertIsOn()
        rule.onNodeWithText("System").performClick().assertIsOn()
        rule.runOnIdle {
            assertEquals(listOf(setOf(1), emptySet<Int>(), setOf(0), setOf(0, 1)), changes)
            assertEquals(setOf(0, 1), original)
        }
    }

    @Test
    fun disabledItemsRemainSelectedAndGroupDisablePreventsChanges() {
        val selection = mutableStateOf(setOf(-1, 1, 10))
        val enabled = mutableStateOf(true)
        val changes = mutableListOf<Set<Int>>()
        rule.setContent {
            MaterialTheme {
                ConnectedButtonGroup(
                    items = listOf(
                        ConnectedButtonGroupItem.Label("User"),
                        ConnectedButtonGroupItem.Label("System", enabled = false),
                    ),
                    selectedIndices = selection.value,
                    onSelectionChanged = { changes += it; selection.value = it },
                    enabled = enabled.value,
                )
            }
        }

        rule.onNodeWithText("System").assertIsOn().assertIsNotEnabled().performClick()
        rule.onNodeWithText("User").assertIsOff().performClick().assertIsOn()
        rule.runOnIdle {
            assertEquals(listOf(setOf(0, 1)), changes)
            enabled.value = false
        }
        rule.onNodeWithText("User").assertIsNotEnabled().performClick().assertIsOn()
        rule.runOnIdle { assertEquals(1, changes.size) }
    }
}
