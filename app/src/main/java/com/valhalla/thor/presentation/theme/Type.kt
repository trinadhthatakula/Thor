package com.valhalla.thor.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.valhalla.thor.R
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

val displayFontFamily = bodyFontFamily

// Default Material 3 typography values
val baseline = Typography()

val AppTypography = Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.Black),
    displayMedium = baseline.displayMedium.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.ExtraBold),
    displaySmall = baseline.displaySmall.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold),
    headlineLarge = baseline.headlineLarge.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold),
    headlineMedium = baseline.headlineMedium.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.Medium),
    titleLarge = baseline.titleLarge.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.SemiBold),
    titleMedium = baseline.titleMedium.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.Medium),
    titleSmall = baseline.titleSmall.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.Normal),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = bodyFontFamily),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = bodyFontFamily),
    bodySmall = baseline.bodySmall.copy(fontFamily = bodyFontFamily),
    labelLarge = baseline.labelLarge.copy(fontFamily = firaMonoFontFamily, fontWeight = FontWeight.Medium),
    labelMedium = baseline.labelMedium.copy(fontFamily = firaMonoFontFamily, fontWeight = FontWeight.Normal),
    labelSmall = baseline.labelSmall.copy(fontFamily = firaMonoFontFamily, fontWeight = FontWeight.Normal),
)