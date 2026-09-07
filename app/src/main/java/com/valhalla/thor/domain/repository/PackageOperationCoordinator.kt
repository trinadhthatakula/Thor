// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationOwner
import kotlin.time.Duration

interface PackageOperationCoordinator {
    suspend fun <T> withPackageLease(
        packageName: String,
        owner: PackageOperationOwner,
        admissionTimeout: Duration,
        block: suspend () -> T,
    ): PackageLeaseResult<T>
}
