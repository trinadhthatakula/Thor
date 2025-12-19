package com.valhalla.thor.presentation.home.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

@Composable
fun AnimatedCounter(
    modifier: Modifier = Modifier,
    count: Int,
    style: TextStyle = MaterialTheme.typography.displaySmall
) {
    // This animates the value from current (0) to target (count) over 1 second
    val animatedValue by animateIntAsState(
        targetValue = count,
        animationSpec = tween(
            durationMillis = 1000,
            easing = FastOutSlowInEasing
        ),
        label = "CounterAnimation"
    )

    Text(
        text = animatedValue.toString(),
        style = style,
        modifier = modifier
    )
}