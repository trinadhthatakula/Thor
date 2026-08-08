// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.AppGridDensity
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkOutcome
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.DefaultTab
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.NoOpReason
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.AnyFileOpenerController
import com.valhalla.thor.domain.repository.AuthCapability
import com.valhalla.thor.domain.repository.BulkFreezeController
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.presentation.security.biometricRefusalMessage
import com.valhalla.thor.util.LocaleManager
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.bulkResultMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

@KoinViewModel
class SettingsViewModel(
    private val preferenceRepository: PreferenceRepository,
    private val systemRepository: SystemRepository,
    private val biometricHelper: AuthCapability,
    private val localeManager: LocaleManager,
    private val bulkFreeze: BulkFreezeController,
    private val freezerShortcutManager: com.valhalla.thor.data.launcher.FreezerShortcutManager,
    private val anyFileOpenerController: AnyFileOpenerController,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    data class SettingsUiState(
        val prefs: UserPreferences = UserPreferences(),
        val isRootAvailable: Boolean = false,
        val isShizukuAvailable: Boolean = false,
        val isDhizukuAvailable: Boolean = false,
        val canUseBiometric: Boolean = false,
        val hasBiometricHardware: Boolean = false,
        /**
         * Not from [prefs] — this one lives in PackageManager's component state. See
         * [AnyFileOpenerController] for why it is not mirrored into DataStore.
         */
        val anyFileOpenerEnabled: Boolean = false
    )

    /** Off-main-thread snapshot of the available privilege engines. */
    private data class PrivilegeProbe(
        val root: Boolean,
        val shizuku: Boolean,
        val dhizuku: Boolean
    )

    /**
     * One-off UI feedback (Toasts) that must fire exactly once — kept off the UiState StateFlow so it
     * isn't re-delivered on recomposition/config change. Collected in SettingsScreen via ObserveAsEvents.
     */
    // Buffered Channel (not a replay=0 SharedFlow): an event emitted before the screen's collector
    // reaches STARTED (early lifecycle / config change) is buffered and delivered on (re)subscribe
    // instead of being silently dropped.
    private val _events = Channel<UiText>(Channel.BUFFERED)
    val events: Flow<UiText> = _events.receiveAsFlow()

    /**
     * Live component state of the any-file alias, re-read rather than remembered.
     *
     * A MutableStateFlow instead of a `flow { emit(...) }` like the privilege probe below, because
     * this one is written from the screen: the probe only ever needs its first value, this needs a
     * new one after every toggle. Seeded off the main thread by [refreshAnyFileOpener].
     */
    private val anyFileOpenerEnabled = MutableStateFlow(false)

    /**
     * Serialises write-then-read-back on the alias, so a fast double-tap cannot land out of order.
     *
     * Both halves suspend on binder IPC, and `viewModelScope.launch` starts a fresh coroutine per
     * tap, so two toggles genuinely overlap. Unserialised, off-then-on can apply as on-then-off:
     * the component ends up in the *earlier* tap's state, and the read-back of the later one sees a
     * value it did not write, so it fires "Android refused to change this setting" when nothing
     * refused — the exact false report the read-back exists to prevent.
     */
    private val anyFileOpenerMutex = Mutex()

    init {
        refreshAnyFileOpener()
    }

    private val _systemStatus = combine(
        preferenceRepository.userPreferences,
        anyFileOpenerEnabled,
        flow {
            // Availability probes hit binder IPC (Shizuku.pingBinder / DhizukuAPI). flowOn(io) below
            // keeps them off the Main thread to avoid janking the first subscription / every
            // WhileSubscribed restart.
            emit(
                PrivilegeProbe(
                    root = systemRepository.isRootAvailable(),
                    shizuku = systemRepository.isShizukuAvailable(),
                    dhizuku = systemRepository.isDhizukuAvailable()
                )
            )
        }.flowOn(ioDispatcher)
    ) { prefs, anyFileOpener, status ->
        SettingsUiState(
            prefs = prefs,
            isRootAvailable = status.root,
            isShizukuAvailable = status.shizuku,
            isDhizukuAvailable = status.dhizuku,
            canUseBiometric = biometricHelper.canAuthenticate(),
            hasBiometricHardware = biometricHelper.hasHardware(),
            anyFileOpenerEnabled = anyFileOpener
        )
    }

    val uiState: StateFlow<SettingsUiState> = _systemStatus
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsUiState()
        )

    /** True only if the device has enrolled biometrics or a device credential. */
    val canUseBiometric: Boolean get() = biometricHelper.canAuthenticate()

    /** True if the device has biometric hardware at all (even if not enrolled). */
    val hasBiometricHardware: Boolean get() = biometricHelper.hasHardware()

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferenceRepository.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { preferenceRepository.setDynamicColor(enabled) }
    }

    fun setAmoledMode(enabled: Boolean) {
        viewModelScope.launch { preferenceRepository.setUseAmoled(enabled) }
    }

    /**
     * Arms or disarms the app lock, refusing to arm one that could never open.
     *
     * The refusal is deliberately wider than the self-heal in `SecurityViewModel`: *any* device that
     * cannot authenticate right now is refused, not only the unrecoverable API 28 case. Turning a
     * lock **on** costs nothing to refuse — the user is standing in Settings, the toast names what is
     * missing, and they can go enrol it and come back. Turning one **off** on their behalf is a
     * security downgrade, so that stays confined to the case where enrolling cannot help.
     *
     * Asks [AuthCapability] fresh rather than reading `uiState.canUseBiometric`: that snapshot is
     * recomputed only when the preferences flow emits, so a user who enrols a fingerprint and returns
     * to a still-composed Settings screen would otherwise be refused on a stale `false`.
     *
     * The message is chosen by [biometricRefusalMessage], not fixed: a refusal that tells an
     * Android 9 user to "set up a screen lock" sends them to do something their prompt cannot accept,
     * which is the same mistake `BiometricUnavailableScreen` exists to avoid making.
     *
     * A dropped write is reported here rather than through the store-wide notice, which
     * `setBiometricLock` deliberately does not raise. This is the one preference where "some
     * settings could not be saved" is not enough: the user needs to know which way their front door
     * is facing, so the message names the state the lock is *actually* in — the opposite of the one
     * they just asked for.
     */
    fun setBiometricLock(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !biometricHelper.canAuthenticate()) {
                _events.send(
                    UiText.StringResource(
                        biometricRefusalMessage(
                            Build.VERSION.SDK_INT,
                            biometricHelper.hasHardware()
                        )
                    )
                )
                return@launch
            }
            if (!preferenceRepository.setBiometricLock(enabled)) {
                _events.send(
                    UiText.StringResource(
                        if (enabled) R.string.biometric_lock_not_saved_still_off
                        else R.string.biometric_lock_not_saved_still_on
                    )
                )
            }
        }
    }

    fun setPrivilegeMode(mode: PrivilegeMode?) {
        viewModelScope.launch { preferenceRepository.setPrivilegeMode(mode) }
    }

    fun setDefaultTab(tab: DefaultTab) {
        viewModelScope.launch { preferenceRepository.setDefaultTab(tab) }
    }

    fun setReinstallAllCardVisibility(visible: Boolean) {
        viewModelScope.launch { preferenceRepository.setReinstallAllCardVisibility(visible) }
    }

    fun setInstallerTileVisibility(visible: Boolean) {
        viewModelScope.launch { preferenceRepository.setInstallerTileVisibility(visible) }
    }

    fun setExtensionsTileVisibility(visible: Boolean) {
        viewModelScope.launch { preferenceRepository.setExtensionsTileVisibility(visible) }
    }

    fun setAutoReinstallEnabled(enabled: Boolean) {
        viewModelScope.launch { preferenceRepository.setAutoReinstallEnabled(enabled) }
    }

    /**
     * Applying the locale is conditional on the write, because these two steps disagree about how
     * long they last: `applyLocale` changes the running process now, the preference is what brings
     * the choice back next launch. Applying after a dropped write hands the user an app that
     * speaks the new language until they close it and then quietly reverts — a setting that looks
     * like it worked, then un-chooses itself, with nothing on screen ever having said otherwise.
     *
     * Leaving the UI in the old language instead makes the failure visible in the plainest way
     * available: the thing the user asked for did not happen. The message names the setting, since
     * the store-wide notice is suppressed for this call.
     */
    fun setLanguage(language: String?) {
        viewModelScope.launch {
            if (preferenceRepository.setLanguage(language)) {
                localeManager.applyLocale(language)
            } else {
                _events.send(UiText.StringResource(R.string.language_not_saved))
            }
        }
    }

    fun setAutoFreezeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferenceRepository.setAutoFreezeEnabled(enabled)
        }
    }

    fun setFreezerMode(mode: FreezerMode) {
        viewModelScope.launch {
            preferenceRepository.setFreezerMode(mode)
        }
    }

    fun setSkipRoutineFreezeConfirmation(enabled: Boolean) {
        viewModelScope.launch {
            preferenceRepository.setSkipRoutineFreezeConfirmation(enabled)
        }
    }

    fun setAddFreezerToLauncher(enabled: Boolean) {
        viewModelScope.launch {
            preferenceRepository.setAddFreezerToLauncher(enabled)
            freezerShortcutManager.syncDynamicShortcuts(enabled)
        }
    }

    /**
     * Restore every app on the freezer watchlist, through [BulkFreezeController] like every other
     * bulk run.
     *
     * This used to unfreeze here, and being the one watchlist-wide path outside the runner cost it
     * four things the runner already had. It fanned out with an unbounded `awaitAll`, so a large
     * watchlist put one concurrent privileged call per entry against a single shell; it had no
     * deadline, so a wedged binder left the toast pending forever; it asked for the pinned-icon
     * rebuild by hand, which is the kind of call that gets forgotten at the next surface; and it
     * left a parked freeze result in the tile subtitle describing apps it had just restored.
     *
     * It also over-reported. `getAllPackageNames` is the whole watchlist, and every entry was
     * unfrozen and counted, so "Unfroze 12 apps" included the ones that were already running.
     * `targetsFor` filters to the entries actually frozen, so the number now matches what changed.
     *
     * Single-app and small-selection unfreezes stay where they are: the runner's API is list-shaped
     * and watchlist-or-profile scoped, and they are neither.
     */
    fun unfreezeAll() {
        viewModelScope.launch {
            val outcome = bulkFreeze.launch(BulkRequest(BulkOp.UNFREEZE)).await()
            _events.send(
                when (outcome) {
                    is BulkOutcome.Completed -> bulkResultMessage(outcome.result)
                    // Naming the precondition beats "Unfroze 0 apps", which reads as a failed
                    // unfreeze. Which precondition matters: with a full watchlist and a dead
                    // Shizuku binder, "No apps in Freezer" is a statement the user can disprove by
                    // looking at the screen behind the toast, and it hides the one thing they could
                    // act on.
                    is BulkOutcome.NothingToDo -> UiText.StringResource(
                        when (outcome.reason) {
                            NoOpReason.NO_PRIVILEGE -> R.string.tile_grant_privilege_toast
                            NoOpReason.NO_TARGETS -> R.string.tile_no_apps_toast
                        }
                    )
                    // Not the same thing: the run raised, possibly after restoring part of the
                    // watchlist, so the one claim that must not be made is that nothing happened.
                    is BulkOutcome.Failed -> UiText.StringResource(R.string.bulk_run_failed)
                }
            )
        }
    }

    /**
     * Turn "show Thor when opening any file" on or off.
     *
     * Reads the state back instead of assuming the write landed: `setComponentEnabledSetting`
     * returns nothing and the platform can refuse it. A switch that flips optimistically would show
     * "on" for a filter that is still off, and the user's evidence for that is a file manager that
     * silently keeps not offering Thor — the exact symptom they enabled this to fix.
     */
    fun setAnyFileOpenerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val actual = anyFileOpenerMutex.withLock {
                anyFileOpenerController.setEnabled(enabled)
                anyFileOpenerController.isEnabled().also { anyFileOpenerEnabled.value = it }
            }
            // Reported outside the lock: _events is a BUFFERED Channel, so send() can suspend once
            // the buffer fills, and suspending there with the mutex held would stall every later
            // toggle behind a Toast nobody has collected yet.
            if (actual != enabled) {
                _events.send(UiText.StringResource(R.string.any_file_opener_failed))
            }
        }
    }

    /**
     * Re-read the alias state from PackageManager.
     *
     * Called on init and whenever Settings is resumed: the component can be changed from outside
     * Thor (`pm enable`, a ROM's own app manager), and PackageManager is the only source of truth,
     * so a value cached from last composition can be stale by the time it is shown.
     */
    fun refreshAnyFileOpener() {
        viewModelScope.launch {
            // Same lock as the writer: a resume that lands between a toggle's write and its
            // read-back would otherwise observe the half-applied state and publish it as the answer.
            anyFileOpenerMutex.withLock {
                anyFileOpenerEnabled.value = anyFileOpenerController.isEnabled()
            }
        }
    }

    fun setAnimationIntensity(intensity: AnimationIntensity) {
        viewModelScope.launch {
            preferenceRepository.setAnimationIntensity(intensity)
        }
    }

    fun setAppGridDensity(density: AppGridDensity) {
        viewModelScope.launch {
            preferenceRepository.setAppGridDensity(density)
        }
    }
}
