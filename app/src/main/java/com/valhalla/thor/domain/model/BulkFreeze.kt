// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Which direction a resolved freezer sweep moves apps. */
enum class BulkOp { FREEZE, UNFREEZE }

/** A mutable source selection that must be resolved before durable enqueue. */
sealed interface BulkScope {
    data object Watchlist : BulkScope
    data class Profile(val id: Long) : BulkScope
}

/**
 * Input to target resolution. The scope is intentionally limited to watchlist and profile;
 * arbitrary selections are passed explicitly to `PrivilegeSweepTargetResolver.resolveSelection`.
 */
data class BulkRequest(
    val op: BulkOp,
    val scope: BulkScope = BulkScope.Watchlist,
    val mode: FreezerMode? = null,
)
