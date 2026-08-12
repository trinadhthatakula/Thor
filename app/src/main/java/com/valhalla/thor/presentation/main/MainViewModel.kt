// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
import com.valhalla.thor.data.backup.BackupRunner
import com.valhalla.thor.data.backup.job.JobSheetTarget
import com.valhalla.thor.data.backup.job.JobSheetTargets
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.Installers
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.fixStoreCandidates
import com.valhalla.thor.domain.model.freezeTier
import com.valhalla.thor.domain.model.isActive
import com.valhalla.thor.domain.model.isFrozen
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.usecase.BackupRejection
import com.valhalla.thor.domain.usecase.BackupRunResult
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.domain.usecase.ShareAppUseCase
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.UsageAccessGate
import com.valhalla.thor.presentation.home.AppDestinations
import com.valhalla.thor.util.AppLocale
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.UiTextException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

/**
 * Side Effects: One-time events that the UI must handle (Navigation, Intents).
 */
sealed interface MainSideEffect {
    data class LaunchApp(val packageName: String) : MainSideEffect
    data class OpenAppSettings(val packageName: String) : MainSideEffect
    /** [mime] describes the container that was actually built, not the app it came from. */
    data class ShareApp(val uri: android.net.Uri, val mime: String) : MainSideEffect
    data class ShareApps(val uris: List<android.net.Uri>) : MainSideEffect
    data class NormalUninstall(val packageName: String) : MainSideEffect

    /** Transient user feedback (Toast). Consumed once by the screen, never re-shown on recomposition. */
    data class Message(val text: UiText) : MainSideEffect
}

/**
 * State for the Terminal Logger Dialog.
 */
data class LoggerState(
    val isVisible: Boolean = false,
    val title: UiText = UiText.DynamicString(""),
    val logs: List<UiText> = emptyList(),
    val isComplete: Boolean = false,
    /**
     * Whether this run can be stopped part-way. True only for the per-app batches, where stopping
     * leaves a coherent result — some apps done, the rest untouched. A single shell command has no
     * such halfway point.
     */
    val canStop: Boolean = false,
    /**
     * A stop has been asked for and the app in flight is being allowed to finish. Killing it
     * mid-`pm install` is what leaves a package half-written, so the button reports "stopping"
     * rather than pretending it was instant.
     */
    val isStopping: Boolean = false
)

/**
 * Compact count-only progress for bulk freeze / unfreeze. Unlike [LoggerState] it
 * never lists app names — just a live `processed / total` count — and auto-dismisses
 * shortly after a fully-successful run.
 */
data class FreezeLoggerState(
    val isVisible: Boolean = false,
    val isFreeze: Boolean = true,
    val total: Int = 0,
    val processed: Int = 0,
    val failed: Int = 0,
    val isComplete: Boolean = false
)

/**
 * Live view of the multi-app export owned by [BackupRunner]; null when nothing is exporting.
 *
 * It looks like [FreezeLoggerState] but is not its sibling: that one describes work this ViewModel
 * is doing, so it has an `isComplete` flag and a dismiss call. This one only *watches* a run that
 * outlives the ViewModel — the run ending is the dismissal, and the outcome arrives separately as
 * a [MainSideEffect.Message], so there is nothing here to complete or dismiss.
 */
data class ExportProgressState(
    val completed: Int,
    val total: Int,
    /** The app that started most recently. Data, not copy — render it verbatim or not at all. */
    val currentLabel: String?
) {
    /** `Exporting 3 of 12`, left as a [UiText] so this stays free of a Context. */
    val status: UiText
        get() = UiText.StringResource(R.string.export_bulk_progress, completed, total)

    /**
     * Bar fill, 0f..1f. Guarded because a zero total would divide by zero, and an empty selection
     * is rejected rather than run — so this defends against a state that should not exist rather
     * than one that routinely does.
     */
    val fraction: Float
        get() = if (total <= 0) 0f else completed.toFloat() / total
}

/**
 * Main UI State holding global feedback.
 */
/**
 * The Fix Store picker: the apps the action would touch, and which of them are still ticked.
 *
 * Everything starts ticked. The accident being prevented is "I did not know what it would touch",
 * not "I did not mean to tap Confirm" — so showing the list is the fix, and making someone tick
 * forty rows would punish the case the feature is for.
 *
 * [selected] holds package names rather than [AppInfo]s so a tick survives the list being rebuilt.
 */
data class FixStoreSelection(
    val candidates: List<AppInfo> = emptyList(),
    val selected: Set<String> = emptySet()
) {
    val selectedApps: List<AppInfo> get() = candidates.filter { it.packageName in selected }
}

/**
 * The whole-device cache clear, from the tile tap to the moment its result sheet goes away.
 * `null` in [MainUiState.cacheClear] means neither is happening.
 *
 * Modelled as state rather than as two `remember`ed booleans in `HomeScreen` because the operation
 * is not the Home screen's: it clears every app on the device, it outlives a tab switch, and the
 * result is a number the user asked for. [Confirming] is in here for the same reason — the
 * confirmation is not a formality, it is the only place the user is told that *system* apps are
 * included, so it belongs where the action it guards does.
 */
sealed interface CacheClearState {
    /** Waiting on the confirmation the tile must not skip. */
    data object Confirming : CacheClearState

    data object Running : CacheClearState

    /**
     * [freedBytes] is `null` when the clear succeeded but Thor could not measure it — no usage
     * access, or an app that refilled its cache between the two readings. A screen must render that
     * as "cache cleared" with no number, never as "0 B freed".
     *
     * [hasUsageAccess] separates those two causes, and exists because the sentence they deserve is
     * not the same one. "Grant usage access to see the figure" is advice for the first and an
     * insult to the second: the user already granted it, so the only actionable thing on screen is
     * an instruction to do what they have done. Sampled when the result lands rather than when it
     * is drawn, because it describes why *this* measurement failed.
     */
    data class Done(val freedBytes: Long?, val hasUsageAccess: Boolean) : CacheClearState
}

