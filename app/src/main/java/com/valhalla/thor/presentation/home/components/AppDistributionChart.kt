package com.valhalla.thor.presentation.home.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
private data class ChartSlice(
    val label: String,
    val count: Int,
    val color: Color,
    val sweepAngle: Float
)

@Composable
fun AppDistributionChart(
    data: Map<String, Int>,
    modifier: Modifier = Modifier
) {
    // 1. Prepare Data & Colors (Memoized)
    val baseColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error,
        Color(0xFF8BC34A), // Light Green
        Color(0xFFFFC107), // Amber
        Color(0xFF00BCD4), // Cyan
        Color(0xFF9C27B0), // Purple
        Color(0xFFE91E63), // Pink
        Color(0xFF3F51B5)  // Indigo
    )

    val chartSlices = remember(data) {
        val totalSum = data.values.sum().toFloat()
        val sortedKeys = data.keys.sorted() // Stable order

        sortedKeys.mapIndexed { index, key ->
            val count = data[key] ?: 0
            val sweep = if (totalSum > 0) (360f * count / totalSum) else 0f
            ChartSlice(
                label = key,
                count = count,
                color = baseColors[index % baseColors.size],
                sweepAngle = sweep
            )
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // LEFT: The Visual Chart
        PieChartVisual(
            slices = chartSlices,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(16.dp))

        // RIGHT: The Legend
        ChartLegend(
            slices = chartSlices,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PieChartVisual(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    radiusOuter: Dp = 80.dp,
    chartBarWidth: Dp = 20.dp,
    animDuration: Int = 800
) {
    var animationPlayed by remember { mutableStateOf(false) }

    // PERFORMANCE FIX: Animate Scale (0f -> 1f) instead of Dp Size to avoid re-layout
    val animateScale by animateFloatAsState(
        targetValue = if (animationPlayed) 1f else 0f,
        animationSpec = tween(
            durationMillis = animDuration,
            easing = FastOutSlowInEasing
        ),
        label = "scale"
    )

    // PERFORMANCE FIX: Animate Rotation using graphicsLayer
    val animateRotation by animateFloatAsState(
        targetValue = if (animationPlayed) 360f else 0f,
        animationSpec = tween(
            durationMillis = animDuration,
            easing = FastOutSlowInEasing
        ),
        label = "rotation"
    )

    LaunchedEffect(Unit) {
        animationPlayed = true
    }

    // Container with fixed size to prevent layout thrashing
    Box(
        modifier = modifier.size(radiusOuter * 2),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(radiusOuter * 2)
                .graphicsLayer {
                    // Apply transformations on the GPU (RenderNode)
                    scaleX = animateScale
                    scaleY = animateScale
                    rotationZ = animateRotation
                }
        ) {
            var startAngle = -90f // Start from top
            val arcGap = 2f // Gap between slices in degrees
            slices.forEach { slice ->
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = maxOf(0f, slice.sweepAngle - arcGap), // Ensure small gap
                    useCenter = false,
                    style = Stroke(chartBarWidth.toPx(), cap = StrokeCap.Butt)
                )
                startAngle += slice.sweepAngle
            }
        }
    }
}

@Composable
private fun ChartLegend(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        slices.forEach { slice ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                // Color Dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(color = slice.color, shape = CircleShape)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Name
                Text(
                    text = slice.label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )

                // Count
                Text(
                    text = "(${slice.count})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}