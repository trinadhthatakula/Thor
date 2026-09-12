// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.FontPreset
import androidx.compose.ui.text.font.Font as ResFont

val firaMonoFontFamily = FontFamily(
    ResFont(resId = R.font.firacode_variable)
)

val bodyFontFamily = FontFamily(
    ResFont(resId = R.font.outfit_regular, weight = FontWeight.Normal, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_black, weight = FontWeight.Black, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_bold, weight = FontWeight.Bold, style = FontStyle.Normal),
    ResFont(
        resId = R.font.outfit_extrabold,
        weight = FontWeight.ExtraBold,
        style = FontStyle.Normal
    ),
    ResFont(
        resId = R.font.outfit_extralight,
        weight = FontWeight.ExtraLight,
        style = FontStyle.Normal
    ),
    ResFont(resId = R.font.outfit_light, weight = FontWeight.Light, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_medium, weight = FontWeight.Medium, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_semibold, weight = FontWeight.SemiBold, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_thin, weight = FontWeight.Thin, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_regular, weight = FontWeight.Normal, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_black, weight = FontWeight.Black, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_bold, weight = FontWeight.Bold, style = FontStyle.Italic),
    ResFont(
        resId = R.font.outfit_extrabold,
        weight = FontWeight.ExtraBold,
        style = FontStyle.Italic
    ),
    ResFont(
        resId = R.font.outfit_extralight,
        weight = FontWeight.ExtraLight,
        style = FontStyle.Italic
    ),
    ResFont(resId = R.font.outfit_light, weight = FontWeight.Light, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_medium, weight = FontWeight.Medium, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_semibold, weight = FontWeight.SemiBold, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_thin, weight = FontWeight.Thin, style = FontStyle.Italic),
)

/** Families are resolved by role so a future role override can inherit the selected preset. */
@Immutable
internal data class AppFontFamilies(
    val headings: FontFamily,
    val body: FontFamily,
    val labels: FontFamily,
    val technical: FontFamily,
)

private val asgardFontFamilies = AppFontFamilies(
    headings = bodyFontFamily,
    body = bodyFontFamily,
    labels = firaMonoFontFamily,
    technical = firaMonoFontFamily,
)

private val systemFontFamilies = AppFontFamilies(
    headings = FontFamily.Default,
    body = FontFamily.Default,
    labels = FontFamily.Default,
    technical = FontFamily.Monospace,
)

internal fun fontFamiliesFor(preset: FontPreset): AppFontFamilies = when (preset) {
    FontPreset.ASGARD -> asgardFontFamilies
    FontPreset.SYSTEM -> systemFontFamilies
}

/** Fixed-width text for logs and technical identifiers, independent of ordinary UI labels. */
val LocalTechnicalFontFamily = staticCompositionLocalOf { firaMonoFontFamily }

// Default Material 3 typography values
private val baseline = Typography()

val AppTypography = createAppTypography(asgardFontFamilies)
private val systemTypography = createAppTypography(systemFontFamilies)

internal fun typographyFor(preset: FontPreset): Typography = when (preset) {
    FontPreset.ASGARD -> AppTypography
    FontPreset.SYSTEM -> systemTypography
}

// Construct both presets with the same metrics. This constructor also carries each family
// into its emphasized counterpart; copying only the 15 ordinary styles would leave those behind.
private fun createAppTypography(families: AppFontFamilies) = Typography(
    displayLarge = baseline.displayLarge.copy(
        fontFamily = families.headings,
        fontWeight = FontWeight.Black
    ),
    displayMedium = baseline.displayMedium.copy(
        fontFamily = families.headings,
        fontWeight = FontWeight.ExtraBold
    ),
    displaySmall = baseline.displaySmall.copy(
        fontFamily = families.headings,
        fontWeight = FontWeight.Bold
    ),
    headlineLarge = baseline.headlineLarge.copy(
        fontFamily = families.headings,
        fontWeight = FontWeight.Bold
    ),
    headlineMedium = baseline.headlineMedium.copy(
        fontFamily = families.headings,
        fontWeight = FontWeight.Bold
    ),
    headlineSmall = baseline.headlineSmall.copy(
        fontFamily = families.headings,
        fontWeight = FontWeight.Medium
    ),
    titleLarge = baseline.titleLarge.copy(
        fontFamily = families.headings,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = baseline.titleMedium.copy(
        fontFamily = families.headings,
        fontWeight = FontWeight.Medium
    ),
    titleSmall = baseline.titleSmall.copy(
        fontFamily = families.headings,
        fontWeight = FontWeight.Normal
    ),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = families.body),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = families.body),
    bodySmall = baseline.bodySmall.copy(fontFamily = families.body),
    labelLarge = baseline.labelLarge.copy(
        fontFamily = families.labels,
        fontWeight = FontWeight.Medium
    ),
    labelMedium = baseline.labelMedium.copy(
        fontFamily = families.labels,
        fontWeight = FontWeight.Normal
    ),
    labelSmall = baseline.labelSmall.copy(
        fontFamily = families.labels,
        fontWeight = FontWeight.Normal
    ),
)
