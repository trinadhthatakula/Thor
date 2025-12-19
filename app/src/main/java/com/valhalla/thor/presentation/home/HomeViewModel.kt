package com.valhalla.thor.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val isLoading: Boolean = true,
    // Filter
    val selectedAppType: AppListType = AppListType.USER,

    // Derived Stats (depend on selectedAppType)
    val displayedAppCount: Int = 0,
    val activeAppCount: Int = 0,
    val frozenAppCount: Int = 0,

    // Specific Warnings (Always User Apps usually, or dependent on context)
    val unknownInstallerCount: Int = 0,

    val distributionData: Map<String, Int> = emptyMap(),

    // Status
    val isRootAvailable: Boolean = false,
    val isShizukuAvailable: Boolean = false
)

class HomeViewModel(
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val systemRepository: SystemRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state = _state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HomeUiState()
    )

    // Cache raw data to allow instant filtering
    private var cachedUserApps: List<AppInfo> = emptyList()
    private var cachedSystemApps: List<AppInfo> = emptyList()

    init {
        loadDashboardData()
    }

    fun loadDashboardData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val hasRoot = systemRepository.isRootAvailable
            val hasShizuku = systemRepository.isShizukuAvailable()

            _state.update { it.copy(isRootAvailable = hasRoot, isShizukuAvailable = hasShizuku) }

            getInstalledAppsUseCase().collect { (userApps, systemApps) ->
                cachedUserApps = userApps
                cachedSystemApps = systemApps

                // Process with currently selected type
                recalculateStats(_state.value.selectedAppType)
            }
        }
    }

    fun updateAppListType(type: AppListType) {
        _state.update { it.copy(selectedAppType = type) }
        recalculateStats(type)
    }

    private fun recalculateStats(type: AppListType) {
        val targetList = if (type == AppListType.USER) cachedUserApps else cachedSystemApps

        // Calculate on Default dispatcher to avoid UI jank if list is huge
        viewModelScope.launch(Dispatchers.Default) {
            val activeCount = targetList.count { it.enabled }
            val frozenCount = targetList.count { !it.enabled }

            // Unknown Installer check is typically most relevant for User apps,
            // but we can show it for System apps if they were updated via unknown sources.
            val unknownCount = targetList.count {
                it.installerPackageName != "com.android.vending" &&
                        it.installerPackageName != "com.google.android.packageinstaller"
            }

            // Chart Data
            val distribution = targetList
                .groupBy { it.installerPackageName ?: "Unknown" }
                .mapValues { it.value.size }
                .mapKeys { (key, _) ->
                    when(key) {
                        "com.android.vending" -> "Play Store"
                        "com.google.android.packageinstaller" -> "Package Installer"
                        "null" -> "Unknown"
                        else -> key.substringAfterLast(".")
                    }
                }

            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        displayedAppCount = targetList.size,
                        activeAppCount = activeCount,
                        frozenAppCount = frozenCount,
                        unknownInstallerCount = unknownCount,
                        distributionData = distribution
                    )
                }
            }
        }
    }
}