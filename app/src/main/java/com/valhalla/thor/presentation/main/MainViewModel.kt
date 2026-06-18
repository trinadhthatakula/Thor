package com.valhalla.thor.presentation.main

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.domain.usecase.ShareAppUseCase
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.presentation.home.AppDestinations
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

/**
 * Side Effects: One-time events that the UI must handle (Navigation, Intents).
 */
sealed interface MainSideEffect {
    data class LaunchApp(val packageName: String) : MainSideEffect
    data class OpenAppSettings(val packageName: String) : MainSideEffect
    data class ShareApp(val uri: android.net.Uri) : MainSideEffect
    data class ShareApps(val uris: List<android.net.Uri>) : MainSideEffect
    data class NormalUninstall(val packageName: String) : MainSideEffect
}

/**
 * State for the Terminal Logger Dialog.
 */
data class LoggerState(
    val isVisible: Boolean = false,
    val title: String = "",
    val logs: List<String> = emptyList(),
    val isComplete: Boolean = false
)

/**
 * Main UI State holding global feedback.
 */
data class MainUiState(
    val actionMessage: UiText? = null, // For transient Toasts
    val loggerState: LoggerState = LoggerState(), // For persistent Logs
    val selectedDestination: AppDestinations = AppDestinations.HOME, // For Bottom Nav
    val hasShownSupportDeveloperPrompt: Boolean = true,
    val showSupportDeveloperPrompt: Boolean = false
)

