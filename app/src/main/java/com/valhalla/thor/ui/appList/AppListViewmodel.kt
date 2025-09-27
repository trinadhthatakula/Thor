package com.valhalla.thor.ui.appList

import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.AppInfoGrabber
import com.valhalla.thor.model.AppListType
import com.valhalla.thor.model.FilterType
import com.valhalla.thor.model.SortBy
import com.valhalla.thor.model.SortOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class AppListState(
    val isLoading: Boolean = false,
    val userApps: List<AppInfo> = emptyList(),
    val systemApps: List<AppInfo> = emptyList(),
    val installers: List<String?> = emptyList(),
    val error: String? = null,
    val appListType: AppListType = AppListType.USER,
    val selectedFilterType: FilterType = FilterType.Source,
    val selectedFilter: String? = null,
    val selectedSortBy: SortBy = SortBy.NAME,
    val selectedSortOrder: SortOrder = SortOrder.ASCENDING,
    val filteredList: List<AppInfo> = emptyList(),
    val selectedAppInfo: AppInfo? = null,
    val selectedInstaller: String = "All",
    val reinstallAppInfo: AppInfo? = null,
)

class AppListViewmodel(
    grabber: AppInfoGrabber
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppListState())
    val uiState = combine(
        _uiState,
        grabber.userApps,
        grabber.systemApps
    ) { uiState, userApps, systemApps ->
        uiState.copy(
            userApps = userApps,
            systemApps = systemApps,
            installers = when (uiState.appListType) {
                AppListType.USER -> {
                    userApps.map { it.installerPackageName }.distinct().toMutableList()
                        .apply { add(0, "All") }.toList()
                }
                AppListType.SYSTEM -> {
                    systemApps.map { it.installerPackageName }.distinct().toMutableList()
                        .apply { add(0, "All") }.toList()
                }
            }
        )
        uiState.copy(
            filteredList = updateFilters(uiState),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        _uiState.value
    )

    fun changeAppListType(type: AppListType) {
        _uiState.update {
            it.copy(
                appListType = type,
                installers = ((if (type == AppListType.USER) uiState.value.userApps else uiState.value.systemApps).map { aList ->
                    aList.installerPackageName
                }.distinct().toMutableList()).apply { add(0, "All") }.toList(),
                selectedInstaller = "All"
            )
        }
        _uiState.update {
            it.copy(
                filteredList = updateFilters(it),
            )
        }
    }

    fun changeFilter(filter: String?) {
        _uiState.update {
            it.copy(
                selectedFilter = filter,
                filteredList = updateFilters(it)
            )
        }
    }

    fun changeSortBy(sortBy: SortBy) {
        _uiState.update { state ->
            state.copy(
                selectedSortBy = sortBy,
                filteredList = state.filteredList.getSorted(sortBy, state.selectedSortOrder)
            )
        }
    }

    fun changeSortOrder(sortOrder: SortOrder) {
        _uiState.update { state ->
            state.copy(
                selectedSortOrder = sortOrder,
                filteredList = state.filteredList.getSorted(state.selectedSortBy, sortOrder)
            )
        }
    }

    fun changeFilterType(type: FilterType) {
        _uiState.update {
            it.copy(
                selectedFilterType = type,
                selectedFilter = null,
                filteredList = updateFilters(it)
            )
        }
    }

    fun updateFilters(state: AppListState): List<AppInfo> {
        return when (state.selectedFilterType) {
            FilterType.Source -> {
                when (state.appListType) {
                    AppListType.USER -> {
                        if (state.selectedFilter == "All") {
                            state.userApps
                        } else {
                            state.userApps.filter { it.installerPackageName == state.selectedFilter }
                        }
                    }

                    AppListType.SYSTEM -> {
                        if (state.selectedFilter == "All") {
                            state.systemApps
                        } else {
                            state.systemApps.filter { it.installerPackageName == state.selectedFilter }
                        }
                    }
                }
                emptyList()
            }

            FilterType.State -> {
                when (state.selectedFilter) {

                    "All" -> {
                        when (state.appListType) {
                            AppListType.USER -> state.userApps
                            else -> state.systemApps
                        }
                    }

                    "Active" -> {
                        when (state.appListType) {
                            AppListType.USER -> state.userApps
                            else -> state.systemApps
                        }.filter { it.enabled }
                    }

                    else -> {
                        when (state.appListType) {
                            AppListType.USER -> state.userApps
                            else -> state.systemApps
                        }.filter { !it.enabled }
                    }
                }
            }
        }.getSorted(state.selectedSortBy, state.selectedSortOrder).toMutableStateList()
    }

    fun selectAppInto(appInfo: AppInfo?) {
        _uiState.update {
            it.copy(selectedAppInfo = appInfo)
        }
    }

    fun reinstallApp(appInfo: AppInfo?) {
        _uiState.update {
            it.copy(reinstallAppInfo = appInfo)
        }
    }

}