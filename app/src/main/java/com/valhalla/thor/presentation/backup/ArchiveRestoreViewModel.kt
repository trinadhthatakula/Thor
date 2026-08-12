// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup

import androidx.lifecycle.ViewModel
import com.valhalla.thor.data.backup.DataArchiveCapabilityCache
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
import com.valhalla.thor.domain.repository.ArchiveOpenOutcome
import com.valhalla.thor.domain.repository.ArchiveSourceFactory
import com.valhalla.thor.domain.repository.ThorJobStatus
import com.valhalla.thor.domain.usecase.ArchiveHeaderOutcome
import com.valhalla.thor.domain.usecase.ArchiveUnlockOutcome
import com.valhalla.thor.domain.usecase.OpenArchiveUseCase
import com.valhalla.thor.domain.usecase.ReadInstalledAppFactsUseCase
import com.valhalla.thor.presentation.launchGuarded
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
 *
 * Two of them carry [workerRan], because the sentence a user needs turns on it and nothing else can
 * answer it later. A restore deletes each data class before it writes it, so *"your data may be
 * incomplete"* is the truth once the worker has run and a lie before it — and a screen that says it
 * anyway sends the user to re-run a destructive operation over a device nothing touched.
 */
sealed interface RestoreFinish {

    /**
     * True where the job was **started** — WorkManager built the worker and handed the job to it, so
     * the destructive phase may have been reached.
     *
     * Deliberately not "`doWork` executed a line", which nothing outside the worker can know:
     * `WorkerWrapper.trySetRunning()` writes RUNNING just *before* `startWork()`, and FAILED has one
     * reachable shape that never enters `doWork` at all (below). Both gaps err the same way — they can
     * make this true where the device was in fact untouched, never false where it was touched — and
     * that is the direction to be wrong in: the false-positive costs a user one needless re-run of a
     * restore, the false-negative leaves half-written data reported as "nothing changed".
     *
     * False means either "definitely not" — the enqueue never happened — or "not as far as this
     * screen saw", which is the honest reading of a cancel that arrived without a preceding `Running`.
     * The two are collapsed on purpose. The dominant unobserved case is a dependent cancelled inside
     * `THOR_JOB_CHAIN`, where `doWork` genuinely never runs, and the case this cannot see —
     * a process killed mid-restore — is not this screen's to report: §8.5's breadcrumb outlives the
     * process and says it on the next launch, which is why that mechanism exists.
     *
     * Abstract rather than defaulted to false: a default would hand the safe-looking answer to every
     * arm added later, and the arm added later is the one whose author has not read this KDoc.
     */
    val workerRan: Boolean

    /**
     * @param warnings what the run finished **in spite of** — a failed OBB placement, an archive with
     *   no game data in it, a breadcrumb that could not be written. Empty is the ordinary case.
     *   Carried on the success arm and not folded into [Failed] because the data did land: a user
     *   told "restore failed" runs it again, which destroys and rewrites data that is correct.
     */
    data class Succeeded(val warnings: List<String> = emptyList()) : RestoreFinish {
        // A SUCCEEDED `WorkInfo` is `Result.success()`, which only `doWork` returns. Nothing reads
        // this arm's copy off it — a success has its own sentence — but stating it keeps the
        // property's meaning uniform rather than "the field the failure arms use".
        override val workerRan: Boolean get() = true
    }

    /**
     * @param reason the worker's own sentence, or null when the failure came from WorkManager.
     * @param workerRan false for the three pre-flight failures in [ArchiveRestoreViewModel.beginRestore],
     *   which are decided before anything is enqueued; true for a `WorkInfo` that reached FAILED.
     *   `doWork` returning `Result.failure()` is the only shape **Thor** can produce, but not the only
     *   shape: `WorkerWrapper` also fails the row when the `WorkerFactory` cannot build the worker at
     *   all — reachable here, since Thor installs a Koin factory — and that never enters `doWork`. It is
     *   reported as "the device may have been touched" anyway; see [workerRan] for why that direction.
     */
    data class Failed(
        val reason: ArchiveRestoreMessage?,
        override val workerRan: Boolean,
    ) : RestoreFinish

