// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

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

    /**
     * The lock is on, but this device has neither an enrolled biometric nor any screen lock, so
     * no prompt can ever succeed.
     *
     * Distinct from [Error], which means an attempt was made and failed and retrying is worth a
     * try. Here retrying is not: the prompt fails the instant it opens, so an [Error] screen would
     * offer a TRY AGAIN button that can only ever loop. The way out is to set a screen lock, which
     * is outside the app — so this state exists to say so and send the user there.
     */
    data object Unavailable : AuthState

    /** Authentication failed or the device has no enrolled biometrics. */
    data class Error(val message: String) : AuthState
}
