// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

enum class RootConfirmation { UNKNOWN, ROOT, NON_ROOT }

enum class RootRefreshStatus { IDLE, CHECKING, BUSY, TIMED_OUT, FAILED }

/** Last confirmed acquisition, kept separately from the status of the latest refresh. */
data class RootAvailabilityState(
    val confirmation: RootConfirmation = RootConfirmation.UNKNOWN,
    val refreshStatus: RootRefreshStatus = RootRefreshStatus.IDLE,
    /** Changes on invalidation and completion, including an equal ROOT to ROOT observation. */
    val revision: Long = 0,
    /** Changes only when fresh acquisition confirms ROOT or NON_ROOT. */
    val confirmedRevision: Long = 0,
    val hasCompletedRefresh: Boolean = false,
    val failure: String? = null,
) {
    val isConfirmedRoot: Boolean get() = confirmation == RootConfirmation.ROOT
    val canAdmitRoot: Boolean get() = isConfirmedRoot && refreshStatus == RootRefreshStatus.IDLE
}

class RootAdmissionUnavailable(val availability: RootAvailabilityState) :
    PrivilegeExecutionException("Root access needs a fresh check before this action can start.")
