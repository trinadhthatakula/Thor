package com.valhalla.thor.presentation.appList

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.data.launcher.FreezerShortcutManager
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
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
    val actionMessage: UiText? = null,
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

    fun toggleFreezerState(packageName: String, appName: String?, freeze: Boolean) {
        viewModelScope.launch {
            val result = manageAppUseCase.setAppDisabled(packageName, freeze)
            result.onSuccess {
                val updatedInFreezer = withContext(Dispatchers.IO) {
                    if (freeze && !freezerRepository.contains(packageName)) {
                        freezerRepository.add(packageName)
                    }
                    freezerRepository.contains(packageName)
                }

                val msgRes = if (freeze) R.string.frozen_success else R.string.unfrozen_success
                _uiState.update {
                    it.copy(
                        actionMessage = UiText.StringResource(msgRes, appName ?: packageName),
                        isInFreezer = updatedInFreezer
                    )
                }
                // Reload state
                loadAppDetails(packageName)
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        actionMessage = UiText.StringResource(
                            R.string.error_format,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    fun toggleSuspendState(packageName: String, suspend: Boolean) {
        viewModelScope.launch {
            val result = manageAppUseCase.setAppSuspended(packageName, suspend)
            result.onSuccess {
                // Reload state
                loadAppDetails(packageName)
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        actionMessage = UiText.StringResource(
                            R.string.error_format,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    fun forceStopApp(packageName: String) {
        viewModelScope.launch {
            val result = manageAppUseCase.forceStop(packageName)
            result.onSuccess {
                val appName = _uiState.value.detailedInfo?.appInfo?.appName ?: packageName
                _uiState.update {
                    it.copy(actionMessage = UiText.StringResource(R.string.killed_success, appName))
                }
                loadAppDetails(packageName)
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        actionMessage = UiText.StringResource(
                            R.string.error_format,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    fun clearCache(packageName: String) {
        viewModelScope.launch {
            val result = manageAppUseCase.clearCache(packageName)
            result.onSuccess {
                val appName = _uiState.value.detailedInfo?.appInfo?.appName ?: packageName
                _uiState.update {
                    it.copy(
                        actionMessage = UiText.StringResource(
                            R.string.cache_cleared_success,
                            appName
                        )
                    )
                }
                loadAppDetails(packageName)
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        actionMessage = UiText.StringResource(
                            R.string.error_format,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    fun clearData(packageName: String) {
        viewModelScope.launch {
            val result = manageAppUseCase.clearAppData(packageName)
            result.onSuccess {
                val appName = _uiState.value.detailedInfo?.appInfo?.appName ?: packageName
                _uiState.update {
                    it.copy(
                        actionMessage = UiText.StringResource(
                            R.string.data_cleared_success,
                            appName
                        )
                    )
                }
                loadAppDetails(packageName)
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        actionMessage = UiText.StringResource(
                            R.string.error_format,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    fun addOrRemoveFromFreezer(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentlyIn = freezerRepository.contains(packageName)
            if (currentlyIn) {
                freezerRepository.remove(packageName)
                freezerShortcutManager.disableAppShortcut(packageName)
                _uiState.update {
                    it.copy(
                        isInFreezer = false,
                        actionMessage = UiText.StringResource(
                            R.string.removed_from_freezer_success,
                            1
                        )
                    )
                }
            } else {
                freezerRepository.add(packageName)
                _uiState.update {
                    it.copy(
                        isInFreezer = true,
                        actionMessage = UiText.StringResource(R.string.added_to_freezer_success)
                    )
                }
            }
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }
}
