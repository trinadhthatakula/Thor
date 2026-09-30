// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.RootAvailabilityState
import kotlinx.coroutines.flow.StateFlow

/** Independent of PrivilegeManager, so gateways can observe it without a dependency cycle. */
interface RootAvailabilityProvider {
    val state: StateFlow<RootAvailabilityState>

    /** Starts the first observation if needed; later calls retain the current refresh status. */
    suspend fun awaitInitialObservation(): RootAvailabilityState
}

fun interface RootRefreshRequest {
    suspend fun await(): RootAvailabilityState
}

interface RootRefreshController : RootAvailabilityProvider {
    /** The shared attempt outlives any individual coroutine awaiting its result. */
    fun requestRefresh(): RootRefreshRequest

    suspend fun refreshAndAwait(): RootAvailabilityState = requestRefresh().await()

    /** One retry after BUSY when an externally observed root operation becomes idle. */
    fun onRootWorkIdle()
}

interface RootAdmissionController : RootAvailabilityProvider {
    /**
     * Accept one operation against fresh root state and retain that acceptance through nested
     * shell/Binder calls. A logical operation can retain acceptance through its nested queue steps;
     * a separately queued operation must recheck when it starts. The block owns complete cleanup.
     */
    suspend fun <T> withRootAdmission(block: suspend () -> T): T
}
