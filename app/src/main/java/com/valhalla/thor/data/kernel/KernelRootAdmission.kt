// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.kernel

import com.valhalla.thor.domain.model.RootAdmissionUnavailable
import com.valhalla.thor.domain.model.RootRefreshStatus
import com.valhalla.thor.domain.repository.RootAdmissionController
import com.valhalla.thor.domain.repository.RootRefreshController

/** Join the foreground refresh after the picker; never retry work once admitted. */
internal suspend fun <T> withKernelRootAdmission(
    refresh: RootRefreshController,
    admission: RootAdmissionController,
    block: suspend () -> T,
): T {
    repeat(2) { attempt ->
        val observed = refresh.refreshAndAwait()
        if (!observed.canAdmitRoot) throw RootAdmissionUnavailable(observed)
        var entered = false
        try {
            return admission.withRootAdmission {
                entered = true
                block()
            }
        } catch (failure: RootAdmissionUnavailable) {
            // onResume can start another refresh between observation and admission. Only join
            // that check if the operation has not entered; package execution is never replayed.
            if (entered || attempt != 0 || failure.availability.refreshStatus != RootRefreshStatus.CHECKING) throw failure
        }
    }
    error("Root admission did not complete")
}
