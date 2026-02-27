package com.valhalla.thor.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.security.BiometricHelper
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferenceRepository: PreferenceRepository,
    private val biometricHelper: BiometricHelper
) : ViewModel() {

    val preferences = preferenceRepository.userPreferences
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UserPreferences()
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
}
