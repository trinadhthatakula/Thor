// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkResult
import com.valhalla.thor.util.UiText

/**
 * Human-readable outcome of a bulk run, resolved late so the caller (tile subtitle or
 * notification) supplies the Context.
 *
 * Wording follows [BulkResult.op]: one runner serves both directions, so hard-wiring the
 * freeze strings made every "Unfreeze all" report "Froze 5 apps". The unfreeze strings are the
 * ones FreezeLoggerDialog already pairs with the freeze strings for the same three buckets, so
 * they are reused rather than duplicated; only the unfreeze *incomplete* string was missing.
 *
 * Unresolved outranks failed in the headline: "didn't finish" is what we actually know when
 * the deadline fires, and claiming those packages failed would be a guess. The success and
 * partial-failure strings are shared with SettingsViewModel / AppListViewModel /
 * FreezeLoggerDialog and are reused verbatim.
 */
fun bulkResultMessage(result: BulkResult): UiText {
    val isFreeze = result.op == BulkOp.FREEZE
    return when {
        result.unresolved > 0 -> UiText.StringResource(
            if (isFreeze) R.string.tile_freeze_incomplete else R.string.tile_unfreeze_incomplete,
            result.succeeded,
            result.total,
            result.unresolved,
        )

        result.failed > 0 -> UiText.StringResource(
            if (isFreeze) R.string.tile_freeze_partial_failure
            else R.string.tile_unfreeze_partial_failure,
            result.succeeded,
            result.total,
            result.failed,
        )

        else -> UiText.PluralsResource(
            if (isFreeze) R.plurals.tile_freeze_success else R.plurals.unfrozen_count_success,
            result.succeeded,
        )
    }
}
