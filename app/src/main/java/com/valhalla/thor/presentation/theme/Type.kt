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
    ResFont(resId = R.font.outfit_extrabold, weight = FontWeight.ExtraBold, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_extralight, weight = FontWeight.ExtraLight, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_light, weight = FontWeight.Light, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_medium, weight = FontWeight.Medium, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_semibold, weight = FontWeight.SemiBold, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_thin, weight = FontWeight.Thin, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_regular, weight = FontWeight.Normal, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_black, weight = FontWeight.Black, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_bold, weight = FontWeight.Bold, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_extrabold, weight = FontWeight.ExtraBold, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_extralight, weight = FontWeight.ExtraLight, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_light, weight = FontWeight.Light, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_medium, weight = FontWeight.Medium, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_semibold, weight = FontWeight.SemiBold, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_thin, weight = FontWeight.Thin, style = FontStyle.Italic),
)

val displayFontFamily = bodyFontFamily

// Default Material 3 typography values
val baseline = Typography()

val AppTypography = Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = displayFontFamily),
    displayMedium = baseline.displayMedium.copy(fontFamily = displayFontFamily),
    displaySmall = baseline.displaySmall.copy(fontFamily = displayFontFamily),
    headlineLarge = baseline.headlineLarge.copy(fontFamily = displayFontFamily),
    headlineMedium = baseline.headlineMedium.copy(fontFamily = displayFontFamily),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = displayFontFamily),
    titleLarge = baseline.titleLarge.copy(fontFamily = displayFontFamily),
    titleMedium = baseline.titleMedium.copy(fontFamily = displayFontFamily),
    titleSmall = baseline.titleSmall.copy(fontFamily = displayFontFamily),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = bodyFontFamily),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = bodyFontFamily),
    bodySmall = baseline.bodySmall.copy(fontFamily = bodyFontFamily),
    labelLarge = baseline.labelLarge.copy(fontFamily = bodyFontFamily),
    labelMedium = baseline.labelMedium.copy(fontFamily = bodyFontFamily),
    labelSmall = baseline.labelSmall.copy(fontFamily = bodyFontFamily),
)