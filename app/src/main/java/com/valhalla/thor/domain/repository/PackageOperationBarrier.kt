// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.PackageLeaseResult

/**
 * Checks retained work after a package claim is admitted, before its operation starts. Implementors
 * may perform read-only reconciliation; an absent observer or unreadable journal must remain blocked.
 */
fun interface PackageOperationBarrier {
    suspend fun isBlocked(packageName: String, owner: PackageOperationOwner): Boolean

    /** A mutation without one known target must respect every retained package operation. */
    suspend fun isGlobalBlocked(): Boolean = false

    /** Atomically exclude new retained work for the full duration of an operation with unknown scope. */
    suspend fun <T> withGlobalLease(block: suspend () -> T): PackageLeaseResult<T> =
        PackageLeaseResult.Acquired(block())
}
