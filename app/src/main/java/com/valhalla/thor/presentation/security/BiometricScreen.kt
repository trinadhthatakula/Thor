// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.security

import android.os.Build
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valhalla.thor.R
import com.valhalla.thor.presentation.theme.greenDark
import com.valhalla.thor.presentation.widgets.AmbientGlow

/**
 * How much of this screen's wash holds its colour before the fade starts.
 *
 * The glow here used to be a solid 400 dp disc at 5 % alpha with a 120 dp blur over it, and became a
 * shared widget when the app-info headers wanted the same motif. That widget carries the fade in a
 * gradient rather than in the blur, so that it also works on API 28-30, where `Modifier.blur` does
 * nothing — but a gradient fading from the dead centre spreads about a third of the ink a solid disc
 * does, and this screen is a near-black background where a third of 5 % is nothing at all. A core
 * restores the disc: full colour out to 65 % of the radius, then the fade. Measured against the old
 * profile that is within a few percent everywhere out to r = 160 dp, and past that it goes to zero
 * instead of being cut off square at the node edge, which is what the old blur did.
 */
private const val AMBIENT_WASH_CORE = 0.65f

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
        AmbientGlow(Modifier.fillMaxSize(), coreFraction = AMBIENT_WASH_CORE)

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

/**
 * Shown when the app lock is on but this device has nothing enrolled that the prompt would accept,
 * so no prompt could ever succeed.
 *
 * The lock is **not** lifted here. This screen exists because the alternative was a closed loop:
 * the prompt failed instantly, the error screen offered TRY AGAIN, and TRY AGAIN re-armed the same
 * prompt — with `finish()` the only other control, and Settings (where the switch that turns the
 * lock off lives) unreachable because MainScreen was never composed. Enrolling something the prompt
 * accepts is the one exit that was always available and never mentioned; this says so and opens the
 * right page.
 *
 * The copy is chosen by what the prompt on *this* API level accepts, not by what a modern device
 * would accept. Android 9's framework prompt has no device-credential path, so telling a P user to
 * "set up a screen lock" is advice that cannot work — and this screen's whole reason to exist is
 * that it is the last thing the user is shown before they conclude Thor is broken. Q and up take
 * the credential (see [promptAcceptsDeviceCredential]), so there the screen lock half is true.
 */
@Composable
fun BiometricUnavailableScreen(
    onOpenSecuritySettings: () -> Unit,
    onExit: () -> Unit,
    credentialAccepted: Boolean = promptAcceptsDeviceCredential(Build.VERSION.SDK_INT)
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AmbientGlow(Modifier.fillMaxSize(), coreFraction = AMBIENT_WASH_CORE)

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.round_key),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.biometric_unavailable_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(
                    if (credentialAccepted) {
                        R.string.biometric_unavailable_message
                    } else {
                        R.string.biometric_unavailable_message_biometric_only
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 48.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { onOpenSecuritySettings() }
                    .padding(horizontal = 32.dp, vertical = 12.dp)
            ) {
                Text(
                    // labelLarge is Fira Code here — a monospace face at a flat 0.6 em, so an
                    // uppercased translation of this label is wide enough to wrap on a 360dp
                    // screen (French is 33 characters). Centre it so the wrap reads as layout
                    // rather than as a label that fell off the button.
                    text = stringResource(R.string.open_security_settings).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
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
                    // graphicsLayer, not Modifier.alpha: the lambda defers the read of the animated
                    // value to the layer block, so each frame re-records the layer instead of
                    // invalidating this composable. Read in composition scope it recomposes
                    // BiometricLockView every frame, forever — on the first screen of a cold start,
                    // against the main thread the biometric prompt needs.
                    .graphicsLayer { this.alpha = pulseAlpha }
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
                    // Same reason as the ring above: `scale` is animated, so it is read in the layer
                    // block rather than in composition. The constant alpha rides along in the same
                    // layer instead of adding a second one.
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.1f
                    }
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
