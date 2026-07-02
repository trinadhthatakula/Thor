package com.valhalla.thor.presentation.widgets

import android.content.res.Configuration
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.valhalla.thor.presentation.theme.LocalDarkTheme

@Composable
fun AnimateLottieRaw(
    modifier: Modifier = Modifier,
    @RawRes resId: Int,
    shouldLoop: Boolean = false,
    repeatCount: Int = LottieConstants.IterateForever,
    contentScale: ContentScale = ContentScale.None
) {
    // Resolve the raw resource against a configuration that reflects the IN-APP theme
    // (LocalDarkTheme), so night-qualified variants (res/raw-night) follow the app's
    // ThemeMode instead of the device's night mode. Lottie's RawRes spec loads via
    // LocalContext's resources — which otherwise honor only the device configuration's
    // -night qualifier — and it also keys its composition cache on the context's night
    // mode, so overriding the context here yields the correct day/night variant.
    val darkTheme = LocalDarkTheme.current
    val baseContext = LocalContext.current
    val themedContext = remember(darkTheme, baseContext) {
        val config = Configuration(baseContext.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (darkTheme) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        baseContext.createConfigurationContext(config)
    }

    CompositionLocalProvider(LocalContext provides themedContext) {
        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(resId))
        val progress by animateLottieCompositionAsState(
            composition = composition,
            iterations = if (shouldLoop) repeatCount else 1
        )
        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = modifier,
            contentScale = contentScale
        )
    }
}
