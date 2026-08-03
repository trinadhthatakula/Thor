// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.BulkOutcome
import com.valhalla.thor.domain.model.BulkRequest
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain port for starting a bulk freeze/unfreeze run and for watching what is already in flight.
 *
 * Exists for the same reason [AppShortcutController] does, only more so: `BulkFreezeRunner` takes
 * four final collaborators a JVM test cannot produce — `PrivilegeManager`, whose initializer
 * registers Shizuku listeners, `AppFreezeStateReader` over the abstract `PackageManager`, and
 * `UadHelper` / `BulkResultNotifier` over a `Context` — so *naming the class* was enough to put
 * every line of the Freezer view model out of reach of a unit test, watchlist removal included.
 *
 * Narrow on purpose. The runner also owns the tile's freezable count, its last result and the
 * completion stream; no view model reads any of them, and the two members here are the whole of
 * what the Freezer screen needs.
 */
interface BulkFreezeController {

    /** Every request in flight, oldest first; empty when nothing is running. */
    val runningRequests: StateFlow<List<BulkRequest>>

    /**
     * Start [request] and return it — or return the in-flight run it coalesces with, rather than
     * starting a second pass over the same packages. Await the result for the outcome.
     */
    fun launch(request: BulkRequest): Deferred<BulkOutcome>
}
