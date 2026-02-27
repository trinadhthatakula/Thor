package com.valhalla.thor.presentation.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SecurityViewModel(
    preferenceRepository: PreferenceRepository
) : ViewModel() {

    // Tracks whether the user has authenticated in this session.
    private val _isSessionAuthenticated = MutableStateFlow(false)

    // Holds the last error message when auth fails permanently.
    private val _authError = MutableStateFlow<String?>(null)

    private val _biometricEnabled = preferenceRepository.userPreferences
        .map { it.biometricLockEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The single source of truth for auth state, derived from:
     *  - Whether biometric lock is enabled in preferences
     *  - Whether the user has authenticated this session
     *  - Whether the last auth attempt produced an error
     */
    val authState = combine(
        _biometricEnabled,
        _isSessionAuthenticated,
        _authError
    ) { enabled, authenticated, error ->
        when {
            !enabled -> AuthState.NotRequired
            authenticated -> AuthState.Unlocked
            error != null -> AuthState.Error(error)
            else -> AuthState.Locked
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Locked)

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
