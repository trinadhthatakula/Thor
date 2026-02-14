package com.valhalla.thor.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.repository.PreferenceRepository // Injected
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
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
    val unknownInstallerCount: Int = 0,
    val distributionData: Map<String, Int> = emptyMap(),
    // Status
    val isRootAvailable: Boolean = false,
    val isShizukuAvailable: Boolean = false,

    // Preferences
    val showReinstallCard: Boolean = true // <--- Controlled by DataStore
)

class HomeViewModel(
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val systemRepository: SystemRepository,
    private val preferenceRepository: PreferenceRepository // Injected
) : ViewModel() {

    private var dashboardJob: Job? = null
    private val _internalState = MutableStateFlow(HomeUiState())

    // Combine internal data processing with user preferences
    val state = combine(_internalState, preferenceRepository.userPreferences) { internal, prefs ->
        internal.copy(showReinstallCard = prefs.showReinstallAllCard)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HomeUiState()
    )

    init {
        loadDashboardData()
    }

    fun loadDashboardData() {
        // Cancel any existing job to ensure we restart with fresh system status
        dashboardJob?.cancel()

        dashboardJob = viewModelScope.launch(Dispatchers.IO) {
            _internalState.update { it.copy(isLoading = true) }
            val hasRoot = systemRepository.isRootAvailable()
            val hasShizuku = systemRepository.isShizukuAvailable()

            getInstalledAppsUseCase().collect { (userApps, systemApps) ->
                processData(userApps, systemApps, hasRoot, hasShizuku)
            }
        }
    }

    fun dismissReinstallCard() {
        viewModelScope.launch {
            preferenceRepository.setReinstallAllCardVisibility(false)
        }
    }

    private fun processData(
        userApps: List<AppInfo>,
        systemApps: List<AppInfo>,
        hasRoot: Boolean,
        hasShizuku: Boolean
    ) {
        val allApps = userApps + systemApps
        val activeCount = allApps.count { it.enabled }
        val frozenCount = allApps.count { !it.enabled }

        val unknownCount = userApps.count {
            it.installerPackageName != "com.android.vending" &&
                    it.installerPackageName != "com.google.android.packageinstaller"
        }

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

        _internalState.update {
            it.copy(
                isLoading = false,
                userAppCount = userApps.size,
                systemAppCount = systemApps.size,
                activeAppCount = activeCount,
                frozenAppCount = frozenCount,
                unknownInstallerCount = unknownCount,
                distributionData = distribution,
                isRootAvailable = hasRoot,
                isShizukuAvailable = hasShizuku
            )
        }
    }
}