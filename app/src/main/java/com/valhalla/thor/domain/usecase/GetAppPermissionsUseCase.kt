package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.AppPermission
import com.valhalla.thor.domain.repository.PermissionRepository
import org.koin.core.annotation.Factory

@Factory
class GetAppPermissionsUseCase(
    private val repository: PermissionRepository
) {
    suspend operator fun invoke(packageName: String): Result<List<AppPermission>> {
        return repository.getAppPermissions(packageName).map { permissions ->
            permissions.sortedWith(
                compareByDescending<AppPermission> { it.isRuntime }
                    .thenBy { it.label }
            )
        }
    }
}
