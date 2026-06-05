package com.valhalla.thor.presentation.freezer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

// packageName + appName of an app frozen outside the freezer list — drives the "Add to Freezer" snackbar
data class FreezerPrompt(val packageName: String, val appName: String?)

data class FreezerUiState(
    val isLoading: Boolean = true,
    val isRoot: Boolean = false,
    val isShizuku: Boolean = false,
    val freezerApps: List<AppInfo> = emptyList(),
    val freezerPackageNames: Set<String> = emptySet(),
    val allInstalledApps: List<AppInfo> = emptyList(),
    val multiSelection: Set<String> = emptySet(),
    val searchQuery: String = "",
    val manageSheetSearchQuery: String = "",
    val actionMessage: UiText? = null,
    val freezerPrompt: FreezerPrompt? = null,
    val autoFreezeEnabled: Boolean = false,
    val isDhizuku: Boolean = false
)

@KoinViewModel
class FreezerViewModel(
    private val freezerRepository: FreezerRepository,
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val manageAppUseCase: ManageAppUseCase,
    private val systemRepository: SystemRepository,
    private val preferenceRepository: PreferenceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FreezerUiState())
    val uiState: StateFlow<FreezerUiState> = _uiState.asStateFlow()

    init {
        observeApps()
        observePreferences()
        loadPrivileges()
    }

    private fun observeApps() {
        viewModelScope.launch {
            try {
                combine(
                    freezerRepository.getAll(),
                    getInstalledAppsUseCase()
                ) { freezerPkgs, (userApps, systemApps) ->
                    val pkgSet = freezerPkgs.toSet()
                    val allApps = userApps + systemApps
                    Triple(pkgSet, allApps.filter { it.packageName in pkgSet }, allApps)
                }
                    .flowOn(Dispatchers.Default)
                    .collect { (pkgSet, freezerApps, allApps) ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                freezerPackageNames = pkgSet,
                                freezerApps = freezerApps,
                                allInstalledApps = allApps
                            )
                        }
                    }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        actionMessage = UiText.StringResource(R.string.failed_to_load_apps)
                    )
                }
            }
        }
    }

    private fun loadPrivileges() {
        viewModelScope.launch(Dispatchers.IO) {
            val hasRoot = systemRepository.isRootAvailable()
            val hasShizuku = systemRepository.isShizukuAvailable()
            val hasDhizuku = systemRepository.isDhizukuAvailable()
            _uiState.update {
                it.copy(
                    isRoot = hasRoot,
                    isShizuku = hasShizuku,
                    isDhizuku = hasDhizuku
                )
            }
        }
    }

    // --- Freeze All / Unfreeze All ---

    fun freezeAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val pkgs = _uiState.value.freezerPackageNames.toList()
            val results = pkgs.map { pkg ->
                async { manageAppUseCase.setAppDisabled(pkg, true) }
            }.awaitAll()
            val failures = results.count { it.isFailure }
            val uiText = if (failures == 0) {
                UiText.StringResource(R.string.tile_freeze_success, pkgs.size)
            } else {
                UiText.StringResource(
                    R.string.tile_freeze_partial_failure,
                    pkgs.size - failures,
                    pkgs.size,
                    failures
                )
            }
            _uiState.update { it.copy(actionMessage = uiText) }
        }
    }

    fun unfreezeAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val pkgs = _uiState.value.freezerPackageNames.toList()
            val results = pkgs.map { pkg ->
                async { manageAppUseCase.setAppDisabled(pkg, false) }
            }.awaitAll()
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
            _uiState.update { it.copy(actionMessage = uiText) }
        }
    }

    // --- Multi-select removal ---

    fun toggleSelection(packageName: String) {
        _uiState.update {
            val sel = it.multiSelection.toMutableSet()
            if (packageName in sel) sel.remove(packageName) else sel.add(packageName)
            it.copy(multiSelection = sel)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(multiSelection = emptySet()) }
    }

    fun selectAll() {
        _uiState.update { it.copy(multiSelection = it.freezerPackageNames.toSet()) }
    }

    fun removeFromFreezer(packageNames: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            packageNames.forEach { pkg ->
                freezerRepository.remove(pkg)
                manageAppUseCase.setAppDisabled(pkg, false)
            }
            _uiState.update {
                it.copy(
                    multiSelection = emptySet(),
                    actionMessage = UiText.StringResource(
                        R.string.removed_from_freezer_success,
                        packageNames.size
                    )
                )
            }
        }
    }

    // --- Manage Sheet ---

    fun toggleManaged(packageName: String, add: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (add) {
                manageAppUseCase.setAppDisabled(packageName, true)
                    .onSuccess {
                        freezerRepository.add(packageName)
                    }
                    .onFailure { e ->
                        _uiState.update {
                            it.copy(
                                actionMessage = UiText.StringResource(
                                    R.string.error_format,
                                    e.message ?: ""
                                )
                            )
                        }
                    }
            } else {
                freezerRepository.remove(packageName)
                manageAppUseCase.setAppDisabled(packageName, false)
                    .onFailure { e ->
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
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateManageSheetSearch(query: String) {
        _uiState.update { it.copy(manageSheetSearchQuery = query) }
    }

    // --- Snackbar from AppInfoDialog (app frozen outside freezer) ---

    fun addToFreezer(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            freezerRepository.add(packageName)
            _uiState.update {
                it.copy(
                    freezerPrompt = null,
                    actionMessage = UiText.StringResource(R.string.added_to_freezer_success)
                )
            }
        }
    }

    fun showFreezerPrompt(packageName: String, appName: String?) {
        _uiState.update { it.copy(freezerPrompt = FreezerPrompt(packageName, appName)) }
    }

    fun dismissFreezerPrompt() {
        _uiState.update { it.copy(freezerPrompt = null) }
    }

    // --- Single-app freeze/unfreeze (called from AppInfoDialog in FreezerScreen) ---

    fun freezeSingleApp(packageName: String, appName: String?, inFreezer: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            manageAppUseCase.setAppDisabled(packageName, true)
                .onSuccess {
                    if (!inFreezer) {
                        _uiState.update {
                            it.copy(
                                freezerPrompt = FreezerPrompt(
                                    packageName,
                                    appName
                                )
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                actionMessage = UiText.StringResource(
                                    R.string.frozen_success,
                                    appName ?: packageName
                                )
                            )
                        }
                    }
                }
                .onFailure { e ->
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

    fun unfreezeSingleApp(packageName: String, appName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            manageAppUseCase.setAppDisabled(packageName, false)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            actionMessage = UiText.StringResource(
                                R.string.unfrozen_success,
                                appName ?: packageName
                            )
                        )
                    }
                }
                .onFailure { e ->
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

    // --- Feedback ---

    fun dismissMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferenceRepository.userPreferences.collect { prefs ->
                _uiState.update { it.copy(autoFreezeEnabled = prefs.autoFreezeEnabled) }
            }
        }
    }

    fun setAutoFreezeEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            preferenceRepository.setAutoFreezeEnabled(enabled)
        }
    }
}
