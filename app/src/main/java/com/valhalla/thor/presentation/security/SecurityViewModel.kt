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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    /**
     * Whether the app lock is armed — `null` until the preference has actually been read.
     *
     * The nullability is the whole point. The preference arrives from DataStore asynchronously, so
     * a `false` seed made "not read yet" indistinguishable from "the user turned the lock off", and
     * the not-required branch is the *first* one the `when` below can take: every cold start reported
     * [AuthState.NotRequired] until the real value landed, which is a fail-open gate. Long enough to
     * compose the whole app, too — and MainScreen reads the restored navigation state on its first
     * composition, which Compose's `SaveableStateRegistry` then drops, so the user who authenticated
     * a moment later arrived back at the start destination with their place lost.
     */
    private val _biometricEnabled: StateFlow<Boolean?> = preferenceRepository.userPreferences
        .map { it.biometricLockEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Whether the preference above is the user's answer or a stand-in for one Thor could not read.
     *
     * The settings file is the one in the Auto Backup allowlist, so a partial restore can hand a
     * brand-new device an unreadable copy; `PreferenceRepositoryImpl` replaces it and carries the
     * loss out on this flag. Everything else in that file degrades to a default the user can see
     * and put back — the theme is wrong on screen, the language is wrong on screen. The app lock
     * degrades to *off*, which looks exactly like a user who never set one.
     */
    private val _settingsLost = preferenceRepository.userPreferences
        .map { it.settingsLost }
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
     *  - Whether biometric lock is enabled in preferences — `null` until that has been read
     *  - Whether the user has authenticated this session
     *  - Whether this device can authenticate at all
     *  - Whether the last auth attempt produced an error
     *  - Whether the preference was readable at all
     */
    val authState = combine(
        _biometricEnabled,
        _isSessionAuthenticated,
        _canAuthenticate,
        _authError,
        _settingsLost
    ) { enabled, authenticated, capable, error, settingsLost ->
        // A settings file Thor could not read cannot answer whether the lock was armed, and `false`
        // is not a safe guess: on a freshly restored device — the one place this happens — it opens
        // Thor for a user who deliberately closed it, and the replaced file makes that permanent.
        // So an unreadable store arms the lock too.
        //
        // Only where a prompt could actually succeed, though. Inventing an *unopenable* gate for a
        // lock the user may never have set is the worse of the two mistakes, so with `capable`
        // false this stays out of the way entirely and the branches below see the same state they
        // always did. The user is told either way — see `init`.
        //
        // `enabled == true` rather than `enabled`, because the preference is tri-state: `null`
        // means "not read yet", and the branch below answers that before this value is ever
        // consulted. Written this way instead of leaning on a smart cast so that reordering the
        // `when` cannot silently turn "not read yet" into "not armed".
        val required = enabled == true || (settingsLost && capable)
        when {
            // Above everything, including `authenticated`: nothing else in this `when` can be
            // decided before the preference is known, and each of the other branches would be
            // asserting something about a lock whose state has not been read yet.
            enabled == null -> AuthState.Loading
            !required -> AuthState.NotRequired
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
        // The same value the combine produces first, so the seed cannot describe a different app
        // than the flow does. `Eagerly` starts the collector but does not make it emit inline — on a
        // real device this seed is what `HomeActivity` reads when it composes, and `Locked` there
        // would show the lock screen (and fire a prompt) to every user who never turned it on.
        AuthState.Loading
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
        // read asynchronously: `_biometricEnabled` seeds `null` and the restored `true` lands a
        // moment later, which is precisely the case this exists for. Writing `false` flips that
        // flow, so this settles after one pass instead of looping.
        //
        // `_settingsLost` is deliberately *not* an input: this branch writes to the store, and
        // writing a lock state Thor is only guessing at would turn a failed read into a permanent
        // answer of its own.
        viewModelScope.launch {
            combine(_biometricEnabled, _canAuthenticate) { enabled, capable ->
                enabled == true && !capable
            }.collect { lockedOut ->
                if (!lockedOut || enrolmentCanFix) return@collect
                // Announce the disarm only if it actually landed. The message is past tense and
                // load-bearing — it is the user's only sign that the lock they configured is off —
                // so sending it after a dropped write states the opposite of the truth, and the
                // preference flow will not correct it because nothing changed.
                //
                // A failed write here does not strand anyone: `authState` resolves this same
                // combination to `NotRequired`, so the app opens either way. What breaks is the
                // next launch, which finds `biometric_lock=true` still on disk and disarms it
                // again. Saying so lets the user act on a store that has stopped taking writes
                // instead of watching one setting undo itself forever.
                val disarmed = preferenceRepository.setBiometricLock(false)
                _events.send(
                    UiText.StringResource(
                        if (disarmed) R.string.biometric_lock_disabled_no_biometric
                        else R.string.biometric_lock_disable_not_saved
                    )
                )
            }
        }

        // And say so when the settings could not be read at all, for the same reason the disarm
        // above does: Thor is running on values the user did not choose, one of which is the app
        // lock, and on the branch where no prompt can succeed the lock is not even re-armed. Told
        // from here rather than from a settings screen because the flag decides what the user sees
        // first, and because this is where the channel that survives that early already exists.
        //
        // `first { it }` completes on the first true and the flag never goes back, so this is one
        // notice per process however many times the stream re-emits.
        viewModelScope.launch {
            _settingsLost.first { it }
            _events.send(UiText.StringResource(R.string.settings_lost_using_defaults))
        }

        // The write-side twin of the notice above, and told from the same place for the same
        // reason: this ViewModel outlives every settings screen, so a preference dropped by a
        // screen the user has already left still gets said out loud.
        //
        // Kept as a second collector rather than folded into a `combine` with `_settingsLost`,
        // because the two failures deserve different sentences — one means Thor is running on
        // values the user never chose, the other means a value the user just chose did not stick —
        // and a `combine` would have to pick one of them to say.
        //
        // Latched flag, `first { it }`: one notice for however many writes fail after it. Then the
        // latch is lowered, because it lives on the repository singleton and this ViewModel does
        // not — Exit finishes the activity without ending the process, so the next launch builds a
        // fresh SecurityViewModel that would collect a `true` it has already reported and open on a
        // notice about nothing. Acknowledging does not claim the disk recovered; the next dropped
        // write raises it again.
        viewModelScope.launch {
            preferenceRepository.settingsWriteFailed.first { it }
            _events.send(UiText.StringResource(R.string.settings_not_saved))
            preferenceRepository.acknowledgeSettingsWriteFailure()
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
        // `_settingsLost` for the same reason `authState` takes it: on that branch the prompt is
        // armed without the preference being `true`, and gating the error on the preference alone
        // would leave a cancelled prompt sitting on the lock screen with nothing said and no Retry.
        //
        // `== true` because the preference is tri-state. A prompt cannot have errored before the
        // preference was read, so the `null` case is unreachable rather than merely unhandled — but
        // spelling it out keeps this branch from quietly changing meaning if that ever stops holding.
        if ((_biometricEnabled.value == true || _settingsLost.value) && !_isSessionAuthenticated.value) {
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
