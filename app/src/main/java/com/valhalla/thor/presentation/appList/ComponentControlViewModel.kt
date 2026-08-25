// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.data.source.local.ComponentCapabilityProvider
import com.valhalla.thor.domain.model.ComponentCapability
import com.valhalla.thor.domain.model.ComponentDetail
import com.valhalla.thor.domain.model.ComponentOverride
import com.valhalla.thor.domain.model.ComponentSnapshot
import com.valhalla.thor.domain.model.ComponentType
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.ComponentControlUseCase
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
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

/**
 * A disable the user has been asked to confirm, held while the first-use disclaimer is up.
 *
 * The whole request is parked, not just a flag, so the confirm button runs the exact action the user
 * pressed. Re-deriving it from "the last row tapped" would break the moment a refresh reorders the
 * list underneath the dialog.
 */
@Immutable
data class PendingComponentDisable(
    val type: ComponentType,
    val component: ComponentDetail,
)

@Immutable
data class ComponentControlUiState(
    val packageName: String = "",
    val isLoading: Boolean = true,
    val snapshot: ComponentSnapshot = ComponentSnapshot(),
    val capability: ComponentCapability = ComponentCapability.None,
    /** Class name → the ledger row, for this package only. */
    val overrides: Map<String, ComponentOverride> = emptyMap(),
    /** The component a privileged call is running against, if any. One at a time, by design. */
    val busyClassName: String? = null,
    /**
     * Starts `true` so the disclaimer cannot flash on screen during the first frame, before the
     * preference has been read. A dialog that appears and vanishes unread is worse than one that
     * appears a beat late.
     */
    val consentAccepted: Boolean = true,
    val pendingConsent: PendingComponentDisable? = null,
    val showRestoreAllConfirm: Boolean = false,
) {
    /** How many components of *this* package Thor's ledger says it changed. */
    val restrictedCount: Int get() = overrides.size

    /**
     * A ledger row whose component is nonetheless enabled right now.
     *
     * The ledger is bookkeeping, not a source of truth — an app update, another root tool, or a
     * `pm clear` can put a component back without telling Thor. Surfacing the disagreement is the
     * whole reason drift is computed rather than assumed away.
     */
    fun isDrifted(component: ComponentDetail): Boolean =
        overrides.containsKey(component.className) && component.enabled
}

