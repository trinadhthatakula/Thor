package com.valhalla.thor.presentation.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.valhalla.thor.R

/**
 * BiometricScreen
 *
 * Routes between two internal states:
 *  - [BiometricPromptScreen]: invisible — fires the system prompt on composition.
 *  - [BiometricErrorScreen]: visible — shown when auth fails, with Retry and Exit actions.
 *
 * The caller controls which is shown via [isError]. Both live in this file because
 * they are two faces of the same security gate and should never exist independently.
 */
@Composable
fun BiometricScreen(
    isError: Boolean,
    errorMessage: String,
    onAuthenticated: () -> Unit,
    onError: (String) -> Unit,
    onRetry: () -> Unit,
    onExit: () -> Unit
) {
    if (isError) {
        BiometricErrorScreen(
            message = errorMessage,
            onRetry = onRetry,
            onExit = onExit
        )
    } else {
        BiometricPromptScreen(
            onAuthenticated = onAuthenticated,
            onError = onError
        )
    }
}

// ─── Prompt (invisible) ───────────────────────────────────────────────────────

@Composable
private fun BiometricPromptScreen(
    onAuthenticated: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val activity = context as? FragmentActivity
        if (activity == null) {
            onError("Biometric prompt requires a FragmentActivity host.")
            return@LaunchedEffect
        }

        val biometricManager = BiometricManager.from(context)
        val allowedAuthenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

        when (biometricManager.canAuthenticate(allowedAuthenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> showBiometricPrompt(
                activity = activity,
                onAuthenticated = onAuthenticated,
                onError = onError
            )

            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                onError("This device has no biometric hardware.")

            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                onError("Biometric hardware is currently unavailable.")

            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                // No credentials enrolled — fail open so user is never locked out.
                onAuthenticated()

            else -> onError("Biometric authentication is not available.")
        }
    }
    // Intentionally renders nothing — the system prompt is the entire UI.
}

// ─── Error screen ─────────────────────────────────────────────────────────────

@Composable
private fun BiometricErrorScreen(
    message: String,
    onRetry: () -> Unit,
    onExit: () -> Unit
) {
    // Animate in once — the spring gives a tactile feel matching Material Expressive.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + scaleIn(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                initialScale = 0.85f
            )
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.round_key),
                    contentDescription = "Authentication failed",
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.error
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Authentication Failed",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(40.dp))

                Button(onClick = onRetry) {
                    Text("Retry")
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = onExit) {
                    Text("Exit")
                }
            }
        }
    }
}

// ─── Prompt builder ───────────────────────────────────────────────────────────

private fun showBiometricPrompt(
    activity: FragmentActivity,
    onAuthenticated: () -> Unit,
    onError: (String) -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)

    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onAuthenticated()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            // Codes 10 (user cancel) and 13 (negative button) are explicit dismissals.
            // All error codes are treated the same — surface the message and let the
            // user decide to retry or exit from the error screen.
            onError(errString.toString())
        }

        override fun onAuthenticationFailed() {
            // A single failed attempt (wrong finger, etc.) — the system prompt
            // handles retry natively. We only react to terminal errors above.
        }
    }

    BiometricPrompt(activity, executor, callback).authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Thor")
            .setSubtitle("Authenticate to continue")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
    )
}
