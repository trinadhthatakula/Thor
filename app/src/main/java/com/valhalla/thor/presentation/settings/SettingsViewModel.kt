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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class SettingsViewModel(
    private val preferenceRepository: PreferenceRepository,
    private val systemRepository: SystemRepository,
    private val biometricHelper: BiometricHelper,
    private val localeManager: LocaleManager,
    private val freezerRepository: FreezerRepository,
    private val manageAppUseCase: ManageAppUseCase,
    private val freezerShortcutManager: com.valhalla.thor.data.launcher.FreezerShortcutManager,
) : ViewModel() {

    data class SettingsUiState(
        val prefs: UserPreferences = UserPreferences(),
        val isRootAvailable: Boolean = false,
        val isShizukuAvailable: Boolean = false,
        val isDhizukuAvailable: Boolean = false,
        val canUseBiometric: Boolean = false,
        val hasBiometricHardware: Boolean = false,
        val actionMessage: UiText? = null
    )

    /** Off-main-thread snapshot of the available privilege engines. */
    private data class PrivilegeProbe(
        val root: Boolean,
        val shizuku: Boolean,
        val dhizuku: Boolean
    )

    private val _actionMessage = MutableStateFlow<UiText?>(null)

    private val _systemStatus = combine(
        preferenceRepository.userPreferences,
        _actionMessage,
        flow {
            // Availability probes hit binder IPC (Shizuku.pingBinder / DhizukuAPI). flowOn(IO) below
            // keeps them off the Main thread to avoid janking the first subscription / every
            // WhileSubscribed restart.
            emit(
                PrivilegeProbe(
                    root = systemRepository.isRootAvailable(),
                    shizuku = systemRepository.isShizukuAvailable(),
                    dhizuku = systemRepository.isDhizukuAvailable()
                )
            )
        }.flowOn(Dispatchers.IO)
    ) { prefs, message, status ->
        SettingsUiState(
            prefs = prefs,
            isRootAvailable = status.root,
            isShizukuAvailable = status.shizuku,
            isDhizukuAvailable = status.dhizuku,
            canUseBiometric = biometricHelper.canAuthenticate(),
            hasBiometricHardware = biometricHelper.hasHardware(),
            actionMessage = message
        )
    }

    val uiState: StateFlow<SettingsUiState> = _systemStatus
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsUiState()
        )

    val preferences = preferenceRepository.userPreferences
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UserPreferences()
        )

    /**
     * Extension-consent as a TRI-STATE: `null` while prefs are still being read from DataStore, then
     * the real `true`/`false`. Screens gate the first-open consent sheet on `== false` (not on the
     * default-seeded `preferences`), so an already-accepted user never sees a first-frame flash.
     */
    val extensionConsentAccepted: StateFlow<Boolean?> = preferenceRepository.userPreferences
        .map { it.extensionConsentAccepted }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

    fun setExtensionConsentAccepted(accepted: Boolean) {
        viewModelScope.launch { preferenceRepository.setExtensionConsentAccepted(accepted) }
    }

    fun setLanguage(language: String?) {
        viewModelScope.launch {
            preferenceRepository.setLanguage(language)
            localeManager.applyLocale(language)
        }
    }

    fun consumeMessage() {
        _actionMessage.value = null
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
                _actionMessage.value = UiText.StringResource(R.string.tile_no_apps_toast)
                return@launch
            }
            val results = withContext(Dispatchers.IO) {
                pkgs.map { pkg ->
                    // forceUnfreeze restores BOTH disabled and suspended apps (not just enable).
                    async { manageAppUseCase.forceUnfreeze(pkg) }
                }.awaitAll()
            }
            val failures = results.count { it.isFailure }
            val uiText = if (failures == 0) {
                UiText.StringResource(R.string.unfrozen_count_success, pkgs.size)
            } else {
                UiText.StringResource(
                    R.string.tile_unfreeze_partial_failure,
                    pkgs.size - failures,
                    pkgs.size,
                    failures
                )
            }
            _actionMessage.value = uiText
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