@KoinViewModel
class ComponentControlViewModel(
    private val appRepository: AppRepository,
    private val componentControl: ComponentControlUseCase,
    private val capabilityProvider: ComponentCapabilityProvider,
    private val preferenceRepository: PreferenceRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComponentControlUiState())
    val uiState = _uiState.asStateFlow()

    // Same shape and same reason as AppInfoDetailsViewModel's: a BUFFERED Channel so a message
    // emitted while the tab's collector is not yet STARTED is delivered rather than dropped.
    private val _events = Channel<UiText>(Channel.BUFFERED)
    val events: Flow<UiText> = _events.receiveAsFlow()

    private var ledgerJob: Job? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            preferenceRepository.userPreferences.collect { prefs ->
                _uiState.update { it.copy(consentAccepted = prefs.componentControlConsentAccepted) }
            }
        }
    }

    /**
     * Bind to [packageName]. Safe to call again — the host drives it from a `LaunchedEffect`.
     *
     * The ledger collector is cancelled and restarted rather than filtered, because this view model
     * is scoped per package by its Koin key and a second collector on the old package would keep
     * overwriting the state with rows that belong to a screen the user has left.
     *
     * **A repeat call for the package already bound still re-reads the snapshot**, and only skips
     * the state reset and the collector restart. That is what makes drift visible: the ledger is
     * bookkeeping, so an app update, another root tool or a `pm clear` can re-enable a component
     * behind Thor's back, and the disagreement is only computable against a *fresh*
     * `getComponentEnabledSetting`. Returning early on a rebind — which is every reopen of the
     * sheet — left the row still claiming "Restricted by Thor" until the process was restarted.
     */
    fun load(packageName: String, initial: ComponentSnapshot = ComponentSnapshot()) {
        val alreadyBound = _uiState.value.packageName == packageName && ledgerJob?.isActive == true
        if (!alreadyBound) {
            ledgerJob?.cancel()
            _uiState.update {
                ComponentControlUiState(
                    packageName = packageName,
                    // Seeded from the snapshot the detail loader already fetched, so the list
                    // renders on the first frame. Without it the tab would blank itself on every
                    // open while a second, identical PackageManager round-trip ran.
                    isLoading = initial.isEmpty,
                    snapshot = initial,
                    consentAccepted = it.consentAccepted,
                )
            }
            ledgerJob = viewModelScope.launch {
                componentControl.observeOverrides(packageName).collect { rows ->
                    _uiState.update { it.copy(overrides = rows.associateBy { row -> row.className }) }
                }
            }
        }
        // Cancelled and restarted so that repeated rebinds cannot stack PackageManager round-trips
        // whose results then land out of order.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // The capability read awaits the privilege probe; the snapshot is a PackageManager
            // round-trip. Neither depends on the other, but the snapshot is what the list renders,
            // so it lands first and the controls light up a moment later rather than the list
            // waiting on the probe.
            refreshSnapshot(packageName)
            val capability = capabilityProvider.capability()
            // Same guard refreshSnapshot carries, for the same reason: a load() for another package
            // may have landed while the probe was settling.
            _uiState.update { if (it.packageName != packageName) it else it.copy(capability = capability) }
        }
    }

    private suspend fun refreshSnapshot(packageName: String) {
        val snapshot = appRepository.getComponentDetails(packageName)
        _uiState.update {
            // Guard against a load() for a different package having landed while this was in
            // flight — the state would otherwise show one app's components under another's name.
            if (it.packageName != packageName) it
            // On a null read the previous snapshot is kept, not cleared: a failed re-read after a
            // successful disable would otherwise empty a list the user is looking at, which reads
            // as "the disable deleted every component".
            else it.copy(isLoading = false, snapshot = snapshot ?: it.snapshot)
        }
        if (snapshot == null) {
            _events.send(UiText.StringResource(R.string.failed_to_load_app_details))
        }
    }

    /**
     * Open an activity.
     *
     * Two routes, chosen by [ComponentDetail.launchRequiresRoot] rather than by which button was
     * pressed, so the caller cannot pick the wrong one. An exported, unguarded activity is reachable
     * with an ordinary `startActivity` and takes it — no privilege, no shell, and the target sees a
     * normal caller. Anything else needs uid 0 and goes through the gateway.
     */
    fun launch(context: Context, component: ComponentDetail) {
        val packageName = _uiState.value.packageName
        if (packageName.isEmpty()) return
        if (!component.launchRequiresRoot) {
            val result = runCatching {
                context.startActivity(
                    Intent().apply {
                        this.component = ComponentName(packageName, component.className)
                        // Required: `context` here is the Activity's, but a component-only intent
                        // into another package still starts a task of its own, and on a Context that
                        // is not an Activity (the sheet host in a wide layout) the flag is mandatory.
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            viewModelScope.launch { reportLaunch(result, component) }
            return
        }
        forceLaunch(component)
    }

    /**
     * Open an activity through the privileged shell, whatever its manifest says.
     *
     * Offered separately from [launch] because it is also the answer when an *exported* activity
     * refuses an ordinary launch — a frozen or suspended app, or one whose task the caller is not
     * allowed to touch.
     */
    fun forceLaunch(component: ComponentDetail) {
        val packageName = _uiState.value.packageName
        if (packageName.isEmpty()) return
        runExclusively(component.className) {
            reportLaunch(componentControl.forceLaunch(packageName, component.className), component)
        }
    }

    private suspend fun reportLaunch(result: Result<Unit>, component: ComponentDetail) {
        result
            .onSuccess {
                _events.send(
                    UiText.StringResource(R.string.component_launched, component.shortName)
                )
            }
            .onFailure { e -> sendFailure(e) }
    }

    fun stopService(component: ComponentDetail) {
        val packageName = _uiState.value.packageName
        if (packageName.isEmpty()) return
        runExclusively(component.className) {
            componentControl.stopService(packageName, component.className)
                .onSuccess {
                    _events.send(
                        UiText.StringResource(R.string.component_service_stopped, component.shortName)
                    )
                }
                .onFailure { e -> sendFailure(e) }
        }
    }

    /**
     * Ask to switch a component off.
     *
     * Routed through the disclaimer on the first ever disable. The check is here rather than in the
     * composable so that every entry point to a disable — row menu, and anything added later —
     * inherits it instead of each host remembering to ask.
     */
    fun requestDisable(type: ComponentType, component: ComponentDetail) {
        if (!_uiState.value.consentAccepted) {
            _uiState.update {
                it.copy(pendingConsent = PendingComponentDisable(type, component))
            }
            return
        }
        performDisable(type, component)
    }

    fun onDisclaimerConfirmed() {
        val pending = _uiState.value.pendingConsent ?: return
        _uiState.update { it.copy(pendingConsent = null) }
        viewModelScope.launch {
            // Persisted before the action runs, not after it succeeds: the question asked was "do
            // you understand what disabling does", and the answer does not become un-given because
            // this particular component refused.
            preferenceRepository.setComponentControlConsentAccepted(true)
        }
        performDisable(pending.type, pending.component)
    }

    fun onDisclaimerDismissed() {
        _uiState.update { it.copy(pendingConsent = null) }
    }

    private fun performDisable(type: ComponentType, component: ComponentDetail) {
        val packageName = _uiState.value.packageName
        if (packageName.isEmpty()) return
        runExclusively(component.className) {
            componentControl.disable(packageName, type, component)
                .onSuccess {
                    _events.send(
                        UiText.StringResource(R.string.component_disabled_success, component.shortName)
                    )
                    refreshSnapshot(packageName)
                }
                .onFailure { e -> sendFailure(e) }
        }
    }

    fun enable(component: ComponentDetail) {
        val packageName = _uiState.value.packageName
        if (packageName.isEmpty()) return
        runExclusively(component.className) {
            componentControl.enable(packageName, component)
                .onSuccess {
                    _events.send(
                        UiText.StringResource(R.string.component_enabled_success, component.shortName)
                    )
                    refreshSnapshot(packageName)
                }
                .onFailure { e -> sendFailure(e) }
        }
    }

    fun resetToDefault(component: ComponentDetail) {
        val packageName = _uiState.value.packageName
        if (packageName.isEmpty()) return
        runExclusively(component.className) {
            componentControl.resetToDefault(packageName, component.className)
                .onSuccess {
                    _events.send(
                        UiText.StringResource(R.string.component_reset_success, component.shortName)
                    )
                    refreshSnapshot(packageName)
                }
                .onFailure { e -> sendFailure(e) }
        }
    }

    /** Drop a ledger row without touching the platform. Offered for a drifted row. */
    fun forget(component: ComponentDetail) {
        val packageName = _uiState.value.packageName
        if (packageName.isEmpty()) return
        viewModelScope.launch {
            componentControl.forget(packageName, component.className)
            _events.send(
                UiText.StringResource(R.string.component_forgotten, component.shortName)
            )
        }
    }

    fun requestRestoreAll() {
        _uiState.update { it.copy(showRestoreAllConfirm = true) }
    }

    fun dismissRestoreAll() {
        _uiState.update { it.copy(showRestoreAllConfirm = false) }
    }

    /**
     * Undo every component Thor recorded, across every package — not just this one.
     *
     * Package-wide would be the smaller promise, but the ledger's reason to exist is that a
     * component disabled six weeks ago in an app the user has forgotten about is otherwise
     * unfindable. The confirmation names the total so the scope is not a surprise.
     */
    fun confirmRestoreAll() {
        val packageName = _uiState.value.packageName
        _uiState.update { it.copy(showRestoreAllConfirm = false) }
        viewModelScope.launch {
            val outcome = withContext(ioDispatcher) { componentControl.restoreAll() }
            _events.send(
                if (outcome.isComplete) {
                    UiText.PluralsResource(
                        R.plurals.component_restore_all_success,
                        outcome.restored,
                    )
                } else {
                    UiText.StringResource(
                        R.string.component_restore_all_partial,
                        outcome.restored,
                        outcome.attempted,
                    )
                }
            )
            if (packageName.isNotEmpty()) refreshSnapshot(packageName)
        }
    }

    /**
     * Run one privileged action at a time, marking its row busy for the duration.
     *
     * Serialising is not just cosmetic: `pm` and `am` reach the same `PackageManagerService` state,
     * and two disables racing on one package can interleave into a `package-restrictions.xml` write
     * that loses one of them. The busy marker also stops a double-tap becoming two disables and two
     * ledger writes.
     *
     * The `catch` is load-bearing, not defensive habit. Every verb returns a `Result`, but the
     * ledger write that follows a successful one happens inside `Result.onSuccess`, which does not
     * catch — so a Room failure there (`SQLiteFullException`, a disk-I/O error) throws straight out
     * of the use case. Without a catch that lands in `viewModelScope`'s uncaught handler and takes
     * the process down, *after* the privileged change already succeeded. Reported like any other
     * failure instead; `CancellationException` is rethrown so cancellation stays cancellation.
     */
    private fun runExclusively(className: String, block: suspend () -> Unit) {
        if (_uiState.value.busyClassName != null) return
        _uiState.update { it.copy(busyClassName = className) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("ComponentControlViewModel", "Component action failed for $className", e)
                sendFailure(e)
            } finally {
                _uiState.update { it.copy(busyClassName = null) }
            }
        }
    }

    private suspend fun sendFailure(e: Throwable) {
        _events.send(UiText.StringResource(R.string.error_format, e.message ?: ""))
    }
}
