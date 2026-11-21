// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.valhalla.thor.R
import java.security.GeneralSecurityException
import java.security.Signature
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Handles biometric authentication using the framework BiometricPrompt API (API 28+).
 * This implementation does NOT require FragmentActivity, making it compatible with ComponentActivity.
 */
internal class BiometricPromptHandler(private val activity: ComponentActivity) {
    private var authentication: Job? = null

    fun authenticate(
        title: String,
        subtitle: String,
        onAuthenticated: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (authentication?.isActive == true) return
        authentication = activity.lifecycleScope.launch {
            try {
                val verified = verifyUser(title, subtitle)
                if (verified) onAuthenticated() else onError(activity.getString(R.string.auth_failed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PromptError) {
                onError(e.message ?: activity.getString(R.string.auth_failed))
            } catch (_: Exception) {
                onError(activity.getString(R.string.auth_failed))
            }
        }
    }

    private suspend fun verifyUser(title: String, subtitle: String): Boolean {
        val legacyQ = Build.VERSION.SDK_INT == Build.VERSION_CODES.Q
        val manager = BiometricManager.from(activity)
        if (!legacyQ || manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
            val operation = try {
                withContext(Dispatchers.IO) { AppLockKey.biometricOperation() }
            } catch (e: GeneralSecurityException) {
                if (!legacyQ) throw e
                null // A Q device can still confirm its screen lock.
            }
            if (operation != null) {
                val (challenge, signer) = operation
                val authorized = promptForSignature(title, subtitle, signer)
                if (authorized != null) {
                    return authorized === signer && withContext(Dispatchers.IO) { challenge.verifyWith(authorized) }
                }
            }
        }
        if (!legacyQ) return false

        // Q rejects a CryptoObject combined with device credentials. Capture the challenge
        // before opening the system credential activity and sign only after its own RESULT_OK.
        val key = withContext(Dispatchers.IO) { AppLockKey.openCredentialKey() }
        if (!confirmCredential(title, subtitle)) return false
        return withContext(Dispatchers.IO) { key.verifyCredential() }
    }

    /** A null result requests the Android 10 screen-lock fallback. */
    private suspend fun promptForSignature(title: String, subtitle: String, signer: Signature): Signature? =
        suspendCancellableCoroutine { pending ->
            val executor = ContextCompat.getMainExecutor(activity)
            val signal = CancellationSignal()
            val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
            fun fail(message: CharSequence?) {
                if (pending.isActive) pending.resumeWithException(PromptError(message?.toString()))
            }
            val legacyQ = Build.VERSION.SDK_INT == Build.VERSION_CODES.Q
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    builder.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                legacyQ -> {
                    val label = BiometricManager.from(activity).getStrings(BiometricManager.Authenticators.DEVICE_CREDENTIAL)?.buttonLabel ?: title
                    builder.setNegativeButton(label, executor) { _, _ -> if (pending.isActive) pending.resume(null) }
                }
                else -> builder.setNegativeButton(activity.getText(android.R.string.cancel), executor) { _, _ ->
                    fail(null)
                }
            }
            pending.invokeOnCancellation { signal.cancel() }
            builder.build().authenticate(
                BiometricPrompt.CryptoObject(signer), signal, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        if (!pending.isActive) return
                        val authorized = result?.cryptoObject?.signature
                        if (authorized == null) fail(null) else pending.resume(authorized)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (!pending.isActive) return
                        val canUseCredential = when (errorCode) {
                            BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                            BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
                            BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
                            BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS -> legacyQ
                            else -> false
                        }
                        if (canUseCredential) pending.resume(null) else fail(errString)
                    }
                }
            )
        }

    @Suppress("DEPRECATION") // API 29 device credentials cannot authorize a per-use biometric key.
    private suspend fun confirmCredential(title: String, subtitle: String): Boolean {
        val intent = activity.getSystemService(KeyguardManager::class.java)
            .createConfirmDeviceCredentialIntent(title, subtitle) ?: return false
        var launcher: ActivityResultLauncher<Intent>? = null
        try {
            return suspendCancellableCoroutine { pending ->
                launcher = activity.activityResultRegistry.register(
                    "thor-app-lock-" + UUID.randomUUID(), ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (pending.isActive) pending.resume(result.resultCode == Activity.RESULT_OK)
                }
                launcher.launch(intent)
            }
        } finally {
            launcher?.unregister()
        }
    }

    fun cancel() {
        authentication?.cancel()
        authentication = null
    }

    private class PromptError(message: String?) : Exception(message)
}

/**
 * Whether the prompt [BiometricPromptHandler] builds on [sdkInt] can be satisfied by the device
 * credential — a PIN, pattern or password — as opposed to a biometric only.
 *
 * R+ allows the credential in the crypto prompt. Q uses the system credential activity.
 * Android 9 supports biometric authentication only.
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
