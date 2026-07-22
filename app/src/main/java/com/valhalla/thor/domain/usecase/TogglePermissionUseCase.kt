// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.repository.PermissionRepository
import org.koin.core.annotation.Factory

@Factory
class TogglePermissionUseCase(
    private val repository: PermissionRepository
) {
    suspend operator fun invoke(
        packageName: String,
        permissionName: String,
        grant: Boolean
    ): Result<Unit> {
        return if (grant) {
            repository.grantPermission(packageName, permissionName)
        } else {
            repository.revokePermission(packageName, permissionName)
        }
    }
}
