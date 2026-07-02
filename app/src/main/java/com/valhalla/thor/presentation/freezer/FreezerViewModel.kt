package com.valhalla.thor.presentation.freezer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.presentation.common.ShizukuPermissionHandler
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.UiText
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
    val actionMessage: UiText? = null,
    val freezerPrompt: FreezerPrompt? = null,
    val autoFreezeEnabled: Boolean = false,
    val isDhizuku: Boolean = false,
    val hasShownDisabledAppsPrompt: Boolean = false,
    val appListType: AppListType = AppListType.USER,
    val isGrid: Boolean = true
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

    // Re-check privileges when Shizuku's binder connects or the user grants permission.
    // This ViewModel is created at app start (before the first-launch Shizuku grant), so
    // its init-time loadPrivileges() would otherwise stay stale until an app restart.
    private val shizukuHandler = ShizukuPermissionHandler(
        onPermissionGranted = { loadPrivileges() }
    )

    init {
        observeApps()
        observePreferences()
        loadPrivileges()
        shizukuHandler.register()
    }

    override fun onCleared() {
        shizukuHandler.unregister()
        super.onCleared()
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
                Logger.e("FreezeViewModel", "observe apps failed", e)
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

    // Freeze-all / Unfreeze-all are handled by the shared batch action
    // (MultiAppAction.Freeze / UnFreeze) so their progress streams into the
    // TermLoggerDialog; the toolbar in FreezerScreen dispatches them via onMultiAppAction.

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

    fun selectAll(packageNames: Collection<String> = _uiState.value.freezerPackageNames) {
        _uiState.update { it.copy(multiSelection = packageNames.toSet()) }
    }

    fun updateListType(type: AppListType) {
        _uiState.update { it.copy(appListType = type) }
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
                _uiState.update {
                    it.copy(
                        autoFreezeEnabled = prefs.autoFreezeEnabled,
                        hasShownDisabledAppsPrompt = prefs.hasShownDisabledAppsPrompt,
                        isGrid = prefs.freezerIsGrid
                    )
                }
            }
        }
    }

    fun setAutoFreezeEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            preferenceRepository.setAutoFreezeEnabled(enabled)
        }
    }

    fun markDisabledAppsPromptShown() {
        viewModelScope.launch(Dispatchers.IO) {
            preferenceRepository.setHasShownDisabledAppsPrompt(true)
        }
    }

    fun toggleGridMode() {
        viewModelScope.launch(Dispatchers.IO) {
            preferenceRepository.toggleFreezerIsGrid()
        }
    }

    fun addAppsToFreezer(packageNames: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            packageNames.forEach { pkg ->
                freezerRepository.add(pkg)
            }
            _uiState.update {
                it.copy(
                    actionMessage = UiText.StringResource(
                        R.string.added_to_freezer_count_success,
                        packageNames.size
                    )
                )
            }
        }
    }
}
