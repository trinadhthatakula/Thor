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
 * [showInstaller] and [showExtensions] are the user's own answer (GH#344): both tiles are shortcuts
 * to something reachable elsewhere — Installer still handles APK intents, Extensions keeps its
 * Settings entry — so hiding either is a layout choice, not a feature switch. They stack with the
 * eligibility rules rather than replacing them: Extensions needs a privilege *and* the preference.
 * Every tile can end up hidden, and an empty list is a legitimate answer; callers must not assume
 * at least one row.
 *
 * [narrowContainer] is for a pane too narrow to pair tiles at all (the wide-screen rail) — every
 * action then gets its own full-width row.
 *
 * [canClearCache] is Root **or** Shizuku, not root alone. The tile runs `pm trim-caches`, which is
 * gated on `CLEAR_APP_CACHE` — a permission `com.android.shell` holds — so Shizuku can do it too.
 * Only Dhizuku is left out: it has no shell to run the command in.
 */
fun homeActionRows(
    reinstallVisible: Boolean,
    canClearCache: Boolean,
    hasPrivilege: Boolean,
    showInstaller: Boolean = true,
    showExtensions: Boolean = true,
    narrowContainer: Boolean = false,
): List<List<HomeAction>> {
    val actions = listOfNotNull(
        HomeAction.INSTALL.takeIf { showInstaller },
        HomeAction.CLEAR_CACHE.takeIf { canClearCache },
        HomeAction.EXTENSIONS.takeIf { hasPrivilege && showExtensions },
        HomeAction.REINSTALL.takeIf { reinstallVisible },
    )
    if (narrowContainer) return actions.map { listOf(it) }
    return if (actions.size % 2 == 1) {
        listOf(listOf(actions.first())) + actions.drop(1).chunked(2)
    } else {
        actions.chunked(2)
    }
}
