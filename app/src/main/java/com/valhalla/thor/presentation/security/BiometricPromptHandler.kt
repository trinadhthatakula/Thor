// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.security

import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.valhalla.thor.R

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

/**
 * Whether a user held at a prompt that cannot succeed could *enrol their way back in* on [sdkInt].
 *
 * Two very different situations hide behind "the lock is on and no prompt can succeed":
 *
 * - **Recoverable.** The device would accept something the user simply has not enrolled — a screen
 *   lock from Android 10 up, a fingerprint anywhere there is a sensor. [BiometricUnavailableScreen]
 *   names the right one for this API level, deep-links to it, and `onResume` re-queries, so the user
 *   goes and enrols and is let in. The lock survives, which is the point of having one.
 * - **Unrecoverable.** Nothing they can enrol will ever satisfy the prompt: API 28, whose framework
 *   prompt takes no device credential at all, on hardware with no biometric sensor. Setting a PIN
 *   there changes nothing. That is not a locked door, it is a wall — the exits are EXIT and clearing
 *   Thor's data — and it is the one case where Thor takes its own lock off rather than leave the
 *   user standing in front of it.
 *
 * [hasBiometricHardware] is sensor *presence*, not enrolment. A sensor with nothing enrolled is
 * recoverable precisely because enrolling on it is the fix; asking about enrolment here would
 * collapse the two cases this function exists to tell apart.
 */
internal fun enrolmentCanFixLockout(sdkInt: Int, hasBiometricHardware: Boolean): Boolean =
    promptAcceptsDeviceCredential(sdkInt) || hasBiometricHardware

/**
 * What to tell a user whose attempt to *arm* the app lock was refused, on this device.
 *
 * Same rule the unavailable screen uses for its copy, and for the same reason: the refusal is
 * useless unless it names something the user can actually go and do. The three answers are the
 * three shapes the situation takes, and they are exactly the two predicates above composed —
 *
 * - **Nothing will ever work.** [enrolmentCanFixLockout] false: API 28 with no sensor. Sending them
 *   to Settings would be a wild goose chase, so this one says the device cannot do it and stops.
 * - **A fingerprint will work; a screen lock will not.** API 28 with a sensor. Android 9's prompt
 *   takes no device credential, so the generic "set up a screen lock or fingerprint" is half wrong
 *   and the wrong half is the one most users would try first.
 * - **Either will work.** Android 10 and up, the ordinary case.
 *
 * A `@StringRes` rather than a string because the caller is a view model, which has no `Context` and
 * emits `UiText`. Kept next to the predicates it composes so the three copy variants and the two
 * capability rules cannot drift apart.
 */
@StringRes
internal fun biometricRefusalMessage(sdkInt: Int, hasBiometricHardware: Boolean): Int = when {
    !enrolmentCanFixLockout(sdkInt, hasBiometricHardware) ->
        R.string.biometric_lock_unavailable_toast
    !promptAcceptsDeviceCredential(sdkInt) -> R.string.biometric_not_enrolled_toast_biometric_only
    else -> R.string.biometric_not_enrolled_toast
}
