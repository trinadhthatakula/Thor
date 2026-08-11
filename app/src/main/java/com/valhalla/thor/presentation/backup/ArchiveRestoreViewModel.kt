// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.backup.PassphraseVault
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveRestoreDecision
import com.valhalla.thor.domain.model.ArchiveRestoreRefusal
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.ArchiveRestoreWarning
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.InstalledAppFacts
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.evaluateArchiveRestoreGate
import com.valhalla.thor.domain.model.saltBytes
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.domain.repository.ArchiveJobLauncher
import com.valhalla.thor.domain.repository.ArchiveSourceFactory
import com.valhalla.thor.domain.repository.ThorJobStatus
import com.valhalla.thor.domain.usecase.ArchiveHeaderOutcome
import com.valhalla.thor.domain.usecase.ArchiveUnlockOutcome
import com.valhalla.thor.domain.usecase.OpenArchiveUseCase
import com.valhalla.thor.domain.usecase.ReadInstalledAppFactsUseCase
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/**
 * How a restore ended, as the screen reports it.
 *
 * Its own type rather than Task 16's `BackupFinish`, which is structurally similar: a restore
 * reporting through a type named *Backup* is the kind of thing that reads fine in a diff and wrong in
 * a stack trace. The three arms are three different sentences, which is the reason there are three.
 */
sealed interface RestoreFinish {

    /**
     * @param warnings what the run finished **in spite of** — a failed OBB placement, an archive with
     *   no game data in it, a breadcrumb that could not be written. Empty is the ordinary case.
     *   Carried on the success arm and not folded into [Failed] because the data did land: a user
     *   told "restore failed" runs it again, which destroys and rewrites data that is correct.
     */
    data class Succeeded(val warnings: List<String> = emptyList()) : RestoreFinish

    /** @param reason the worker's own sentence, or null when the failure came from WorkManager. */
    data class Failed(val reason: String?) : RestoreFinish

    /**
     * The job reached a terminal CANCELLED state.
     *
     * Not `Failed(null)`, which renders as "it stopped without saying why" — the one thing that is
     * not true here. Nothing in Thor calls `cancel`, so in practice this is always the chain case:
     * every job is appended to `THOR_JOB_CHAIN`, and WorkManager cancels the dependents of a
     * prerequisite that returns `Result.failure()`. `doWork` was never called.
     */
    data object Cancelled : RestoreFinish
}

data class ArchiveRestoreUiState(
    val loading: Boolean = false,
    /** The archive's own display name, for a message. Never a path. */
    val fileName: String? = null,
    val header: ArchiveHeader? = null,
    /** Why this file cannot be used at all — not a gate refusal, which needs a readable header. */
    val error: String? = null,
    val supported: Boolean? = null,
    val refusal: ArchiveRestoreRefusal? = null,
    val warnings: List<ArchiveRestoreWarning> = emptyList(),
    val installFirst: Boolean = false,
    val selected: Set<DataClass> = emptySet(),
    /** False when the archive holds no OBB, in which case the checkbox is not drawn at all. */
    val obbOffered: Boolean = false,
    val restoreObb: Boolean = false,
    val passphraseNeeded: Boolean = false,
    val passphraseError: String? = null,
    val unlocked: Boolean = false,
    /** The user has read what "replace" means and agreed to it. */
    val confirmed: Boolean = false,
    val progress: ThorJobProgress? = null,
    val running: Boolean = false,
    /**
     * The job is enqueued behind another one and has not started.
     *
     * Distinct from [running] because it is the part the user can act on, and because copy driven by
     * it must not promise a run: a dependent whose prerequisite fails is cancelled without `doWork`
     * ever being called.
     */
    val queued: Boolean = false,
    val finished: RestoreFinish? = null,
    /** §8.5: a restore that never finished, from this launch or an earlier one. */
    val interrupted: ArchiveBreadcrumb? = null,
) {
    val canStart: Boolean
        get() = supported == true &&
            header != null &&
            refusal == null &&
            unlocked &&
            confirmed &&
            !running
}

