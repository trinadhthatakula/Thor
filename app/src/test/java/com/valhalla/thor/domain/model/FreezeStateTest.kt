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
    private val stateOf: (String) -> FreezeState = { states[it] ?: FreezeState.ABSENT }

    @Test
    fun `freeze targets only active apps`() {
        assertEquals(
            listOf("com.active.one", "com.active.two"),
            freezableCandidates(watchlist, BulkOp.FREEZE, stateOf)
        )
    }

    @Test
    fun `unfreeze targets only frozen apps`() {
        assertEquals(
            listOf("com.frozen.one"),
            freezableCandidates(watchlist, BulkOp.UNFREEZE, stateOf)
        )
    }

    @Test
    fun `uninstalled packages are never candidates`() {
        val all = freezableCandidates(watchlist, BulkOp.FREEZE, stateOf) +
                freezableCandidates(watchlist, BulkOp.UNFREEZE, stateOf)
        assertEquals(emptyList<String>(), all.filter { it == "com.gone" })
    }

    @Test
    fun `a fully frozen watchlist yields no freeze candidates`() {
        // This is the reported bug: the tile must go INACTIVE here, and it can only do that
        // if the candidate list is empty rather than the watchlist size.
        val allFrozen = listOf("a", "b", "c")
        assertEquals(
            emptyList<String>(),
            freezableCandidates(allFrozen, BulkOp.FREEZE) { FreezeState.FROZEN }
        )
    }

    @Test
    fun `an empty watchlist yields no candidates`() {
        assertEquals(
            emptyList<String>(),
            freezableCandidates(emptyList(), BulkOp.FREEZE, stateOf)
        )
    }

    @Test
    fun `candidate order follows the watchlist`() {
        val reversed = listOf("com.active.two", "com.active.one")
        assertEquals(
            listOf("com.active.two", "com.active.one"),
            freezableCandidates(reversed, BulkOp.FREEZE, stateOf)
        )
    }
}
