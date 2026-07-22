// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.security

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valhalla.thor.R
import com.valhalla.thor.presentation.theme.greenDark

@Composable
fun BiometricScreen(
    isError: Boolean,
    errorMessage: String,
    onAuthenticated: () -> Unit,
    onError: (String) -> Unit,
    onRetry: () -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val handler = remember { BiometricPromptHandler(context) }

    // Clean up on dispose
    androidx.compose.runtime.DisposableEffect(handler) {
        onDispose { handler.cancel() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 1. Ambient Glow
        AmbientGlow()

        if (isError) {
            BiometricErrorView(
                message = errorMessage,
                onRetry = onRetry,
                onExit = onExit
            )
        } else {
            BiometricLockView(
                onAuthenticated = onAuthenticated,
                onError = onError,
                handler = handler
            )
        }
    }
}

@Composable
private fun AmbientGlow() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(400.dp)
                .alpha(0.05f)
                .blur(120.dp)
                .background(greenDark, CircleShape)
        )
    }
}

@Composable
private fun BiometricLockView(
    onAuthenticated: () -> Unit,
    onError: (String) -> Unit,
    handler: BiometricPromptHandler
) {
    val unlockTitle = stringResource(R.string.biometric_unlock_title)
    val unlockSubtitle = stringResource(R.string.biometric_unlock_subtitle)
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo Section
        Box(contentAlignment = Alignment.Center) {
            // Identity Ring (Pulsing)
            val infiniteTransition = rememberInfiniteTransition(label = "ring")
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )

            Box(
                modifier = Modifier
                    .size(110.dp)
                    .alpha(pulseAlpha)
                    .background(Color.Transparent, CircleShape)
                    .padding(2.dp)
                    .background(greenDark.copy(alpha = 0.2f), CircleShape)
            )

            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.thor_mono),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Typography Header
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
            letterSpacing = (-2).sp
        )
        Text(
            text = stringResource(R.string.unlock_to_continue),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(64.dp))

        // Fingerprint Button
        Box(contentAlignment = Alignment.Center) {
            // Pulsing Background
            val infiniteTransition = rememberInfiniteTransition(label = "fingerprint")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )

            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(scale)
                    .alpha(0.1f)
                    .background(greenDark, RoundedCornerShape(24.dp))
            )

            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(greenDark, greenDark.copy(alpha = 0.8f))
                        )
                    )
                    .clickable {
                        handler.authenticate(
                            title = unlockTitle,
                            subtitle = unlockSubtitle,
                            onAuthenticated = onAuthenticated,
                            onError = onError
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.round_key), // Fingerprint icon fallback
                    contentDescription = stringResource(R.string.unlock),
                    modifier = Modifier.size(48.dp),
                    tint = Color.Black
                )
            }
        }
    }

    // Auto-trigger on first launch
    LaunchedEffect(Unit) {
        handler.authenticate(
            title = unlockTitle,
            subtitle = unlockSubtitle,
            onAuthenticated = onAuthenticated,
            onError = onError
        )
    }
}

@Composable
private fun BiometricErrorView(
    message: String,
    onRetry: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.danger),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.auth_failed),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 48.dp)
        )

        Spacer(modifier = Modifier.height(48.dp))

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer)
                .clickable { onRetry() }
                .padding(horizontal = 32.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.try_again).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.exit).uppercase(),
            modifier = Modifier
                .clickable { onExit() }
                .padding(16.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
