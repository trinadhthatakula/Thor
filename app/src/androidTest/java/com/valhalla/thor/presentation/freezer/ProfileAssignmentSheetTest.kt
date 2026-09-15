// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.presentation.widgets.MultiSelectToolBox
import com.valhalla.thor.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileAssignmentSheetTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun multipleProfilesCanBeSelectedAndOverlapCountsDescribeEachTarget() {
        var state by mutableStateOf(draft())
        val toggles = mutableListOf<Long>()
        var submittedIds = emptySet<Long>()
        setSheet(
            state = { state },
            onToggle = { id ->
                toggles += id
                state = state.copy(
                    selectedProfileIds = if (id in state.selectedProfileIds) {
                        state.selectedProfileIds - id
                    } else state.selectedProfileIds + id,
                )
            },
            onSubmit = { submittedIds = state.selectedProfileIds },
        )

        sheetText(R.string.profile_assignment_selected_apps, 2).assertExists()
        profileRow(1).assertIsOff()
            .assertTextContains("Games")
            .assertTextContains(str(R.string.profile_assignment_member_count, 2))
            .assertTextContains(str(R.string.profile_assignment_overlap, 1, 1))
            .performClick()
        profileRow(2).assertIsOff()
            .assertTextContains("Travel")
            .assertTextContains(str(R.string.profile_assignment_overlap, 2, 0))
            .performClick()
        profileRow(1).assertIsOn()
        profileRow(2).assertIsOn()
        sheetNode(SUBMIT).assertIsEnabled().performClick()
        rule.onNodeWithText(str(R.string.profile_assignment_selected_profiles, 2)).assertExists()
        rule.runOnIdle {
            assertEquals(listOf(1L, 2L), toggles)
            assertEquals(setOf(1L, 2L), submittedIds)
        }

        profileRow(1).performClick().assertIsOff()
        profileRow(2).assertIsOn()
        sheetNode(SUBMIT).assertIsEnabled()
        rule.onNodeWithText(str(R.string.profile_assignment_selected_profiles, 1)).assertExists()
        rule.runOnIdle { assertEquals(listOf(1L, 2L, 1L), toggles) }
    }

    @Test
    fun submissionRequiresBothAnAppAndASelectedProfile() {
        var state by mutableStateOf(draft())
        setSheet(state = { state })

        sheetNode(SUBMIT).assertIsNotEnabled()
        rule.runOnIdle { state = state.copy(selectedApps = emptyList(), selectedProfileIds = setOf(1L)) }
        sheetNode(SUBMIT).assertIsNotEnabled()
        profileRow(1).assertIsOn()
    }

    @Test
    fun emptyProfilesDisableSubmissionAndOfferNoProfileCreation() {
        setSheet(state = { draft().copy(profiles = emptyList()) })

        sheetNode(SUBMIT).assertIsNotEnabled()
        sheetText(R.string.profile_assignment_no_profiles).assertExists()
        rule.onNodeWithText(str(R.string.profile_new)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.profile_save_selection)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.profile_name_label)).assertDoesNotExist()
    }

    @Test
    fun freezerEnrollmentIsOptionalAndLocksWhileSaving() {
        var state by mutableStateOf(draft().copy(freezerLoaded = true, selectedProfileIds = setOf(1L)))
        setSheet(
            state = { state },
            onToggleFreezer = { state = state.copy(alsoAddToFreezer = !state.alsoAddToFreezer) },
        )

        sheetNode(FREEZER).assertIsOff().performClick().assertIsOn()
        sheetText(R.string.profile_assignment_also_freezer_description).assertExists()
        rule.runOnIdle { state = state.copy(isSaving = true) }
        sheetNode(FREEZER).assertIsNotEnabled()
    }

    @Test
    fun enrollmentIsHiddenWhenEverySelectedAppIsAlreadyInFreezer() {
        setSheet(state = {
            draft().copy(freezerLoaded = true, freezerPackageNames = apps.map { it.packageName }.toSet())
        })

        rule.onNodeWithTag(FREEZER).assertDoesNotExist()
    }

    @Test
    fun failedFreezerReadStillAllowsRemovingTheOptionalEnrollment() {
        var state by mutableStateOf(draft().copy(
            selectedProfileIds = setOf(1L), freezerLoaded = true,
            freezerLoadFailed = true, alsoAddToFreezer = true,
        ))
        setSheet(
            state = { state },
            onToggleFreezer = { state = state.copy(alsoAddToFreezer = false) },
        )

        sheetNode(SUBMIT).assertIsNotEnabled()
        sheetNode(FREEZER).assertIsOn().performClick()
        rule.onNodeWithTag(FREEZER).assertDoesNotExist()
        sheetNode(SUBMIT).assertIsEnabled()
    }

    @Test
    fun savingDisablesRowsSubmitAndCancelWithoutCallingTheirCallbacks() {
        var toggles = 0
        var submissions = 0
        var dismissals = 0
        setSheet(
            state = { draft().copy(selectedProfileIds = setOf(1L, 2L), isSaving = true) },
            onToggle = { toggles++ },
            onSubmit = { submissions++ },
            onDismiss = { dismissals++ },
        )

        profileRow(1).assertIsOn().assertIsNotEnabled().performTouchInput { click() }
        profileRow(2).assertIsOn().assertIsNotEnabled().performTouchInput { click() }
        sheetNode(SUBMIT).assertIsNotEnabled().performTouchInput { click() }
        sheetNode(CANCEL).assertIsNotEnabled().performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(0, toggles)
            assertEquals(0, submissions)
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun inlineWriteErrorKeepsChoicesAndAllowsRetryOrCancel() {
        var state by mutableStateOf(draft().copy(selectedProfileIds = setOf(1L, 2L)))
        var submissions = 0
        var dismissals = 0
        setSheet(
            state = { state },
            onSubmit = { submissions++ },
            onDismiss = { dismissals++ },
        )
        rule.runOnIdle {
            state = state.copy(error = UiText.StringResource(R.string.profile_assignment_save_failed))
        }

        sheetNode(ERROR).assertTextContains(str(R.string.profile_assignment_save_failed))
        profileRow(1).assertIsOn().assertIsEnabled()
        profileRow(2).assertIsOn().assertIsEnabled()
        sheetNode(SUBMIT).assertIsEnabled().performClick()
        sheetNode(CANCEL).assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(setOf(1L, 2L), state.selectedProfileIds)
            assertEquals(1, submissions)
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun expertConfirmationShowsCautionAndKeepsTheDraftWhenCancelled() {
        val experts = listOf(apps[0].copy(appName = "System helper"), apps[1].copy(appName = null))
        var state by mutableStateOf(
            draft().copy(selectedProfileIds = setOf(1L, 2L), expertApps = experts, skippedCount = 1),
        )
        var confirmations = 0
        var expertDismissals = 0
        var sheetDismissals = 0
        var ordinarySubmissions = 0
        setSheet(
            state = { state },
            onSubmit = { ordinarySubmissions++ },
            onDismiss = { sheetDismissals++ },
            onConfirmExperts = {
                confirmations++
                state = state.copy(expertApps = emptyList())
            },
            onDismissExperts = {
                expertDismissals++
                state = state.copy(expertApps = emptyList())
            },
        )

        rule.onNodeWithText(str(R.string.profile_assignment_expert_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.profile_assignment_expert_message)).assertExists()
        rule.onNodeWithText("System helper\ncom.example.beta").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(str(R.string.profile_assignment_skipped, 1)).performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, confirmations) }
        rule.onNodeWithText(str(R.string.profile_assignment_expert_confirm)).performClick()
        rule.onNodeWithText(str(R.string.profile_assignment_expert_title)).assertDoesNotExist()

        // Reopen the confirmation to verify Cancel affects only the warning, preserving targets.
        rule.runOnIdle { state = state.copy(expertApps = experts) }
        rule.onNode(
            hasText(str(R.string.cancel)) and
                hasAnySibling(hasText(str(R.string.profile_assignment_expert_confirm))),
        ).performClick()
        rule.onNodeWithText(str(R.string.profile_assignment_expert_title)).assertDoesNotExist()
        profileRow(1).assertIsOn()
        profileRow(2).assertIsOn()
        sheetNode(SUBMIT).assertIsEnabled()
        rule.runOnIdle {
            assertEquals(1, confirmations)
            assertEquals(1, expertDismissals)
            assertEquals(0, ordinarySubmissions)
            assertEquals(0, sheetDismissals)
            assertEquals(setOf(1L, 2L), state.selectedProfileIds)
        }
    }

    @Test
    fun profileLoadFailureDisablesStaleChoicesAndOffersRetry() {
        var state by mutableStateOf(
            draft().copy(
                selectedProfileIds = setOf(1L),
                profilesLoadFailed = true,
                error = UiText.StringResource(R.string.error_profiles_load_failed),
            ),
        )
        var retries = 0
        setSheet(
            state = { state },
            onRetry = {
                retries++
                state = state.copy(profilesLoadFailed = false, error = null)
            },
        )

        profileRow(1).assertIsOn().assertIsNotEnabled()
        profileRow(2).assertIsOff().assertIsNotEnabled()
        sheetNode(SUBMIT).assertIsNotEnabled()
        sheetNode(ERROR).assertTextContains(str(R.string.error_profiles_load_failed))
        sheetText(R.string.retry_label).assertIsEnabled().performClick()
        rule.onNodeWithTag(ERROR).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.retry_label)).assertDoesNotExist()
        profileRow(1).assertIsOn().assertIsEnabled()
        profileRow(2).assertIsOff().assertIsEnabled()
        sheetNode(SUBMIT).assertIsEnabled()
        rule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(setOf(1L), state.selectedProfileIds)
        }
    }

    @Test
    fun backCannotDismissWhileSavingAndWorksAfterSavingFinishes() {
        var state by mutableStateOf(draft().copy(selectedProfileIds = setOf(1L)))
        var dismissals = 0
        setSheet(state = { state }, onDismiss = { dismissals++ })
        rule.runOnIdle { state = state.copy(isSaving = true) }

        // Target the sheet's dialog window; the underlying Activity does not have input focus.
        onView(isRoot()).inRoot(isDialog()).perform(pressBack())

        rule.onNodeWithTag(SHEET).assertIsDisplayed()
        sheetNode(CANCEL).assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(0, dismissals)
            state = state.copy(isSaving = false)
        }
        sheetNode(CANCEL).assertIsEnabled()
        onView(isRoot()).inRoot(isDialog()).perform(pressBack())
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun appListToolbarHidesAssignmentWhenNoCallbackIsAvailable() {
        rule.setContent {
            MaterialTheme {
                MultiSelectToolBox(selected = apps, canForceStop = false, onAddToProfiles = null)
            }
        }

        rule.onNodeWithText(str(R.string.profile_assignment_title)).assertDoesNotExist()
    }

    @Test
    fun freezerToolbarHidesAssignmentWhenNoCallbackIsAvailable() {
        rule.setContent {
            MaterialTheme {
                FreezerSelectToolBox(selected = apps, onAddToProfiles = null)
            }
        }

        rule.onNodeWithText(str(R.string.profile_assignment_title)).assertDoesNotExist()
    }

    @Test
    fun appListToolbarAssignsWithoutPrivilegeOrDispatchingAnotherAction() {
        var assignments = 0
        val actions = mutableListOf<MultiAppAction>()
        rule.setContent {
            MaterialTheme {
                MultiSelectToolBox(
                    selected = apps,
                    isRoot = false,
                    isShizuku = false,
                    isDhizuku = false,
                    canForceStop = false,
                    onAddToProfiles = { assignments++ },
                    onMultiAppAction = { actions += it },
                )
            }
        }

        rule.onNodeWithText(str(R.string.action_freeze)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.profile_assignment_title))
            .performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(1, assignments)
            assertTrue(actions.isEmpty())
        }
    }

    @Test
    fun freezerToolbarAssignsWithoutPrivilegeOrChangingTheWatchlist() {
        var assignments = 0
        var removals = 0
        var newProfiles = 0
        val actions = mutableListOf<MultiAppAction>()
        rule.setContent {
            MaterialTheme {
                FreezerSelectToolBox(
                    selected = apps,
                    isRoot = false,
                    isShizuku = false,
                    isDhizuku = false,
                    onAddToProfiles = { assignments++ },
                    onRemoveFromFreezer = { removals++ },
                    onSaveAsProfile = { newProfiles++ },
                    onMultiAppAction = { actions += it },
                )
            }
        }

        rule.onNodeWithText(str(R.string.action_freeze)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.profile_assignment_title))
            .performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(1, assignments)
            assertEquals(0, removals)
            assertEquals(0, newProfiles)
            assertTrue(actions.isEmpty())
        }
    }

    private fun setSheet(
        state: () -> ProfileAssignmentUiState,
        onToggle: (Long) -> Unit = {},
        onToggleFreezer: () -> Unit = {},
        onSubmit: () -> Unit = {},
        onDismiss: () -> Unit = {},
        onRetry: () -> Unit = {},
        onConfirmExperts: () -> Unit = {},
        onDismissExperts: () -> Unit = {},
    ) = rule.setContent {
        MaterialTheme {
            ProfileAssignmentSheet(
                state = state(),
                onToggleProfile = onToggle,
                onToggleAlsoAddToFreezer = onToggleFreezer,
                onSubmit = onSubmit,
                onDismiss = onDismiss,
                onRetry = onRetry,
                onConfirmExperts = onConfirmExperts,
                onDismissExperts = onDismissExperts,
            )
        }
    }

    private fun profileRow(id: Long) = sheetNode("profile_assignment_profile_$id")

    // The sheet is lazy and may need scrolling to its footer on small emulators.
    private fun sheetNode(tag: String): SemanticsNodeInteraction {
        rule.onNodeWithTag(SHEET).performScrollToNode(hasTestTag(tag))
        return rule.onNodeWithTag(tag)
    }

    private fun sheetText(id: Int, vararg args: Any): SemanticsNodeInteraction {
        val text = str(id, *args)
        rule.onNodeWithTag(SHEET).performScrollToNode(hasText(text))
        return rule.onNodeWithText(text)
    }

    private fun str(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    private fun draft() = ProfileAssignmentUiState(
        isOpen = true,
        selectedApps = apps,
        profiles = listOf(
            FreezeProfile(1L, "Games", listOf("com.example.alpha", "com.example.existing")),
            FreezeProfile(2L, "Travel", emptyList()),
        ),
    )

    private val apps = listOf(
        AppInfo(appName = "Alpha", packageName = "com.example.alpha"),
        AppInfo(appName = "Beta", packageName = "com.example.beta"),
    )

    private companion object {
        const val SHEET = "profile_assignment_sheet"
        const val SUBMIT = "profile_assignment_submit"
        const val CANCEL = "profile_assignment_cancel"
        const val ERROR = "profile_assignment_error"
        const val FREEZER = "profile_assignment_also_freezer"
    }
}
