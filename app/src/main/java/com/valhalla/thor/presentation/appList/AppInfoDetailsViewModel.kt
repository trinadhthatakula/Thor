// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.freezeTier
import com.valhalla.thor.presentation.freezer.FreezerPrompt
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.AppShortcutController
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.FreezeAppUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.UiTextException
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
    val errorMessage: UiText? = null
)

@KoinViewModel
class AppInfoDetailsViewModel(
    private val appRepository: AppRepository,
    private val systemRepository: SystemRepository,
    private val manageAppUseCase: ManageAppUseCase,
    private val freezeAppUseCase: FreezeAppUseCase,
    private val freezerRepository: FreezerRepository,
    // The narrow port, not the concrete FreezerShortcutManager: this screen only retires and
    // re-renders a single app's shortcut, and the manager needs a Context, so depending on the
    // class put the whole view model out of reach of a JVM test. Same dependency AppListViewModel
    // already takes.
    private val appShortcuts: AppShortcutController,
    // Injected rather than a baked-in Dispatchers.IO, so a test can put this work on its own
    // scheduler — otherwise every action below escapes the test dispatcher and nothing here is
    // deterministically assertable.
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppInfoDetailsUiState())
    val uiState = _uiState.asStateFlow()

    // One-off toast feedback lives here (not in UiState) so it fires exactly once and is never
    // replayed on recomposition or config change. A buffered Channel (not a replay=0 SharedFlow)
    // retains events emitted before/between collectors so a value fired while the screen's collector
    // is not yet STARTED (early lifecycle / config change) is delivered rather than silently dropped.
    private val _events = Channel<UiText>(Channel.BUFFERED)
    val events: Flow<UiText> = _events.receiveAsFlow()

    fun loadAppDetails(packageName: String) {
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null
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
                val freezer = freezerRepository.contains(packageName)
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
        val inFreezer = withContext(ioDispatcher) { freezerRepository.contains(packageName) }
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
        viewModelScope.launch {
            // Freezing goes through FreezeAppUseCase so the BLOCKED tier is enforced below this
            // view model rather than by AppRiskDialog declining to render a confirm button.
            // Unfreezing keeps the raw call: it must never be blocked.
            val result = if (freeze) freezeAppUseCase(packageName)
            else manageAppUseCase.setAppDisabled(packageName, false)
            result.onSuccess {
                appShortcuts.refreshAppShortcut(packageName)
                val inFreezer = withContext(ioDispatcher) { freezerRepository.contains(packageName) }
                if (freeze && !inFreezer) {
                    // Don't auto-add — prompt the user to add it to the Freezer instead.
                    _uiState.update { it.copy(freezerPrompt = FreezerPrompt(packageName, appName)) }
                } else {
                    val msgRes = if (freeze) R.string.frozen_success else R.string.unfrozen_success
                    _uiState.update { it.copy(isInFreezer = inFreezer) }
                    _events.send(UiText.StringResource(msgRes, appName ?: packageName))
                }
                // Refresh detail only — no privilege re-probe, no loader flash.
                refreshDetails(packageName)
            }.onFailure { e ->
                // A UiTextException already carries the message to show (the tier refusal) and
                // has a null `message`, which error_format would render as a bare "Error: ".
                _events.send(
                    if (e is UiTextException) e.uiText
                    else UiText.StringResource(R.string.error_format, e.message ?: "")
                )
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
        viewModelScope.launch {
            withContext(ioDispatcher) { freezerRepository.add(packageName) }
            _uiState.update { it.copy(freezerPrompt = null, isInFreezer = true) }
            refreshDetails(packageName)
        }
    }

    fun dismissFreezerPrompt() {
        _uiState.update { it.copy(freezerPrompt = null) }
    }

    fun addOrRemoveFromFreezer(packageName: String) {
        viewModelScope.launch(ioDispatcher) {
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
                        return@launch
                    }
                freezerRepository.remove(packageName)
                appShortcuts.disableAppShortcut(packageName)
                // refreshDetails re-reads membership, but only when details are loaded — set it here
                // too so the toggle also flips before the first load lands.
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
                    return@launch
                }
                freezerRepository.add(packageName)
                _uiState.update { it.copy(isInFreezer = true) }
                _events.send(UiText.StringResource(R.string.added_to_freezer_success))
            }
        }
    }
}
