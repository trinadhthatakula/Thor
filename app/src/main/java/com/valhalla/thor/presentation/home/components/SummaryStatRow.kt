package com.valhalla.thor.presentation.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R

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
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(
            title = "Active",
            count = activeCount,
            icon = R.drawable.apps,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.weight(1f),
            onClick = onActiveClick
        )
        StatCard(
            title = "Frozen",
            count = frozenCount,
            icon = R.drawable.frozen,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.weight(1f),
            onClick = onFrozenClick
        )
    }
}

@Composable
fun StatCard(
    title: String,
    count: Int,
    icon: Int,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = color),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(painterResource(icon), null)
            Spacer(Modifier.height(8.dp))

            // USE THE ANIMATED COUNTER HERE
            AnimatedCounter(
                count = count,
                style = MaterialTheme.typography.displaySmall
            )

            Text(text = title, style = MaterialTheme.typography.bodyMedium)
        }
    }
}