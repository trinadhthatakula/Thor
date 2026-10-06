// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.FilterType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UAD availability and metadata status must stay clear even before recommendations arrive. */
@RunWith(AndroidJUnit4::class)
class AppListUadFilterTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var listType by mutableStateOf(AppListType.SYSTEM)
    private var selectedFilter by mutableStateOf("All")
    private var loadingUad by mutableStateOf(false)
    private var uadFailed by mutableStateOf(false)
    private val selectedValues = mutableListOf<String?>()

    @Test
    fun systemFilterSheetOffersUadAndRemovesItWhenSwitchingToUserApps() {
        setList()
        rule.onNodeWithContentDescription(label(R.string.cd_config)).performClick()
        rule.onNodeWithText(label(R.string.filter_type_uad)).performScrollTo().assertExists()

        rule.onNodeWithContentDescription(label(R.string.chip_user))
            .performScrollTo()
            .performClick()

        rule.onNodeWithText(label(R.string.filter_type_uad)).assertDoesNotExist()
        rule.onNodeWithText(label(R.string.filter_type_source)).assertExists()
    }

    @Test
    fun recommendationChipsSelectStableValues() {
        setList()
        val recommendations = listOf(
            R.string.uad_filter_recommended to "Recommended",
            R.string.uad_filter_advanced to "Advanced",
            R.string.uad_filter_expert to "Expert",
            R.string.uad_filter_unsafe to "Unsafe",
            R.string.unknown to "Unknown",
        )

        recommendations.forEach { (resource, _) ->
            rule.onNodeWithText(label(resource))
                .performScrollTo()
                .performClick()
                .assertIsSelected()
        }

        rule.runOnIdle { assertEquals(recommendations.map { it.second }, selectedValues) }
    }

    @Test
    fun pendingOrFailedMetadataDoesNotShowUnknownResultsAndAllRemainsAvailable() {
        selectedFilter = "Unknown"
        loadingUad = true
        setList()

        rule.onNodeWithText(label(R.string.uad_filter_loading)).assertExists()
        rule.onNodeWithText(label(R.string.no_matching_apps)).assertDoesNotExist()
        rule.onNodeWithText(label(R.string.unknown)).assertIsNotEnabled()
        rule.onNodeWithText(label(R.string.filter_all)).assertIsEnabled()

        rule.runOnIdle {
            loadingUad = false
            uadFailed = true
        }
        rule.onNodeWithText(label(R.string.uad_filter_failed)).assertExists()
        rule.onNodeWithText(label(R.string.uad_filter_loading)).assertDoesNotExist()
        rule.onNodeWithText(label(R.string.no_matching_apps)).assertDoesNotExist()
        rule.onNodeWithText(label(R.string.unknown)).assertIsNotEnabled()
        rule.onNodeWithText(label(R.string.filter_all)).performClick().assertIsSelected()
        rule.onNodeWithText(label(R.string.uad_filter_failed)).assertExists()

        rule.runOnIdle { assertEquals(listOf("All"), selectedValues) }
    }

    @Test
    fun userListDoesNotExposeUadChipsEvenWithAnInvalidCategory() {
        listType = AppListType.USER
        loadingUad = true
        setList()

        rule.onNodeWithText(label(R.string.uad_filter_recommended)).assertDoesNotExist()
        rule.onNodeWithText(label(R.string.unknown)).assertDoesNotExist()
        rule.onNodeWithText(label(R.string.uad_filter_loading)).assertDoesNotExist()
    }

    private fun setList() = rule.setContent {
        MaterialTheme {
            AppList(
                appListType = listType,
                installers = listOf("All"),
                appList = emptyList<AppInfo>(),
                selectedFilter = selectedFilter,
                filterType = FilterType.Uad,
                isLoadingUad = loadingUad,
                uadLoadFailed = uadFailed,
                onFilterSelected = {
                    selectedValues += it
                    selectedFilter = it ?: "All"
                },
                onListTypeChanged = { listType = it },
                onAppInfoSelected = {},
            )
        }
    }

    private fun label(resource: Int) = rule.activity.getString(resource)
}
