// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home.components

/** The four Home action tiles, in bento order. */
enum class HomeAction { REINSTALL, INSTALL, CLEAR_CACHE, EXTENSIONS }

/**
 * Computes the Home bento rows from the current privilege/state flags.
 * Row 1 = [Reinstall?] + Install (Install is always present).
 * Row 2 = [Clear cache (root)?] + [Extensions (privilege)?].
 * A row that ends up with a single tile renders full-width; empty rows are dropped.
 */
fun homeActionRows(
    reinstallVisible: Boolean,
    isRoot: Boolean,
    hasPrivilege: Boolean,
): List<List<HomeAction>> {
    val row1 = listOfNotNull(
        HomeAction.REINSTALL.takeIf { reinstallVisible },
        HomeAction.INSTALL,
    )
    val row2 = listOfNotNull(
        HomeAction.CLEAR_CACHE.takeIf { isRoot },
        HomeAction.EXTENSIONS.takeIf { hasPrivilege },
    )
    return listOf(row1, row2).filter { it.isNotEmpty() }
}
