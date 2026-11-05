// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup.hub

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.THOR_JOB_CHAIN
import com.valhalla.thor.domain.model.TaskQueueKind
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.ArchiveDeleteFailure
import com.valhalla.thor.domain.repository.ArchiveDeleteResult
import com.valhalla.thor.domain.repository.BackupArchiveItem
import com.valhalla.thor.domain.repository.BackupArchiveKind
import com.valhalla.thor.domain.repository.BackupArchiveScanner
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.TaskQueueRepository
import com.valhalla.thor.domain.repository.UnavailableArchiveSource
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

enum class BackupHubFilter { ALL, DATA_BACKUPS, APP_BUNDLES }
enum class BackupArchiveSort { NEWEST, NAME }

data class ArchiveDeletionIssue(val archive: BackupArchiveItem, val reason: ArchiveDeleteFailure)

data class BackupRestoreHubState(
    val isLoading: Boolean = true,
    val archives: List<BackupArchiveItem> = emptyList(),
    val unavailableSources: List<UnavailableArchiveSource> = emptyList(),
    val scanFailed: Boolean = false,
    val folderSelectionFailed: Boolean = false,
    val archiveQuery: String = "",
    val archiveSort: BackupArchiveSort = BackupArchiveSort.NEWEST,
    val activeFilter: BackupHubFilter = BackupHubFilter.ALL,
    val isAppPickerVisible: Boolean = false,
    val appPickerSearchQuery: String = "",
    val installedApps: List<AppInfo> = emptyList(),
    val archiveToDelete: BackupArchiveItem? = null,
    val deletingArchive: Boolean = false,
    val deletionIssue: ArchiveDeletionIssue? = null,
) {
    val hasBackups: Boolean get() = archives.any { it.kind == BackupArchiveKind.DATA_BACKUP }
    val hasBundles: Boolean get() = archives.any { it.kind == BackupArchiveKind.APP_BUNDLE }
    val showFilterChips: Boolean get() = hasBackups && hasBundles

    val filteredArchives: List<BackupArchiveItem>
        get() {
            val query = archiveQuery.trim()
            val matches = archives.filter { archive ->
                val kindMatches = when (activeFilter) {
                    BackupHubFilter.ALL -> true
                    BackupHubFilter.DATA_BACKUPS -> archive.kind == BackupArchiveKind.DATA_BACKUP
                    BackupHubFilter.APP_BUNDLES -> archive.kind == BackupArchiveKind.APP_BUNDLE
                }
                kindMatches && (query.isEmpty() || listOfNotNull(
                    archive.displayName, archive.appName, archive.packageName,
                ).any { it.contains(query, ignoreCase = true) })
            }
            return when (archiveSort) {
                BackupArchiveSort.NEWEST -> matches.sortedWith(
                    compareByDescending<BackupArchiveItem> { it.dateModifiedEpochSec }
                        .thenBy { it.uriString },
                )
                BackupArchiveSort.NAME -> matches.sortedWith(
                    compareBy<BackupArchiveItem> { (it.appName ?: it.displayName).lowercase() }
                        .thenByDescending { it.dateModifiedEpochSec }.thenBy { it.uriString },
                )
            }
        }

    val totalSizeBytes: Long get() = archives.sumOf { it.sizeBytes }

    val filteredInstalledApps: List<AppInfo>
        get() {
            if (appPickerSearchQuery.isBlank()) return installedApps
            val q = appPickerSearchQuery.trim()
            return installedApps.filter {
                it.appName?.contains(q, ignoreCase = true) == true ||
                    it.packageName.contains(q, ignoreCase = true)
            }
        }
}

