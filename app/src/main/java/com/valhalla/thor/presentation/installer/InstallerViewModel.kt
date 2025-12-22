package com.valhalla.thor.presentation.installer

import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.model.AppMetadata
import com.valhalla.thor.domain.model.HistoryRecord
import com.valhalla.thor.domain.model.OperationType
import com.valhalla.thor.domain.repository.AppAnalyzer
import com.valhalla.thor.domain.repository.HistoryRepository
import com.valhalla.thor.domain.repository.InstallerRepository
import kotlinx.coroutines.launch
import kotlin.collections.firstOrNull
import kotlin.onFailure
import kotlin.onSuccess

class InstallerViewModel(
    private val repository: InstallerRepository,
    private val analyzer: AppAnalyzer,
    private val eventBus: InstallerEventBus,
    private val packageManager: PackageManager,
    private val historyRepository: HistoryRepository
) : ViewModel() {

    val installState = eventBus.events

    // History Read Logic Moved to HistoryViewModel

    var currentPackageName: String? = null
        private set

    private var pendingUri: Uri? = null
    private var pendingMetadata: AppMetadata? = null
    private var isUpdateOperation: Boolean = false

    init {
        // Clear sticky state logic
        val lastState = eventBus.events.replayCache.firstOrNull()
        if (lastState is InstallState.Success || lastState is InstallState.Error) {
            viewModelScope.launch { eventBus.emit(InstallState.Idle) }
        }

        // Listen for SUCCESS to save history
        viewModelScope.launch {
            eventBus.events.collect { state ->
                if (state is InstallState.Success) {
                    saveHistoryRecord()
                }
            }
        }
    }

    fun installFile(uri: Uri) {
        viewModelScope.launch {
            currentPackageName = null
            pendingMetadata = null
            eventBus.emit(InstallState.Parsing)

            val analysis = analyzer.analyze(uri)

            analysis.onSuccess { meta ->
                pendingUri = uri
                pendingMetadata = meta
                currentPackageName = meta.packageName

                isUpdateOperation = try {
                    packageManager.getPackageInfo(meta.packageName, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }

                eventBus.emit(InstallState.ReadyToInstall(meta, isUpdateOperation))
            }.onFailure {
                eventBus.emit(InstallState.Error("Failed to parse package."))
            }
        }
    }

    fun confirmInstall() {
        val uri = pendingUri ?: return
        viewModelScope.launch {
            repository.installPackage(uri)
        }
    }

    private fun saveHistoryRecord() {
        val meta = pendingMetadata ?: return
        val uri = pendingUri

        viewModelScope.launch {
            historyRepository.addRecord(
                HistoryRecord(
                    packageName = meta.packageName,
                    label = meta.label,
                    version = meta.version,
                    timestamp = System.currentTimeMillis(),
                    type = if (isUpdateOperation) OperationType.UPDATE else OperationType.INSTALL,
                    path = uri?.toString() ?: "Unknown"
                )
            )
            // Cleanup
            pendingUri = null
            pendingMetadata = null
        }
    }

    fun resetState() {
        viewModelScope.launch {
            eventBus.emit(InstallState.Idle)
            pendingUri = null
            currentPackageName = null
        }
    }
}