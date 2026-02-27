package com.valhalla.thor.presentation.security

/**
 * Represents the authentication state gate for the app.
 * The UI tree uses this to decide whether to show BiometricScreen or MainScreen.
 */
sealed interface AuthState {
    /** Biometric lock is disabled — proceed directly to the app. */
    data object NotRequired : AuthState

    /** Biometric lock is enabled but the user has not yet authenticated this session. */
    data object Locked : AuthState

    /** User has successfully authenticated this session. */
    data object Unlocked : AuthState

    /** Authentication failed or the device has no enrolled biometrics. */
    data class Error(val message: String) : AuthState
}
