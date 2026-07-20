package com.valhalla.thor.presentation.appList

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.data.launcher.FreezerShortcutManager
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.presentation.freezer.FreezerPrompt
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

data class AppInfoDetailsUiState(
    val isLoading: Boolean = true,
    val isRoot: Boolean = false,
    val isShizuku: Boolean = false,
    val isDhizuku: Boolean = false,
    val detailedInfo: DetailedAppInfo? = null,
    val isInFreezer: Boolean = false,
    val freezerPrompt: FreezerPrompt? = null,
    val errorMessage: UiText? = null
)

@KoinViewModel
class AppInfoDetailsViewModel(
    private val appRepository: AppRepository,
    private val systemRepository: SystemRepository,
    private val manageAppUseCase: ManageAppUseCase,
    private val freezerRepository: FreezerRepository,
    private val freezerShortcutManager: FreezerShortcutManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppInfoDetailsUiState())
    val uiState = _uiState.asStateFlow()

    // One-off toast feedback lives here (not in UiState) so it fires exactly once and is never
    // replayed on recomposition or config change.
    private val _events = MutableSharedFlow<UiText>(replay = 0)
    val events: SharedFlow<UiText> = _events.asSharedFlow()

    fun loadAppDetails(packageName: String) {
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            // Availability probes include non-suspend binder IPC (Shizuku / Dhizuku) and a
            // potentially slow root check; run them off the Main thread. Each probe is an
            // independent round-trip, so launch them concurrently and let their latency
            // overlap (alongside the freezer lookup) instead of stacking sequentially.
            val (probes, inFreezer) = withContext(Dispatchers.IO) {
                val rootProbe = async { systemRepository.isRootAvailable() }
                val shizukuProbe = async { systemRepository.isShizukuAvailable() }
                val dhizukuProbe = async { systemRepository.isDhizukuAvailable() }
                val freezer = freezerRepository.contains(packageName)
                Triple(
                    rootProbe.await(),
                    shizukuProbe.await(),
                    dhizukuProbe.await()
                ) to freezer
            }
            val (hasRoot, hasShizuku, hasDhizuku) = probes

            val details = appRepository.getDetailedAppInfo(packageName)
            if (details != null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRoot = hasRoot,
                        isShizuku = hasShizuku,
                        isDhizuku = hasDhizuku,
                        detailedInfo = details,
                        isInFreezer = inFreezer
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiText.StringResource(R.string.failed_to_load_app_details)
                    )
                }
            }
        }
    }

    /**
     * Lighter reload used after a mutating action succeeds. Re-reads the detailed info and freezer
     * membership so the UI reflects the new state, but deliberately skips the Root / Shizuku /
     * Dhizuku availability probes (privilege mode doesn't change mid-session — it's probed once by
     * [loadAppDetails]) and never flips [AppInfoDetailsUiState.isLoading], so the screen doesn't
     * flash the loader after every freeze / suspend / force-stop / clear action.
     */
    private fun refreshDetails(packageName: String) {
        viewModelScope.launch {
            val inFreezer = withContext(Dispatchers.IO) { freezerRepository.contains(packageName) }
            val details = appRepository.getDetailedAppInfo(packageName)
            if (details != null) {
                _uiState.update {
                    it.copy(
                        detailedInfo = details,
                        isInFreezer = inFreezer
                    )
                }
            }
        }
    }

    fun toggleFreezerState(packageName: String, appName: String?, freeze: Boolean) {
        viewModelScope.launch {
            val result = manageAppUseCase.setAppDisabled(packageName, freeze)
            result.onSuccess {
                freezerShortcutManager.refreshAppShortcut(packageName)
                val inFreezer = withContext(Dispatchers.IO) { freezerRepository.contains(packageName) }
                if (freeze && !inFreezer) {
                    // Don't auto-add — prompt the user to add it to the Freezer instead.
                    _uiState.update { it.copy(freezerPrompt = FreezerPrompt(packageName, appName)) }
                } else {
                    val msgRes = if (freeze) R.string.frozen_success else R.string.unfrozen_success
                    _uiState.update { it.copy(isInFreezer = inFreezer) }
                    _events.emit(UiText.StringResource(msgRes, appName ?: packageName))
                }
                // Refresh detail only — no privilege re-probe, no loader flash.
                refreshDetails(packageName)
            }.onFailure { e ->
                _events.emit(UiText.StringResource(R.string.error_format, e.message ?: ""))
            }
        }
    }

    fun toggleSuspendState(packageName: String, suspend: Boolean) {
        viewModelScope.launch {
            val result = manageAppUseCase.setAppSuspended(packageName, suspend)
            result.onSuccess {
                // Refresh detail only — no privilege re-probe, no loader flash.
                refreshDetails(packageName)
            }.onFailure { e ->
                _events.emit(UiText.StringResource(R.string.error_format, e.message ?: ""))
            }
        }
    }

    fun forceStopApp(packageName: String) {
        viewModelScope.launch {
            val result = manageAppUseCase.forceStop(packageName)
            result.onSuccess {
                val appName = _uiState.value.detailedInfo?.appInfo?.appName ?: packageName
                _events.emit(UiText.StringResource(R.string.killed_success, appName))
                refreshDetails(packageName)
            }.onFailure { e ->
                _events.emit(UiText.StringResource(R.string.error_format, e.message ?: ""))
            }
        }
    }

    fun clearCache(packageName: String) {
        viewModelScope.launch {
            val result = manageAppUseCase.clearCache(packageName)
            result.onSuccess {
                val appName = _uiState.value.detailedInfo?.appInfo?.appName ?: packageName
                _events.emit(UiText.StringResource(R.string.cache_cleared_success, appName))
                refreshDetails(packageName)
            }.onFailure { e ->
                _events.emit(UiText.StringResource(R.string.error_format, e.message ?: ""))
            }
        }
    }

    fun clearData(packageName: String) {
        viewModelScope.launch {
            val result = manageAppUseCase.clearAppData(packageName)
            result.onSuccess {
                val appName = _uiState.value.detailedInfo?.appInfo?.appName ?: packageName
                _events.emit(UiText.StringResource(R.string.data_cleared_success, appName))
                refreshDetails(packageName)
            }.onFailure { e ->
                _events.emit(UiText.StringResource(R.string.error_format, e.message ?: ""))
            }
        }
    }

    fun addToFreezer(packageName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { freezerRepository.add(packageName) }
            _uiState.update { it.copy(freezerPrompt = null, isInFreezer = true) }
            refreshDetails(packageName)
        }
    }

    fun dismissFreezerPrompt() {
        _uiState.update { it.copy(freezerPrompt = null) }
    }

    fun addOrRemoveFromFreezer(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentlyIn = freezerRepository.contains(packageName)
            if (currentlyIn) {
                freezerRepository.remove(packageName)
                freezerShortcutManager.disableAppShortcut(packageName)
                _uiState.update { it.copy(isInFreezer = false) }
                _events.emit(UiText.StringResource(R.string.removed_from_freezer_success, 1))
            } else {
                freezerRepository.add(packageName)
                _uiState.update { it.copy(isInFreezer = true) }
                _events.emit(UiText.StringResource(R.string.added_to_freezer_success))
            }
        }
    }
}
