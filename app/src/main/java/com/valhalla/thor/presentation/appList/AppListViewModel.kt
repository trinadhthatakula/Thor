package com.valhalla.thor.presentation.appList

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.GetAppDetailsUseCase
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppListUiState(
    val isLoading: Boolean = true,
    val isRoot: Boolean = false,
    val isShizuku: Boolean = false,

    // Raw Data (Cache)
    val allUserApps: List<AppInfo> = emptyList(),
    val allSystemApps: List<AppInfo> = emptyList(),

    // Filter State
    val appListType: AppListType = AppListType.USER,
    val filterType: FilterType = FilterType.Source,
    val selectedFilter: String = "All",
    val sortBy: SortBy = SortBy.NAME,
    val sortOrder: SortOrder = SortOrder.ASCENDING,

    // Display Data
    val displayedApps: List<AppInfo> = emptyList(),
    val availableInstallers: List<String> = listOf("All"),

    // Detail View State
    val selectedAppDetails: AppInfo? = null,
    val isLoadingDetails: Boolean = false
)

class AppListViewModel(
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val getAppDetailsUseCase: GetAppDetailsUseCase,
    private val systemRepository: SystemRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AppListUiState())
    val uiState = _state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppListUiState()
    )

    private var filterJob: Job? = null


    fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true) }

            val hasRoot = systemRepository.isRootAvailable()
            val hasShizuku = systemRepository.isShizukuAvailable()

            getInstalledAppsUseCase().collect { (user, system) ->
                // Data arrived on IO thread.
                // Prepare the state update
                val partialState = _state.value.copy(
                    isLoading = false,
                    isRoot = hasRoot,
                    isShizuku = hasShizuku,
                    allUserApps = user,
                    allSystemApps = system
                )

                // Switch to Default (CPU) for sorting/filtering the initial list
                val finalState = processListState(partialState)

                _state.update { finalState }
            }
        }
    }

    // --- Actions ---

    fun selectApp(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) { // App details are heavy (Disk I/O)
            _state.update { it.copy(isLoadingDetails = true, selectedAppDetails = null) }

            getAppDetailsUseCase(packageName).onSuccess { fullDetails ->
                _state.update { it.copy(isLoadingDetails = false, selectedAppDetails = fullDetails) }
            }.onFailure {
                _state.update { it.copy(isLoadingDetails = false) }
            }
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedAppDetails = null) }
    }

    // --- Async Filter/Sort Updates ---
    // Every time user clicks a filter/sort button, we run logic on background
    // to keep the button click instant (ripple effect) without lag.

    fun updateListType(type: AppListType) {
        triggerAsyncUpdate { it.copy(appListType = type, selectedFilter = "All") }
    }

    fun updateFilter(filter: String) {
        triggerAsyncUpdate { it.copy(selectedFilter = filter) }
    }

    fun updateFilterType(type: FilterType) {
        triggerAsyncUpdate { it.copy(filterType = type, selectedFilter = "All") }
    }

    fun updateSort(sortBy: SortBy) {
        triggerAsyncUpdate { it.copy(sortBy = sortBy) }
    }

    fun updateSortOrder(order: SortOrder) {
        triggerAsyncUpdate { it.copy(sortOrder = order) }
    }

    private fun triggerAsyncUpdate(reducer: (AppListUiState) -> AppListUiState) {
        // Cancel previous calculation if user taps quickly
        filterJob?.cancel()
        filterJob = viewModelScope.launch(Dispatchers.Default) {
            val pendingState = reducer(_state.value)
            val finalState = processListState(pendingState)
            _state.update { finalState }
        }
    }

    // --- The Core Logic Engine (CPU Intensive) ---
    // Runs on Dispatchers.Default context via callers
    private suspend fun processListState(state: AppListUiState): AppListUiState = withContext(Dispatchers.Default) {
        // 1. Pick Source
        val rawList = if (state.appListType == AppListType.USER) state.allUserApps else state.allSystemApps

        // 2. Filter
        val filtered = when (state.filterType) {
            FilterType.Source -> {
                if (state.selectedFilter == "All") rawList
                else rawList.filter { it.installerPackageName == state.selectedFilter }
            }
            FilterType.State -> {
                when (state.selectedFilter) {
                    "Active" -> rawList.filter { it.enabled }
                    "Frozen" -> rawList.filter { !it.enabled }
                    else -> rawList
                }
            }
        }

        // 3. Sort
        val sorted = getSortedList(filtered, state.sortBy, state.sortOrder)

        // 4. Calculate Installers
        val installers = rawList.mapNotNull { it.installerPackageName }.distinct().sorted().toMutableList()
        installers.add(0, "All")

        state.copy(
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