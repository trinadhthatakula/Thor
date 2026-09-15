// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.MissingFreezeProfilesException
import com.valhalla.thor.domain.model.ProfileAssignmentResult
import com.valhalla.thor.domain.model.freezeTier
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.FreezeProfileRepository
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

data class ProfileAssignmentUiState(
    val profiles: List<FreezeProfile> = emptyList(),
    val profilesLoadFailed: Boolean = false,
    val isOpen: Boolean = false,
    val selectedApps: List<AppInfo> = emptyList(),
    val selectedProfileIds: Set<Long> = emptySet(),
    val isSaving: Boolean = false,
    val error: UiText? = null,
    val skippedCount: Int = 0,
    val expertApps: List<AppInfo> = emptyList(),
)

sealed interface ProfileAssignmentEvent {
    data class Assigned(
        val result: ProfileAssignmentResult,
        val skippedCount: Int,
    ) : ProfileAssignmentEvent
}

/** Owns an assignment draft; it never changes watchlist membership or runs a freeze. */
@KoinViewModel
class ProfileAssignmentViewModel(
    private val freezeProfileRepository: FreezeProfileRepository,
    private val appRepository: AppRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileAssignmentUiState())
    val uiState: StateFlow<ProfileAssignmentUiState> = _uiState.asStateFlow()

    private val _events = Channel<ProfileAssignmentEvent>(Channel.BUFFERED)
    val events: Flow<ProfileAssignmentEvent> = _events.receiveAsFlow()

    private var profilesJob: Job? = null

    init {
        retryProfiles()
    }

    fun retryProfiles() {
        profilesJob?.cancel()
        _uiState.update {
            it.copy(
                profilesLoadFailed = false,
                error = if (it.profilesLoadFailed) null else it.error,
            )
        }
        profilesJob = viewModelScope.launch {
            try {
                freezeProfileRepository.observeProfiles()
                    .flowOn(ioDispatcher)
                    .collect { profiles ->
                        val availableIds = profiles.mapTo(mutableSetOf()) { it.id }
                        _uiState.update { state ->
                            val retainedIds = state.selectedProfileIds intersect availableIds
                            val removedSelection = retainedIds != state.selectedProfileIds
                            state.copy(
                                profiles = profiles,
                                profilesLoadFailed = false,
                                selectedProfileIds = retainedIds,
                                expertApps = if (removedSelection) emptyList() else state.expertApps,
                                error = if (removedSelection && state.isOpen) {
                                    UiText.StringResource(R.string.profile_assignment_profiles_changed)
                                } else state.error,
                            )
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logger.e(TAG, "Observing assignment profiles failed", error)
                _uiState.update {
                    it.copy(
                        profilesLoadFailed = true,
                        error = UiText.StringResource(R.string.error_profiles_load_failed),
                    )
                }
            }
        }
    }

    fun open(apps: List<AppInfo>) {
        if (_uiState.value.isSaving || apps.isEmpty()) return
        // Copy the source list: changes to screen selection must not mutate an open draft.
        val snapshot = apps.distinctBy { it.packageName }
        _uiState.update {
            it.copy(
                isOpen = true,
                selectedApps = snapshot,
                selectedProfileIds = emptySet(),
                error = if (it.profilesLoadFailed) {
                    UiText.StringResource(R.string.error_profiles_load_failed)
                } else null,
                skippedCount = 0,
                expertApps = emptyList(),
            )
        }
    }

    fun toggleProfile(id: Long) {
        _uiState.update { state ->
            if (!state.isOpen || state.isSaving || state.profiles.none { it.id == id }) state
            else state.copy(
                selectedProfileIds = if (id in state.selectedProfileIds) {
                    state.selectedProfileIds - id
                } else state.selectedProfileIds + id,
                expertApps = emptyList(),
                error = if (state.profilesLoadFailed) state.error else null,
            )
        }
    }

    fun dismiss() {
        _uiState.update { state ->
            if (state.isSaving) state
            else state.copy(
                isOpen = false,
                selectedApps = emptyList(),
                selectedProfileIds = emptySet(),
                expertApps = emptyList(),
                skippedCount = 0,
                error = null,
            )
        }
    }

    fun dismissExpertConfirmation() {
        _uiState.update { if (it.isSaving) it else it.copy(expertApps = emptyList()) }
    }

    fun submit(confirmExperts: Boolean = false) {
        val draft = _uiState.value
        if (!draft.isOpen || draft.isSaving || draft.profilesLoadFailed || draft.selectedApps.isEmpty() ||
            draft.selectedProfileIds.isEmpty()
        ) return

        // A true flag only confirms apps that the user has actually been shown. Revalidation may
        // discover a newly expert app while the dialog was open; that app needs a fresh warning.
        val confirmedPackages = if (confirmExperts) {
            draft.expertApps.mapTo(mutableSetOf()) { it.packageName }
        } else emptySet()

        // Set before launching so two taps in the same frame cannot enqueue two writes.
        _uiState.update {
            it.copy(isSaving = true, error = null, skippedCount = 0, expertApps = emptyList())
        }
        viewModelScope.launch {
            try {
                val eligible = withContext(ioDispatcher) {
                    draft.selectedApps.mapNotNull { selected ->
                        // MATCH_UNINSTALLED_PACKAGES-backed details can resolve recoverable frozen
                        // apps with isInstalled=false. A successful lookup is sufficient here.
                        appRepository.getAppDetails(selected.packageName)
                            ?.takeUnless { it.freezeTier == FreezeTier.BLOCKED }
                    }
                }
                val skippedCount = draft.selectedApps.size - eligible.size
                val experts = eligible.filter { it.freezeTier == FreezeTier.EXPERT }
                _uiState.update { it.copy(skippedCount = skippedCount) }

                val availableIds = _uiState.value.profiles.mapTo(mutableSetOf()) { it.id }
                if (!availableIds.containsAll(draft.selectedProfileIds)) {
                    _uiState.update {
                        it.copy(error = UiText.StringResource(R.string.profile_assignment_profiles_changed))
                    }
                    return@launch
                }
                if (eligible.isEmpty()) {
                    _uiState.update {
                        it.copy(error = UiText.StringResource(R.string.profile_assignment_no_eligible_apps))
                    }
                    return@launch
                }
                if (experts.any { it.packageName !in confirmedPackages }) {
                    _uiState.update { it.copy(expertApps = experts) }
                    return@launch
                }

                // The repository checks the original target set again inside its transaction;
                // deletion can still race the observed list after the check above.
                val result = withContext(ioDispatcher) {
                    freezeProfileRepository.addApps(
                        profileIds = draft.selectedProfileIds,
                        packageNames = eligible.mapTo(linkedSetOf()) { it.packageName },
                    )
                }
                _uiState.update {
                    it.copy(
                        isOpen = false,
                        selectedApps = emptyList(),
                        selectedProfileIds = emptySet(),
                        expertApps = emptyList(),
                        error = null,
                    )
                }
                _events.send(ProfileAssignmentEvent.Assigned(result, skippedCount))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (missing: MissingFreezeProfilesException) {
                Logger.e(TAG, "Assignment targets were deleted", missing)
                _uiState.update {
                    it.copy(
                        profiles = it.profiles.filterNot { profile -> profile.id in missing.profileIds },
                        selectedProfileIds = it.selectedProfileIds - missing.profileIds,
                        error = UiText.StringResource(R.string.profile_assignment_profiles_changed),
                    )
                }
            } catch (error: Exception) {
                Logger.e(TAG, "Adding selected apps to profiles failed", error)
                _uiState.update {
                    it.copy(error = UiText.StringResource(R.string.profile_assignment_save_failed))
                }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private companion object {
        const val TAG = "ProfileAssignment"
    }
}
