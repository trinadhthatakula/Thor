// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Which direction a bulk run moves apps. The QS tile only ever issues [FREEZE]. */
enum class BulkOp { FREEZE, UNFREEZE }

/**
 * Outcome of a bulk run.
 *
 * [unresolved] is the third bucket that makes a deadline honest: those packages were either
 * never started or were still running when the deadline fired. Reporting them as failures
 * (as the pre-rework tile did) claims knowledge we do not have.
 */
data class BulkResult(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
) {
    val unresolved: Int get() = total - succeeded - failed
}
