package com.valhalla.thor.presentation.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valhalla.thor.presentation.home.AppDestinations
import com.valhalla.thor.presentation.theme.animateExpressiveResize
import com.valhalla.thor.presentation.theme.expressivePress

@Composable
fun ThorNavigationRail(
    destinations: List<AppDestinations>,
    selectedDestination: AppDestinations,
    onDestinationSelected: (AppDestinations) -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .animateContentSize()
            .clip(RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp)),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .safeDrawingPadding()
                .padding(vertical = 24.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            destinations.forEach { destination ->
                ThorNavigationRailItem(
                    destination = destination,
                    selected = destination == selectedDestination,
                    onClick = { onDestinationSelected(destination) },
                    showLabel = showLabel
                )
            }
        }
    }
}

@Composable
private fun ThorNavigationRailItem(
    destination: AppDestinations,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }

    val containerColorSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val contentColorSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val alphaEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = containerColorSpec,
        label = "containerColor"
    )

    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = contentColorSpec,
        label = "contentColor"
    )

    val contentAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.7f,
        animationSpec = alphaEffectsSpec,
        label = "contentAlpha"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .expressivePress(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .animateExpressiveResize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.graphicsLayer { alpha = contentAlpha },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(if (selected) destination.selectedIcon else destination.icon),
                contentDescription = stringResource(destination.contentDescription),
                tint = contentColor
            )

            if (showLabel) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(destination.label),
                    color = contentColor,
                    style = if (selected) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}
