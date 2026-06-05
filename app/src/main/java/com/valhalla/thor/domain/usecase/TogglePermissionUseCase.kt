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
