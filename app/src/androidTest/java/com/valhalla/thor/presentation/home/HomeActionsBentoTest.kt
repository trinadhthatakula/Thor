// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.R
import com.valhalla.thor.presentation.home.components.HomeActionsBento
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end cover for the long-press explanation: a paired tile drops its description, so the
 * sheet is the only place that text still exists for a sighted user. A wide tile shows it inline
 * and must not open anything.
 */
@RunWith(AndroidJUnit4::class)
class HomeActionsBentoTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private var clearCacheRuns = 0
    private var installRuns = 0

    /** Root privilege + reinstall card = the full 2x2, so every tile is paired and compact. */
    private fun setFullGrid() = rule.setContent {
        HomeActionsBento(
            reinstallVisible = true,
            canClearCache = true,
            hasPrivilege = true,
            unknownInstallerCount = 7,
            selectedTypeName = "user",
            onReinstall = {},
            onDismissReinstall = {},
            onInstall = { installRuns++ },
            onClearCache = { clearCacheRuns++ },
            onNavigateToExtensionManager = {},
        )
    }

    private fun awaitText(text: String) =
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun pairedTile_dropsItsDescriptionUntilLongPressed() {
        val title = str(R.string.clear_all_cache)
        val body = str(R.string.clear_all_cache_subtitle)
        setFullGrid()

        rule.onNodeWithText(body).assertDoesNotExist()

        rule.onNodeWithText(title).performTouchInput { longClick() }
        awaitText(body)
        rule.onNodeWithText(body).assertExists()
    }

    @Test fun confirmingInTheSheet_runsTheActionTheLongPressSuppressed() {
        val title = str(R.string.clear_all_cache)
        val body = str(R.string.clear_all_cache_subtitle)
        setFullGrid()

        rule.onNodeWithText(title).performTouchInput { longClick() }
        awaitText(body)
        assertEquals("a long press must not run the action on its own", 0, clearCacheRuns)

        // The confirm button is the clickable node sitting next to Cancel. The tile carries the
        // same label but no Cancel sibling; the sheet's heading has the sibling but no action.
        rule.onNode(
            hasText(title) and hasClickAction() and hasAnySibling(hasText(str(R.string.cancel)))
        ).performClick()
        rule.waitForIdle()

        assertEquals(1, clearCacheRuns)
    }

    @Test fun cancellingTheSheet_runsNothing() {
        val title = str(R.string.clear_all_cache)
        val body = str(R.string.clear_all_cache_subtitle)
        setFullGrid()

        rule.onNodeWithText(title).performTouchInput { longClick() }
        awaitText(body)
        rule.onNodeWithText(str(R.string.cancel)).performClick()
        rule.waitForIdle()

        assertEquals(0, clearCacheRuns)
        assertEquals(0, installRuns)
    }

    @Test fun wideTile_showsItsDescriptionInlineAndDoesNotOpenTheSheet() {
        // No reinstall card and no root: Install + Extensions is an even pair... add the card back
        // and Install becomes the odd leader, full-width, with its description intact.
        rule.setContent {
            HomeActionsBento(
                reinstallVisible = true,
                canClearCache = false,
                hasPrivilege = true,
                unknownInstallerCount = 7,
                selectedTypeName = "user",
                onReinstall = {},
                onDismissReinstall = {},
                onInstall = { installRuns++ },
                onClearCache = {},
                onNavigateToExtensionManager = {},
            )
        }
        val title = str(R.string.install_from_file)
        val body = str(R.string.install_from_file_subtitle)

        rule.onNodeWithText(body).assertExists()

        rule.onNodeWithText(title).performTouchInput { longClick() }
        rule.waitForIdle()

        // Nothing to explain, so nothing opened: the description is still the only copy on screen.
        assertEquals(1, rule.onAllNodesWithText(body).fetchSemanticsNodes().size)
        rule.onNodeWithText(str(R.string.cancel)).assertDoesNotExist()
    }
}
