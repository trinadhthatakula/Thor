// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import android.database.sqlite.SQLiteConstraintException
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.data.launcher.FreezerShortcutContract
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkOutcome
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.BulkScope
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.freezeTier
import com.valhalla.thor.domain.repository.AppShortcutController
import com.valhalla.thor.domain.repository.BulkFreezeController
import com.valhalla.thor.domain.repository.FreezeProfileRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.domain.usecase.FreezeAppUseCase
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.UiTextException
import com.valhalla.thor.util.bulkResultMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

// packageName + appName of an app frozen outside the freezer list — drives the "Add to Freezer" snackbar
data class FreezerPrompt(val packageName: String, val appName: String?)

// One-off UI feedback that must fire exactly once — kept off the UiState StateFlow so it isn't
// re-delivered on recomposition/config change. Collected in FreezerScreen via ObserveAsEvents.
sealed interface FreezerEvent {
    data class ShowToast(val message: UiText) : FreezerEvent
    data class ShowFreezerPrompt(val packageName: String, val appName: String?) : FreezerEvent
}

data class FreezerUiState(
    val isLoading: Boolean = true,
    val isRoot: Boolean = false,
    val isShizuku: Boolean = false,
    val freezerApps: List<AppInfo> = emptyList(),
    val freezerPackageNames: Set<String> = emptySet(),
    val allInstalledApps: List<AppInfo> = emptyList(),
    val multiSelection: Set<String> = emptySet(),
    val searchQuery: String = "",
    val manageSheetSearchQuery: String = "",
    val autoFreezeEnabled: Boolean = false,
    val freezerMode: FreezerMode = FreezerMode.FREEZE,
    val isDhizuku: Boolean = false,
    val hasShownDisabledAppsPrompt: Boolean = false,
    val appListType: AppListType = AppListType.USER,
    val isGrid: Boolean = true,
    val addFreezerToLauncher: Boolean = false,
    val profiles: List<FreezeProfile> = emptyList(),
    val profileEditorSearchQuery: String = "",
    /**
     * Every bulk run in flight, oldest first. Carries whole requests, not a Boolean, so a profile
     * row can show its own spinner without every other row spinning alongside it — and a list
     * rather than one slot because runs of the same op serialize, so a profile queued behind a
     * watchlist freeze must not erase the freeze that is actually running.
     */
    val runningRequests: List<BulkRequest> = emptyList()
)

