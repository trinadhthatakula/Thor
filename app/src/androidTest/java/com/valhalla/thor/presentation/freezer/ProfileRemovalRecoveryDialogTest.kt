// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileRemovalRecoveryDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val initial = ProfileRemovalRecovery(listOf(
        ProfileRemovalApp("com.example.frozen", AppInfo(packageName = "com.example.frozen", appName = "Frozen app", enabled = false)),
        ProfileRemovalApp("com.example.unavailable", null),
    ))

    @Test
    fun failedRestoreKeepsAppsVisibleAndOffersExplicitRemoval() {
        var recovery by mutableStateOf(initial)
        var restores = 0
        var removals = 0
        var dismissals = 0
        rule.setContent {
            MaterialTheme {
                ProfileRemovalRecoveryDialog(
                    recovery = recovery,
                    isWorking = false,
                    onUnfreeze = {
                        restores++
                        recovery = recovery.copy(error = UiText.StringResource(R.string.profile_removal_restore_failed))
                    },
                    onKeepFrozen = { removals++ },
                    onDismiss = { dismissals++ },
                )
            }
        }

        rule.onNodeWithText("com.example.unavailable").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.profile_removal_state_unknown))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("profile_removal_unfreeze").performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.profile_removal_restore_failed))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("profile_removal_keep").performClick()
        rule.runOnIdle {
            assertEquals(initial.apps, recovery.apps)
            assertEquals(1, restores)
            assertEquals(1, removals)
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun workingDialogBlocksBackAndActionsThenAllowsCancellation() {
        var working by mutableStateOf(true)
        var dismissals = 0
        rule.setContent {
            MaterialTheme {
                ProfileRemovalRecoveryDialog(initial, working, {}, {}, { dismissals++ })
            }
        }

        rule.onNodeWithTag("profile_removal_unfreeze").assertIsNotEnabled()
        rule.onNodeWithTag("profile_removal_keep").assertIsNotEnabled()
        rule.onNodeWithTag("profile_removal_cancel").assertIsNotEnabled()
        onView(isRoot()).inRoot(isDialog()).perform(pressBack())
        rule.onNodeWithTag("profile_removal_recovery").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(0, dismissals)
            working = false
        }
        rule.onNodeWithTag("profile_removal_cancel").performClick()
        rule.runOnIdle { assertEquals(1, dismissals) }
    }
}
