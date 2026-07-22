package com.valhalla.thor.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.data.security.BiometricHelper
import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.LocaleManager
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
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
    private val biometricHelper: BiometricHelper,
    private val localeManager: LocaleManager,
    private val freezerRepository: FreezerRepository,
    private val manageAppUseCase: ManageAppUseCase,
    private val freezerShortcutManager: com.valhalla.thor.data.launcher.FreezerShortcutManager,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    data class SettingsUiState(
        val prefs: UserPreferences = UserPreferences(),
        val isRootAvailable: Boolean = false,
        val isShizukuAvailable: Boolean = false,
        val isDhizukuAvailable: Boolean = false,
        val canUseBiometric: Boolean = false,
        val hasBiometricHardware: Boolean = false
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

    private val _systemStatus = combine(
        preferenceRepository.userPreferences,
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
    ) { prefs, status ->
        SettingsUiState(
            prefs = prefs,
            isRootAvailable = status.root,
            isShizukuAvailable = status.shizuku,
            isDhizukuAvailable = status.dhizuku,
            canUseBiometric = biometricHelper.canAuthenticate(),
            hasBiometricHardware = biometricHelper.hasHardware()
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

    fun setBiometricLock(enabled: Boolean) {
        viewModelScope.launch { preferenceRepository.setBiometricLock(enabled) }
    }

    fun setPrivilegeMode(mode: PrivilegeMode?) {
        viewModelScope.launch { preferenceRepository.setPrivilegeMode(mode) }
    }

    fun setReinstallAllCardVisibility(visible: Boolean) {
        viewModelScope.launch { preferenceRepository.setReinstallAllCardVisibility(visible) }
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

    fun setDetailedViewEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferenceRepository.setDetailedViewEnabled(enabled)
        }
    }

    fun setAnimationIntensity(intensity: AnimationIntensity) {
        viewModelScope.launch {
            preferenceRepository.setAnimationIntensity(intensity)
        }
    }
}
