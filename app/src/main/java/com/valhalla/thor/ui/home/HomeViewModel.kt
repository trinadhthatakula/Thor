package com.valhalla.thor.ui.home

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.AppInfoGrabber
import com.valhalla.thor.model.MultiAppAction
import com.valhalla.thor.model.clearCache
import com.valhalla.thor.model.disableApps
import com.valhalla.thor.model.enableApps
import com.valhalla.thor.model.killApp
import com.valhalla.thor.model.killApps
import com.valhalla.thor.model.launchApp
import com.valhalla.thor.model.openAppInfoScreen
import com.valhalla.thor.model.reInstallAppsWithGoogle
import com.valhalla.thor.model.reInstallWithGoogle
import com.valhalla.thor.model.rootAvailable
import com.valhalla.thor.model.shareApp
import com.valhalla.thor.model.shareSplitApks
import com.valhalla.thor.model.shizuku.ElevatableState
import com.valhalla.thor.model.uninstallSystemApp
import com.valhalla.thor.ui.widgets.AppClickAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val userApps: List<AppInfo> = emptyList(),
    val systemApps: List<AppInfo> = emptyList(),
    val isRefreshing: Boolean = false,
    val showExitDialog: Boolean = false,
    val exitPermitted: Boolean = false,
    val appAction: AppClickAction? = null,
    val multiAppAction: MultiAppAction? = null,
    val termLoggerTitle: String = "",
    val logObserver: List<String> = emptyList(),
    val showTerminate: Boolean = false,
    val showConfirmation: Boolean = false,
    val selectedDestinationIndex : Int = AppDestinations.HOME.ordinal
)

