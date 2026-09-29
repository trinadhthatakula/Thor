// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.permission

import com.valhalla.thor.domain.model.AppPermission
import com.valhalla.thor.domain.model.AppOpsSnapshot

enum class AppOpsFilter { RELEVANT, CHANGED, ALL }

data class PermissionUiState(
    val appName: String = "",
    val packageName: String = "",
    val permissions: List<AppPermission> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val isPrivilegeMode: Boolean = false,
    val appOpsSnapshot: AppOpsSnapshot? = null,
    val isAppOpsLoading: Boolean = false,
    val appOpsLoadFailed: Boolean = false,
    val appOpsStatusUncertain: Boolean = false,
    val appOpsSearchQuery: String = "",
    val appOpsFilter: AppOpsFilter = AppOpsFilter.RELEVANT,
    val savingAppOpCode: Int? = null,
)
