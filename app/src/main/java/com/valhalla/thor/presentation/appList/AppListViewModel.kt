package com.valhalla.thor.presentation.appList

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.data.manager.StorageStatsHelper
import com.valhalla.thor.data.manager.UsageAccessManager
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.sortApps
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.GetAppDetailsUseCase
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.presentation.freezer.FreezerPrompt
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import org.koin.core.annotation.KoinViewModel
import kotlin.time.Duration.Companion.milliseconds

// ... AppListUiState remains same ...
data class AppListUiState(
    val isLoading: Boolean = true,
    // Privileges
    val isRoot: Boolean = false,
    val isShizuku: Boolean = false,
    val isDhizuku: Boolean = false,
    // Raw Data
    val allUserApps: List<AppInfo> = emptyList(),
    val allSystemApps: List<AppInfo> = emptyList(),
    // Filter State
    val appListType: AppListType = AppListType.USER,
    val filterType: FilterType = FilterType.Source,
    val selectedFilter: String = "All",
    val searchQuery: String = "",
    val sortBy: SortBy = SortBy.NAME,
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    // Display Data
    val displayedApps: List<AppInfo> = emptyList(),
    val availableInstallers: List<String> = listOf("All"),
    val installerNameMap: Map<String, String> = emptyMap(),
    // Detail View State
    val selectedAppDetails: AppInfo? = null,
    val isLoadingDetails: Boolean = false,
    // Action Feedback
    val actionMessage: UiText? = null,
    val freezerPrompt: FreezerPrompt? = null,
    val useDetailedView: Boolean = true,
    val isGrid: Boolean = true,
    val isComputingSizes: Boolean = false,
    val needsUsageAccessPrompt: Boolean = false
)

