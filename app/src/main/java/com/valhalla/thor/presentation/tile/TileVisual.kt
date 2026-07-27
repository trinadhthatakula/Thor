// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import com.valhalla.thor.domain.model.PrivilegeState

/**
 * What the QS tile should show. Deliberately framework-free — [FreezerTileService] maps
 * these onto `Tile.STATE_*` so this stays a plain JVM unit under test.
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
 * listen. CHECKING maps to a clickable state, and `onClick` re-checks privilege itself.
 */
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
