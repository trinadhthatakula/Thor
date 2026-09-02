// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.freezeTier
import com.valhalla.thor.presentation.freezer.FreezerPrompt
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.AppShortcutController
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.FreezeAppUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.presentation.launchGuarded
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.asUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

data class AppInfoDetailsUiState(
    val isLoading: Boolean = true,
    val isRoot: Boolean = false,
    val isShizuku: Boolean = false,
    val isDhizuku: Boolean = false,
    val detailedInfo: DetailedAppInfo? = null,
    val isInFreezer: Boolean = false,
    val freezerPrompt: FreezerPrompt? = null,
    /**
     * [UserPreferences.skipRoutineFreezeConfirmation][com.valhalla.thor.domain.model.UserPreferences],
     * carried here because both freeze surfaces
     * reach this view model and neither of their hosts should have to.
     *
     * `AppInfoHeaderAndActions` reads it from this state via `AppInfoDetailsScreen`, and `AppInfoSheet`
     * reads it from the instance it already scopes to itself — so the setting arrives at both without
     * a `skip…` parameter threaded through `AppListScreen`, `FreezerScreen` and `MainScreen`, none of
     * which have an opinion about it. Populated from a collector in `init`, not from
     * [AppInfoDetailsViewModel.loadAppDetails], so it is right before the sheet is ever expanded —
     * the freeze action is reachable at the partial detent, where no detail load has run.
     */
    val skipRoutineFreezeConfirmation: Boolean = false,
    /** Null until the probe answers. See [com.valhalla.thor.domain.model.ObbProbe]. */
    val obbProbe: ObbProbe? = null,
    val errorMessage: UiText? = null
)

