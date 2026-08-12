// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import androidx.lifecycle.ViewModel
import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.MIN_PASSPHRASE_LENGTH
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
import com.valhalla.thor.presentation.launchGuarded
import com.valhalla.thor.presentation.settings.PassphraseError
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/**
 * How a finished job is reported once. Cleared by [AppBackupViewModel.dismissResult].
 *
 * Three arms and four sentences, mirroring `RestoreFinish` on the other half of this feature, because
 * the three ways a backup ends need three different things said about them: an enqueue that never
 * happened, a chain dependent WorkManager cancelled before `doWork`, and a worker that ran and
 * failed. All three used to render as *"Backup failed: it stopped without saying why"*, which is
 * false of the first two and unhelpfully vague about the third.
 */
sealed interface BackupFinish {

    /**
     * True where WorkManager **started** the job — it built the worker and was about to enter
     * `doWork`.
     *
     * Deliberately not "`doWork` executed a line", which nothing outside the worker can know:
     * `WorkerWrapper.trySetRunning()` writes RUNNING just *before* `startWork()`, and a FAILED row
     * can also come from a `WorkerFactory` that could not build the worker at all. Both gaps err
     * towards true, which is the safe direction here: it costs the user a look in the backup folder,
     * where the opposite would claim nothing was written by a run that did write.
     *
     * Abstract rather than defaulted, for the same reason `RestoreFinish.workerRan` is: a default
     * hands the safe-looking answer to whichever arm is added next.
     */
    val workerRan: Boolean

    data object Succeeded : BackupFinish {
        // SUCCEEDED is `Result.success()`, which only `doWork` returns.
        override val workerRan: Boolean get() = true
    }

    /**
     * @param reason the worker's own sentence, or null when the failure was decided before or by the
     *   enqueue — in which case [workerRan] is false and the screen says so instead of guessing.
     */
    data class Failed(val reason: String?, override val workerRan: Boolean) : BackupFinish

    /**
     * The job reached a terminal CANCELLED state.
     *
     * Not `Failed(null)`: nothing in Thor cancels a live job, so in practice this is the chain case —
     * every job is appended to one `APPEND_OR_REPLACE` chain and WorkManager cancels the dependents
     * of a prerequisite that returns `Result.failure()`, without ever calling `doWork`.
     *
     * @param workerRan true only where this watcher saw the job RUNNING before the cancel. A cancel
     *   is the one terminal state whose meaning cannot be read off the state itself, so it is read
     *   off the history instead.
     */
    data class Cancelled(override val workerRan: Boolean) : BackupFinish
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
    /**
     * At least one data class. The bundle on its own is **not** enough, however reasonable that
     * reads.
     *
     * `evaluateArchiveRestoreGate` refuses an empty selection unconditionally
     * (`ArchiveRestoreRefusal.NOTHING_SELECTED`), before it looks at the bundle or at
     * `installFirst` — and the restore screen hides every control behind `refusal == null`. So a
     * bundle-only `.thorbak` is a file Thor writes and then will not read: the restore screen shows
     * *"Nothing is selected."* over a screen with nothing selectable and no way forward, and it is
     * discovered at the moment the archive was the point. The gate is the authority on what is
     * restorable, and this side must not offer what it refuses.
     *
     * Widening this again means widening the gate first. Nothing else in the feature validates the
     * empty set — `ArchiveBackupRequest` has no `require`, and neither the use case nor the worker
     * checks it — so this expression is the whole of the guard.
     */
    val canStart: Boolean
        get() = supported == true && !running && selected.isNotEmpty()
}

/**
 * Why the sheet will not accept what is in its two passphrase fields, or null when it will.
 *
 * Here rather than in the composable because a rule expressed only in a `Button`'s `enabled` is a
 * rule no JVM test can reach: the same minimum lives in `PassphraseSettingsViewModel.save`, where it
 * *is* pinned on both sides, and the sheet's copy could be flipped from `>=` to `>` without reddening
 * anything. [PassphraseError] is reused rather than mirrored for the same reason — two enums for one
 * rule is a second place for it to drift.
 *
 * @param needed false when the vault will supply the passphrase and the fields are not drawn, in
 *   which case there is nothing to refuse.
 */