/**
 * The restore sheet, or null when it is closed.
 *
 * A nullable field holding a type with a nullable field, on purpose: the *outer* null means the sheet
 * is not open, and [uriString] being null means it is open with no archive chosen yet, so the sheet
 * shows its file picker. Collapsing the two would make "open the restore sheet" and "open the restore
 * sheet on this file" indistinguishable, which is the difference between the Settings row and a
 * notification tap.
 */
data class RestoreSheetState(val uriString: String? = null)

/**
 * The backup sheet hosted by `MainScreen`, or null when it is closed.
 *
 * Only ever opened by a notification tap. The in-app route to a backup is the sheet the app-info
 * surfaces host themselves, which owns its own visibility — so this is a *second* host, and the two
 * can be composed at once if the user backgrounds Thor with a backup sheet open and then taps the
 * notification. Both watch the same job through `runningJobFor`, so the stacked pair shows the same
 * progress twice rather than disagreeing; dismissing the top one leaves the original underneath.
 * Lifting all backup-sheet hosting up here would fix the cosmetics at the cost of threading a
 * `MainViewModel` call through `onAppAction` on both app-info surfaces, which is not worth it for a
 * duplicate that requires leaving the sheet open on the way to the shade.
 *
 * [appLabel] must be the app's real name: `AppBackupViewModel.start` writes what it is handed straight
 * into its state and never resolves it.
 */
data class BackupSheetState(val packageName: String, val appLabel: String)

data class MainUiState(
    val loggerState: LoggerState = LoggerState(), // For persistent Logs
    val fixStoreSelection: FixStoreSelection? = null, // Fix Store picker, null when closed
    val freezeLoggerState: FreezeLoggerState = FreezeLoggerState(), // Compact freeze/unfreeze progress
    val exportProgress: ExportProgressState? = null, // Multi-app export, null when idle
    val cacheClear: CacheClearState? = null, // Whole-device cache clear, null when idle
    val restoreSheet: RestoreSheetState? = null, // Archive restore sheet, null when closed
    val backupSheet: BackupSheetState? = null, // Archive backup sheet reopened from a notification
    val selectedDestination: AppDestinations = AppDestinations.HOME, // For Bottom Nav
    val hasShownSupportDeveloperPrompt: Boolean = true,
    val showSupportDeveloperPrompt: Boolean = false,
    val prefs: UserPreferences = UserPreferences()
)

