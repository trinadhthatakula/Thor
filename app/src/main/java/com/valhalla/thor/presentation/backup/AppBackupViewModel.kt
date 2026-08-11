// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.PassphraseVault
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.repository.AppArchiveStore
import com.valhalla.thor.domain.repository.ArchiveJobLauncher
import com.valhalla.thor.domain.repository.ThorJobStatus
import com.valhalla.thor.domain.usecase.MeasureAppDataUseCase
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/** How a finished job is reported once. Cleared by [AppBackupViewModel.dismissResult]. */
sealed interface BackupFinish {
    data object Succeeded : BackupFinish
    data class Failed(val reason: String?) : BackupFinish
}

data class AppBackupUiState(
    val packageName: String = "",
    val appLabel: String = "",
    /**
     * Null while the capability probe is in flight; false only once Thor has asked and cannot.
     *
     * Three states, not two, for the same reason [DataClassSize] has three: a "not supported" panel
     * shown for one frame on every open is a lie the user reads before the truth arrives.
     */
    val supported: Boolean? = null,
    val sizes: Map<DataClass, DataClassSize> = emptyMap(),
    val selected: Set<DataClass> = emptySet(),
    val includeBundle: Boolean = true,
    /** Null until the store answers. Never a hardcoded "Downloads". */
    val destinationLabel: String? = null,
    val passphraseNeeded: Boolean = false,
    val progress: ThorJobProgress? = null,
    val running: Boolean = false,
    val finished: BackupFinish? = null,
) {
    /** The bundle alone is a valid backup, as is data alone. Nothing at all is not. */
    val canStart: Boolean
        get() = supported == true && !running && (selected.isNotEmpty() || includeBundle)
}

