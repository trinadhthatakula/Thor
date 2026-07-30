// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

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

        // Capture this prompt's own signal so a stale callback from a superseded prompt
        // can't null out a newer in-flight prompt's signal (which would orphan it — cancel()
        // and the next authenticate() could no longer cancel it).
        val signal = CancellationSignal()

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                super.onAuthenticationSucceeded(result)
                if (cancellationSignal === signal) cancellationSignal = null
                onAuthenticated()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                super.onAuthenticationError(errorCode, errString)
                if (cancellationSignal === signal) cancellationSignal = null
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

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                builder.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)

            // Android 10 has no setAllowedAuthenticators, but it does have the flag that became
            // DEVICE_CREDENTIAL — and the app lock needs it, because without it a device whose
            // only unlock is a PIN can never satisfy the prompt. Deprecated on purpose: it is the
            // only device-credential path Q offers, and R+ takes the branch above.
            //
            // Mutually exclusive with setNegativeButton (the framework throws), which is why this
            // is a `when` and not two independent `if`s. The system prompt supplies its own
            // "Use PIN" affordance and Back still dismisses, so nothing is lost.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)

            // Android 9's prompt is biometric-only: setDeviceCredentialAllowed arrives in Q, so
            // there is nothing here to accept a screen lock with. `promptAuthenticators` asks
            // BiometricManager the matching biometric-only question, and the unavailable screen
            // tells a P user to enrol rather than to set a screen lock that would not help.
            else -> builder.setNegativeButton("Cancel", executor) { _, _ ->
                onError("User cancelled")
            }
        }

        cancellationSignal = signal
        builder.build().authenticate(signal, executor, callback)
    }

    fun cancel() {
        cancellationSignal?.cancel()
        cancellationSignal = null
    }
}

/**
 * Whether the prompt [BiometricPromptHandler] builds on [sdkInt] can be satisfied by the device
 * credential — a PIN, pattern or password — as opposed to a biometric only.
 *
 * True from Q up: R+ passes `DEVICE_CREDENTIAL` to `setAllowedAuthenticators`, Q sets the
 * deprecated `setDeviceCredentialAllowed`. False on P, whose framework prompt has neither.
 *
 * The version literal is repeated inside [BiometricPromptHandler.authenticate] rather than read
 * from here, because lint's `NewApi` only recognises a direct `SDK_INT` comparison as a guard for
 * an API-29 call. `PromptAuthenticatorsTest` pins this against `promptAuthenticators` so the two
 * halves — what the prompt accepts and what capability asks about — cannot drift apart silently.
 *
 * Read by [BiometricUnavailableScreen], which must not tell an Android 9 user that setting a
 * screen lock will get them in.
 */
internal fun promptAcceptsDeviceCredential(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.Q