@KoinViewModel
class MainViewModel(
    private val manageAppUseCase: ManageAppUseCase,
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val shareAppUseCase: ShareAppUseCase,
    private val preferenceRepository: PreferenceRepository,
    private val freezerRepository: FreezerRepository,
    private val backupRunner: BackupRunner,
    // Only ever asked `isGranted`, and only to explain a measurement that came back empty. The
    // interface rather than UsageAccessManager because the concrete class reaches for a Context for
    // its Settings deep-link, which would put an Android type in this ViewModel's constructor and
    // take it off the JVM test classpath.
    private val usageAccessGate: UsageAccessGate,
    // Where a tap on a running job's notification arrives. A plain in-memory holder, so it costs
    // nothing to observe and stays on the JVM test classpath.
    private val jobSheetTargets: JobSheetTargets,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        MainUiState()
    )

    private var pendingSupportPrompt = false

    /** Whether [openRestoreSheetForLaunchUri] has already fired for this ViewModel. See it for why here. */
    private var launchRestoreUriConsumed = false

    // Declared *above* `init`, and it has to stay there.
    //
    // `viewModelScope` runs on `Dispatchers.Main.immediate`, so a collector launched from `init`
    // starts executing inline rather than on a later main-loop turn — and `completions` replays,
    // so a view model built after a run finished receives that outcome *during* `init`. Every
    // property the collector touches is therefore initialised or null at that moment, in plain
    // declaration order. Moved back below `init`, this one is null exactly on the replay path:
    // reopen Thor after an export that finished with no UI attached and the send NPEs inside
    // `viewModelScope`, i.e. on the main thread, with no handler.
    private val _effect = Channel<MainSideEffect>()
    val effect = _effect.receiveAsFlow()

    init {
        observePreferences()
        observeBackupRun()
        observeJobSheetRequests()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferenceRepository.userPreferences.collect { prefs ->
                _uiState.update {
                    it.copy(
                        hasShownSupportDeveloperPrompt = prefs.hasShownSupportDeveloperPrompt,
                        prefs = prefs
                    )
                }
            }
        }
    }

    /**
     * Mirror [BackupRunner]'s two flows — progress into [MainUiState], outcome into a one-shot
     * message.
     *
     * Collected from `init`, not from the Backup branch, because a run outlives the ViewModel that
     * started it: reopen Thor mid-export and *this* instance never called `start`, so a collector
     * hung off the start call would show nothing. `progress` is a StateFlow whose null means idle,
     * so attaching to a finished run costs nothing.
     */
    private fun observeBackupRun() {
        viewModelScope.launch {
            backupRunner.progress.collect { progress ->
                _uiState.update { state ->
                    state.copy(
                        exportProgress = progress?.let {
                            ExportProgressState(it.completed, it.total, it.current)
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            backupRunner.completions.collect { result ->
                val report = exportReport(result)
                var shown = false
                try {
                    for (message in report.messages) {
                        _effect.send(MainSideEffect.Message(message))
                        shown = true
                    }
                    if (report.asksForSupport) triggerSupportPromptIfNeeded()
                } finally {
                    // Acknowledge once *any* of it has reached the user, and only then.
                    //
                    // Not acknowledging is what lets a run that finished with no UI attached report
                    // when one arrives — so consuming an outcome nobody saw would lose it silently.
                    // But `_effect` is a rendezvous channel and a finished run whose manifest
                    // failed sends *twice*, so a ViewModel cleared between the two sends would
                    // leave the outcome unconsumed and the next instance would replay the whole
                    // thing: the "Exported 8 of 12" already read, support prompt included.
                    //
                    // In `finally` and non-suspending, so it still runs while this coroutine is
                    // being cancelled — which is exactly the case it exists for.
                    if (shown) backupRunner.consumeCompletion()
                }
            }
        }
    }

    /**
     * Reopen the sheet a job notification was tapped on.
     *
     * From `init` for the same reason as [observeBackupRun]: the tap is what brings Thor forward, so
     * this instance is routinely *younger* than the request. `JobSheetTargets` conflates rather than
     * drops, so a request made before the ViewModel existed is still waiting here — which is the whole
     * point on the app-lock path, where the trampoline runs minutes before `MainScreen` composes.
     *
     * Touches only [_uiState] and the constructor parameter, both live before `init` runs. See the
     * comment on `_effect` for why that sentence has to be checked and not assumed.
     */
    private fun observeJobSheetRequests() {
        viewModelScope.launch {
            jobSheetTargets.requests.collect { target ->
                _uiState.update { state ->
                    when (target) {
                        is JobSheetTarget.Backup -> state.copy(
                            backupSheet = BackupSheetState(target.packageName, target.appLabel)
                        )
                        is JobSheetTarget.Restore -> state.copy(
                            restoreSheet = RestoreSheetState(target.uriString)
                        )
                    }
                }
            }
        }
    }

    /**
     * Open the restore sheet, on [uriString] if there is one and on its file picker if not.
     *
     * The Settings row and `HomeActivity`'s incoming-`.thorbak` intent both land here. It is not a
     * navigation call any more: the sheet is hosted over whatever tab is showing, so nothing switches
     * section on the way in.
     */
    fun openRestoreSheet(uriString: String? = null) {
        _uiState.update { it.copy(restoreSheet = RestoreSheetState(uriString)) }
    }

    /**
     * Open the restore sheet on the `.thorbak` this launch was opened on, at most once.
     *
     * The latch belongs here rather than in a `rememberSaveable` in `MainScreen`, and that is a
     * correctness point, not tidiness: it has to have **the same lifetime as the sheet state it
     * guards**. `restoreSheet` lives on this ViewModel, which survives a rotation and dies with the
     * process; a `rememberSaveable` latch survives *both*. So the pair disagreed exactly once — kill the
     * process while it is backgrounded, return through Recents, and the activity is recreated with the
     * same VIEW intent and a still-valid task-scoped read grant, but the saved latch said "already
     * handled" while the fresh ViewModel had no sheet. The archive the user opened Thor on was dropped
     * with nothing on screen to say so. Sharing one lifetime makes the two answers agree by
     * construction: rotation keeps both, process death clears both and the sheet reopens.
     *
     * Being on the ViewModel is also what makes the no-reopen-after-dismiss half testable, which the
     * `rememberSaveable` never was.
     */
    fun openRestoreSheetForLaunchUri(uriString: String) {
        if (launchRestoreUriConsumed) return
        launchRestoreUriConsumed = true
        openRestoreSheet(uriString)
    }

    fun dismissRestoreSheet() {
        _uiState.update { it.copy(restoreSheet = null) }
    }

    fun dismissBackupSheet() {
        _uiState.update { it.copy(backupSheet = null) }
    }

    fun markSupportDeveloperPromptShown() {
        viewModelScope.launch(ioDispatcher) {
            preferenceRepository.setHasShownSupportDeveloperPrompt(true)
        }
        _uiState.update {
            it.copy(
                showSupportDeveloperPrompt = false,
                hasShownSupportDeveloperPrompt = true
            )
        }
    }

    fun dismissSupportDeveloperPrompt() {
        _uiState.update { it.copy(showSupportDeveloperPrompt = false) }
    }

    private fun triggerSupportPromptIfNeeded() {
        if (!_uiState.value.hasShownSupportDeveloperPrompt) {
            if (_uiState.value.loggerState.isVisible) {
                pendingSupportPrompt = true
            } else {
                _uiState.update { it.copy(showSupportDeveloperPrompt = true) }
            }
        }
    }

    // --- State Management Helpers ---

    fun dismissLogger() {
        _uiState.update { it.copy(loggerState = LoggerState(isVisible = false)) }
        if (pendingSupportPrompt) {
            pendingSupportPrompt = false
            triggerSupportPromptIfNeeded()
        }
    }

    fun onDestinationSelected(destination: AppDestinations) {
        _uiState.update { it.copy(selectedDestination = destination) }
    }

    // --- Fix Store picker ---

    fun toggleFixStoreTarget(packageName: String) {
        _uiState.update { state ->
            val picker = state.fixStoreSelection ?: return@update state
            val selected = if (packageName in picker.selected) {
                picker.selected - packageName
            } else {
                picker.selected + packageName
            }
            state.copy(fixStoreSelection = picker.copy(selected = selected))
        }
    }

    fun setAllFixStoreTargets(selectAll: Boolean) {
        _uiState.update { state ->
            val picker = state.fixStoreSelection ?: return@update state
            val selected = if (selectAll) {
                picker.candidates.mapTo(mutableSetOf()) { it.packageName }
            } else {
                emptySet()
            }
            state.copy(fixStoreSelection = picker.copy(selected = selected))
        }
    }

    fun dismissFixStorePicker() {
        _uiState.update { it.copy(fixStoreSelection = null) }
    }

    /**
     * Runs Fix Store against whatever is still ticked, and closes the picker.
     *
     * An empty selection closes the picker and does nothing rather than starting a batch of zero —
     * the confirm button is disabled at that point, so reaching here means the state moved out from
     * under the click.
     */
    fun confirmFixStore() {
        val targets = _uiState.value.fixStoreSelection?.selectedApps.orEmpty()
        dismissFixStorePicker()
        if (targets.isNotEmpty()) {
            onMultiAppAction(MultiAppAction.ReInstall(targets))
        }
    }

    /**
     * Asks the batch in flight to stop once the current app finishes.
     *
     * Written from the main thread and read from [ioDispatcher], hence `@Volatile`. Cancelling the
     * job instead would abandon a `pm install` mid-write; this lets the app in flight land and then
     * stops handing out more work.
     */
    @Volatile
    private var stopRequested = false

    fun requestStopBatch() {
        val logger = _uiState.value.loggerState
        if (!logger.isVisible || logger.isComplete || !logger.canStop) return
        stopRequested = true
        _uiState.update { it.copy(loggerState = it.loggerState.copy(isStopping = true)) }
    }

    private fun startLogger(title: UiText, canStop: Boolean = false) {
        stopRequested = false
        _uiState.update {
            it.copy(
                loggerState = LoggerState(
                    isVisible = true,
                    title = title,
                    logs = listOf(UiText.StringResource(R.string.log_initializing)),
                    canStop = canStop
                )
            )
        }
    }

    private fun addLog(message: UiText) {
        _uiState.update { state ->
            val newLogs = state.loggerState.logs + message
            state.copy(loggerState = state.loggerState.copy(logs = newLogs))
        }
    }

    private fun finishLogger() {
        addLog(UiText.StringResource(R.string.log_op_complete))
        _uiState.update { state ->
            state.copy(
                loggerState = state.loggerState.copy(isComplete = true, isStopping = false)
            )
        }
    }

    /**
     * Opens the confirmation for the whole-device cache clear. The tile calls this, never
     * [confirmClearAllCaches] — see [CacheClearState.Confirming].
     */
    fun requestClearAllCaches() {
        _uiState.update { it.copy(cacheClear = CacheClearState.Confirming) }
    }

    /**
     * Clears every app's cache on the primary volume, system apps included.
     *
     * This used to be `clearAllCache(type)`: load every app of one [AppListType], drop Thor and the
     * Play Store, then walk the list clearing one package at a time behind the batch logger. Every
     * part of that is now wrong.
     *
     * The per-package loop could not work outside Root. `INTERNAL_DELETE_CACHE_FILES` is
     * signature-level, so under Shizuku `PackageManagerService` logged that it was silently ignoring
     * each call — hundreds of packages, fifteen seconds of observer timeout each, nothing deleted.
     * The operation that *does* work is `pm trim-caches`, and it is not a loop: PMS picks its own
     * victims by LRU across the whole volume.
     *
     * Which is why the USER/SYSTEM choice is gone rather than moved. It could not be honoured — a
     * trim clears both — and offering it would have been a lie in the one place the user is deciding
     * whether to touch system apps. The `safeList` filter goes with it for the same reason: PMS
     * decides, so excluding Thor and the Play Store was never in Thor's gift. Thor's own cache being
     * included is the correct behaviour anyway; it was only ever excluded because clearing it
     * mid-batch was visible.
     */
    fun confirmClearAllCaches() {
        // Synchronous, and deliberately *outside* the coroutine. Two taps landing in the same frame
        // each queue a launch, and a guard inside the coroutine can only see whatever the other one
        // left behind — which by the time it runs may already be `Done`, and `Done` is not
        // `Running`, so the second trim would start anyway. Flipping the state here, before either
        // coroutine exists, is what makes the second tap a no-op. Safe as a check-then-set because
        // both callers are Compose click handlers on the main thread.
        //
        // Requiring `Confirming` rather than "not Running" also means nothing can start a trim
        // without the sheet having asked first.
        if (_uiState.value.cacheClear != CacheClearState.Confirming) return
        _uiState.update { it.copy(cacheClear = CacheClearState.Running) }
        viewModelScope.launch {
            manageAppUseCase.clearAllCaches()
                .onSuccess { freed ->
                    // Read only when the number is missing. `isGranted` is a local AppOps lookup,
                    // but asking it on the happy path would still be asking a question whose answer
                    // is already implied — a byte count arrived, so the op is held.
                    val hasUsageAccess = freed != null || usageAccessGate.isGranted()
                    _uiState.update {
                        it.copy(cacheClear = CacheClearState.Done(freed, hasUsageAccess))
                    }
                }
                .onFailure { e ->
                    Logger.e("MainViewModel", "clearAllCaches failed", e)
                    _uiState.update { it.copy(cacheClear = null) }
                    val errorText = if (e is UiTextException) e.uiText else UiText.DynamicString(e.message ?: "")
                    _effect.send(MainSideEffect.Message(UiText.StringResource(R.string.error_format, errorText)))
                }
        }
    }

    /**
     * Closes the confirmation or the result sheet, whichever is open.
     *
     * The support prompt is triggered from here rather than at the moment the clear succeeds: two
     * bottom sheets racing each other is not a thing to ask a user to read.
     */
    fun dismissCacheClear() {
        val wasDone = _uiState.value.cacheClear is CacheClearState.Done
        _uiState.update { it.copy(cacheClear = null) }
        if (wasDone) viewModelScope.launch { triggerSupportPromptIfNeeded() }
    }

    // --- Single App Action Handler ---

    fun onAppAction(action: AppClickAction) {
        viewModelScope.launch {
            when (action) {
                // 1. SMART LAUNCH
                is AppClickAction.Launch -> {
                    val app = action.appInfo
                    // Smart launch: a frozen app is disabled OR suspended. Restore it (enable
                    // and/or unsuspend) before launching — otherwise a suspended app just opens the
                    // system "app paused" dialog instead of the app.
                    if (app.isFrozen) {
                        _effect.send(
                            MainSideEffect.Message(
                                UiText.StringResource(
                                    R.string.unfreezing_app,
                                    app.appName ?: app.packageName
                                )
                            )
                        )

                        val result = manageAppUseCase.restoreApp(app.packageName, app.enabled, app.isSuspended)
                        if (result.isSuccess) {
                            _effect.send(MainSideEffect.LaunchApp(app.packageName))
                        } else {
                            _effect.send(
                                MainSideEffect.Message(
                                    UiText.StringResource(
                                        R.string.error_format,
                                        result.exceptionOrNull()?.message ?: ""
                                    )
                                )
                            )
                        }
                    } else {
                        _effect.send(MainSideEffect.LaunchApp(app.packageName))
                    }
                }

                // 2. SETTINGS
                is AppClickAction.AppInfoSettings -> {
                    _effect.send(MainSideEffect.OpenAppSettings(action.appInfo.packageName))
                }

                // 3. SHARE (Heavy I/O -> Use Logger)
                is AppClickAction.Share -> {
                    startLogger(UiText.StringResource(R.string.log_sharing_app, action.appInfo.appName ?: ""))
                    addLog(UiText.StringResource(R.string.log_preparing_files))

                    val result = shareAppUseCase(action.appInfo)

                    if (result.isSuccess) {
                        addLog(UiText.StringResource(R.string.log_files_ready))
                        dismissLogger()
                        val uri = result.getOrThrow()
                        _effect.send(MainSideEffect.ShareApp(uri, mimeForBundle(uri)))
                    } else {
                        addLog(UiText.StringResource(R.string.log_error, result.exceptionOrNull()?.message ?: ""))
                        finishLogger()
                    }
                }

                // 4. REINSTALL (Complex -> Use Logger)
                is AppClickAction.Reinstall -> {
                    startLogger(UiText.StringResource(R.string.log_reinstalling_app, action.appInfo.appName ?: ""))
                    addLog(UiText.StringResource(R.string.log_applying_play_store_sig))

                    withContext(ioDispatcher) {
                        val result =
                            manageAppUseCase.reinstallAppWithGoogle(action.appInfo.packageName)
                        if (result.isSuccess) {
                            addLog(UiText.StringResource(R.string.log_reinstall_success))
                            triggerSupportPromptIfNeeded()
                        } else {
                            addLog(UiText.StringResource(R.string.log_failed_with_msg, result.exceptionOrNull()?.message ?: ""))
                        }
                    }
                    finishLogger()
                }

                // 5. UNINSTALL (System = Risky -> Logger / User = Fast -> Toast)
                is AppClickAction.Uninstall -> {
                    if (action.appInfo.isSystem) {
                        startLogger(UiText.StringResource(R.string.log_uninstalling_system_app))
                        addLog(UiText.StringResource(R.string.log_target_app, action.appInfo.appName ?: ""))
                        withContext(ioDispatcher) {
                            val result = manageAppUseCase.uninstallApp(action.appInfo.packageName)
                            if (result.isSuccess) {
                                addLog(UiText.StringResource(R.string.log_uninstall_success))
                                freezerRepository.add(action.appInfo.packageName)
                                triggerSupportPromptIfNeeded()
                            } else {
                                addLog(UiText.StringResource(R.string.log_priv_uninstall_failed))
                                addLog(UiText.StringResource(R.string.log_attempting_system_uninstall))
                                _effect.send(MainSideEffect.NormalUninstall(action.appInfo.packageName))
                            }
                        }
                        finishLogger()
                    } else {
                        viewModelScope.launch(ioDispatcher) {
                            val result = manageAppUseCase.uninstallApp(action.appInfo.packageName)
                            if (result.isSuccess) {
                                _effect.send(
                                    MainSideEffect.Message(
                                        UiText.StringResource(
                                            R.string.uninstall_success,
                                            action.appInfo.appName ?: action.appInfo.packageName
                                        )
                                    )
                                )
                                triggerSupportPromptIfNeeded()
                            } else {
                                _effect.send(MainSideEffect.NormalUninstall(action.appInfo.packageName))
                            }
                        }
                    }
                }

                // 6. REINSTALL ALL (Batch Logic Triggered via Single Action Enum)
                AppClickAction.ReinstallAll -> {
                    startLogger(UiText.StringResource(R.string.log_scanning_apps))
                    // getInstalledAppsUseCase() reads PackageManager and can throw
                    // (e.g. DeadObjectException). Guard it so a failure can't crash the app or
                    // leave the logger dialog stuck spinning.
                    val (userApps, _) = try {
                        getInstalledAppsUseCase().first()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e // preserve structured-concurrency cancellation
                        Logger.e("MainViewModel", "reinstallAll: failed to load apps", e)
                        addLog(UiText.StringResource(R.string.log_error, e.message ?: ""))
                        finishLogger()
                        return@launch
                    }

                    val targets = fixStoreCandidates(userApps, BuildConfig.APPLICATION_ID)

                    if (targets.isEmpty()) {
                        addLog(UiText.StringResource(R.string.log_no_apps_to_fix))
                        finishLogger()
                    } else {
                        addLog(UiText.StringResource(R.string.log_found_apps_to_fix, targets.size))
                        dismissLogger()
                        // The scan hands over to the picker, not to the batch. What this action used
                        // to do was reinstall every app it had just counted, behind a warning
                        // dialog that never said which ones.
                        _uiState.update { state ->
                            state.copy(
                                fixStoreSelection = FixStoreSelection(
                                    candidates = targets.sortedBy { app ->
                                        (app.appName ?: app.packageName).lowercase()
                                    },
                                    selected = targets.mapTo(mutableSetOf()) { it.packageName }
                                )
                            )
                        }
                    }
                }

                // 7. QUICK ACTIONS
                is AppClickAction.Kill -> quickAction(action) { manageAppUseCase.forceStop(it.packageName) }
                is AppClickAction.Freeze -> quickAction(action) { manageAppUseCase.setAppDisabled(it.packageName, true) }
                is AppClickAction.UnFreeze -> quickAction(action) { manageAppUseCase.setAppDisabled(it.packageName, false) }
                // The only quick action with a number to report. A null count is not zero — the
                // clear worked and the measurement did not — so it falls back to the plain message
                // rather than saying "0 B".
                is AppClickAction.ClearCache -> quickAction(
                    action,
                    successMessage = { app, freed ->
                        val name = app.appName ?: app.packageName
                        if (freed == null || freed <= 0L) {
                            UiText.StringResource(R.string.cache_cleared_success, name)
                        } else {
                            // Order matters: %1$s is the app label, %2$s the size — the same
                            // positions cache_cleared_success uses for its single argument, so the
                            // two messages stay swappable.
                            UiText.StringResource(
                                R.string.cache_cleared_success_size,
                                name,
                                formatBytes(freed)
                            )
                        }
                    }
                ) { manageAppUseCase.clearCache(it.packageName) }
                is AppClickAction.ClearData -> quickAction(action) { manageAppUseCase.clearAppData(it.packageName) }
                is AppClickAction.Suspend -> quickAction(action) { manageAppUseCase.setAppSuspended(it.packageName, true) }
                is AppClickAction.UnSuspend -> quickAction(action) { manageAppUseCase.setAppSuspended(it.packageName, false) }
                is AppClickAction.ManagePermissions -> {}
                // Handled entirely in FreezerScreen (viewModel.pinAppToLauncher); never routed here.
                is AppClickAction.AddToHomeScreen -> {}
            }
        }
    }

    // --- Multi App Action Handler ---

    fun onMultiAppAction(action: MultiAppAction) {
        viewModelScope.launch {
            when (action) {
                is MultiAppAction.ReInstall -> performLoggedMultiAction(
                    UiText.StringResource(R.string.log_reinstalling_batch),
                    action.appList
                ) { appInfo ->
                    val result = manageAppUseCase.reinstallAppWithGoogle(appInfo.packageName)
                    if (result.isSuccess) {
                        result
                    } else {
                        // appInfo.isDebuggable is already resolved on the domain model (from the
                        // installed-app scan), so no PackageManager lookup is needed here.
                        if (appInfo.isDebuggable) {
                            Result.failure(UiTextException(UiText.StringResource(R.string.error_debuggable_app)))
                        } else {
                            result
                        }
                    }
                }

                is MultiAppAction.Freeze -> performCountedFreeze(action.appList, isFreeze = true, useSuspend = action.useSuspend)

                is MultiAppAction.UnFreeze -> performCountedFreeze(action.appList, isFreeze = false)

                is MultiAppAction.Kill -> performLoggedMultiAction(
                    UiText.StringResource(R.string.log_killing_batch),
                    action.appList
                ) {
                    manageAppUseCase.forceStop(it.packageName)
                }

                // Root-only, like every per-package clear. The freed byte counts are discarded here
                // on purpose: this path reports through the batch logger, which speaks in
                // per-app success/failure lines, and a running total interleaved with them would be
                // the one number on screen that nothing else agrees with. The whole-device clear is
                // where a total belongs.
                is MultiAppAction.ClearCache -> performLoggedMultiAction(
                    UiText.StringResource(R.string.log_clearing_cache_batch),
                    action.appList
                ) {
                    manageAppUseCase.clearCache(it.packageName).map { }
                }

                is MultiAppAction.Uninstall -> performLoggedMultiAction(
                    UiText.StringResource(R.string.log_uninstalling_batch),
                    action.appList
                ) { appInfo ->
                    if (appInfo.freezeTier == FreezeTier.BLOCKED) {
                        Result.failure(UiTextException(UiText.StringResource(R.string.error_unsafe_skipped)))
                    } else {
                        val result = manageAppUseCase.uninstallApp(appInfo.packageName)
                        if (result.isSuccess && appInfo.isSystem) {
                            freezerRepository.add(appInfo.packageName)
                        }
                        result
                    }
                }

                is MultiAppAction.Suspend -> performLoggedMultiAction(
                    UiText.StringResource(R.string.log_suspending_batch),
                    action.appList
                ) {
                    manageAppUseCase.setAppSuspended(it.packageName, true)
                }

                is MultiAppAction.UnSuspend -> performLoggedMultiAction(
                    UiText.StringResource(R.string.log_unsuspending_batch),
                    action.appList
                ) {
                    manageAppUseCase.setAppSuspended(it.packageName, false)
                }

                is MultiAppAction.ClearData -> performLoggedMultiAction(
                    UiText.StringResource(R.string.log_clearing_data_batch),
                    action.appList
                ) {
                    manageAppUseCase.clearAppData(it.packageName)
                }

                is MultiAppAction.Share -> {
                    viewModelScope.launch {
                        startLogger(UiText.StringResource(R.string.log_sharing_batch))
                        val uris = mutableListOf<android.net.Uri>()

                        withContext(ioDispatcher) {
                            action.appList.forEachIndexed { index, app ->
                                addLog(UiText.StringResource(R.string.log_batch_preparing, index + 1, action.appList.size, app.appName ?: ""))
                                val result = shareAppUseCase(app)
                                if (result.isSuccess) {
                                    uris.add(result.getOrThrow())
                                    addLog(UiText.StringResource(R.string.log_ready))
                                } else {
                                    val exception = result.exceptionOrNull()
                                    val errorLog = if (exception is UiTextException) {
                                        UiText.StringResource(R.string.log_failed, exception.uiText)
                                    } else {
                                        UiText.StringResource(R.string.log_failed, exception?.message ?: "")
                                    }
                                    addLog(errorLog)
                                }
                            }
                        }

                        if (uris.isNotEmpty()) {
                            dismissLogger()
                            _effect.send(MainSideEffect.ShareApps(uris))
                        } else {
                            finishLogger()
                        }
                    }
                }

                // Deliberately not run here. Exporting 200 apps takes minutes and has to survive
                // the toolbox, this ViewModel and usually the Activity behind it, so the work
                // belongs to BackupRunner's process-lifetime scope; this branch only hands over
                // the selection. The returned Deferred is dropped on purpose — [observeBackupRun]
                // is the single place an outcome is reported, and awaiting it here as well would
                // report the same run twice.
                is MultiAppAction.Backup -> backupRunner.start(action.appList)
            }
        }
    }

    /**
     * Bulk freeze / unfreeze with compact count-only progress ([FreezeLoggerState]).
     * Unsafe / UAD-failed system apps are excluded from the freeze set up-front (so the
     * total reflects only what we actually attempt), then each app is toggled
     * sequentially with a live `processed / total` count.
     */
    private suspend fun performCountedFreeze(apps: List<AppInfo>, isFreeze: Boolean, useSuspend: Boolean = false) {
        val targets = if (isFreeze) {
            // Only freeze ACTIVE apps: skip unsafe/UAD system apps AND anything already frozen
            // (disabled or suspended) so we never stack disable+suspend into a mixed state.
            apps.filter { it.isActive && it.freezeTier != FreezeTier.BLOCKED }
        } else {
            apps
        }

        _uiState.update {
            it.copy(
                freezeLoggerState = FreezeLoggerState(
                    isVisible = true,
                    isFreeze = isFreeze,
                    total = targets.size
                )
            )
        }

        var processed = 0
        var failed = 0
        withContext(ioDispatcher) {
            targets.forEach { app ->
                val result = if (isFreeze) {
                    if (useSuspend) manageAppUseCase.setAppSuspended(app.packageName, true)
                    else manageAppUseCase.setAppDisabled(app.packageName, true)
                } else {
                    // State-aware restore: clears suspend AND disable, incl. mixed state.
                    manageAppUseCase.restoreApp(app.packageName, app.enabled, app.isSuspended)
                }
                processed++
                if (result.isFailure) failed++
                val p = processed
                val f = failed
                _uiState.update {
                    it.copy(freezeLoggerState = it.freezeLoggerState.copy(processed = p, failed = f))
                }
            }
        }

        _uiState.update {
            it.copy(freezeLoggerState = it.freezeLoggerState.copy(isComplete = true))
        }
        if (processed - failed > 0) {
            triggerSupportPromptIfNeeded()
        }
    }

    fun dismissFreezeLogger() {
        _uiState.update { it.copy(freezeLoggerState = FreezeLoggerState()) }
    }

    /** Stop the export in flight. Whatever it already wrote stays written. */
    fun cancelExport() {
        backupRunner.cancel()
    }

    /** What the end of an export run should say, and whether it earns the support prompt. */
    private data class ExportReport(
        val messages: List<UiText>,
        val asksForSupport: Boolean = false
    )

    /**
     * The end of an export run, as data.
     *
     * Messages rather than state so the outcome reaches whichever screen the user wandered off to
     * while the run continued — the normal case for a run this long.
     *
     * Pure, and separate from the delivery loop in [observeBackupRun], because delivery has one
     * rule that has to hold across every branch — acknowledge as soon as the first message lands —
     * and a rule spread across five `when` arms is a rule with a hole in it.
     */
    private fun exportReport(result: BackupRunResult): ExportReport = when (result) {
        is BackupRunResult.Rejected -> ExportReport(listOf(result.reason.asMessage()))

        // saved, not attempted: the sentence says "were saved", and an app that failed is
        // counted by neither the folder nor the user.
        is BackupRunResult.Cancelled -> ExportReport(
            listOf(
                UiText.StringResource(
                    R.string.export_bulk_cancelled,
                    result.saved,
                    result.total
                )
            )
        )

        is BackupRunResult.Failed -> ExportReport(
            listOf(
                UiText.StringResource(
                    R.string.export_bulk_failed,
                    result.saved,
                    result.total
                )
            )
        )

        // location is null exactly when nothing was written, so it is both the "did anything
        // land?" test and the only value that could leave the sentence hanging after "to".
        is BackupRunResult.Finished -> when (val location = result.location) {
            null -> ExportReport(
                listOf(UiText.StringResource(R.string.export_bulk_finished_none, result.total))
            )

            else -> ExportReport(
                messages = buildList {
                    // The partial run needs no string of its own: "8 of 12" and "12 of 12" read
                    // correctly from the same sentence, so nothing here branches on `failed`.
                    add(
                        UiText.StringResource(
                            R.string.export_bulk_finished,
                            result.succeeded,
                            result.total,
                            location
                        )
                    )
                    // A second message rather than a longer first one: the missing manifest is a
                    // separate fact about the same folder, and only worth saying when there are
                    // files in there for it to have described.
                    if (!result.indexWritten) {
                        add(UiText.StringResource(R.string.export_bulk_index_failed))
                    }
                },
                asksForSupport = true
            )
        }
    }

    private fun BackupRejection.asMessage(): UiText = when (this) {
        BackupRejection.NothingToExport ->
            UiText.StringResource(R.string.export_bulk_nothing_selected)

        is BackupRejection.InsufficientStagingSpace -> UiText.StringResource(
            R.string.export_bulk_no_space,
            formatBytes(requiredBytes),
            formatBytes(availableBytes)
        )
    }

    /**
     * A byte count as a short human string.
     *
     * `android.text.format.Formatter.formatShortFileSize` is the usual answer and is what the
     * details screen uses, but it needs a Context and this ViewModel deliberately has none — every
     * other string leaves here as a [UiText] for the screen to resolve. Same SI units the platform
     * helper has used since O.
     *
     * The locale is [AppLocale.formattingLocale], not `Locale.getDefault()`. Below API 33 nothing
     * makes the process default follow Thor's in-app language, so the default would put `1.5 GB`
     * inside `export_bulk_no_space` — a sentence rendered from `values-fr` — where the rest of that
     * sentence expects `1,5`. On 33+ the platform merges the per-app locale into the process
     * default and the two answers coincide.
     */
    private fun formatBytes(bytes: Long): String {
        val units = listOf("B", "kB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        val pattern = if (unit == 0) "%.0f %s" else "%.1f %s"
        return String.format(AppLocale.formattingLocale(), pattern, value, units[unit])
    }

    private suspend fun performLoggedMultiAction(
        title: UiText,
        apps: List<AppInfo>,
        block: suspend (AppInfo) -> Result<Unit>
    ) {
        startLogger(title, canStop = apps.size > 1)
        var hasAtLeastOneSuccess = false
        var processed = 0

        withContext(ioDispatcher) {
            for ((index, app) in apps.withIndex()) {
                // Checked between apps, never during one: a batch stopped here has done some apps
                // and left the rest untouched, which is a state the user can reason about.
                if (stopRequested) break
                addLog(UiText.StringResource(R.string.log_batch_step, index + 1, apps.size, app.appName ?: ""))
                val result = block(app)
                processed++
                if (result.isSuccess) {
                    addLog(UiText.StringResource(R.string.log_success))
                    hasAtLeastOneSuccess = true
                } else {
                    val exception = result.exceptionOrNull()
                    val errorLog = if (exception is UiTextException) {
                        UiText.StringResource(R.string.log_failed, exception.uiText)
                    } else {
                        UiText.StringResource(R.string.log_failed, exception?.message ?: "")
                    }
                    addLog(errorLog)
                }
            }
        }

        if (stopRequested) {
            addLog(UiText.StringResource(R.string.log_stopped, processed, apps.size))
        }
        finishLogger()
        if (hasAtLeastOneSuccess) {
            triggerSupportPromptIfNeeded()
        }
    }

    /**
     * [successMessage] exists for the one action whose result carries information: a cache clear
     * knows how many bytes it freed, and the toast is the place to say so. Every other caller omits
     * it and gets [getSuccessMessage], which only knows the action and the app name.
     */
    private suspend fun <T> quickAction(
        action: AppClickAction,
        successMessage: ((AppInfo, T) -> UiText)? = null,
        block: suspend (AppInfo) -> Result<T>
    ) {
        val app = action.appInfo()
        if (app != null)
            block(app)
                .onSuccess { value ->
                    _effect.send(
                        MainSideEffect.Message(
                            successMessage?.invoke(app, value) ?: getSuccessMessage(
                                action,
                                app.appName ?: app.packageName
                            )
                        )
                    )
                    triggerSupportPromptIfNeeded()
                }
                .onFailure { e ->
                    val errorText = if (e is UiTextException) e.uiText else UiText.DynamicString(e.message ?: "")
                    _effect.send(
                        MainSideEffect.Message(
                            UiText.StringResource(
                                R.string.error_format,
                                errorText
                            )
                        )
                    )
                }
        else {
            _effect.send(MainSideEffect.Message(UiText.StringResource(R.string.error_app_info_missing)))
        }
    }

    /**
     * The declared type for a share, taken from the bundle's own file name. The builder — not the
     * app — decides the container (a split app comes back as an `.apks` zip), so the file name is
     * the only honest source; the FileProvider URI's last segment is that name.
     *
     * An unrecognised extension falls back to the non-installable type on purpose: typing a zip as
     * a package archive is what makes an installer accept the share and then choke on it.
     */
    private fun mimeForBundle(uri: android.net.Uri): String {
        val extension = uri.lastPathSegment.orEmpty().substringAfterLast('.', "")
        return BundleFormat.entries.firstOrNull { it.extension == extension }?.mime
            ?: BundleFormat.APKS.mime
    }

    private fun getSuccessMessage(action: AppClickAction, appName: String): UiText {
        return when (action) {
            is AppClickAction.Kill -> UiText.StringResource(R.string.killed_success, appName)
            is AppClickAction.Freeze -> UiText.StringResource(R.string.frozen_success, appName)
            is AppClickAction.UnFreeze -> UiText.StringResource(R.string.unfrozen_success, appName)
            is AppClickAction.ClearCache -> UiText.StringResource(R.string.cache_cleared_success, appName)
            is AppClickAction.ClearData -> UiText.StringResource(R.string.data_cleared_success, appName)
            is AppClickAction.Suspend -> UiText.StringResource(R.string.suspended_success, appName)
            is AppClickAction.UnSuspend -> UiText.StringResource(R.string.unsuspended_success, appName)
            else -> UiText.StringResource(R.string.action_completed_format, action.javaClass.simpleName, appName)
        }
    }

    private fun AppClickAction.appInfo(): AppInfo? = when (this) {
        is AppClickAction.Kill -> appInfo
        is AppClickAction.Freeze -> appInfo
        is AppClickAction.UnFreeze -> appInfo
        is AppClickAction.ClearCache -> appInfo
        is AppClickAction.Uninstall -> appInfo
        is AppClickAction.Launch -> appInfo
        is AppClickAction.Share -> appInfo
        is AppClickAction.Reinstall -> appInfo
        is AppClickAction.AppInfoSettings -> appInfo
        is AppClickAction.ClearData -> appInfo
        is AppClickAction.Suspend -> appInfo
        is AppClickAction.UnSuspend -> appInfo
        else -> null
    }
}
