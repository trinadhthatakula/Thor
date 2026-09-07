// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import android.service.quicksettings.Tile
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepStatus

/**
 * What the QS tile should show. Deliberately framework-free — [tileStateFor] maps these onto
 * `Tile.STATE_*` so the decision itself stays a plain JVM unit under test.
 */
enum class TileVisual { CHECKING, NO_PRIVILEGE, NOTHING_TO_FREEZE, READY, WORKING }

/**
 * Resolve the tile's visual state.
 *
 * [freezableCount] is null until the first PackageManager sweep lands.
 *
 * The ordering matters. CHECKING must win over NO_PRIVILEGE while the privilege probe is
 * still in flight: AOSP's `CustomTile.handleClick()` early-returns on `STATE_UNAVAILABLE`,
 * so an optimistic NO_PRIVILEGE paint would silently swallow every tap until the next
 * listen. CHECKING maps to a clickable state, and the click path re-checks privilege itself
 * (`the durable sweep controller.run` awaits `isReady` and no-ops without privilege).
 */
fun tileVisualFor(
    privilege: PrivilegeState,
    freezableCount: Int?,
    status: PrivilegeSweepStatus?,
): TileVisual = tileVisualFor(
    privilege = privilege,
    freezableCount = freezableCount,
    isRunning = status?.phase == PrivilegeSweepPhase.QUEUED ||
        status?.phase == PrivilegeSweepPhase.RUNNING,
)

fun retainedProcessedCount(status: PrivilegeSweepStatus?): Int? = status?.let {
    it.succeeded + it.failed + it.busy
}

fun tileVisualFor(
    privilege: PrivilegeState,
    freezableCount: Int?,
    isRunning: Boolean,
): TileVisual = when {
    !privilege.isReady -> TileVisual.CHECKING
    !privilege.hasAnyPrivilege -> TileVisual.NO_PRIVILEGE
    isRunning -> TileVisual.WORKING
    freezableCount == null -> TileVisual.CHECKING
    freezableCount == 0 -> TileVisual.NOTHING_TO_FREEZE
    else -> TileVisual.READY
}

/**
 * Map a [TileVisual] onto the `Tile.STATE_*` the framework understands.
 *
 * [TileVisual.CHECKING] **must** map to a clickable state. AOSP's `CustomTile.handleClick()`
 * early-returns on `STATE_UNAVAILABLE`, so painting UNAVAILABLE while the privilege probe is
 * still in flight makes the tile swallow every tap until the next listen — the original bug
 * this rework exists to fix. The click path re-checks privilege itself, so an optimistically
 * clickable tile is safe; an optimistically unavailable one is not.
 *
 * Only [TileVisual.NO_PRIVILEGE] — a *resolved* "you cannot use this" — is UNAVAILABLE.
 *
 * `Tile.STATE_*` are compile-time constants (`public static final int` 0/1/2), so this
 * function is exercisable from a plain JVM unit test with no android.jar at runtime.
 */
fun tileStateFor(visual: TileVisual): Int = when (visual) {
    TileVisual.NO_PRIVILEGE -> Tile.STATE_UNAVAILABLE
    TileVisual.NOTHING_TO_FREEZE -> Tile.STATE_INACTIVE
    TileVisual.CHECKING -> Tile.STATE_INACTIVE
    TileVisual.WORKING -> Tile.STATE_ACTIVE
    TileVisual.READY -> Tile.STATE_ACTIVE
}