@KoinViewModel
class FreezerViewModel(
    private val freezerRepository: FreezerRepository,
    private val freezeProfileRepository: FreezeProfileRepository,
    // The two-member port, not the concrete BulkFreezeRunner: the runner takes four collaborators
    // that need a Context, a PackageManager or Shizuku's listeners, and naming the class kept this
    // whole view model — watchlist removal included — out of reach of a JVM test.
    private val bulkFreeze: BulkFreezeController,
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val manageAppUseCase: ManageAppUseCase,
    private val freezeAppUseCase: FreezeAppUseCase,
    // The read-only privilege port and the shortcut port, not the concrete PrivilegeManager /
    // FreezerShortcutManager: one registers Shizuku binder listeners from its initializer and the
    // other needs a Context, so depending on either class put this whole view model out of reach of
    // a JVM test. This screen only observes the probe and never triggers a re-probe.
    private val privilege: PrivilegeStateProvider,
    private val preferenceRepository: PreferenceRepository,
    private val appShortcuts: AppShortcutController,
    // Injected rather than baked-in Dispatchers.Default/IO so a test can put every stage of this
    // view model on one scheduler — otherwise the actions below escape the test dispatcher and
    // nothing here is deterministically assertable, and the app-list fold behind `uiState` keeps
    // running on a real thread pool while the rest runs on virtual time. Same pair, and the same
    // reason, as AppListViewModel.
    @Named("default") private val defaultDispatcher: CoroutineDispatcher,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(FreezerUiState())
    val uiState: StateFlow<FreezerUiState> = _uiState.asStateFlow()

    // Buffered Channel (not a replay=0 SharedFlow) so a one-off event emitted while no collector is
    // subscribed — e.g. the load-failure toast from observeApps() in init(), fired before the screen's
    // ObserveAsEvents reaches STARTED (FreezerViewModel is created eagerly by MainScreen) — is retained
    // and delivered when the screen subscribes rather than silently dropped. Matches MainViewModel.
    private val _events = Channel<FreezerEvent>(Channel.BUFFERED)
    val events: Flow<FreezerEvent> = _events.receiveAsFlow()

    init {
        observeApps()
        observePreferences()
        observeProfiles()
        observeRunningRequest()
    }

    private fun observeApps() {
        viewModelScope.launch {
            try {
                combine(
                    freezerRepository.getAll(),
                    getInstalledAppsUseCase(),
                    privilege.state
                ) { freezerPkgs, (userApps, systemApps), priv ->
                    val pkgSet = freezerPkgs.toSet()
                    val allApps = userApps + systemApps
                    Triple(pkgSet, allApps.filter { it.packageName in pkgSet }, allApps) to priv
                }
                    .retryWhen { cause, attempt ->
                        // transient PM/binder failures self-heal; never retry cancellation; bounded so a hard
                        // failure still falls through to the catch (which shows the load-failure toast).
                        if (cause is CancellationException || attempt >= 2) {
                            false
                        } else {
                            delay(500)
                            true
                        }
                    }
                    .flowOn(defaultDispatcher)
                    .collect { (appsData, priv) ->
                        val (pkgSet, freezerApps, allApps) = appsData
                        _uiState.update {
                            it.copy(
                                // Hold the loader until the first privilege probe lands so
                                // freeze/unfreeze controls never flash disabled on cold start;
                                // privilege flags now update atomically with the app list.
                                isLoading = !priv.isReady,
                                freezerPackageNames = pkgSet,
                                freezerApps = freezerApps,
                                allInstalledApps = allApps,
                                isRoot = priv.root,
                                isShizuku = priv.shizuku,
                                isDhizuku = priv.dhizuku
                            )
                        }
                    }
            } catch (e: Exception) {
                Logger.e("FreezeViewModel", "observe apps failed", e)
                _uiState.update { it.copy(isLoading = false) }
                emitToast(UiText.StringResource(R.string.failed_to_load_apps))
            }
        }
    }

    // Freeze-all / Unfreeze-all are handled by the shared batch action
    // (MultiAppAction.Freeze / UnFreeze) so their progress streams into the
    // TermLoggerDialog; the toolbar in FreezerScreen dispatches them via onMultiAppAction.

    // --- Multi-select removal ---

    fun toggleSelection(packageName: String) {
        _uiState.update {
            val sel = it.multiSelection.toMutableSet()
            if (packageName in sel) sel.remove(packageName) else sel.add(packageName)
            it.copy(multiSelection = sel)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(multiSelection = emptySet()) }
    }

    fun selectAll(packageNames: Collection<String> = _uiState.value.freezerPackageNames) {
        _uiState.update { it.copy(multiSelection = packageNames.toSet()) }
    }

    fun updateListType(type: AppListType) {
        _uiState.update { it.copy(appListType = type) }
    }

    /**
     * Take a multi-selection off the watchlist, restoring each app first.
     *
     * Restore, *then* delete the row — per package, and only on success. The Room delete is durable
     * and the privileged call is the only step that can fail, so the old order left a failed restore
     * holding a frozen app with no watchlist entry: gone from this screen, and out of reach of
     * Unfreeze-all, which iterates the watchlist (GH#310). Same ordering
     * [com.valhalla.thor.presentation.appList.AppListViewModel.toggleFreezerMembership] uses for the
     * single-app case. Nothing here aborts the loop — a per-package failure is recorded and the rest
     * of the selection still runs — because the bug was never that it stopped too early.
     */
    fun removeFromFreezer(packageNames: Set<String>) {
        viewModelScope.launch(ioDispatcher) {
            val succeeded = mutableSetOf<String>()
            val failures = mutableListOf<Throwable>()
            packageNames.forEach { pkg ->
                try {
                    // forceUnfreeze, not restoreApp(app.enabled, app.isSuspended): the flags would
                    // come from allInstalledApps, a rescan snapshot that the suspend-freeze path
                    // never patches, so an app suspended a moment ago still reads active here and
                    // restorePlanFor() plans nothing at all. restoreApp would then return success
                    // having made zero privileged calls, and the app would leave the watchlist still
                    // suspended — a failure that reports as one. forceUnfreeze asks unconditionally
                    // instead. Root and Shizuku answer an unsuspend of a never-suspended app from
                    // the flag alone (RootSystemGateway.unsuspendPackage and Shizuku.setAppSuspended
                    // both early-return on a *positive* not-suspended read); Dhizuku pays one
                    // `pm unsuspend` for it. A redundant call is the cheaper of the two mistakes.
                    manageAppUseCase.forceUnfreeze(pkg)
                        .onFailure { e ->
                            failures += e
                            return@forEach
                        }
                    freezerRepository.remove(pkg)
                    // Pinned shortcuts can only be greyed, never removed — leaving a live one for an
                    // app no longer in the freezer would let the launcher drive a freeze from it.
                    appShortcuts.disableAppShortcut(pkg)
                    succeeded += pkg
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Room and ShortcutManagerCompat report by throwing, not by returning a Result,
                    // and :app installs no CoroutineExceptionHandler — one escaping here would
                    // abandon the rest of the selection and take the report down with it.
                    Logger.e("FreezeViewModel", "removing $pkg from the freezer failed", e)
                    failures += e
                }
            }
            // Only what actually left. A failure stays selected so the retry is one more tap rather
            // than finding the app in the list again.
            _uiState.update { it.copy(multiSelection = it.multiSelection - succeeded) }
            emitToast(
                when (failures.size) {
                    0 -> UiText.PluralsResource(
                        R.plurals.removed_from_freezer_success,
                        succeeded.size
                    )
                    // One failure: say what the gateway said. Post-GH#330 that message names the
                    // privilege still holding the app, which is the whole answer — a count would be
                    // strictly less than what the Apps tab already gives for the same action.
                    1 -> UiText.StringResource(
                        R.string.error_format,
                        failures.first().message ?: ""
                    )
                    // Past one there is no single message to show, so report the split instead.
                    else -> UiText.StringResource(
                        R.string.removed_from_freezer_partial_failure,
                        succeeded.size,
                        packageNames.size,
                        failures.size
                    )
                }
            )
        }
    }

    // --- Manage Sheet ---

    fun toggleManaged(packageName: String, add: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            if (add) {
                // The blocked tier, enforced here and not only in the sheet that calls this.
                // Adding does two things — it freezes the app now, and it enlists it in every
                // later bulk run — so a surface that forgot to ask would hand the QS tile and
                // the launcher shortcuts a target the in-app dialog refuses to even offer a
                // confirm button for. The sheet still asks first; this is the backstop.
                //
                // A miss fails closed. allInstalledApps is a snapshot of the last full rescan,
                // so between the tap and this coroutine a refresh can drop the entry — and the
                // very next statement freezes. Treating "not found" as "not blocked" would let
                // an unclassified system app through on exactly the timing where we know least
                // about it. error_unsafe_skipped covers both readings: it says UNSAFE / safety
                // check failed.
                val app = _uiState.value.allInstalledApps
                    .firstOrNull { it.packageName == packageName }
                if (app == null || app.freezeTier == FreezeTier.BLOCKED) {
                    emitToast(UiText.StringResource(R.string.error_unsafe_skipped))
                    return@launch
                }
                val freezeResult = if (_uiState.value.freezerMode == FreezerMode.SUSPEND)
                    manageAppUseCase.setAppSuspended(packageName, true)
                else manageAppUseCase.setAppDisabled(packageName, true)
                freezeResult
                    .onSuccess {
                        freezerRepository.add(packageName)
                    }
                    .onFailure { e ->
                        emitToast(UiText.StringResource(R.string.error_format, e.message ?: ""))
                    }
            } else {
                // Restore first, drop the row second — the same ordering [removeFromFreezer] uses,
                // and for the same reason. This path already reported the failure, but over a row
                // that was gone by the time the toast appeared: the app stayed frozen with nothing
                // left to retry from (GH#310).
                //
                // forceUnfreeze rather than restoreApp(app.enabled, app.isSuspended), also as in
                // [removeFromFreezer], and this switch is the shortest route to the trap that
                // choice avoids: it is drawn from freezerPackageNames, which re-emits the instant
                // the row lands, while the flags would come from the app lists, which only move on
                // the next full rescan. So switching an app on and straight back off restores from
                // a snapshot that still calls it active, plans nothing, and reports success having
                // made no privileged call at all — the row goes, the app stays frozen.
                manageAppUseCase.forceUnfreeze(packageName)
                    .onFailure { e ->
                        emitToast(UiText.StringResource(R.string.error_format, e.message ?: ""))
                        return@launch
                    }
                freezerRepository.remove(packageName)
                appShortcuts.disableAppShortcut(packageName)
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateManageSheetSearch(query: String) {
        _uiState.update { it.copy(manageSheetSearchQuery = query) }
    }

    // --- Freeze profiles (#55a) ---

    private fun observeProfiles() {
        viewModelScope.launch {
            freezeProfileRepository.observeProfiles()
                // Same bounded retry the app list gets, and for the same reason: `catch` ends the
                // flow, so without this one transient Room failure freezes the profiles list for
                // the rest of the process — the sheet keeps showing a stale set of profiles and
                // the only way back is to restart the app.
                .retryWhen { cause, attempt ->
                    if (cause is CancellationException || attempt >= 2) {
                        false
                    } else {
                        delay(500)
                        true
                    }
                }
                // A Room read failure must not take the whole Freezer screen down with it: the
                // watchlist is a separate flow and stays perfectly usable without profiles.
                .catch { e ->
                    Logger.e("FreezeViewModel", "observe profiles failed", e)
                    emitToast(UiText.StringResource(R.string.error_profiles_load_failed))
                }
                .collect { profiles -> _uiState.update { it.copy(profiles = profiles) } }
        }
    }

    private fun observeRunningRequest() {
        viewModelScope.launch {
            bulkFreeze.runningRequests.collect { requests ->
                _uiState.update { it.copy(runningRequests = requests) }
            }
        }
    }

    fun updateProfileEditorSearch(query: String) {
        _uiState.update { it.copy(profileEditorSearchQuery = query) }
    }

    /**
     * Run a profile through [BulkFreezeController] rather than freezing here.
     *
     * That routing is the whole point of the runner's `targetsFor`: it is where the
     * [FreezeTier] block is applied to a *list*, so a profile cannot freeze what the in-app
     * dialog refuses to offer a confirm button for. It also gets same-request coalescing and
     * the serialize-don't-cancel rule for free — tapping two profiles runs both, in order.
     *
     * The awaited result is reported as a toast. A profile run deliberately does not park its
     * result in the tile subtitle (see the runner), so this is the only surface that reports it
     * besides the notification, which the user may not have permitted.
     */
    fun runProfile(profileId: Long, op: BulkOp) {
        viewModelScope.launch {
            val outcome = bulkFreeze
                .launch(BulkRequest(op, BulkScope.Profile(profileId)))
                .await()
            emitToast(
                when (outcome) {
                    is BulkOutcome.Completed -> bulkResultMessage(outcome.result)
                    // A no-op: no privilege, or nothing left to act on after the tier filter.
                    // Saying "Froze 0 apps" would read as a failure of the freeze rather than of
                    // the precondition, so name the precondition instead.
                    BulkOutcome.NothingToDo ->
                        UiText.StringResource(R.string.profile_nothing_to_do)
                    // And this is not that. The run raised — Room, a dead binder — possibly after
                    // freezing part of the profile, so the one thing that must not be said is
                    // that there was nothing to do.
                    is BulkOutcome.Failed -> UiText.StringResource(R.string.bulk_run_failed)
                }
            )
        }
    }

    fun createProfile(name: String, packageNames: List<String>) {
        viewModelScope.launch(ioDispatcher) {
            runProfileWrite(R.string.error_profile_name_taken) {
                freezeProfileRepository.create(name, packageNames)
                emitToast(UiText.StringResource(R.string.profile_saved))
            }
        }
    }

    fun updateProfile(profileId: Long, name: String, packageNames: List<String>) {
        viewModelScope.launch(ioDispatcher) {
            runProfileWrite(R.string.error_profile_name_taken) {
                freezeProfileRepository.update(profileId, name, packageNames)
                emitToast(UiText.StringResource(R.string.profile_saved))
            }
        }
    }

    fun deleteProfile(profileId: Long) {
        viewModelScope.launch(ioDispatcher) {
            // A delete cannot collide with the unique name index — the only constraint it can
            // trip is the members table's foreign key — so it must not borrow the save path's
            // "that name is already taken", which would be nonsense over a Delete button.
            runProfileWrite(R.string.error_profile_delete_failed) {
                freezeProfileRepository.delete(profileId)
                emitToast(UiText.StringResource(R.string.profile_deleted))
            }
        }
    }

    /**
     * Backstop for the editor's own [com.valhalla.thor.domain.model.profileNameError] check.
     *
     * The editor validates against the names it can see, which is a snapshot; the unique index
     * is the only thing that actually holds. Deleting a profile also cascades, and Room surfaces
     * a foreign-key failure as the same exception type — so both get named rather than crashing
     * the screen through a coroutine no one catches. [constraintMessage] is what separates them:
     * one exception type, two writes that can raise it for unrelated reasons, and only the caller
     * knows which constraint was reachable.
     */
    private suspend fun runProfileWrite(
        @StringRes constraintMessage: Int,
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteConstraintException) {
            Logger.e("FreezeViewModel", "profile write rejected by a constraint", e)
            emitToast(UiText.StringResource(constraintMessage))
        } catch (e: Exception) {
            Logger.e("FreezeViewModel", "profile write failed", e)
            emitToast(UiText.StringResource(R.string.error_format, e.message ?: ""))
        }
    }

    // --- Snackbar from AppInfoSheet (app frozen outside freezer) ---

    /**
     * Deliberately not tier-gated, unlike [toggleManaged]: the prompt this confirms is only raised
     * after a freeze succeeded, so the app is already frozen and the question is whether to track
     * it. Tracking can't re-freeze a blocked app (`freezableCandidates` drops it from FREEZE runs)
     * and is what lets Unfreeze-all reach it.
     */
    fun addToFreezer(packageName: String) {
        viewModelScope.launch(ioDispatcher) {
            freezerRepository.add(packageName)
            emitToast(UiText.StringResource(R.string.added_to_freezer_success))
        }
    }

    fun showFreezerPrompt(packageName: String, appName: String?) {
        viewModelScope.launch {
            _events.send(FreezerEvent.ShowFreezerPrompt(packageName, appName))
        }
    }

    // --- Single-app freeze/unfreeze (called from AppInfoSheet in FreezerScreen) ---

    fun freezeSingleApp(packageName: String, appName: String?, inFreezer: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            // Same BLOCKED rule `toggleManaged` applies to the watchlist, now applied to the
            // freeze itself — and applied inside the use case, so a surface that never learned
            // about AppRiskDialog cannot route around it. The mode goes through because a
            // suspend-mode freeze is still a freeze.
            freezeAppUseCase(packageName, _uiState.value.freezerMode)
                .onSuccess {
                    appShortcuts.refreshAppShortcut(packageName)
                    if (!inFreezer) {
                        _events.send(FreezerEvent.ShowFreezerPrompt(packageName, appName))
                    } else {
                        emitToast(
                            UiText.StringResource(R.string.frozen_success, appName ?: packageName)
                        )
                    }
                }
                .onFailure { e ->
                    // A UiTextException already carries the message to show (the tier refusal)
                    // and has a null `message`, which error_format renders as a bare "Error: ".
                    emitToast(
                        if (e is UiTextException) e.uiText
                        else UiText.StringResource(R.string.error_format, e.message ?: "")
                    )
                }
        }
    }

    fun unfreezeSingleApp(packageName: String, appName: String?) {
        viewModelScope.launch(ioDispatcher) {
            val app = _uiState.value.freezerApps.firstOrNull { it.packageName == packageName }
                ?: _uiState.value.allInstalledApps.firstOrNull { it.packageName == packageName }
            val restoreResult = if (app != null) {
                manageAppUseCase.restoreApp(packageName, app.enabled, app.isSuspended)
            } else {
                // Unknown state (e.g. externally uninstalled): clear both dimensions best-effort.
                manageAppUseCase.forceUnfreeze(packageName)
            }
            restoreResult
                .onSuccess {
                    appShortcuts.refreshAppShortcut(packageName)
                    emitToast(
                        UiText.StringResource(R.string.unfrozen_success, appName ?: packageName)
                    )
                }
                .onFailure { e ->
                    emitToast(UiText.StringResource(R.string.error_format, e.message ?: ""))
                }
        }
    }

    // --- Feedback ---

    private suspend fun emitToast(text: UiText) = _events.send(FreezerEvent.ShowToast(text))

    private fun observePreferences() {
        viewModelScope.launch {
            preferenceRepository.userPreferences.collect { prefs ->
                _uiState.update {
                    it.copy(
                        autoFreezeEnabled = prefs.autoFreezeEnabled,
                        freezerMode = prefs.freezerMode,
                        hasShownDisabledAppsPrompt = prefs.hasShownDisabledAppsPrompt,
                        isGrid = prefs.freezerIsGrid,
                        addFreezerToLauncher = prefs.addFreezerToLauncher
                    )
                }
            }
        }
    }

    fun setAutoFreezeEnabled(enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            preferenceRepository.setAutoFreezeEnabled(enabled)
        }
    }

    fun setFreezerMode(mode: FreezerMode) {
        viewModelScope.launch(ioDispatcher) {
            preferenceRepository.setFreezerMode(mode)
        }
    }

    fun markDisabledAppsPromptShown() {
        viewModelScope.launch(ioDispatcher) {
            preferenceRepository.setHasShownDisabledAppsPrompt(true)
        }
    }

    fun toggleGridMode() {
        viewModelScope.launch(ioDispatcher) {
            preferenceRepository.toggleFreezerIsGrid()
        }
    }

    /**
     * The "you have disabled apps that aren't in the Freezer — import them?" dialog.
     *
     * Not tier-gated here, for the same reason as [addToFreezer]: every app in this list is already
     * frozen, so importing records a freeze that has happened rather than performing one.
     *
     * The *list* is tier-gated, by `importableDisabledApps`, and that is no longer incidental. This
     * doc used to argue the gate could not matter because the candidate filter dropped every system
     * app and `freezeTierOf` opens with `!isSystem -> NORMAL`. Both halves of that are gone: a
     * system app frozen with `pm disable` is exactly what the filter now exists to offer, and a
     * BLOCKED one must not reach the watchlist — `freezableCandidates` would refuse to re-freeze it
     * while `Unfreeze all` would happily enable it, which is a one-way door out of a frozen state
     * the user chose.
     */
    fun addAppsToFreezer(packageNames: List<String>) {
        viewModelScope.launch(ioDispatcher) {
            packageNames.forEach { pkg ->
                freezerRepository.add(pkg)
            }
            emitToast(
                UiText.PluralsResource(
                    R.plurals.added_to_freezer_count_success,
                    packageNames.size
                )
            )
        }
    }

    // --- Launcher shortcuts (gated by the "Add Freezer to launcher" preference) ---

    fun isPinSupported(): Boolean = appShortcuts.isPinSupported()

    fun pinAppToLauncher(app: AppInfo) {
        if (app.isSystem) return // v1: user apps only
        // Grayscale icon decode + binder pin request — keep it off Main to avoid jank.
        viewModelScope.launch(defaultDispatcher) {
            appShortcuts.pinAppShortcut(app.packageName, app.appName ?: app.packageName)
        }
    }

    fun pinAllToLauncher() {
        // Pin sequentially (suspending) off Main: a rapid loop of the fire-and-forget pinAppShortcut
        // would spawn N concurrent bitmap decodes + binder pin requests and risk OOM / overwhelming
        // the shortcut service. A small gap keeps the per-shortcut system prompts orderly too.
        viewModelScope.launch(defaultDispatcher) {
            _uiState.value.freezerApps
                .filter { !it.isSystem }
                .forEach {
                    appShortcuts.pinAppShortcutSuspend(it.packageName, it.appName ?: it.packageName)
                    delay(100)
                }
        }
    }

    fun pinBulkShortcut(freeze: Boolean) {
        // Rasterizes a 216x216 tile bitmap + issues a binder pin request; keep it off Main
        // to avoid click-time jank, matching pinAppToLauncher / pinAllToLauncher.
        viewModelScope.launch(defaultDispatcher) {
            appShortcuts.pinBulkShortcut(
                if (freeze) FreezerShortcutContract.ACTION_FREEZE_ALL
                else FreezerShortcutContract.ACTION_UNFREEZE_ALL
            )
        }
    }
}