@KoinViewModel
class AppListViewModel(
    private val context: Context,
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val getAppDetailsUseCase: GetAppDetailsUseCase,
    private val privilegeManager: PrivilegeManager,
    private val manageAppUseCase: ManageAppUseCase,
    private val preferenceRepository: PreferenceRepository,
    private val freezerRepository: FreezerRepository,
    private val appRepository: AppRepository,
    private val storageStatsHelper: StorageStatsHelper,
    private val usageAccessManager: UsageAccessManager
) : ViewModel() {

    private var appsJob: Job? = null
    private var sizeJob: Job? = null
    private val _rawState = MutableStateFlow(AppListUiState())

    // Combine raw app data with user preferences from DataStore
    // OPTIMIZATION: flowOn(Dispatchers.Default) ensures sorting/filtering happens on background thread
    val uiState = combine(_rawState, preferenceRepository.userPreferences) { state, prefs ->
        val mergedState = state.copy(
            sortBy = prefs.appSortBy,
            sortOrder = prefs.appSortOrder,
            filterType = prefs.appFilterType,
            selectedFilter = prefs.appSelectedFilter,
            useDetailedView = prefs.useDetailedView,
            isGrid = prefs.appListIsGrid
        )
        processList(mergedState)
    }
        .flowOn(Dispatchers.Default) // Move computation off Main Thread
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppListUiState()
        )

    init {
        loadApps()
        observeSizeSort()
    }

    fun loadApps() {
        // Cancel any existing collector so the prior (infinite) getInstalledAppsUseCase()
        // callbackFlow tears down (awaitClose -> unregister receivers) before we relaunch.
        appsJob?.cancel()

        appsJob = viewModelScope.launch {
            _rawState.update { it.copy(isLoading = true) }

            // Allow navigation/bottom bar animations to finish fluidly
            delay(800.milliseconds)

            // Privilege availability now comes from the shared reactive PrivilegeManager,
            // so a Shizuku grant reflects here without reloading the list.
            combine(
                getInstalledAppsUseCase(),
                privilegeManager.state
            ) { (user, system), priv ->
                Triple(user, system, priv)
            }.collect { (user, system, priv) ->
                _rawState.update {
                    it.copy(
                        // Hold the loader until the first privilege probe lands
                        // (isReady) so privilege-gated controls never flash their
                        // disabled state on cold start. This restores the old
                        // await-probe-before-reveal behavior; later Shizuku grants
                        // still update reactively once isReady is true.
                        isLoading = !priv.isReady,
                        isRoot = priv.root,
                        isShizuku = priv.shizuku,
                        isDhizuku = priv.dhizuku,
                        allUserApps = user,
                        allSystemApps = system
                    )
                }
                if (priv.hasAnyPrivilege) {
                    launch { usageAccessManager.maybeAutoGrant() }
                }
            }
        }
    }

    // Recompute total install sizes when Size is the active sort AND apps are loaded
    // (fires both when the user picks Size and when the list finishes loading with
    // Size already selected). distinctUntilChanged prevents a self-trigger loop.
    private fun observeSizeSort() {
        viewModelScope.launch {
            combine(
                preferenceRepository.userPreferences.map { it.appSortBy }.distinctUntilChanged(),
                _rawState.map { it.allUserApps.size + it.allSystemApps.size }.distinctUntilChanged()
            ) { sortBy, appCount -> sortBy to appCount }
                .collect { (sortBy, appCount) ->
                    if (sortBy == SortBy.SIZE && appCount > 0) ensureInstallSizes()
                }
        }
    }

    private fun ensureInstallSizes() {
        sizeJob?.cancel()
        sizeJob = viewModelScope.launch {
            if (!usageAccessManager.isGranted() && !usageAccessManager.tryGrantViaPrivilege()) {
                _rawState.update { it.copy(needsUsageAccessPrompt = true) }
                return@launch
            }
            _rawState.update { it.copy(isComputingSizes = true) }
            val packages = (_rawState.value.allUserApps + _rawState.value.allSystemApps)
                .map { it.packageName }
            val sizes = storageStatsHelper.installSizes(packages)
            _rawState.update { state ->
                state.copy(
                    isComputingSizes = false,
                    needsUsageAccessPrompt = false, // clear any stale prompt once sizes resolve
                    allUserApps = state.allUserApps.map {
                        it.copy(installSize = sizes[it.packageName] ?: it.installSize)
                    },
                    allSystemApps = state.allSystemApps.map {
                        it.copy(installSize = sizes[it.packageName] ?: it.installSize)
                    }
                )
            }
            appRepository.updateInstallSizes(sizes)
        }
    }

    fun dismissUsageAccessPrompt() {
        _rawState.update { it.copy(needsUsageAccessPrompt = false) }
    }

    fun freezeApp(packageName: String, appName: String?, freeze: Boolean) {
        viewModelScope.launch {
            val result = manageAppUseCase.setAppDisabled(packageName, freeze)
            result.onSuccess {
                // Update the app's enabled state in our local list immediately for UI responsiveness
                _rawState.update { state ->
                    state.copy(
                        allUserApps = state.allUserApps.map {
                            if (it.packageName == packageName) it.copy(enabled = !freeze) else it
                        },
                        allSystemApps = state.allSystemApps.map {
                            if (it.packageName == packageName) it.copy(enabled = !freeze) else it
                        }
                    )
                }

                if (freeze) {
                    val inFreezer = withContext(Dispatchers.IO) {
                        freezerRepository.contains(packageName)
                    }
                    if (!inFreezer) {
                        _rawState.update {
                            it.copy(
                                freezerPrompt = FreezerPrompt(
                                    packageName,
                                    appName
                                )
                            )
                        }
                    } else {
                        _rawState.update {
                            it.copy(
                                actionMessage = UiText.StringResource(
                                    R.string.frozen_success,
                                    appName ?: packageName
                                )
                            )
                        }
                    }
                } else {
                    _rawState.update {
                        it.copy(
                            actionMessage = UiText.StringResource(
                                R.string.unfrozen_success,
                                appName ?: packageName
                            )
                        )
                    }
                }
            }.onFailure { e ->
                _rawState.update {
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

    fun dismissFreezerPrompt() {
        _rawState.update { it.copy(freezerPrompt = null) }
    }

    fun addToFreezer(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            freezerRepository.add(packageName)
            _rawState.update {
                it.copy(
                    freezerPrompt = null,
                    actionMessage = UiText.StringResource(R.string.added_to_freezer_success)
                )
            }
        }
    }

    fun dismissMessage() {
        _rawState.update { it.copy(actionMessage = null) }
    }

    fun performMultiAction(action: MultiAppAction) {
        viewModelScope.launch(Dispatchers.IO) {
            when (action) {
                is MultiAppAction.Freeze -> {
                    val eligibleApps = action.appList.filter { appInfo ->
                        val isSystem = appInfo.isSystem
                        val isUadFailed = isSystem && appInfo.isUadLoadFailed
                        val isUnsafe = isSystem && appInfo.bloatRecommendation?.lowercase() == "unsafe"
                        !(isUadFailed || isUnsafe)
                    }
                    val skippedCount = action.appList.size - eligibleApps.size
                    val succeededPackages = mutableSetOf<String>()
                    var failures = skippedCount

                    eligibleApps.forEach { app ->
                        val res = manageAppUseCase.setAppDisabled(app.packageName, true)
                        if (res.isSuccess) {
                            succeededPackages.add(app.packageName)
                        } else {
                            failures++
                        }
                    }

                    _rawState.update { state ->
                        state.copy(
                            allUserApps = state.allUserApps.map {
                                if (it.packageName in succeededPackages) it.copy(enabled = false) else it
                            },
                            allSystemApps = state.allSystemApps.map {
                                if (it.packageName in succeededPackages) it.copy(enabled = false) else it
                            },
                            actionMessage = if (failures == 0) {
                                UiText.StringResource(
                                    R.string.tile_freeze_success,
                                    action.appList.size
                                )
                            } else {
                                UiText.StringResource(
                                    R.string.tile_freeze_partial_failure,
                                    succeededPackages.size,
                                    action.appList.size,
                                    failures
                                )
                            }
                        )
                    }
                }

                is MultiAppAction.UnFreeze -> {
                    val packageNames = action.appList.map { it.packageName }.toSet()
                    action.appList.forEach {
                        manageAppUseCase.setAppDisabled(it.packageName, false)
                    }
                    _rawState.update { state ->
                        state.copy(
                            allUserApps = state.allUserApps.map {
                                if (it.packageName in packageNames) it.copy(enabled = true) else it
                            },
                            allSystemApps = state.allSystemApps.map {
                                if (it.packageName in packageNames) it.copy(enabled = true) else it
                            },
                            actionMessage = UiText.StringResource(
                                R.string.unfrozen_count_success,
                                action.appList.size
                            )
                        )
                    }
                }

                else -> {
                    // Fallback or forward? If we forward, we need a callback. 
                    // For now let's just stay consistent with single app actions.
                }
            }
        }
    }

    // --- Actions (Write to DataStore) ---

    fun selectApp(packageName: String) {
        viewModelScope.launch {
            _rawState.update { it.copy(isLoadingDetails = true, selectedAppDetails = null) }
            getAppDetailsUseCase(packageName).onSuccess { fullDetails ->
                _rawState.update {
                    it.copy(
                        isLoadingDetails = false,
                        selectedAppDetails = fullDetails
                    )
                }
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

    fun updateSearchQuery(query: String) {
        _rawState.update { it.copy(searchQuery = query) }
    }

    fun toggleGridMode() {
        viewModelScope.launch {
            preferenceRepository.toggleAppListIsGrid()
        }
    }

    private fun processList(state: AppListUiState): AppListUiState {
        // 1. Pick Source
        val rawList =
            if (state.appListType == AppListType.USER) state.allUserApps else state.allSystemApps

        // 2. Filter by Search Query (Early out for performance)
        val searched = if (state.searchQuery.isBlank()) {
            rawList
        } else {
            rawList.filter {
                it.appName?.contains(state.searchQuery, ignoreCase = true) == true ||
                        it.packageName.contains(state.searchQuery, ignoreCase = true)
            }
        }

        // 3. Filter by Source/State
        val filtered = when (state.filterType) {
            FilterType.Source -> {
                if (state.selectedFilter == "All") searched
                else searched.filter { it.installerPackageName == state.selectedFilter }
            }

            FilterType.State -> {
                when (state.selectedFilter) {
                    "Active" -> searched.filter { it.enabled }
                    "Frozen" -> searched.filter { !it.enabled }
                    "Suspended" -> searched.filter { it.isSuspended }
                    else -> searched
                }
            }
        }

        // 4. Sort
        val sorted = getSortedList(filtered, state.sortBy, state.sortOrder)

        // 5. Calculate Installers (Metadata) - OPTIMIZED
        // Only recalculate map if the full list changed (avoid doing this on search)
        val installers =
            rawList.mapNotNull { it.installerPackageName }.distinct().sorted().toMutableList()

        // Fast lookup map for app names to avoid O(N^2) associative logic
        val nameMap = rawList.associateBy({ it.packageName }, { it.appName })
        val installerNames = installers.associateWith { pkg ->
            when (pkg) {
                "com.android.vending" -> context.getString(R.string.installer_play_store)
                "org.fdroid.fdroid" -> context.getString(R.string.installer_fdroid)
                // Sideloaded via the system package-installer UI: Google ships
                // com.google.android.packageinstaller, AOSP uses com.android.packageinstaller.
                "com.google.android.packageinstaller",
                "com.android.packageinstaller" -> context.getString(R.string.installer_sideloaded)
                else -> nameMap[pkg] ?: pkg
            }
        }

        installers.add(0, "All")

        return state.copy(
            displayedApps = sorted,
            availableInstallers = installers,
            installerNameMap = installerNames
        )
    }

    private fun getSortedList(
        list: List<AppInfo>,
        sortBy: SortBy,
        order: SortOrder
    ): List<AppInfo> = sortApps(list, sortBy, order)
}