    /**
     * The job reached a terminal CANCELLED state.
     *
     * Not `Failed(null)`, which renders as "it stopped without saying why" — the one thing that is
     * not true here. `ThorJobLauncher.cancel` has no call site, but that does not make this the chain
     * case: `ThorJobNotifications` puts a Cancel action on the ongoing notification built from
     * `WorkManager.createCancelPendingIntent`, which cancels the **work** and so can land here with a
     * worker mid-restore. The other route is the chain — every job is appended to `THOR_JOB_CHAIN`,
     * and WorkManager cancels the dependents of a prerequisite that returned `Result.failure()`
     * without ever calling `doWork`. The two differ in exactly one way that matters to a user, which
     * is whether the device was touched, and [workerRan] is what separates them.
     *
     * @param workerRan true when this watcher saw the job RUNNING before the cancel — the state
     *   WorkManager writes as it hands the job to a built worker, a line before `startWork()`. A cancel
     *   is the one terminal state whose damage cannot be read off the state itself, so it is read off
     *   the history instead.
     */
    data class Cancelled(override val workerRan: Boolean) : RestoreFinish
}

/**
 * A sentence for the user, in a form a view model is allowed to hold.
 *
 * This screen's failure copy has two origins and they cannot be represented the same way. Some of it
 * is written *here* — four fixed sentences this view model decides for itself — and some of it arrives
 * as free text from below, where the layer that produced it knows something this one does not
 * (`ArchiveHeaderOutcome.NotAnArchive.reason` names the entry it could not find;
 * `ArchiveUnlockOutcome.Unsupported.reason` names the cipher it does not implement; a `WorkInfo`
 * carries the worker's own sentence).
 *
 * The four written here used to be Kotlin string literals in this file. That is the shape the branch
 * already ruled against once: `PassphraseSettingsViewModel` exposes `PassphraseError` and lets
 * `PassphraseSettingsSheet` map it to `R`, so the view model stays free of Android resources and stays
 * JVM-testable. A literal is worse than untranslated — it is *invisible*, because lint cannot see
 * inside a Kotlin string, so when `strings_backup.xml` is translated these would have been the only
 * English left on the screen with nothing pointing at them.
 *
 * Free text keeps its own arm rather than being forced into an id, because inventing an id for it
 * would mean discarding the detail it exists to carry.
 */
sealed interface ArchiveRestoreMessage {

    /** Free text from a layer below the presentation one. Shown as it arrived. */
    data class FromBelow(val text: String) : ArchiveRestoreMessage

    /** One of this screen's own sentences. `ArchiveRestoreScreen` resolves it against `R`. */
    data class Known(val reason: ArchiveRestoreReason) : ArchiveRestoreMessage
}

/**
 * The sentences [ArchiveRestoreViewModel] decides for itself.
 *
 * Enumerable because all four are properties of a decision this class makes, not of anything it was
 * told. [ArchiveRestoreScreen] owns the mapping to `R`, in the same file and for the same reason
 * `refusalLabel` and `warningLabel` live there.
 */
enum class ArchiveRestoreReason {
    /**
     * `ArchiveSourceFactory` could not read the URI at all — no header was ever read.
     *
     * About the *access*, not the file: a revoked grant, an unmounted volume, no room in cache for
     * the fallback copy. The file it names may be a perfectly good backup, so the advice is to try
     * the same one again — which is exactly the wrong advice for [NOT_AN_ARCHIVE], and why the two
     * are not one reason.
     */
    FILE_UNREADABLE,

    /** The bytes were readable and are not a `.thorbak` — the ordinary "wrong file picked". */
    NOT_AN_ARCHIVE,

    /** The archive is intact; the key derived from what was typed does not open it. */
    WRONG_PASSPHRASE,

