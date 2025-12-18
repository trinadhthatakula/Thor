package com.valhalla.thor.presentation.home.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R

@Composable
fun DashboardHeader(
    isRoot: Boolean,
    isShizuku: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App Icon
        Icon(
            painter = painterResource(R.drawable.thor_mono),
            contentDescription = null,
            modifier = Modifier.size(48.dp)
        )

        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                text = "Thor",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "System Manager",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Status Chip
        StatusChip(isRoot, isShizuku)
    }
}

@Composable
fun StatusChip(isRoot: Boolean, isShizuku: Boolean) {
    val (icon, color, text) = when {
        isRoot -> Triple(R.drawable.magisk_icon, Color.Green, "Rooted")
        isShizuku -> Triple(R.drawable.shizuku, Color.Cyan, "Shizuku")
        else -> Triple(R.drawable.round_close, MaterialTheme.colorScheme.error, "Restricted")
    }

    SuggestionChip(
        onClick = { /* Show details dialog */ },
        label = { Text(text) },
        icon = {
            Icon(
                painterResource(icon),
                null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
        },
        border = null
    )
}