@KoinViewModel
class BackupRestoreHubViewModel(
    private val scanner: BackupArchiveScanner,
    private val appRepository: AppRepository,
    private val application: Application,
    private val taskQueue: TaskQueueRepository,
    private val preferences: PreferenceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BackupRestoreHubState())
    val state: StateFlow<BackupRestoreHubState> = _state.asStateFlow()
    private var scanJob: Job? = null
    private var completionRefreshJob: Job? = null

    init {
        loadInstalledApps()
        refreshArchives()
        observeArchiveJobs()
        observeWorkManagerJobs()
    }

    private fun scheduleCompletionRefresh() {
        completionRefreshJob?.cancel()
        completionRefreshJob = viewModelScope.launch {
            delay(250)
            refreshArchives()
        }
    }

    private fun observeArchiveJobs() {
        viewModelScope.launch {
            taskQueue.tasks.map { tasks ->
                tasks.filter {
                    it.queueKind == TaskQueueKind.DATA && it.terminalAtEpochMs != null &&
                        it.operationId in setOf(DataTaskKind.ARCHIVE_BACKUP.name, DataTaskKind.APP_EXPORT.name)
                }.map { Triple(it.taskId, it.phase, it.terminalAtEpochMs) }.toSet()
            }.distinctUntilChanged()
                .catch { Logger.e("BackupRestoreHub", "Could not observe archive tasks", it) }
                .collect { completed ->
                    // Also cover a completion racing the initial scan before the first queue snapshot.
                    if (completed.isNotEmpty()) scheduleCompletionRefresh()
                }
        }
    }

    private fun observeWorkManagerJobs() {
        viewModelScope.launch {
            runCatching {
                var previous: Set<java.util.UUID>? = null
                WorkManager.getInstance(application).getWorkInfosForUniqueWorkFlow(THOR_JOB_CHAIN)
                    .collect { workInfos ->
                        val finished = workInfos.filter { it.state.isFinished }.map { it.id }.toSet()
                        if (previous != null && (finished - previous!!).isNotEmpty()) scheduleCompletionRefresh()
                        previous = finished
                    }
            }
        }
    }

    fun refreshArchives() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, scanFailed = false) }
            scanner.scanBackups().catch {
                _state.update { current -> current.copy(isLoading = false, scanFailed = true) }
            }.collect { scan ->
                _state.update { current ->
                    val hasMatchingFilter = when (current.activeFilter) {
                        BackupHubFilter.ALL -> true
                        BackupHubFilter.DATA_BACKUPS -> scan.archives.any { it.kind == BackupArchiveKind.DATA_BACKUP }
                        BackupHubFilter.APP_BUNDLES -> scan.archives.any { it.kind == BackupArchiveKind.APP_BUNDLE }
                    }
                    current.copy(
                        archives = scan.archives,
                        unavailableSources = scan.unavailableSources,
                        activeFilter = if (hasMatchingFilter) current.activeFilter else BackupHubFilter.ALL,
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun reconnectFolder(uri: String) {
        viewModelScope.launch {
            try {
                if (preferences.setExportDirUri(uri)) {
                    _state.update { it.copy(folderSelectionFailed = false) }
                    refreshArchives()
                } else reportFolderSelectionFailure()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reportFolderSelectionFailure()
            }
        }
    }

    fun reportFolderSelectionFailure() {
        _state.update { it.copy(folderSelectionFailed = true) }
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            appRepository.getAllApps().collect { apps ->
                _state.update { it.copy(installedApps = apps.filter { app -> !app.isSystem }
                    .sortedBy { app -> (app.appName ?: app.packageName).lowercase() }) }
            }
        }
    }

    fun setFilter(filter: BackupHubFilter) { _state.update { it.copy(activeFilter = filter) } }
    fun setArchiveQuery(query: String) { _state.update { it.copy(archiveQuery = query) } }
    fun setArchiveSort(sort: BackupArchiveSort) { _state.update { it.copy(archiveSort = sort) } }
    fun showAppPicker() { _state.update { it.copy(isAppPickerVisible = true, appPickerSearchQuery = "") } }
    fun hideAppPicker() { _state.update { it.copy(isAppPickerVisible = false, appPickerSearchQuery = "") } }
    fun updateAppPickerSearch(query: String) { _state.update { it.copy(appPickerSearchQuery = query) } }
    fun requestDeleteArchive(item: BackupArchiveItem) {
        if (!_state.value.deletingArchive) _state.update { it.copy(archiveToDelete = item) }
    }
    fun dismissDeleteArchive() { _state.update { it.copy(archiveToDelete = null) } }
    fun dismissDeletionIssue() { _state.update { it.copy(deletionIssue = null) } }

    fun confirmDeleteArchive() {
        val target = _state.value.archiveToDelete ?: return
        if (_state.value.deletingArchive) return
        _state.update { it.copy(archiveToDelete = null, deletingArchive = true, deletionIssue = null) }
        viewModelScope.launch {
            try {
                when (val result = scanner.deleteArchive(target)) {
                    ArchiveDeleteResult.Deleted -> refreshArchives()
                    is ArchiveDeleteResult.Failed -> _state.update {
                        it.copy(deletionIssue = ArchiveDeletionIssue(target, result.reason))
                    }
                }
            } finally {
                _state.update { it.copy(deletingArchive = false) }
            }
        }
    }
}
