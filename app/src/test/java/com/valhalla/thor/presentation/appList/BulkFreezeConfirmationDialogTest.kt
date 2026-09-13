// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import android.app.Application
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.freezeTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BulkFreezeConfirmationDialogTest {

    @get:Rule val rule = createComposeRule()

    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private val freezeLabel get() = application.getString(R.string.action_freeze)
    private val checkboxLabel get() = application.getString(R.string.add_to_freezer)
    private val checkboxDescription get() = application.getString(R.string.bulk_freeze_add_to_freezer_desc)
    private val cancelLabel get() = application.getString(R.string.cancel)

    @Test
    fun defaultsToOneLabeledCheckedCheckboxAndDoesNothingUntilConfirmed() {
        val confirmed = mutableListOf<Boolean>()
        rule.setContent {
            MaterialTheme {
                BulkFreezeConfirmationDialog(
                    appCount = 2,
                    requestKey = "first",
                    onConfirm = confirmed::add,
                    onDismiss = {},
                )
            }
        }

        rule.onAllNodes(hasCheckboxRole).assertCountEquals(1)
        rule.onNode(hasCheckboxRole and hasText(checkboxLabel) and hasText(checkboxDescription))
            .assertIsEnabled().assertIsOn()
        rule.onNodeWithText(application.resources.getQuantityString(R.plurals.bulk_freeze_confirm_desc, 2, 2))
            .assertIsDisplayed()
        rule.runOnIdle { assertTrue(confirmed.isEmpty()) }

        confirmButton().performClick()
        rule.runOnIdle { assertEquals(listOf(true), confirmed) }
    }

    @Test
    fun uncheckingOnlyChangesTheTrackingChoiceSentAfterConfirmation() {
        val confirmed = mutableListOf<Boolean>()
        rule.setContent {
            MaterialTheme {
                BulkFreezeConfirmationDialog(
                    appCount = 1,
                    requestKey = "first",
                    onConfirm = confirmed::add,
                    onDismiss = {},
                )
            }
        }

        rule.onNodeWithText(checkboxLabel).performClick()
        rule.onNode(hasCheckboxRole).assertIsOff()
        rule.runOnIdle { assertTrue(confirmed.isEmpty()) }
        confirmButton().performClick()
        rule.runOnIdle { assertEquals(listOf(false), confirmed) }
    }

    @Test
    fun cancellingAfterChangingTheCheckboxDoesNotConfirmAnything() {
        val confirmed = mutableListOf<Boolean>()
        var dismissals = 0
        rule.setContent {
            MaterialTheme {
                BulkFreezeConfirmationDialog(
                    appCount = 2,
                    requestKey = "first",
                    onConfirm = confirmed::add,
                    onDismiss = { dismissals++ },
                )
            }
        }

        rule.onNode(hasCheckboxRole).performClick().assertIsOff()
        rule.onNodeWithText(cancelLabel).performClick()
        rule.runOnIdle {
            assertTrue(confirmed.isEmpty())
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun aNewRequestResetsTheCheckboxEvenWhenTheSelectionCountIsUnchanged() {
        val requestKey = mutableStateOf("first")
        rule.setContent {
            MaterialTheme {
                BulkFreezeConfirmationDialog(
                    appCount = 2,
                    requestKey = requestKey.value,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        rule.onNode(hasCheckboxRole).performClick().assertIsOff()
        rule.runOnIdle { requestKey.value = "next" }
        rule.onNode(hasCheckboxRole).assertIsOn()
    }

    @Test
    fun restorationPreservesOptOutAndSelectedPackageSafetyInputsWithoutSubmitting() {
        val fixture = MultiAppAction.Freeze(
            appList = listOf(
                AppInfo(packageName = "com.example.user", enabled = false),
                AppInfo(packageName = "com.example.expert", isSystem = true, bloatRecommendation = "Expert"),
                AppInfo(packageName = "com.example.unsafe", isSystem = true, bloatRecommendation = "Unsafe"),
                AppInfo(packageName = "com.example.unknown", isSystem = true, isUadLoadFailed = true),
            ),
            useSuspend = true,
        )
        val confirmed = mutableListOf<Pair<MultiAppAction.Freeze, Boolean>>()
        val restorationTester = StateRestorationTester(rule)
        restorationTester.setContent {
            PendingRequestHost(fixture) { action, addToFreezer -> confirmed += action to addToFreezer }
        }
        rule.onNodeWithText(REQUEST_FREEZE).performClick()
        rule.onNode(hasCheckboxRole).performClick().assertIsOff()

        restorationTester.emulateSavedInstanceStateRestore()

        rule.onNode(hasCheckboxRole).assertIsOff()
        rule.runOnIdle { assertTrue(confirmed.isEmpty()) }
        confirmButton().performClick()
        rule.runOnIdle {
            val (action, addToFreezer) = confirmed.single()
            assertFalse(addToFreezer)
            assertEquals(fixture.appList.map { it.packageName }, action.appList.map { it.packageName })
            assertEquals(fixture.appList.map { it.freezeTier }, action.appList.map { it.freezeTier })
            assertEquals(fixture.useSuspend, action.useSuspend)
        }
        rule.onAllNodes(hasCheckboxRole).assertCountEquals(0)
    }

    @Test
    fun cancellationClearsPendingConsentAcrossRestorationAndTheNextRequestStartsChecked() {
        val confirmed = mutableListOf<Boolean>()
        val fixture = MultiAppAction.Freeze(listOf(AppInfo(packageName = "com.example.user")))
        val restorationTester = StateRestorationTester(rule)
        restorationTester.setContent {
            PendingRequestHost(fixture) { _, addToFreezer -> confirmed += addToFreezer }
        }
        rule.onNodeWithText(REQUEST_FREEZE).performClick()
        rule.onNode(hasCheckboxRole).performClick().assertIsOff()
        rule.onNodeWithText(cancelLabel).performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        rule.onAllNodes(hasCheckboxRole).assertCountEquals(0)
        rule.runOnIdle { assertTrue(confirmed.isEmpty()) }
        rule.onNodeWithText(REQUEST_FREEZE).performClick()
        rule.onNode(hasCheckboxRole).assertIsOn()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun narrowScreenAtDoubleFontScaleKeepsTheExplanationAndBothActionsReachable() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme {
                    BulkFreezeConfirmationDialog(
                        appCount = 24,
                        requestKey = "first",
                        onConfirm = {},
                        onDismiss = {},
                    )
                }
            }
        }

        rule.onNodeWithText(
            application.resources.getQuantityString(R.plurals.bulk_freeze_confirm_desc, 24, 24),
            useUnmergedTree = true,
        ).assertHasNoTruncation()
        rule.onNodeWithText(checkboxLabel, useUnmergedTree = true).assertHasNoTruncation()
        rule.onNodeWithText(checkboxDescription, useUnmergedTree = true)
            .assertHasNoTruncation()
            .performScrollTo()
            .assertIsDisplayed()
        confirmButton().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText(cancelLabel).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun anEmptySelectionCannotBeConfirmed() {
        val confirmed = mutableListOf<Boolean>()
        rule.setContent {
            MaterialTheme {
                BulkFreezeConfirmationDialog(
                    appCount = 0,
                    requestKey = "empty",
                    onConfirm = confirmed::add,
                    onDismiss = {},
                )
            }
        }
        confirmButton().assertIsNotEnabled().performClick()
        rule.runOnIdle { assertTrue(confirmed.isEmpty()) }
    }

    @Composable
    private fun PendingRequestHost(
        fixture: MultiAppAction.Freeze,
        onConfirm: (MultiAppAction.Freeze, Boolean) -> Unit,
    ) {
        var pending by rememberSaveable(stateSaver = BulkFreezeConfirmationRequest.Saver) {
            mutableStateOf<BulkFreezeConfirmationRequest?>(null)
        }
        MaterialTheme {
            Button(onClick = { pending = BulkFreezeConfirmationRequest.from(fixture) }) {
                Text(REQUEST_FREEZE)
            }
            pending?.let { request ->
                BulkFreezeConfirmationDialog(
                    appCount = request.action.appList.size,
                    requestKey = request.id,
                    onConfirm = { addToFreezer ->
                        if (pending?.id == request.id) {
                            pending = null
                            onConfirm(request.action, addToFreezer)
                        }
                    },
                    onDismiss = { pending = null },
                )
            }
        }
    }

    private fun confirmButton() = rule.onNode(hasText(freezeLabel) and hasClickAction())

    private fun SemanticsNodeInteraction.assertHasNoTruncation(): SemanticsNodeInteraction {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action)
        assertTrue(action(layouts))
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        val diagnostic = "text=${layout.layoutInput.text}, size=${layout.size}, " +
            "constraints=${layout.layoutInput.constraints}, lines=${layout.lineCount}, " +
            "paragraphWidth=${layout.multiParagraph.width}, paragraphHeight=${layout.multiParagraph.height}, " +
            "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}"
        assertFalse(diagnostic, layout.hasVisualOverflow)
        assertFalse(diagnostic, layout.didOverflowWidth)
        assertFalse(diagnostic, layout.didOverflowHeight)
        assertFalse(diagnostic, (0 until layout.lineCount).any(layout::isLineEllipsized))
        return this
    }

    private companion object {
        const val REQUEST_FREEZE = "Request freeze"
        val hasCheckboxRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
    }
}
