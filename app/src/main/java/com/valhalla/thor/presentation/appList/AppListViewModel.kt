// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.data.launcher.FreezerShortcutManager
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.data.manager.StorageStatsHelper
import com.valhalla.thor.data.manager.UsageAccessManager
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.model.freezeTier
import com.valhalla.thor.domain.model.sortApps
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.GetAppDetailsUseCase
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.presentation.freezer.FreezerPrompt
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

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
    // Freezer membership (the watchlist), not freeze state: an app can be frozen without being in
    // the freezer and vice versa. Drives the sheet's "Add to / Remove from Freezer" action.
    val freezerPackageNames: Set<String> = emptySet(),
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
    // Installer identifiers -> display label. Resolved to a String in the screen so the
    // ViewModel stays free of a Context dependency.
    val installerNameMap: Map<String, UiText> = emptyMap(),
    // Detail View State
    val selectedAppDetails: AppInfo? = null,
    val isLoadingDetails: Boolean = false,
    val isGrid: Boolean = true,
    val isComputingSizes: Boolean = false,
    // Holds the pull-to-refresh indicator up for a readable minimum. isLoading cannot do this job:
    // getAllApps() emits the Room cache before it starts the package rescan, so isLoading clears
    // after one DAO read and the indicator would blink out while the real scan is still running.
    val isManualRefreshing: Boolean = false,
    val needsUsageAccessPrompt: Boolean = false
)

/**
 * One-off UI events surfaced to [AppListScreen] via a buffered `Channel`, kept out of [AppListUiState]
 * so transient feedback is delivered exactly once and never replayed on recomposition/config change.
 */
sealed interface AppListEvent {
    data class ShowMessage(val message: UiText) : AppListEvent
    data class ShowFreezerPrompt(val prompt: FreezerPrompt) : AppListEvent
}

