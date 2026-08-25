// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.APP_LIST_MIME
import com.valhalla.thor.domain.model.AppGridDensity
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkResult
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.InstalledAppsPermission
import com.valhalla.thor.domain.model.Installers
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.PermissionIndex
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.filterApps
import com.valhalla.thor.domain.model.freezeTier
import com.valhalla.thor.domain.model.sortApps
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.AppShortcutController
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.InstalledAppsPermissionGate
import com.valhalla.thor.domain.repository.InstallerLabelResolver
import com.valhalla.thor.domain.repository.PermissionRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.domain.repository.StorageStatsProvider
import com.valhalla.thor.domain.repository.UsageAccessGate
import com.valhalla.thor.domain.usecase.ExportAppListUseCase
import com.valhalla.thor.domain.usecase.FreezeAppUseCase
import com.valhalla.thor.domain.usecase.GetAppDetailsUseCase
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.presentation.freezer.FreezerPrompt
import com.valhalla.thor.presentation.launchGuarded
import com.valhalla.thor.util.AppScanRevision
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.asUiText
import com.valhalla.thor.util.bulkResultMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import org.koin.core.annotation.Named

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
    // Runtime-permission group -> declaring packages, plus the platform's own labels for the chips.
    // Empty until FilterType.Permission is selected; see observePermissionFilter().
    val permissionIndex: PermissionIndex = PermissionIndex(),
    val isLoadingPermissions: Boolean = false,
    // Distinct from "the index is empty". Both leave the chip row with nothing to show, but only one
    // of them is Thor's fault, and the row says so. The one-off toast is gone by the time a user
    // looks up from the empty row and wonders what happened.
    val permissionIndexFailed: Boolean = false,
    // Detail View State
    val selectedAppDetails: AppInfo? = null,
    val isLoadingDetails: Boolean = false,
    val isGrid: Boolean = true,
    val gridDensity: AppGridDensity = AppGridDensity.DEFAULT,
    val isComputingSizes: Boolean = false,
    // Holds the pull-to-refresh indicator up for a readable minimum. isLoading cannot do this job:
    // getAllApps() emits the Room cache before it starts the package rescan, so isLoading clears
    // after one DAO read and the indicator would blink out while the real scan is still running.
    val isManualRefreshing: Boolean = false,
    val needsUsageAccessPrompt: Boolean = false,
    // What this device says about com.android.permission.GET_INSTALLED_APPS. Only
    // InstalledAppsPermission.Denied puts anything on screen; the default is deliberately
    // Unsupported so nothing can render before the device has actually been asked. That default is
    // also the permanent answer on every AOSP build, which is the point — a Pixel does not define
    // this permission, so "not granted" there must never be mistaken for "denied" and turned into a
    // banner nobody can ever dismiss. See installedAppsPermissionState().
    val installedAppsPermission: InstalledAppsPermission = InstalledAppsPermission.Unsupported
)

/**
 * One-off UI events surfaced to [AppListScreen] via a buffered `Channel`, kept out of [AppListUiState]
 * so transient feedback is delivered exactly once and never replayed on recomposition/config change.
 */
sealed interface AppListEvent {
    data class ShowMessage(val message: UiText) : AppListEvent
    data class ShowFreezerPrompt(val prompt: FreezerPrompt) : AppListEvent

    /** Hand the exported list to another app. [uri] is a `content://` string; the screen chooses. */
    data class ShareList(val uri: String, val mime: String) : AppListEvent
}

