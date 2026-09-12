// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.theme

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.valhalla.thor.domain.model.FontPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class FontTypographyTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun systemFontsReachEveryRoleIncludingEmphasizedStylesWithoutChangingMetrics() {
        val current = typographyFor(FontPreset.ASGARD).allStyles()
        val system = typographyFor(FontPreset.SYSTEM).allStyles()

        current.zip(system).forEachIndexed { index, (asgardStyle, systemStyle) ->
            assertEquals("role $index", FontFamily.Default, systemStyle.fontFamily)
            assertEquals(
                "Only the family should change for role $index",
                asgardStyle.copy(fontFamily = FontFamily.Default),
                systemStyle,
            )
        }
    }

    @Test
    fun asgardKeepsOutfitForReadingAndFiraCodeForLabelsAndTechnicalText() {
        val styles = typographyFor(FontPreset.ASGARD).allStyles()
        // Each half is the same 15 roles: ordinary styles, then their emphasized counterparts.
        styles.chunked(15).forEach { roles ->
            roles.take(12).forEach { assertEquals(bodyFontFamily, it.fontFamily) }
            roles.takeLast(3).forEach { assertEquals(firaMonoFontFamily, it.fontFamily) }
        }
        assertEquals(firaMonoFontFamily, fontFamiliesFor(FontPreset.ASGARD).technical)
        assertEquals(FontFamily.Monospace, fontFamiliesFor(FontPreset.SYSTEM).technical)
    }

    @Test
    fun switchingAndSwitchingBackUpdatesTheThemeWithoutRemountingContent() {
        val preset = mutableStateOf(FontPreset.ASGARD)
        var observedTypography: Typography? = null
        var observedTechnicalFamily: FontFamily? = null
        var contentIdentity: Any? = null

        rule.setContent {
            ThorTheme(fontPreset = preset.value) {
                val typography = MaterialTheme.typography
                val technicalFamily = LocalTechnicalFontFamily.current
                val identity = remember { Any() }
                SideEffect {
                    observedTypography = typography
                    observedTechnicalFamily = technicalFamily
                    contentIdentity = identity
                }
            }
        }

        var originalIdentity: Any? = null
        rule.runOnIdle {
            originalIdentity = contentIdentity
            assertEquals(typographyFor(FontPreset.ASGARD), observedTypography)
            assertEquals(firaMonoFontFamily, observedTechnicalFamily)
            preset.value = FontPreset.SYSTEM
        }
        rule.runOnIdle {
            assertEquals(typographyFor(FontPreset.SYSTEM), observedTypography)
            assertEquals(FontFamily.Monospace, observedTechnicalFamily)
            assertSame(originalIdentity, contentIdentity)
            preset.value = FontPreset.ASGARD
        }
        rule.runOnIdle {
            assertEquals(typographyFor(FontPreset.ASGARD), observedTypography)
            assertEquals(firaMonoFontFamily, observedTechnicalFamily)
            assertSame(originalIdentity, contentIdentity)
        }
    }

    private fun Typography.allStyles(): List<TextStyle> = listOf(
        displayLarge, displayMedium, displaySmall,
        headlineLarge, headlineMedium, headlineSmall,
        titleLarge, titleMedium, titleSmall,
        bodyLarge, bodyMedium, bodySmall,
        labelLarge, labelMedium, labelSmall,
        displayLargeEmphasized, displayMediumEmphasized, displaySmallEmphasized,
        headlineLargeEmphasized, headlineMediumEmphasized, headlineSmallEmphasized,
        titleLargeEmphasized, titleMediumEmphasized, titleSmallEmphasized,
        bodyLargeEmphasized, bodyMediumEmphasized, bodySmallEmphasized,
        labelLargeEmphasized, labelMediumEmphasized, labelSmallEmphasized,
    )
}
