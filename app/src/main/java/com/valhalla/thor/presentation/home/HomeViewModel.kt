package com.valhalla.thor.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

data class HomeUiState(
    val isLoading: Boolean = true,
    val selectedType: AppListType = AppListType.USER,
    // Stats
    val activeAppCount: Int = 0,
    val frozenAppCount: Int = 0,
    val suspendedAppCount: Int = 0,
    val unknownInstallerCount: Int = 0,
    val distributionData: Map<String, Int> = emptyMap(),
    // Status
    val isRootAvailable: Boolean = false,
    val isShizukuAvailable: Boolean = false,
    val isDhizukuAvailable: Boolean = false,
    val activePrivilegeMode: PrivilegeMode? = null,

    // Preferences
    val showReinstallCard: Boolean = true, // <--- Controlled by DataStore
    val extensionsUnlocked: Boolean = false
)

@KoinViewModel
class HomeViewModel(
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val systemRepository: SystemRepository,
    private val preferenceRepository: PreferenceRepository // Injected
) : ViewModel() {

    private var dashboardJob: Job? = null
    private var typeChangeJob: Job? = null
    private val _internalState = MutableStateFlow(HomeUiState())

    private var lastUserApps: List<AppInfo> = emptyList()
    private var lastSystemApps: List<AppInfo> = emptyList()

    // Combine internal data processing with user preferences
    val state = combine(_internalState, preferenceRepository.userPreferences) { internal, prefs ->
        val activeMode = prefs.preferredPrivilegeMode ?: when {
            internal.isRootAvailable -> PrivilegeMode.ROOT
            internal.isShizukuAvailable -> PrivilegeMode.SHIZUKU
            internal.isDhizukuAvailable -> PrivilegeMode.DHIZUKU
            else -> null
        }
        internal.copy(
            showReinstallCard = prefs.showReinstallAllCard,
            activePrivilegeMode = activeMode,
            extensionsUnlocked = prefs.extensionsUnlocked
        )
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
            val hasDhizuku = systemRepository.isDhizukuAvailable()

            getInstalledAppsUseCase().collect { (userApps, systemApps) ->
                lastUserApps = userApps
                lastSystemApps = systemApps
                processData(
                    userApps,
                    systemApps,
                    _internalState.value.selectedType,
                    hasRoot,
                    hasShizuku,
                    hasDhizuku
                )
            }
        }
    }

    fun onTypeChanged(type: AppListType) {
        _internalState.update { it.copy(selectedType = type) }
        typeChangeJob?.cancel()
        typeChangeJob = viewModelScope.launch(Dispatchers.IO) {
            val s = _internalState.value
            processData(
                lastUserApps,
                lastSystemApps,
                type,
                s.isRootAvailable,
                s.isShizukuAvailable,
                s.isDhizukuAvailable
            )
        }
    }

    fun onPrivilegeModeChanged(mode: PrivilegeMode) {
        viewModelScope.launch {
            preferenceRepository.setPrivilegeMode(mode)
            loadDashboardData() // Refresh everything
        }
    }

    fun dismissReinstallCard() {
        viewModelScope.launch {
            preferenceRepository.setReinstallAllCardVisibility(false)
        }
    }

    /** Easter egg: unlock the (still-unstable) Extensions feature in Settings. */
    fun unlockExtensions() {
        viewModelScope.launch {
            preferenceRepository.setExtensionsUnlocked(true)
        }
    }

    private fun processData(
        userApps: List<AppInfo>,
        systemApps: List<AppInfo>,
        selectedType: AppListType,
        hasRoot: Boolean,
        hasShizuku: Boolean,
        hasDhizuku: Boolean
    ) {
        val filteredApps = if (selectedType == AppListType.USER) userApps else systemApps

        val activeCount = filteredApps.count { it.enabled && !it.isSuspended }
        val frozenCount = filteredApps.count { !it.enabled }
        val suspendedCount = filteredApps.count { it.isSuspended && it.enabled }

        val unknownCount = if (selectedType == AppListType.USER) {
            userApps.count {
                it.installerPackageName != "com.android.vending" &&
                        it.installerPackageName != "com.google.android.packageinstaller"
            }
        } else 0

        val labelCounts = filteredApps
            .groupBy {
                when (val pkg = it.installerPackageName) {
                    "com.android.vending" -> "PLAY STORE"
                    "org.fdroid.fdroid" -> "F-DROID"
                    "com.google.android.packageinstaller" -> "SIDELOADED"
                    null, "Unknown" -> "OTHERS"
                    else -> pkg.substringAfterLast(".").uppercase()
                }
            }
            .mapValues { it.value.size }

        // --- TOP 3 / 4 GROUPING LOGIC ---
        val sortedLabels = labelCounts.entries.sortedByDescending { it.value }

        val distribution = if (sortedLabels.size <= 4) {
            // If 4 or fewer categories, show them exactly as they are
            labelCounts
        } else {
            // If more than 4, take top 3 and bunch the rest into "Others"
            val top3Entries = sortedLabels.take(3)
            val restEntries = sortedLabels.drop(3)

            val result = mutableMapOf<String, Int>()
            top3Entries.forEach { result[it.key] = it.value }

            val othersCount = restEntries.sumOf { it.value }
            // Add 'othersCount' to 'OTHERS' label (merge if 'OTHERS' was already in top 3)
            result["OTHERS"] = result.getOrDefault("OTHERS", 0) + othersCount
            result
        }

        _internalState.update {
            it.copy(
                isLoading = false,
                selectedType = selectedType,
                activeAppCount = activeCount,
                frozenAppCount = frozenCount,
                suspendedAppCount = suspendedCount,
                unknownInstallerCount = unknownCount,
                distributionData = distribution,
                isRootAvailable = hasRoot,
                isShizukuAvailable = hasShizuku,
                isDhizukuAvailable = hasDhizuku
            )
        }
    }
}
