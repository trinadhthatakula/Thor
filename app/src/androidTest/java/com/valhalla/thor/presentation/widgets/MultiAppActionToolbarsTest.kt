// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.MultiAppActionId
import com.valhalla.thor.domain.model.MultiAppActionId.*
import com.valhalla.thor.presentation.freezer.FreezerSelectToolBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiAppActionToolbarsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val selected = listOf(
        AppInfo(appName = "First", packageName = "test.first"),
        AppInfo(appName = "Second", packageName = "test.second", enabled = false, isSuspended = true),
    )

    @Test
    fun customOrderAndHiddenActionsKeepExportDispatchAndSelection() {
        val dispatched = mutableListOf<MultiAppAction>()
        rule.setContent {
            MaterialTheme {
                MultiSelectToolBox(
                    selected = selected,
                    isDhizuku = true,
                    canForceStop = false,
                    actionOrder = listOf(EXPORT, SHARE, FREEZE, ADD_TO_PROFILES, FORCE_STOP),
                    hiddenActions = setOf(FREEZE),
                    onMultiAppAction = { dispatched += it },
                )
            }
        }
        val export = app(EXPORT).fetchSemanticsNode().boundsInRoot.left
        val share = app(SHARE).fetchSemanticsNode().boundsInRoot.left
        assertTrue(export < share)
        app(FREEZE).assertDoesNotExist()
        app(ADD_TO_PROFILES).assertDoesNotExist()
        app(FORCE_STOP).assertDoesNotExist()
        app(EXPORT).performClick()
        rule.runOnIdle { assertEquals(listOf(MultiAppAction.Backup(selected)), dispatched) }
    }

    @Test
    fun layoutNeverOverridesLivePrivilegeSelectionOrProfileAvailability() {
        var root by mutableStateOf(false)
        var forceStop by mutableStateOf(false)
        var profilesAvailable by mutableStateOf(false)
        var selection by mutableStateOf(selected)
        var profileRequests = 0
        rule.setContent {
            MaterialTheme {
                MultiSelectToolBox(
                    selected = selection,
                    isShizuku = true,
                    isRoot = root,
                    canForceStop = forceStop,
                    actionOrder = listOf(CLEAR_CACHE, FORCE_STOP, ADD_TO_PROFILES, FREEZE, UNFREEZE, SUSPEND, UNSUSPEND),
                    onAddToProfiles = if (profilesAvailable) ({ profileRequests++ }) else null,
                )
            }
        }
        app(CLEAR_CACHE).assertDoesNotExist()
        app(FORCE_STOP).assertDoesNotExist()
        app(ADD_TO_PROFILES).assertDoesNotExist()
        listOf(FREEZE, UNFREEZE, SUSPEND, UNSUSPEND).forEach { app(it).assertExists() }
        rule.runOnIdle {
            root = true
            forceStop = true
            profilesAvailable = true
            selection = listOf(selected.first())
        }
        app(CLEAR_CACHE).assertExists()
        app(FORCE_STOP).assertExists()
        app(UNFREEZE).assertDoesNotExist()
        app(UNSUSPEND).assertDoesNotExist()
        app(ADD_TO_PROFILES).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, profileRequests) }
    }

    @Test
    fun hidingEveryActionStillAllowsCancellingBothToolbars() {
        var appCancelled = false
        var freezerCancelled = false
        rule.setContent {
            MaterialTheme {
                Column {
                    MultiSelectToolBox(
                        selected = selected,
                        isRoot = true,
                        canForceStop = true,
                        onAddToProfiles = {},
                        hiddenActions = MultiAppActionId.entries.toSet(),
                        onCancel = { appCancelled = true },
                    )
                    FreezerSelectToolBox(
                        selected = selected,
                        isRoot = true,
                        onAddToProfiles = {},
                        hiddenActions = MultiAppActionId.entries.toSet(),
                        onCancel = { freezerCancelled = true },
                    )
                }
            }
        }
        MultiAppActionId.entries.forEach {
            app(it).assertDoesNotExist()
            freezer(it).assertDoesNotExist()
        }
        rule.onNodeWithTag("app_list_multi_action_close").performClick()
        rule.onNodeWithTag("freezer_multi_action_close").performClick()
        rule.runOnIdle {
            assertTrue(appCancelled)
            assertTrue(freezerCancelled)
        }
    }

    @Test
    fun freezerReorderingPreservesSuspendModeAndMetadataCallbacks() {
        val dispatched = mutableListOf<MultiAppAction>()
        var saved = 0
        var removed = 0
        rule.setContent {
            MaterialTheme {
                FreezerSelectToolBox(
                    selected = selected,
                    isDhizuku = true,
                    freezerMode = FreezerMode.SUSPEND,
                    actionOrder = listOf(SAVE_AS_PROFILE, FREEZE, UNFREEZE, REMOVE_FROM_FREEZER, SHARE),
                    hiddenActions = setOf(SHARE),
                    onSaveAsProfile = { saved++ },
                    onRemoveFromFreezer = { removed++ },
                    onMultiAppAction = { dispatched += it },
                )
            }
        }
        assertTrue(
            freezer(SAVE_AS_PROFILE).fetchSemanticsNode().boundsInRoot.left <
                freezer(FREEZE).fetchSemanticsNode().boundsInRoot.left
        )
        freezer(SHARE).assertDoesNotExist()
        freezer(SAVE_AS_PROFILE).performClick()
        freezer(FREEZE).performScrollTo().performClick()
        freezer(UNFREEZE).performScrollTo().performClick()
        freezer(REMOVE_FROM_FREEZER).performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1, saved)
            assertEquals(1, removed)
            assertEquals(
                listOf(MultiAppAction.Freeze(selected, useSuspend = true), MultiAppAction.UnFreeze(selected)),
                dispatched,
            )
        }
    }

    @Test
    fun freezerProfileActionsRemainAvailableWithoutPrivileges() {
        var assigned = 0
        rule.setContent {
            MaterialTheme {
                FreezerSelectToolBox(
                    selected = selected,
                    actionOrder = listOf(FREEZE, UNFREEZE, ADD_TO_PROFILES, SAVE_AS_PROFILE, REMOVE_FROM_FREEZER, CLEAR_CACHE),
                    onAddToProfiles = { assigned++ },
                )
            }
        }
        freezer(FREEZE).assertDoesNotExist()
        freezer(UNFREEZE).assertDoesNotExist()
        freezer(CLEAR_CACHE).assertDoesNotExist()
        freezer(SAVE_AS_PROFILE).assertExists()
        freezer(REMOVE_FROM_FREEZER).assertExists()
        freezer(ADD_TO_PROFILES).performClick()
        rule.runOnIdle { assertEquals(1, assigned) }
    }

    private fun app(action: MultiAppActionId) = rule.onNodeWithTag("app_list_multi_action_${action.name}")
    private fun freezer(action: MultiAppActionId) = rule.onNodeWithTag("freezer_multi_action_${action.name}")
}
