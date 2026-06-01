package com.valhalla.thor.presentation.permission

import com.valhalla.thor.domain.model.AppPermission

data class PermissionUiState(
    val appName: String = "",
    val packageName: String = "",
    val permissions: List<AppPermission> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val isPrivilegeMode: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)
