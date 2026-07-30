// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure candidate filtering for bulk freeze/unfreeze. No Android deps. */
class FreezeStateTest {

    private val states = mapOf(
        "com.active.one" to FreezeState.ACTIVE,
        "com.active.two" to FreezeState.ACTIVE,
        "com.frozen.one" to FreezeState.FROZEN,
        "com.gone" to FreezeState.ABSENT,
    )
    private val watchlist = states.keys.toList()
    private val candidateOf: (String) -> FreezeCandidate = {
        FreezeCandidate(states[it] ?: FreezeState.ABSENT)
    }

    @Test
    fun `freeze targets only active apps`() {
        assertEquals(
            listOf("com.active.one", "com.active.two"),
            freezableCandidates(watchlist, BulkOp.FREEZE, candidateOf)
        )
    }

    @Test
    fun `unfreeze targets only frozen apps`() {
        assertEquals(
            listOf("com.frozen.one"),
            freezableCandidates(watchlist, BulkOp.UNFREEZE, candidateOf)
        )
    }

    @Test
    fun `uninstalled packages are never candidates`() {
        val all = freezableCandidates(watchlist, BulkOp.FREEZE, candidateOf) +
                freezableCandidates(watchlist, BulkOp.UNFREEZE, candidateOf)
        assertEquals(emptyList<String>(), all.filter { it == "com.gone" })
    }

    @Test
    fun `a fully frozen watchlist yields no freeze candidates`() {
        // This is the reported bug: the tile must go INACTIVE here, and it can only do that
        // if the candidate list is empty rather than the watchlist size.
        val allFrozen = listOf("a", "b", "c")
        assertEquals(
            emptyList<String>(),
            freezableCandidates(allFrozen, BulkOp.FREEZE) { FreezeCandidate(FreezeState.FROZEN) }
        )
    }

    @Test
    fun `an empty watchlist yields no candidates`() {
        assertEquals(
            emptyList<String>(),
            freezableCandidates(emptyList(), BulkOp.FREEZE, candidateOf)
        )
    }

    @Test
    fun `candidate order follows the watchlist`() {
        val reversed = listOf("com.active.two", "com.active.one")
        assertEquals(
            listOf("com.active.two", "com.active.one"),
            freezableCandidates(reversed, BulkOp.FREEZE, candidateOf)
        )
    }

    // --- blocked tier ---------------------------------------------------------------------
    //
    // The QS tile and the launcher Freeze-all shortcut act on the watchlist with no dialog in
    // front of them, so this filter is the only thing standing between a stored watchlist entry
    // and a `pm uninstall --user` on a package the in-app dialog refuses to freeze at all.

    private fun blocked(state: FreezeState) = FreezeCandidate(state, blockedFromFreeze = true)

    @Test
    fun `a blocked active app is not a freeze candidate`() {
        assertEquals(
            emptyList<String>(),
            freezableCandidates(listOf("com.unsafe"), BulkOp.FREEZE) { blocked(FreezeState.ACTIVE) }
        )
    }

    @Test
    fun `a blocked frozen app is still an unfreeze candidate`() {
        // The asymmetry that makes the block safe. An app can be in the watchlist frozen from
        // before it was ever classified (or from a Thor version without this filter); gating
        // unfreeze on the same predicate would trap it frozen with no in-app way out.
        assertEquals(
            listOf("com.unsafe"),
            freezableCandidates(listOf("com.unsafe"), BulkOp.UNFREEZE) {
                blocked(FreezeState.FROZEN)
            }
        )
    }

    @Test
    fun `blocked apps are dropped but their neighbours survive`() {
        val mixed = listOf("com.ok.one", "com.unsafe", "com.ok.two")
        assertEquals(
            listOf("com.ok.one", "com.ok.two"),
            freezableCandidates(mixed, BulkOp.FREEZE) {
                if (it == "com.unsafe") blocked(FreezeState.ACTIVE)
                else FreezeCandidate(FreezeState.ACTIVE)
            }
        )
    }

    @Test
    fun `an all-blocked watchlist yields no freeze candidates`() {
        // What the tile has to paint INACTIVE. A count taken any other way would advertise
        // "Freeze 3" over a batch that then froze nothing.
        assertEquals(
            emptyList<String>(),
            freezableCandidates(listOf("a", "b", "c"), BulkOp.FREEZE) { blocked(FreezeState.ACTIVE) }
        )
    }

    @Test
    fun `blockedFromFreeze defaults to false`() {
        // Guards the default: flipping it would silently empty every freeze batch.
        assertEquals(false, FreezeCandidate(FreezeState.ACTIVE).blockedFromFreeze)
    }
}

/** The freeze-risk tier shared by the tile, the bulk paths and the in-app dialogs. */
class FreezePolicyTest {

    @Test
    fun `user apps are never blocked whatever the recommendation says`() {
        // bloatRecommendation is meaningless for a user app, and freezing one is reversible
        // with pm enable — so a stale UAD row must not gate it.
        assertEquals(
            FreezeTier.NORMAL,
            freezeTierOf(isSystem = false, bloatRecommendation = "Unsafe", isUadLoadFailed = false)
        )
    }

    @Test
    fun `user apps are not blocked by a failed UAD load either`() {
        assertEquals(
            FreezeTier.NORMAL,
            freezeTierOf(isSystem = false, bloatRecommendation = null, isUadLoadFailed = true)
        )
    }

    @Test
    fun `an unsafe system app is blocked`() {
        // Capitalised exactly as uad_lists.json stores it. A comparison that forgets
        // .lowercase() matches nothing here and the whole gate becomes a silent no-op that
        // still passes every other test in this class.
        assertEquals(
            FreezeTier.BLOCKED,
            freezeTierOf(isSystem = true, bloatRecommendation = "Unsafe", isUadLoadFailed = false)
        )
    }

    @Test
    fun `an expert system app warns but is not blocked`() {
        assertEquals(
            FreezeTier.EXPERT,
            freezeTierOf(isSystem = true, bloatRecommendation = "Expert", isUadLoadFailed = false)
        )
    }

    @Test
    fun `a recommended system app is normal`() {
        assertEquals(
            FreezeTier.NORMAL,
            freezeTierOf(
                isSystem = true,
                bloatRecommendation = "Recommended",
                isUadLoadFailed = false
            )
        )
    }

    @Test
    fun `an unclassified system app is normal`() {
        // Present in the list but with no removal advice, or absent from it entirely: Thor has
        // always allowed these, and blocking them would take most of the system list away.
        assertEquals(
            FreezeTier.NORMAL,
            freezeTierOf(isSystem = true, bloatRecommendation = null, isUadLoadFailed = false)
        )
    }

    @Test
    fun `a failed UAD load blocks every system app`() {
        // Fail closed: with no list we cannot tell a safe system app from a bootloop.
        assertEquals(
            FreezeTier.BLOCKED,
            freezeTierOf(isSystem = true, bloatRecommendation = null, isUadLoadFailed = true)
        )
    }

    @Test
    fun `a failed UAD load outranks a benign recommendation`() {
        // The recommendation string cannot be trusted when the load that produced it failed —
        // it is whatever a partially-populated or stale map happened to hold.
        assertEquals(
            FreezeTier.BLOCKED,
            freezeTierOf(
                isSystem = true,
                bloatRecommendation = "Recommended",
                isUadLoadFailed = true
            )
        )
    }
}