@KoinViewModel
class AppListViewModel(
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val getAppDetailsUseCase: GetAppDetailsUseCase,
    private val privilegeManager: PrivilegeManager,
    private val manageAppUseCase: ManageAppUseCase,
    private val preferenceRepository: PreferenceRepository,
    private val freezerRepository: FreezerRepository,
    private val freezerShortcutManager: FreezerShortcutManager,
    private val appRepository: AppRepository,
    private val storageStatsHelper: StorageStatsHelper,
    private val usageAccessManager: UsageAccessManager
) : ViewModel() {

    private var appsJob: Job? = null
    private var sizeJob: Job? = null
    private var refreshIndicatorJob: Job? = null
    private val _rawState = MutableStateFlow(AppListUiState())

    // One-off UI feedback (toasts, freezer prompt). A buffered Channel fires each event exactly
    // once (no replay on recomposition/config change) and, unlike a replay = 0 SharedFlow, retains
    // events emitted while no collector is active — e.g. a load-failure event from loadApps() in
    // init() before the screen's collector reaches STARTED, or during a rotation collector gap — so
    // they are delivered when the screen (re)subscribes rather than silently dropped.
    private val _events = Channel<AppListEvent>(Channel.BUFFERED)
    val events: Flow<AppListEvent> = _events.receiveAsFlow()

    // Combine raw app data with user preferences from DataStore
    // OPTIMIZATION: flowOn(Dispatchers.Default) ensures sorting/filtering happens on background thread
    val uiState = combine(_rawState, preferenceRepository.userPreferences) { state, prefs ->
        val mergedState = state.copy(
            sortBy = prefs.appSortBy,
            sortOrder = prefs.appSortOrder,
            filterType = prefs.appFilterType,
            selectedFilter = prefs.appSelectedFilter,
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
        loadApps(deferForTransition = true)
        observeSizeSort()
        observeFreezerMembership()
    }

    /**
     * Mirrors the freezer watchlist into state so the app-info sheet can offer "Add to / Remove from
     * Freezer" without a per-app `contains()` round trip. This is the only surface offering that
     * toggle on phones now that the details screen is a wide-window detail pane, and it is a Room
     * flow, so an add/remove made anywhere else (freezer screen, launcher shortcut) lands here too.
     */
    private fun observeFreezerMembership() {
        viewModelScope.launch {
            freezerRepository.getAll()
                .catch { e ->
                    // Room's Flow throws on a failed query; a membership read failure must not take
                    // the app list down with it — the sheet just falls back to "not in freezer".
                    if (e is CancellationException) throw e // preserve structured-concurrency cancellation
                    Logger.e("AppListViewModel", "freezer membership observation failed", e)
                }
                .collect { packages ->
                    _rawState.update { it.copy(freezerPackageNames = packages.toSet()) }
                }
        }
    }

    /**
     * @param deferForTransition hold the scan back until the screen-entry animation has had time to
     * settle. Only the navigation-entry paths want this; see [settleDelayFor].
     */
    fun loadApps(deferForTransition: Boolean = false) {
        // Cancel any existing collector so the prior (infinite) getInstalledAppsUseCase()
        // callbackFlow tears down (awaitClose -> unregister receivers) before we relaunch.
        appsJob?.cancel()

        if (!deferForTransition) holdRefreshIndicator()

        appsJob = viewModelScope.launch {
            _rawState.update { it.copy(isLoading = true) }

            // Allow navigation/bottom bar animations to finish fluidly.
            // Opt-in, because this runs BEFORE the (cold) flow below is collected, so it is dead
            // time prepended to the scan rather than overlapped with it. A deliberate
            // pull-to-refresh has no transition to protect and must not pay for it.
            if (deferForTransition) {
                // catch: userPreferences is dataStore.data, which throws IOException on a failed
                // read. Fall back to the defaults rather than letting a preference read failure
                // take down the whole app list. (Flow.catch stays transparent to cancellation.)
                val intensity = preferenceRepository.userPreferences
                    .catch { emit(UserPreferences()) }
                    .first()
                    .animationIntensity
                // LOW resolves to ZERO, which delay() returns from without suspending.
                delay(settleDelayFor(intensity))
            }

            // Privilege availability now comes from the shared reactive PrivilegeManager,
            // so a Shizuku grant reflects here without reloading the list.
            combine(
                getInstalledAppsUseCase(),
                privilegeManager.state
            ) { (user, system), priv ->
                Triple(user, system, priv)
            }.catch { e ->
                // getInstalledAppsUseCase() is a callbackFlow that registers package receivers
                // and reads PackageManager; it can throw (e.g. DeadObjectException). Guard the
                // collection so an upstream throw can't propagate out of the collector and crash
                // the app, and clear the loader so the UI doesn't spin forever.
                if (e is CancellationException) throw e // preserve structured-concurrency cancellation
                Logger.e("AppListViewModel", "loadApps failed", e)
                _rawState.update { it.copy(isLoading = false) }
                _events.send(AppListEvent.ShowMessage(UiText.StringResource(R.string.failed_to_load_apps)))
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

    /**
     * Keeps the pull-to-refresh indicator on screen for a readable minimum, without holding the
     * scan back.
     *
     * `isLoading` alone cannot drive the indicator on this path: `getAllApps()` sends the Room
     * cache before it triggers the `pm.getInstalledPackages` rescan, and `priv.isReady` has long
     * since latched true, so the first emission — one DAO read later — clears `isLoading` while
     * the real scan is still running. The indicator would blink out immediately and the list would
     * then mutate under the user with nothing to explain it.
     *
     * The old unconditional 800 ms delay masked this by keeping `isLoading` true, but it did so by
     * postponing the work. This holds only the *indicator*, so the scan still starts at once. It
     * also restores the re-entrancy guard that fell out of that delay: `PullToRefreshBox` ignores
     * pulls while it is refreshing, so a user cannot stack overlapping package scans by pulling
     * repeatedly.
     */
    private fun holdRefreshIndicator() {
        refreshIndicatorJob?.cancel()
        // Raised here rather than inside the coroutine, and lowered only by a timer that ran to
        // completion. A cancelled hold must never lower the flag: the only thing that cancels one
        // is a newer hold, which has already raised it again, so clearing from the old job's
        // teardown would hide the indicator for the refresh that just started.
        _rawState.update { it.copy(isManualRefreshing = true) }
        refreshIndicatorJob = viewModelScope.launch {
            delay(REFRESH_INDICATOR_MIN_VISIBLE)
            _rawState.update { it.copy(isManualRefreshing = false) }
        }
    }

    // Recompute total install sizes when Size is the active sort AND apps are loaded.
    // Keyed on the SET of package names (not the count) so an install+uninstall that
    // keeps the total unchanged still re-triggers; distinctUntilChanged then suppresses
    // the self-trigger loop (ensureInstallSizes only writes installSize, not the set).
    private fun observeSizeSort() {
        viewModelScope.launch {
            combine(
                preferenceRepository.userPreferences.map { it.appSortBy }.distinctUntilChanged(),
                _rawState.map { state ->
                    (state.allUserApps + state.allSystemApps).mapTo(HashSet()) { it.packageName }
                }.distinctUntilChanged()
            ) { sortBy, packages -> sortBy to packages }
                .collect { (sortBy, packages) ->
                    if (sortBy == SortBy.SIZE && packages.isNotEmpty()) ensureInstallSizes()
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
            try {
                val packages = (_rawState.value.allUserApps + _rawState.value.allSystemApps)
                    .map { it.packageName }
                val sizes = storageStatsHelper.installSizes(packages)
                _rawState.update { state ->
                    state.copy(
                        needsUsageAccessPrompt = false,
                        allUserApps = state.allUserApps.map {
                            it.copy(installSize = sizes[it.packageName] ?: it.installSize)
                        },
                        allSystemApps = state.allSystemApps.map {
                            it.copy(installSize = sizes[it.packageName] ?: it.installSize)
                        }
                    )
                }
                appRepository.updateInstallSizes(sizes)
            } finally {
                _rawState.update { it.copy(isComputingSizes = false) }
            }
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
                        _events.send(
                            AppListEvent.ShowFreezerPrompt(FreezerPrompt(packageName, appName))
                        )
                    } else {
                        _events.send(
                            AppListEvent.ShowMessage(
                                UiText.StringResource(
                                    R.string.frozen_success,
                                    appName ?: packageName
                                )
                            )
                        )
                    }
                } else {
                    _events.send(
                        AppListEvent.ShowMessage(
                            UiText.StringResource(
                                R.string.unfrozen_success,
                                appName ?: packageName
                            )
                        )
                    )
                }
            }.onFailure { e ->
                _events.send(
                    AppListEvent.ShowMessage(
                        UiText.StringResource(
                            R.string.error_format,
                            e.message ?: ""
                        )
                    )
                )
            }
        }
    }

    fun addToFreezer(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            freezerRepository.add(packageName)
            _events.send(
                AppListEvent.ShowMessage(UiText.StringResource(R.string.added_to_freezer_success))
            )
        }
    }

    /**
     * Freezer watchlist membership, not freeze state — this adds/removes the app from the set the
     * freezer screen and the bulk freeze paths operate on; it never disables anything itself.
     *
     * Reads `contains()` rather than [AppListUiState.freezerPackageNames] so the decision is made
     * against the database at the moment of the tap, not against a state snapshot the observer may
     * not have refreshed yet. No state write here either: [observeFreezerMembership] is collecting
     * the same Room flow and reflects the change on its own.
     *
     * Adding is gated on [FreezeTier]; removing never is, so an app that got onto the watchlist
     * before the gate existed can always be taken back off.
     */
    fun toggleFreezerMembership(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (freezerRepository.contains(packageName)) {
                freezerRepository.remove(packageName)
                // Pinned launcher shortcuts can't be removed silently, only greyed out — leaving a
                // live shortcut for an app no longer in the freezer would let it drive a freeze
                // from the launcher.
                freezerShortcutManager.disableAppShortcut(packageName)
                _events.send(
                    AppListEvent.ShowMessage(
                        UiText.PluralsResource(R.plurals.removed_from_freezer_success, 1)
                    )
                )
            } else {
                // Same BLOCKED gate as FreezerViewModel.toggleManaged, and for the same reason: the
                // watchlist is the input to every bulk-freeze surface, so letting an unsafe app in
                // means the snowflake lights up and the app sits in the freezer list forever while
                // every run silently skips it. Refusing the add is the honest answer.
                //
                // Resolved against _rawState, not the filtered uiState: a search or filter that
                // hides the app must not turn into "not found" and, via the fail-closed branch
                // below, a refusal.
                val app = (_rawState.value.allUserApps + _rawState.value.allSystemApps)
                    .firstOrNull { it.packageName == packageName }
                if (app == null || app.freezeTier == FreezeTier.BLOCKED) {
                    // Fail closed on an unresolvable package: an unknown tier is not a safe tier.
                    _events.send(
                        AppListEvent.ShowMessage(UiText.StringResource(R.string.error_unsafe_skipped))
                    )
                    return@launch
                }
                freezerRepository.add(packageName)
                _events.send(
                    AppListEvent.ShowMessage(UiText.StringResource(R.string.added_to_freezer_success))
                )
            }
        }
    }

    fun performMultiAction(action: MultiAppAction) {
        viewModelScope.launch(Dispatchers.IO) {
            when (action) {
                is MultiAppAction.Freeze -> {
                    // EXPERT apps go through unwarned here by design — a batch is not the place to
                    // interrogate the user app by app. BLOCKED is stopped here; the single-app
                    // freeze paths still lean on the dialog hiding its confirm button instead.
                    val eligibleApps = action.appList.filter { it.freezeTier != FreezeTier.BLOCKED }
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
                            }
                        )
                    }
                    _events.send(
                        AppListEvent.ShowMessage(
                            if (failures == 0) {
                                UiText.PluralsResource(
                                    R.plurals.tile_freeze_success,
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
                    )
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
                            }
                        )
                    }
                    _events.send(
                        AppListEvent.ShowMessage(
                            UiText.PluralsResource(
                                R.plurals.unfrozen_count_success,
                                action.appList.size
                            )
                        )
                    )
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
        // Emit UiText identifiers instead of resolved strings so the ViewModel needs no Context;
        // AppListScreen resolves them via UiText.asString(context).
        val installerNames: Map<String, UiText> = installers.associateWith { pkg ->
            when (pkg) {
                "com.android.vending" -> UiText.StringResource(R.string.installer_play_store)
                "org.fdroid.fdroid" -> UiText.StringResource(R.string.installer_fdroid)
                // Sideloaded via the system package-installer UI: Google ships
                // com.google.android.packageinstaller, AOSP uses com.android.packageinstaller.
                "com.google.android.packageinstaller",
                "com.android.packageinstaller" -> UiText.StringResource(R.string.installer_sideloaded)
                else -> UiText.DynamicString(nameMap[pkg] ?: pkg)
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