@KoinViewModel
class AppBackupViewModel(
    private val measure: MeasureAppDataUseCase,
    private val archiveStore: AppArchiveStore,
    private val vault: PassphraseVault,
    // Injected for `newSalt()` and nothing else. The cipher is pure JCE with no Android and no Thor
    // types, and one fresh salt per archive is the invariant that keeps one reused passphrase from
    // meaning one reused key — so the generator belongs at the site that builds the request, not
    // somewhere it can be forgotten.
    private val cipher: AppArchiveCipher,
    private val launcher: ArchiveJobLauncher,
    private val registry: JobRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppBackupUiState())
    val uiState = _uiState.asStateFlow()

    private var started = false
    private var watching: Job? = null

    /** Idempotent: `LaunchedEffect` re-runs after a configuration change and `du` is not cheap. */
    fun start(packageName: String, appLabel: String) {
        if (started) return
        started = true
        _uiState.update { it.copy(packageName = packageName, appLabel = appLabel) }

        viewModelScope.launch {
            val measurement = measure(packageName)
            _uiState.update { state ->
                state.copy(
                    supported = measurement.supported,
                    sizes = measurement.sizes,
                    // §4.2: all default on. Including a class whose size is Undetermined — that is a
                    // failed measurement, not an empty directory, and dropping it would narrow the
                    // backup without saying so.
                    selected = if (measurement.supported) DataClass.entries.toSet() else emptySet(),
                )
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(destinationLabel = archiveStore.currentTargetLabel()) }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(passphraseNeeded = !vault.isRemembered.first()) }
        }
        // The rotation case: a job for this app may already be running, in which case this sheet is a
        // progress view rather than a form.
        viewModelScope.launch {
            launcher.runningJobFor(ThorJobKind.ARCHIVE_BACKUP, packageName).collect { id ->
                if (id != null && watching == null) watch(id)
            }
        }
    }

    fun toggleClass(dataClass: DataClass) = _uiState.update { state ->
        state.copy(
            selected = if (dataClass in state.selected) {
                state.selected - dataClass
            } else {
                state.selected + dataClass
            }
        )
    }

    fun setIncludeBundle(include: Boolean) = _uiState.update { it.copy(includeBundle = include) }

    /** §10's "use a different passphrase" affordance. Shows the field even with a filled vault. */
    fun useDifferentPassphrase() = _uiState.update { it.copy(passphraseNeeded = true) }

    /**
     * Clear the finished banner and the bar under it.
     *
     * Deliberately does **not** clear [AppBackupUiState.running]: dismissing a *result* must not
     * claim a job stopped. A second backup only becomes startable when the job itself reports a
     * terminal state.
     */
    fun dismissResult() = _uiState.update { it.copy(finished = null, progress = null) }

    /**
     * @param typed what the user entered, or an empty array when the field was not shown.
     * @param remember whether to cache [typed] in the vault. Ignored when [typed] is empty.
     */
    fun beginBackup(typed: CharArray, remember: Boolean) {
        val state = _uiState.value
        if (!state.canStart) return
        // `progress` is cleared with `finished`: the bar left over from the previous run belongs to a
        // job that is over, and leaving it up would show this one starting at whatever percentage the
        // last one stopped at until its first publish arrives.
        _uiState.update { it.copy(running = true, finished = null, progress = null) }

        viewModelScope.launch {
            // An empty array means the field was not shown, so the vault is the source. A vault that
            // cannot be unwrapped is a *prompt*, not a failure: the archive would be perfectly
            // readable, it is the convenience layer that broke (§5.4).
            val passphrase = typed.takeIf { it.isNotEmpty() } ?: vault.recall()
            if (passphrase == null || passphrase.isEmpty()) {
                _uiState.update { it.copy(running = false, passphraseNeeded = true) }
                return@launch
            }
            if (remember && typed.isNotEmpty()) vault.remember(typed)

            val request = ArchiveBackupRequest(
                packageName = state.packageName,
                classes = state.selected,
                includeBundle = state.includeBundle,
                salt = cipher.newSalt(),
            )
            val id = launcher.startBackup(request, passphrase)
            if (id == null) {
                _uiState.update {
                    it.copy(running = false, finished = BackupFinish.Failed(null))
                }
            } else {
                watch(id)
            }
        }
    }

    /**
     * Follow one job: its published progress and its WorkManager state, until it settles.
     *
     * Only one at a time — a sheet shows one app's backup — so an earlier watcher is cancelled here
     * rather than left collecting a job whose result nothing will read.
     */
    private fun watch(jobId: UUID) {
        watching?.cancel()
        watching = viewModelScope.launch {
            _uiState.update { it.copy(running = true) }
            launch {
                // Filtered, not assigned. `progressOf` mints a `MutableStateFlow(null)` for an id
                // nothing has published for, so a collector that subscribes first would otherwise
                // assign that null over whatever the state already holds.
                //
                // Narrower than it looks, and worth stating precisely rather than repeating the
                // usual claim: `JobRegistry.clear` *removes* the entry, it does not null it, so a
                // collector already attached to that flow never sees a null when a job ends. The
                // null this guards against is only ever an initial value.
                registry.progressOf(jobId).collect { progress ->
                    if (progress != null) _uiState.update { it.copy(progress = progress) }
                }
            }
            launcher.status(jobId).collect { status ->
                when (status) {
                    is ThorJobStatus.Pending, is ThorJobStatus.Running ->
                        _uiState.update { it.copy(running = true) }

                    is ThorJobStatus.Succeeded -> finish(BackupFinish.Succeeded)
                    is ThorJobStatus.Failed -> finish(BackupFinish.Failed(status.reason))
                    is ThorJobStatus.Cancelled -> finish(BackupFinish.Failed(null))
                    // Reached when a finished job's record has been pruned — which is what a
                    // reattach after a long absence sees. Not a failure to report.
                    is ThorJobStatus.Gone -> _uiState.update { it.copy(running = false) }
                }
            }
        }
    }

    /**
     * Record the outcome and stop watching.
     *
     * **This cancels the coroutine it is called from.** That is intended — the status flow never
     * completes on its own — but it means nothing placed after the `collect` in [watch] would run,
     * so cleanup does not belong there. The `progressOf` child collector is cancelled with it.
     */
    private fun finish(result: BackupFinish) {
        _uiState.update { it.copy(running = false, finished = result) }
        watching?.cancel()
        watching = null
    }
}
