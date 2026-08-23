// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.percentOffset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What [SettingsSwitchRow] promises the twelve settings that use it.
 *
 * Two of those promises were being broken at once, in opposite directions, and neither was visible
 * from reading a call site. The row announced itself as a *button* — `clickable`, no state — while
 * the `Switch` beside it had its semantics cleared so the setting would not be offered twice, so
 * between them nothing carried on or off and a screen reader user could change any toggle in
 * Settings without ever hearing what it was set to. Meanwhile the Usage Access and Notification
 * Access handlers both read the row as reporting "the state I am now in" and guarded on the
 * permission still being missing, which made both rows dead once granted.
 *
 * So: the row is one control, it says which control it is, it says what it is set to, and it
 * reports the value the user asked for rather than the one already in force.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSwitchRowTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val title = "Auto freeze"
    private val subtitle = "Freeze apps on a schedule"

    private var reported = mutableListOf<Boolean>()

    private fun setRow(checked: Boolean, enabled: Boolean = true) = rule.setContent {
        SettingsSwitchRow(
            icon = R.drawable.frozen,
            title = title,
            subtitle = subtitle,
            checked = checked,
            enabled = enabled,
            onCheckedChange = { reported += it }
        )
    }

    private val hasSwitchRole = SemanticsMatcher.expectValue(
        SemanticsProperties.Role,
        Role.Switch
    )

    /**
     * The row is a switch and it is on — the two facts the old markup lost between the `clickable`
     * that had no state and the `clearAndSetSemantics` that threw the state away.
     */
    @Test fun theRowAnnouncesItselfAsASwitchAndSaysWhatItIsSetTo() {
        setRow(checked = true)

        rule.onNodeWithText(title).assert(hasSwitchRole).assertIsOn()
        rule.onNodeWithText(subtitle).assertIsOn()
    }

    /** And off, so that "on" above is a reading rather than a constant. */
    @Test fun anUncheckedRowSaysOff() {
        setRow(checked = false)

        rule.onNodeWithText(title).assert(hasSwitchRole).assertIsOff()
    }

    /**
     * The setting is offered once.
     *
     * The `Switch` used to carry its own toggle semantics, which is why it was silenced by hand; a
     * null handler is what silences it now, and this is the assertion that the substitution held.
     * Two toggleable nodes means a screen reader walks past the same setting twice and reads the
     * title on only one of them.
     */
    @Test fun thereIsExactlyOneControlToFind() {
        setRow(checked = true)

        assertEquals(
            1,
            rule.onAllNodes(isToggleable()).fetchSemanticsNodes().size
        )
    }

    /**
     * The finding, as an assertion: a checked row reports **false**.
     *
     * `onCheckedChange` carries the value the user is asking for, not the one already in force.
     * Usage Access and Notification Access both read it the other way and guarded on the permission
     * being absent, so switching either of them off called the handler and the handler returned —
     * the switch snapped back and nothing opened. Any implementation that reports the current value
     * makes that guard look correct again.
     */
    @Test fun tappingACheckedRowAsksForFalse() {
        setRow(checked = true)

        rule.onNodeWithText(title).performClick()

        assertEquals(listOf(false), reported)
    }

    /** The other direction, for completeness — an unchecked row asks for true. */
    @Test fun tappingAnUncheckedRowAsksForTrue() {
        setRow(checked = false)

        rule.onNodeWithText(title).performClick()

        assertEquals(listOf(true), reported)
    }

    /**
     * A tap that lands on the switch itself still counts once.
     *
     * The `Switch` takes a null handler so the row owns the gesture, which also stops the Switch
     * consuming the pointer — the tap falls through to the row. Give the Switch a handler again and
     * this is the test that fails, because the setting toggles twice from one tap: once in the
     * Switch, once in the row underneath it.
     */
    @Test fun aTapOnTheSwitchItselfTogglesOnce() {
        setRow(checked = false)

        rule.onNodeWithText(title).performTouchInput {
            click(percentOffset(0.92f, 0.5f))
        }
        rule.waitForIdle()

        assertEquals(listOf(true), reported)
    }

    /** A disabled row is inert and says so, rather than looking live and swallowing the tap. */
    @Test fun aDisabledRowReportsNothing() {
        setRow(checked = false, enabled = false)

        rule.onNodeWithText(title).assertIsNotEnabled()
        rule.onNodeWithText(title).performClick()

        assertEquals(emptyList<Boolean>(), reported)
    }
}
