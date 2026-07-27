// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** An app's freeze state as far as a bulk run cares. [ABSENT] = not installed. */
enum class FreezeState { FROZEN, ACTIVE, ABSENT }

/**
 * The packages a bulk [op] would actually act on, in watchlist order.
 *
 * This is the fix for the tile counting watchlist rows: the freezer watchlist is invariant
 * under freeze/unfreeze, so only the live per-app state can tell us whether there is
 * anything left to do. [FreezeState.ABSENT] packages are skipped but deliberately left in
 * the watchlist — pruning them is out of scope.
 */
fun freezableCandidates(
    watchlist: List<String>,
    op: BulkOp,
    stateOf: (String) -> FreezeState,
): List<String> {
    val wanted = when (op) {
        BulkOp.FREEZE -> FreezeState.ACTIVE
        BulkOp.UNFREEZE -> FreezeState.FROZEN
    }
    return watchlist.filter { stateOf(it) == wanted }
}
