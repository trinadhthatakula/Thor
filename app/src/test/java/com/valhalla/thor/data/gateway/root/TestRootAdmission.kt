// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.thor.domain.model.RootAdmissionUnavailable
import com.valhalla.thor.domain.model.RootAvailabilityState
import com.valhalla.thor.domain.model.RootConfirmation
import com.valhalla.thor.domain.repository.RootAdmissionController
import kotlinx.coroutines.flow.MutableStateFlow

/** Explicit fixture for tests of command behavior; coordinator concurrency has its own tests. */
internal class TestRootAdmission : RootAdmissionController {
    override val state = MutableStateFlow(
        RootAvailabilityState(
            confirmation = RootConfirmation.ROOT,
            revision = 1,
            confirmedRevision = 1,
            hasCompletedRefresh = true,
        ),
    )

    override suspend fun awaitInitialObservation(): RootAvailabilityState = state.value

    override suspend fun <T> withRootAdmission(block: suspend () -> T): T {
        if (!state.value.canAdmitRoot) throw RootAdmissionUnavailable(state.value)
        return block()
    }
}
