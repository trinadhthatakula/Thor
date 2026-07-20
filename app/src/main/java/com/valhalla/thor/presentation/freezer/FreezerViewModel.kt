package com.valhalla.thor.presentation.freezer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.data.launcher.FreezerShortcutContract
import com.valhalla.thor.data.launcher.FreezerShortcutManager
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

// packageName + appName of an app frozen outside the freezer list — drives the "Add to Freezer" snackbar
data class FreezerPrompt(val packageName: String, val appName: String?)

// One-off UI feedback that must fire exactly once — kept off the UiState StateFlow so it isn't
// re-delivered on recomposition/config change. Collected in FreezerScreen via ObserveAsEvents.
sealed interface FreezerEvent {
    data class ShowToast(val message: UiText) : FreezerEvent
    data class ShowFreezerPrompt(val packageName: String, val appName: String?) : FreezerEvent
}

data class FreezerUiState(
    val isLoading: Boolean = true,
    val isRoot: Boolean = false,
    val isShizuku: Boolean = false,
    val freezerApps: List<AppInfo> = emptyList(),
    val freezerPackageNames: Set<String> = emptySet(),
    val allInstalledApps: List<AppInfo> = emptyList(),
    val multiSelection: Set<String> = emptySet(),
    val searchQuery: String = "",
    val manageSheetSearchQuery: String = "",
    val autoFreezeEnabled: Boolean = false,
    val freezerMode: FreezerMode = FreezerMode.FREEZE,
    val isDhizuku: Boolean = false,
    val hasShownDisabledAppsPrompt: Boolean = false,
    val appListType: AppListType = AppListType.USER,
    val isGrid: Boolean = true,
    val addFreezerToLauncher: Boolean = false
)

