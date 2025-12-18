package com.valhalla.thor.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.model.AppInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    // Stats
    val userAppCount: Int = 0,
    val systemAppCount: Int = 0,
    val activeAppCount: Int = 0,
    val frozenAppCount: Int = 0,
    val unknownInstallerCount: Int = 0, // <--- NEW: Triggers the "Reinstall All" card
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

    init {
        loadDashboardData()
    }

    fun loadDashboardData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val hasRoot = systemRepository.isRootAvailable()
            val hasShizuku = systemRepository.isShizukuAvailable()

            getInstalledAppsUseCase().collect { (userApps, systemApps) ->
                processData(userApps, systemApps, hasRoot, hasShizuku)
            }
        }
    }

    private fun processData(
        userApps: List<AppInfo>,
        systemApps: List<AppInfo>,
        hasRoot: Boolean,
        hasShizuku: Boolean
    ) {
        val allApps = userApps + systemApps

        // Stats
        val activeCount = allApps.count { it.enabled }
        val frozenCount = allApps.count { !it.enabled }

        // Check for apps not from Play Store (for Reinstall suggestion)
        // Only relevant for User Apps
        val unknownCount = userApps.count {
            it.installerPackageName != "com.android.vending" &&
                    it.installerPackageName != "com.google.android.packageinstaller"
        }

        // Chart Data
        val distribution = allApps
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

        _state.update {
            it.copy(
                isLoading = false,
                userAppCount = userApps.size,
                systemAppCount = systemApps.size,
                activeAppCount = activeCount,
                frozenAppCount = frozenCount,
                unknownInstallerCount = unknownCount, // <--- Set Value
                distributionData = distribution,
                isRootAvailable = hasRoot,
                isShizukuAvailable = hasShizuku
            )
        }
    }
}