internal fun backupPassphraseRefusal(
    needed: Boolean,
    passphrase: String,
    confirmation: String,
): PassphraseError? = when {
    !needed -> null
    // Length before match, matching `PassphraseSettingsViewModel.save`: a user who has typed four
    // characters into both fields has a length problem, not a matching one, and naming the matching
    // one sends them to re-type an identical pair.
    passphrase.length < MIN_PASSPHRASE_LENGTH -> PassphraseError.TOO_SHORT
    passphrase != confirmation -> PassphraseError.MISMATCH
    else -> null
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

        // `launchGuarded`, not a bare `launch`, here and at every other site in this class: the
        // measurement runs a privileged shell, the label walks a `DocumentFile` tree and the vault
        // read touches DataStore, and an uncaught throw out of `viewModelScope` kills the process.
        // Each site passes the state that leaves the sheet usable rather than a silent swallow.
        launchGuarded(
            // Not `supported = null`, which is "still asking" and leaves the spinner turning for the
            // life of the sheet. A measurement that threw is a measurement Thor cannot make, which is
            // what `false` already means and what `backup_unsupported` already says.
            onFailure = { _uiState.update { it.copy(supported = false, selected = emptySet()) } }
        ) {
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
        //
        // No `onFailure`: the label is a caption. Left null it reads "Finding the backup folder…",
        // and the store resolves the destination for itself when the job runs, so a failure here
        // costs the caption and nothing else.
        launchGuarded {
            val label = archiveStore.currentTargetLabel()
            _uiState.update { it.copy(destinationLabel = label) }
        }
        launchGuarded(
            // §5.4's rule, applied to the read as well as to the unwrap: a vault Thor cannot consult
            // is a prompt, not a failure. Falling through to `false` would instead hide the field and
            // start a backup under a passphrase nothing supplied.
            onFailure = { _uiState.update { it.copy(passphraseNeeded = true) } }
        ) {
            val remembered = vault.isRemembered.first()
            _uiState.update { it.copy(passphraseNeeded = !remembered) }
        }
        // The rotation case: a job for this app may already be running, in which case this sheet is a
        // progress view rather than a form. No `onFailure`: nothing was claimed to the user yet, and
        // a sheet that failed to re-attach is a sheet showing its form, which is where it started.
        launchGuarded {
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
        // As in `start`: a caption that could not be re-read stays as it was, and the store still
        // resolves the real destination when the job runs.
        launchGuarded {
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
     *   before this function's work ends** — synchronously by the guard clause below, which is the
     *   one path that never starts a coroutine, and in a `finally` on every path the coroutine does
     *   reach.
     *
     *   One hole is left, and it cannot be closed from here: if this view model is cleared before the
     *   launched block is dispatched, the block and its `finally` never run. `AppBackupSheet` scopes
     *   the view model to its own composition with `rememberViewModelStoreOwner()`, so a dismissal
     *   landing in that window leaves the array intact. Stated rather than papered over, because the
     *   layer below cites this contract: `ThorJobLauncher.startBackup` documents that the caller owns
     *   the passphrase and that the callee will not clear it.
     * @param remember whether to cache [typed] in the vault. Ignored when [typed] is empty.
     */
    fun beginBackup(typed: CharArray, remember: Boolean) {
        val state = _uiState.value
        if (!state.canStart) {
            // A second tap in the frame before the Button recomposes disabled arrives here holding a
            // *fresh* array — `AppBackupSheet` builds one with `toCharArray()` on every click — so
            // this is a second live copy of the passphrase that nothing downstream will ever see and
            // nothing below will ever wipe. There is no state to change on this path; the wipe is the
            // whole of what the branch does.
            typed.fill(' ')
            return
        }
        // Cleared synchronously, before the coroutine that enqueues. Nothing is watching yet — the
        // passphrase recall and the enqueue both have to complete first — so for that whole window
        // this is the only thing that can take the previous run's bar down.
        _uiState.update { it.copy(running = true, queued = false, finished = null, progress = null) }

        launchGuarded(
            // The `catch` this function did without. `strings_backup.xml`'s
            // `passphrase_error_store_failed` tells the user in as many words that failing to cache a
            // passphrase is survivable, so ticking *Remember it on this device* must not be able to kill
            // the process on the condition the copy calls harmless.
            //
            // `PassphraseVault.remember` now catches its own store write and reports `false`, so that
            // particular throw no longer reaches here — this block ignores the Boolean, because a backup
            // whose passphrase merely failed to cache still succeeded. What is left for this guard is
            // everything else in the block: `recall()`'s unwrap, `startBackup`, and any future addition.
            // `workerRan = false` and a null reason: everything in this block happens before
            // `startBackup` returns an id, so no job exists and nothing was written.
            onFailure = {
                _uiState.update {
                    it.copy(
                        running = false,
                        queued = false,
                        finished = BackupFinish.Failed(reason = null, workerRan = false),
                    )
                }
            }
        ) {
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
                    return@launchGuarded
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
                    // `workerRan = false` is the whole point of the flag: `startBackup` handed back no
                    // id, so nothing was ever enqueued, no worker was constructed and no byte of the
                    // archive was written. The banner can say so outright instead of hedging.
                    _uiState.update {
                        it.copy(
                            running = false,
                            queued = false,
                            finished = BackupFinish.Failed(reason = null, workerRan = false),
                        )
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
        watching = launchGuarded(
            // A throw out of either collector would otherwise leave `running` true with no watcher
            // left to clear it: the bar sticks, Start stays disabled, and the only way out is killing
            // the app. `finish(null)` is the existing "terminal, nothing to say" path — the same one
            // the pruned-record case below uses — so it releases the watcher and re-enables Start
            // without claiming an archive was or was not written.
            onFailure = { finish(result = null) }
        ) {
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
            // `Gone` is a null `WorkInfo`, and a row that has not been written yet is null too:
            // `ThorJobLauncher` does not await `enqueue()`, so WorkManager writes the row on its own
            // executor after `startBackup` has returned and this collector has already subscribed.
            // Both readings arrive as the same value, and only the order tells them apart — a `Gone`
            // that arrives *before* the job has ever been seen alive is the not-yet-written one.
            //
            // Whether that ordering actually occurs is **unverified**: nothing prevents it, and
            // nothing on this branch has run on hardware. The guard is written for the case where it
            // does, and costs nothing where it does not.
            var seenLive = false
            // Separate from `seenLive`, which `Pending` also sets — a queued job has not started.
            // RUNNING is the closest thing to proof this sheet can hold that the worker reached the
            // device: `trySetRunning()` writes it once the worker is built and about to be started.
            // Same reasoning, same name, as `ArchiveRestoreViewModel.watch`.
            var seenRunning = false
            launcher.status(jobId).collect { status ->
                when (status) {
                    // Both are "the job exists and has not finished", which is why they share
                    // `running`. `queued` is the part that differs and the part the user can act on.
                    is ThorJobStatus.Pending -> {
                        seenLive = true
                        _uiState.update { it.copy(running = true, queued = true) }
                    }

                    is ThorJobStatus.Running -> {
                        seenLive = true
                        seenRunning = true
                        _uiState.update { it.copy(running = true, queued = false) }
                    }

                    is ThorJobStatus.Succeeded -> finish(BackupFinish.Succeeded)
                    // `workerRan = true` unconditionally rather than from `seenRunning`: every FAILED
                    // Thor produces is `doWork` returning `Result.failure()`, and a watcher that
                    // attached late can miss RUNNING but cannot make the run un-happen. The one FAILED
                    // that does not mean the worker ran is a `WorkerFactory` that could not build it;
                    // over-reported here on purpose, as on the restore side.
                    is ThorJobStatus.Failed ->
                        finish(BackupFinish.Failed(reason = status.reason, workerRan = true))

                    // The one terminal state that does not say for itself whether work happened, and
                    // the reason this stopped being a `Failed`. WorkManager cancels the dependents of
                    // a prerequisite that fails and every job is appended to one chain, so the common
                    // cancel is a backup queued behind a failing job that never entered `doWork` —
                    // "Thor did not start it" rather than "Thor tried and failed".
                    is ThorJobStatus.Cancelled -> finish(BackupFinish.Cancelled(workerRan = seenRunning))
                    // After the job has been seen alive: the record went away underneath a live
                    // watcher — WorkManager dropped a row this collector had already observed in a
                    // non-`Gone` state. Not the reattach path, despite how that reads: `runningJobFor`
                    // matches on `!state.isFinished`, so a job whose record is already gone hands back
                    // no id and no watcher is ever attached to it. Terminal like the three above, so it
                    // releases the watcher the same way; it just has no outcome to report.
                    //
                    // Before that: the row has not landed. Ignored rather than treated as terminal —
                    // finishing here would take the bar down a frame after the tap, re-enable Start
                    // and invite a duplicate enqueue. The residue if the row never lands at all is a
                    // sheet that keeps showing the bar; dismissing it clears this view model with the
                    // composition, so reopening asks WorkManager again from scratch.
                    is ThorJobStatus.Gone -> if (seenLive) finish(result = null)
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
     *   not a failure and it is not a success this sheet witnessed. Null here cannot erase a banner
     *   the user has not read yet — though not because this is the only writer of one. The other is
     *   [beginBackup]'s enqueue-failure branch, and a watcher *can* be alive while that branch's
     *   banner is up: the `runningJobFor` collector in [start] attaches one for a job this sheet did
     *   not enqueue, and it does not consult `canStart`. What makes the claim hold is ordering, not
     *   exclusivity. Every route to a live watcher clears the banner on the way in — [beginBackup]
     *   synchronously before it launches, [watch] before its first status arrives — so by the time
     *   any watcher reaches this line, the banner it might have erased is already gone.
     */
    private fun finish(result: BackupFinish?) {
        // `progress` is deliberately **not** cleared here, and it was tried. The last published
        // percentage is no longer true of anything, so nulling it looks like an improvement — but
        // `AppBackupSheet` reads `progress` only under `if (state.running)`, which this same update
        // sets false, so the change is invisible on screen. What it is not invisible to is the pair of
        // tests that use the surviving bar as their fixture: `a job picked up after another finished
        // inherits neither its bar nor its banner` and `a second backup does not open on the first
        // one's progress bar` both establish a stale bar here and then assert that the *next* job
        // clears it. Clearing it here makes that fixture unreachable and those two assertions
        // vacuous — it would delete the coverage of the reattach and `beginBackup` clears rather than
        // add any. Whoever tries this again: the bar is cleared by whatever starts the next job, on
        // purpose.
        _uiState.update { it.copy(running = false, queued = false, finished = result) }
        watching?.cancel()
        watching = null
    }
}
