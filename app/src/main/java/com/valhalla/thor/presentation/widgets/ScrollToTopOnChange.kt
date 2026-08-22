// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Send a lazy list back to the top whenever [keys] change — but never on the first composition.
 *
 * Sorting, filtering or searching leaves the user looking at row 40 of a list whose row 40 is now a
 * different app, so the reset is right. What is *not* right is doing it the first time the effect
 * runs: `rememberLazyListState`/`rememberLazyGridState` are `rememberSaveable`-backed, so on an
 * activity recreation (rotation, theme change, process death) or on re-entering the destination
 * Compose restores `firstVisibleItemIndex` — and a bare `LaunchedEffect(keys) { scrollToItem(0) }`
 * then fires with unchanged keys and throws that restored position away. [skipInitialReset] is what
 * separates "the keys changed" from "this composition is new".
 *
 * The flag is `remember`, deliberately **not** `rememberSaveable`. Its correctness depends on it
 * being `true` again on exactly the recompositions where the scroll position is restored, which is
 * exactly the ones a `rememberSaveable` would carry a stale `false` across.
 *
 * [scrollToTop] is a suspend lambda rather than a state parameter because `scrollToItem` lives on
 * `LazyListState` and `LazyGridState` separately, not on their common `ScrollableState` supertype.
 */
@Composable
fun ScrollToTopOnChange(
    vararg keys: Any?,
    scrollToTop: suspend () -> Unit,
) {
    var skipInitialReset by remember { mutableStateOf(true) }
    LaunchedEffect(*keys) {
        if (skipInitialReset) skipInitialReset = false
        else scrollToTop()
    }
}
