// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import android.database.sqlite.SQLiteConstraintException
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.data.launcher.FreezerShortcutContract
import com.valhalla.thor.domain.model.AppGridDensity
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkOutcome
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.BulkScope
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.NoOpReason
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
import com.valhalla.thor.presentation.launchGuarded
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.asUiText
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

    /**
     * A profile write reached the database. The *only* thing that closes the profile editor.
     *
     * An event rather than a UiState flag because closing is an edge, not a condition: a state
     * field saying "the last save succeeded" would re-close the sheet the next time it is opened,
     * and clearing it would need a second call the screen has no natural place for.
     *
     * [editorSession] names *which* editor issued the write, echoed back from whatever the screen
     * passed in. The editor can be dismissed by hand while its write is still running, so without
     * this the event closes whichever editor happens to be open when the database answers — dismiss
     * a saving editor, open another, and the first write takes the second one's draft down with it.
     * The profile id cannot stand in for the identity, which is worth saying because it is the
     * obvious substitute: two "new profile" editors both carry `NEW_PROFILE_ID`.
     *
     * Opaque here on purpose. The view model never mints or interprets it; it only has to hand
     * back the same value it was given, which is the smallest thing that lets the screen — the only
     * layer that knows what an editor *is* — decide whether this answer is addressed to it.
     */
    data class ProfileSaveSucceeded(val editorSession: Int) : FreezerEvent
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
    /**
     * Carried on the state rather than passed to `FreezerScreen`, because the screen's only call
     * site is `MainScreen` and the two app pickers that also need it are opened from inside the
     * screen — so a parameter would have to be threaded through a host that has no interest in it.
     */
    val gridDensity: AppGridDensity = AppGridDensity.DEFAULT,
    val addFreezerToLauncher: Boolean = false,
    val profiles: List<FreezeProfile> = emptyList(),
    val profileEditorSearchQuery: String = "",
    /**
     * A profile create/update is in flight. Disables the editor's Save button for as long as it
     * runs, so the sheet that is now waiting for its write cannot have a second one issued into it.
     */
    val profileSaveInFlight: Boolean = false,
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
     *
     * [com.valhalla.thor.domain.repository.FreezerRepository.removeAll] looks like the bulk version
     * of this and is not: it deletes rows with no restore at all. That is not an oversight to be
     * "fixed" by routing it through here. Its only caller is the scan-driven prune, whose entire
     * precondition is that the package is *gone* — there is nothing installed to thaw, and an
     * unfreeze against a package the scan just proved absent is a privileged call that can only fail.
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
                    // Pinned shortcuts can only be greyed, never removed — leaving a live one for an
                    // app no longer in the freezer would let the launcher drive a freeze from it.
                    // Before the delete, not after, so that holds even when one of the two throws:
                    // see AppListViewModel.toggleFreezerMembership for the full argument.
                    appShortcuts.disableAppShortcut(pkg)
                    freezerRepository.remove(pkg)
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
            // Guarded for the same reason [removeFromFreezer]'s loop body is: the privileged calls
            // report by returning a Result, but the durable steps around them — the Room write and
            // ShortcutManagerCompat — report by throwing, and :app installs no
            // CoroutineExceptionHandler, so one escaping here takes the process with it rather than
            // the toast. Both branches are covered: `add` writes a row too.
            try {
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
                            emitToast(
                                UiText.StringResource(R.string.error_format, e.message ?: "")
                            )
                        }
                } else {
                    // Restore first, drop the row second — the same ordering [removeFromFreezer]
                    // uses, and for the same reason. This path already reported the failure, but
                    // over a row that was gone by the time the toast appeared: the app stayed
                    // frozen with nothing left to retry from (GH#310).
                    //
                    // forceUnfreeze rather than restoreApp(app.enabled, app.isSuspended), also as
                    // in [removeFromFreezer], and this switch is the shortest route to the trap
                    // that choice avoids: it is drawn from freezerPackageNames, which re-emits the
                    // instant the row lands, while the flags would come from the app lists, which
                    // only move on the next full rescan. So switching an app on and straight back
                    // off restores from a snapshot that still calls it active, plans nothing, and
                    // reports success having made no privileged call at all — the row goes, the
                    // app stays frozen.
                    manageAppUseCase.forceUnfreeze(packageName)
                        .onFailure { e ->
                            emitToast(
                                UiText.StringResource(R.string.error_format, e.message ?: "")
                            )
                            return@launch
                        }
                    // Shortcut first, then the row — the same order as the bulk path above and
                    // AppListViewModel.toggleFreezerMembership.
                    appShortcuts.disableAppShortcut(packageName)
                    freezerRepository.remove(packageName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("FreezeViewModel", "toggling watchlist membership for $packageName", e)
                emitToast(e.asUiText())
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
     *
     * [mode] is null for the row's Freeze button, which means the user's standing choice, and
     * [FreezerMode.SUSPEND] for the menu's explicit Suspend. It is part of the request rather than
     * something resolved here because it is part of the run's *identity*: the runner coalesces on
     * request equality, and a suspend of a profile is not a repeat of a disable of the same one.
     */
    fun runProfile(profileId: Long, op: BulkOp, mode: FreezerMode? = null) {
        viewModelScope.launch {
            val outcome = bulkFreeze
                .launch(BulkRequest(op, BulkScope.Profile(profileId), mode))
                .await()
            emitToast(
                when (outcome) {
                    is BulkOutcome.Completed -> bulkResultMessage(outcome.result)
                    // A no-op. Saying "Froze 0 apps" would read as a failure of the freeze rather
                    // than of the precondition, so name the precondition — and name the *right*
                    // one. "Nothing to do for this profile" is false when the profile is full and
                    // Thor simply has no privilege, and it sends the user looking at the profile
                    // instead of at the thing they can fix.
                    is BulkOutcome.NothingToDo -> UiText.StringResource(
                        when (outcome.reason) {
                            NoOpReason.NO_PRIVILEGE -> R.string.tile_grant_privilege_toast
                            NoOpReason.NO_TARGETS -> R.string.profile_nothing_to_do
                        }
                    )
                    // And this is not that. The run raised — Room, a dead binder — possibly after
                    // freezing part of the profile, so the one thing that must not be said is
                    // that there was nothing to do.
                    is BulkOutcome.Failed -> UiText.StringResource(R.string.bulk_run_failed)
                }
            )
        }
    }

    fun createProfile(editorSession: Int, name: String, packageNames: List<String>) {
        saveProfile(editorSession) { freezeProfileRepository.create(name, packageNames) }
    }

    fun updateProfile(
        editorSession: Int,
        profileId: Long,
        name: String,
        packageNames: List<String>
    ) {
        saveProfile(editorSession) { freezeProfileRepository.update(profileId, name, packageNames) }
    }

    /**
     * Run a profile write, and announce success loudly enough for the editor to close on it.
     *
     * The editor used to be dismissed by its own caller the instant Save was tapped, which meant
     * the two writes that can legitimately be refused — a name the unique index already holds, and
     * a members-table foreign key — reported themselves as a toast floating over a sheet that had
     * already thrown the user's draft away. The write is the thing that decides, so the write is
     * what emits [FreezerEvent.ProfileSaveSucceeded]; a failure now leaves the sheet up with the
     * draft in it, and the Save button the user is already looking at is the retry.
     *
     * The write stays on [viewModelScope] rather than being awaited inside the sheet. The editor's
     * `rememberCoroutineScope()` dies with the composition, so a rotation mid-save would cancel
     * `FreezeProfileDao.updateProfile` part-way through its `@Transaction`.
     *
     * The in-flight check is a backstop, not the guard: Save is disabled from [profileSaveInFlight]
     * for the whole run, and this only covers the frame between the tap and that recomposition.
     * `finally` rather than the success path, so a failed write does not leave the button dead.
     *
     * [editorSession] is carried through untouched and handed back on success — see
     * [FreezerEvent.ProfileSaveSucceeded]. It is what makes "the editor closes on the write" mean
     * *that* editor rather than whichever one is on screen when the write lands.
     */
    private fun saveProfile(editorSession: Int, write: suspend () -> Unit) {
        if (_uiState.value.profileSaveInFlight) return
        _uiState.update { it.copy(profileSaveInFlight = true) }
        viewModelScope.launch(ioDispatcher) {
            try {
                val saved = runProfileWrite(R.string.error_profile_name_taken) { write() }
                if (saved) {
                    emitToast(UiText.StringResource(R.string.profile_saved))
                    _events.send(FreezerEvent.ProfileSaveSucceeded(editorSession))
                }
            } finally {
                _uiState.update { it.copy(profileSaveInFlight = false) }
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
     *
     * Returns whether the write landed. A caller that only reports the outcome can ignore it; a
     * caller that has UI riding on it — [saveProfile], which dismisses a sheet — cannot, because
     * "the toast was shown" and "the row exists" are otherwise indistinguishable from out here.
     */
    private suspend fun runProfileWrite(
        @StringRes constraintMessage: Int,
        block: suspend () -> Unit
    ): Boolean {
        try {
            block()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteConstraintException) {
            Logger.e("FreezeViewModel", "profile write rejected by a constraint", e)
            emitToast(UiText.StringResource(constraintMessage))
        } catch (e: Exception) {
            Logger.e("FreezeViewModel", "profile write failed", e)
            emitToast(UiText.StringResource(R.string.error_format, e.message ?: ""))
        }
        return false
    }

    // --- Snackbar from AppInfoSheet (app frozen outside freezer) ---

    /**
     * Deliberately not tier-gated, unlike [toggleManaged]: the prompt this confirms is only raised
     * after a freeze succeeded, so the app is already frozen and the question is whether to track
     * it. Tracking can't re-freeze a blocked app (`freezableCandidates` drops it from FREEZE runs)
     * and is what lets Unfreeze-all reach it.
     */
    fun addToFreezer(packageName: String) {
        // [launchGuarded], not the [runProfileWrite] helper thirty lines up, even though both catch
        // the same class of Room throw. That helper is about *profile* writes: its reason to exist
        // is the `@StringRes constraintMessage` naming which constraint the write could trip, and a
        // watchlist insert can trip neither of the two it was written for — `FreezerDao.insert` is
        // `OnConflictStrategy.IGNORE`, so re-adding a tracked app is a no-op rather than a
        // SQLiteConstraintException, and its "that name is already taken" branch is unreachable
        // nonsense here. It is also `suspend`, so it cannot close the hole that is actually open:
        // the crash is the unguarded *coroutine*, and only something owning the `launch` catches
        // what escapes it.
        //
        // `context = ioDispatcher` because adopting the guard must not quietly move the Room write
        // onto Main — nothing would fail if it did, since Room's suspend DAO functions dispatch
        // internally.
        launchGuarded(
            context = ioDispatcher,
            onFailure = { e ->
                // The freeze already happened: this prompt is only raised after one succeeded, per
                // the doc above. What failed is Thor's record of it, so the toast must not say the
                // freeze did — the app is frozen and simply untracked, which means `Unfreeze all`
                // will not reach it until the row is added again. No existing string says that
                // sentence and a new one is eight locales of debt, so report the throw plainly
                // rather than invent a claim about the action.
                Logger.e("FreezeViewModel", "adding $packageName to the freezer failed", e)
                tryEmitToast(e.asUiText())
            }
        ) {
            freezerRepository.add(packageName)
            // After the write, never before: "Added to Freezer" emitted first would be the only
            // thing the user is told about a row that then failed to land.
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
                    // The tier refusal arrives here as a UiTextException, which carries its message
                    // in `uiText` and leaves `message` null — see [asUiText].
                    emitToast(e.asUiText())
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

    /**
     * [emitToast] from somewhere that cannot suspend.
     *
     * [launchGuarded]'s `onFailure` is declared `(Throwable) -> Unit`, not a suspending function, so
     * `send` is simply not callable there — the handler does run inside the guard's `catch`, and so
     * inside the coroutine, but a plain lambda offers no suspension point to use it from. The channel
     * is `Channel.BUFFERED`, so `trySend` only refuses once 64 events are queued with nothing
     * collecting them, and a dropped error toast in that state is a far smaller loss than the process
     * death this whole guard exists to prevent.
     *
     * The refusal is logged rather than dropped on the floor, because this view model is created
     * eagerly by `MainScreen` and so can accumulate events long before the Freezer tab is ever
     * opened. Worth being exact about what that buys, though: `Logger` gates every level on
     * `Logger.isDebug`, which `ThorApplication` sets to `BuildConfig.DEBUG ||
     * BuildConfig.PRIVILEGE_TRACE`, so on the builds users run the line is not emitted either. The
     * log is a bug-report aid for a `PRIVILEGE_TRACE` build, not a promise that the message survives
     * in release. Making a refused toast visible to a user with nothing collecting the channel would
     * need a different mechanism than a toast, and 64 queued undelivered events is not a state worth
     * one.
     */
    private fun tryEmitToast(text: UiText) {
        val delivered = _events.trySend(FreezerEvent.ShowToast(text)).isSuccess
        if (!delivered) {
            Logger.e("FreezeViewModel", "dropped a freezer toast: the event channel refused it")
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferenceRepository.userPreferences.collect { prefs ->
                _uiState.update {
                    it.copy(
                        autoFreezeEnabled = prefs.autoFreezeEnabled,
                        freezerMode = prefs.freezerMode,
                        hasShownDisabledAppsPrompt = prefs.hasShownDisabledAppsPrompt,
                        isGrid = prefs.freezerIsGrid,
                        gridDensity = prefs.appGridDensity,
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
        launchGuarded(
            context = ioDispatcher,
            // The per-item catch below is what stops one package abandoning the rest, so this outer
            // net only ever sees a throw from *outside* the loop — the report itself. It still says
            // something, because an import that reports nothing at all is indistinguishable from an
            // import that did nothing.
            onFailure = { e ->
                Logger.e("FreezeViewModel", "reporting the disabled-apps import failed", e)
                tryEmitToast(e.asUiText())
            }
        ) {
            var succeeded = 0
            val failures = mutableListOf<Throwable>()
            packageNames.forEach { pkg ->
                // Inside the loop, per package, exactly as [removeFromFreezer]'s guard is: N inserts
                // are N chances for a full or failing disk to throw, and a guard around the whole
                // loop would let the first one lose every package after it — the user's entire
                // import gone to one row. [launchGuarded] cannot serve as this per-item guard: it
                // starts a coroutine, which would turn an ordered walk into N concurrent writes and
                // have the report below read its counts before any of them landed.
                try {
                    freezerRepository.add(pkg)
                    succeeded++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e("FreezeViewModel", "importing $pkg into the freezer failed", e)
                    failures += e
                }
            }
            // `succeeded`, not `packageNames.size`: the old count was the size of the request, so it
            // announced "Added 12 apps to Freezer" over an import where not one row had landed.
            //
            // A partial import reports *both* facts, in two toasts, rather than collapsing to the
            // error. There is no "Added x/y (z failed)" string to mirror [removeFromFreezer]'s third
            // branch — the only string of that shape says *Removed*, which over an import would tell
            // the user the opposite of what happened, and minting one costs eight locales — but
            // suppressing the count entirely was the worse half of that trade: 11 of 12 rows landing
            // read as a total failure, over a screen that was about to show all 11. So the count goes
            // out when there is one, and the throw goes out after it, which is the same
            // two-facts-in-order convention `AppInfoDetailsViewModel`'s guards use for the same
            // succeeded-then-threw shape.
            if (succeeded > 0) {
                emitToast(UiText.PluralsResource(R.plurals.added_to_freezer_count_success, succeeded))
            }
            if (failures.isNotEmpty()) {
                // Say what the database said. Every app in this list was already disabled before the
                // import — that is the dialog's whole precondition — so a failure here costs
                // watchlist rows, not the freeze.
                emitToast(UiText.StringResource(R.string.error_format, failures.first().message ?: ""))
            }
        }
    }

    // --- Launcher shortcuts (gated by the "Add Freezer to launcher" preference) ---

    fun isPinSupported(): Boolean = appShortcuts.isPinSupported()

    /**
     * All three pin entry points below are guarded for the same reason the watchlist writes above
     * are, and it is the same defect rather than a related one: `ShortcutManagerCompat` reports by
     * throwing — `IllegalStateException` from a background caller, `IllegalArgumentException` on a
     * malformed id, and whatever the launcher's own binder raises — and `:app` installs no
     * `CoroutineExceptionHandler`, so a bare `launch` around one of these made a failed pin request
     * process death.
     *
     * Which throws *reach* this guard is a property of the port method, not of the guard, and the
     * three below are deliberately the three that throw into their caller:
     * [AppShortcutController.pinAppShortcutSuspend] suspends, and `pinBulkShortcut` /
     * `disableAppShortcut` run on the caller's thread. The two fire-and-forget members —
     * `pinAppShortcut` and `refreshAppShortcut` — hand their body to `FreezerShortcutManager`'s own
     * `SupervisorJob` scope and return, so no caller-side guard can ever see their failure; they are
     * caught in `FreezerShortcutManager.launchSafely` instead, which is also the only place that can
     * cover their non-view-model callers (`AutoFreezeManager`, `FreezerLaunchActivity`,
     * `ThorApplication`). [pinAppToLauncher] therefore calls the *suspending* pin rather than the
     * fire-and-forget one, so a refusal the user is waiting on still becomes a message.
     *
     * `context = defaultDispatcher`, never the [ioDispatcher] the watchlist sites use: these decode
     * and rasterize bitmaps, which is why they were on Default to begin with, and adopting the guard
     * must not quietly re-file CPU work onto the IO pool any more than it may move Room onto Main.
     *
     * Reported through [tryEmitToast] with `error_format` rather than silently: a pin the user asked
     * for and did not get is worth a line, and inventing a "couldn't pin that shortcut" string costs
     * eight locales for a failure this rare.
     */
    private fun launchPin(what: String, block: suspend () -> Unit) {
        launchGuarded(
            context = defaultDispatcher,
            onFailure = { e ->
                Logger.e("FreezeViewModel", "pinning $what to the launcher failed", e)
                tryEmitToast(e.asUiText())
            }
        ) { block() }
    }

    fun pinAppToLauncher(app: AppInfo) {
        if (app.isSystem) return // v1: user apps only
        // Grayscale icon decode + binder pin request — keep it off Main to avoid jank.
        //
        // The suspending pin, not the fire-and-forget one: `launchPin` is already off Main, which is
        // the only thing `pinAppShortcut` buys, and it swallows the outcome into the manager's scope
        // where this guard cannot see it. Same call the bulk loop below makes, for the same reason.
        launchPin(app.packageName) {
            appShortcuts.pinAppShortcutSuspend(app.packageName, app.appName ?: app.packageName)
        }
    }

    fun pinAllToLauncher() {
        // Pin sequentially (suspending) off Main: a rapid loop of the fire-and-forget pinAppShortcut
        // would spawn N concurrent bitmap decodes + binder pin requests and risk OOM / overwhelming
        // the shortcut service. A small gap keeps the per-shortcut system prompts orderly too.
        launchPin("every freezer app") {
            var refused: Throwable? = null
            _uiState.value.freezerApps
                .filter { !it.isSystem }
                .forEach {
                    // Per app, so one launcher refusing a single shortcut does not abandon the rest
                    // of the run — the same per-item isolation [addAppsToFreezer]'s loop has, and for
                    // the same reason. The outer guard then only ever sees a throw from outside the
                    // loop.
                    try {
                        appShortcuts.pinAppShortcutSuspend(
                            it.packageName,
                            it.appName ?: it.packageName
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e("FreezeViewModel", "pinning ${it.packageName} failed", e)
                        // Kept for one line after the loop. Isolating the failure must not also hide
                        // it: `Logger` is gated on `Logger.isDebug`, false in every build users run,
                        // so catching per app and only logging meant a run in which the launcher
                        // refused *every* shortcut reported nothing at all — no toast, no logcat —
                        // against a [launchPin] doc that promises a pin the user asked for and did
                        // not get is worth a line. First throw only, and no count: they are the same
                        // refusal repeated, N lines would bury each other, and a "%d shortcuts could
                        // not be pinned" sentence needs a string that does not exist in any of the
                        // eight locales.
                        if (refused == null) refused = e
                    }
                    delay(100)
                }
            // Rethrown rather than reported here, so the one place that formats a pin failure stays
            // [launchPin]'s `onFailure` instead of becoming two places that must agree.
            refused?.let { throw it }
        }
    }

    fun pinBulkShortcut(freeze: Boolean) {
        // Rasterizes a 216x216 tile bitmap + issues a binder pin request; keep it off Main
        // to avoid click-time jank, matching pinAppToLauncher / pinAllToLauncher.
        launchPin(if (freeze) "Freeze all" else "Unfreeze all") {
            appShortcuts.pinBulkShortcut(
                if (freeze) FreezerShortcutContract.ACTION_FREEZE_ALL
                else FreezerShortcutContract.ACTION_UNFREEZE_ALL
            )
        }
    }
}
