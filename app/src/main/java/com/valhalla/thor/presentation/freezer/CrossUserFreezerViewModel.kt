// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.data.freezer.CrossUserFreezer
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.FreezerUser
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepSpec
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import com.valhalla.thor.domain.model.ScopedFreezerApp
import com.valhalla.thor.domain.model.normalizeSweepTargets
import com.valhalla.thor.domain.repository.PrivilegeSweepController
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

internal data class CrossUserFreezerState(
    val users: List<FreezerUser> = emptyList(),
    val selected: FreezerUser? = null,
    val apps: List<ScopedFreezerApp> = emptyList(),
    val watchlist: Set<String> = emptySet(),
    val profiles: List<FreezeProfile> = emptyList(),
    val requests: List<PrivilegeSweepStatus> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val message: Int? = null,
)

@KoinViewModel
internal class CrossUserFreezerViewModel(
    private val freezer: CrossUserFreezer,
    private val sweeps: PrivilegeSweepController,
) : ViewModel() {
    private val mutable = MutableStateFlow(CrossUserFreezerState())
    val state = mutable.asStateFlow()
    private var discovery: Job? = null
    private var loading: Job? = null
    private var savingOperation: Any? = null

    init {
        viewModelScope.launch { freezer.privilegeState.collect { discover() } }
        viewModelScope.launch {
            sweeps.activeRequests.collect { requests ->
                val scoped = requests.filter { it.targetUserSerial != null }
                val previous = state.value
                mutable.update { it.copy(requests = scoped) }
                val serial = previous.selected?.serial
                if (serial != null && scoped.any { request ->
                    request.targetUserSerial == serial && request.phase !in pendingPhases &&
                        previous.requests.none { it.requestId == request.requestId && it.phase == request.phase }
                }) {
                    if (scoped.any { it.targetUserSerial == serial && it.failed > 0 &&
                            previous.requests.none { old -> old.requestId == it.requestId && old.phase == it.phase } }) {
                        mutable.update { it.copy(message = R.string.freezer_user_action_failed) }
                    }
                    reload()
                }
            }
        }
    }

    fun discover() {
        if (discovery?.isActive == true) return
        discovery = viewModelScope.launch {
            val users = try {
                freezer.users()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Logger.w("CrossUserFreezer", "User discovery unavailable: ${failure.javaClass.simpleName}")
                emptyList()
            }
            val selected = state.value.selected
            val stillAvailable = selected?.let { user -> users.firstOrNull { it.serial == user.serial && it.id == user.id } }
            if (selected != null && stillAvailable == null) {
                select(null)
                mutable.update { it.copy(users = users, message = R.string.freezer_user_unavailable) }
            } else {
                mutable.update { it.copy(users = users, selected = stillAvailable) }
            }
        }
    }

    fun select(user: FreezerUser?) {
        if (user != null && state.value.users.none { it.id == user.id && it.serial == user.serial }) return
        loading?.cancel()
        mutable.update { CrossUserFreezerState(it.users, user, requests = it.requests, saving = it.saving) }
        reload()
    }

    fun reload() {
        val user = state.value.selected ?: return
        loading?.cancel()
        loading = viewModelScope.launch {
            mutable.update { it.copy(loading = true) }
            try {
                val apps = freezer.apps(user)
                val watchlist = freezer.watchlist(user)
                val profiles = freezer.profiles(user)
                if (state.value.selected?.serial == user.serial) {
                    mutable.update { it.copy(apps = apps, watchlist = watchlist, profiles = profiles, loading = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Logger.e("CrossUserFreezer", "Could not load selected user's app list", failure)
                mutable.update { it.copy(loading = false, message = R.string.freezer_user_action_failed) }
                discover()
            }
        }
    }

    fun run(user: FreezerUser, names: List<String>, operation: PrivilegeSweepOperation, add: Boolean) =
        launchFor(user) {
            val result = sweeps.launch(
                PrivilegeSweepSpec(
                    operation = operation,
                    packageNames = normalizeSweepTargets(names),
                    freezerMode = if (operation == PrivilegeSweepOperation.FREEZE) FreezerMode.FREEZE else null,
                    userId = user.id,
                    targetUserSerial = user.serial,
                    targetUserName = user.name.trim().ifBlank { null },
                    source = PrivilegeSweepSource.FREEZER,
                    addToFreezer = add,
                ),
            )
            check(result is PrivilegeSweepLaunchResult.Accepted)
        }

    fun save(user: FreezerUser, profile: FreezeProfile, onSuccess: () -> Unit) = launchFor(user) {
        freezer.saveProfile(user, profile)
        reload()
        if (state.value.selected?.serial == user.serial) onSuccess()
    }

    fun delete(user: FreezerUser, id: Long) = launchFor(user) {
        freezer.deleteProfile(user, id)
        reload()
    }

    fun remove(user: FreezerUser, app: ScopedFreezerApp) = launchFor(user) {
        freezer.removeFromWatchlist(user, app)
        reload()
    }

    fun consumeMessage() = mutable.update { it.copy(message = null) }

    private fun launchFor(user: FreezerUser, action: suspend () -> Unit) {
        val snapshot = state.value
        if (savingOperation != null || snapshot.selected?.serial != user.serial) return
        val operation = Any()
        savingOperation = operation
        mutable.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Logger.e("CrossUserFreezer", "Could not apply scoped Freezer action", failure)
                if (state.value.selected?.serial == user.serial) {
                    mutable.update { it.copy(message = R.string.freezer_user_action_failed) }
                    discover()
                }
            } finally {
                if (savingOperation === operation) {
                    savingOperation = null
                    mutable.update { it.copy(saving = false) }
                }
            }
        }
    }

    private companion object {
        val pendingPhases = setOf(PrivilegeSweepPhase.QUEUED, PrivilegeSweepPhase.RUNNING)
    }
}
