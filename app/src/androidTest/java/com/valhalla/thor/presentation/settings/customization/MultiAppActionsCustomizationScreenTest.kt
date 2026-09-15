// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings.customization

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfoActionId
import com.valhalla.thor.domain.model.MultiAppActionId
import com.valhalla.thor.domain.model.MultiAppActionLayout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiAppActionsCustomizationScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun hidingEveryActionLeavesOnlyCloseInTheNonInteractivePreview() {
        val layout = MultiAppActionLayout.APP_LIST
        setEditor(layout, hidden = { layout.defaultOrder.toSet() })

        rule.onNodeWithTag(PREVIEW)
            .assertContentDescriptionEquals(str(R.string.close))
            .assertHasNoClickAction()
            .onChildren().assertCountEquals(0)
        rule.onNodeWithText(str(R.string.all_actions_hidden)).assertDoesNotExist()
        rule.onNodeWithTag("customization_action_CLOSE").assertDoesNotExist()
        actionSwitch(layout.defaultOrder.first()).assertIsOff()
    }

    @Test
    fun unavailableActionsRemainEditableAndVisibilityTargetsTheSelectedLayout() {
        val layout = MultiAppActionLayout.FREEZER
        var hidden by mutableStateOf(emptySet<MultiAppActionId>())
        val changes = mutableListOf<Triple<MultiAppActionLayout, MultiAppActionId, Boolean>>()
        setEditor(
            layout = layout,
            hidden = { hidden },
            onVisibilityChanged = { target, action, visible ->
                changes += Triple(target, action, visible)
                hidden = if (visible) hidden - action else hidden + action
            },
        )

        // There is no runtime selection or profile list in this editor: users can configure the
        // action before they have profiles, and the availability explanation says when it appears.
        rule.onNodeWithText(str(R.string.customization_multi_actions_availability)).assertExists()
        rule.onNodeWithTag(LIST).performScrollToNode(hasTestTag(rowTag(MultiAppActionId.ADD_TO_PROFILES)))
        actionSwitch(MultiAppActionId.ADD_TO_PROFILES).assertIsOn().performClick().assertIsOff()
        rule.onNodeWithTag(PREVIEW).assertContentDescriptionEquals(
            previewOrder(layout.defaultOrder.filterNot { it == MultiAppActionId.ADD_TO_PROFILES })
        )
        rule.runOnIdle {
            assertEquals(
                listOf(Triple(layout, MultiAppActionId.ADD_TO_PROFILES, false)),
                changes,
            )
        }
    }

    @Test
    fun accessibleSteppingReordersHiddenActionsAndKeepsCloseFirst() {
        val layout = MultiAppActionLayout.APP_LIST
        val first = MultiAppActionId.FREEZE
        val second = MultiAppActionId.UNFREEZE
        val third = MultiAppActionId.ADD_TO_PROFILES
        var order by mutableStateOf(listOf(first, second, third))
        val writes = mutableListOf<Pair<MultiAppActionLayout, List<MultiAppActionId>>>()
        setEditor(
            layout = layout,
            order = { order },
            hidden = { setOf(first) },
            onOrderChanged = { target, updated ->
                writes += target to updated
                order = updated
            },
        )

        moveButton(first, R.string.cd_move_up).assertIsNotEnabled()
        moveButton(first, R.string.cd_move_down).performClick()
        moveButton(first, R.string.cd_move_up).assertIsEnabled()
        actionSwitch(first).assertIsOff()
        rule.onNodeWithTag(PREVIEW).assertContentDescriptionEquals(previewOrder(listOf(second, third)))
        rule.runOnIdle { assertEquals(listOf(layout to listOf(second, first, third)), writes) }
    }

    @Test
    fun resetRequiresConfirmationAndUsesOnlyTheCurrentLayout() {
        var layout by mutableStateOf(MultiAppActionLayout.APP_LIST)
        val resets = mutableListOf<MultiAppActionLayout>()
        rule.setContent {
            MaterialTheme {
                MultiAppActionsCustomizationContent(
                    layout = layout,
                    currentOrder = layout.defaultOrder,
                    hiddenActions = emptySet(),
                    onBack = {},
                    onOrderChanged = { _, _ -> },
                    onVisibilityChanged = { _, _, _ -> },
                    onReset = { resets += it },
                )
            }
        }

        rule.onNodeWithContentDescription(str(R.string.reset_to_default)).performClick()
        rule.onNodeWithText(str(R.string.reset_multi_actions_confirm_desc)).assertExists()
        rule.onNodeWithText(str(R.string.cancel)).performClick()
        rule.runOnIdle { assertEquals(emptyList<MultiAppActionLayout>(), resets) }

        rule.onNodeWithContentDescription(str(R.string.reset_to_default)).performClick()
        rule.onNodeWithText(str(R.string.reset_to_default)).performClick()
        rule.runOnIdle {
            assertEquals(listOf(MultiAppActionLayout.APP_LIST), resets)
        }

        // Changing the destination cannot carry an open reset prompt or the previous layout's
        // remembered action order into another toolbar.
        rule.onNodeWithContentDescription(str(R.string.reset_to_default)).performClick()
        rule.runOnIdle { layout = MultiAppActionLayout.FREEZER }
        rule.onNodeWithText(str(R.string.reset_multi_actions_confirm_desc)).assertDoesNotExist()
        rule.onNodeWithTag(PREVIEW).assertContentDescriptionEquals(
            previewOrder(MultiAppActionLayout.FREEZER.defaultOrder)
        )
        rule.onNodeWithContentDescription(str(R.string.reset_to_default)).performClick()
        rule.onNodeWithText(str(R.string.reset_to_default)).performClick()
        rule.runOnIdle {
            assertEquals(listOf(MultiAppActionLayout.APP_LIST, MultiAppActionLayout.FREEZER), resets)
        }
    }

    @Test
    fun appInfoEditorRetainsDescriptionsAndItsExistingAllHiddenState() {
        val action = AppInfoActionId.FREEZE
        rule.setContent {
            MaterialTheme {
                ActionsCustomizationEditor(
                    currentOrder = listOf(
                        CustomizationAction(
                            action, action.name, action.titleRes, action.defaultIconRes,
                            action.descriptionRes,
                        )
                    ),
                    hiddenActions = setOf(action),
                    titleRes = R.string.customization_app_info_actions,
                    onBack = {},
                    onOrderChanged = {},
                    onVisibilityChanged = { _, _ -> },
                    onReset = {},
                )
            }
        }

        rule.onNodeWithText(str(action.descriptionRes)).assertExists()
        rule.onNodeWithText(str(R.string.all_actions_hidden)).assertExists()
        rule.onNodeWithTag(PREVIEW).assertDoesNotExist()
        rule.onNodeWithContentDescription(str(R.string.reset_to_default)).performClick()
        rule.onNodeWithText(str(R.string.reset_actions_confirm_desc)).assertExists()
    }

    private fun setEditor(
        layout: MultiAppActionLayout,
        order: () -> List<MultiAppActionId> = { layout.defaultOrder },
        hidden: () -> Set<MultiAppActionId> = { emptySet() },
        onOrderChanged: (MultiAppActionLayout, List<MultiAppActionId>) -> Unit = { _, _ -> },
        onVisibilityChanged: (MultiAppActionLayout, MultiAppActionId, Boolean) -> Unit = { _, _, _ -> },
    ) = rule.setContent {
        MaterialTheme {
            MultiAppActionsCustomizationContent(
                layout = layout,
                currentOrder = order(),
                hiddenActions = hidden(),
                onBack = {},
                onOrderChanged = onOrderChanged,
                onVisibilityChanged = onVisibilityChanged,
                onReset = {},
            )
        }
    }

    private fun actionSwitch(action: MultiAppActionId) = rule.onNode(
        isToggleable() and hasAnyAncestor(hasTestTag(rowTag(action)))
    )

    private fun moveButton(action: MultiAppActionId, description: Int) = rule.onNode(
        hasContentDescription(str(description)) and hasAnyAncestor(hasTestTag(rowTag(action)))
    )

    private fun previewOrder(actions: List<MultiAppActionId>) =
        (listOf(str(R.string.close)) + actions.map { str(it.titleRes) }).joinToString(", ")

    private fun rowTag(action: MultiAppActionId) = "customization_action_${action.name}"
    private fun str(resource: Int) = rule.activity.getString(resource)

    companion object {
        private const val PREVIEW = "customization_actions_preview"
        private const val LIST = "customization_actions_list"
    }
}