@KoinViewModel
class AppListViewModel(
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val getAppDetailsUseCase: GetAppDetailsUseCase,
    private val privilege: PrivilegeStateProvider,
    private val manageAppUseCase: ManageAppUseCase,
    private val freezeAppUseCase: FreezeAppUseCase,
    private val preferenceRepository: PreferenceRepository,
    private val freezerRepository: FreezerRepository,
    private val appShortcuts: AppShortcutController,
    private val appRepository: AppRepository,
    private val permissionRepository: PermissionRepository,
    private val storageStats: StorageStatsProvider,
    private val usageAccess: UsageAccessGate,
    private val installedAppsPermission: InstalledAppsPermissionGate,
    private val installerLabelResolver: InstallerLabelResolver,
    private val exportAppListUseCase: ExportAppListUseCase,
    // Injected rather than hardcoded so a test can put every stage of this view model on one
    // scheduler: the sort/filter pipeline below runs off-main, and a `Dispatchers.Default` baked
    // in here would keep it on a real thread pool while the rest ran on virtual time.
    @Named("default") private val defaultDispatcher: CoroutineDispatcher,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private var appsJob: Job? = null
    private var sizeJob: Job? = null
    private var refreshIndicatorJob: Job? = null
    private var permissionIndexJob: Job? = null
    private var permissionRefreshJob: Job? = null

    /**
     * The one list export in flight, save or share.
     *
     * Both destinations are guarded by the same handle on purpose: they are one feature with two
     * endings, and the share half stages through a directory that is wiped on entry
     * (`AppBundleFileStoreImpl.stageText`). Two overlapping runs therefore do not merely write the
     * same file twice — the second one can delete the first one's staged file after the Uri for it
     * has already been handed out.
     *
     * A duplicate tap is dropped rather than reported. The work is a `buildString` and one write,
     * so the window is a few frames wide; a toast saying "already exporting" would appear and
     * disappear faster than it could be read, and would be the only message on this screen that
     * describes Thor's internals rather than the user's apps.
     */
    private var listExportJob: Job? = null
    private val _rawState = MutableStateFlow(AppListUiState())

    // One-off UI feedback (toasts, freezer prompt). A buffered Channel fires each event exactly
    // once (no replay on recomposition/config change) and, unlike a replay = 0 SharedFlow, retains
    // events emitted while no collector is active — e.g. a load-failure event from loadApps() in
    // init() before the screen's collector reaches STARTED, or during a rotation collector gap — so
    // they are delivered when the screen (re)subscribes rather than silently dropped.
    private val _events = Channel<AppListEvent>(Channel.BUFFERED)
    val events: Flow<AppListEvent> = _events.receiveAsFlow()

    // Combine raw app data with user preferences from DataStore
    // OPTIMIZATION: flowOn(defaultDispatcher) ensures sorting/filtering happens on background thread
    val uiState = combine(_rawState, preferenceRepository.userPreferences) { state, prefs ->
        val mergedState = state.copy(
            sortBy = prefs.appSortBy,
            sortOrder = prefs.appSortOrder,
            filterType = prefs.appFilterType,
            selectedFilter = prefs.appSelectedFilter,
            isGrid = prefs.appListIsGrid,
            gridDensity = prefs.appGridDensity
        )
        processList(mergedState)
    }
        .flowOn(defaultDispatcher) // Move computation off Main Thread
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppListUiState()
        )

    init {
        loadApps(deferForTransition = true)
        observeSizeSort()
        observeFreezerMembership()
        observePermissionFilter()
        refreshInstalledAppsPermission()
    }

    /**
     * Re-reads whether Thor may still see the full package list, for the banner in [AppListScreen].
     *
     * Called from `init` and again on every `ON_RESUME` and after every request, because the grant
     * this reports is the three-state kind: "while in use" reads as granted in the foreground and
     * stops being true the moment Thor is backgrounded, and a grant made in system Settings never
     * comes back through a result callback at all. A one-shot read at construction would show the
     * banner for a permission the user granted ten seconds later and keep showing it until the
     * process died.
     *
     * Off the main thread because the very first call resolves the checker's cached
     * `getPermissionInfo` binder round trip, and the callers are a lifecycle observer and an
     * activity-result callback — both of which run on the main thread, on resume, which is exactly
     * where a blocking package-manager call is most expensive.
     *
     * Strictly last-read-wins. The result callback and `ON_RESUME` fire within milliseconds of each
     * other when the user answers the dialog, so two reads can be in flight at once — and without
     * this, a slower earlier read that saw `Denied` could land *after* the newer one that saw the
     * grant, putting the banner back up over a permission the user just granted. Cancelling the
     * previous job closes most of the window and [ensureActive] closes the rest: a read that lost
     * the race is cancelled between the binder call and the state write, so it never publishes.
     */
    fun refreshInstalledAppsPermission() {
        permissionRefreshJob?.cancel()
        permissionRefreshJob = viewModelScope.launch(ioDispatcher) {
            val permission = installedAppsPermission.state()
            ensureActive()
            val previous = _rawState.value.installedAppsPermission
            _rawState.update { it.copy(installedAppsPermission = permission) }
            if (permission is InstalledAppsPermission.Granted && previous !is InstalledAppsPermission.Granted) {
                AppScanRevision.bump()
                loadApps()
            }
        }
    }

    /**
     * Builds the permission index whenever [FilterType.Permission] is active *and* the set of
     * installed apps has changed under it.
     *
     * Driven off the preference rather than [updateFilterType] so the persisted case is covered too
     * — a user who left Thor on this filter gets the index built at startup instead of an empty
     * chip row.
     *
     * The second input is the invalidation. The index is a snapshot of what every package declares,
     * so an install, an uninstall or an update makes it wrong, and the filter it feeds does not
     * degrade gracefully: a package the index has not heard of matches *no* group, so a freshly
     * installed camera app is simply missing from the Camera chip with nothing to indicate why. The
     * key is `packageName@lastUpdateTime`, which moves on all three of those events and stays put
     * for the ones that do not matter (freeze, suspend, a size arriving), so a session that installs
     * nothing pays for exactly one sweep. That the app list is *already* watching for those changes
     * is what makes this cheap: no `PACKAGE_ADDED` receiver, no polling, just the list Thor
     * refreshes anyway.
     *
     * Nothing is built until that set is non-empty, so the startup sweep waits for the first real
     * app load rather than racing it and then immediately redoing itself. That wait reports itself
     * as loading, because to the chip row an empty index during it is indistinguishable from a
     * device with no permission groups, and only one of those is true.
     */
    private fun observePermissionFilter() {
        viewModelScope.launch {
            combine(
                preferenceRepository.userPreferences.map { it.appFilterType }.distinctUntilChanged(),
                _rawState.map { state ->
                    (state.allUserApps + state.allSystemApps)
                        .mapTo(HashSet()) { "${it.packageName}@${it.lastUpdateTime}" }
                }.distinctUntilChanged()
            ) { type, packages -> type to packages }
                .collect { (type, packages) ->
                    permissionIndexJob?.cancel()
                    if (type != FilterType.Permission) {
                        _rawState.update { it.copy(isLoadingPermissions = false) }
                        return@collect
                    }
                    if (packages.isEmpty()) {
                        // Waiting on the app list, not done with it. Both states leave
                        // `permissionIndex` empty, and the chip row has to tell them apart: dropping
                        // out of loading here puts "No permission groups found on this device" —
                        // a claim about the *device* — on screen beside the main spinner, for every
                        // returning user who left the filter here. `permissionIndexFailed` is left
                        // alone rather than cleared; the loading state outranks it in that selector
                        // anyway, and the very next emission re-answers it from a real build.
                        _rawState.update { it.copy(isLoadingPermissions = true) }
                        return@collect
                    }
                    permissionIndexJob = launch {
                        _rawState.update {
                            it.copy(isLoadingPermissions = true, permissionIndexFailed = false)
                        }
                        val result = permissionRepository.buildPermissionIndex()
                        result.onFailure { e ->
                            Logger.e("AppListViewModel", "Permission index failed: ${e.message}")
                            _events.send(
                                AppListEvent.ShowMessage(
                                    UiText.StringResource(R.string.permission_filter_failed)
                                )
                            )
                        }
                        _rawState.update { state ->
                            state.copy(
                                // On failure the previous index is dropped rather than kept: a stale
                                // index filters silently and wrongly, an empty one shows a
                                // placeholder the user can act on.
                                permissionIndex = result.getOrElse { PermissionIndex() },
                                permissionIndexFailed = result.isFailure,
                                isLoadingPermissions = false
                            )
                        }
                    }
                }
        }
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
                val intensity = preferenceRepository.userPreferences
                    .first()
                    .animationIntensity
                // LOW resolves to ZERO, which delay() returns from without suspending.
                delay(settleDelayFor(intensity))
            }

            // Privilege availability now comes from the shared reactive PrivilegeManager,
            // so a Shizuku grant reflects here without reloading the list.
            combine(
                getInstalledAppsUseCase(),
                privilege.state
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
                    launch { usageAccess.maybeAutoGrant() }
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
            if (!usageAccess.isGranted() && !usageAccess.tryGrantViaPrivilege()) {
                _rawState.update { it.copy(needsUsageAccessPrompt = true) }
                return@launch
            }
            _rawState.update { it.copy(isComputingSizes = true) }
            try {
                val packages = (_rawState.value.allUserApps + _rawState.value.allSystemApps)
                    .map { it.packageName }
                val sizes = storageStats.installSizes(packages)
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
        // `launchGuarded`, not a bare `launch`, because of the freezer read inside `onSuccess`
        // below. `Result.onSuccess` catches nothing — its lambda is a plain inline lambda — so a
        // Room throw from `contains()` (SQLiteFullException, SQLiteDiskIOException,
        // SQLiteDatabaseCorruptException) walks straight out of it, out of the launch, and into the
        // thread's default handler, because `:app` installs no `CoroutineExceptionHandler`. One
        // freeze on a full disk was process death.
        //
        // The report here is deliberately unadorned. Everything on this path that can fail in a way
        // worth naming already names itself: both privileged calls return a `Result` and the
        // `onFailure` branch renders it, and the freezer read degrades on its own rather than
        // aborting (see below). What is left to reach this handler is an unexpected throw with
        // nothing to add to it, so it is shown as-is instead of being dressed up as a claim about
        // what did or did not happen to the app.
        launchGuarded(
            onFailure = { e ->
                Logger.e("AppListViewModel", "freeze toggle for $packageName failed", e)
                _events.trySend(AppListEvent.ShowMessage(e.asUiText()))
            }
        ) {
            // Freezing goes through FreezeAppUseCase so the BLOCKED tier is enforced below this
            // view model rather than by AppRiskDialog declining to render a confirm button.
            // Unfreezing keeps the raw call: it must never be blocked.
            val result = if (freeze) freezeAppUseCase(packageName)
            else manageAppUseCase.setAppDisabled(packageName, false)
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
                    // "Not in the freezer" is the safe answer when the read fails — the same
                    // fallback observeFreezerMembership's `catch` takes, for the same reason. The
                    // app is already frozen by the time this runs, and all this Boolean decides is
                    // whether to offer to track it. Guessing `true` would leave a frozen app off
                    // the watchlist without a word, which is the stranding this feature exists to
                    // prevent; guessing `false` costs at worst one prompt for an app already on it,
                    // and `FreezerDao.insert` is `OnConflictStrategy.IGNORE`, so confirming that
                    // prompt is a no-op rather than a duplicate row. Caught here rather than left
                    // to the guard above because the guard can only abandon the block — and
                    // abandoning it here would drop the freeze report the user is owed for a
                    // freeze that succeeded.
                    val inFreezer = try {
                        withContext(ioDispatcher) {
                            freezerRepository.contains(packageName)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(
                            "AppListViewModel",
                            "freezer membership read for $packageName failed",
                            e
                        )
                        false
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
                // The tier refusal arrives here as a UiTextException, which carries its message in
                // `uiText` and leaves `message` null — see [asUiText].
                _events.send(AppListEvent.ShowMessage(e.asUiText()))
            }
        }
    }

    /**
     * The "Frozen — add it to the Freezer?" prompt's confirm.
     *
     * Deliberately **not** tier-gated, unlike [toggleFreezerMembership]. This only ever runs after
     * [freezeApp] succeeded, so the app is already frozen; the question is whether to track it, not
     * whether to freeze it. Membership is what makes a frozen app recoverable —
     * `freezableCandidates` drops `blockedFromFreeze` from FREEZE runs but filters UNFREEZE runs on
     * `state == FROZEN` alone — so tracking can never cause a re-freeze and is the only way
     * Unfreeze-all reaches it. Refusing here would strand a frozen app off the list it belongs on.
     */
    fun addToFreezer(packageName: String) {
        launchGuarded(
            // `ioDispatcher` passed through rather than dropped: this is a Room write, and letting
            // it default to `Dispatchers.Main.immediate` would move it to the main thread without
            // anything failing to say so, because Room's suspend DAO functions dispatch internally
            // and would keep working.
            context = ioDispatcher,
            // The app is *already frozen* when this runs — the prompt this confirms only appears
            // after [freezeApp] succeeded — so a failed insert has undone nothing. What is lost is
            // the row that makes a frozen app recoverable: gone from the freezer screen and out of
            // Unfreeze-all's reach, which iterates the watchlist. That is worth reporting, but it
            // is emphatically not "the freeze failed", and no existing string says "it is frozen,
            // Thor just could not write it down" — one Room failure is not worth an English-only
            // ninth string across eight locales. So the throw is reported verbatim instead: on the
            // failures that actually reach here (a full disk, a failing one) its message names the
            // disk, which is the part the user can act on.
            onFailure = { e ->
                Logger.e("AppListViewModel", "adding $packageName to the freezer failed", e)
                _events.trySend(AppListEvent.ShowMessage(e.asUiText()))
            }
        ) {
            freezerRepository.add(packageName)
            _events.send(
                AppListEvent.ShowMessage(UiText.StringResource(R.string.added_to_freezer_success))
            )
        }
    }

    /**
     * Freezer watchlist membership. Adding never freezes; **removing always restores**, so an app
     * can never be left frozen but untracked.
     *
     * That asymmetry is deliberate and matches [FreezerViewModel.removeFromFreezer]: leaving a
     * removed app frozen strands it somewhere the freezer screen no longer lists, and the user's
     * only route back is the import-already-disabled flow. Every surface that can take an app off
     * the watchlist has to restore it, or the answer depends on which screen you tapped.
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
        // Non-null once the restore has come back successful, holding the label to report it with.
        // `onFailure` is not suspend and cannot re-derive which side of the irreversible step a
        // throw landed on, and the two sides mean opposite things: before the restore nothing has
        // happened to the app and a Room throw means the tap did nothing, while after it the app
        // really is thawed and only Thor's record of it is missing. Reporting both the same way
        // would tell a user whose app just came back that the unfreeze failed.
        var unfrozenLabel: String? = null
        launchGuarded(
            // `ioDispatcher` passed through rather than dropped: this body runs the three watchlist
            // calls *and* the privileged restore, and the default context would put all four on
            // `Dispatchers.Main.immediate` with nothing failing to report it.
            context = ioDispatcher,
            // All three Room calls in this body report by throwing, and the delete is the one that
            // matters: it runs after `restoreApp`/`forceUnfreeze` has already succeeded, so an
            // escaping throw used to land between the irreversible act and the record of it — and
            // then kill the process, leaving exactly the frozen-but-untracked stranding this
            // method's KDoc promises to prevent, arrived at from the other direction.
            onFailure = { e ->
                Logger.e("AppListViewModel", "freezer membership toggle for $packageName failed", e)
                val unfrozen = unfrozenLabel
                if (unfrozen != null) {
                    // Two facts, in the order they matter, the same as
                    // AppInfoDetailsViewModel.addOrRemoveFromFreezer — the other surface that takes an
                    // app off the watchlist, and one that must not answer the same failure
                    // differently. The restore returned success, so the app is enabled and
                    // unsuspended, and saying so first is the only claim here that is certainly true;
                    // a bare "Error: …" on its own would read as "the unfreeze failed" over an app the
                    // user can see running.
                    //
                    // Then the throw, rather than stopping at the good news. The undropped row is
                    // survivable — it keeps the app on the freezer screen and inside Unfreeze-all's
                    // reach, and the next tap retries the delete after a restore that is by then a
                    // no-op — but "survivable" is not "not worth mentioning": whatever broke the
                    // delete is a failing disk, and that is the part the user can act on.
                    _events.trySend(
                        AppListEvent.ShowMessage(
                            UiText.StringResource(R.string.unfrozen_success, unfrozen)
                        )
                    )
                }
                // Nothing has happened to the app on the other side of the restore — the membership
                // read that chooses the branch, or the insert that only ever adds a row (adding never
                // freezes) — so on that path this is the whole report.
                _events.trySend(AppListEvent.ShowMessage(e.asUiText()))
            }
        ) {
            if (freezerRepository.contains(packageName)) {
                // Restore before dropping the row, and before reporting success. The privileged call
                // is the only step that can fail and the Room delete is durable, so removing first
                // would leave a failed restore holding a frozen app with no watchlist entry to retry
                // from — the exact stranding this method exists to prevent. Resolved against
                // _rawState for the same reason the add path is: a search or filter that hides the
                // app must not decide its fate. When it can't be resolved, forceUnfreeze does both
                // halves unconditionally — an unsuspend on a non-suspended app and an enable on an
                // enabled one are no-ops, so the fallback is safe rather than merely tolerable.
                val app = (_rawState.value.allUserApps + _rawState.value.allSystemApps)
                    .firstOrNull { it.packageName == packageName }
                val restored =
                    if (app != null) manageAppUseCase.restoreApp(packageName, app.enabled, app.isSuspended)
                    else manageAppUseCase.forceUnfreeze(packageName)
                restored.onFailure { e ->
                    _events.send(
                        AppListEvent.ShowMessage(
                            UiText.StringResource(R.string.error_format, e.message ?: "")
                        )
                    )
                    return@launchGuarded
                }
                // Latched between the privileged call and the durable one, which is the only place
                // it can be right: past here the app is thawed whatever the rest of this block does.
                unfrozenLabel = app?.appName ?: packageName
                // Same optimistic local patch freezeApp does, so the row stops reading as frozen
                // without waiting for a full rescan. Ordered ahead of the two steps that can throw,
                // because it cannot: it is a CAS over two `List.map`s, touching neither Room nor the
                // shortcut service. Left behind them, a throw from either would skip it and leave the
                // list drawing the app as frozen while the guard above says "Unfrozen X" — the screen
                // contradicting its own toast, on the one path where the toast is certainly true.
                _rawState.update { state ->
                    fun restore(list: List<AppInfo>) = list.map {
                        if (it.packageName == packageName) it.copy(enabled = true, isSuspended = false)
                        else it
                    }
                    state.copy(
                        allUserApps = restore(state.allUserApps),
                        allSystemApps = restore(state.allSystemApps)
                    )
                }
                // Grey the shortcut before dropping the row. A pinned shortcut can only be disabled,
                // never removed — `disableShortcuts` is the whole of what the app gets — so both
                // orders leave residue when their second step throws, and the only question is which
                // residue the user can get out of.
                //
                // Greying first: the disable throws, the row survives, the app is still listed in the
                // freezer, so the same toggle retries the whole pair and the guard above has already
                // said what failed. Row first: the delete lands, the disable throws, and the app is
                // gone from the freezer screen — which is the surface that would have retried the
                // disable. What is left is an orphaned live shortcut and no route back to it.
                //
                // Note what that orphan does *not* do, because an earlier version of this comment had
                // it backwards. A per-app shortcut carries ACTION_LAUNCH, and
                // FreezerLaunchActivity.launchApp answers it with forceUnfreeze-then-start, so a stale
                // one thaws an app — it cannot freeze one. And it is not self-healing either: a
                // shortcut greyed by `disableShortcuts` shows `shortcut_no_longer_frozen` instead of
                // firing, so the way back is a fresh "Add to home screen", not the next tap. The cost
                // of getting this order wrong is a launcher tile that outlives the watchlist row it
                // was made for, not a freeze nobody asked for.
                appShortcuts.disableAppShortcut(packageName)
                freezerRepository.remove(packageName)
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
                    return@launchGuarded
                }
                freezerRepository.add(packageName)
                _events.send(
                    AppListEvent.ShowMessage(UiText.StringResource(R.string.added_to_freezer_success))
                )
            }
        }
    }

    fun performMultiAction(action: MultiAppAction) {
        viewModelScope.launch(ioDispatcher) {
            when (action) {
                is MultiAppAction.Freeze -> {
                    // EXPERT apps go through unwarned here by design — a batch is not the place to
                    // interrogate the user app by app. BLOCKED is filtered here rather than left
                    // to FreezeAppUseCase (which is what `freezeApp` uses) so the skipped apps
                    // are counted once, in `failures`, instead of each costing a redundant
                    // getAppDetails on the way to a second report of the same refusal.
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
                            bulkResultMessage(
                                BulkResult(
                                    op = BulkOp.FREEZE,
                                    total = action.appList.size,
                                    succeeded = succeededPackages.size,
                                    failed = failures,
                                )
                            )
                        )
                    )
                }

                is MultiAppAction.UnFreeze -> {
                    // Only the packages that actually came back successful, for both halves of the
                    // report. This used to discard every result, mark the whole selection
                    // `enabled = true` and then send an unconditional success plural for
                    // `appList.size` — so a batch where nothing was unfrozen said "Unfroze 12 apps"
                    // and drew twelve thawed rows to match. The freeze branch above always counted
                    // properly; only this direction did not.
                    //
                    // `forceUnfreeze`, not `setAppDisabled(_, false)` and not
                    // `restoreApp(_, app.enabled, app.isSuspended)`:
                    //
                    //  - `setAppDisabled` clears one of the two dimensions a frozen app can be
                    //    frozen in. An app suspended from any other surface — the Freezer's suspend
                    //    mode, `MainViewModel.performCountedFreeze(useSuspend = true)`, the QS tile,
                    //    an extension — comes back still suspended, and the report above now says so
                    //    *precisely*: it counts the enable that succeeded while the user still can't
                    //    open the app.
                    //  - `restoreApp` reads the flags, and the flags here are stale by construction.
                    //    `isSuspended` is patched in exactly one place in this ViewModel
                    //    ([toggleFreezerMembership]) and never on a bulk path, so it only moves on a
                    //    full rescan. A snapshot that still calls a just-suspended app active makes
                    //    `restorePlanFor` plan nothing, and `restoreApp` then returns success having
                    //    made zero privileged calls — the same lie this branch was just fixed to stop
                    //    telling, re-entering through the choice of API. FreezerViewModel documents
                    //    this trap twice for the same reason.
                    //
                    // `forceUnfreeze` asks unconditionally, which is what its KDoc is for ("bulk
                    // 'unfreeze all' when per-app state isn't known"). The cost is one redundant
                    // unsuspend per already-active app; root and Shizuku answer that from the flag
                    // alone and Dhizuku pays one `pm unsuspend`. A redundant call is the cheaper of
                    // the two mistakes.
                    val succeededPackages = mutableSetOf<String>()
                    action.appList.forEach { app ->
                        if (manageAppUseCase.forceUnfreeze(app.packageName).isSuccess) {
                            succeededPackages.add(app.packageName)
                        }
                    }
                    _rawState.update { state ->
                        // Both dimensions, because forceUnfreeze cleared both. Patching only
                        // `enabled` would leave a thawed app drawn as suspended until the next
                        // rescan, and would leave the next unfreeze reading that stale flag.
                        state.copy(
                            allUserApps = state.allUserApps.map {
                                if (it.packageName in succeededPackages) {
                                    it.copy(enabled = true, isSuspended = false)
                                } else it
                            },
                            allSystemApps = state.allSystemApps.map {
                                if (it.packageName in succeededPackages) {
                                    it.copy(enabled = true, isSuspended = false)
                                } else it
                            }
                        )
                    }
                    _events.send(
                        AppListEvent.ShowMessage(
                            bulkResultMessage(
                                BulkResult(
                                    op = BulkOp.UNFREEZE,
                                    total = action.appList.size,
                                    succeeded = succeededPackages.size,
                                    failed = action.appList.size - succeededPackages.size,
                                )
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
            // Keep the filter *category*, reset only the selection. This used to hardcode
            // FilterType.Source, which was invisible while Source was the only interesting default
            // but means "filter by Camera, then tap System apps" silently throws you back to
            // Installation Source — reading as a bug in whichever filter you had chosen.
            preferenceRepository.updateAppFilter(uiState.value.filterType, "All")
        }
    }

    /**
     * Jumps the list to one installer's apps — where the Home distribution chart's bars lead.
     *
     * Sets the list type as well, because that chart is drawn per type and a bar read off System
     * names apps a list left on User would hide, so the tap would land on an empty screen.
     *
     * Deliberately not [updateListType] followed by [updateFilter]: the first of those resets the
     * selection to "All", so the pair would queue two writes and the list would depend on their
     * order to not throw away the very filter this was called to apply. One write, right value.
     */
    fun showAppsFromInstaller(type: AppListType, installerPackageName: String) {
        _rawState.update { it.copy(appListType = type) }
        viewModelScope.launch {
            preferenceRepository.updateAppFilter(FilterType.Source, installerPackageName)
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

    /**
     * Save the list as it is on screen to the export destination.
     *
     * [AppListUiState.displayedApps] and nothing else: the tab, the search, the filter and the sort
     * are all already applied to it, so what a user gets is what they were looking at. That is also
     * the whole feature — the request behind it is "give me a list of what I have to work through",
     * and a list that ignored the filter you set to narrow it down would be no use at all. Someone
     * who wants everything clears the filter first.
     */
    fun exportList() {
        val apps = uiState.value.displayedApps
        if (apps.isEmpty()) {
            // Not a failure: nothing was written because there was nothing to write. Saying so is
            // the difference between "the filter is too narrow" and "the export is broken".
            viewModelScope.launch {
                _events.send(
                    AppListEvent.ShowMessage(UiText.StringResource(R.string.export_list_empty))
                )
            }
            return
        }
        if (listExportJob?.isActive == true) return
        listExportJob = viewModelScope.launch {
            exportAppListUseCase(apps)
                .onSuccess {
                    _events.send(
                        AppListEvent.ShowMessage(UiText.StringResource(R.string.export_saved, it))
                    )
                }
                .onFailure { e ->
                    Logger.e("AppListViewModel", "list export failed", e)
                    _events.send(
                        AppListEvent.ShowMessage(
                            UiText.StringResource(R.string.export_list_failed)
                        )
                    )
                }
        }
    }

    /** Hand the same list straight to another app, without writing a copy to the export folder. */
    fun shareList() {
        val apps = uiState.value.displayedApps
        if (apps.isEmpty()) {
            viewModelScope.launch {
                _events.send(
                    AppListEvent.ShowMessage(UiText.StringResource(R.string.export_list_empty))
                )
            }
            return
        }
        if (listExportJob?.isActive == true) return
        listExportJob = viewModelScope.launch {
            exportAppListUseCase.shareUri(apps)
                .onSuccess { _events.send(AppListEvent.ShareList(it, APP_LIST_MIME)) }
                .onFailure { e ->
                    Logger.e("AppListViewModel", "list share failed", e)
                    _events.send(
                        AppListEvent.ShowMessage(
                            UiText.StringResource(R.string.export_list_failed)
                        )
                    )
                }
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

        // 3. Filter by Source/State/Permission — the rules live in domain so they are testable,
        // the same way sorting does.
        val filtered = filterApps(
            apps = searched,
            filterType = state.filterType,
            selectedFilter = state.selectedFilter,
            permissionIndex = state.permissionIndex
        )

        // 4. Sort
        val sorted = getSortedList(filtered, state.sortBy, state.sortOrder)

        // 5. Calculate Installers (Metadata) - OPTIMIZED
        // Only recalculate map if the full list changed (avoid doing this on search)
        val installers =
            rawList.mapNotNull { it.installerPackageName }.distinct().sorted().toMutableList()

        // Emit UiText identifiers instead of resolved strings so the ViewModel needs no Context;
        // AppListScreen resolves them via UiText.asString(context).
        //
        // Anything outside the curated three is named by [installerLabelResolver]. It used to be
        // looked up in the list being shown, which made the name depend on the tab: Aurora Store is
        // a user app, so its apps were "Aurora Store" on the User tab and "com.aurora.store" on the
        // System tab. The resolver asks the package manager, which knows either way, and memoises —
        // this runs on every search keystroke.
        val installerNames: Map<String, UiText> = installers.associateWith { pkg ->
            when (pkg) {
                Installers.PLAY_STORE -> UiText.StringResource(R.string.installer_play_store)
                Installers.F_DROID -> UiText.StringResource(R.string.installer_fdroid)
                // Sideloaded via the system package-installer UI: Google ships
                // com.google.android.packageinstaller, AOSP uses com.android.packageinstaller.
                in Installers.PACKAGE_INSTALLERS ->
                    UiText.StringResource(R.string.installer_sideloaded)

                else -> UiText.DynamicString(installerLabelResolver.labelFor(pkg) ?: pkg)
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
