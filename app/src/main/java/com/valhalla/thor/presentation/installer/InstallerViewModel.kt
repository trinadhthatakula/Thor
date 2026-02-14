package com.valhalla.thor.presentation.installer

import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.model.AppMetadata
import com.valhalla.thor.domain.repository.AppAnalyzer
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class InstallerViewModel(
    private val repository: InstallerRepository,
    private val analyzer: AppAnalyzer,
    private val eventBus: InstallerEventBus,
    private val packageManager: PackageManager,
    private val systemRepository: SystemRepository
) : ViewModel() {

    val installState = eventBus.events

    val installMode: StateFlow<InstallMode>
        field = MutableStateFlow(InstallMode.NORMAL)

    val availableModes: StateFlow<List<InstallMode>>
        field = MutableStateFlow(listOf(InstallMode.NORMAL))

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

        checkAvailableModes()
    }

    private fun checkAvailableModes() {
        viewModelScope.launch {
            val modes = mutableListOf(InstallMode.NORMAL)

            if (systemRepository.isRootAvailable()) {
                modes.add(InstallMode.ROOT)
            }

            if (systemRepository.isShizukuAvailable()) {
                modes.add(InstallMode.SHIZUKU)
            }

            availableModes.value = modes

            if (availableModes.value.contains(InstallMode.ROOT)) {
                installMode.value = InstallMode.ROOT
            } else if (availableModes.value.contains(InstallMode.SHIZUKU)) {
                installMode.value = InstallMode.SHIZUKU
            } else {
                installMode.value = InstallMode.NORMAL
            }

        }
    }

    fun setInstallMode(mode: InstallMode) {
        if (availableModes.value.contains(mode)) {
            installMode.value = mode
        }
    }

    fun setInstallModeAlsoInstall(mode: InstallMode) {
        if (availableModes.value.contains(mode)) {
            installMode.value = mode
        }
        confirmInstall()
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
            repository.installPackage(uri, installMode.value)
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
