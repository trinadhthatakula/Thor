package com.valhalla.thor.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

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
    // False until the first privilege probe completes — lets the status icon show a
    // neutral "detecting" state instead of flashing the red "no privilege" icon on cold start.
    val isPrivilegeReady: Boolean = false,

    // Preferences
    val showReinstallCard: Boolean = true, // <--- Controlled by DataStore
    val extensionsUnlocked: Boolean = false
)

@KoinViewModel
class HomeViewModel(
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val privilegeManager: PrivilegeManager,
    private val preferenceRepository: PreferenceRepository, // Injected
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private var dashboardJob: Job? = null
    private var typeChangeJob: Job? = null
    private val _internalState = MutableStateFlow(HomeUiState())

    private var lastUserApps: List<AppInfo> = emptyList()
    private var lastSystemApps: List<AppInfo> = emptyList()

    // Combine internal data processing with user preferences
    val state = combine(
        _internalState,
        preferenceRepository.userPreferences,
        privilegeManager.state
    ) { internal, prefs, priv ->
        internal.copy(
            showReinstallCard = prefs.showReinstallAllCard,
            isRootAvailable = priv.root,
            isShizukuAvailable = priv.shizuku,
            isDhizukuAvailable = priv.dhizuku,
            isPrivilegeReady = priv.isReady,
            // Keep the existing "null = no privilege" contract for the UI. Until the
            // first probe completes (isReady == false), optimistically fall back to the
            // persisted preference so a configured user never sees a "no privilege"
            // flash on cold start (this restores the old one-shot behavior, which read
            // the preference straight from DataStore before any hardware probe).
            activePrivilegeMode = if (priv.isReady) {
                priv.active.takeIf { it != PrivilegeMode.NONE }
            } else {
                prefs.preferredPrivilegeMode
            },
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

        dashboardJob = viewModelScope.launch(ioDispatcher) {
            _internalState.update { it.copy(isLoading = true) }
            getInstalledAppsUseCase().catch { e ->
                // getInstalledAppsUseCase() is a callbackFlow that registers package receivers
                // and reads PackageManager; it can throw (e.g. DeadObjectException). Guard the
                // collection so an upstream throw can't propagate out of the collector and crash
                // the app, and clear the loader so the dashboard doesn't spin forever.
                if (e is CancellationException) throw e // preserve structured-concurrency cancellation
                Logger.e("HomeViewModel", "loadDashboardData failed", e)
                _internalState.update { it.copy(isLoading = false) }
            }.collect { (userApps, systemApps) ->
                lastUserApps = userApps
                lastSystemApps = systemApps
                processData(userApps, systemApps, _internalState.value.selectedType)
            }
        }
    }

    fun onTypeChanged(type: AppListType) {
        _internalState.update { it.copy(selectedType = type) }
        typeChangeJob?.cancel()
        typeChangeJob = viewModelScope.launch(ioDispatcher) {
            processData(lastUserApps, lastSystemApps, type)
        }
    }

    fun onPrivilegeModeChanged(mode: PrivilegeMode) {
        // PrivilegeManager observes the preference and recomputes `active` reactively;
        // no dashboard reload needed (app stats don't depend on the privilege mode).
        viewModelScope.launch {
            preferenceRepository.setPrivilegeMode(mode)
        }
    }

    fun dismissReinstallCard() {
        viewModelScope.launch {
            preferenceRepository.setReinstallAllCardVisibility(false)
        }
    }

    /**
     * The tap easter egg reached its cap: mark it "cracked" (persisted via the extensionsUnlocked
     * flag) so it stays cracked until a new egg ships in a future update. A cracked egg no longer
     * counts — it just shakes the logo and opens the Support Developer sheet.
     */
    fun crackEasterEgg() {
        viewModelScope.launch {
            preferenceRepository.setExtensionsUnlocked(true)
        }
    }

    private fun processData(
        userApps: List<AppInfo>,
        systemApps: List<AppInfo>,
        selectedType: AppListType
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
                distributionData = distribution
                // Privilege fields are populated by the state combine from PrivilegeManager.
            )
        }
    }
}
