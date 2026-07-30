// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import com.valhalla.thor.domain.repository.AuthCapability
import org.koin.core.annotation.Single

/**
 * Thin wrapper around [BiometricManager] that answers capability questions
 * without touching any UI. Lives in the data layer — no Compose dependency.
 */
@Single(binds = [AuthCapability::class])
class BiometricHelper(private val context: Context) : AuthCapability {

    private val allowedAuthenticators get() = promptAuthenticators(Build.VERSION.SDK_INT)

    /** Returns true if the device can authenticate via biometric or device credential. */
    override fun canAuthenticate(): Boolean {
        return BiometricManager.from(context)
            .canAuthenticate(allowedAuthenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** Returns true if the device has biometric hardware, regardless of enrollment state. */
    override fun hasHardware(): Boolean {
        val status = BiometricManager.from(context).canAuthenticate(allowedAuthenticators)
        return status != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
    }
}

/**
 * The authenticator set the prompt can actually offer on [sdkInt].
 *
 * `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is **not a supported combination on API 28-29**:
 * androidx's `AuthenticatorUtils.isSupportedCombination` rejects it outright, so
 * `canAuthenticate` returns `BIOMETRIC_ERROR_UNSUPPORTED` on every Android 9 and 10 device
 * whatever hardware it has and whatever is enrolled. Thor's minSdk is 28, so asking the
 * unsupported question would declare two whole API levels incapable — which used to only hide
 * the Settings toggle, but now also decides whether the launch path holds the user at
 * [com.valhalla.thor.presentation.security.AuthState.Unavailable]. Getting it wrong there is a
 * lockout, not a cosmetic bug.
 *
 * The split mirrors [com.valhalla.thor.presentation.security.BiometricPromptHandler], which
 * only calls `setAllowedAuthenticators` on API 30+ and falls back to a biometric-only prompt
 * with a Cancel button below that. The capability predicate and the prompt have to agree, or
 * the gate is answering a different question than the screen behind it.
 */
internal fun promptAuthenticators(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG
