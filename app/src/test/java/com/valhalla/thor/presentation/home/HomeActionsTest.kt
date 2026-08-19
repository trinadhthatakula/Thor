// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home

import com.valhalla.thor.presentation.home.components.HomeAction
import com.valhalla.thor.presentation.home.components.HomeAction.BACKUP_RESTORE
import com.valhalla.thor.presentation.home.components.HomeAction.CLEAR_CACHE
import com.valhalla.thor.presentation.home.components.HomeAction.EXTENSIONS
import com.valhalla.thor.presentation.home.components.HomeAction.INSTALL
import com.valhalla.thor.presentation.home.components.HomeAction.REINSTALL
import com.valhalla.thor.presentation.home.components.homeActionRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three privilege flags all derive from one nullable field on the Home state, so only five of
 * their eight combinations are reachable: `canClearCache` implies privilege, and so does a visible
 * reinstall card. The GH#344 preferences are independent of all of that and of each other.
 *
 * `canClearCache` is Root **or** Shizuku, because the tile runs `pm trim-caches`, gated on
 * `CLEAR_APP_CACHE` — a permission `com.android.shell` holds.
 *
 * Each privilege state is asserted on its own below, then the preference rules, then the packing
 * and visibility invariants across every combination.
 */
class HomeActionsTest {

    /** One state the Home screen can be in: the three derived flags plus preferences. */
    private data class State(
        val reinstall: Boolean,
        val canClearCache: Boolean,
        val privilege: Boolean,
        val showInstaller: Boolean,
        val showExtensions: Boolean,
        val showBackupRestore: Boolean = true,
    ) {
        fun rows() =
            homeActionRows(reinstall, canClearCache, privilege, showInstaller, showExtensions, showBackupRestore)

        override fun toString() = "reinstall=$reinstall canClearCache=$canClearCache " +
            "privilege=$privilege installer=$showInstaller extensions=$showExtensions backupRestore=$showBackupRestore"
    }

    /** The five reachable privilege states, crossed with preference combinations. */
    private val reachableStates: List<State> =
        listOf(
            Triple(false, false, false),
            Triple(false, false, true),
            Triple(true, false, true),
            Triple(false, true, true),
            Triple(true, true, true),
        ).flatMap { (reinstall, canClearCache, privilege) ->
            listOf(true, false).flatMap { installer ->
                listOf(true, false).flatMap { extensions ->
                    listOf(true, false).map { backupRestore ->
                        State(reinstall, canClearCache, privilege, installer, extensions, backupRestore)
                    }
                }
            }
        }

    // --- Privilege states, all optional tiles kept ---------------------------------------------

    @Test fun noPrivilege_isOnePair() {
        assertEquals(
            listOf(listOf(INSTALL, BACKUP_RESTORE)),
            homeActionRows(reinstallVisible = false, canClearCache = false, hasPrivilege = false)
        )
    }

    @Test fun dhizukuOnly_noReinstall_leadsWide() {
        assertEquals(
            listOf(listOf(INSTALL), listOf(BACKUP_RESTORE, EXTENSIONS)),
            homeActionRows(reinstallVisible = false, canClearCache = false, hasPrivilege = true)
        )
    }

    @Test fun dhizukuOnly_withReinstall_isTwoByTwo() {
        assertEquals(
            listOf(listOf(INSTALL, BACKUP_RESTORE), listOf(EXTENSIONS, REINSTALL)),
            homeActionRows(reinstallVisible = true, canClearCache = false, hasPrivilege = true)
        )
    }

    @Test fun rootOrShizuku_noReinstall_isTwoByTwo() {
        assertEquals(
            listOf(listOf(INSTALL, BACKUP_RESTORE), listOf(CLEAR_CACHE, EXTENSIONS)),
            homeActionRows(reinstallVisible = false, canClearCache = true, hasPrivilege = true)
        )
    }

    @Test fun rootOrShizuku_withReinstall_leadsWide() {
        assertEquals(
            listOf(listOf(INSTALL), listOf(BACKUP_RESTORE, CLEAR_CACHE), listOf(EXTENSIONS, REINSTALL)),
            homeActionRows(reinstallVisible = true, canClearCache = true, hasPrivilege = true)
        )
    }

    /** Reinstall is last in the flow, so dismissing it re-packs only the tiles after it. */
    @Test fun dismissingReinstall_leavesTheOtherTilesInPlace() {
        val withCard = homeActionRows(reinstallVisible = true, canClearCache = true, hasPrivilege = true)
        val without = homeActionRows(reinstallVisible = false, canClearCache = true, hasPrivilege = true)
        assertEquals(listOf(INSTALL, BACKUP_RESTORE, CLEAR_CACHE, EXTENSIONS), without.flatten())
        assertEquals(listOf(INSTALL, BACKUP_RESTORE, CLEAR_CACHE, EXTENSIONS, REINSTALL), withCard.flatten())
    }

