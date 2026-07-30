// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.repository.AuthCapability
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class SecurityViewModel(
    preferenceRepository: PreferenceRepository,
    private val authCapability: AuthCapability
) : ViewModel() {

    // Tracks whether the user has authenticated in this session.
    private val _isSessionAuthenticated = MutableStateFlow(false)

    // Holds the last error message when auth fails permanently.
    private val _authError = MutableStateFlow<String?>(null)

    private val _biometricEnabled = preferenceRepository.userPreferences
        .map { it.biometricLockEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Whether a prompt could succeed at all. Seeded synchronously rather than refreshed into
     * place, so the very first composition already knows: seeding it optimistically would show
     * the lock screen, fire a prompt that fails instantly, and only then correct itself. One
     * `BiometricManager` query is cheap next to that flicker.
     */
    private val _canAuthenticate = MutableStateFlow(authCapability.canAuthenticate())

    /**
     * The single source of truth for auth state, derived from:
     *  - Whether biometric lock is enabled in preferences
     *  - Whether the user has authenticated this session
     *  - Whether this device can authenticate at all
     *  - Whether the last auth attempt produced an error
     */
    val authState = combine(
        _biometricEnabled,
        _isSessionAuthenticated,
        _canAuthenticate,
        _authError
    ) { enabled, authenticated, capable, error ->
        when {
            !enabled -> AuthState.NotRequired
            authenticated -> AuthState.Unlocked
            // Above Error on purpose. The prompt fails the instant it opens on a device that
            // cannot authenticate, so `error` is always populated here a moment later — and an
            // Error screen's TRY AGAIN re-arms the prompt, which fails again, which is exactly
            // the loop this state exists to break. Also below `authenticated`, so a user who
            // unlocks and then removes their screen lock from system Settings is not thrown out
            // of a session they legitimately started.
            !capable -> AuthState.Unavailable
            error != null -> AuthState.Error(error)
            else -> AuthState.Locked
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AuthState.Locked
    )

    /**
     * Re-asks whether authentication is possible. Called from `onResume`, which is what makes
     * [AuthState.Unavailable] an escape rather than a nicer dead end: the user leaves for system
     * Settings, sets a screen lock, comes back, and this flips them to [AuthState.Locked] — a
     * prompt that can now succeed — with no restart.
     */
    fun refreshCapability() {
        val capable = authCapability.canAuthenticate()
        if (_canAuthenticate.value != capable) {
            // The device's auth setup changed while Thor was backgrounded, so any error recorded
            // against the old setup describes a prompt that no longer applies. Without this, a
            // user who left to enrol a fingerprint comes back to the stale "no biometrics
            // enrolled" error they were shown before they went and fixed it.
            _authError.value = null
        }
        _canAuthenticate.value = capable
    }

    /** Called by BiometricScreen on successful authentication. */
    fun onAuthenticated() {
        _authError.value = null
        _isSessionAuthenticated.value = true
    }

    /**
     * Called when the biometric prompt is dismissed with an error (user cancel,
     * too many attempts, lockout, etc.). Surfaces the message to the UI so the
     * user can choose to retry or exit.
     */
    fun onAuthError(message: String) {
        if (_biometricEnabled.value && !_isSessionAuthenticated.value) {
            _authError.value = message
        }
    }

    /**
     * Called when the user taps "Retry" on the error screen.
     * Clears the error and returns to Locked so BiometricScreen re-triggers the prompt.
     */
    fun onRetry() {
        _authError.value = null
    }
}
