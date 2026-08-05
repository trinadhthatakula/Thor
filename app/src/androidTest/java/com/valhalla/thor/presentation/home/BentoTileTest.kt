// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.R
import com.valhalla.thor.presentation.home.components.BentoTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the two behaviours the compact bento tile depends on. Both are regressions waiting to
 * happen: the first because dropping a Text also drops its string from the merged semantics node,
 * the second because a nested clickable silently swallows the parent's long press.
 */
@RunWith(AndroidJUnit4::class)
class BentoTileTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val title = "Reinstall All"
    private val subtitle = "7 user apps not from Play Store. Fix them?"
    private val dismissLabel: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.dismiss)

    private fun setTile(
        showSubtitle: Boolean = false,
        onLongClick: (() -> Unit)? = {},
        onLongClickLabel: String? = null,
        onClose: (() -> Unit)? = null,
        onClick: () -> Unit = {},
    ) = rule.setContent {
        BentoTile(
            title = title,
            subtitle = subtitle,
            icon = R.drawable.apk_install,
            showSubtitle = showSubtitle,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
            onClose = onClose,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Test fun compactTile_keepsTheSubtitleInTheAccessibilityTree() {
        setTile(showSubtitle = false)

        // Not rendered as text...
        rule.onNodeWithText(subtitle).assertDoesNotExist()
        // ...but still announced. The tile root merges its descendants, so the string has to
        // survive somewhere beneath it or TalkBack loses the scope of the action entirely.
        rule.onNodeWithContentDescription(subtitle).assertExists()
        rule.onNodeWithText(title).assertExists()
    }

    @Test fun wideTile_rendersTheSubtitleAsText() {
        setTile(showSubtitle = true)

        rule.onNodeWithText(subtitle).assertExists()
        rule.onNodeWithText(title).assertExists()
    }

    @Test fun compactTile_exposesLongPressAsAnAccessibilityAction() {
        setTile(showSubtitle = false, onLongClickLabel = "Show details")

        val config = rule.onNodeWithText(title).fetchSemanticsNode().config
        assertEquals("Show details", config[SemanticsActions.OnLongClick].label)
        assertTrue("the tile must announce itself as a button", SemanticsProperties.Role in config)
    }

    /**
     * The dismiss X sits inside the tile. Given its own plain clickable it would consume the
     * pointer down, so the tile's long-press timer would never start and holding the corner would
     * dismiss the card instead of explaining it. Both gestures are wired on the X for that reason.
     */
    @Test fun longPressOnTheDismissTarget_explainsRatherThanDismisses() {
        var dismissed = 0
        var explained = 0
        var clicked = 0
        setTile(onLongClick = { explained++ }, onClose = { dismissed++ }, onClick = { clicked++ })

        rule.onNodeWithContentDescription(dismissLabel, useUnmergedTree = true)
            .performTouchInput { longClick() }
        rule.waitForIdle()

        assertEquals("holding the X must not dismiss the card", 0, dismissed)
        assertEquals("holding the X must not run the tile action", 0, clicked)
        assertEquals("holding the X must open the explanation", 1, explained)
    }

    @Test fun tapOnTheDismissTarget_stillDismisses() {
        var dismissed = 0
        var explained = 0
        var clicked = 0
        setTile(onLongClick = { explained++ }, onClose = { dismissed++ }, onClick = { clicked++ })

        rule.onNodeWithContentDescription(dismissLabel, useUnmergedTree = true).performClick()
        rule.waitForIdle()

        assertEquals(1, dismissed)
        assertEquals("dismissing must not also run the tile action", 0, clicked)
        assertEquals(0, explained)
    }

    @Test fun longPressOnTheTileBody_explainsInsteadOfActing() {
        var explained = 0
        var clicked = 0
        setTile(onLongClick = { explained++ }, onClose = {}, onClick = { clicked++ })

        rule.onNodeWithText(title).performTouchInput { longClick() }
        rule.waitForIdle()

        assertEquals("a long press must suppress the click", 0, clicked)
        assertEquals(1, explained)
    }
}
