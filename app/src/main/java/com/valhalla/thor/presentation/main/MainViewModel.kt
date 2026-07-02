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
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.domain.usecase.ShareAppUseCase
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.presentation.home.AppDestinations
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.UiTextException
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
    val title: UiText = UiText.DynamicString(""),
    val logs: List<UiText> = emptyList(),
    val isComplete: Boolean = false
)

/**
 * Compact count-only progress for bulk freeze / unfreeze. Unlike [LoggerState] it
 * never lists app names — just a live `processed / total` count — and auto-dismisses
 * shortly after a fully-successful run.
 */
data class FreezeLoggerState(
    val isVisible: Boolean = false,
    val isFreeze: Boolean = true,
    val total: Int = 0,
    val processed: Int = 0,
    val failed: Int = 0,
    val isComplete: Boolean = false
)

/**
 * Main UI State holding global feedback.
 */
data class MainUiState(
    val actionMessage: UiText? = null, // For transient Toasts
    val loggerState: LoggerState = LoggerState(), // For persistent Logs
    val freezeLoggerState: FreezeLoggerState = FreezeLoggerState(), // Compact freeze/unfreeze progress
    val selectedDestination: AppDestinations = AppDestinations.HOME, // For Bottom Nav
    val hasShownSupportDeveloperPrompt: Boolean = true,
    val showSupportDeveloperPrompt: Boolean = false,
    val prefs: UserPreferences = UserPreferences()
)

