package com.valhalla.thor.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.data.corepatch.CorePatchArmStateHolder
import com.valhalla.thor.data.corepatch.CorePatchAudit
import com.valhalla.thor.data.corepatch.CorePatchAuditEntry
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
    private val extensionManager: com.valhalla.thor.data.manager.ExtensionManager,
    private val corePatchAudit: CorePatchAudit,
    private val corePatchArmStateHolder: CorePatchArmStateHolder
) : ViewModel() {

    data class SettingsUiState(
        val prefs: UserPreferences = UserPreferences(),
        val isRootAvailable: Boolean = false,
        val isShizukuAvailable: Boolean = false,
        val isDhizukuAvailable: Boolean = false,
        // Best-effort proxy for "the Strombringer LSPosed module is present" — gates the CorePatch
        // danger-zone toggle. Precise hook-active detection is a follow-up (see ExtensionManager).
        val lsposedActive: Boolean = false,
        val canUseBiometric: Boolean = false,
        val hasBiometricHardware: Boolean = false,
        val actionMessage: UiText? = null
    )

    /** Off-main-thread snapshot of the available privilege engines plus the LSPosed-module probe. */
    private data class PrivilegeProbe(
        val root: Boolean,
        val shizuku: Boolean,
        val dhizuku: Boolean,
        val lsposedActive: Boolean
    )

    private val _actionMessage = MutableStateFlow<UiText?>(null)

    private val _systemStatus = combine(
        preferenceRepository.userPreferences,
        _actionMessage,
        flow {
            // Availability probes hit binder IPC (Shizuku.pingBinder / DhizukuAPI) and the
            // Strombringer probe hits PackageManager. flowOn(IO) below keeps them off the Main
            // thread to avoid janking the first subscription / every WhileSubscribed restart.
            emit(
                PrivilegeProbe(
                    root = systemRepository.isRootAvailable(),
                    shizuku = systemRepository.isShizukuAvailable(),
                    dhizuku = systemRepository.isDhizukuAvailable(),
                    lsposedActive = extensionManager.isStrombringerInstalled()
                )
            )
        }.flowOn(Dispatchers.IO)
    ) { prefs, message, status ->
        SettingsUiState(
            prefs = prefs,
            isRootAvailable = status.root,
            isShizukuAvailable = status.shizuku,
            isDhizukuAvailable = status.dhizuku,
            lsposedActive = status.lsposedActive,
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

    /**
     * Master opt-in for CorePatch (Xposed signature-bypass). Enabling is gated behind the
     * type-to-confirm dialog in the UI; disabling is the fail-safe direction and needs no confirm.
     */
    fun setCorePatchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferenceRepository.setCorePatchEnabled(enabled)
        }
    }

    /**
     * Snapshot of the CorePatch audit trail, newest first. The audit is an in-memory ring buffer
     * ([CorePatchAudit]); reading it is cheap, so the viewer reads a fresh snapshot on entry.
     */
    fun corePatchAuditEntries(): List<CorePatchAuditEntry> =
        corePatchAudit.all().sortedByDescending { it.timestampMillis }

    /**
     * One-tap kill-switch: flips the durable `corePatchEnabled` pref off AND disarms the in-memory
     * arm-state so any pending per-operation bypass is revoked immediately. Fail-safe direction —
     * no confirmation needed.
     */
    fun disableAllBypasses() {
        corePatchArmStateHolder.disarm()
        viewModelScope.launch {
            preferenceRepository.setCorePatchEnabled(false)
        }
        _actionMessage.value = UiText.StringResource(R.string.core_patch_kill_switch_done)
    }
}
