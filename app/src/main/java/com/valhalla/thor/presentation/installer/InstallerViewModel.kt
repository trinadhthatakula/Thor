package com.valhalla.thor.presentation.installer

import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.repository.AppAnalyzer
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.UiText
import com.valhalla.thor.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class InstallerViewModel(
    private val repository: InstallerRepository,
    private val analyzer: AppAnalyzer,
    private val eventBus: InstallerEventBus,
    private val packageManager: PackageManager,
    private val systemRepository: SystemRepository,
) : ViewModel() {

    val installState = eventBus.events

    private val _installMode = MutableStateFlow(InstallMode.NORMAL)
    val installMode: StateFlow<InstallMode> = _installMode.asStateFlow()

    private val _availableModes = MutableStateFlow(listOf(InstallMode.NORMAL))
    val availableModes: StateFlow<List<InstallMode>> = _availableModes.asStateFlow()

    var currentPackageName: String? = null
        private set

    private var pendingUri: Uri? = null
    private var isUpdateOperation: Boolean = false
    private var isDowngrade: Boolean = false

    // True once THIS ViewModel has driven a parse/install on the shared @Single bus. Only an
    // owning VM may reset the bus in onCleared(), so tearing down a non-owning installer screen
    // (e.g. one that merely observed the bus) can't clobber a terminal Success / ReadyToInstall
    // that a different, still-alive InstallerViewModel is displaying.
    private var ownsInstall = false

    fun resetState() {
        viewModelScope.launch { eventBus.emit(InstallState.Idle) }
    }

    override fun onCleared() {
        super.onCleared()
        // The event bus is app-scoped (@Single) and holds replay = 1. Without a reset it would
        // retain the last emitted state past this ViewModel's lifetime — e.g. a ReadyToInstall
        // carrying a decoded Bitmap, or a terminal Success — leaking it for the rest of the
        // process and replaying stale state onto a future installer screen. Only reset when THIS
        // VM owns the flow, so we don't wipe a state another live InstallerViewModel (sharing this
        // @Single bus) is still showing. The viewModelScope is already cancelled here, so use the
        // synchronous reset() rather than resetState().
        if (ownsInstall) eventBus.reset()
    }

    fun parsePackage(uri: Uri) {
        pendingUri = uri
        ownsInstall = true
        viewModelScope.launch {
            eventBus.emit(InstallState.Parsing)
            val result = analyzer.analyze(uri)

            result.fold(
                onSuccess = { meta ->
                    currentPackageName = meta.packageName

                    // getPackageInfo() and the privilege checks in checkPrivilegeAndModes()
                    // (isShizukuAvailable()/isDhizukuAvailable() are synchronous binder IPC)
                    // must not run on the main thread.
                    val existing = withContext(Dispatchers.IO) {
                        // Privilege detection is best-effort: an unexpected repository/
                        // binder IPC exception must not crash package parsing. On failure
                        // the available modes simply stay at their defaults (NORMAL) and
                        // parsing still proceeds to getPackageInfo so the user can install.
                        runCatching { checkPrivilegeAndModes(meta.packageName) }
                        runCatching {
                            packageManager.getPackageInfo(meta.packageName, 0)
                        }.getOrNull()
                    }

                    isUpdateOperation = existing != null
                    isDowngrade = if (existing != null) {
                        meta.versionCode < PackageInfoCompat.getLongVersionCode(existing)
                    } else false

                    eventBus.emit(
                        InstallState.ReadyToInstall(
                            meta = meta,
                            isUpdate = isUpdateOperation,
                            isDowngrade = isDowngrade,
                            oldVersion = existing?.versionName
                        )
                    )
                },
                onFailure = {
                    eventBus.emit(InstallState.Error(UiText.StringResource(R.string.error_parse_package)))
                }
            )
        }
    }

    private suspend fun checkPrivilegeAndModes(packageName: String) {
        val modes = mutableListOf(InstallMode.NORMAL)
        if (systemRepository.isRootAvailable()) modes.add(InstallMode.ROOT)
        if (systemRepository.isShizukuAvailable()) modes.add(InstallMode.SHIZUKU)
        if (systemRepository.isDhizukuAvailable()) modes.add(InstallMode.DHIZUKU)
        
        _availableModes.value = modes
        
        // Pick best available mode
        _installMode.value = when {
            modes.contains(InstallMode.DHIZUKU) -> InstallMode.DHIZUKU
            modes.contains(InstallMode.SHIZUKU) -> InstallMode.SHIZUKU
            modes.contains(InstallMode.ROOT) -> InstallMode.ROOT
            else -> InstallMode.NORMAL
        }
    }

    fun setInstallMode(mode: InstallMode) {
        _installMode.value = mode
    }

    fun startInstallation() {
        val uri = pendingUri ?: return
        ownsInstall = true
        val mode = _installMode.value

        if (isDowngrade && mode == InstallMode.NORMAL) {
            viewModelScope.launch {
                eventBus.emit(InstallState.Error(UiText.StringResource(R.string.error_downgrade_privilege)))
            }
            return
        }

        viewModelScope.launch {
            repository.installPackage(uri, mode, isDowngrade)
        }
    }
}
