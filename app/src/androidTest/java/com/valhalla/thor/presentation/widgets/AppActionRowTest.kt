// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cover for the Force Stop / Suspend / Freeze explainers.
 *
 * The three actions differ only in how long they last and whether the launcher icon survives, and
 * the tile labels are one word each, so the sheet is the only place that difference is written down.
 * The other half of the contract matters just as much: a long press must *not* run the action it was
 * held on — these are the destructive tiles, and a user holding one is hesitating, not committing.
 *
 * No Koin setup here. [AppActionRow] resolves `FreezerShortcutManager` and `PreferenceRepository`
 * with `koinInject`, and an instrumented test runs inside the app process, whose `ThorApplication`
 * has already called `startKoin` — the real graph is up before the first `setContent`.
 */
@RunWith(AndroidJUnit4::class)
class AppActionRowTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private var freezeRuns = 0
    private var suspendRuns = 0
    private var forceStopRuns = 0

    /**
     * Privileged, enabled, not suspended — the state in which all three explainable tiles render.
     * Freeze and Suspend are behind `hasPrivilege`; Force Stop additionally needs `enabled`.
     */
    private fun setPrivilegedRow() = rule.setContent {
        AppActionRow(
            appInfo = AppInfo(appName = "Demo", packageName = "com.example.demo"),
            isRoot = true,
            isShizuku = false,
            isDhizuku = false,
            onLaunch = {},
            onSystemSettings = {},
            onFreezeToggle = { freezeRuns++ },
            onSuspendToggle = { suspendRuns++ },
            onForceStop = { forceStopRuns++ },
            onManagePermissions = {},
            onClearCache = {},
            onClearData = {},
            onFixStore = {},
            onUninstall = {},
            onShare = {},
            onExport = {},
        )
    }

    private fun awaitText(text: String) =
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /**
     * The row scrolls horizontally and holds more than a phone's width of 72 dp tiles, so anything
     * past the fourth is off-screen on a typical device and a bare `performTouchInput` would fail on
     * position rather than on behaviour. `performScrollTo` is a no-op for a tile already in view.
     */
    private fun longPressTile(label: String) =
        rule.onNodeWithText(label).performScrollTo().performTouchInput { longClick() }

    @Test fun longPressingFreeze_explainsItWithoutFreezingAnything() {
        val body = str(R.string.explain_freeze_body)
        setPrivilegedRow()

        rule.onNodeWithText(body).assertDoesNotExist()

        longPressTile(str(R.string.action_freeze))
        awaitText(body)

        rule.onNodeWithText(body).assertExists()
        assertEquals("a long press must explain, never act", 0, freezeRuns)
    }

    @Test fun longPressingSuspend_explainsItWithoutSuspendingAnything() {
        val body = str(R.string.explain_suspend_body)
        setPrivilegedRow()

        longPressTile(str(R.string.action_suspend))
        awaitText(body)

        rule.onNodeWithText(body).assertExists()
        assertEquals("a long press must explain, never act", 0, suspendRuns)
    }

    @Test fun longPressingForceStop_explainsItWithoutStoppingAnything() {
        val body = str(R.string.explain_force_stop_body)
        setPrivilegedRow()

        longPressTile(str(R.string.action_force_stop))
        awaitText(body)

        rule.onNodeWithText(body).assertExists()
        assertEquals("a long press must explain, never act", 0, forceStopRuns)
    }

    /**
     * The sheet is informational only. Unlike the Home bento's version it offers no way to run the
     * action from inside it: Close dismisses, and nothing has happened by the time it is gone.
     */
    @Test fun theSheetOffersNoWayToRunTheAction() {
        val body = str(R.string.explain_force_stop_body)
        setPrivilegedRow()

        longPressTile(str(R.string.action_force_stop))
        awaitText(body)

        rule.onNodeWithText(str(R.string.cancel)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.close)).performClick()
        rule.waitForIdle()

        assertEquals(0, forceStopRuns)
        assertEquals(0, freezeRuns)
        assertEquals(0, suspendRuns)
    }

    /** A tile with no explainer keeps the plain click gesture: holding it does nothing at all. */
    @Test fun aTileWithoutAnExplainerOpensNothing() {
        setPrivilegedRow()

        longPressTile(str(R.string.action_permissions))
        rule.waitForIdle()

        rule.onNodeWithText(str(R.string.close)).assertDoesNotExist()
    }
}
