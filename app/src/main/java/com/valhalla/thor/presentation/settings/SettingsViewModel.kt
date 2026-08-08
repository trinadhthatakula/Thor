// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.AppGridDensity
import com.valhalla.thor.domain.model.DefaultTab
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.AnyFileOpenerController
import com.valhalla.thor.domain.repository.AuthCapability
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.presentation.security.biometricRefusalMessage
import com.valhalla.thor.util.LocaleManager
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

@KoinViewModel
class SettingsViewModel(
    private val preferenceRepository: PreferenceRepository,
    private val systemRepository: SystemRepository,
    private val biometricHelper: AuthCapability,
    private val localeManager: LocaleManager,
    private val freezerRepository: FreezerRepository,
    private val manageAppUseCase: ManageAppUseCase,
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
            preferenceRepository.setBiometricLock(enabled)
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

    fun setLanguage(language: String?) {
        viewModelScope.launch {
            preferenceRepository.setLanguage(language)
            localeManager.applyLocale(language)
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

    fun setAddFreezerToLauncher(enabled: Boolean) {
        viewModelScope.launch {
            preferenceRepository.setAddFreezerToLauncher(enabled)
            freezerShortcutManager.syncDynamicShortcuts(enabled)
        }
    }

    fun unfreezeAll() {
        viewModelScope.launch {
            val pkgs = freezerRepository.getAllPackageNames()
            if (pkgs.isEmpty()) {
                _events.send(UiText.StringResource(R.string.tile_no_apps_toast))
                return@launch
            }
            val results = withContext(ioDispatcher) {
                pkgs.map { pkg ->
                    // forceUnfreeze restores BOTH disabled and suspended apps (not just enable).
                    async { manageAppUseCase.forceUnfreeze(pkg) }
                }.awaitAll()
            }
            // This is the one bulk path that does not go through BulkFreezeRunner, so it does
            // not get the icon rebuild that hangs off the runner's completions. Ask for it
            // explicitly or pinned shortcuts stay grey for apps this just restored.
            freezerShortcutManager.refreshPinnedShortcutIcons()

            val failures = results.count { it.isFailure }
            val uiText = if (failures == 0) {
                UiText.PluralsResource(R.plurals.unfrozen_count_success, pkgs.size)
            } else {
                UiText.StringResource(
                    R.string.tile_unfreeze_partial_failure,
                    pkgs.size - failures,
                    pkgs.size,
                    failures
                )
            }
            _events.send(uiText)
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
            anyFileOpenerController.setEnabled(enabled)
            val actual = anyFileOpenerController.isEnabled()
            anyFileOpenerEnabled.value = actual
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
        viewModelScope.launch { anyFileOpenerEnabled.value = anyFileOpenerController.isEnabled() }
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
