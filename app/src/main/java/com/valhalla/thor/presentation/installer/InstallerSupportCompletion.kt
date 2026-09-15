// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

import com.valhalla.thor.domain.InstallState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal data class InstallerSupportObservation(
    val state: InstallState,
    val completedWhileObserved: Boolean = false,
)

/** Observe delivered bus events before Compose can coalesce several events into one frame. */
internal fun Flow<InstallState>.observeSupportCompletion(): Flow<InstallerSupportObservation> {
    val completion = InstallerSupportCompletion()
    return map { state -> InstallerSupportObservation(state, completion.observe(state)) }
}

/** A replayed result does not invite support unless this sheet observed the installation. */
internal class InstallerSupportCompletion {
    private var observedInstallation = false

    fun observe(state: InstallState): Boolean {
        when (state) {
            is InstallState.Installing,
            InstallState.UserConfirmationRequired -> observedInstallation = true

            InstallState.Success -> Unit
            else -> observedInstallation = false
        }
        return state == InstallState.Success && observedInstallation
    }
}
