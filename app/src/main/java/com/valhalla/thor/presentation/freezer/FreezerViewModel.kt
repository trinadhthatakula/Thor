package com.valhalla.thor.presentation.freezer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import kotlinx.coroutines.Dispatchers
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
    val actionMessage: String? = null,
    val freezerPrompt: FreezerPrompt? = null
)

@KoinViewModel
class FreezerViewModel(
    private val freezerRepository: FreezerRepository,
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val manageAppUseCase: ManageAppUseCase,
    private val systemRepository: SystemRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FreezerUiState())
    val uiState: StateFlow<FreezerUiState> = _uiState.asStateFlow()

    init {
        observeApps()
        loadPrivileges()
    }

    private fun observeApps() {
        viewModelScope.launch {
            try {
                combine(
                    freezerRepository.getAll(),
                    getInstalledAppsUseCase()
                ) { freezerPkgs, (userApps, _) ->
                    val pkgSet = freezerPkgs.toSet()
                    Triple(pkgSet, userApps.filter { it.packageName in pkgSet }, userApps)
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
                _uiState.update { it.copy(isLoading = false, actionMessage = "Failed to load apps") }
            }
        }
    }

    private fun loadPrivileges() {
        viewModelScope.launch(Dispatchers.IO) {
            val hasRoot = systemRepository.isRootAvailable()
            val hasShizuku = systemRepository.isShizukuAvailable()
            _uiState.update { it.copy(isRoot = hasRoot, isShizuku = hasShizuku) }
        }
    }

    // --- Freeze All / Unfreeze All ---

    fun freezeAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val pkgs = _uiState.value.freezerPackageNames.toList()
            var failures = 0
            pkgs.forEach { pkg ->
                manageAppUseCase.setAppDisabled(pkg, true).onFailure { failures++ }
            }
            val msg = if (failures == 0) "Froze ${pkgs.size} apps"
                      else "Froze ${pkgs.size - failures}/${pkgs.size} apps (${failures} failed)"
            _uiState.update { it.copy(actionMessage = msg) }
        }
    }

    fun unfreezeAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val pkgs = _uiState.value.freezerPackageNames.toList()
            var failures = 0
            pkgs.forEach { pkg ->
                manageAppUseCase.setAppDisabled(pkg, false).onFailure { failures++ }
            }
            val msg = if (failures == 0) "Unfroze ${pkgs.size} apps"
                      else "Unfroze ${pkgs.size - failures}/${pkgs.size} apps (${failures} failed)"
            _uiState.update { it.copy(actionMessage = msg) }
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
            _uiState.update { it.copy(multiSelection = emptySet(), actionMessage = "Removed ${packageNames.size} app(s) from Freezer") }
        }
    }

    // --- Manage Sheet ---

    fun toggleManaged(packageName: String, add: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (add) {
                freezerRepository.add(packageName)
                manageAppUseCase.setAppDisabled(packageName, true)
            } else {
                freezerRepository.remove(packageName)
                manageAppUseCase.setAppDisabled(packageName, false)
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
            _uiState.update { it.copy(freezerPrompt = null, actionMessage = "Added to Freezer") }
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
                        _uiState.update { it.copy(freezerPrompt = FreezerPrompt(packageName, appName)) }
                    } else {
                        _uiState.update { it.copy(actionMessage = "Frozen ${appName ?: packageName}") }
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(actionMessage = "Failed: ${e.message}") }
                }
        }
    }

    fun unfreezeSingleApp(packageName: String, appName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            manageAppUseCase.setAppDisabled(packageName, false)
                .onSuccess {
                    _uiState.update { it.copy(actionMessage = "Unfrozen ${appName ?: packageName}") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(actionMessage = "Failed: ${e.message}") }
                }
        }
    }

    // --- Feedback ---

    fun dismissMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }
}
