// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.BulkResult
import com.valhalla.thor.util.UiText

/**
 * Human-readable outcome of a bulk run, resolved late so the caller (tile subtitle or
 * notification) supplies the Context.
 *
 * Unresolved outranks failed in the headline: "didn't finish" is what we actually know when
 * the deadline fires, and claiming those packages failed would be a guess. The success and
 * partial-failure strings are shared with SettingsViewModel / AppListViewModel /
 * FreezeLoggerDialog and are reused verbatim.
 */
fun bulkResultMessage(result: BulkResult): UiText = when {
    result.unresolved > 0 -> UiText.StringResource(
        R.string.tile_freeze_incomplete,
        result.succeeded,
        result.total,
        result.unresolved,
    )

    result.failed > 0 -> UiText.StringResource(
        R.string.tile_freeze_partial_failure,
        result.succeeded,
        result.total,
        result.failed,
    )

    else -> UiText.PluralsResource(R.plurals.tile_freeze_success, result.succeeded)
}
