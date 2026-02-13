package com.valhalla.thor.presentation.theme

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize

/**
 * Applies the Expressive 'Spatial' spring to layout changes.
 * Use this instead of the standard [animateContentSize].
 */
@Composable
fun Modifier.animateExpressiveResize(): Modifier {
    // Explicitly resolving the spec from the composition local
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    return this.animateContentSize(animationSpec = spatialSpec)
}

/**
 * Adds a physical "squish" scale effect on press.
 * Best for Cards, Boxes, or custom Buttons that need tactile feedback.
 */
fun Modifier.expressivePress(
    interactionSource: MutableInteractionSource,
    scaleOnPress: Float = 0.95f
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()

    // Use 'fastSpatialSpec' for micro-interactions like touches
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleOnPress else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "expressivePressScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}