@KoinViewModel
class MainViewModel(
    private val manageAppUseCase: ManageAppUseCase,
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val shareAppUseCase: ShareAppUseCase,
    private val packageManager: PackageManager,
    private val preferenceRepository: PreferenceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        MainUiState()
    )

    private var pendingSupportPrompt = false

    init {
        observePreferences()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferenceRepository.userPreferences.collect { prefs ->
                _uiState.update {
                    it.copy(
                        hasShownSupportDeveloperPrompt = prefs.hasShownSupportDeveloperPrompt
                    )
                }
            }
        }
    }

    fun markSupportDeveloperPromptShown() {
        viewModelScope.launch(Dispatchers.IO) {
            preferenceRepository.setHasShownSupportDeveloperPrompt(true)
        }
        _uiState.update {
            it.copy(
                showSupportDeveloperPrompt = false,
                hasShownSupportDeveloperPrompt = true
            )
        }
    }

    fun dismissSupportDeveloperPrompt() {
        _uiState.update { it.copy(showSupportDeveloperPrompt = false) }
    }

    private fun triggerSupportPromptIfNeeded() {
        if (!_uiState.value.hasShownSupportDeveloperPrompt) {
            if (_uiState.value.loggerState.isVisible) {
                pendingSupportPrompt = true
            } else {
                _uiState.update { it.copy(showSupportDeveloperPrompt = true) }
            }
        }
    }

    private val _effect = Channel<MainSideEffect>()
    val effect = _effect.receiveAsFlow()

    // --- State Management Helpers ---

    fun consumeMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    fun dismissLogger() {
        _uiState.update { it.copy(loggerState = LoggerState(isVisible = false)) }
        if (pendingSupportPrompt) {
            pendingSupportPrompt = false
            triggerSupportPromptIfNeeded()
        }
    }

    fun onDestinationSelected(destination: AppDestinations) {
        _uiState.update { it.copy(selectedDestination = destination) }
    }

    private fun startLogger(title: String) {
        _uiState.update {
            it.copy(
                loggerState = LoggerState(
                    isVisible = true,
                    title = title,
                    logs = listOf("Initializing...")
                )
            )
        }
    }

    private fun addLog(message: String) {
        _uiState.update { state ->
            val newLogs = state.loggerState.logs + message
            state.copy(loggerState = state.loggerState.copy(logs = newLogs))
        }
    }

    private fun finishLogger() {
        addLog("\nOperation Complete.")
        _uiState.update { state ->
            state.copy(loggerState = state.loggerState.copy(isComplete = true))
        }
    }

    fun clearAllCache(type: AppListType) {
        viewModelScope.launch {
            startLogger("Preparing Cache Cleanup...")

            // 1. Fetch current list
            val (userApps, systemApps) = getInstalledAppsUseCase().first()
            val targetList = if (type == AppListType.USER) userApps else systemApps

            // 2. Filter out self and Play Store to be safe
            val safeList = targetList.filter {
                it.packageName != "com.valhalla.thor" &&
                        it.packageName != "com.android.vending"
            }

            if (safeList.isEmpty()) {
                addLog("No eligible apps found.")
                finishLogger()
                return@launch
            }

            dismissLogger() // Switch to batch logger
            onMultiAppAction(MultiAppAction.ClearCache(safeList))
        }
    }

    // --- Single App Action Handler ---

    fun onAppAction(action: AppClickAction) {
        viewModelScope.launch {
            when (action) {
                // 1. SMART LAUNCH
                is AppClickAction.Launch -> {
                    if (!action.appInfo.enabled) {
                        // Quick toast for feedback, or could use logger if preferred.
                        // Using Toast here for speed.
                        _uiState.update {
                            it.copy(
                                actionMessage = UiText.StringResource(
                                    R.string.unfreezing_app,
                                    action.appInfo.appName ?: action.appInfo.packageName
                                )
                            )
                        }

                        val result =
                            manageAppUseCase.setAppDisabled(action.appInfo.packageName, false)
                        if (result.isSuccess) {
                            _effect.send(MainSideEffect.LaunchApp(action.appInfo.packageName))
                        } else {
                            _uiState.update {
                                it.copy(
                                    actionMessage = UiText.StringResource(
                                        R.string.error_format,
                                        result.exceptionOrNull()?.message ?: ""
                                    )
                                )
                            }
                        }
                    } else {
                        _effect.send(MainSideEffect.LaunchApp(action.appInfo.packageName))
                    }
                }

                // 2. SETTINGS
                is AppClickAction.AppInfoSettings -> {
                    _effect.send(MainSideEffect.OpenAppSettings(action.appInfo.packageName))
                }

                // 3. SHARE (Heavy I/O -> Use Logger)
                is AppClickAction.Share -> {
                    startLogger("Sharing ${action.appInfo.appName}")
                    addLog("Preparing files...")

                    val result = shareAppUseCase(action.appInfo)

                    if (result.isSuccess) {
                        addLog("✔ Files Ready")
                        // Dismiss logger immediately so user sees the Share Sheet
                        dismissLogger()
                        _effect.send(MainSideEffect.ShareApp(result.getOrThrow()))
                    } else {
                        addLog("✘ Error: ${result.exceptionOrNull()?.message}")
                        finishLogger() // Keep open to show error
                    }
                }

                // 4. REINSTALL (Complex -> Use Logger)
                is AppClickAction.Reinstall -> {
                    startLogger("Reinstalling ${action.appInfo.appName}")
                    addLog("Applying Google Play Store signature...")

                    withContext(Dispatchers.IO) {
                        val result =
                            manageAppUseCase.reinstallAppWithGoogle(action.appInfo.packageName)
                        if (result.isSuccess) {
                            addLog("✔ Reinstall successful")
                            triggerSupportPromptIfNeeded()
                        }
                        else addLog("✘ Failed: ${result.exceptionOrNull()?.message}")
                    }
                    finishLogger()
                }

                // 5. UNINSTALL (System = Risky -> Logger / User = Fast -> Toast)
                is AppClickAction.Uninstall -> {
                    if (action.appInfo.isSystem) {
                        startLogger("Uninstalling System App")
                        addLog("Target: ${action.appInfo.appName}")
                        withContext(Dispatchers.IO) {
                            val result = manageAppUseCase.uninstallApp(action.appInfo.packageName)
                            if (result.isSuccess) {
                                addLog("✔ Uninstall successful")
                                triggerSupportPromptIfNeeded()
                            }
                            else {
                                addLog("✘ Privileged uninstall failed")
                                addLog("Attempting system uninstall...")
                                _effect.send(MainSideEffect.NormalUninstall(action.appInfo.packageName))
                            }
                        }
                        finishLogger()
                    } else {
                        viewModelScope.launch(Dispatchers.IO) {
                            val result = manageAppUseCase.uninstallApp(action.appInfo.packageName)
                            if (result.isSuccess) {
                                _uiState.update {
                                    it.copy(
                                        actionMessage = UiText.StringResource(
                                            R.string.uninstall_success,
                                            action.appInfo.appName ?: action.appInfo.packageName
                                        )
                                    )
                                }
                                triggerSupportPromptIfNeeded()
                            } else {
                                _effect.send(MainSideEffect.NormalUninstall(action.appInfo.packageName))
                            }
                        }
                    }
                }

                // 6. REINSTALL ALL (Batch Logic Triggered via Single Action Enum)
                AppClickAction.ReinstallAll -> {
                    startLogger("Scanning Apps...")
                    // Fetch user apps flows, take first emission
                    val (userApps, _) = getInstalledAppsUseCase().first()

                    val targets = userApps.filter {
                        it.installerPackageName != "com.android.vending" &&
                                it.installerPackageName != "com.google.android.packageinstaller"
                    }

                    if (targets.isEmpty()) {
                        addLog("No apps found that require fixing.")
                        finishLogger()
                    } else {
                        addLog("Found ${targets.size} apps to fix.")
                        dismissLogger() // Dismiss scan logger, start batch logger
                        onMultiAppAction(MultiAppAction.ReInstall(targets))
                    }
                }

                // 7. QUICK ACTIONS (Kill, Freeze, Unfreeze, Cache) -> Toast
                is AppClickAction.Kill -> quickAction(action) { manageAppUseCase.forceStop(it.packageName) }
                is AppClickAction.Freeze -> quickAction(action) {
                    manageAppUseCase.setAppDisabled(
                        it.packageName,
                        true
                    )
                }

                is AppClickAction.UnFreeze -> quickAction(action) {
                    manageAppUseCase.setAppDisabled(
                        it.packageName,
                        false
                    )
                }

                is AppClickAction.ClearCache -> quickAction(action) { manageAppUseCase.clearCache(it.packageName) }
                is AppClickAction.ClearData -> quickAction(action) {
                    manageAppUseCase.clearAppData(
                        it.packageName
                    )
                }

                is AppClickAction.Suspend -> quickAction(action) {
                    manageAppUseCase.setAppSuspended(
                        it.packageName,
                        true
                    )
                }

                is AppClickAction.UnSuspend -> quickAction(action) {
                    manageAppUseCase.setAppSuspended(
                        it.packageName,
                        false
                    )
                }

                is AppClickAction.ManagePermissions -> {
                    // Handled directly in Compose UI layer via Navigation 3
                }
            }
        }
    }

    // --- Multi App Action Handler ---

    fun onMultiAppAction(action: MultiAppAction) {
        viewModelScope.launch {
            when (action) {
                is MultiAppAction.ReInstall -> performLoggedMultiAction(
                    "Reinstalling Apps",
                    action.appList
                ) { appInfo ->
                    val result = manageAppUseCase.reinstallAppWithGoogle(appInfo.packageName)
                    if (result.isSuccess) {
                        result
                    } else {
                        try {
                            packageManager.getPackageInfo(
                                appInfo.packageName,
                                0
                            ).applicationInfo?.let { appInfo ->
                                val isDebuggable =
                                    (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                                if (isDebuggable) {
                                    Result.failure(Exception("App is Debuggable (Signature mismatch likely)"))
                                } else {
                                    result // Return original error
                                }
                            } ?: result

                        } catch (_: Exception) {
                            result // Return original error if package check fails
                        }
                    }
                }

                is MultiAppAction.Freeze -> performLoggedMultiAction(
                    "Freezing Apps",
                    action.appList
                ) {
                    manageAppUseCase.setAppDisabled(it.packageName, disabled = true)
                }

                is MultiAppAction.UnFreeze -> performLoggedMultiAction(
                    "Unfreezing Apps",
                    action.appList
                ) {
                    manageAppUseCase.setAppDisabled(it.packageName, disabled = false)
                }

                is MultiAppAction.Kill -> performLoggedMultiAction("Killing Apps", action.appList) {
                    manageAppUseCase.forceStop(it.packageName)
                }

                is MultiAppAction.ClearCache -> performLoggedMultiAction(
                    "Clearing Caches",
                    action.appList
                ) {
                    manageAppUseCase.clearCache(it.packageName)
                }

                is MultiAppAction.Uninstall -> performLoggedMultiAction(
                    "Uninstalling Apps",
                    action.appList
                ) {
                    manageAppUseCase.uninstallApp(it.packageName)
                }

                is MultiAppAction.Suspend -> performLoggedMultiAction(
                    "Suspending Apps",
                    action.appList
                ) {
                    manageAppUseCase.setAppSuspended(it.packageName, true)
                }

                is MultiAppAction.UnSuspend -> performLoggedMultiAction(
                    "Unsuspending Apps",
                    action.appList
                ) {
                    manageAppUseCase.setAppSuspended(it.packageName, false)
                }

                is MultiAppAction.ClearData -> performLoggedMultiAction(
                    "Clearing Data",
                    action.appList
                ) {
                    manageAppUseCase.clearAppData(it.packageName)
                }

                is MultiAppAction.Share -> {
                    viewModelScope.launch {
                        startLogger("Sharing Apps")
                        val uris = mutableListOf<android.net.Uri>()

                        withContext(Dispatchers.IO) {
                            action.appList.forEachIndexed { index, app ->
                                addLog("[${index + 1}/${action.appList.size}] Preparing ${app.appName}...")
                                val result = shareAppUseCase(app)
                                if (result.isSuccess) {
                                    uris.add(result.getOrThrow())
                                    addLog(" -> Ready")
                                } else {
                                    addLog(" -> Failed: ${result.exceptionOrNull()?.message}")
                                }
                            }
                        }

                        if (uris.isNotEmpty()) {
                            dismissLogger()
                            _effect.send(MainSideEffect.ShareApps(uris))
                        } else {
                            finishLogger()
                        }
                    }
                }
            }
        }
    }

    // --- Helper Implementations ---

    /**
     * Executes a batch operation and updates the Logger Dialog.
     */
    private suspend fun performLoggedMultiAction(
        title: String,
        apps: List<AppInfo>,
        block: suspend (AppInfo) -> Result<Unit>
    ) {
        startLogger(title)
        var hasAtLeastOneSuccess = false

        withContext(Dispatchers.IO) {
            apps.forEachIndexed { index, app ->
                addLog("[${index + 1}/${apps.size}] ${app.appName}...")
                val result = block(app)
                if (result.isSuccess) {
                    addLog(" -> Success")
                    hasAtLeastOneSuccess = true
                } else {
                    addLog(" -> Failed: ${result.exceptionOrNull()?.message}")
                }
            }
        }

        finishLogger()
        if (hasAtLeastOneSuccess) {
            triggerSupportPromptIfNeeded()
        }
    }

    /**
     * Executes a quick single action and shows a Toast on completion.
     */
    private suspend fun quickAction(
        action: AppClickAction,
        block: suspend (AppInfo) -> Result<Unit>
    ) {
        val app = action.appInfo()
        if (app != null)
            block(app)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            actionMessage = getSuccessMessage(
                                action,
                                app.appName ?: app.packageName
                            )
                        )
                    }
                    triggerSupportPromptIfNeeded()
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            actionMessage = UiText.StringResource(
                                R.string.error_format,
                                e.message ?: ""
                            )
                        )
                    }
                }
        else {
            _uiState.update { it.copy(actionMessage = UiText.StringResource(R.string.error_app_info_missing)) }
        }
    }

    private fun getSuccessMessage(action: AppClickAction, appName: String): UiText {
        return when (action) {
            is AppClickAction.Kill -> UiText.StringResource(R.string.killed_success, appName)
            is AppClickAction.Freeze -> UiText.StringResource(R.string.frozen_success, appName)
            is AppClickAction.UnFreeze -> UiText.StringResource(R.string.unfrozen_success, appName)
            is AppClickAction.ClearCache -> UiText.StringResource(
                R.string.cache_cleared_success,
                appName
            )

            is AppClickAction.ClearData -> UiText.StringResource(
                R.string.data_cleared_success,
                appName
            )

            is AppClickAction.Suspend -> UiText.StringResource(R.string.suspended_success, appName)
            is AppClickAction.UnSuspend -> UiText.StringResource(
                R.string.unsuspended_success,
                appName
            )

            else -> UiText.StringResource(
                R.string.action_completed_format,
                action.javaClass.simpleName,
                appName
            )
        }
    }

    // Helper to extract AppInfo from the sealed interface safely
    private fun AppClickAction.appInfo(): AppInfo? = when (this) {
        is AppClickAction.Kill -> appInfo
        is AppClickAction.Freeze -> appInfo
        is AppClickAction.UnFreeze -> appInfo
        is AppClickAction.ClearCache -> appInfo
        is AppClickAction.Uninstall -> appInfo
        is AppClickAction.Launch -> appInfo
        is AppClickAction.Share -> appInfo
        is AppClickAction.Reinstall -> appInfo
        is AppClickAction.AppInfoSettings -> appInfo
        is AppClickAction.ClearData -> appInfo
        is AppClickAction.Suspend -> appInfo
        is AppClickAction.UnSuspend -> appInfo
        else -> null
    }
}
