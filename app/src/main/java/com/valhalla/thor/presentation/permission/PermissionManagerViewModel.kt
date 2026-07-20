package com.valhalla.thor.presentation.permission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.repository.PermissionRepository
import com.valhalla.thor.domain.usecase.GetAppPermissionsUseCase
import com.valhalla.thor.domain.usecase.TogglePermissionUseCase
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class PermissionManagerViewModel(
    private val getAppPermissionsUseCase: GetAppPermissionsUseCase,
    private val togglePermissionUseCase: TogglePermissionUseCase,
    private val permissionRepository: PermissionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionUiState())
    val uiState = _uiState.asStateFlow()

    // 1-slot DROP_OLDEST buffer so an event emitted just before the screen's collector reaches
    // STARTED (early lifecycle / config change) is delivered rather than silently dropped.
    private val _events = MutableSharedFlow<UiText>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<UiText> = _events.asSharedFlow()

    fun loadPermissions(packageName: String, appName: String) {
        _uiState.update {
            it.copy(
                packageName = packageName,
                appName = appName,
                isLoading = true
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
                            isPrivilegeMode = isPrivilege
                        )
                    }
                    _events.emit(
                        UiText.DynamicString(error.message ?: "Failed to load permissions")
                    )
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
                        state.copy(permissions = updated)
                    }
                    _events.emit(UiText.DynamicString("Permission status updated"))
                }
                .onFailure { error ->
                    _events.emit(
                        UiText.DynamicString(error.message ?: "Failed to modify permission")
                    )
                }
        }
    }

}