/**
 * §10's restore screen.
 *
 * Reads the header, runs §8.1's gate, and re-runs it on every selection change — a warning that only
 * appears once the destructive step has begun is not a warning. Nothing here writes to the device:
 * the button hands a request to [ArchiveJobLauncher] and the worker does the work.
 */
// `internal` is forced, not chosen: `ReadInstalledAppFactsUseCase` is internal because
// `AppDataArchiveGateway` is, and a public class cannot take it. `:app` is one module, so every caller
// this needs — the screen, `MainScreen` — can still see it.
@KoinViewModel
internal class ArchiveRestoreViewModel(
    private val sources: ArchiveSourceFactory,
    private val openArchive: OpenArchiveUseCase,
    private val probe: AppDataProbe,
    private val installedFacts: ReadInstalledAppFactsUseCase,
    private val vault: PassphraseVault,
    private val launcher: ArchiveJobLauncher,
    private val registry: JobRegistry,
    private val breadcrumbs: ArchiveBreadcrumbStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArchiveRestoreUiState())
    val uiState = _uiState.asStateFlow()

    private var installed: InstalledAppFacts? = null

    /**
     * The passphrase that opened this archive, held from [submitPassphrase] until this view model is
     * cleared.
     *
     * It has to survive that gap: unlike a backup, a restore is unlocked in one interaction and
     * started in another. Three paths stop holding it and all three zero it first — a replacement in
     * [submitPassphrase] or [tryRememberedPassphrase], [useDifferentPassphrase], and [open] with a
     * different file — and [onCleared] zeroes whatever is left. A *rejected* attempt never reaches
     * this field; the array the user typed is zeroed where it was refused and this one is untouched,
     * which is what lets a wrong second guess leave a working screen behind.
     *
     * Deliberately **not** zeroed after [beginRestore] hands it over: an enqueue that returns null
     * leaves the user on a usable screen, and a wiped array would make the retry derive a key from
     * spaces and report a wrong passphrase the user never typed.
     */
    private var passphrase: CharArray? = null
    private var uriString: String? = null
    private var watching: Job? = null
    private var reattach: Job? = null

    init {
        // Not gated on a file being picked: the Settings entry point arrives with no URI, and after a
        // crash that is exactly how the user gets here.
        viewModelScope.launch {
            breadcrumbs.read()?.let { crumb -> _uiState.update { it.copy(interrupted = crumb) } }
        }
    }

    /**
     * Read [uriString]'s header and run the gate against it.
     *
     * Idempotent **per file**, not once per view model. `LaunchedEffect(uriString)` and a
     * recomposition can both call this with the URI already open, and re-reading would restart the
     * gate under the user; but the screen's own file picker calls it with a *different* URI, and a
     * flat "already opened" guard turned "choose a file" into a button that did nothing for the rest
     * of the screen's life once the first pick failed.
     *
     * Refused while a job is running: switching archives under a live watcher would leave job A's
     * progress on job B's screen.
     */
    fun open(uriString: String) {
        if (uriString == this.uriString) return
        if (_uiState.value.running) return
        this.uriString = uriString
        // The previous file's answers are not this file's. Cleared here rather than merged, because
        // every field below is derived from the header and a stale one is worse than an empty one.
        // Four fields are left alone. `interrupted` and `supported` because neither is a property of
        // the file; `running` and `queued` because the guard above already returned if a job is live,
        // and `queued` is only ever set alongside `running` and cleared with it.
        wipePassphrase()
        reattach?.cancel()
        reattach = null
        _uiState.update {
            it.copy(
                loading = true,
                error = null,
                fileName = null,
                header = null,
                refusal = null,
                warnings = emptyList(),
                installFirst = false,
                selected = emptySet(),
                obbOffered = false,
                restoreObb = false,
                passphraseNeeded = false,
                passphraseError = null,
                unlocked = false,
                confirmed = false,
                progress = null,
                finished = null,
            )
        }

        viewModelScope.launch {
            val supported = probe.probeDataArchiveCapability()
            _uiState.update { it.copy(supported = supported) }

            val source = sources.open(uriString)
            if (source == null) {
                _uiState.update {
                    it.copy(loading = false, error = "Thor could not open that file")
                }
                return@launch
            }

            // Closed as soon as the header is out. Holding it open would hold a ParcelFileDescriptor
            // for as long as the screen lives, and the worker opens the container again anyway.
            val outcome = source.use { openArchive.readHeader(it) }
            val header = when (outcome) {
                is ArchiveHeaderOutcome.Read -> outcome.header
                is ArchiveHeaderOutcome.NotAnArchive -> {
                    _uiState.update { it.copy(loading = false, error = outcome.reason) }
                    return@launch
                }
            }

            installed = installedFacts(header.packageName)
            val obbCount = header.appBundle?.obbCount ?: 0
            _uiState.update { state ->
                state.copy(
                    loading = false,
                    fileName = source.displayName,
                    header = header,
                    // Everything the archive holds, selected. The user narrows from there.
                    selected = header.heldClasses().toSet(),
                    obbOffered = obbCount > 0,
                    restoreObb = obbCount > 0,
                )
            }
            evaluate()
            watchForExistingJob(header.packageName)
            tryRememberedPassphrase(header)
        }
    }

    fun toggleClass(dataClass: DataClass) {
        _uiState.update { state ->
            state.copy(
                selected = if (dataClass in state.selected) {
                    state.selected - dataClass
                } else {
                    state.selected + dataClass
                }
            )
        }
        evaluate()
    }

    fun setRestoreObb(restore: Boolean) = _uiState.update { it.copy(restoreObb = restore) }

    fun setConfirmed(confirmed: Boolean) = _uiState.update { it.copy(confirmed = confirmed) }

    fun useDifferentPassphrase() {
        // Wiped, not merely forgotten: this is the one interaction that says "the passphrase I gave
        // you is not the one I meant", and leaving it in the heap is the opposite of that.
        wipePassphrase()
        _uiState.update { it.copy(passphraseNeeded = true, unlocked = false, passphraseError = null) }
    }

    /**
     * Clear the outcome banner.
     *
     * Deliberately does **not** clear [ArchiveRestoreUiState.running]: dismissing a *result* must not
     * claim a job stopped.
     */
    fun dismissResult() = _uiState.update { it.copy(finished = null, progress = null) }

    fun acknowledgeInterruption() {
        viewModelScope.launch {
            breadcrumbs.clear()
            _uiState.update { it.copy(interrupted = null) }
        }
    }

    /**
     * @param typed the array the screen built from its text field. Owned by this view model from here
     *   on: kept on the accepted path, zeroed on both refused ones.
     */
    fun submitPassphrase(typed: CharArray) {
        val header = _uiState.value.header ?: run {
            // No header means no archive to test it against, and nothing downstream will ever see
            // this array. The wipe is the whole of what this branch does.
            typed.fill(' ')
            return
        }
        viewModelScope.launch {
            when (val outcome = openArchive.unlock(header, typed)) {
                // The key is discarded: this call is a yes/no answer. `ThorJobLauncher` derives the
                // real one, so there is one enqueue path rather than two.
                is ArchiveUnlockOutcome.Unlocked -> {
                    // The one already held is superseded, so it goes now rather than at `onCleared`.
                    wipePassphrase()
                    passphrase = typed
                    _uiState.update {
                        it.copy(unlocked = true, passphraseNeeded = false, passphraseError = null)
                    }
                }

                is ArchiveUnlockOutcome.WrongPassphrase -> {
                    typed.fill(' ')
                    _uiState.update {
                        it.copy(
                            unlocked = false,
                            passphraseNeeded = true,
                            passphraseError = "that passphrase does not open this backup",
                        )
                    }
                }

                // A property of the archive, not of the passphrase, so it goes to `error` where the
                // screen shows it instead of blaming what the user typed.
                is ArchiveUnlockOutcome.Unsupported -> {
                    typed.fill(' ')
                    _uiState.update {
                        it.copy(unlocked = false, passphraseNeeded = false, error = outcome.reason)
                    }
                }
            }
        }
    }

    fun beginRestore() {
        // Every read hoisted out of the `update` lambdas below: `MutableStateFlow.update` is a
        // compare-and-set retry loop, so its lambda can run more than once.
        val state = _uiState.value
        if (!state.canStart) return
        val header = state.header ?: return
        val uri = uriString ?: return
        val key = passphrase ?: run {
            // Reachable only if the passphrase was dropped between unlocking and pressing the button.
            // Silently returning would leave a Restore button that does nothing.
            _uiState.update {
                it.copy(
                    unlocked = false,
                    passphraseNeeded = true,
                    finished = RestoreFinish.Failed(
                        "Thor no longer has the passphrase for this backup — unlock it again"
                    ),
                )
            }
            return
        }
        val salt = header.kdf.saltBytes() ?: run {
            _uiState.update {
                it.copy(finished = RestoreFinish.Failed("this archive's salt could not be read"))
            }
            return
        }

        _uiState.update { it.copy(running = true, queued = false, finished = null) }
        viewModelScope.launch {
            val request = ArchiveRestoreRequest(
                uriString = uri,
                packageName = header.packageName,
                classes = state.selected,
                restoreObb = state.restoreObb,
            )
            val id = launcher.startRestore(request, key, salt)
            if (id == null) {
                _uiState.update {
                    it.copy(running = false, queued = false, finished = RestoreFinish.Failed(null))
                }
            } else {
                watch(id)
            }
        }
    }

    /** §8.1 as the screen sees it. Called on open and after every selection change. */
    private fun evaluate() {
        val header = _uiState.value.header ?: return
        when (val decision = evaluateArchiveRestoreGate(header, installed, _uiState.value.selected)) {
            is ArchiveRestoreDecision.Allowed -> _uiState.update {
                it.copy(
                    refusal = null,
                    warnings = decision.warnings,
                    installFirst = decision.installFirst,
                )
            }

            is ArchiveRestoreDecision.Refused -> _uiState.update {
                // Warnings are cleared with the refusal: a refused restore has no warnings to heed,
                // and leaving them on screen reads as two problems where there is one.
                it.copy(refusal = decision.reason, warnings = emptyList(), installFirst = false)
            }
        }
    }

    private suspend fun tryRememberedPassphrase(header: ArchiveHeader) {
        // First, and ahead of the vault: this gates the *prompt* as much as the derivation. Asking for
        // a passphrase beside a refusal suggests the passphrase is what went wrong, and there is no
        // passphrase that makes a signer mismatch restorable. The refusals that a later selection
        // change can clear (NOTHING_SELECTED, CLASS_NOT_IN_ARCHIVE) cannot be the answer here — every
        // class the archive holds is selected at this point.
        if (_uiState.value.refusal != null) return

        val stored = vault.recall()
        if (stored == null) {
            _uiState.update { it.copy(passphraseNeeded = true) }
            return
        }

        when (openArchive.unlock(header, stored)) {
            is ArchiveUnlockOutcome.Unlocked -> {
                wipePassphrase()
                passphrase = stored
                _uiState.update { it.copy(unlocked = true, passphraseNeeded = false) }
            }
            // §5.4: the vault is a cache. This archive was made with a different passphrase, which is
            // an ordinary state and says nothing about the archive's health — so prompt, silently.
            // `stored` is this class's own array, from `recall()`; nothing else holds it.
            is ArchiveUnlockOutcome.WrongPassphrase -> {
                stored.fill(' ')
                _uiState.update { it.copy(passphraseNeeded = true) }
            }

            is ArchiveUnlockOutcome.Unsupported -> {
                stored.fill(' ')
                _uiState.update { it.copy(passphraseNeeded = true) }
            }
        }
    }

    private fun watchForExistingJob(packageName: String) {
        reattach?.cancel()
        reattach = viewModelScope.launch {
            launcher.runningJobFor(ThorJobKind.ARCHIVE_RESTORE, packageName).collect { id ->
                if (id != null && watching == null) watch(id)
            }
        }
    }

    /**
     * Follow one job: its published progress and its WorkManager state, until it settles.
     *
     * Only one at a time — the screen shows one archive — so an earlier watcher is cancelled here
     * rather than left collecting a job whose result nothing will read.
     */
    private fun watch(jobId: UUID) {
        watching?.cancel()
        watching = viewModelScope.launch {
            // `running` ahead of the first status rather than waiting for it: WorkManager's flow is
            // not guaranteed to answer in the same frame, and a Restore button over a job that is
            // already writing invites a second one.
            _uiState.update { it.copy(running = true, finished = null) }
            launch {
                // Assigned, never filtered. `progressOf` mints a fresh `MutableStateFlow(null)` for
                // an id that has published nothing, and dropping that null is how the previous job's
                // bar ends up sitting over this one's work.
                registry.progressOf(jobId).collect { progress ->
                    _uiState.update { it.copy(progress = progress) }
                }
            }
            // `Gone` is a null `WorkInfo`, and a row WorkManager has not written yet is null too:
            // `ThorJobLauncher` does not await `enqueue()`. Only the order tells the two apart, so a
            // `Gone` before the job has ever been seen alive is ignored rather than reported.
            var seenLive = false
            launcher.status(jobId).collect { status ->
                when (status) {
                    is ThorJobStatus.Pending -> {
                        seenLive = true
                        _uiState.update { it.copy(running = true, queued = true) }
                    }

                    is ThorJobStatus.Running -> {
                        seenLive = true
                        _uiState.update { it.copy(running = true, queued = false) }
                    }

                    // `status.warnings`, not an empty list: a restore that placed the data but not
                    // the game data succeeds, and this is the only place that reaches the user.
                    is ThorJobStatus.Succeeded -> finish(RestoreFinish.Succeeded(status.warnings))
                    is ThorJobStatus.Failed -> finish(RestoreFinish.Failed(status.reason))
                    is ThorJobStatus.Cancelled -> finish(RestoreFinish.Cancelled)
                    // After the job has been seen alive, the record went away underneath a live
                    // watcher: terminal, with no outcome to report. Before that, the row has simply
                    // not landed — finishing there would take the bar down a frame after the tap and
                    // invite a duplicate enqueue.
                    is ThorJobStatus.Gone -> if (seenLive) finish(result = null)
                }
            }
        }
    }

    /**
     * Record the outcome and stop watching.
     *
     * **This cancels the coroutine it is called from.** Intended — the status flow never completes on
     * its own — but it means nothing placed after the `collect` in [watch] would run, so cleanup does
     * not belong there. The `progressOf` child collector is cancelled with it.
     *
     * @param result null for a terminal state with nothing to say: a pruned job is over, but it is
     *   neither a failure nor a success this screen witnessed.
     */
    private fun finish(result: RestoreFinish?) {
        _uiState.update { it.copy(running = false, queued = false, finished = result) }
        watching?.cancel()
        watching = null
    }

    private fun wipePassphrase() {
        passphrase?.fill(' ')
        passphrase = null
    }

    /**
     * The last line of the passphrase's lifetime.
     *
     * A restore holds its passphrase across two interactions, so unlike `AppBackupViewModel` there is
     * no single function whose `finally` can own the wipe. This is that owner. It runs when the
     * screen leaves the back stack for good, which is the point at which nothing can need the array
     * again.
     */
    override fun onCleared() {
        wipePassphrase()
    }
}