@KoinViewModel
class FreezerViewModel(
    private val freezerRepository: FreezerRepository,
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val manageAppUseCase: ManageAppUseCase,
    private val privilegeManager: PrivilegeManager,
    private val preferenceRepository: PreferenceRepository,
    private val freezerShortcutManager: FreezerShortcutManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(FreezerUiState())
    val uiState: StateFlow<FreezerUiState> = _uiState.asStateFlow()

    // Small DROP_OLDEST buffer so a load-failure event emitted from observeApps() in init() (before
    // the screen's collector reaches STARTED) is delivered once the screen subscribes rather than
    // silently dropped; emit() also never suspends off a collector.
    private val _events = MutableSharedFlow<FreezerEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<FreezerEvent> = _events.asSharedFlow()

    init {
        observeApps()
        observePreferences()
    }

    private fun observeApps() {
        viewModelScope.launch {
            try {
                combine(
                    freezerRepository.getAll(),
                    getInstalledAppsUseCase(),
                    privilegeManager.state
                ) { freezerPkgs, (userApps, systemApps), priv ->
                    val pkgSet = freezerPkgs.toSet()
                    val allApps = userApps + systemApps
                    Triple(pkgSet, allApps.filter { it.packageName in pkgSet }, allApps) to priv
                }
                    .flowOn(Dispatchers.Default)
                    .collect { (appsData, priv) ->
                        val (pkgSet, freezerApps, allApps) = appsData
                        _uiState.update {
                            it.copy(
                                // Hold the loader until the first privilege probe lands so
                                // freeze/unfreeze controls never flash disabled on cold start;
                                // privilege flags now update atomically with the app list.
                                isLoading = !priv.isReady,
                                freezerPackageNames = pkgSet,
                                freezerApps = freezerApps,
                                allInstalledApps = allApps,
                                isRoot = priv.root,
                                isShizuku = priv.shizuku,
                                isDhizuku = priv.dhizuku
                            )
                        }
                    }
            } catch (e: Exception) {
                Logger.e("FreezeViewModel", "observe apps failed", e)
                _uiState.update { it.copy(isLoading = false) }
                emitToast(UiText.StringResource(R.string.failed_to_load_apps))
            }
        }
    }

    // Freeze-all / Unfreeze-all are handled by the shared batch action
    // (MultiAppAction.Freeze / UnFreeze) so their progress streams into the
    // TermLoggerDialog; the toolbar in FreezerScreen dispatches them via onMultiAppAction.

    // --- Multi-select removal ---

    fun toggleSelection(packageName: String) {
        _uiState.update {
            val sel = it.multiSelection.toMutableSet()
            if (packageName in sel) sel.remove(packageName) else sel.add(packageName)
            it.copy(multiSelection = sel)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(multiSelection = emptySet()) }
    }

    fun selectAll(packageNames: Collection<String> = _uiState.value.freezerPackageNames) {
        _uiState.update { it.copy(multiSelection = packageNames.toSet()) }
    }

    fun updateListType(type: AppListType) {
        _uiState.update { it.copy(appListType = type) }
    }

    fun removeFromFreezer(packageNames: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val appByPkg = _uiState.value.allInstalledApps.associateBy { it.packageName }
            packageNames.forEach { pkg ->
                freezerRepository.remove(pkg)
                // Restore to active (unsuspend and/or enable) so a suspended app isn't stranded.
                val app = appByPkg[pkg]
                if (app != null) manageAppUseCase.restoreApp(pkg, app.enabled, app.isSuspended)
                else manageAppUseCase.forceUnfreeze(pkg)
                freezerShortcutManager.disableAppShortcut(pkg)
            }
            _uiState.update { it.copy(multiSelection = emptySet()) }
            emitToast(
                UiText.StringResource(
                    R.string.removed_from_freezer_success,
                    packageNames.size
                )
            )
        }
    }

    // --- Manage Sheet ---

    fun toggleManaged(packageName: String, add: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (add) {
                val freezeResult = if (_uiState.value.freezerMode == FreezerMode.SUSPEND)
                    manageAppUseCase.setAppSuspended(packageName, true)
                else manageAppUseCase.setAppDisabled(packageName, true)
                freezeResult
                    .onSuccess {
                        freezerRepository.add(packageName)
                    }
                    .onFailure { e ->
                        emitToast(UiText.StringResource(R.string.error_format, e.message ?: ""))
                    }
            } else {
                freezerRepository.remove(packageName)
                freezerShortcutManager.disableAppShortcut(packageName)
                val app = _uiState.value.freezerApps.firstOrNull { it.packageName == packageName }
                    ?: _uiState.value.allInstalledApps.firstOrNull { it.packageName == packageName }
                (if (app != null) manageAppUseCase.restoreApp(packageName, app.enabled, app.isSuspended)
                else manageAppUseCase.forceUnfreeze(packageName))
                    .onFailure { e ->
                        emitToast(UiText.StringResource(R.string.error_format, e.message ?: ""))
                    }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateManageSheetSearch(query: String) {
        _uiState.update { it.copy(manageSheetSearchQuery = query) }
    }

    // --- Snackbar from AppInfoDialog (app frozen outside freezer) ---

    fun addToFreezer(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            freezerRepository.add(packageName)
            emitToast(UiText.StringResource(R.string.added_to_freezer_success))
        }
    }

    fun showFreezerPrompt(packageName: String, appName: String?) {
        viewModelScope.launch {
            _events.emit(FreezerEvent.ShowFreezerPrompt(packageName, appName))
        }
    }

    // --- Single-app freeze/unfreeze (called from AppInfoDialog in FreezerScreen) ---

    fun freezeSingleApp(packageName: String, appName: String?, inFreezer: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val freezeResult = if (_uiState.value.freezerMode == FreezerMode.SUSPEND)
                manageAppUseCase.setAppSuspended(packageName, true)
            else manageAppUseCase.setAppDisabled(packageName, true)
            freezeResult
                .onSuccess {
                    freezerShortcutManager.refreshAppShortcut(packageName)
                    if (!inFreezer) {
                        _events.emit(FreezerEvent.ShowFreezerPrompt(packageName, appName))
                    } else {
                        emitToast(
                            UiText.StringResource(R.string.frozen_success, appName ?: packageName)
                        )
                    }
                }
                .onFailure { e ->
                    emitToast(UiText.StringResource(R.string.error_format, e.message ?: ""))
                }
        }
    }

    fun unfreezeSingleApp(packageName: String, appName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = _uiState.value.freezerApps.firstOrNull { it.packageName == packageName }
                ?: _uiState.value.allInstalledApps.firstOrNull { it.packageName == packageName }
            val restoreResult = if (app != null) {
                manageAppUseCase.restoreApp(packageName, app.enabled, app.isSuspended)
            } else {
                // Unknown state (e.g. externally uninstalled): clear both dimensions best-effort.
                manageAppUseCase.forceUnfreeze(packageName)
            }
            restoreResult
                .onSuccess {
                    freezerShortcutManager.refreshAppShortcut(packageName)
                    emitToast(
                        UiText.StringResource(R.string.unfrozen_success, appName ?: packageName)
                    )
                }
                .onFailure { e ->
                    emitToast(UiText.StringResource(R.string.error_format, e.message ?: ""))
                }
        }
    }

    // --- Feedback ---

    private suspend fun emitToast(text: UiText) = _events.emit(FreezerEvent.ShowToast(text))

    private fun observePreferences() {
        viewModelScope.launch {
            preferenceRepository.userPreferences.collect { prefs ->
                _uiState.update {
                    it.copy(
                        autoFreezeEnabled = prefs.autoFreezeEnabled,
                        freezerMode = prefs.freezerMode,
                        hasShownDisabledAppsPrompt = prefs.hasShownDisabledAppsPrompt,
                        isGrid = prefs.freezerIsGrid,
                        addFreezerToLauncher = prefs.addFreezerToLauncher
                    )
                }
            }
        }
    }

    fun setAutoFreezeEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            preferenceRepository.setAutoFreezeEnabled(enabled)
        }
    }

    fun setFreezerMode(mode: FreezerMode) {
        viewModelScope.launch(Dispatchers.IO) {
            preferenceRepository.setFreezerMode(mode)
        }
    }

    fun markDisabledAppsPromptShown() {
        viewModelScope.launch(Dispatchers.IO) {
            preferenceRepository.setHasShownDisabledAppsPrompt(true)
        }
    }

    fun toggleGridMode() {
        viewModelScope.launch(Dispatchers.IO) {
            preferenceRepository.toggleFreezerIsGrid()
        }
    }

    fun addAppsToFreezer(packageNames: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            packageNames.forEach { pkg ->
                freezerRepository.add(pkg)
            }
            emitToast(
                UiText.StringResource(
                    R.string.added_to_freezer_count_success,
                    packageNames.size
                )
            )
        }
    }

    // --- Launcher shortcuts (gated by the "Add Freezer to launcher" preference) ---

    fun isPinSupported(): Boolean = freezerShortcutManager.isPinSupported()

    fun pinAppToLauncher(app: AppInfo) {
        if (app.isSystem) return // v1: user apps only
        // Grayscale icon decode + binder pin request — keep it off Main to avoid jank.
        viewModelScope.launch(Dispatchers.Default) {
            freezerShortcutManager.pinAppShortcut(app.packageName, app.appName ?: app.packageName)
        }
    }

    fun pinAllToLauncher() {
        // Pin sequentially (suspending) off Main: a rapid loop of the fire-and-forget pinAppShortcut
        // would spawn N concurrent bitmap decodes + binder pin requests and risk OOM / overwhelming
        // the shortcut service. A small gap keeps the per-shortcut system prompts orderly too.
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value.freezerApps
                .filter { !it.isSystem }
                .forEach {
                    freezerShortcutManager.pinAppShortcutSuspend(it.packageName, it.appName ?: it.packageName)
                    delay(100)
                }
        }
    }

    fun pinBulkShortcut(freeze: Boolean) {
        // Rasterizes a 216x216 tile bitmap + issues a binder pin request; keep it off Main
        // to avoid click-time jank, matching pinAppToLauncher / pinAllToLauncher.
        viewModelScope.launch(Dispatchers.Default) {
            freezerShortcutManager.pinBulkShortcut(
                if (freeze) FreezerShortcutContract.ACTION_FREEZE_ALL
                else FreezerShortcutContract.ACTION_UNFREEZE_ALL
            )
        }
    }
}
