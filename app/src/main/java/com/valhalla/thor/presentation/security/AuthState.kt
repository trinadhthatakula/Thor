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
     * The lock is on, but nothing this device has enrolled is accepted by the prompt Thor can
     * build on this API level, so no prompt can ever succeed.
     *
     * Not simply "no biometric and no screen lock": what counts is what
     * `promptAuthenticators(SDK_INT)` asks about. From Android 10 that includes the device
     * credential, so this state means neither a biometric nor a PIN. On Android 9 the framework
     * prompt cannot take a credential at all, so a device with a screen lock and no enrolled
     * fingerprint lands here too.
     *
     * Distinct from [Error], which means an attempt was made and failed and retrying is worth a
     * try. Here retrying is not: the prompt fails the instant it opens, so an [Error] screen would
     * offer a TRY AGAIN button that can only ever loop. The way out is enrolling something the
     * prompt accepts, which is outside the app — so this state exists to say so, name the right
     * thing for this API level, and send the user there.
     */
    data object Unavailable : AuthState

    /** Authentication failed or the device has no enrolled biometrics. */
    data class Error(val message: String) : AuthState
}
