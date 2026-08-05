// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home.components

/** The four Home action tiles, in bento order. */
enum class HomeAction { REINSTALL, INSTALL, CLEAR_CACHE, EXTENSIONS }

/**
 * Packs the visible Home actions into bento rows: pairs throughout, with a single full-width
 * leader when the count is odd. 4 tiles -> 2x2; 3 -> one wide then a pair; 2 -> one pair; 1 -> one
 * wide tile.
 *
 * The leader goes first rather than last so the wide tile lands at the top of the grid, next to
 * the summary row, instead of leaving a ragged edge at the bottom.
 *
 * Order is Install, Clear cache, Extensions, Reinstall. Reinstall is last because it is the only
 * dismissible tile: putting it at the end means dismissing it re-packs the rows below it rather
 * than shifting every other tile up one slot.
 *
 * [narrowContainer] is for a pane too narrow to pair tiles at all (the wide-screen rail) — every
 * action then gets its own full-width row.
 */
fun homeActionRows(
    reinstallVisible: Boolean,
    isRoot: Boolean,
    hasPrivilege: Boolean,
    narrowContainer: Boolean = false,
): List<List<HomeAction>> {
    val actions = listOfNotNull(
        HomeAction.INSTALL,
        HomeAction.CLEAR_CACHE.takeIf { isRoot },
        HomeAction.EXTENSIONS.takeIf { hasPrivilege },
        HomeAction.REINSTALL.takeIf { reinstallVisible },
    )
    if (narrowContainer) return actions.map { listOf(it) }
    return if (actions.size % 2 == 1) {
        listOf(listOf(actions.first())) + actions.drop(1).chunked(2)
    } else {
        actions.chunked(2)
    }
}
