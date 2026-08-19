// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.source.local.room.AppDao
import com.valhalla.thor.data.source.local.room.AppEntity
import com.valhalla.thor.domain.repository.BackupArchiveItem
import com.valhalla.thor.domain.repository.BackupArchiveKind
import com.valhalla.thor.domain.repository.BackupArchiveScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

enum class BackupHubFilter {
    ALL,
    DATA_BACKUPS,
    APP_BUNDLES,
}

data class BackupRestoreHubState(
    val isLoading: Boolean = true,
    val archives: List<BackupArchiveItem> = emptyList(),
    val activeFilter: BackupHubFilter = BackupHubFilter.ALL,
    val isAppPickerVisible: Boolean = false,
    val appPickerSearchQuery: String = "",
    val installedApps: List<AppEntity> = emptyList(),
    val archiveToDelete: BackupArchiveItem? = null,
) {
    val hasBackups: Boolean get() = archives.any { it.kind == BackupArchiveKind.DATA_BACKUP }
    val hasBundles: Boolean get() = archives.any { it.kind == BackupArchiveKind.APP_BUNDLE }
    val showFilterChips: Boolean get() = hasBackups && hasBundles

    val filteredArchives: List<BackupArchiveItem>
        get() = when (activeFilter) {
            BackupHubFilter.ALL -> archives
            BackupHubFilter.DATA_BACKUPS -> archives.filter { it.kind == BackupArchiveKind.DATA_BACKUP }
            BackupHubFilter.APP_BUNDLES -> archives.filter { it.kind == BackupArchiveKind.APP_BUNDLE }
        }

    val totalSizeBytes: Long get() = archives.sumOf { it.sizeBytes }

    val filteredInstalledApps: List<AppEntity>
        get() {
            if (appPickerSearchQuery.isBlank()) return installedApps
            val q = appPickerSearchQuery.trim().lowercase()
            return installedApps.filter {
                (it.appName?.lowercase()?.contains(q) == true) ||
                    it.packageName.lowercase().contains(q)
            }
        }
}

@KoinViewModel
class BackupRestoreHubViewModel(
    private val scanner: BackupArchiveScanner,
    private val appDao: AppDao,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupRestoreHubState())
    val state: StateFlow<BackupRestoreHubState> = _state.asStateFlow()

    init {
        loadInstalledApps()
        refreshArchives()
    }

    fun refreshArchives() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            scanner.scanBackups().collect { list ->
                _state.update { it.copy(archives = list, isLoading = false) }
            }
        }
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            appDao.getAllAppsFlow().collect { apps ->
                val sorted = apps.filter { !it.isSystem }
                    .sortedBy { it.appName?.lowercase() ?: it.packageName }
                _state.update { it.copy(installedApps = sorted) }
            }
        }
    }

    fun setFilter(filter: BackupHubFilter) {
        _state.update { it.copy(activeFilter = filter) }
    }

    fun showAppPicker() {
        _state.update { it.copy(isAppPickerVisible = true, appPickerSearchQuery = "") }
    }

    fun hideAppPicker() {
        _state.update { it.copy(isAppPickerVisible = false, appPickerSearchQuery = "") }
    }

    fun updateAppPickerSearch(query: String) {
        _state.update { it.copy(appPickerSearchQuery = query) }
    }

    fun requestDeleteArchive(item: BackupArchiveItem) {
        _state.update { it.copy(archiveToDelete = item) }
    }

    fun dismissDeleteArchive() {
        _state.update { it.copy(archiveToDelete = null) }
    }

    fun confirmDeleteArchive() {
        val target = _state.value.archiveToDelete ?: return
        viewModelScope.launch {
            _state.update { it.copy(archiveToDelete = null) }
            if (scanner.deleteArchive(target)) {
                refreshArchives()
            }
        }
    }
}
