// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
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
 * Four things here are deliberate.
 *
 * **A radial gradient, not a solid circle with a blur.** [Modifier.blur] is a `RenderEffect`, and
 * `RenderEffect` starts at API 31 — on 28-30, which this app supports, the blur is a documented
 * no-op. A solid circle therefore renders as a hard-edged disc on those releases. At the biometric
 * screen's 5% alpha nobody could tell, but this is also the affordance behind a tappable app icon
 * whose alpha *rises* on press, and a hard disc there reads as a rendering bug rather than a glow.
 * The gradient carries the whole fade, so the glow looks the same on every supported API and a blur
 * is optional decoration rather than the mechanism.
 *
 * **The fade is a smoothstep, and the blur does not clip to a rectangle.** A blur is what turns a
 * glow square: [Modifier.blur] defaults to [BlurredEdgeTreatment.Rectangle], which smears the
 * gradient outwards and then clips the result to the node's *rectangular* bounds, so a halo that
 * fades to nothing in the gradient still ends in four straight lines on screen — measured at 13/255
 * right at the node edge against 0 one pixel outside. Hence [BlurredEdgeTreatment.Unbounded] here,
 * and a `smoothstep` alpha ramp rather than a linear one, which has zero slope at both ends: no kink
 * where the fade starts and nothing left to cut off where it ends.
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
 * @param blurRadius extra softening on top of the gradient, or `0.dp` for none. Unbounded, so it is
 *   drawn outside the node's bounds rather than cut off at them — which means an ancestor that clips
 *   can still cut it, and a caller that has a tight clip nearby is better off at 0 and letting the
 *   gradient do the work.
 * @param coreFraction how much of the radius holds [color] at full strength before the fade begins,
 *   as a fraction of the radius. 0 fades from the dead centre, which is right for a wash nothing
 *   sits on top of. It is wrong when something opaque covers the middle, as an app icon covers the
 *   middle of its own halo: then the only visible part of the gradient is its faintest tail, and the
 *   glow disappears at any tasteful alpha. A core pushes the falloff outwards so the visible ring
 *   keeps most of the colour.
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
    val brush = remember(color, coreFraction) {
        Brush.radialGradient(colorStops = glowColorStops(color, coreFraction))
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
                .then(
                    if (blurRadius > 0.dp) {
                        Modifier.blur(blurRadius, BlurredEdgeTreatment.Unbounded)
                    } else {
                        Modifier
                    }
                )
                .background(brush = brush, shape = CircleShape)
        )
    }
}

/**
 * Stops for a glow that holds [color] out to [coreFraction] of the radius and then fades to nothing
 * on a smoothstep curve.
 *
 * Sampled rather than analytic because a gradient brush interpolates linearly between stops — the
 * curve has to be baked into the positions. Eight segments is where the banding stops being
 * measurable at the alphas this is used at.
 *
 * The curve matters at both ends. A linear ramp kinks visibly where the core meets the fade, and it
 * still has slope when it reaches the rim, so anything that cuts the glow there (a blur clipped to
 * bounds, an ancestor clip, a scale that outgrows the reserved space) cuts a *visible* edge.
 * `smoothstep` arrives at zero flat, which leaves nothing to cut.
 */
private fun glowColorStops(color: Color, coreFraction: Float): Array<Pair<Float, Color>> {
    val core = coreFraction.coerceIn(0f, 0.9f)
    val segments = 8
    return Array(segments + 1) { index ->
        val t = index / segments.toFloat()
        // 1 - smoothstep(t): starts at full colour with zero slope, reaches zero with zero slope.
        val fade = 1f - t * t * (3f - 2f * t)
        (core + (1f - core) * t) to color.copy(alpha = color.alpha * fade)
    }
}
