// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide trigger to request a package scan / cache refresh in
 * [com.valhalla.thor.data.repository.AppRepositoryImpl].
 *
 * Bumped whenever an external permission grant, self-grant, or privilege elevation occurs that
 * changes package visibility, so the repository re-asks `PackageManager` instead of waiting for an
 * unrelated package broadcast.
 *
 * ### Why a counter and not a `SharedFlow<Unit>`
 *
 * A `MutableSharedFlow(replay = 0)` **silently discards** a `tryEmit` made while nobody is
 * collecting, and the one caller that matters most bumps from exactly there: the self-grant runs off
 * the privilege probe started in `ThorApplication.onCreate`, which on a fast device can finish
 * before the first ViewModel has begun collecting the repository flow. The signal that a
 * Chinese-ROM user's package list just became visible would land in an empty room and be dropped,
 * leaving the list holding only Thor with nothing scheduled to fix it.
 *
 * A monotonic revision has no such hole, because the *value* survives having no subscriber. A
 * collector that arrives afterwards reads the bumped number as its initial value — which its own
 * first scan already covers — and every later bump is a change it sees. Hence the contract below:
 * **collect with `.drop(1)`**, so the initial read does not buy a second scan on top of the one the
 * collector performs on subscribe.
 */
object AppScanRevision {

    private val _revision = MutableStateFlow(0)

    /**
     * Increments once per requested package scan.
     *
     * Collectors must `.drop(1)` — see the class KDoc. Conflating is fine and deliberate: two bumps
     * in quick succession collapsing into one emission costs nothing, since the repository drains
     * its trigger channel before scanning anyway.
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Request a package scan / cache refresh. */
    fun bump() {
        _revision.update { it + 1 }
    }
}
