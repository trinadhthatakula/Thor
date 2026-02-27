package com.valhalla.thor.data.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * Thin wrapper around [BiometricManager] that answers capability questions
 * without touching any UI. Lives in the data layer — no Compose dependency.
 */
class BiometricHelper(private val context: Context) {

    private val allowedAuthenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    /** Returns true if the device can authenticate via biometric or device credential. */
    fun canAuthenticate(): Boolean {
        return BiometricManager.from(context)
            .canAuthenticate(allowedAuthenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** Returns true if the device has biometric hardware, regardless of enrollment state. */
    fun hasHardware(): Boolean {
        val status = BiometricManager.from(context).canAuthenticate(allowedAuthenticators)
        return status != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
    }
}
