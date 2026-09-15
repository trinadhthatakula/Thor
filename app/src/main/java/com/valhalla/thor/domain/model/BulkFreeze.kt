// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Which direction a resolved freezer sweep moves apps. */
enum class BulkOp { FREEZE, UNFREEZE }

/** A mutable source selection that must be resolved before durable enqueue. */
sealed interface BulkScope {
    data object Watchlist : BulkScope
    data class Profile(val id: Long) : BulkScope
    /** Explicit recovery across both lists; never used to widen a freeze or auto-freeze. */
    data object ManagedApps : BulkScope
}

/**
 * Input to target resolution. Named scopes cover watchlist, profile, and combined recovery;
 * arbitrary selections are passed explicitly to `PrivilegeSweepTargetResolver.resolveSelection`.
 */
data class BulkRequest(
    val op: BulkOp,
    val scope: BulkScope = BulkScope.Watchlist,
    val mode: FreezerMode? = null,
)
