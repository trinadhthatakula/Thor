// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.common

import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.repository.ThorJobStatus

/**
 * How a job ended, as a screen has to report it.
 *
 * The shape is `AppBackupViewModel`'s `BackupFinish`, lifted out of the backup package because the
 * export sheet needs the identical three outcomes and the identical [workerRan] split. It is not a
 * generalisation made in advance: the second consumer arrived, and one of these per job kind is how
 * the `Cancelled(workerRan = …)` distinction below gets forgotten in the third copy.
 *
 * `BackupFinish` and `RestoreFinish` are deliberately **not** repointed at this in the same commit —
 * that is a rename across two view models, their sheets and their tests, and it would bury the export
 * feature it is meant to serve. See `docs/follow-ups/`.
 */
sealed interface JobFinish {

    /**
     * Whether a worker actually ran, which is what decides the second sentence on screen.
     *
     * "Nothing was saved and nothing was started" and "it ran and then failed" are different things
     * to have to act on, and a UI that cannot tell them apart says the vaguer one to both.
     */
    val workerRan: Boolean

    /**
     * @param warnings the sentences the worker put in `JOB_WARNINGS_KEY`, in order.
     *
     * A class rather than an object because a job can succeed *and* have something the user must be
     * told. Export produces none today; carrying the list anyway is what keeps this usable by the
     * restore path, which is the one that has them.
     */
    data class Succeeded(val warnings: List<String> = emptyList()) : JobFinish {
        override val workerRan: Boolean get() = true
    }

    /**
     * @param reason the sentence the worker put in `JOB_ERROR_KEY`, or null.
     *
     * **Null is still a failure.** WorkManager produces a bare `Result.failure()` on some of its own
     * paths, and a screen keying "did it fail?" off a non-null reason reports those as nothing at all.
     */
    data class Failed(val reason: String?, override val workerRan: Boolean) : JobFinish

    /** The only outcome whose [workerRan] genuinely varies — see [reduce]'s `Cancelled` arm. */
    data class Cancelled(override val workerRan: Boolean) : JobFinish
}

/**
 * Everything a screen shows about one job, and the two bits of history needed to read the next status.
 *
 * A value, reduced by [reduce] from the statuses a [ThorJobStatus] flow emits. Keeping [seenLive] and
 * [seenRunning] *in* the phase rather than in the collecting view model is the whole reason this is
 * testable without a device: the reduction becomes a pure function of (phase, status) and every
 * ordering that mattered on the backup side — a late attach that misses RUNNING, a `Gone` that arrives
 * before the row is written — is a two-line test rather than a fake WorkManager.
 *
 * @param running the job exists and has not finished. Set before the first status arrives, because
 *   WorkManager's flow is not guaranteed to answer in the same frame and a Start button over a job
 *   that is already writing invites a second one.
 * @param queued running *and* waiting behind something else. Every Thor job is appended to one chain.
 * @param progress the worker's last published `ThorJobProgress`, or null when it has published none.
 *   Not cleared on settle: what clears it is whatever starts the next job.
 * @param finished the outcome, once there is one. Null both before a job settles and after the user
 *   dismisses the banner.
 * @param settled whether the watcher may stop collecting. Distinct from `finished != null` — a job
 *   whose record WorkManager pruned under a live watcher is over with nothing to report.
 * @param seenLive whether any non-`Gone` status has been observed. See the `Gone` arm of [reduce].
 * @param seenRunning whether `Running` specifically has been observed. Only `Cancelled` reads it.
 */
data class JobPhase(
    val running: Boolean = false,
    val queued: Boolean = false,
    val progress: ThorJobProgress? = null,
    val finished: JobFinish? = null,
    val settled: Boolean = false,
    val seenLive: Boolean = false,
    val seenRunning: Boolean = false,
)

/**
 * Fold one status into the phase.
 *
 * Every arm below was a bug on the backup side first; the comments say which.
 */
fun JobPhase.reduce(status: ThorJobStatus): JobPhase = when (status) {
    // Both are "the job exists and has not finished", which is why they share `running`. `queued` is
    // the part that differs and the part the user can act on.
    ThorJobStatus.Pending -> copy(running = true, queued = true, seenLive = true)

    ThorJobStatus.Running ->
        copy(running = true, queued = false, seenLive = true, seenRunning = true)

    is ThorJobStatus.Succeeded -> settle(JobFinish.Succeeded(status.warnings))

    // `workerRan = true` unconditionally rather than from [seenRunning]: every FAILED Thor produces is
    // `doWork` returning `Result.failure()`, and a watcher that attached late can miss RUNNING but
    // cannot make the run un-happen. The one FAILED that does not mean the worker ran is a
    // `WorkerFactory` that could not build it; over-reported here on purpose.
    is ThorJobStatus.Failed -> settle(JobFinish.Failed(status.reason, workerRan = true))

    // The one terminal state that does not say for itself whether work happened. WorkManager cancels
    // the dependents of a prerequisite that fails and every job is appended to one chain, so the
    // common cancel is a job queued behind a failing one that never entered `doWork` — "Thor did not
    // start it" rather than "Thor tried and failed".
    ThorJobStatus.Cancelled -> settle(JobFinish.Cancelled(workerRan = seenRunning))

    // `Gone` is a null `WorkInfo`, and null is what "no row for this id" looks like as well as "the
    // row was pruned". `enqueueUniqueJob` awaits the `Operation`, so an id handed back normally does
    // have a row — but an enqueue that fails *after* the caller stopped waiting, and an id recovered
    // from `runningJobFor` that WorkManager prunes in between, both produce a leading null. Nothing in
    // the value distinguishes them from a terminal one; only the order does.
    //
    // After the job has been seen alive: the record went away underneath a live watcher. Terminal, but
    // with no outcome to report — hence `settle(null)` rather than a `Failed`.
    //
    // Before that: ignored. Settling here would take the bar down a frame after the tap, re-enable
    // the button and invite a duplicate enqueue.
    ThorJobStatus.Gone -> if (seenLive) settle(null) else this
}

/**
 * `progress` survives on purpose — see [JobPhase.progress]. Nulling it here is invisible on screen
 * (every sheet reads the bar under `if (running)`, which this clears) and it deletes the fixture the
 * "a second run does not open on the first one's bar" tests are built from.
 */
private fun JobPhase.settle(finish: JobFinish?): JobPhase =
    copy(running = false, queued = false, finished = finish, settled = true)
