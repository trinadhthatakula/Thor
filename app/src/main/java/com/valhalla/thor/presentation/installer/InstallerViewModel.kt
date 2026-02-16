package com.valhalla.thor.presentation.installer

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
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

    var currentPackageName: String? = null
        private set

    private var pendingUri: Uri? = null
    private var isUpdateOperation: Boolean = false
    private var isDowngrade: Boolean = false

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

            if (systemRepository.isDhizukuAvailable()) {
                modes.add(InstallMode.DHIZUKU)
            }

            availableModes.value = modes

            if (availableModes.value.contains(InstallMode.ROOT)) {
                installMode.value = InstallMode.ROOT
            } else if (availableModes.value.contains(InstallMode.SHIZUKU)) {
                installMode.value = InstallMode.SHIZUKU
            } else if (availableModes.value.contains(InstallMode.DHIZUKU)) {
                installMode.value = InstallMode.DHIZUKU
            } else {
                installMode.value = InstallMode.NORMAL
            }
        }
    }

    @Suppress("unused")
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
            eventBus.emit(InstallState.Parsing)

            val analysis = analyzer.analyze(uri)

            analysis.onSuccess { meta ->
                pendingUri = uri
                currentPackageName = meta.packageName
                var oldVersion: String? = null
                isDowngrade = false

                isUpdateOperation = try {
                    val installedPkg = packageManager.getPackageInfo(meta.packageName, 0)
                    oldVersion = installedPkg.versionName
                    
                    val installedVersionCode = installedPkg.longVersionCode
                    isDowngrade = meta.versionCode < installedVersionCode
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }

                eventBus.emit(InstallState.ReadyToInstall(meta, isUpdateOperation, isDowngrade, oldVersion))
            }.onFailure {
                eventBus.emit(InstallState.Error("Failed to parse package."))
            }
        }
    }

    fun confirmInstall() {
        val uri = pendingUri ?: return
        
        // Validation: Only allow downgrade with Root, Shizuku or Dhizuku
        if (isDowngrade && installMode.value == InstallMode.NORMAL) {
            viewModelScope.launch {
                eventBus.emit(InstallState.Error("Downgrade is only supported with Root, Shizuku or Dhizuku mode."))
            }
            return
        }

        viewModelScope.launch {
            repository.installPackage(uri, installMode.value, canDowngrade = isDowngrade)
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