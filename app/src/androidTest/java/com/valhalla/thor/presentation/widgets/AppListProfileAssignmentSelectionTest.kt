// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The picker retains its draft; recreating the source widget must not lose that selection. */
@RunWith(AndroidJUnit4::class)
class AppListProfileAssignmentSelectionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val selected = listOf(
        AppInfo(appName = "Selected first", packageName = "com.example.first"),
        AppInfo(appName = "Selected second", packageName = "com.example.second"),
    )
    private val apps = selected + AppInfo(appName = "Other app", packageName = "com.example.other")
    private var showList by mutableStateOf(true)
    private var assignmentDraft by mutableStateOf(selected)
    private var clearSelectionRequest by mutableIntStateOf(0)
    private val assignmentRequests = mutableListOf<List<AppInfo>>()

    @Test
    fun recreatingSourceWidgetRestoresTheRetainedAssignmentDraft() {
        setList()
        selectedHeader().assertExists()

        rule.runOnIdle { showList = false }
        selectedHeader().assertDoesNotExist()
        rule.runOnIdle { showList = true }

        selectedHeader().assertExists()
        requestAssignment()
        rule.runOnIdle { assertEquals(listOf(selected), assignmentRequests) }
    }

    @Test
    fun cancellingTheAssignmentDraftKeepsTheSourceSelection() {
        setList()
        selectedHeader().assertExists()

        // Dismissing the picker clears its retained draft but sends no successful-save reset.
        rule.runOnIdle { assignmentDraft = emptyList() }

        selectedHeader().assertExists()
        requestAssignment()
        rule.runOnIdle { assertEquals(listOf(selected), assignmentRequests) }
    }

    @Test
    fun successfulAssignmentResetClearsTheSourceSelection() {
        setList()
        selectedHeader().assertExists()

        // The host clears the draft at commit and increments this signal on its Assigned event.
        rule.runOnIdle {
            assignmentDraft = emptyList()
            clearSelectionRequest++
        }

        selectedHeader().assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.profile_assignment_title))
            .assertDoesNotExist()
        rule.onNodeWithText("Selected first").assertExists()
    }

    private fun setList() = rule.setContent {
        MaterialTheme {
            if (showList) {
                AppList(
                    appListType = AppListType.USER,
                    installers = emptyList(),
                    appList = apps,
                    selectedFilter = null,
                    isGrid = false,
                    profileAssignmentSelection = assignmentDraft,
                    clearSelectionRequest = clearSelectionRequest,
                    onFilterSelected = {},
                    onAppInfoSelected = {},
                    onAddToProfiles = { assignmentRequests += it.toList() },
                )
            }
        }
    }

    private fun selectedHeader() =
        rule.onNodeWithText(rule.activity.getString(R.string.selected_count, selected.size))

    private fun requestAssignment() {
        rule.onNodeWithText(rule.activity.getString(R.string.profile_assignment_title))
            .performScrollTo()
            .performClick()
    }
}
