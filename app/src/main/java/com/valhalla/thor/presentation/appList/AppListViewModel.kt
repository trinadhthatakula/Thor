package com.valhalla.thor.presentation.appList

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.repository.PreferenceRepository // Injected
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.GetAppDetailsUseCase
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ... AppListUiState remains same ...
data class AppListUiState(
    val isLoading: Boolean = true,
    // Privileges
    val isRoot: Boolean = false,
    val isShizuku: Boolean = false,
    // Raw Data
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
    private val systemRepository: SystemRepository,
    private val preferenceRepository: PreferenceRepository // Injected
) : ViewModel() {

    private val _rawState = MutableStateFlow(AppListUiState())

    // Combine raw app data with user preferences from DataStore
    val uiState = combine(_rawState, preferenceRepository.userPreferences) { state, prefs ->
        val mergedState = state.copy(
            sortBy = prefs.appSortBy,
            sortOrder = prefs.appSortOrder,
            filterType = prefs.appFilterType,
            selectedFilter = prefs.appSelectedFilter
        )
        processList(mergedState)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppListUiState()
    )

    init {
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch {
            _rawState.update { it.copy(isLoading = true) }
            val hasRoot = systemRepository.isRootAvailable()
            val hasShizuku = systemRepository.isShizukuAvailable()
            getInstalledAppsUseCase().collect { (user, system) ->
                _rawState.update {
                    it.copy(
                        isLoading = false,
                        isRoot = hasRoot,
                        isShizuku = hasShizuku,
                        allUserApps = user,
                        allSystemApps = system
                    )
                }
            }
        }
    }

    // --- Actions (Write to DataStore) ---

    fun selectApp(packageName: String) {
        viewModelScope.launch {
            _rawState.update { it.copy(isLoadingDetails = true, selectedAppDetails = null) }
            getAppDetailsUseCase(packageName).onSuccess { fullDetails ->
                _rawState.update { it.copy(isLoadingDetails = false, selectedAppDetails = fullDetails) }
            }.onFailure {
                _rawState.update { it.copy(isLoadingDetails = false) }
            }
        }
    }

    fun clearSelection() {
        _rawState.update { it.copy(selectedAppDetails = null) }
    }

    fun updateListType(type: AppListType) {
        // AppListType is usually session-only, but we reset filter to "All" when switching
        _rawState.update { it.copy(appListType = type) }
        viewModelScope.launch {
            preferenceRepository.updateAppFilter(FilterType.Source, "All")
        }
    }

    fun updateFilter(filter: String) {
        viewModelScope.launch {
            // We need to know current filter type to update properly
            val currentType = uiState.value.filterType
            preferenceRepository.updateAppFilter(currentType, filter)
        }
    }

    fun updateFilterType(type: FilterType) {
        viewModelScope.launch {
            preferenceRepository.updateAppFilter(type, "All")
        }
    }

    fun updateSort(sortBy: SortBy) {
        viewModelScope.launch {
            preferenceRepository.updateAppSort(sortBy)
        }
    }

    fun updateSortOrder(order: SortOrder) {
        viewModelScope.launch {
            preferenceRepository.updateAppSortOrder(order)
        }
    }

    // ... processList and getSortedList remain the same ...
    private fun processList(state: AppListUiState): AppListUiState {
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

        // 4. Calculate Installers (Metadata)
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