// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.AppPermission
import com.valhalla.thor.domain.model.PermissionIndex

interface PermissionRepository {
    suspend fun getAppPermissions(packageName: String): Result<List<AppPermission>>

    /**
     * Device-wide map of runtime-permission group -> declaring packages, for the app-list filter.
     *
     * Deliberately one sweep rather than [getAppPermissions] per app: the latter is a
     * `getPackageInfo` binder call each, and the filter needs the answer for every installed
     * package at once.
     */
    suspend fun buildPermissionIndex(): Result<PermissionIndex>

    suspend fun grantPermission(packageName: String, permissionName: String): Result<Unit>
    suspend fun revokePermission(packageName: String, permissionName: String): Result<Unit>
    suspend fun isPrivilegeActive(): Boolean
}
