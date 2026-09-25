// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.permission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppOpMode
import com.valhalla.thor.domain.model.AppOpScope
import com.valhalla.thor.domain.repository.AppOpsRepository
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
    private val permissionRepository: PermissionRepository,
    private val appOpsRepository: AppOpsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = Channel<UiText>(Channel.BUFFERED)
    val events: Flow<UiText> = _events.receiveAsFlow()

    private var appOpsLoadGeneration = 0L

    fun loadPermissions(packageName: String, appName: String) {
        if (_uiState.value.packageName != packageName) {
            ++appOpsLoadGeneration
            _uiState.value = PermissionUiState(packageName = packageName, appName = appName)
        }
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
                    if (_uiState.value.packageName != packageName) return@onSuccess
                    _uiState.update {
                        it.copy(
                            permissions = list,
                            isPrivilegeMode = isPrivilege,
                            isLoading = false
                        )
                    }
                }
                .onFailure { error ->
                    if (_uiState.value.packageName != packageName) return@onFailure
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
        val current = _uiState.value
        // A grant/revoke call is only meaningful for a requested runtime permission.
        if (!current.isPrivilegeMode || current.permissions.none { it.name == permissionName && it.isRuntime }) return
        val packageName = current.packageName
        viewModelScope.launch {
            togglePermissionUseCase(packageName, permissionName, grant)
                .onSuccess {
                    if (_uiState.value.packageName != packageName) return@onSuccess
                    val refreshAppOps = _uiState.value.let {
                        it.appOpsSnapshot != null || it.isAppOpsLoading ||
                            it.appOpsLoadFailed || it.appOpsStatusUncertain
                    }
                    // Runtime grants can determine App Ops modes. An older read must not
                    // restore the pre-grant snapshot after we discard it here.
                    ++appOpsLoadGeneration
                    // Update state locally first to avoid full reload lag
                    _uiState.update { state ->
                        val updated = state.permissions.map {
                            if (it.name == permissionName) it.copy(isGranted = grant) else it
                        }
                        state.copy(
                            permissions = updated,
                            appOpsSnapshot = null,
                            isAppOpsLoading = false,
                            appOpsLoadFailed = false,
                            appOpsStatusUncertain = false,
                        )
                    }
                    // Also cover a quick return to the App Ops tab while the grant was pending.
                    // Keep catalog loading lazy if the user has never opened App Ops.
                    if (refreshAppOps) loadAppOps()
                    _events.send(UiText.StringResource(R.string.permission_status_updated))
                }
                .onFailure { error ->
                    if (_uiState.value.packageName != packageName) return@onFailure
                    _events.send(
                        error.message?.let { UiText.DynamicString(it) }
                            ?: UiText.StringResource(R.string.failed_to_modify_permission)
                    )
                }
        }
    }

    /** Load on first App Ops visit; permission loading must not wait for the system catalog. */
    fun loadAppOps(force: Boolean = false) {
        val current = _uiState.value
        val packageName = current.packageName
        if (packageName.isBlank() || current.savingAppOpCode != null) return
        if (!force && (current.appOpsSnapshot != null || current.isAppOpsLoading)) return

        val generation = ++appOpsLoadGeneration
        _uiState.update { it.copy(isAppOpsLoading = true, appOpsLoadFailed = false) }
        viewModelScope.launch {
            val result = appOpsRepository.getAppOps(packageName)
            if (generation != appOpsLoadGeneration || _uiState.value.packageName != packageName) return@launch
            result.fold(
                onSuccess = { snapshot ->
                    _uiState.update {
                        it.copy(
                            appOpsSnapshot = snapshot,
                            isAppOpsLoading = false,
                            appOpsLoadFailed = false,
                            appOpsStatusUncertain = false,
                        )
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(isAppOpsLoading = false, appOpsLoadFailed = true) }
                },
            )
        }
    }

    fun updateAppOpsSearchQuery(query: String) {
        _uiState.update { it.copy(appOpsSearchQuery = query) }
    }

    fun updateAppOpsFilter(filter: AppOpsFilter) {
        _uiState.update { it.copy(appOpsFilter = filter) }
    }

    fun setAppOpMode(code: Int, scope: AppOpScope, mode: AppOpMode) {
        if (mode == AppOpMode.UNKNOWN) return
        writeAppOpMode(code, scope, mode, reset = false)
    }

    /** Deliberately separate from choosing MODE_DEFAULT: the platform baseline may differ. */
    fun resetAppOpMode(code: Int, scope: AppOpScope) {
        val entry = _uiState.value.appOpsSnapshot?.entries?.firstOrNull { it.definition.code == code }
            ?: return
        if (!entry.definition.allowsReset || entry.definition.platformDefault == AppOpMode.UNKNOWN) return
        writeAppOpMode(code, scope, entry.definition.platformDefault, reset = true)
    }

    private fun writeAppOpMode(code: Int, scope: AppOpScope, mode: AppOpMode, reset: Boolean) {
        val current = _uiState.value
        val snapshot = current.appOpsSnapshot ?: return
        if (!snapshot.canEdit || current.savingAppOpCode != null || current.isAppOpsLoading ||
            current.appOpsLoadFailed || current.appOpsStatusUncertain
        ) return
        val entry = snapshot.entries.firstOrNull { it.definition.code == code } ?: return
        if (entry.definition.isRuntimePermissionEditBlocked) return
        if (reset && !entry.definition.allowsReset) return
        val packageName = current.packageName
        ++appOpsLoadGeneration // An older refresh must never overwrite the write's read-back.
        _uiState.update { it.copy(savingAppOpCode = code) }

        viewModelScope.launch {
            val write = if (reset) {
                appOpsRepository.resetAppOpMode(packageName, code, scope)
            } else {
                appOpsRepository.setAppOpMode(packageName, code, scope, mode)
            }
            if (_uiState.value.packageName != packageName) return@launch
            if (write.isFailure) {
                // The command may have applied before verification failed. Discard the old status.
                _uiState.update {
                    it.copy(
                        appOpsSnapshot = null,
                        savingAppOpCode = null,
                        appOpsLoadFailed = true,
                        appOpsStatusUncertain = true,
                    )
                }
                _events.send(UiText.StringResource(R.string.app_ops_save_failed))
                return@launch
            }

            // The mode shown to the user comes from a new system read, never from the tap itself.
            val readBackGeneration = appOpsLoadGeneration
            val readBack = appOpsRepository.getAppOps(packageName)
            if (_uiState.value.packageName != packageName) return@launch
            if (readBackGeneration != appOpsLoadGeneration) {
                // A runtime grant changed the modes while this read was pending. Its refresh
                // could not start while saving was set; release that guard and read again.
                _uiState.update {
                    it.copy(
                        appOpsSnapshot = null,
                        savingAppOpCode = null,
                        appOpsStatusUncertain = true,
                    )
                }
                loadAppOps(force = true)
                return@launch
            }
            readBack.fold(
                onSuccess = { refreshed ->
                    _uiState.update {
                        it.copy(
                            appOpsSnapshot = refreshed,
                            savingAppOpCode = null,
                            appOpsLoadFailed = false,
                            appOpsStatusUncertain = false,
                        )
                    }
                    _events.send(UiText.StringResource(R.string.app_ops_status_updated))
                },
                onFailure = {
                    _uiState.update {
                        it.copy(
                            appOpsSnapshot = null,
                            savingAppOpCode = null,
                            appOpsLoadFailed = true,
                            appOpsStatusUncertain = true,
                        )
                    }
                    _events.send(UiText.StringResource(R.string.app_ops_refresh_failed))
                },
            )
        }
    }
}