@KoinViewModel
class AppInfoDetailsViewModel(
    private val appRepository: AppRepository,
    private val systemRepository: SystemRepository,
    private val manageAppUseCase: ManageAppUseCase,
    private val freezeAppUseCase: FreezeAppUseCase,
    private val freezerRepository: FreezerRepository,
    // The port, not the concrete FreezerShortcutManager: this screen only retires and re-renders a
    // single app's shortcut, and the manager needs a Context, so depending on the class put the
    // whole view model out of reach of a JVM test. Same dependency AppListViewModel already takes.
    private val appShortcuts: AppShortcutController,
    private val preferenceRepository: PreferenceRepository,
    // Injected rather than a baked-in Dispatchers.IO, so a test can put this work on its own
    // scheduler — otherwise every action below escapes the test dispatcher and nothing here is
    // deterministically assertable.
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppInfoDetailsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // Collected for the view model's whole life rather than read once, so flipping the setting
        // takes effect on a sheet that is already open.
        viewModelScope.launch {
            preferenceRepository.userPreferences.collect { prefs ->
                _uiState.update {
                    it.copy(skipRoutineFreezeConfirmation = prefs.skipRoutineFreezeConfirmation)
                }
            }
        }
    }

    // One-off toast feedback lives here (not in UiState) so it fires exactly once and is never
    // replayed on recomposition or config change. A buffered Channel (not a replay=0 SharedFlow)
    // retains events emitted before/between collectors so a value fired while the screen's collector
    // is not yet STARTED (early lifecycle / config change) is delivered rather than silently dropped.
    private val _events = Channel<UiText>(Channel.BUFFERED)
    val events: Flow<UiText> = _events.receiveAsFlow()

    /**
     * The same toast channel every action below already reports through, reached from a non-suspend
     * caller.
     *
     * `launchGuarded` hands its `onFailure` a plain `(Throwable) -> Unit`, so `send` — which
     * suspends — is out of reach there, and the guards would otherwise have no way to say anything
     * at all. `trySend` is not a weaker promise here: the channel is `BUFFERED`, so it only declines
     * once 64 messages are sitting undelivered, which describes a screen nobody is collecting rather
     * than a report that got lost. Inside a coroutine keep using `_events.send`, whose backpressure
     * is the right behaviour there.
     *
     * Named `tryEmitToast` rather than `emitToast` to match `FreezerViewModel`, where the two names
     * are two different functions: `emitToast` there is a suspending `send` and `tryEmitToast` is the
     * `trySend`. Calling this one `emitToast` gave the same name to opposite delivery guarantees
     * across two files that are read together, in a class where both spellings compile.
     */
    private fun tryEmitToast(text: UiText) {
        _events.trySend(text)
    }

    /**
     * Watchlist membership for the three places that only *display* it.
     *
     * Room reports a failed query by throwing — `SQLiteDiskIOException` on a failing disk,
     * `SQLiteFullException` on a full one, `SQLiteDatabaseCorruptException` on a damaged file — and
     * `FreezerRepositoryImpl` is a pass-through that does not catch, so before this existed a
     * membership read on a bad disk reached the thread's default handler and killed the process on a
     * tap that was only ever going to decide which way a toggle points.
     *
     * Degrading to "not in the freezer" is the fallback
     * `AppListViewModel.observeFreezerMembership` already chose for the same read — its `Flow.catch`
     * says so in as many words — and the two surfaces should not disagree. It is also the harmless
     * direction: the toggle then offers to *add* an app that may already be tracked, and a duplicate
     * add is the mistake with no consequence, whereas defaulting the other way would hide an
     * untracked frozen app behind a control that claims it is already handled. Nothing is toasted —
     * this read is a passenger on a detail load or on a force-stop / clear-cache refresh that
     * otherwise succeeded, and an error toast there would blame the action the user actually took.
     *
     * [CancellationException] is rethrown rather than degraded: a sheet dismissed mid-read is not a
     * database failure, and swallowing it would publish "not in the freezer" into a view model that
     * is being cleared.
     */
    private suspend fun isInFreezer(packageName: String): Boolean = try {
        freezerRepository.contains(packageName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e("AppInfoDetailsViewModel", "freezer membership read for $packageName failed", e)
        false
    }

    fun loadAppDetails(packageName: String) {
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                // Cleared, not carried: the details land before the probe answers (see below), so a
                // verdict left over from the previous app would be rendered against the new one —
                // "1.2 GB of game data" for an app that has none, and an enabled .xapk chip for one
                // whose expansions Thor cannot read. `null` is the "still asking" state both
                // consumers already handle.
                obbProbe = null
            )
        }
        viewModelScope.launch {
            // Availability probes include non-suspend binder IPC (Shizuku / Dhizuku) and a
            // potentially slow root check; run them off the Main thread. Each probe is an
            // independent round-trip, so launch them concurrently and let their latency
            // overlap (alongside the freezer lookup) instead of stacking sequentially.
            val (probes, inFreezer) = withContext(ioDispatcher) {
                val rootProbe = async { systemRepository.isRootAvailable() }
                val shizukuProbe = async { systemRepository.isShizukuAvailable() }
                val dhizukuProbe = async { systemRepository.isDhizukuAvailable() }
                // [isInFreezer], not the repository directly: this read is one input to a load the
                // user asked for, and a Room throw here would take the whole detail sheet — probes,
                // manifest, components and all — down with the process. Falling back to "not in the
                // freezer" costs the freezer toggle its accuracy and nothing else.
                val freezer = isInFreezer(packageName)
                Triple(
                    rootProbe.await(),
                    shizukuProbe.await(),
                    dhizukuProbe.await()
                ) to freezer
            }
            val (hasRoot, hasShizuku, hasDhizuku) = probes

            val details = appRepository.getDetailedAppInfo(packageName)
            if (details != null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRoot = hasRoot,
                        isShizuku = hasShizuku,
                        isDhizuku = hasDhizuku,
                        detailedInfo = details,
                        isInFreezer = inFreezer
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiText.StringResource(R.string.failed_to_load_app_details)
                    )
                }
            }

            // Deliberately after the details land: the probe shells out, and the rest of the
            // screen should not wait on it.
            val probe = probeObbForPresentation {
                systemRepository.probeObb(packageName)
            }
            _uiState.update { it.copy(obbProbe = probe) }
        }
    }

    /**
     * Lighter reload used after a mutating action succeeds. Re-reads the detailed info and freezer
     * membership so the UI reflects the new state, but deliberately skips the Root / Shizuku /
     * Dhizuku availability probes (privilege mode doesn't change mid-session — it's probed once by
     * [loadAppDetails]) and never flips [AppInfoDetailsUiState.isLoading], so the screen doesn't
     * flash the loader after every freeze / suspend / force-stop / clear action.
     */
    // suspend (not a nested viewModelScope.launch): every caller already runs inside a
    // viewModelScope coroutine, so launching again was redundant and could let concurrent refreshes
    // complete out of order. Called directly => it serializes within the caller's coroutine.
    private suspend fun refreshDetails(packageName: String) {
        // The membership read is guarded inside [isInFreezer] rather than at this function's call
        // sites, and that is the whole reason the helper exists. Seven coroutines call this —
        // toggleFreezerState, toggleSuspendState, forceStopApp, clearCache, clearData, addToFreezer
        // and addOrRemoveFromFreezer — and in every one of them the action the user asked for has
        // already succeeded by the time it runs. Guarding per call site would mean four more functions
        // needing an `onFailure` that invents something to say about a force-stop or a clear-cache
        // that worked, over a failure in a refresh the user never asked for. Degrading the read once,
        // here, fixes all seven at the same point and says nothing, which is the correct amount.
        val inFreezer = withContext(ioDispatcher) { isInFreezer(packageName) }
        val details = appRepository.getDetailedAppInfo(packageName)
        if (details != null) {
            _uiState.update {
                it.copy(
                    detailedInfo = details,
                    isInFreezer = inFreezer
                )
            }
        }
    }

    fun toggleFreezerState(packageName: String, appName: String?, freeze: Boolean) {
        // Raised the instant the freeze / unfreeze returns success and lowered again the moment the
        // user has been told about it, so the guard below can tell the two failures apart. Everything
        // inside `onSuccess` runs *after* an irreversible privileged call and none of it reports by
        // returning: refreshAppShortcut goes to ShortcutManagerCompat, refreshDetails to the package
        // manager. `Result.onSuccess` is a plain inline lambda and catches nothing, so a throw in
        // there used to walk straight out of the launch and end the process.
        var appliedButUnannounced = false
        launchGuarded(
            onFailure = { e ->
                Logger.e(
                    "AppInfoDetailsViewModel",
                    "toggling the frozen state of $packageName failed",
                    e
                )
                // Lead with what is true of the app. The freeze or unfreeze really did happen, and a
                // bare "Error: …" on top of it reads as "your action failed" over an app that is
                // demonstrably frozen — the one thing this must not say. The throw still gets
                // reported straight after, because a watchlist or shortcut that did not keep up is
                // something the user is entitled to know about.
                if (appliedButUnannounced) {
                    tryEmitToast(
                        UiText.StringResource(
                            if (freeze) R.string.frozen_success else R.string.unfrozen_success,
                            appName ?: packageName
                        )
                    )
                }
                // [asUiText], the same unwrap the `Result.onFailure` branch below makes. The two
                // paths don't carry the same refusals today — FreezeAppUseCase *returns* its tier
                // refusal rather than throwing it, so a refusal lands there and not here — but a
                // `throw` added anywhere in this block surfaces through this handler instead, and
                // two sites unwrapping the same type by hand is the asymmetry that shows up as an
                // empty error toast. Hence one function, called from both.
                tryEmitToast(e.asUiText())
            }
        ) {
            // Freezing goes through FreezeAppUseCase so the BLOCKED tier is enforced below this
            // view model rather than by AppRiskDialog declining to render a confirm button.
            // Unfreezing keeps the raw call: it must never be blocked.
            val result = if (freeze) freezeAppUseCase(packageName)
            else manageAppUseCase.setAppDisabled(packageName, false)
            result.onSuccess {
                appliedButUnannounced = true
                appShortcuts.refreshAppShortcut(packageName)
                val inFreezer = withContext(ioDispatcher) { isInFreezer(packageName) }
                if (freeze && !inFreezer) {
                    // Don't auto-add — prompt the user to add it to the Freezer instead.
                    _uiState.update { it.copy(freezerPrompt = FreezerPrompt(packageName, appName)) }
                } else {
                    val msgRes = if (freeze) R.string.frozen_success else R.string.unfrozen_success
                    _uiState.update { it.copy(isInFreezer = inFreezer) }
                    _events.send(UiText.StringResource(msgRes, appName ?: packageName))
                    // Told. A later throw must not repeat this toast on its way out.
                    appliedButUnannounced = false
                }
                // Refresh detail only — no privilege re-probe, no loader flash.
                refreshDetails(packageName)
            }.onFailure { e ->
                // The tier refusal arrives here as a UiTextException, which carries its message in
                // `uiText` and leaves `message` null — see [asUiText].
                _events.send(e.asUiText())
            }
        }
    }

    fun toggleSuspendState(packageName: String, suspend: Boolean) {
        viewModelScope.launch {
            val result = manageAppUseCase.setAppSuspended(packageName, suspend)
            result.onSuccess {
                // Refresh detail only — no privilege re-probe, no loader flash.
                refreshDetails(packageName)
            }.onFailure { e ->
                _events.send(UiText.StringResource(R.string.error_format, e.message ?: ""))
            }
        }
    }

    fun forceStopApp(packageName: String) {
        viewModelScope.launch {
            val result = manageAppUseCase.forceStop(packageName)
            result.onSuccess {
                val appName = _uiState.value.detailedInfo?.appInfo?.appName ?: packageName
                _events.send(UiText.StringResource(R.string.killed_success, appName))
                refreshDetails(packageName)
            }.onFailure { e ->
                _events.send(UiText.StringResource(R.string.error_format, e.message ?: ""))
            }
        }
    }

    fun clearCache(packageName: String) {
        viewModelScope.launch {
            val result = manageAppUseCase.clearCache(packageName)
            result.onSuccess {
                val appName = _uiState.value.detailedInfo?.appInfo?.appName ?: packageName
                _events.send(UiText.StringResource(R.string.cache_cleared_success, appName))
                refreshDetails(packageName)
            }.onFailure { e ->
                _events.send(UiText.StringResource(R.string.error_format, e.message ?: ""))
            }
        }
    }

    fun clearData(packageName: String) {
        viewModelScope.launch {
            val result = manageAppUseCase.clearAppData(packageName)
            result.onSuccess {
                val appName = _uiState.value.detailedInfo?.appInfo?.appName ?: packageName
                _events.send(UiText.StringResource(R.string.data_cleared_success, appName))
                refreshDetails(packageName)
            }.onFailure { e ->
                _events.send(UiText.StringResource(R.string.error_format, e.message ?: ""))
            }
        }
    }

    /**
     * The freezer prompt's confirm. Deliberately not tier-gated, unlike [addOrRemoveFromFreezer]:
     * [toggleFreezerState] only raises the prompt inside `onSuccess`, so the app is already frozen
     * and the question is whether to track it. Tracking a frozen app can't re-freeze it
     * (`freezableCandidates` drops blocked apps from FREEZE runs) and is what lets Unfreeze-all
     * reach it, so refusing here would stand between the user and their own frozen app.
     */
    fun addToFreezer(packageName: String) {
        launchGuarded(
            // Nothing here can fail the user's freeze, because the freeze is already done — the
            // prompt only exists because [toggleFreezerState]'s `onSuccess` raised it. What a full
            // or failing disk costs is the watchlist row, which leaves the app frozen and untracked:
            // the very state the prompt was offering to fix. So the report is `error_format` and
            // nothing more. There is no existing string for "it is frozen, Thor just could not write
            // it down", and this branch is not allowed to add one — eight in-app locales, six
            // coupling sites — so the throw is reported plainly rather than dressed up in a claim.
            //
            // The prompt is deliberately left standing and `isInFreezer` deliberately left false:
            // no row was written, so false is the truth, and a prompt still on screen makes the
            // retry one more tap — the same call FreezerViewModel.removeFromFreezer makes when it
            // keeps a failed package selected.
            onFailure = { e ->
                Logger.e("AppInfoDetailsViewModel", "adding $packageName to the freezer failed", e)
                tryEmitToast(e.asUiText())
            }
        ) {
            withContext(ioDispatcher) { freezerRepository.add(packageName) }
            _uiState.update { it.copy(freezerPrompt = null, isInFreezer = true) }
            refreshDetails(packageName)
        }
    }

    fun dismissFreezerPrompt() {
        _uiState.update { it.copy(freezerPrompt = null) }
    }

    fun addOrRemoveFromFreezer(packageName: String) {
        // Raised the moment the restore below reports success. Past that point the app really is
        // running again, and everything left — the Room delete, the shortcut, the refresh — is Thor
        // writing down what already happened. A guard that could not tell the two apart would answer
        // a successful unfreeze with a bare "Error: …", which every user reads as "it didn't
        // unfreeze" about an app they can see in their launcher.
        var restored = false
        launchGuarded(
            // context, not a bare launch: this coroutine was `viewModelScope.launch(ioDispatcher)`
            // and both the Room calls and the privileged restore below have to stay off the main
            // thread. Dropping it would move them onto Dispatchers.Main.immediate and nothing would
            // fail, because Room's suspend DAO functions dispatch internally.
            context = ioDispatcher,
            onFailure = { e ->
                Logger.e(
                    "AppInfoDetailsViewModel",
                    "toggling freezer membership for $packageName failed",
                    e
                )
                if (restored) {
                    // Two facts, in the order they matter. The app is unfrozen — say so — and then
                    // say that something threw, because the watchlist row this was meant to drop is
                    // still there and the toggle above is still showing it. No existing string says
                    // "unfrozen, but the record survived" and adding one is out of the question here
                    // (eight in-app locales, six coupling sites), so the two halves are reported as
                    // the two facts they are rather than compressed into a claim that would be false
                    // either way round.
                    //
                    // `unfrozen_success`, which is *not* the string the success path ends on — that
                    // one sends the `removed_from_freezer_success` plural, a claim about the row,
                    // which is the exact thing that did not happen here. Because the two strings
                    // differ, `restored` is deliberately left raised after its toast rather than
                    // lowered the way `toggleFreezerState`'s `appliedButUnannounced` is: there is no
                    // duplicate to suppress, and a throw from a statement appended below would
                    // otherwise report a bare "Error: …" over an app the user can see running.
                    val appName = _uiState.value.detailedInfo?.appInfo?.appName ?: packageName
                    tryEmitToast(UiText.StringResource(R.string.unfrozen_success, appName))
                }
                tryEmitToast(e.asUiText())
            }
        ) {
            // Deliberately the repository and not [isInFreezer]: this read picks between two
            // opposite actions rather than feeding a label, so degrading it to "not in the freezer"
            // would answer a Remove tap by trying to Add. Failing loudly through the guard above is
            // the honest answer, and `restored` is still false there, so it reports as the plain
            // error it is — nothing has happened to the app at this point.
            val currentlyIn = freezerRepository.contains(packageName)
            if (currentlyIn) {
                // Removing always restores, the same as FreezerViewModel.removeFromFreezer and
                // AppListViewModel.toggleFreezerMembership. Leaving the app frozen here would strand
                // it: the freezer screen no longer lists it, so the only route back is the
                // import-already-disabled flow. forceUnfreeze covers the case where details haven't
                // loaded — both of its halves are no-ops on an already-active app.
                //
                // Restore first, drop the watchlist row second. The privileged call is the only step
                // that can fail, and the Room delete is durable — do it first and a failed restore
                // leaves a frozen app with no watchlist entry to retry from, which is the exact
                // stranding this method exists to prevent.
                val app = _uiState.value.detailedInfo?.appInfo
                (if (app != null) manageAppUseCase.restoreApp(packageName, app.enabled, app.isSuspended)
                else manageAppUseCase.forceUnfreeze(packageName))
                    .onFailure { e ->
                        _events.send(UiText.StringResource(R.string.error_format, e.message ?: ""))
                        return@launchGuarded
                    }
                // From here on the app is running again and cannot be un-run. Everything below is
                // bookkeeping, and the guard reports it as such.
                restored = true
                // The same optimistic patch AppListViewModel.toggleFreezerMembership applies, and for
                // the same reason: past this line the app is thawed, and both statements below can
                // throw. Left behind them a throw would skip it, leaving the sheet drawing the `frozen`
                // chip over an app the guard has just toasted as unfrozen — the screen contradicting
                // its own toast on the one path where the toast is certainly true.
                //
                // Stated here rather than left to refreshDetails, which re-reads through the package
                // manager and races the enable: the new state is not always visible to
                // getDetailedAppInfo immediately (FreezerLaunchActivity retries ~10×150ms for exactly
                // that), whereas a restoreApp that returned success is knowledge.
                _uiState.update { state ->
                    state.copy(
                        detailedInfo = state.detailedInfo?.let {
                            it.copy(appInfo = it.appInfo.copy(enabled = true, isSuspended = false))
                        }
                    )
                }
                // Grey the launcher shortcut *before* dropping the row, matching
                // AppListViewModel.toggleFreezerMembership — see the comment there for the argument.
                // Short version: a pinned shortcut can only ever be greyed, never removed, so both
                // orders leave residue on a throw and the question is which residue is worse. Greying
                // first keeps the pair consistent and the removal retryable; dropping the row first
                // and then failing to grey leaves an orphaned live shortcut for an app that is no
                // longer on the watchlist, and no route back to it — the freezer screen no longer
                // lists the app, so the toggle that would retry the disable is gone.
                appShortcuts.disableAppShortcut(packageName)
                freezerRepository.remove(packageName)
                // refreshDetails re-reads membership, but only when details are loaded — set it here
                // too so the toggle also flips before the first load lands. After the delete, never
                // before it, so the toggle describes the database at every point: claiming "not in
                // Freezer" over a row that is still there invites a second tap that re-adds nothing
                // and reports success.
                _uiState.update { it.copy(isInFreezer = false) }
                refreshDetails(packageName)
                _events.send(UiText.PluralsResource(R.plurals.removed_from_freezer_success, 1))
            } else {
                // Same BLOCKED gate as FreezerViewModel.toggleManaged and
                // AppListViewModel.toggleFreezerMembership — three surfaces reach the watchlist and
                // all three have to agree, or the answer just depends on which one you tapped.
                // Fails closed while details are still loading: an unknown tier is not a safe tier.
                val app = _uiState.value.detailedInfo?.appInfo
                if (app == null || app.freezeTier == FreezeTier.BLOCKED) {
                    _events.send(UiText.StringResource(R.string.error_unsafe_skipped))
                    return@launchGuarded
                }
                // Unlike the branch above, this one freezes nothing — the watchlist row is the whole
                // action. So a throw here really is a failed action and `restored` is still false,
                // which is exactly how the guard reports it: one plain error, no success claim.
                freezerRepository.add(packageName)
                _uiState.update { it.copy(isInFreezer = true) }
                _events.send(UiText.StringResource(R.string.added_to_freezer_success))
            }
        }
    }
}
