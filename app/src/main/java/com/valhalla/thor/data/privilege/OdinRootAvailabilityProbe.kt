// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.privilege

import com.valhalla.superuser.RootAvailabilityKind
import com.valhalla.superuser.ktx.refreshRootAvailability
import org.koin.core.annotation.Single

@Single(binds = [RootAvailabilityProbe::class])
class OdinRootAvailabilityProbe : RootAvailabilityProbe {
    override suspend fun observeFreshRoot(): RootProbeResult {
        val observation = refreshRootAvailability()
        return RootProbeResult(
            outcome = when (observation.kind) {
                RootAvailabilityKind.ROOT -> RootProbeOutcome.ROOT
                RootAvailabilityKind.NON_ROOT -> RootProbeOutcome.NON_ROOT
                RootAvailabilityKind.BUSY -> RootProbeOutcome.BUSY
                RootAvailabilityKind.TIMED_OUT -> RootProbeOutcome.TIMED_OUT
                RootAvailabilityKind.FAILED -> RootProbeOutcome.FAILED
            },
            failure = observation.failure,
        )
    }
}
