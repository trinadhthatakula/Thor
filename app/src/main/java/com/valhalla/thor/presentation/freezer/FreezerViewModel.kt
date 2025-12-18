package com.valhalla.thor.presentation.freezer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FreezerUiState(
    val isLoading: Boolean = true,
    // Privileges
    val isRoot: Boolean = false,
    val isShizuku: Boolean = false,
    // Complete App Lists
    val allUserApps: List<AppInfo> = emptyList(),
    val allSystemApps: List<AppInfo> = emptyList(),
    // Display State
    val displayedApps: List<AppInfo> = emptyList(),
    val appListType: AppListType = AppListType.USER,
    // Freezer defaults to showing "Frozen" apps first, usually
    val filterType: FilterType = FilterType.State,
    val selectedFilter: String = "Frozen",
    val sortBy: SortBy = SortBy.NAME,
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    val availableInstallers: List<String> = listOf("All"),
    // Action Feedback
    val actionMessage: String? = null
)

class FreezerViewModel(
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val manageAppUseCase: ManageAppUseCase,
    private val systemRepository: SystemRepository
) : ViewModel() {

    private val _state = MutableStateFlow(FreezerUiState())
    val uiState = _state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        FreezerUiState()
    )

    init {
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            // Check privileges
            val hasRoot = systemRepository.isRootAvailable()
            val hasShizuku = systemRepository.isShizukuAvailable()
            getInstalledAppsUseCase().collect { (user, system) ->
                _state.update {
                    val newState = it.copy(
                        isLoading = false,
                        isRoot = hasRoot,
                        isShizuku = hasShizuku,
                        allUserApps = user,
                        allSystemApps = system
                    )
                    processList(newState)
                }
            }
        }
    }

    // --- Actions ---

    fun toggleAppFreezeState(app: AppInfo) {
        viewModelScope.launch {
            val shouldFreeze = app.enabled // If enabled, we freeze. If disabled, we unfreeze.
            val result = manageAppUseCase.setAppDisabled(app.packageName, shouldFreeze)

            result.onSuccess {
                _state.update { s -> s.copy(actionMessage = "${if(shouldFreeze) "Frozen" else "Unfrozen"} ${app.appName}") }
                // Note: The list update happens automatically because GetInstalledAppsUseCase
                // should emit new values if the repo observes changes,
                // OR we need to manually trigger a reload if the flow isn't hot on package changes.
                // For now, let's assume we might need a manual refresh or the flow updates.
                loadApps()
            }.onFailure { e ->
                _state.update { s -> s.copy(actionMessage = "Error: ${e.message}") }
            }
        }
    }

    fun performMultiAction(action: MultiAppAction) {
        viewModelScope.launch {
            when (action) {
                is MultiAppAction.Freeze -> {
                    action.appList.forEach { manageAppUseCase.setAppDisabled(it.packageName, true) }
                    _state.update { it.copy(actionMessage = "Froze ${action.appList.size} apps") }
                }
                is MultiAppAction.UnFreeze -> {
                    action.appList.forEach { manageAppUseCase.setAppDisabled(it.packageName, false) }
                    _state.update { it.copy(actionMessage = "Unfrozen ${action.appList.size} apps") }
                }
                else -> {
                    _state.update { it.copy(actionMessage = "Action not supported in Freezer yet") }
                }
            }
            loadApps()
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(actionMessage = null) }
    }

    // --- Filter / Sort Updates (Boilerplate similar to AppList, but essential for independence) ---

    fun updateListType(type: AppListType) {
        _state.update { processList(it.copy(appListType = type)) }
    }

    fun updateFilter(filter: String) {
        _state.update { processList(it.copy(selectedFilter = filter)) }
    }

    fun updateFilterType(type: FilterType) {
        _state.update { processList(it.copy(filterType = type, selectedFilter = if(type == FilterType.State) "Frozen" else "All")) }
    }

    fun updateSort(sortBy: SortBy) {
        _state.update { processList(it.copy(sortBy = sortBy)) }
    }

    fun updateSortOrder(order: SortOrder) {
        _state.update { processList(it.copy(sortOrder = order)) }
    }

    private fun processList(state: FreezerUiState): FreezerUiState {
        val rawList = if (state.appListType == AppListType.USER) state.allUserApps else state.allSystemApps

        // Filter
        val filtered = when (state.filterType) {
            FilterType.State -> {
                when (state.selectedFilter) {
                    "Frozen" -> rawList.filter { !it.enabled }
                    "Active" -> rawList.filter { it.enabled }
                    else -> rawList
                }
            }
            FilterType.Source -> {
                if (state.selectedFilter == "All") rawList
                else rawList.filter { it.installerPackageName == state.selectedFilter }
            }
        }

        // Sort
        val sorted = getSortedList(filtered, state.sortBy, state.sortOrder)

        // Metadata
        val installers = rawList.mapNotNull { it.installerPackageName }.distinct().sorted().toMutableList()
        installers.add(0, "All")

        return state.copy(
            displayedApps = sorted,
            availableInstallers = installers
        )
    }

    private fun getSortedList(list: List<AppInfo>, sortBy: SortBy, order: SortOrder): List<AppInfo> {
        val comparator = when (sortBy) {
            SortBy.NAME -> compareBy<AppInfo> { it.appName?.lowercase() }
            SortBy.INSTALL_DATE -> compareBy { it.firstInstallTime }
            SortBy.LAST_UPDATED -> compareBy { it.lastUpdateTime }
            SortBy.VERSION_CODE -> compareBy { it.versionCode }
            SortBy.VERSION_NAME -> compareBy { it.versionName }
            SortBy.TARGET_SDK_VERSION -> compareBy { it.targetSdk }
            SortBy.MIN_SDK_VERSION -> compareBy { it.minSdk }
        }
        return if (order == SortOrder.ASCENDING) list.sortedWith(comparator)
        else list.sortedWith(comparator).reversed()
    }
}