package com.valhalla.thor.presentation.home.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valhalla.thor.R

private data class ChartSlice(
    val label: String,
    val count: Int,
    val color: Color
)

@Composable
fun AppDistributionChart(
    data: Map<String, Int>,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    // 1. Prepare Data & Colors
    val chartSlices = remember(data, colorScheme) {
        val sortedData = data.toList().sortedByDescending { it.second }

        sortedData.mapIndexed { index, (label, count) ->
            val color = when (label.uppercase()) {
                "PLAY STORE" -> Color(0xFFEFFFD7) // Light Green
                "F-DROID" -> Color(0xFFC7CCE1)   // Muted Blue
                "SIDELOADED" -> Color(0xFFC7BFFF) // Muted Purple
                "OTHERS" -> Color(0xFFB14028)    // Muted Red
                else -> {
                    val colors = listOf(
                        colorScheme.primary,
                        colorScheme.secondary,
                        colorScheme.tertiary,
                        colorScheme.error,
                        Color(0xFF8BC34A), // Light Green
                        Color(0xFFFFC107), // Amber
                        Color(0xFF00BCD4), // Cyan
                        Color(0xFF9C27B0)  // Purple
                    )
                    colors[index % colors.size]
                }
            }
            ChartSlice(label, count, color)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // 1. The Horizontal Bar
        DistributionBar(slices = chartSlices)

        // 2. The Legend Grid
        LegendGrid(slices = chartSlices)
    }
}

@Composable
private fun DistributionBar(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier
) {
    val total = slices.sumOf { it.count }.toFloat()
    var startAnimation by remember { mutableFloatStateOf(0f) }

    val animatedProgress by animateFloatAsState(
        targetValue = startAnimation,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "barAnimation"
    )

    LaunchedEffect(Unit) {
        startAnimation = 1f
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black.copy(alpha = 0.5f))
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            slices.forEachIndexed { index, slice ->
                val weight = if (total > 0) slice.count / total else 0f
                val animWeight = weight * animatedProgress
                if (animWeight > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(animWeight)
                            .background(slice.color)
                            .padding(end = if (index < slices.lastIndex) 4.dp else 0.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendGrid(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        slices.chunked(2).forEach { rowSlices ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                rowSlices.forEach { slice ->
                    LegendItem(
                        slice = slice,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowSlices.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LegendItem(
    slice: ChartSlice,
    modifier: Modifier = Modifier
) {
    val localizedLabel = when (slice.label.uppercase()) {
        "PLAY STORE" -> stringResource(R.string.play_store)
        "F-DROID" -> stringResource(R.string.f_droid)
        "SIDELOADED" -> stringResource(R.string.sideloaded)
        "OTHERS" -> stringResource(R.string.others)
        else -> slice.label
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(slice.color, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = localizedLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = slice.count.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}
