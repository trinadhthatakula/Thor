// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import android.service.quicksettings.Tile
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pure QS tile state machine. No Android deps. */
class TileVisualTest {

    private val unprobed = PrivilegeState(isReady = false)
    private val none = PrivilegeState(active = PrivilegeMode.NONE, isReady = true)
    private val rooted =
        PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)

    @Test
    fun `before the first probe the tile is CHECKING, never NO_PRIVILEGE`() {
        // Painting STATE_UNAVAILABLE here would make AOSP drop every onClick until the
        // next listen, because CustomTile.handleClick early-returns on UNAVAILABLE.
        assertEquals(
            TileVisual.CHECKING,
            tileVisualFor(unprobed, freezableCount = null, isRunning = false)
        )
    }

    @Test
    fun `an unprobed privilege state is CHECKING even with a known count`() {
        assertEquals(
            TileVisual.CHECKING,
            tileVisualFor(unprobed, freezableCount = 4, isRunning = false)
        )
    }

    @Test
    fun `no privilege is NO_PRIVILEGE once probed`() {
        assertEquals(
            TileVisual.NO_PRIVILEGE,
            tileVisualFor(none, freezableCount = 4, isRunning = false)
        )
    }

    @Test
    fun `privileged with no freezable apps is NOTHING_TO_FREEZE`() {
        assertEquals(
            TileVisual.NOTHING_TO_FREEZE,
            tileVisualFor(rooted, freezableCount = 0, isRunning = false)
        )
    }

    @Test
    fun `privileged with freezable apps is READY`() {
        assertEquals(
            TileVisual.READY,
            tileVisualFor(rooted, freezableCount = 3, isRunning = false)
        )
    }

    @Test
    fun `privileged with an unknown count is CHECKING`() {
        assertEquals(
            TileVisual.CHECKING,
            tileVisualFor(rooted, freezableCount = null, isRunning = false)
        )
    }

    @Test
    fun `a running batch is WORKING regardless of count`() {
        assertEquals(
            TileVisual.WORKING,
            tileVisualFor(rooted, freezableCount = 0, isRunning = true)
        )
        assertEquals(
            TileVisual.WORKING,
            tileVisualFor(rooted, freezableCount = 3, isRunning = true)
        )
    }

    @Test
    fun `losing privilege beats a running batch`() {
        assertEquals(
            TileVisual.NO_PRIVILEGE,
            tileVisualFor(none, freezableCount = 3, isRunning = true)
        )
    }

    // ── TileVisual -> Tile.STATE_* ──────────────────────────────────────────
    // Tile.STATE_* are JLS constant variables (public static final int 0/1/2), so kotlinc
    // inlines them and these assertions load no android.jar class at runtime.

    @Test
    fun `CHECKING must stay clickable or the tile silently swallows every tap`() {
        // This is the whole point of the rework. CustomTile.handleClick() early-returns on
        // STATE_UNAVAILABLE, so an optimistically-unavailable tile drops taps until the next
        // listen. If this assertion ever fails, the original bug is back.
        assertEquals(Tile.STATE_INACTIVE, tileStateFor(TileVisual.CHECKING))
    }

    @Test
    fun `NO_PRIVILEGE is the only unavailable state`() {
        assertEquals(Tile.STATE_UNAVAILABLE, tileStateFor(TileVisual.NO_PRIVILEGE))
        TileVisual.entries.filter { it != TileVisual.NO_PRIVILEGE }.forEach { visual ->
            assertNotEquals(
                "$visual must remain clickable",
                Tile.STATE_UNAVAILABLE,
                tileStateFor(visual)
            )
        }
    }

    @Test
    fun `NOTHING_TO_FREEZE is inactive but still clickable`() {
        assertEquals(Tile.STATE_INACTIVE, tileStateFor(TileVisual.NOTHING_TO_FREEZE))
    }

    @Test
    fun `WORKING is active`() {
        assertEquals(Tile.STATE_ACTIVE, tileStateFor(TileVisual.WORKING))
    }

    @Test
    fun `READY is active`() {
        assertEquals(Tile.STATE_ACTIVE, tileStateFor(TileVisual.READY))
    }

    @Test
    fun `tile visual reflects queued running and latest retained terminal count`() {
        assertEquals(TileVisual.WORKING, tileVisualFor(rooted, 3, status(PrivilegeSweepPhase.QUEUED)))
        assertEquals(TileVisual.WORKING, tileVisualFor(rooted, 3, status(PrivilegeSweepPhase.RUNNING)))
        assertEquals(TileVisual.READY, tileVisualFor(rooted, 3, status(PrivilegeSweepPhase.PARTIAL)))
        assertEquals(2, retainedProcessedCount(status(PrivilegeSweepPhase.PARTIAL)))
    }

    private fun status(phase: PrivilegeSweepPhase) = PrivilegeSweepStatus(
        requestId = UUID(0L, 1L),
        workId = UUID(1L, 1L),
        operation = PrivilegeSweepOperation.FREEZE,
        source = PrivilegeSweepSource.QS_TILE,
        phase = phase,
        total = 3,
        succeeded = 1,
        failed = 1,
        busy = 0,
        unresolved = 1,
        rootLaneDegraded = false,
    )
}
