package com.valhalla.thor.presentation.permission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.domain.repository.PermissionRepository
import com.valhalla.thor.domain.usecase.GetAppPermissionsUseCase
import com.valhalla.thor.domain.usecase.TogglePermissionUseCase
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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

    private val _events = Channel<UiText>(Channel.BUFFERED)
    val events: Flow<UiText> = _events.receiveAsFlow()

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
                    _events.send(
                        error.message?.let { UiText.DynamicString(it) }
                            ?: UiText.StringResource(R.string.failed_to_load_permissions)
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
                    _events.send(UiText.StringResource(R.string.permission_status_updated))
                }
                .onFailure { error ->
                    _events.send(
                        error.message?.let { UiText.DynamicString(it) }
                            ?: UiText.StringResource(R.string.failed_to_modify_permission)
                    )
                }
        }
    }

}
