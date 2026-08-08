// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valhalla.thor.domain.model.Installers
import com.valhalla.thor.presentation.home.InstallerSlice
import com.valhalla.thor.util.UiText

private data class ChartSlice(
    val installerPackageName: String?,
    val label: UiText,
    val count: Int,
    val color: Color
)

/**
 * The installation-source breakdown on Home.
 *
 * [onInstallerClick] fires for a bar that names one installer, and is how the chart stopped being
 * read-only: seeing that 40 apps came from somewhere is only half an answer if there is no way to
 * ask which ones. Others names several installers at once, so it reports but does not navigate.
 */
@Composable
fun AppDistributionChart(
    slices: List<InstallerSlice>,
    onInstallerClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    // 1. Prepare Data & Colors
    val chartSlices = remember(slices, colorScheme) {
        slices.sortedByDescending { it.count }.mapIndexed { index, slice ->
            val installer = slice.installerPackageName
            // Keyed on the installer id rather than the display label, so a translated or
            // device-supplied name can never silently move a bar to a different colour.
            val color = when {
                installer == null -> colorScheme.error
                installer == Installers.PLAY_STORE -> colorScheme.primary
                installer == Installers.F_DROID -> colorScheme.secondary
                installer in Installers.PACKAGE_INSTALLERS -> colorScheme.tertiary
                else -> {
                    val colors = listOf(
                        colorScheme.primary,
                        colorScheme.secondary,
                        colorScheme.tertiary,
                        colorScheme.error,
                        colorScheme.outline,
                        colorScheme.inversePrimary
                    )
                    colors[index % colors.size]
                }
            }
            ChartSlice(installer, slice.label, slice.count, color)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // 1. The Horizontal Bar
        DistributionBar(slices = chartSlices)

        // 2. The Legend Grid
        LegendGrid(slices = chartSlices, onInstallerClick = onInstallerClick)
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
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
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
    onInstallerClick: (String) -> Unit,
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
                        onInstallerClick = onInstallerClick,
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
    onInstallerClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val installer = slice.installerPackageName

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            // Others stands for several installers at once and the Apps tab filters on exactly one,
            // so that entry stays a label. Anything that names an installer drills through to it.
            .then(
                if (installer == null) Modifier
                else Modifier.clickable { onInstallerClick(installer) }
            )
            .padding(4.dp),
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
                text = slice.label.asString().uppercase(),
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
