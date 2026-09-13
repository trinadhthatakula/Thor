// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import androidx.lifecycle.ViewModel
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.repository.ExportJobLauncher
import com.valhalla.thor.presentation.common.JobFinish
import com.valhalla.thor.presentation.common.JobPhase
import com.valhalla.thor.presentation.common.reduce
import com.valhalla.thor.presentation.launchGuarded
import com.valhalla.thor.presentation.navigation.TaskNavigationTargets
import com.valhalla.thor.presentation.queue.ProvisionalTaskIdentity
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/**
 * The job half of the export sheet: enqueue one, follow it, and say how it went.
 *
 * Deliberately *only* the job. Format, destination label and the OBB probe stay in `ExportBottomSheet`
 * where they already were — they are inputs the user is still editing, they survive nothing, and
 * moving them here would have made this commit a rewrite of the sheet rather than a change of what
 * the Export button does.
 *
 * [ExportBottomSheet] resolves the destination while the sheet is open and passes the exact URI selected
 * at tap time here. The process-owned [ExportSubmissionCoordinator] owns durable submission and navigation
 * so they survive this sheet's short-lived view model. Session resolution may clear a saved-folder
 * preference whose grant has gone; that write belongs to the visible export interaction, not to a worker
 * re-run tomorrow. The resolved target travels in the request, and `AppExportWorker` reads no preference.
 */
@KoinViewModel
class ExportViewModel(
    private val submissionCoordinator: ExportSubmissionCoordinator,
    private val launcher: ExportJobLauncher,
    private val registry: JobRegistry,
    private val taskNavigationTargets: TaskNavigationTargets,
) : ViewModel() {

    private val _phase = MutableStateFlow(JobPhase())
    val phase: StateFlow<JobPhase> = _phase.asStateFlow()

    private val _canSubmit = MutableStateFlow(false)
    val canSubmit: StateFlow<Boolean> = _canSubmit.asStateFlow()

    private var watching: Job? = null
    private var runningJobCollector: Job? = null
    private var submissionObserver: Job? = null
    private var attachedTo: String? = null

    /**
     * Pick up an export of [packageName] that is already running, if there is one.
     *
     * Not a nicety. The sheet invites the user to walk away mid-export, and the way back in is App
     * Info → Export — which without this shows a fresh Export button over a live job. Tapping it
     * appends a *second* export of the same app to the chain, and both write into the same staging
     * directory: `ExportSession`'s own KDoc describes what that does to the zip.
     *
     * Idempotent per package, because the sheet calls it from a `LaunchedEffect` that a recomposition
     * can re-run.
     */
    fun attach(packageName: String) {
        if (attachedTo == packageName) return
        if (attachedTo != null) {
            runningJobCollector?.cancel()
            runningJobCollector = null
            submissionObserver?.cancel()
            submissionObserver = null
            stopWatching()
            _phase.value = JobPhase()
        }
        _canSubmit.value = false
        attachedTo = packageName
        runningJobCollector = launchGuarded {
            launcher.runningJobFor(ThorJobKind.APP_EXPORT, packageName).collect { id ->
                if (attachedTo != packageName) return@collect
                _canSubmit.value = id == null
                // Guarded rather than unconditional: this flow re-emits the same id, and re-watching
                // would restart the collector — and with it the `finished = null` clear in [watch] —
                // over a job that has already reported its outcome.
                //
                // Liveness, not nullness. [watch]'s `onFailure` settles the phase but cannot null the
                // field its own `launchGuarded` is still being assigned into, so a collector that
                // throws parks a *completed* Job here; `== null` would then refuse to reattach for the
                // rest of this sheet's life. A live watcher keeps `isActive` true, so every state that
                // has one behaves exactly as before.
                if (id != null && watching?.isActive != true) watch(id)
            }
        }
    }

    /**
     * Enqueue an export and follow it.
     *
     * @param label the app's label as it reads right now. Display-only, and resolved on this side
     *   because the worker reads it on the `setForeground` deadline path.
     */
    fun start(
        packageName: String,
        label: String,
        format: BundleFormat,
        treeUri: String?,
    ) {
        // A second tap in the frame before the button leaves the composition. The chain would accept
        // it — `APPEND_OR_REPLACE` appends rather than refusing — so nothing below would catch it.
        if (_phase.value.running || attachedTo != packageName || !_canSubmit.compareAndSet(true, false)) {
            return
        }
        val taskId = UUID.randomUUID()
        taskNavigationTargets.requestOpenProvisional(
            taskId = taskId,
            identity = ProvisionalTaskIdentity(
                queueKind = TaskQueueKind.DATA,
                operationId = DataTaskKind.APP_EXPORT.name,
            ),
        )
        // Cleared synchronously, before the coroutine. Nothing is watching yet, so for that whole
        // window this is the only thing that can take the previous run's banner down.
        _phase.value = JobPhase(running = true)

        val submission = submissionCoordinator.submit(taskId, packageName, label, format, treeUri)
        submissionObserver = launchGuarded(onFailure = { notStarted() }) {
            val acceptedTaskId = submission.await()
            if (acceptedTaskId == null) {
                notStarted()
            } else {
                watch(acceptedTaskId)
            }
        }
    }

    /** Clear the outcome banner, putting the form back. The failure paths' only way out. */
    fun dismissResult() = _phase.update { it.copy(finished = null) }

    /**
     * Follow one job: its published progress and its WorkManager state, until it settles.
     *
     * Only one at a time — a sheet shows one app's export — so an earlier watcher is cancelled here
     * rather than left collecting a job whose result nothing will read.
     */
    private fun watch(jobId: UUID) {
        stopWatching()
        watching = launchGuarded(
            // A throw out of either collector would otherwise leave `running` true with no watcher
            // left to clear it: the bar sticks, Export stays gone, and the only way out is killing the
            // app. A settle with no outcome releases the watcher without claiming a file was or was
            // not written.
            onFailure = { _phase.update { it.copy(running = false, queued = false, settled = true) } }
        ) {
            // The invariant, and it is this function's job because both callers reach here: [start],
            // and the `runningJobFor` collector in [attach], which picks up a job this sheet did not
            // enqueue. **Nothing from job A may be on screen once a watcher attaches to job B.** Only
            // [start] clears anything of its own, so the reattach path starts from whatever the last
            // settle left — which is the banner, still up.
            _phase.update { it.copy(running = true, finished = null) }
            launch {
                // Assigned, never filtered. The registry is keyed by job id, so this flow holds this
                // job's progress and nothing else's — including the null a job that has not published
                // yet correctly has. Filtering that null is how job A's bar ends up over job B's work.
                registry.progressOf(jobId).collect { progress ->
                    _phase.update { it.copy(progress = progress) }
                }
            }
            launcher.status(jobId).collect { status ->
                if (_phase.updateAndGet { it.reduce(status) }.settled) stopWatching()
            }
        }
    }

    /**
     * **Cancels the coroutine it is called from** when the status collector reaches a terminal state.
     * That is intended — the status flow never completes on its own — but it means nothing placed
     * after the `collect` in [watch] would run, so cleanup does not belong there. The `progressOf`
     * child collector is cancelled with it.
     */
    private fun stopWatching() {
        watching?.cancel()
        watching = null
    }

    /**
     * Nothing was enqueued: no worker was constructed and no byte was written, so the banner can say
     * so outright instead of hedging. A null reason is not a missing one — there is no worker to have
     * produced a sentence.
     */
    private fun notStarted() {
        _canSubmit.value = attachedTo != null
        _phase.value = JobPhase(
            finished = JobFinish.Failed(reason = null, workerRan = false),
            settled = true,
        )
    }
}
