// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
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
 * The authenticator set to ask `BiometricManager` about on [sdkInt] — the capability half of
 * what [com.valhalla.thor.presentation.security.BiometricPromptHandler] builds.
 *
 * Three tiers, because two platform facts cut at different versions:
 *
 * - **API 30+** — `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, the set the prompt itself passes to
 *   `setAllowedAuthenticators`.
 * - **API 29** — the same *question* is not askable: androidx's
 *   `AuthenticatorUtils.isSupportedCombination` rejects `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`
 *   below R, so `canAuthenticate` answers `BIOMETRIC_ERROR_UNSUPPORTED` without consulting the
 *   device. `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` is supported at every level and is what the
 *   Android 10 prompt actually accepts, since it is built with the deprecated
 *   `setDeviceCredentialAllowed(true)`. The `WEAK`/`STRONG` difference cannot make this
 *   over-permissive in practice: Android will not let a biometric be enrolled without a backup
 *   credential, so "a credential is set" is implied by every enrolled biometric anyway.
 * - **API 28** — Android 9's framework prompt has no device-credential path at all (
 *   `setDeviceCredentialAllowed` arrives in Q), so the honest question is biometric-only. A
 *   *screen lock alone does not unlock Thor on Android 9*, which is why the unavailable screen
 *   says something different there.
 *
 * The predicate and the prompt have to agree, or the gate is answering a different question than
 * the screen behind it — and getting that wrong is a lockout, not a cosmetic bug: the launch path
 * gates on `canAuthenticate()`, and `BIOMETRIC_ERROR_UNSUPPORTED` is indistinguishable, to a
 * `== BIOMETRIC_SUCCESS` test, from "nothing is enrolled".
 */
internal fun promptAuthenticators(sdkInt: Int): Int = when {
    sdkInt >= Build.VERSION_CODES.R -> BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    sdkInt >= Build.VERSION_CODES.Q -> BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    else -> BIOMETRIC_STRONG
}