    @Test fun narrowContainer_givesEveryTileItsOwnRow() {
        assertEquals(
            listOf(listOf(INSTALL), listOf(BACKUP_RESTORE), listOf(CLEAR_CACHE), listOf(EXTENSIONS), listOf(REINSTALL)),
            homeActionRows(
                reinstallVisible = true, canClearCache = true, hasPrivilege = true, narrowContainer = true
            )
        )
    }

    // --- Visibility preferences ----------------------------------------------------------------

    @Test fun hidingTheInstaller_dropsOnlyThatTile() {
        val rows = homeActionRows(
            reinstallVisible = true, canClearCache = true, hasPrivilege = true, showInstaller = false
        )
        assertTrue("Install must be gone", INSTALL !in rows.flatten())
        assertEquals(listOf(listOf(BACKUP_RESTORE, CLEAR_CACHE), listOf(EXTENSIONS, REINSTALL)), rows)
    }

    @Test fun hidingExtensions_dropsOnlyThatTile() {
        val rows = homeActionRows(
            reinstallVisible = true, canClearCache = true, hasPrivilege = true, showExtensions = false
        )
        assertTrue("Extensions must be gone", EXTENSIONS !in rows.flatten())
        assertEquals(listOf(listOf(INSTALL, BACKUP_RESTORE), listOf(CLEAR_CACHE, REINSTALL)), rows)
    }

    @Test fun hidingInstallerAndExtensions_keepsRemainingTiles() {
        val rows = homeActionRows(
            reinstallVisible = true,
            canClearCache = true,
            hasPrivilege = true,
            showInstaller = false,
            showExtensions = false,
        )
        assertEquals(listOf(listOf(BACKUP_RESTORE), listOf(CLEAR_CACHE, REINSTALL)), rows)
    }

    @Test fun hidingAllTilesWithNoPrivilege_leavesNothingToDraw() {
        assertEquals(
            emptyList<List<HomeAction>>(),
            homeActionRows(
                reinstallVisible = false,
                canClearCache = false,
                hasPrivilege = false,
                showInstaller = false,
                showExtensions = false,
                showBackupRestore = false,
            )
        )
    }

    /** The preference relaxes nothing: without a privilege there is no Extensions tile to keep. */
    @Test fun wantingExtensionsWithoutPrivilege_staysHidden() {
        val rows = homeActionRows(
            reinstallVisible = false, canClearCache = false, hasPrivilege = false, showExtensions = true
        )
        assertEquals(listOf(listOf(INSTALL, BACKUP_RESTORE)), rows)
    }

    /** Hiding a tile is layout-only, so the rail packs the survivors the same way. */
    @Test fun narrowContainer_respectsTheVisibilityPreferences() {
        assertEquals(
            listOf(listOf(CLEAR_CACHE), listOf(REINSTALL)),
            homeActionRows(
                reinstallVisible = true,
                canClearCache = true,
                hasPrivilege = true,
                showInstaller = false,
                showExtensions = false,
                showBackupRestore = false,
                narrowContainer = true,
            )
        )
    }

    // --- Invariants across every reachable state ------------------------------------------------

    @Test fun everyReachableState_packsIntoPairsWithAtMostOneWideLeader() {
        for (state in reachableStates) {
            val rows = state.rows()

            assertTrue("$state: a row holds more than two tiles", rows.all { it.size in 1..2 })
            assertTrue("$state: an empty row was emitted", rows.none { it.isEmpty() })
            assertTrue(
                "$state: a single-tile row appears somewhere other than first",
                rows.drop(1).all { it.size == 2 }
            )

            val flat = rows.flatten()
            assertEquals("$state: a tile was duplicated", flat.size, flat.toSet().size)
        }
    }

    /** Every tile's visibility is exactly its own rule — no tile rides along on another's. */
    @Test fun everyReachableState_showsExactlyTheEligibleTiles() {
        for (state in reachableStates) {
            val flat = state.rows().flatten()
            assertEquals("$state: Install visibility", state.showInstaller, INSTALL in flat)
            assertEquals("$state: Backup & restore visibility", state.showBackupRestore, BACKUP_RESTORE in flat)
            assertEquals("$state: Clear cache visibility", state.canClearCache, CLEAR_CACHE in flat)
            assertEquals(
                "$state: Extensions visibility",
                state.privilege && state.showExtensions,
                EXTENSIONS in flat
            )
            assertEquals("$state: Reinstall visibility", state.reinstall, REINSTALL in flat)
        }
    }

    @Test fun everyActionIsReachableFromSomeState() {
        val seen = reachableStates.flatMap { it.rows().flatten() }
        assertEquals(HomeAction.entries.toSet(), seen.toSet())
    }
}
