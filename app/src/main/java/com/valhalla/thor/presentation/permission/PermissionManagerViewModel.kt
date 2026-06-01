package com.valhalla.thor.presentation.permission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.repository.PermissionRepository
import com.valhalla.thor.domain.usecase.GetAppPermissionsUseCase
import com.valhalla.thor.domain.usecase.TogglePermissionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PermissionManagerViewModel(
    private val getAppPermissionsUseCase: GetAppPermissionsUseCase,
    private val togglePermissionUseCase: TogglePermissionUseCase,
    private val permissionRepository: PermissionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionUiState())
    val uiState = _uiState.asStateFlow()

    fun loadPermissions(packageName: String, appName: String) {
        _uiState.update {
            it.copy(
                packageName = packageName,
                appName = appName,
                isLoading = true,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            val isPrivilege = permissionRepository.isPrivilegeActive()
            getAppPermissionsUseCase(packageName)
                .onSuccess { list ->
                    _uiState.update {
                        it.copy(
                            permissions = list,
                            isPrivilegeMode = isPrivilege,
                            isLoading = false
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Failed to load permissions"
                        )
                    }
                }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun togglePermission(permissionName: String, grant: Boolean) {
        val packageName = _uiState.value.packageName
        viewModelScope.launch {
            togglePermissionUseCase(packageName, permissionName, grant)
                .onSuccess {
                    // Update state locally first to avoid full reload lag
                    _uiState.update { state ->
                        val updated = state.permissions.map {
                            if (it.name == permissionName) it.copy(isGranted = grant) else it
                        }
                        state.copy(permissions = updated, successMessage = "Permission status updated")
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: "Failed to modify permission")
                    }
                }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}
