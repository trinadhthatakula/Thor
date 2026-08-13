// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.common

import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.ThorJobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orderings that were bugs on the backup side, restated as a pure function.
 *
 * Every one of these previously needed a fake `ArchiveJobLauncher`, a `runTest` and a view model to
 * assert; folding `seenLive`/`seenRunning` into [JobPhase] is what makes them two lines each.
 */
class JobPhaseTest {

    @Test
    fun `pending is running and queued`() {
        val phase = JobPhase().reduce(ThorJobStatus.Pending)

        assertTrue(phase.running)
        assertTrue(phase.queued)
        assertFalse(phase.settled)
    }

    @Test
    fun `running clears queued`() {
        // The transition the user actually watches: the "waiting behind another job" line has to go
        // away by itself, not on the next thing the worker publishes.
        val phase = JobPhase().reduce(ThorJobStatus.Pending).reduce(ThorJobStatus.Running)

        assertTrue(phase.running)
        assertFalse(phase.queued)
    }

    @Test
    fun `success carries the worker's warnings through`() {
        // A job can succeed *and* have something the user must be told. Dropping the list here would
        // make the restore path's partial-placement warning unreportable.
        val phase = JobPhase().reduce(ThorJobStatus.Succeeded(listOf("game data was not placed")))

        assertEquals(
            JobFinish.Succeeded(listOf("game data was not placed")),
            phase.finished
        )
        assertTrue(phase.settled)
        assertFalse(phase.running)
    }

    @Test
    fun `a failure counts as having run even when RUNNING was never observed`() {
        // A watcher that attaches late — the reattach path — can miss RUNNING entirely. It cannot
        // make the run un-happen, and reporting "nothing was started" over a worker that packaged
        // half a gigabyte and then failed is the wrong recovery to offer.
        val phase = JobPhase().reduce(ThorJobStatus.Failed("no space left"))

        assertEquals(JobFinish.Failed("no space left", workerRan = true), phase.finished)
    }

    @Test
    fun `a failure with no reason is still a failure`() {
        // WorkManager produces a bare Result.failure() on some of its own paths. A screen keying
        // "did it fail?" off a non-null reason reports those as nothing at all.
        val phase = JobPhase().reduce(ThorJobStatus.Failed(null))

        val finished = phase.finished
        assertTrue(finished is JobFinish.Failed)
        assertNull((finished as JobFinish.Failed).reason)
        assertTrue(phase.settled)
    }

    @Test
    fun `a cancel after RUNNING says the worker ran`() {
        val phase = JobPhase()
            .reduce(ThorJobStatus.Running)
            .reduce(ThorJobStatus.Cancelled)

        assertEquals(JobFinish.Cancelled(workerRan = true), phase.finished)
    }

    @Test
    fun `a cancel before RUNNING says it never started`() {
        // The common cancel, and the reason `Cancelled` is not folded into `Failed`: every Thor job
        // is appended to one chain, and WorkManager cancels the dependents of a prerequisite that
        // fails. Such a job never entered `doWork`, so "Thor did not start it" is the true sentence.
        val phase = JobPhase()
            .reduce(ThorJobStatus.Pending)
            .reduce(ThorJobStatus.Cancelled)

        assertEquals(JobFinish.Cancelled(workerRan = false), phase.finished)
    }

    @Test
    fun `a Gone before the job was ever seen alive is ignored`() {
        // A null `WorkInfo` is "no row for this id" as much as it is "the row was pruned" — an id
        // recovered from `runningJobFor` and pruned in between reads exactly like a fresh one. Settling
        // here would take the bar down a frame after the tap and invite a duplicate enqueue.
        val phase = JobPhase(running = true).reduce(ThorJobStatus.Gone)

        assertTrue(phase.running)
        assertFalse(phase.settled)
        assertNull(phase.finished)
    }

    @Test
    fun `a Gone after the job was seen alive settles with nothing to report`() {
        // The record went away underneath a live watcher. Terminal — so the watcher is released and
        // the form comes back — but not a failure and not a success anyone witnessed.
        val phase = JobPhase()
            .reduce(ThorJobStatus.Running)
            .reduce(ThorJobStatus.Gone)

        assertTrue(phase.settled)
        assertFalse(phase.running)
        assertNull(phase.finished)
    }

    @Test
    fun `settling leaves the last progress in place`() {
        // Deliberate, and it was tried the other way. Nulling it is invisible on screen — every sheet
        // reads the bar under `if (running)`, which settling clears — and it deletes the fixture the
        // "a second run does not open on the first one's bar" assertions are built from.
        val progress = ThorJobProgress(ThorJobStage.CAPTURING, "Packaging Maps as .xapk")
        val phase = JobPhase(progress = progress)
            .reduce(ThorJobStatus.Running)
            .reduce(ThorJobStatus.Succeeded())

        assertEquals(progress, phase.progress)
    }
}
