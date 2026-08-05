// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home

import com.valhalla.thor.presentation.home.components.HomeAction
import com.valhalla.thor.presentation.home.components.HomeAction.CLEAR_CACHE
import com.valhalla.thor.presentation.home.components.HomeAction.EXTENSIONS
import com.valhalla.thor.presentation.home.components.HomeAction.INSTALL
import com.valhalla.thor.presentation.home.components.HomeAction.REINSTALL
import com.valhalla.thor.presentation.home.components.homeActionRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All three flags derive from one nullable field on the Home state, so only five of the eight
 * combinations are reachable: root implies privilege, and so does a visible reinstall card.
 * Each reachable state is asserted below; the last test pins the packing invariant across all of
 * them.
 */
class HomeActionsTest {

    /** (reinstall, root, privilege) for every state the Home screen can actually be in. */
    private val reachableStates = listOf(
        Triple(false, false, false),
        Triple(false, false, true),
        Triple(true, false, true),
        Triple(false, true, true),
        Triple(true, true, true),
    )

    @Test fun noPrivilege_onlyInstallFullWidth() {
        assertEquals(
            listOf(listOf(INSTALL)),
            homeActionRows(reinstallVisible = false, isRoot = false, hasPrivilege = false)
        )
    }

    @Test fun shizukuOrDhizuku_noReinstall_isOnePair() {
        assertEquals(
            listOf(listOf(INSTALL, EXTENSIONS)),
            homeActionRows(reinstallVisible = false, isRoot = false, hasPrivilege = true)
        )
    }

    @Test fun shizukuOrDhizuku_withReinstall_leadsWide() {
        assertEquals(
            listOf(listOf(INSTALL), listOf(EXTENSIONS, REINSTALL)),
            homeActionRows(reinstallVisible = true, isRoot = false, hasPrivilege = true)
        )
    }

    @Test fun root_noReinstall_leadsWide() {
        assertEquals(
            listOf(listOf(INSTALL), listOf(CLEAR_CACHE, EXTENSIONS)),
            homeActionRows(reinstallVisible = false, isRoot = true, hasPrivilege = true)
        )
    }

    @Test fun root_withReinstall_isTwoByTwo() {
        assertEquals(
            listOf(listOf(INSTALL, CLEAR_CACHE), listOf(EXTENSIONS, REINSTALL)),
            homeActionRows(reinstallVisible = true, isRoot = true, hasPrivilege = true)
        )
    }

    /** Reinstall is last in the flow, so dismissing it re-packs only the tiles after it. */
    @Test fun dismissingReinstall_leavesTheOtherTilesInPlace() {
        val withCard = homeActionRows(reinstallVisible = true, isRoot = true, hasPrivilege = true)
        val without = homeActionRows(reinstallVisible = false, isRoot = true, hasPrivilege = true)
        assertEquals(listOf(INSTALL, CLEAR_CACHE, EXTENSIONS), without.flatten())
        assertEquals(listOf(INSTALL, CLEAR_CACHE, EXTENSIONS, REINSTALL), withCard.flatten())
    }

    @Test fun narrowContainer_givesEveryTileItsOwnRow() {
        assertEquals(
            listOf(listOf(INSTALL), listOf(CLEAR_CACHE), listOf(EXTENSIONS), listOf(REINSTALL)),
            homeActionRows(
                reinstallVisible = true, isRoot = true, hasPrivilege = true, narrowContainer = true
            )
        )
    }

    @Test fun everyReachableState_packsIntoPairsWithAtMostOneWideLeader() {
        for ((reinstall, root, privilege) in reachableStates) {
            val rows = homeActionRows(reinstall, root, privilege)
            val label = "reinstall=$reinstall root=$root privilege=$privilege"

            assertTrue("$label: a row holds more than two tiles", rows.all { it.size in 1..2 })
            assertTrue("$label: an empty row was emitted", rows.none { it.isEmpty() })
            assertTrue(
                "$label: a single-tile row appears somewhere other than first",
                rows.drop(1).all { it.size == 2 }
            )

            val flat = rows.flatten()
            assertEquals("$label: a tile was duplicated", flat.size, flat.toSet().size)
            assertTrue("$label: Install is missing", INSTALL in flat)
            assertEquals("$label: Clear cache visibility", root, CLEAR_CACHE in flat)
            assertEquals("$label: Extensions visibility", privilege, EXTENSIONS in flat)
            assertEquals("$label: Reinstall visibility", reinstall, REINSTALL in flat)
        }
    }

    @Test fun everyActionIsReachableFromSomeState() {
        val seen = reachableStates.flatMap { (r, root, p) -> homeActionRows(r, root, p).flatten() }
        assertEquals(HomeAction.entries.toSet(), seen.toSet())
    }
}
