// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
// Real font metrics are required for overflow assertions; legacy graphics uses stub text widths.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegacyInstallSettingRowTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun legacyInstallSettingKeepsItsFullWarningAtLargeTextAndIsOneEnabledSwitch() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val title = context.getString(R.string.allow_legacy_apk_install)
        val subtitle = context.getString(R.string.allow_legacy_apk_install_desc)
        val checked = mutableStateOf(false)
        val changes = mutableListOf<Boolean>()

        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MaterialTheme {
                    Surface(Modifier.width(320.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            SettingsSwitchRow(
                                icon = R.drawable.danger,
                                title = title,
                                subtitle = subtitle,
                                checked = checked.value,
                                titleMaxLines = Int.MAX_VALUE,
                                subtitleMaxLines = Int.MAX_VALUE,
                                onCheckedChange = {
                                    changes += it
                                    checked.value = it
                                },
                            )
                        }
                    }
                }
            }
        }

        val titleNode = rule.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
        val subtitleNode = rule.onNodeWithText(subtitle, useUnmergedTree = true).assertIsDisplayed()
        titleNode.assertHasNoTruncation()
        subtitleNode.assertHasNoTruncation()

        val hasSwitchRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        rule.onAllNodes(hasSwitchRole).assertCountEquals(1)
        rule.onNode(hasSwitchRole).assertIsEnabled().assertIsOff().performClick()
        rule.onNode(hasSwitchRole).assertIsOn()
        rule.runOnIdle { assertEquals(listOf(true), changes) }
    }

    private fun SemanticsNodeInteraction.assertHasNoTruncation() {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(
            fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        )

        assertTrue(action(layouts))
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        val diagnostic = "text=${layout.layoutInput.text}, size=${layout.size}, " +
            "constraints=${layout.layoutInput.constraints}, lines=${layout.lineCount}, " +
            "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}"
        assertFalse(diagnostic, layout.hasVisualOverflow)
        assertFalse(layout.didOverflowWidth)
        assertFalse(layout.didOverflowHeight)
        assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
    }
}
