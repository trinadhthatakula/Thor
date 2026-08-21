// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.valhalla.thor.presentation.theme.greenDark

/**
 * A soft coloured wash centred behind whatever it is placed under — Thor's one ambient-light motif.
 *
 * Lifted out of `BiometricScreen`, which is still a caller, when the app-info headers wanted the
 * same glow behind their app icon. One implementation rather than two: this is a look, and a look
 * that exists twice drifts.
 *
 * Three things here are deliberate.
 *
 * **A radial gradient, not a solid circle with a blur.** [Modifier.blur] is a `RenderEffect`, and
 * `RenderEffect` starts at API 31 — on 28-30, which this app supports, the blur is a documented
 * no-op. A solid circle therefore renders as a hard-edged disc on those releases. At the biometric
 * screen's 5% alpha nobody could tell, but this is now also the affordance behind a tappable app
 * icon whose alpha *rises* on press, and a hard disc there reads as a rendering bug rather than a
 * glow. A gradient that fades to transparent is a glow on every supported API, and the blur only
 * softens it further where it exists.
 *
 * **[alpha] and [scale] are lambdas, not values.** Callers animate them, and a `State` read inside
 * the `graphicsLayer` block re-records the layer without recomposing anyone — the same reason
 * `BiometricLockView`'s pulsing ring uses `graphicsLayer { this.alpha = … }` instead of
 * `Modifier.alpha(…)`. Taking `Float`s here would move that read to the call site's composition and
 * hand every caller a header that recomposes every frame.
 *
 * **[requiredSize], not `size`.** The glow is normally larger than the thing it sits behind, so it
 * is usually placed with `Modifier.matchParentSize()` to keep it out of the parent's measurement.
 * That hands it the parent's bounds as constraints, and `size` would quietly clamp [diameter] down
 * to them.
 *
 * @param coreFraction how much of the radius holds [color] at full strength before the fade to
 *   transparent begins, as a fraction of the radius. 0 is a plain two-stop gradient — brightest at
 *   the dead centre, which is right for a wash nothing sits on top of. It is wrong when something
 *   opaque covers the middle, as an app icon covers the middle of its own halo: then the only
 *   visible part of the gradient is its faintest tail, and the glow disappears at any tasteful
 *   alpha. A core pushes the falloff outwards so the visible ring keeps most of the colour.
 */
@Composable
internal fun AmbientGlow(
    modifier: Modifier = Modifier,
    diameter: Dp = 400.dp,
    blurRadius: Dp = 120.dp,
    color: Color = greenDark,
    alpha: () -> Float = { 0.05f },
    scale: () -> Float = { 1f },
    coreFraction: Float = 0f,
) {
    val brush = if (coreFraction > 0f) {
        Brush.radialGradient(
            0f to color,
            coreFraction.coerceAtMost(0.99f) to color,
            1f to Color.Transparent
        )
    } else {
        Brush.radialGradient(listOf(color, Color.Transparent))
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .requiredSize(diameter)
                .graphicsLayer {
                    val currentScale = scale()
                    scaleX = currentScale
                    scaleY = currentScale
                    this.alpha = alpha()
                }
                .blur(blurRadius)
                .background(brush = brush, shape = CircleShape)
        )
    }
}
