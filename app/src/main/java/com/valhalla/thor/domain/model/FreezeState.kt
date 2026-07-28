// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** An app's freeze state as far as a bulk run cares. [ABSENT] = not installed. */
enum class FreezeState { FROZEN, ACTIVE, ABSENT }

/**
 * Everything a bulk run needs to know about one package: where it is now, and whether policy
 * lets us freeze it at all.
 *
 * Both halves come from a single read so they cannot disagree. Asking for [state] and
 * [blockedFromFreeze] separately would mean a second PackageManager binder call per watchlist
 * entry on every QS shade open, and a window in which the two answers describe different
 * instants.
 */
data class FreezeCandidate(
    val state: FreezeState,
    /**
     * True when [FreezeTier.BLOCKED] applies — an unsafe system app, or any system app while the
     * UAD list is unreadable. Freeze-only: it says nothing about unfreezing, which is always
     * allowed and is in fact the way *out* of a bad state.
     */
    val blockedFromFreeze: Boolean = false,
)

/**
 * The packages a bulk [op] would actually act on, in watchlist order.
 *
 * This is the fix for the tile counting watchlist rows: the freezer watchlist is invariant
 * under freeze/unfreeze, so only the live per-app state can tell us whether there is
 * anything left to do. [FreezeState.ABSENT] packages are skipped but deliberately left in
 * the watchlist — pruning them is out of scope.
 *
 * Written as two explicit branches rather than one `wanted` state plus a shared filter so the
 * asymmetry is structural: [BulkOp.FREEZE] honours [FreezeCandidate.blockedFromFreeze],
 * [BulkOp.UNFREEZE] must not. A blocked app that somehow got frozen has to stay unfreezable,
 * or the block would trap it.
 */
fun freezableCandidates(
    watchlist: List<String>,
    op: BulkOp,
    candidateOf: (String) -> FreezeCandidate,
): List<String> = when (op) {
    BulkOp.FREEZE -> watchlist.filter {
        val candidate = candidateOf(it)
        candidate.state == FreezeState.ACTIVE && !candidate.blockedFromFreeze
    }

    BulkOp.UNFREEZE -> watchlist.filter { candidateOf(it).state == FreezeState.FROZEN }
}
