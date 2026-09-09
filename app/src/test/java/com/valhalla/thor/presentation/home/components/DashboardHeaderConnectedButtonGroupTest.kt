// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppListType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class DashboardHeaderConnectedButtonGroupTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun appTypeSwitcherRendersAndForwardsUserAndSystemSelections() {
        val selectedType = mutableStateOf(AppListType.USER)
        val selections = mutableListOf<AppListType>()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val user = context.getString(R.string.chip_user)
        val system = context.getString(R.string.chip_system)

        rule.setContent {
            MaterialTheme {
                DashboardHeader(
                    isRoot = false,
                    isShizuku = false,
                    isDhizuku = false,
                    activeMode = null,
                    isPrivilegeReady = true,
                    selectedType = selectedType.value,
                    onTypeChanged = {
                        selections += it
                        selectedType.value = it
                    },
                    onPrivilegeChanged = {},
                    onRestrictedStatusClick = {},
                    onNavigateToQueue = {},
                )
            }
        }

        rule.onNodeWithContentDescription(system).assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription(user).assertIsDisplayed().performClick()

        rule.runOnIdle {
            assertEquals(listOf(AppListType.SYSTEM, AppListType.USER), selections)
            assertEquals(AppListType.USER, selectedType.value)
        }
    }
}
