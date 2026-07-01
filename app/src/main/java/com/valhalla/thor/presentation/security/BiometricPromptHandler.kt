package com.valhalla.thor.presentation.security

import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

/**
 * Handles biometric authentication using the framework BiometricPrompt API (API 28+).
 * This implementation does NOT require FragmentActivity, making it compatible with ComponentActivity.
 */
internal class BiometricPromptHandler(private val context: Context) {

    private var cancellationSignal: CancellationSignal? = null

    fun authenticate(
        title: String,
        subtitle: String,
        onAuthenticated: () -> Unit,
        onError: (String) -> Unit
    ) {
        // Cancel any in-flight prompt before starting a new one so the previous
        // CancellationSignal (and its captured callbacks) isn't orphaned.
        cancellationSignal?.cancel()

        val executor = ContextCompat.getMainExecutor(context)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                super.onAuthenticationSucceeded(result)
                cancellationSignal = null
                onAuthenticated()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                super.onAuthenticationError(errorCode, errString)
                cancellationSignal = null
                // Error code 5 is developer-initiated cancellation, ignore it.
                if (errorCode != 5) {
                    onError(errString?.toString() ?: "Authentication error")
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Framework handles internal retries; we could notify UI if needed.
            }
        }

        val builder = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setSubtitle(subtitle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        } else {
            // On API 28-29, DEVICE_CREDENTIAL wasn't supported in setAllowedAuthenticators.
            // We fall back to a negative button if only BIOMETRIC_STRONG is possible.
            builder.setNegativeButton("Cancel", executor) { _, _ ->
                onError("User cancelled")
            }
        }

        cancellationSignal = CancellationSignal()
        builder.build().authenticate(cancellationSignal!!, executor, callback)
    }

    fun cancel() {
        cancellationSignal?.cancel()
        cancellationSignal = null
    }
}
