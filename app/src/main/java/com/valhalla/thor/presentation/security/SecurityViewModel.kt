// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.security

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.domain.repository.AuthCapability
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class SecurityViewModel(
    private val preferenceRepository: PreferenceRepository,
    private val authCapability: AuthCapability
) : ViewModel() {

    // Tracks whether the user has authenticated in this session.
    private val _isSessionAuthenticated = MutableStateFlow(false)

    // Holds the last error message when auth fails permanently.
    private val _authError = MutableStateFlow<String?>(null)

    /**
     * One-off UI feedback for the self-heal below, collected by `HomeActivity`.
     *
     * A buffered [Channel] rather than a `replay = 0` SharedFlow, for the same reason as
     * `FreezerViewModel`'s: this view model is constructed during `onCreate`, so the disarm can fire
     * before the collector reaches STARTED — and a dropped emission here means Thor silently turned
     * the user's app lock off with no word about it.
     */
    private val _events = Channel<UiText>(Channel.BUFFERED)
    val events: Flow<UiText> = _events.receiveAsFlow()

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
     * Whether a user who is locked out could enrol their way back in on this device.
     *
     * `lazy` rather than eager on purpose: it costs a second `BiometricManager` query, the answer
     * only matters on the one branch where the lock is armed *and* cannot open, and this class is
     * constructed on every cold start — the #22 measurement is why an unconditional extra binder
     * call on that path is not free. Both inputs are fixed for the life of the process, so caching
     * the answer cannot go stale: `SDK_INT` never changes and a biometric sensor is not removable.
     */
    private val enrolmentCanFix by lazy {
        enrolmentCanFixLockout(Build.VERSION.SDK_INT, authCapability.hasHardware())
    }

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
            //
            // Fail open, and only here: the lock is armed, no prompt can succeed, and there is
            // nothing the user could go and enrol that would change that — API 28's prompt takes no
            // device credential and this device has no sensor to enrol on. `Unavailable` would be an
            // honest screen with no way off it but EXIT and "clear app data". The write that makes
            // this permanent is in `init`; this branch is what stops a frame of that dead end from
            // rendering while the write lands.
            !capable && !enrolmentCanFix -> AuthState.NotRequired
            !capable -> AuthState.Unavailable
            error != null -> AuthState.Error(error)
            else -> AuthState.Locked
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AuthState.Locked
    )

    init {
        // Disarm a lock this device can never open. This is the mirror of the guard in
        // `SettingsViewModel.setBiometricLock`: a lock Thor refuses to let the user switch **on** is
        // one it will not leave switched on either, however it got that way. Auto Backup restoring
        // `biometric_lock=true` onto a new device is the route that motivated it, but removing the
        // last enrolment reaches the same state with no backup involved.
        //
        // Only the unrecoverable case is disarmed. Where enrolling something would fix it, the lock
        // stays on and `AuthState.Unavailable` sends the user to enrol — silently dropping a lock a
        // user can still open would be a security downgrade dressed up as a bug fix.
        //
        // Driven off the preference flow rather than checked once here, because the preference is
        // read asynchronously: `_biometricEnabled` seeds `false` and the restored `true` lands a
        // moment later, which is precisely the case this exists for. Writing `false` flips that
        // flow, so this settles after one pass instead of looping.
        viewModelScope.launch {
            combine(_biometricEnabled, _canAuthenticate) { enabled, capable ->
                enabled && !capable
            }.collect { lockedOut ->
                if (!lockedOut || enrolmentCanFix) return@collect
                preferenceRepository.setBiometricLock(false)
                _events.send(UiText.StringResource(R.string.biometric_lock_disabled_no_biometric))
            }
        }
    }

    /**
     * Re-asks whether authentication is possible. Called from `onResume`, which is what makes
     * [AuthState.Unavailable] an escape rather than a nicer dead end: the user leaves for system
     * Settings, enrols whatever that screen told them to (a screen lock from Android 10 up, a
     * fingerprint on 9), comes back, and this flips them to [AuthState.Locked] — a prompt that can
     * now succeed — with no restart.
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