@KoinViewModel
class MainViewModel(
    private val manageAppUseCase: ManageAppUseCase,
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val shareAppUseCase: ShareAppUseCase,
    private val packageManager: PackageManager,
    private val preferenceRepository: PreferenceRepository,
    private val freezerRepository: FreezerRepository
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
                        hasShownSupportDeveloperPrompt = prefs.hasShownSupportDeveloperPrompt,
                        prefs = prefs
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

    private fun startLogger(title: UiText) {
        _uiState.update {
            it.copy(
                loggerState = LoggerState(
                    isVisible = true,
                    title = title,
                    logs = listOf(UiText.StringResource(R.string.log_initializing))
                )
            )
        }
    }

    private fun addLog(message: UiText) {
        _uiState.update { state ->
            val newLogs = state.loggerState.logs + message
            state.copy(loggerState = state.loggerState.copy(logs = newLogs))
        }
    }

    private fun finishLogger() {
        addLog(UiText.StringResource(R.string.log_op_complete))
        _uiState.update { state ->
            state.copy(loggerState = state.loggerState.copy(isComplete = true))
        }
    }

    fun clearAllCache(type: AppListType) {
        viewModelScope.launch {
            startLogger(UiText.StringResource(R.string.log_preparing_cache))

            // 1. Fetch current list
            val (userApps, systemApps) = getInstalledAppsUseCase().first()
            val targetList = if (type == AppListType.USER) userApps else systemApps

            // 2. Filter out self and Play Store to be safe
            val safeList = targetList.filter {
                it.packageName != "com.valhalla.thor" &&
                        it.packageName != "com.android.vending"
            }

            if (safeList.isEmpty()) {
                addLog(UiText.StringResource(R.string.log_no_eligible_apps))
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
                    startLogger(UiText.StringResource(R.string.log_sharing_app, action.appInfo.appName ?: ""))
                    addLog(UiText.StringResource(R.string.log_preparing_files))

                    val result = shareAppUseCase(action.appInfo)

                    if (result.isSuccess) {
                        addLog(UiText.StringResource(R.string.log_files_ready))
                        dismissLogger()
                        _effect.send(MainSideEffect.ShareApp(result.getOrThrow()))
                    } else {
                        addLog(UiText.StringResource(R.string.log_error, result.exceptionOrNull()?.message ?: ""))
                        finishLogger()
                    }
                }

                // 4. REINSTALL (Complex -> Use Logger)
                is AppClickAction.Reinstall -> {
                    startLogger(UiText.StringResource(R.string.log_reinstalling_app, action.appInfo.appName ?: ""))
                    addLog(UiText.StringResource(R.string.log_applying_play_store_sig))

                    withContext(Dispatchers.IO) {
                        val result =
                            manageAppUseCase.reinstallAppWithGoogle(action.appInfo.packageName)
                        if (result.isSuccess) {
                            addLog(UiText.StringResource(R.string.log_reinstall_success))
                            triggerSupportPromptIfNeeded()
                        } else {
                            addLog(UiText.StringResource(R.string.log_failed_with_msg, result.exceptionOrNull()?.message ?: ""))
                        }
                    }
                    finishLogger()
                }

                // 5. UNINSTALL (System = Risky -> Logger / User = Fast -> Toast)
                is AppClickAction.Uninstall -> {
                    if (action.appInfo.isSystem) {
                        startLogger(UiText.StringResource(R.string.log_uninstalling_system_app))
                        addLog(UiText.StringResource(R.string.log_target_app, action.appInfo.appName ?: ""))
                        withContext(Dispatchers.IO) {
                            val result = manageAppUseCase.uninstallApp(action.appInfo.packageName)
                            if (result.isSuccess) {
                                addLog(UiText.StringResource(R.string.log_uninstall_success))
                                freezerRepository.add(action.appInfo.packageName)
                                triggerSupportPromptIfNeeded()
                            } else {
                                addLog(UiText.StringResource(R.string.log_priv_uninstall_failed))
                                addLog(UiText.StringResource(R.string.log_attempting_system_uninstall))
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
                    startLogger(UiText.StringResource(R.string.log_scanning_apps))
                    val (userApps, _) = getInstalledAppsUseCase().first()

                    val targets = userApps.filter {
                        it.installerPackageName != "com.android.vending" &&
                                it.installerPackageName != "com.google.android.packageinstaller"
                    }

                    if (targets.isEmpty()) {
                        addLog(UiText.StringResource(R.string.log_no_apps_to_fix))
                        finishLogger()
                    } else {
                        addLog(UiText.StringResource(R.string.log_found_apps_to_fix, targets.size))
                        dismissLogger()
                        onMultiAppAction(MultiAppAction.ReInstall(targets))
                    }
                }

                // 7. QUICK ACTIONS
                is AppClickAction.Kill -> quickAction(action) { manageAppUseCase.forceStop(it.packageName) }
                is AppClickAction.Freeze -> quickAction(action) { manageAppUseCase.setAppDisabled(it.packageName, true) }
                is AppClickAction.UnFreeze -> quickAction(action) { manageAppUseCase.setAppDisabled(it.packageName, false) }
                is AppClickAction.ClearCache -> quickAction(action) { manageAppUseCase.clearCache(it.packageName) }
                is AppClickAction.ClearData -> quickAction(action) { manageAppUseCase.clearAppData(it.packageName) }
                is AppClickAction.Suspend -> quickAction(action) { manageAppUseCase.setAppSuspended(it.packageName, true) }
                is AppClickAction.UnSuspend -> quickAction(action) { manageAppUseCase.setAppSuspended(it.packageName, false) }
                is AppClickAction.ManagePermissions -> {}
                is AppClickAction.OpenDetails -> {}
            }
        }
    }

    // --- Multi App Action Handler ---

    fun onMultiAppAction(action: MultiAppAction) {
        viewModelScope.launch {
            when (action) {
                is MultiAppAction.ReInstall -> performLoggedMultiAction(
                    UiText.StringResource(R.string.log_reinstalling_batch),
                    action.appList
                ) { appInfo ->
                    val result = manageAppUseCase.reinstallAppWithGoogle(appInfo.packageName)
                    if (result.isSuccess) {
                        result
                    } else {
                        try {
                            packageManager.getPackageInfo(appInfo.packageName, 0).applicationInfo?.let { info ->
                                val isDebuggable = (info.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                                if (isDebuggable) {
                                    Result.failure(UiTextException(UiText.StringResource(R.string.error_debuggable_app)))
                                } else {
                                    result
                                }
                            } ?: result
                        } catch (_: Exception) {
                            result
                        }
                    }
                }

                is MultiAppAction.Freeze -> performCountedFreeze(action.appList, isFreeze = true)

                is MultiAppAction.UnFreeze -> performCountedFreeze(action.appList, isFreeze = false)

                is MultiAppAction.Kill -> performLoggedMultiAction(
                    UiText.StringResource(R.string.log_killing_batch),
                    action.appList
                ) {
                    manageAppUseCase.forceStop(it.packageName)
                }

                is MultiAppAction.ClearCache -> performLoggedMultiAction(
                    UiText.StringResource(R.string.log_clearing_cache_batch),
                    action.appList
                ) {
                    manageAppUseCase.clearCache(it.packageName)
                }

                is MultiAppAction.Uninstall -> performLoggedMultiAction(
                    UiText.StringResource(R.string.log_uninstalling_batch),
                    action.appList
                ) { appInfo ->
                    val isSystem = appInfo.isSystem
                    val isUadFailed = isSystem && appInfo.isUadLoadFailed
                    val isUnsafe = isSystem && appInfo.bloatRecommendation?.lowercase() == "unsafe"
                    if (isUadFailed || isUnsafe) {
                        Result.failure(UiTextException(UiText.StringResource(R.string.error_unsafe_skipped)))
                    } else {
                        val result = manageAppUseCase.uninstallApp(appInfo.packageName)
                        if (result.isSuccess && appInfo.isSystem) {
                            freezerRepository.add(appInfo.packageName)
                        }
                        result
                    }
                }

                is MultiAppAction.Suspend -> performLoggedMultiAction(
                    UiText.StringResource(R.string.log_suspending_batch),
                    action.appList
                ) {
                    manageAppUseCase.setAppSuspended(it.packageName, true)
                }

                is MultiAppAction.UnSuspend -> performLoggedMultiAction(
                    UiText.StringResource(R.string.log_unsuspending_batch),
                    action.appList
                ) {
                    manageAppUseCase.setAppSuspended(it.packageName, false)
                }

                is MultiAppAction.ClearData -> performLoggedMultiAction(
                    UiText.StringResource(R.string.log_clearing_data_batch),
                    action.appList
                ) {
                    manageAppUseCase.clearAppData(it.packageName)
                }

                is MultiAppAction.Share -> {
                    viewModelScope.launch {
                        startLogger(UiText.StringResource(R.string.log_sharing_batch))
                        val uris = mutableListOf<android.net.Uri>()

                        withContext(Dispatchers.IO) {
                            action.appList.forEachIndexed { index, app ->
                                addLog(UiText.StringResource(R.string.log_batch_preparing, index + 1, action.appList.size, app.appName ?: ""))
                                val result = shareAppUseCase(app)
                                if (result.isSuccess) {
                                    uris.add(result.getOrThrow())
                                    addLog(UiText.StringResource(R.string.log_ready))
                                } else {
                                    val exception = result.exceptionOrNull()
                                    val errorLog = if (exception is UiTextException) {
                                        UiText.StringResource(R.string.log_failed, exception.uiText)
                                    } else {
                                        UiText.StringResource(R.string.log_failed, exception?.message ?: "")
                                    }
                                    addLog(errorLog)
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

    /**
     * Bulk freeze / unfreeze with compact count-only progress ([FreezeLoggerState]).
     * Unsafe / UAD-failed system apps are excluded from the freeze set up-front (so the
     * total reflects only what we actually attempt), then each app is toggled
     * sequentially with a live `processed / total` count.
     */
    private suspend fun performCountedFreeze(apps: List<AppInfo>, isFreeze: Boolean) {
        val targets = if (isFreeze) {
            apps.filter { app ->
                !(app.isSystem && (app.isUadLoadFailed ||
                    app.bloatRecommendation?.lowercase() == "unsafe"))
            }
        } else {
            apps
        }

        _uiState.update {
            it.copy(
                freezeLoggerState = FreezeLoggerState(
                    isVisible = true,
                    isFreeze = isFreeze,
                    total = targets.size
                )
            )
        }

        var processed = 0
        var failed = 0
        withContext(Dispatchers.IO) {
            targets.forEach { app ->
                val result = manageAppUseCase.setAppDisabled(app.packageName, disabled = isFreeze)
                processed++
                if (result.isFailure) failed++
                val p = processed
                val f = failed
                _uiState.update {
                    it.copy(freezeLoggerState = it.freezeLoggerState.copy(processed = p, failed = f))
                }
            }
        }

        _uiState.update {
            it.copy(freezeLoggerState = it.freezeLoggerState.copy(isComplete = true))
        }
        if (processed - failed > 0) {
            triggerSupportPromptIfNeeded()
        }
    }

    fun dismissFreezeLogger() {
        _uiState.update { it.copy(freezeLoggerState = FreezeLoggerState()) }
    }

    private suspend fun performLoggedMultiAction(
        title: UiText,
        apps: List<AppInfo>,
        block: suspend (AppInfo) -> Result<Unit>
    ) {
        startLogger(title)
        var hasAtLeastOneSuccess = false

        withContext(Dispatchers.IO) {
            apps.forEachIndexed { index, app ->
                addLog(UiText.StringResource(R.string.log_batch_step, index + 1, apps.size, app.appName ?: ""))
                val result = block(app)
                if (result.isSuccess) {
                    addLog(UiText.StringResource(R.string.log_success))
                    hasAtLeastOneSuccess = true
                } else {
                    val exception = result.exceptionOrNull()
                    val errorLog = if (exception is UiTextException) {
                        UiText.StringResource(R.string.log_failed, exception.uiText)
                    } else {
                        UiText.StringResource(R.string.log_failed, exception?.message ?: "")
                    }
                    addLog(errorLog)
                }
            }
        }

        finishLogger()
        if (hasAtLeastOneSuccess) {
            triggerSupportPromptIfNeeded()
        }
    }

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
                    val errorText = if (e is UiTextException) e.uiText else UiText.DynamicString(e.message ?: "")
                    _uiState.update {
                        it.copy(
                            actionMessage = UiText.StringResource(
                                R.string.error_format,
                                errorText
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
            is AppClickAction.ClearCache -> UiText.StringResource(R.string.cache_cleared_success, appName)
            is AppClickAction.ClearData -> UiText.StringResource(R.string.data_cleared_success, appName)
            is AppClickAction.Suspend -> UiText.StringResource(R.string.suspended_success, appName)
            is AppClickAction.UnSuspend -> UiText.StringResource(R.string.unsuspended_success, appName)
            else -> UiText.StringResource(R.string.action_completed_format, action.javaClass.simpleName, appName)
        }
    }

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
