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
    /**
     * The job exists but WorkManager has not started it — `ENQUEUED` or `BLOCKED`, both of which
     * [ThorJobStatus.Pending] carries.
     *
     * Separate from [running] rather than folded into it because the two look identical on screen
     * otherwise: `beginUniqueWork(…, APPEND_OR_REPLACE, …)` appends behind whatever is already in the
     * chain, so a backup queued behind a long restore shows the same indeterminate bar as one that is
     * actively writing, for as long as the other job takes.
     *
     * Copy driven by this flag must not promise a run. A dependent is cancelled when its prerequisite
     * returns `Result.failure()`, which is the [ThorJobStatus.Cancelled] arm below.
     */
    val queued: Boolean = false,
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
        // Both reads are hoisted out of their `update` lambdas, matching `measure(packageName)` above.
        // `update` is an inline compare-and-set retry loop, so a lost CAS re-runs the lambda — and
        // these two lambdas would re-run a `DocumentFile` tree walk and a DataStore read inside it.
        // Four coroutines here all write `_uiState`, so losing a CAS is ordinary, not exotic.
        viewModelScope.launch {
            val label = archiveStore.currentTargetLabel()
            _uiState.update { it.copy(destinationLabel = label) }
        }
        viewModelScope.launch {
            val remembered = vault.isRemembered.first()
            _uiState.update { it.copy(passphraseNeeded = !remembered) }
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

    /**
     * Re-read the destination after the user has picked a new folder.
     *
     * [start] reads the label once, behind a one-shot guard, so nothing else would ever ask again —
     * and the sheet would keep naming the old folder while the archive landed in the new one. The
     * label is the one thing on this sheet the user just deliberately changed, so it is the worst
     * place to be stale.
     *
     * Call it *after* the preference write has returned: [AppArchiveStore.currentTargetLabel] resolves
     * against `exportDirUri` at call time, so an earlier call answers with the old folder.
     * `ExportBottomSheet` does the same two steps in the same order.
     */
    fun refreshDestination() {
        viewModelScope.launch {
            val label = archiveStore.currentTargetLabel()
            _uiState.update { it.copy(destinationLabel = label) }
        }
    }

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
     * @param typed what the user entered, or an empty array when the field was not shown. **Zeroed
     *   before this returns to the caller's coroutine**, on every path — see the `finally` below.
     * @param remember whether to cache [typed] in the vault. Ignored when [typed] is empty.
     */
    fun beginBackup(typed: CharArray, remember: Boolean) {
        val state = _uiState.value
        if (!state.canStart) return
        // Cleared synchronously, before the coroutine that enqueues. Nothing is watching yet — the
        // passphrase recall and the enqueue both have to complete first — so for that whole window
        // this is the only thing that can take the previous run's bar down.
        _uiState.update { it.copy(running = true, queued = false, finished = null, progress = null) }

        viewModelScope.launch {
            // An empty array means the field was not shown, so the vault is the source. A vault that
            // cannot be unwrapped is a *prompt*, not a failure: the archive would be perfectly
            // readable, it is the convenience layer that broke (§5.4).
            //
            // Read before the `try` because `recall()` only runs when nothing was typed — so if it
            // throws, `typed` is the empty array and there is no key material to leave behind.
            val recalled = if (typed.isEmpty()) vault.recall() else null
            try {
                val passphrase = if (typed.isNotEmpty()) typed else recalled
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
                        it.copy(running = false, queued = false, finished = BackupFinish.Failed(null))
                    }
                } else {
                    watch(id)
                }
            } finally {
                // `ThorJobLauncher.startBackup` states that the caller owns the array and that the
                // callee will not clear it. This is that caller, and this is the whole of that
                // ownership: every layer below pays real complexity to keep key material short-lived
                // (`PassphraseVault` encodes through a `CharBuffer` to avoid minting a String,
                // `ArchiveKeyHolder` expires derived keys after an hour) and none of it means anything
                // if the passphrase is simply dropped in the clear here.
                //
                // In the `finally` so the early return above and any throw are covered too. `recalled`
                // is this class's own array — nothing else holds a reference to it. `typed` came from
                // the sheet, where it was a Compose `String` a moment earlier, so zeroing it narrows
                // the window rather than closing it.
                recalled?.fill(' ')
                typed.fill(' ')
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
            // The invariant, and it is this function's job because both callers reach here:
            // `beginBackup`, and the `runningJobFor` collector in `start`, which picks up a job this
            // sheet did not enqueue. **Nothing from job A may be on screen once a watcher attaches
            // to job B.** Only `beginBackup` clears anything of its own, so the reattach path starts
            // from whatever `finish` left — which is the banner, still up, and `watching` nulled.
            // The banner is cleared here; the bar is the collector's, below.
            //
            // `running` is set ahead of the first status rather than waiting for it: WorkManager's
            // flow is not guaranteed to answer in the same frame, and a Start button over a job that
            // is already writing invites a second one.
            _uiState.update { it.copy(running = true, finished = null) }
            launch {
                // Assigned, never filtered. The registry is keyed by job id, so this flow holds this
                // job's progress and nothing else's — including the null that a job which has not
                // published yet correctly has. Filtering that null was how job A's bar ended up
                // sitting over job B's work: `progressOf` mints a fresh `MutableStateFlow(null)`,
                // the filter dropped it, and the state kept whatever the last job left behind.
                registry.progressOf(jobId).collect { progress ->
                    _uiState.update { it.copy(progress = progress) }
                }
            }
            launcher.status(jobId).collect { status ->
                when (status) {
                    // Both are "the job exists and has not finished", which is why they share
                    // `running`. `queued` is the part that differs and the part the user can act on.
                    is ThorJobStatus.Pending -> _uiState.update {
                        it.copy(running = true, queued = true)
                    }

                    is ThorJobStatus.Running -> _uiState.update {
                        it.copy(running = true, queued = false)
                    }

                    is ThorJobStatus.Succeeded -> finish(BackupFinish.Succeeded)
                    is ThorJobStatus.Failed -> finish(BackupFinish.Failed(status.reason))
                    // WorkManager cancels the dependents of a prerequisite that fails, and every job
                    // is appended to one chain — so a backup queued behind a failing job lands here
                    // with `doWork` never called. Reporting it as anything but a failure would tell
                    // the user an archive exists when none was written.
                    is ThorJobStatus.Cancelled -> finish(BackupFinish.Failed(null))
                    // Reached when a finished job's record has been pruned — which is what a
                    // reattach after a long absence sees. Terminal like the three above, so it
                    // releases the watcher the same way; it just has no outcome to report.
                    is ThorJobStatus.Gone -> finish(result = null)
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
     *
     * @param result null for a terminal state with nothing to say: a pruned job is over, but it is
     *   not a failure and it is not a success this sheet witnessed. Writing null cannot erase an
     *   earlier banner, because [finish] is the only thing that ever sets one and it takes the
     *   watcher down with it — so no watcher is ever alive while [AppBackupUiState.finished] is set.
     */
    private fun finish(result: BackupFinish?) {
        _uiState.update { it.copy(running = false, queued = false, finished = result) }
        watching?.cancel()
        watching = null
    }
}
