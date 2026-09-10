// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import java.util.Locale
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
@Config(application = Application::class, sdk = [36], qualifiers = "w800dp-h1280dp")
// Stub font widths cannot detect the truncation these regressions protect against.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsExpandedSwitchRowTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun translatedExplanationsRemainCompleteOnNarrowScreensAtDoubleTextSize() {
        assertLocalizedRowsFit(width = 320.dp, fontScale = 2f)
    }

    @Test
    fun translatedExplanationsRemainCompleteOnLargerScreensAtLargeTextSize() {
        assertLocalizedRowsFit(width = 600.dp, fontScale = 1.5f)
    }

    @Test
    fun expandedRowRemainsOneSwitchAndReportsTheRequestedStateOncePerTap() {
        val checked = mutableStateOf(false)
        val changes = mutableListOf<Boolean>()

        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MaterialTheme {
                    Surface(Modifier.width(320.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                            SettingsExpandedSwitchRow(
                                icon = R.drawable.round_key,
                                title = stringResource(R.string.biometric_lock),
                                subtitle = stringResource(R.string.biometric_lock_desc),
                                checked = checked.value,
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

        rule.onAllNodes(hasSwitchRole).assertCountEquals(1)
        rule.onNode(hasSwitchRole).assertIsEnabled().assertIsOff().performClick()
        rule.onNode(hasSwitchRole).assertIsOn().performClick()
        rule.onNode(hasSwitchRole).assertIsOff()

        val application = ApplicationProvider.getApplicationContext<Application>()
        rule.onNodeWithText(application.getString(R.string.biometric_lock_desc), useUnmergedTree = true)
            .performTouchInput { click() }
        rule.onNode(hasSwitchRole).assertIsOn()
        // The decorative Switch sits at the trailing edge; touching it must reach the row once.
        rule.onNode(hasSwitchRole).performTouchInput { click(percentOffset(0.9f, 0.5f)) }
        rule.onNode(hasSwitchRole).assertIsOff()
        rule.runOnIdle { assertEquals(listOf(true, false, true, false), changes) }
    }

    @Test
    fun disabledExpandedRowDoesNotChangeTheSetting() {
        val changes = mutableListOf<Boolean>()

        rule.setContent {
            MaterialTheme {
                SettingsExpandedSwitchRow(
                    icon = R.drawable.frozen,
                    title = stringResource(R.string.suspend_instead_of_freeze),
                    subtitle = stringResource(R.string.suspend_instead_of_freeze_desc),
                    checked = false,
                    enabled = false,
                    onCheckedChange = { changes += it },
                )
            }
        }

        rule.onAllNodes(hasSwitchRole).assertCountEquals(1)
        rule.onNode(hasSwitchRole).assertIsNotEnabled().assertIsOff().performClick()
        rule.runOnIdle { assertEquals(emptyList<Boolean>(), changes) }
    }

    private fun assertLocalizedRowsFit(width: Dp, fontScale: Float) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val localizedContexts = localeTags.map { tag ->
            val locale = Locale.forLanguageTag(tag)
            val configuration = Configuration(application.resources.configuration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
            }
            application.createConfigurationContext(configuration)
        }
        val selectedContext = mutableStateOf(localizedContexts.first())

        // Reuse one composition per viewport instead of launching a new activity for every row.
        rule.setContent {
            val context = selectedContext.value
            val layoutDirection = if (context.resources.configuration.locales[0].language == "ar") {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalLayoutDirection provides layoutDirection,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                MaterialTheme {
                    Surface(Modifier.width(width).height(600.dp).testTag(VIEWPORT_TAG)) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                            expandedRows.forEach { row ->
                                SettingsExpandedSwitchRow(
                                    icon = row.icon,
                                    title = stringResource(row.title),
                                    subtitle = stringResource(row.subtitle),
                                    checked = false,
                                    onCheckedChange = {},
                                )
                            }
                        }
                    }
                }
            }
        }

        localizedContexts.forEach { context ->
            rule.runOnIdle { selectedContext.value = context }
            val tag = context.resources.configuration.locales[0].toLanguageTag()
            val direction = if (tag == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
            val case = "locale=$tag, width=$width, fontScale=$fontScale"
            rule.onNodeWithTag(VIEWPORT_TAG).assertWidthIsEqualTo(width)

            expandedRows.forEach { row ->
                val title = context.getString(row.title)
                val subtitle = context.getString(row.subtitle)
                rule.onNodeWithText(title, useUnmergedTree = true).assertHasNoTruncation(case, direction)
                rule.onNodeWithText(subtitle, useUnmergedTree = true).assertHasNoTruncation(case, direction)
            }

            // Longer rows may extend beyond the viewport, but their complete text must be reachable.
            rule.onNodeWithText(context.getString(expandedRows.last().subtitle), useUnmergedTree = true)
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    private fun SemanticsNodeInteraction.assertHasNoTruncation(case: String, direction: LayoutDirection) {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action)
        assertTrue(action(layouts))
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        val diagnostic = "$case, text=${layout.layoutInput.text}, size=${layout.size}, " +
            "constraints=${layout.layoutInput.constraints}, lines=${layout.lineCount}, " +
            "paragraphWidth=${layout.multiParagraph.width}, paragraphHeight=${layout.multiParagraph.height}, " +
            "maxIntrinsicWidth=${layout.multiParagraph.maxIntrinsicWidth}, " +
            "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}, " +
            "lineBounds=" + (0 until layout.lineCount).joinToString { line ->
                "[$line: ${layout.getLineLeft(line)}..${layout.getLineRight(line)}, " +
                    "start=${layout.getLineStart(line)}, end=${layout.getLineEnd(line)}, " +
                    "visibleEnd=${layout.getLineEnd(line, visibleEnd = true)}, " +
                    "ellipsized=${layout.isLineEllipsized(line)}]"
            }
        assertEquals(diagnostic, direction, layout.layoutInput.layoutDirection)
        assertFalse(diagnostic, layout.hasVisualOverflow)
        assertFalse(diagnostic, layout.didOverflowWidth)
        assertFalse(diagnostic, layout.didOverflowHeight)
        assertFalse(diagnostic, (0 until layout.lineCount).any(layout::isLineEllipsized))
    }

    private data class ExpandedRow(val icon: Int, val title: Int, val subtitle: Int)

    private companion object {
        const val VIEWPORT_TAG = "expanded-settings-viewport"
        val hasSwitchRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        val localeTags = listOf("en", "ar", "es", "fr", "pl", "pt", "pt-BR", "zh-CN")
        val expandedRows = listOf(
            ExpandedRow(R.drawable.frozen, R.string.auto_freeze, R.string.auto_freeze_desc),
            ExpandedRow(R.drawable.frozen, R.string.suspend_instead_of_freeze, R.string.suspend_instead_of_freeze_desc),
            ExpandedRow(R.drawable.danger, R.string.skip_routine_freeze_confirmation, R.string.skip_routine_freeze_confirmation_desc),
            ExpandedRow(R.drawable.frozen, R.string.add_freezer_to_launcher, R.string.add_freezer_to_launcher_desc),
            ExpandedRow(R.drawable.round_key, R.string.biometric_lock, R.string.biometric_lock_desc),
        )
    }
}
