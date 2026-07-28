// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Which direction a bulk run moves apps. The QS tile only ever issues [FREEZE]. */
enum class BulkOp { FREEZE, UNFREEZE }

/**
 * The concrete per-package action a bulk run performs.
 *
 * Separate from [BulkOp] because "freeze" is two different system calls depending on the
 * user's [FreezerMode]: disable the package, or suspend it.
 */
enum class BulkAction { UNFREEZE, SUSPEND, DISABLE }

/**
 * Resolve [op] × [mode] to the action to perform on each package.
 *
 * Extracted as a pure function because this is the Freeze-vs-Suspend rule the project already
 * regressed on once (GH#239): unfreezing must restore both dimensions (unsuspend *and*
 * enable), while freezing picks exactly one according to the mode. [mode] is irrelevant when
 * unfreezing — [BulkAction.UNFREEZE] maps to `forceUnfreeze`, which handles both cases.
 */
fun bulkActionFor(op: BulkOp, mode: FreezerMode): BulkAction = when {
    op == BulkOp.UNFREEZE -> BulkAction.UNFREEZE
    mode == FreezerMode.SUSPEND -> BulkAction.SUSPEND
    else -> BulkAction.DISABLE
}

/**
 * Outcome of a bulk run.
 *
 * [op] is carried on the result because one runner serves both directions: without it an
 * UNFREEZE run is reported with freeze wording ("Froze 5 apps").
 *
 * [unresolved] is the third bucket that makes a deadline honest: those packages were either
 * never started or were still running when the deadline fired. Reporting them as failures
 * (as the pre-rework tile did) claims knowledge we do not have.
 */
data class BulkResult(
    val op: BulkOp,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
) {
    val unresolved: Int get() = total - succeeded - failed
}