class HomeViewModel(val appInfoGrabber: AppInfoGrabber) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()

    init {
        refreshAppList()
    }

    fun refreshAppList() {
        _uiState.update {
            it.copy(
                isRefreshing = true
            )
        }
        viewModelScope.launch {
            val userApps = appInfoGrabber.getUserApps()
            val systemApps = appInfoGrabber.getSystemApps()
            _uiState.update {
                it.copy(
                    userApps = userApps,
                    systemApps = systemApps,
                    isRefreshing = false
                )
            }
        }
    }

    fun selectDestination(destinations: AppDestinations){
        _uiState.update {
            it.copy(
                selectedDestinationIndex = destinations.ordinal
            )
        }
    }

    fun selectDestination(pos: Int){
        _uiState.update {
            it.copy(
                selectedDestinationIndex = pos
            )
        }
    }

    fun showExitDialog(show: Boolean) = _uiState.update {
        it.copy(
            showExitDialog = show
        )
    }

    fun clearActions() = _uiState.update {
        it.copy(
            logObserver = emptyList(),
            appAction = null,
            multiAppAction = null,
            showConfirmation = false,
            exitPermitted = false
        )
    }

    fun onMultiAppAction(multiAppAction: MultiAppAction) = _uiState.update {
        it.copy(
            multiAppAction = multiAppAction,
            logObserver = emptyList(),
            showConfirmation = true
        )
    }

    fun processMultiAppAction(
        context: Context,
        elevatableState: ElevatableState = ElevatableState.NONE,
        multiAction: MultiAppAction,
        exit: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    multiAppAction = multiAction,
                    logObserver = emptyList(),
                    exitPermitted = false,
                    termLoggerTitle = when (multiAction) {
                        is MultiAppAction.Freeze -> "Freezing Apps.,"
                        is MultiAppAction.Kill -> "Killing Apps..,"
                        is MultiAppAction.ReInstall -> "Reinstalling Apps..,"
                        is MultiAppAction.Share -> "Share Apps"
                        is MultiAppAction.UnFreeze -> "UnFreezing Apps..,"
                        is MultiAppAction.Uninstall -> "Uninstalling Apps..,"
                        is MultiAppAction.ClearCache -> "Clearing Cache..,"
                    }
                )
            }
            when (multiAction) {
                is MultiAppAction.ClearCache -> {
                    val appList =
                        multiAction.appList.filter {
                            it.packageName != BuildConfig.APPLICATION_ID
                                    && it.packageName != "com.android.vending"
                        }
                    clearCache(
                        *appList.toTypedArray(),
                        elevatableState = elevatableState,
                        observer = {
                            addLog(it)
                        },
                        exit = exit
                    )
                }

                is MultiAppAction.Freeze -> {
                    val selectedAppInfos = multiAction.appList
                    val activeApps =
                        selectedAppInfos.filter { it.enabled && it.packageName != BuildConfig.APPLICATION_ID }
                    context.disableApps(
                        *activeApps.toTypedArray(),
                        observer = {
                            addLog(it)
                        },
                        exit = exit,
                        elevatableState = elevatableState
                    )
                }

                is MultiAppAction.Kill -> {
                    killApps(
                        *multiAction.appList.filter { it.packageName != BuildConfig.APPLICATION_ID }
                            .toTypedArray(),
                        observer = {
                            addLog(it)
                        }, exit = exit
                    )
                }

                is MultiAppAction.ReInstall -> {
                    reInstallAppsWithGoogle(
                        multiAction.appList.filter { it.installerPackageName != "com.android.vending" }
                            .toMutableList().apply {
                                val thor =
                                    firstOrNull { it.packageName == BuildConfig.APPLICATION_ID }
                                if (thor != null) {
                                    remove(thor)
                                    add(thor)
                                }
                            },
                        observer = {
                            addLog(it)
                        },
                        exit = exit
                    )
                }

                is MultiAppAction.Share -> {

                }

                is MultiAppAction.UnFreeze -> {
                    val selectedAppInfos = multiAction.appList
                    val frozenApps = selectedAppInfos.filter { it.enabled.not() }
                    context.enableApps(
                        *frozenApps.toTypedArray(),
                        elevatableState = elevatableState,
                        observer = {
                            addLog(it)
                        },
                        exit = exit
                    )
                }

                is MultiAppAction.Uninstall -> {
                    (multiAction).appList.filter { it.packageName != BuildConfig.APPLICATION_ID }
                        .forEach {
                            try {
                                if (it.isSystem) {
                                    val result = uninstallSystemApp(it)
                                    addLog(
                                        "Uninstalling ${it.appName} : $result"
                                    )
                                } else {
                                    val appPackage = it.packageName
                                    val intent = Intent(Intent.ACTION_DELETE)
                                    intent.data = "package:${appPackage}".toUri()
                                    context.startActivity(intent)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                exit()
                            }
                        }
                }
            }
        }
    }

    fun permitExit() {
        _uiState.update {
            it.copy(
                exitPermitted = true
            )
        }
    }

    fun clearLogger() {
        _uiState.update {
            it.copy(
                logObserver = emptyList()
            )
        }
    }

    fun processAppAction(
        context: Context,
        elevatableState: ElevatableState = ElevatableState.NONE,
        appAction: AppClickAction,
        exit: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    appAction = appAction,
                    logObserver = emptyList(),
                    exitPermitted = false,
                    termLoggerTitle = when (appAction) {
                        is AppClickAction.AppInfoSettings -> "Opening AppInfo"
                        is AppClickAction.Freeze -> "Freezing"
                        is AppClickAction.Kill -> "War Machine"
                        is AppClickAction.Launch -> "Launch Pad"
                        is AppClickAction.Reinstall -> {
                            "Reinstalling App..,"
                        }

                        AppClickAction.ReinstallAll -> {
                            "Reinstalling Apps..,"
                        }

                        is AppClickAction.Share -> "Share App"
                        is AppClickAction.UnFreeze -> "Defrosting"
                        is AppClickAction.Uninstall -> "Uninstalling..,"
                        is AppClickAction.ClearCache -> "Clearing Cache..,"
                    }
                )
            }
            when (appAction) {

                /* is AppClickAction.Logcat -> {
                     appAction.appInfo.showLogs(
                         observer, exit
                     )
                 }*/

                is AppClickAction.ClearCache -> {
                    if (appAction.appInfo.packageName != BuildConfig.APPLICATION_ID && appAction.appInfo.packageName != "com.android.vending") {
                        clearCache(
                            appAction.appInfo,
                            observer = { log ->
                                _uiState.update {
                                    val tempList = it.logObserver.toMutableList()
                                    tempList.add(log)
                                    it.copy(
                                        logObserver = tempList
                                    )
                                }
                            },
                            elevatableState = elevatableState,
                            exit = exit
                        )
                    }
                }

                is AppClickAction.Share -> {
                    if (appAction.appInfo.splitPublicSourceDirs.isEmpty())
                        shareApp(appAction.appInfo, context)
                    else shareSplitApks(appAction.appInfo, context, observer = {
                        addLog(it)
                    }, exit)
                }

                is AppClickAction.AppInfoSettings -> {
                    openAppInfoScreen(
                        context,
                        appAction.appInfo
                    )
                    exit()
                }

                is AppClickAction.Freeze -> {
                    context.disableApps(
                        appAction.appInfo,
                        observer = { log ->
                            _uiState.update {
                                val tempList = it.logObserver.toMutableList()
                                tempList.add(log)
                                it.copy(
                                    logObserver = tempList
                                )
                            }
                        },
                        exit = exit,
                        elevatableState = elevatableState
                    )
                }

                is AppClickAction.Kill -> {
                    try {
                        val killResult = killApp(appAction.appInfo)
                        if (killResult.isEmpty()) {
                            "Killed ${appAction.appInfo.appName}"
                        } else {
                            addLog("Failed to kill ${appAction.appInfo.appName}")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        exit()
                    }
                }

                is AppClickAction.Launch -> {
                    val appInfo = appAction.appInfo
                    try {
                        if (appInfo.enabled.not()) {
                            if (elevatableState == ElevatableState.SU || elevatableState == ElevatableState.SHIZUKU_RUNNING) {
                                context.enableApps(
                                    appInfo,
                                    elevatableState = elevatableState,
                                    observer = { log ->
                                        _uiState.update {
                                            val tempList = it.logObserver.toMutableList()
                                            tempList.add(log)
                                            it.copy(
                                                logObserver = tempList
                                            )
                                        }
                                    }
                                ) {
                                    if (launchApp(appInfo.packageName).isSuccess.not()) {
                                        addLog("Failed to launch ${appInfo.appName}")
                                    }
                                }
                            } else {
                                addLog("Failed to launch ${appInfo.appName}")
                            }
                        } else {
                            if (rootAvailable()) {
                                if (launchApp(appInfo.packageName).isSuccess.not()) {
                                    addLog("Failed to launch ${appInfo.appName}")
                                } else {
                                    addLog("Launching ${appInfo.appName}")
                                }
                            } else {
                                context.packageManager.getLaunchIntentForPackage(appInfo.packageName)
                                    ?.let {
                                        context.startActivity(it)
                                    } ?: run {

                                    addLog("Failed to launch ${appInfo.appName}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        exit()
                    }
                }

                is AppClickAction.Reinstall -> {
                    val appInfo = appAction.appInfo
                    addLog("Reinstalling ${appInfo.appName}")
                    if (rootAvailable()) {
                        reInstallWithGoogle(appInfo, { addLog(it) }, exit)
                    } else {
                        addLog("Root not found")
                        addLog("Root is Required to reinstall apps")
                        addLog("Grant root access in manager app then restart Thor")
                        addLog("\n")
                        exit()
                    }

                }

                AppClickAction.ReinstallAll -> {}

                is AppClickAction.UnFreeze -> {
                    context.enableApps(
                        appAction.appInfo,
                        elevatableState = elevatableState,
                        observer = { log ->
                            addLog(log)
                        },
                        exit = exit
                    )
                }

                is AppClickAction.Uninstall -> {
                    val it = appAction.appInfo
                    try {
                        if (it.isSystem) {
                            val result = uninstallSystemApp(it)
                            _uiState.update { state ->
                                val tempList = state.logObserver.toMutableList()
                                tempList.add("Uninstalling ${it.appName} : $result")
                                state.copy(
                                    logObserver = tempList
                                )
                            }
                        } else {
                            val appPackage = it.packageName
                            val intent = Intent(Intent.ACTION_DELETE)
                            intent.data = "package:${appPackage}".toUri()
                            context.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        exit()
                    }
                }

            }
        }

    }

    fun addLog(log: String) {
        _uiState.update {
            val tempList = it.logObserver.toMutableList()
            tempList.add(log)
            it.copy(
                logObserver = tempList
            )
        }
    }

}