// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the component disclaimer's "don't ask again this session" option announces.
 *
 * The same defect [SettingsSwitchRow][com.valhalla.thor.presentation.settings.SettingsSwitchRow] was
 * fixed for, on a row written afterwards: `Modifier.clickable` around a `Checkbox` whose own handler
 * is null contributes an on-click action and no *state*, so between the two nodes nothing carries
 * ticked-or-not and a screen reader offers "double tap to activate" over an option whose value it
 * never says.
 *
 * Worth asserting on this row in particular rather than trusting the pattern. It is the one control
 * in Thor that turns *off* a warning — the disclaimer standing between a mis-tap and a component
 * disabled inside an app that goes on looking perfectly healthy. A user who cannot hear the box's
 * state can silence that warning for the session without knowing they did.
 */
@RunWith(AndroidJUnit4::class)
class DontAskAgainRowTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val reported = mutableListOf<Boolean>()

    /** Read off the activity rather than hardcoded, so the eight locales cannot break the lookup. */
    private val label: String
        get() = rule.activity.getString(R.string.component_disclaimer_dont_ask_session)

    private fun setRow(checked: Boolean) = rule.setContent {
        DontAskAgainRow(checked = checked, onCheckedChange = { reported += it })
    }

    private val hasCheckboxRole = SemanticsMatcher.expectValue(
        SemanticsProperties.Role,
        Role.Checkbox
    )

    /** The two facts `clickable` plus a silenced `Checkbox` lost between them: which control, and its state. */
    @Test
    fun theRowAnnouncesItselfAsACheckboxAndSaysItIsTicked() {
        setRow(checked = true)

        rule.onNodeWithText(label).assert(hasCheckboxRole).assertIsOn()
    }

    /** And unticked, so that "on" above is a reading rather than a constant. */
    @Test
    fun anUntickedRowSaysOff() {
        setRow(checked = false)

        rule.onNodeWithText(label).assert(hasCheckboxRole).assertIsOff()
    }

    /**
     * The option is offered once.
     *
     * A live `onCheckedChange` on the `Checkbox` would carry its own toggle semantics, making two
     * toggleable nodes for one option — a screen reader walks past it twice and reads the label on
     * only one of them. The null handler is what prevents that, and this is the assertion that it
     * holds.
     */
    @Test
    fun thereIsExactlyOneControlToFind() {
        setRow(checked = false)

        assertEquals(1, rule.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
    }

    /** Tapping an unticked row asks to silence the disclaimer. */
    @Test
    fun tappingAnUntickedRowAsksForTrue() {
        setRow(checked = false)

        rule.onNodeWithText(label).performClick()

        assertEquals(listOf(true), reported)
    }

    /**
     * And a ticked row asks for **false** — the value the user wants, not the one in force.
     *
     * The direction matters because the caller assigns the reported value straight to its state. An
     * implementation that reported the current value instead would make the box impossible to untick,
     * which on this row means a consent the user tried to withdraw and could not.
     */
    @Test
    fun tappingATickedRowAsksForFalse() {
        setRow(checked = true)

        rule.onNodeWithText(label).performClick()

        assertEquals(listOf(false), reported)
    }

    /**
     * A tap that lands on the box itself still counts once.
     *
     * The null handler stops the `Checkbox` consuming the pointer, so the tap falls through to the
     * row. Give the box a handler back and this is the test that fails, because one tap toggles
     * twice — once in the box, once in the row underneath it — and the option ends where it started.
     */
    @Test
    fun aTapOnTheBoxItselfTogglesOnce() {
        setRow(checked = false)

        rule.onNodeWithText(label).performTouchInput {
            click(percentOffset(0.04f, 0.5f))
        }
        rule.waitForIdle()

        assertEquals(listOf(true), reported)
    }
}
