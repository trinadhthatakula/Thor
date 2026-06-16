package com.valhalla.thor.presentation.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import com.valhalla.thor.presentation.theme.animateExpressiveResize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import com.valhalla.thor.presentation.theme.expressivePress

@Composable
fun ThorNavigationBar(
    destinations: List<AppDestinations>,
    selectedDestination: AppDestinations,
    onDestinationSelected: (AppDestinations) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .height(64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            destinations.forEach { destination ->
                ThorNavigationBarItem(
                    destination = destination,
                    selected = destination == selectedDestination,
                    onClick = { onDestinationSelected(destination) }
                )
            }
        }
    }
}

@Composable
private fun ThorNavigationBarItem(
    destination: AppDestinations,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    val containerColorSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val contentColorSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val alphaEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntSize>()

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
            .clip(RoundedCornerShape(32.dp))
            .background(containerColor)
            .expressivePress(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .animateExpressiveResize()
            .padding(horizontal = if (selected) 20.dp else 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.graphicsLayer { alpha = contentAlpha },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(if (selected) destination.selectedIcon else destination.icon),
                contentDescription = stringResource(destination.contentDescription),
                tint = contentColor
            )

            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(animationSpec = alphaEffectsSpec) +
                        expandHorizontally(
                            animationSpec = spatialSpec
                        ),
                exit = fadeOut(animationSpec = alphaEffectsSpec) +
                        shrinkHorizontally(
                            animationSpec = spatialSpec
                        )
            ) {
                Text(
                    text = stringResource(destination.label),
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1
                )
            }
        }
    }
}
