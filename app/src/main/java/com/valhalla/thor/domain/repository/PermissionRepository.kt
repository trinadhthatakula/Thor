package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.AppPermission

interface PermissionRepository {
    suspend fun getAppPermissions(packageName: String): Result<List<AppPermission>>
    suspend fun grantPermission(packageName: String, permissionName: String): Result<Unit>
    suspend fun revokePermission(packageName: String, permissionName: String): Result<Unit>
    suspend fun isPrivilegeActive(): Boolean
}
