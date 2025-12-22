package com.valhalla.thor.presentation.home.components

import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AppDistributionChart(
    data: Map<String, Int>,
    modifier: Modifier = Modifier
) {
    // 1. Prepare Data & Colors
    val totalSum = data.values.sum()
    val keys = data.keys.toList()

    // Generate a consistent color palette based on your theme
    // We use a predefined list to avoid "random" colors changing on recomposition
    val baseColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error,
        Color(0xFF8BC34A), // Light Green
        Color(0xFFFFC107), // Amber
        Color(0xFF00BCD4), // Cyan
        Color(0xFF9C27B0)  // Purple
    )

    // Assign colors to data points (cycle through if more data than colors)
    val colorMap = keys.mapIndexed { index, key ->
        key to baseColors[index % baseColors.size]
    }.toMap()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // LEFT: The Visual Chart
        PieChartVisual(
            data = data,
            totalSum = totalSum,
            colors = keys.map { colorMap[it]!! },
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(16.dp))

        // RIGHT: The Legend
        ChartLegend(
            data = data,
            colorMap = colorMap,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PieChartVisual(
    data: Map<String, Int>,
    totalSum: Int,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    radiusOuter: Dp = 80.dp,
    chartBarWidth: Dp = 20.dp,
    animDuration: Int = 1000
) {
    // Calculate angles
    val floatValues = data.values.map { 360 * it.toFloat() / totalSum.toFloat() }
    var lastValue = 0f

    // Animation States
    var animationPlayed by remember { mutableStateOf(false) }

    val animateSize by animateFloatAsState(
        targetValue = if (animationPlayed) radiusOuter.value * 2f else 0f,
        animationSpec = tween(
            durationMillis = animDuration,
            delayMillis = 0,
            easing = LinearOutSlowInEasing
        ), label = "size"
    )

    val animateRotation by animateFloatAsState(
        targetValue = if (animationPlayed) 90f * 11f else 0f,
        animationSpec = tween(
            durationMillis = animDuration,
            delayMillis = 0,
            easing = LinearOutSlowInEasing
        ), label = "rotation"
    )

    LaunchedEffect(Unit) {
        animationPlayed = true
    }

    Box(
        modifier = modifier.size(animateSize.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(radiusOuter * 2)
                .rotate(animateRotation)
        ) {
            floatValues.forEachIndexed { index, value ->
                drawArc(
                    color = colors[index],
                    startAngle = lastValue,
                    sweepAngle = value - 2f, // Small gap between arcs
                    useCenter = false,
                    style = Stroke(chartBarWidth.toPx(), cap = StrokeCap.Butt)
                )
                lastValue += value
            }
        }
    }
}

@Composable
private fun ChartLegend(
    data: Map<String, Int>,
    colorMap: Map<String, Color>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        data.forEach { (key, count) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                // Color Dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(color = colorMap[key]!!, shape = CircleShape)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Name
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )

                // Count
                Text(
                    text = "($count)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}