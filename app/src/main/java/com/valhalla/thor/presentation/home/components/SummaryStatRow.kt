package com.valhalla.thor.presentation.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SummaryStatRow(
    activeCount: Int,
    frozenCount: Int,
    onActiveClick: () -> Unit,
    onFrozenClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            title = "Active",
            count = activeCount,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            onClick = onActiveClick
        )
        StatCard(
            title = "Frozen",
            count = frozenCount,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
            onClick = onFrozenClick
        )
    }
}

@Composable
fun StatCard(
    title: String,
    count: Int,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp)) // squircle-lg approx
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { onClick() }
            .padding(24.dp)
    ) {
        // USE THE ANIMATED COUNTER HERE
        AnimatedCounter(
            count = count,
            style = MaterialTheme.typography.displayMedium.copy(
                color = color,
                fontWeight = FontWeight.ExtraBold
            )
        )

        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified // tracking-wider
        )
    }
}

