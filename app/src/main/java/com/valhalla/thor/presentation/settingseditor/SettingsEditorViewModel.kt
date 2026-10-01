// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.presentation.settingseditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.settingseditor.SettingsEditorRepository
import com.valhalla.thor.data.settingseditor.SettingsEditorStore
import com.valhalla.thor.data.settingseditor.SettingsExecutionUncertain
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

internal data class SettingsEditorUiState(
    val view: SettingsEditorView = SettingsEditorView.SYSTEM,
    val entries: List<SettingEntry> = emptyList(),
    val history: List<SettingsEditRecord> = emptyList(),
    val consent: Boolean? = null,
    val mode: PrivilegeMode? = null,
    val busy: Boolean = false,
    val error: Boolean = false,
    val conflict: Boolean = false,
    val executionUnconfirmed: Boolean = false,
    val outcome: SettingsEditOutcome? = null,
    val consentWriteFailed: Boolean = false,
)
@KoinViewModel
class SettingsEditorViewModel(
    private val repository: SettingsEditorRepository,
    private val store: SettingsEditorStore,
    privileges: PrivilegeStateProvider,
    preferences: PreferenceRepository,
) : ViewModel() {
    private val state = MutableStateFlow(SettingsEditorUiState())
    internal val uiState = state.asStateFlow()
    private var readJob: Job? = null
    private var mutationRunning = false
    init {
        viewModelScope.launch {
            combine(privileges.state, preferences.userPreferences, store.consent) { privilege, preference, consent ->
                settingsEditorMode(privilege, preference.preferredPrivilegeMode) to consent
            }.collect { (mode, consent) ->
                val previous = state.value
                state.update { it.copy(mode = mode, consent = consent) }
                if (mode == null) {
                    readJob?.cancel()
                    state.update { it.copy(entries = emptyList(), busy = mutationRunning) }
                } else if (previous.mode != mode || previous.consent != consent) refresh()
            }
        }
    }
    fun acceptConsent() {
        if (state.value.busy) return
        state.update { it.copy(busy = true, consentWriteFailed = false) }
        viewModelScope.launch {
            val result = store.acceptConsent()
            state.update { it.copy(busy = false, consentWriteFailed = result.isFailure) }
        }
    }
    fun select(view: SettingsEditorView) {
        if (state.value.busy) return
        state.update { it.copy(view = view, entries = emptyList(), outcome = null) }
        refresh()
    }
    fun refresh() {
        if (mutationRunning || state.value.mode == null) return
        readJob?.cancel()
        val view = state.value.view
        state.update { it.copy(busy = true, error = false, conflict = false, executionUnconfirmed = false) }
        readJob = viewModelScope.launch {
            val entries = repository.read(view)
            val history = repository.history()
            state.update { it.copy(entries = entries.getOrDefault(emptyList()), history = history.getOrDefault(emptyList()), busy = false, error = entries.isFailure || history.isFailure) }
        }
    }
    fun change(key: String, expected: SettingValue, desired: SettingValue) = mutate {
        repository.change(state.value.view, key, expected, desired)
    }
    fun undo(id: String) = mutate { repository.undo(id) }
    private fun mutate(operation: suspend () -> Result<SettingsEditRecord>) {
        if (state.value.busy || state.value.mode == null || state.value.consent != true) return
        readJob?.cancel()
        mutationRunning = true
        state.update { it.copy(busy = true, error = false, conflict = false, executionUnconfirmed = false, outcome = null) }
        viewModelScope.launch {
            try {
                val result = operation()
                val entries = repository.read(state.value.view)
                val history = repository.history()
                state.update {
                    it.copy(entries = if (it.mode == null) emptyList() else entries.getOrDefault(it.entries),
                        history = history.getOrDefault(it.history), busy = false,
                        outcome = result.getOrNull()?.outcome,
                        executionUnconfirmed = result.exceptionOrNull() is SettingsExecutionUncertain,
                        conflict = result.exceptionOrNull()?.message == "conflict",
                        error = result.isFailure && result.exceptionOrNull()?.message != "conflict" || entries.isFailure || history.isFailure)
                }
            } finally { mutationRunning = false }
        }
    }
}