    /**
     * The check itself did not complete — the derivation or the read threw.
     *
     * Separate from [WRONG_PASSPHRASE] because it is not a claim about the passphrase. A failure to
     * *test* an answer is not the answer being wrong, and conflating them tells a user to change
     * something that may be correct.
     */
    UNLOCK_CHECK_FAILED,

    /** Unlocked earlier, but the passphrase is gone by the time Restore was pressed. */
    PASSPHRASE_LOST,

    /** The header's stored salt will not decode, so no key can be derived from it. */
    SALT_UNREADABLE,
}

data class ArchiveRestoreUiState(
    val loading: Boolean = false,
    /** The archive's own display name, for a message. Never a path. */
    val fileName: String? = null,
    val header: ArchiveHeader? = null,
    /** Why this file cannot be used at all — not a gate refusal, which needs a readable header. */
    val error: ArchiveRestoreMessage? = null,
    val supported: Boolean? = null,
    val refusal: ArchiveRestoreRefusal? = null,
    val warnings: List<ArchiveRestoreWarning> = emptyList(),
    val installFirst: Boolean = false,
    val selected: Set<DataClass> = emptySet(),
    /**
     * False when the archive holds no OBB, in which case the checkbox is not drawn at all — and
     * false on an **install-first** restore even when it does, because that path installs the
     * archive's `.xapk` and the install places the game data with it. `RestoreAppArchiveUseCase`
     * documents that `restoreObb` is not honoured there; a checkbox that changes nothing is worse
     * than no checkbox.
     */
    val obbOffered: Boolean = false,
    val restoreObb: Boolean = false,
    val passphraseNeeded: Boolean = false,
    val passphraseError: ArchiveRestoreMessage? = null,
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
    /**
     * The cache, never [AppDataProbe] directly.
     *
     * This screen was the one production call site of `probeDataArchiveCapability()` outside
     * [DataArchiveCapabilityCache], and it lost both of the properties that class exists to provide.
     * The gate: the cache returns false without probing when `PrivilegeState.hasAnyPrivilege` is
     * false, precisely so opening a `.thorbak` on a device where the user has granted nothing cannot
     * shell out and raise a `su` prompt. The memoisation: the answer is keyed on the whole privilege
     * state, so the round trip happens once rather than on every file the user opens.
     *
     * `MeasureAppDataUseCase` routes through the same object and its KDoc says why in as many words;
     * two test files carry comments asserting that this call site does not exist.
     */
    private val capability: DataArchiveCapabilityCache,
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

    /**
     * The in-flight header read, held so [open] can cancel it before starting another.
     *
     * Without it, two reads race and split the screen's identity in half: [uriString] is always the
     * newest pick, while `header` and everything derived from it belong to whichever read finished
     * last. A slow archive picked first and a small one picked during its load leaves the sheet
     * showing A's package, classes and unlocked passphrase while the request it builds carries B's
     * URI — a wrong request the user has no way to see. `AppArchiveWorker` refuses it on a package
     * mismatch before touching anything, so it is not a data-loss path, but the user is told a
     * restore may have left their data incomplete for a job that never got past the gate.
     */
    private var opening: Job? = null

    /**
     * Which pick the state currently belongs to. Incremented by every accepted [open].
     *
     * [opening]'s cancel is not enough on its own, because cancellation is cooperative and the work
     * being cancelled is blocking I/O this class does not own. Two ways a dead read still writes:
     * a `ContentResolver` copy or a PBKDF2 loop that finishes its blocking block before noticing,
     * and — the reachable one — a read that fails for a *real* reason at the moment it is cancelled.
     * The original exception wins over the cancellation, so `onFailure` runs, and it wrote
     * `loading = false` plus a `FILE_UNREADABLE` for archive A over archive B's fresh spinner. That
     * is worse than a stale sentence: `loading = false` re-enables both pick buttons while B is
     * still being read, which re-opens the very door [opening] closes.
     *
     * So the cancel stops the *work* and this stops the *writes*. Checked with [updateForOpen] at
     * every state write derived from a file, which is the whole of [open]'s block, its `onFailure`,
     * and [tryRememberedPassphrase] — the last one matters most, because it is what would hand
     * archive A's unlocked passphrase to a screen showing archive B.
     */
    private var openGeneration = 0

    init {
        // Not gated on a file being picked: the Settings entry point arrives with no URI, and after a
        // crash that is exactly how the user gets here.
        //
        // No `onFailure`: a breadcrumb that cannot be read is a banner that does not appear, which is
        // the state the screen is in for every user who has never had a restore interrupted. Guarded
        // all the same — this runs in the constructor, so an unguarded throw here would take the
        // process down as the screen opens, which is the least recoverable moment there is.
        launchGuarded {
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
     * of the screen's life once the first pick failed. Re-picking the *same* file after a failure is
     * the remaining half of that: see [forgetUriIfStill], which releases the URI on the two paths
     * whose own advice is to try it again.
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
        // Cancel-and-replace rather than `if (loading) return`: an early return would silently discard
        // the file the user just picked, which is the one thing they are certain they asked for.
        // Cancelling is also what keeps [uriString] and `header` describing the same archive — see
        // [opening]. `launchGuarded` rethrows CancellationException instead of routing it to
        // `onFailure`, so the cancelled read cannot write a failure over the new one's state.
        opening?.cancel()
        // Claimed before the reset below, so every write from here on is tagged with this pick and any
        // write still coming from the one before it is already stale. See [openGeneration].
        val generation = ++openGeneration
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

        opening = launchGuarded(
            // Without this the spinner set above is permanent: `loading = true` is written
            // synchronously and every path that clears it lives inside this block, so a throw from the
            // source factory or the header reader left a screen that span until the user backed out of
            // it. Reported as the file being unopenable, which is what a throw on this path means.
            onFailure = {
                forgetUriIfStill(uriString)
                updateForOpen(generation) {
                    it.copy(
                        loading = false,
                        error = ArchiveRestoreMessage.Known(ArchiveRestoreReason.FILE_UNREADABLE),
                    )
                }
            }
        ) {
            // The one write here that is *not* file-derived, and so not generation-guarded: whether the
            // device can restore private data at all is a property of the privilege state, identical for
            // every archive, and the cache behind it answers the same for both reads.
            val supported = capability.isSupported()
            _uiState.update { it.copy(supported = supported) }

            // The picker offers every file on the device, so "that is not a backup" is the ordinary
            // mistake and it gets its own sentence. The two failures point the user opposite ways:
            // one says pick a different file, the other says this one is fine, try it again.
            val source = when (val opened = sources.open(uriString)) {
                is ArchiveOpenOutcome.Opened -> opened.source
                ArchiveOpenOutcome.NotAnArchive -> {
                    updateForOpen(generation) {
                        it.copy(
                            loading = false,
                            error = ArchiveRestoreMessage.Known(ArchiveRestoreReason.NOT_AN_ARCHIVE),
                        )
                    }
                    return@launchGuarded
                }
                ArchiveOpenOutcome.Unreadable -> {
                    forgetUriIfStill(uriString)
                    updateForOpen(generation) {
                        it.copy(
                            loading = false,
                            error = ArchiveRestoreMessage.Known(ArchiveRestoreReason.FILE_UNREADABLE),
                        )
                    }
                    return@launchGuarded
                }
            }

            // Closed as soon as the header is out. Holding it open would hold a ParcelFileDescriptor
            // for as long as the screen lives, and the worker opens the container again anyway.
            val outcome = source.use { openArchive.readHeader(it) }
            val header = when (outcome) {
                is ArchiveHeaderOutcome.Read -> outcome.header
                is ArchiveHeaderOutcome.NotAnArchive -> {
                    // `FromBelow`: this sentence names the archive entry that was missing, which is
                    // detail only the reader has. An id here would throw it away.
                    updateForOpen(generation) {
                        it.copy(
                            loading = false,
                            error = ArchiveRestoreMessage.FromBelow(outcome.reason),
                        )
                    }
                    return@launchGuarded
                }
            }

            // Into a local, then the guard, then the field. `installed` is a plain field rather than
            // state, so [updateForOpen] cannot cover it and a write to it is not undone by bailing
            // afterwards — which is what an earlier version of this did. A dead read that publishes its
            // facts leaves the gate comparing the live header against the *previous* archive's app:
            // [evaluate] reads this field on every selection change, so the next `toggleClass` decides
            // signer and version against facts belonging to a file the user already replaced.
            val facts = installedFacts(header.packageName)
            if (generation != openGeneration) return@launchGuarded
            installed = facts
            val obbCount = header.appBundle?.obbCount ?: 0
            updateForOpen(generation) { state ->
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
            tryRememberedPassphrase(header, generation)
        }
    }

    /**
     * Release the remembered URI so [open] will look at that file again — but only if it is still the
     * file [open] is remembering.
     *
     * Only the two "try this one again" failures call this. `FILE_UNREADABLE` means the file may be a
     * perfectly good backup that a full cache volume or an offline provider could not be copied out
     * of, and [ArchiveRestoreReason] says as much; without this, [open]'s same-file guard turned the
     * one file the user wanted into a picker that did nothing. The two `NOT_AN_ARCHIVE` paths
     * deliberately do not call it: there the advice is to pick a *different* file, re-reading would
     * print the same sentence that is already on screen, and any different URI resets the field
     * anyway.
     *
     * Guarded on equality rather than nulled outright: a slow failure for file A must not clear a URI
     * that a later `open(B)` has since stored, which would let a second `open(B)` re-enter and restart
     * the gate under a loaded header. [open] does now cancel the read it replaces, so the ordinary
     * version of that race is gone — but cancellation is cooperative and this guard costs one
     * comparison, so it stays as the backstop for a failure that lands anyway. See [openGeneration].
     */
    private fun forgetUriIfStill(uriString: String) {
        if (this.uriString == uriString) this.uriString = null
    }

    /**
     * Write file-derived state, but only if [generation] is still the pick the user is looking at.
     *
     * See [openGeneration]. A no-op is the correct answer for a stale generation: the read that owns
     * the screen now has already written, or is about to, everything this one would have said.
     */
    private inline fun updateForOpen(
        generation: Int,
        block: (ArchiveRestoreUiState) -> ArchiveRestoreUiState,
    ) {
        if (generation != openGeneration) return
        _uiState.update(block)
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
        // No `onFailure`: a breadcrumb that would not clear leaves the banner up, which is the state
        // the user just asked to leave but is not a state they are stuck in — the store is asked again
        // on the next launch, and the banner's own button is still there to press.
        launchGuarded {
            breadcrumbs.clear()
            _uiState.update { it.copy(interrupted = null) }
        }
    }

    /**
     * @param typed the array the screen built from its text field. Owned by this view model from here
     *   on: kept on the accepted path, zeroed on both refused ones.
     */
    fun submitPassphrase(typed: CharArray) {
        val state = _uiState.value
        val header = state.header ?: run {
            // No header means no archive to test it against, and nothing downstream will ever see
            // this array. The wipe is the whole of what this branch does.
            typed.fill(' ')
            return
        }
        // The re-entry guard, and it is the reason `loading` below is not merely cosmetic. A
        // derivation takes 210,000 PBKDF2 iterations — comfortably over a second on a minSdk-28-era
        // device — and the screen clears its text field the moment Unlock is pressed. Retyping and
        // pressing again used to put two derivations in flight whose completion order decided whether
        // `unlocked` or `passphraseError` won, so the second attempt could overwrite the first
        // attempt's success with a stale refusal. This array is wiped rather than parked: nothing
        // downstream will see it.
        if (state.loading) {
            typed.fill(' ')
            return
        }
        // The spinner at the top of the screen is already wired to this flag and was never set here —
        // `open()` was its only writer, which is why the *automatic* unlock through
        // `tryRememberedPassphrase` showed progress and the manual one showed nothing at all. Set
        // synchronously, before the coroutine, so the frame that clears the text field is also the
        // frame that explains why.
        _uiState.update { it.copy(loading = true) }
        launchGuarded(
            // `loading` is cleared on every path below, including this one — a throw out of the key
            // derivation must not leave a spinner that never stops over a field the user cannot use.
            //
            // `UNLOCK_CHECK_FAILED`, deliberately not `WRONG_PASSPHRASE`: a throw is not evidence
            // about what was typed. `unlock` answers `WrongPassphrase` for that and `Unsupported` for
            // a cipher it does not implement, so anything reaching here is a third thing — and telling
            // a user their passphrase is wrong when it may well be right sends them to change a
            // passphrase that was never the problem.
            onFailure = {
                typed.fill(' ')
                _uiState.update {
                    it.copy(
                        loading = false,
                        unlocked = false,
                        passphraseNeeded = true,
                        passphraseError = ArchiveRestoreMessage.Known(
                            ArchiveRestoreReason.UNLOCK_CHECK_FAILED
                        ),
                    )
                }
            }
        ) {
            when (val outcome = openArchive.unlock(header, typed)) {
                // The key is discarded: this call is a yes/no answer. `ThorJobLauncher` derives the
                // real one, so there is one enqueue path rather than two.
                is ArchiveUnlockOutcome.Unlocked -> {
                    // The one already held is superseded, so it goes now rather than at `onCleared`.
                    wipePassphrase()
                    passphrase = typed
                    _uiState.update {
                        it.copy(
                            loading = false,
                            unlocked = true,
                            passphraseNeeded = false,
                            passphraseError = null,
                        )
                    }
                }

                is ArchiveUnlockOutcome.WrongPassphrase -> {
                    typed.fill(' ')
                    _uiState.update {
                        it.copy(
                            loading = false,
                            unlocked = false,
                            passphraseNeeded = true,
                            passphraseError = ArchiveRestoreMessage.Known(
                                ArchiveRestoreReason.WRONG_PASSPHRASE
                            ),
                        )
                    }
                }

                // A property of the archive, not of the passphrase, so it goes to `error` where the
                // screen shows it instead of blaming what the user typed. `FromBelow`, because the
                // sentence names the cipher or the KDF the archive asked for and this layer does not
                // know one from another.
                is ArchiveUnlockOutcome.Unsupported -> {
                    typed.fill(' ')
                    _uiState.update {
                        it.copy(
                            loading = false,
                            unlocked = false,
                            passphraseNeeded = false,
                            error = ArchiveRestoreMessage.FromBelow(outcome.reason),
                        )
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
            //
            // No public path reaches it. `wipePassphrase` has five callers and only one of them leaves
            // this field null while `unlocked` — which `canStart` above requires — is still true: `open`
            // and `useDifferentPassphrase` clear `unlocked` in the same breath, and the two unlock paths
            // (`submitPassphrase` accepting, the vault recall) assign the replacement on the next line.
            // The fifth is `onCleared`, which does leave the flag standing — but it is `protected`, so
            // it runs only after this view model is dead and nothing can press the button. Kept,
            // not deleted: without it this line is `!!` on a nullable field, i.e. a crash on a path
            // nobody can prove impossible from the call site.
            // `workerRan = false` here and at the two producers below: all three decide before
            // `startRestore`, so no job exists, and the screen must not tell a user whose device was
            // never touched that their data may be half-written.
            _uiState.update {
                it.copy(
                    unlocked = false,
                    passphraseNeeded = true,
                    finished = RestoreFinish.Failed(
                        reason = ArchiveRestoreMessage.Known(ArchiveRestoreReason.PASSPHRASE_LOST),
                        workerRan = false,
                    ),
                )
            }
            return
        }
        val salt = header.kdf.saltBytes() ?: run {
            // Also unreachable while `unlocked` gates this: `OpenArchiveUseCase.unlock` decodes the same
            // salt first and answers `Unsupported` when it cannot, so an archive whose salt is undecodable
            // never becomes unlocked. Same reason as above for keeping it — the alternative is `!!` on a
            // nullable header field, on the one screen where a crash mid-tap is least affordable.
            _uiState.update {
                it.copy(
                    finished = RestoreFinish.Failed(
                        reason = ArchiveRestoreMessage.Known(ArchiveRestoreReason.SALT_UNREADABLE),
                        workerRan = false,
                    )
                )
            }
            return
        }

        _uiState.update { it.copy(running = true, queued = false, finished = null) }
        launchGuarded(
            // `running` is set synchronously above and only this block can clear it, so an unguarded
            // throw out of `startRestore` left a permanently disabled Restore button with a bar over
            // it — a state the user cannot leave without killing the app. Reported exactly as the
            // enqueue-returned-null branch below reports itself, because it is the same fact: no job
            // exists, so nothing on the device was touched.
            onFailure = {
                _uiState.update {
                    it.copy(
                        running = false,
                        queued = false,
                        finished = RestoreFinish.Failed(reason = null, workerRan = false),
                    )
                }
            }
        ) {
            val request = ArchiveRestoreRequest(
                uriString = uri,
                packageName = header.packageName,
                classes = state.selected,
                restoreObb = state.restoreObb,
            )
            // `header.kdf.iterations`, never the default: this screen is the only place that holds the
            // archive's own count, and the launcher derives the job's key from what it is given here.
            val id = launcher.startRestore(request, key, salt, header.kdf.iterations)
            if (id == null) {
                // The enqueue itself threw; `ThorJobLauncher` has already dropped the key. There is no
                // job, so nothing ran.
                _uiState.update {
                    it.copy(
                        running = false,
                        queued = false,
                        finished = RestoreFinish.Failed(reason = null, workerRan = false),
                    )
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
                val obbCount = header.appBundle?.obbCount ?: 0
                it.copy(
                    refusal = null,
                    warnings = decision.warnings,
                    installFirst = decision.installFirst,
                    // Withdrawn on the install-first path, where the install places the game data
                    // itself and the flag reaches nothing. `restoreObb` is then set to what will
                    // actually happen, so the request Thor sends matches the app the user gets; on
                    // every other path the user's own choice is left alone.
                    obbOffered = obbCount > 0 && !decision.installFirst,
                    restoreObb = if (decision.installFirst) obbCount > 0 else it.restoreObb,
                )
            }

            is ArchiveRestoreDecision.Refused -> _uiState.update {
                // Warnings are cleared with the refusal: a refused restore has no warnings to heed,
                // and leaving them on screen reads as two problems where there is one.
                it.copy(refusal = decision.reason, warnings = emptyList(), installFirst = false)
            }
        }
    }

    /**
     * @param generation the pick this unlock belongs to, checked before every write. The derivation is
     *   210,000 PBKDF2 iterations, so this is the longest window on the screen for the user to pick a
     *   different file — and the only one whose stale write would hand a *key* to the wrong archive.
     */
    private suspend fun tryRememberedPassphrase(header: ArchiveHeader, generation: Int) {
        // First, and ahead of the vault: this gates the *prompt* as much as the derivation. Asking for
        // a passphrase beside a refusal suggests the passphrase is what went wrong, and there is no
        // passphrase that makes a signer mismatch restorable. The refusals that a later selection
        // change can clear (NOTHING_SELECTED, CLASS_NOT_IN_ARCHIVE) cannot be the answer here — every
        // class the archive holds is selected at this point.
        if (_uiState.value.refusal != null) return

        val stored = vault.recall()
        if (stored == null) {
            updateForOpen(generation) { it.copy(passphraseNeeded = true) }
            return
        }

        when (openArchive.unlock(header, stored)) {
            is ArchiveUnlockOutcome.Unlocked -> {
                // Generation checked before the key is parked, not just before the flag: `passphrase` is
                // what `restore()` hands the worker, so a stale write here is the one that would send
                // archive A's key with archive B's request. Zeroed rather than dropped — this array came
                // from the vault and nothing else holds it.
                if (generation != openGeneration) {
                    stored.fill(' ')
                    return
                }
                wipePassphrase()
                passphrase = stored
                updateForOpen(generation) { it.copy(unlocked = true, passphraseNeeded = false) }
            }
            // §5.4: the vault is a cache. This archive was made with a different passphrase, which is
            // an ordinary state and says nothing about the archive's health — so prompt, silently.
            // `stored` is this class's own array, from `recall()`; nothing else holds it.
            is ArchiveUnlockOutcome.WrongPassphrase -> {
                stored.fill(' ')
                updateForOpen(generation) { it.copy(passphraseNeeded = true) }
            }

            is ArchiveUnlockOutcome.Unsupported -> {
                stored.fill(' ')
                updateForOpen(generation) { it.copy(passphraseNeeded = true) }
            }
        }
    }

    private fun watchForExistingJob(packageName: String) {
        reattach?.cancel()
        // No `onFailure`: nothing has been claimed to the user by this point, and a screen that failed
        // to re-attach to someone else's job is a screen showing its form, which is where it started.
        reattach = launchGuarded {
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
        watching = launchGuarded(
            // Without this a throw out of either collector leaves `running` true with no watcher left
            // to clear it: the bar stays, Restore stays disabled, and the only way out is killing the
            // app. `finish(null)` is the existing "terminal, nothing to say" path, so it releases the
            // watcher without claiming the restore did or did not touch the device.
            onFailure = { finish(result = null) }
        ) {
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
            // Separate from `seenLive`, which is also set by `Pending` — a queued job has not been
            // started. RUNNING is the closest thing to proof this screen can hold that the worker
            // reached the device: `trySetRunning()` writes it once the worker has been built and is
            // about to be started, which is a line short of `doWork` and as near as an observer gets.
            var seenRunning = false
            launcher.status(jobId).collect { status ->
                when (status) {
                    is ThorJobStatus.Pending -> {
                        seenLive = true
                        _uiState.update { it.copy(running = true, queued = true) }
                    }

                    is ThorJobStatus.Running -> {
                        seenLive = true
                        seenRunning = true
                        _uiState.update { it.copy(running = true, queued = false) }
                    }

                    // `status.warnings`, not an empty list: a restore that placed the data but not
                    // the game data succeeds, and this is the only place that reaches the user.
                    is ThorJobStatus.Succeeded -> finish(RestoreFinish.Succeeded(status.warnings))
                    // `workerRan = true` unconditionally, and not from `seenRunning`: every shape of
                    // FAILED that Thor itself produces is `doWork` returning `Result.failure()`, and a
                    // watcher that attached late can miss RUNNING but cannot make the run un-happen.
                    // The one FAILED that does not mean the worker ran is a `WorkerFactory` that could
                    // not build it; that is over-reported here on purpose (see `RestoreFinish.workerRan`).
                    is ThorJobStatus.Failed ->
                        finish(
                            RestoreFinish.Failed(
                                // The worker's own sentence, which is why it is `FromBelow`: it names
                                // the class or the step that failed, and nothing here could re-derive
                                // that from an id. Null when WorkManager failed the row itself.
                                reason = status.reason?.let(ArchiveRestoreMessage::FromBelow),
                                workerRan = true,
                            )
                        )

                    // The one terminal state that does not say for itself whether work happened.
                    // Unobserved RUNNING is read as "it did not run": the common cancel is a chain
                    // dependent, which never enters `doWork`, and §8.5's breadcrumb — not this
                    // screen — is what reports a restore whose process died while it was live.
                    is ThorJobStatus.Cancelled ->
                        finish(RestoreFinish.Cancelled(workerRan = seenRunning))
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
