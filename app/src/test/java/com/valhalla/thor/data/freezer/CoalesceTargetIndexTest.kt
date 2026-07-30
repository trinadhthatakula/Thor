// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.BulkScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which in-flight run a repeated bulk request is handed back.
 *
 * `BulkFreezeRunner` itself cannot be constructed on a JVM — see the `BulkFreezeWorkerTest` KDoc for
 * the four final collaborators in the way — so this rule was lifted out of `launch` to be asserted
 * directly. Both ways of being wrong are silent: coalescing too little runs the same batch twice and
 * reports twice, coalescing too much hands a caller a run that is about to be cancelled and its
 * freeze never happens at all.
 *
 * A chain here is written as a list of `(request, active)` pairs, oldest first — the same order as
 * `unsettled`.
 */
class CoalesceTargetIndexTest {

    private val freezeWatchlist = BulkRequest(BulkOp.FREEZE, BulkScope.Watchlist)
    private val unfreezeWatchlist = BulkRequest(BulkOp.UNFREEZE, BulkScope.Watchlist)
    private val freezeProfile1 = BulkRequest(BulkOp.FREEZE, BulkScope.Profile(1))
    private val freezeProfile2 = BulkRequest(BulkOp.FREEZE, BulkScope.Profile(2))

    private fun target(chain: List<Pair<BulkRequest, Boolean>>, request: BulkRequest): Int? =
        coalesceTargetIndex(
            size = chain.size,
            request = request,
            requestAt = { chain[it].first },
            isActive = { chain[it].second }
        )

    @Test
    fun `an empty chain has nothing to coalesce onto`() {
        assertNull(target(emptyList(), freezeWatchlist))
    }

    @Test
    fun `a repeat of the only run in flight gets that run`() {
        assertEquals(0, target(listOf(freezeWatchlist to true), freezeWatchlist))
    }

    @Test
    fun `a different scope is a different run even at the same op`() {
        // Keyed on the whole request. Coalescing here would mean profile 2 silently never freezes.
        assertNull(target(listOf(freezeProfile1 to true), freezeProfile2))
    }

    @Test
    fun `a repeat still coalesces once something is queued behind it`() {
        // The head is the run touching packages; the tail has not started. A repeat of the head has
        // to find the head, or it takes the serialize path and enqueues a second full batch that
        // re-acts on every package and re-reports after the first one already did.
        val chain = listOf(freezeWatchlist to true, freezeProfile1 to true)

        assertEquals(0, target(chain, freezeWatchlist))
        assertEquals(1, target(chain, freezeProfile1))
    }

    @Test
    fun `a repeat of the replacement coalesces even while the run it cancelled is still unwinding`() {
        // The regression. A(FREEZE) is in flight; B(UNFREEZE) arrives, cancels it and becomes the
        // replacement; the same UNFREEZE arrives again before A has left the chain. A still answers
        // isActive, because B cancels from inside its own coroutine body.
        //
        // The previous rule asked whether the *whole* chain shared the incoming op and refused to
        // coalesce at all when it did not. So this repeat fell through to the serialize path — same
        // op as B, so nothing was cancelled — and queued a second identical unfreeze behind B. Both
        // ran the same batch and both reported.
        val chain = listOf(freezeWatchlist to true, unfreezeWatchlist to true)

        assertEquals(1, target(chain, unfreezeWatchlist))
    }

    @Test
    fun `a doomed run is never handed back, even to an exact repeat of itself`() {
        // The other direction, and why the fix above is a suffix rather than a plain search. A is
        // doomed the moment B is launched; it just has not observed the cancellation yet. Handing A
        // back would coalesce this caller onto a run that is about to die, and its freeze would
        // never happen — so this request must start its own.
        val chain = listOf(freezeWatchlist to true, unfreezeWatchlist to true)

        assertNull(target(chain, freezeWatchlist))
    }

    @Test
    fun `only the runs after the newest op change are eligible`() {
        // FREEZE, then UNFREEZE (cancels the FREEZE), then FREEZE again (cancels both), then a
        // second FREEZE of a different scope queued behind it. Everything before the last op change
        // is doomed; the two survivors are the trailing same-op run.
        val chain = listOf(
            freezeProfile1 to true,
            unfreezeWatchlist to true,
            freezeWatchlist to true,
            freezeProfile2 to true,
        )

        assertEquals(2, target(chain, freezeWatchlist))
        assertEquals(3, target(chain, freezeProfile2))
        // Same request as index 0, but index 0 sits behind an op change and is dead.
        assertNull(target(chain, freezeProfile1))
        // And nothing unfreezes: index 1 is behind an op change too.
        assertNull(target(chain, unfreezeWatchlist))
    }

    @Test
    fun `a settled run is not handed back`() {
        // A run that finished but has not been retired from the chain yet — its own completion is
        // what removes it, so there is a window. Awaiting it would return an outcome for a batch
        // this caller's tap never triggered.
        assertNull(target(listOf(freezeWatchlist to false), freezeWatchlist))
    }

    @Test
    fun `the oldest live match wins`() {
        // Two identical live entries should be unreachable — the second would have coalesced onto
        // the first — but if one ever appears, the run actually touching packages is the one to
        // hand back, not the one still waiting to start.
        val chain = listOf(freezeWatchlist to true, freezeWatchlist to true)

        assertEquals(0, target(chain, freezeWatchlist))
    }
}
