package com.valhalla.thor.ui.home

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
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
import com.valhalla.thor.model.shizuku.ShizukuManager
import com.valhalla.thor.model.stopLogger
import com.valhalla.thor.model.uninstallSystemApp
import com.valhalla.thor.ui.screens.AppListScreen
import com.valhalla.thor.ui.screens.FreezerScreen
import com.valhalla.thor.ui.screens.HomeActions
import com.valhalla.thor.ui.screens.HomeScreen
import com.valhalla.thor.ui.widgets.AffirmationDialog
import com.valhalla.thor.ui.widgets.AppClickAction
import com.valhalla.thor.ui.widgets.MultiAppAffirmationDialog
import com.valhalla.thor.ui.widgets.TermLoggerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun HomePage(
    modifier: Modifier = Modifier,
    shizukuManager: ShizukuManager = viewModel(),
    onExit: () -> Unit
) {

    var selectedDestination: AppDestinations by rememberSaveable {
        mutableStateOf(AppDestinations.HOME)
    }
    val context = LocalContext.current

    val grabber = AppInfoGrabber(context)
    var userAppList by remember { mutableStateOf(grabber.getUserApps()) }
    var systemAppList by remember { mutableStateOf(grabber.getSystemApps()) }
    var isRefreshing by remember { mutableStateOf(false) }

    var appAction: AppClickAction? by remember { mutableStateOf(null) }
    var multiAction: MultiAppAction? by remember { mutableStateOf(null) }

    var reinstalling by remember { mutableStateOf(false) }
    var canExit by remember { mutableStateOf(false) }
    var showTerminate by remember { mutableStateOf(false) }
    var logObserver by remember { mutableStateOf(emptyList<String>()) }
    var termLoggerTitle by remember { mutableStateOf("") }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            withContext(Dispatchers.IO) {
                userAppList = grabber.getUserApps()
                systemAppList = grabber.getSystemApps()
                delay(1000)
                isRefreshing = false
            }
        }
    }

    var getExitConfirmation by remember { mutableStateOf(false) }

    BackHandler {
        if (selectedDestination != AppDestinations.HOME)
            selectedDestination = AppDestinations.HOME
        else {
            if (logObserver.isEmpty())
                getExitConfirmation = true
        }
    }

    if (getExitConfirmation) {
        AffirmationDialog(
            text = "You want to exit?",
            onConfirm = { onExit() },
            onRejected = { getExitConfirmation = false }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppDestinations.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = selectedDestination == dest,
                        label = {
                            Text(stringResource(dest.label))
                        },
                        icon = {
                            Icon(
                                imageVector = ImageVector.vectorResource(if (selectedDestination == dest) dest.selectedIcon else dest.icon),
                                stringResource(dest.label)
                            )
                        },
                        onClick = {
                            /*if (dest == AppDestinations.SETTINGS)
                                Toast.makeText(context, "coming soon", Toast.LENGTH_SHORT).show()
                            else*/
                            selectedDestination = dest
                        }
                    )
                }
            }
        }
    ) {
        when (selectedDestination) {
            AppDestinations.HOME -> HomeScreen(
                modifier = modifier.padding(it),
                userAppList = userAppList,
                systemAppList = systemAppList,
                onHomeActions = { homeAction ->
                    when (homeAction) {
                        is HomeActions.ActiveApps -> {
                            selectedDestination = AppDestinations.APPS
                        }

                        HomeActions.BKI -> {}
                        is HomeActions.FrozenApps -> {
                            selectedDestination = AppDestinations.FREEZER
                        }

                        HomeActions.ReinstallAll -> {
                            multiAction = MultiAppAction.ReInstall(userAppList)
                        }

                        is HomeActions.ShowToast -> {
                            Toast.makeText(
                                context,
                                homeAction.text,
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        HomeActions.SwitchAutoReinstall -> {

                        }

                        is HomeActions.ClearCache -> {
                            val appInfo = homeAction.appInfos
                            multiAction = MultiAppAction.ClearCache(
                                appInfo.filter { ap -> ap.packageName != BuildConfig.APPLICATION_ID }
                            )

                        }

                    }
                }
            )

            AppDestinations.APPS -> AppListScreen(
                modifier = modifier.padding(it),
                userAppList = userAppList,
                systemAppList = systemAppList,
                isRefreshing = isRefreshing,
                onRefresh = { isRefreshing = true },
                onAppAction = { aAction ->
                    appAction = aAction
                },
                onMultiAppAction = { mAction ->
                    multiAction = mAction
                }
            )

            AppDestinations.FREEZER -> FreezerScreen(
                icon = R.drawable.frozen,
                modifier = modifier.padding(it),
                userAppList = userAppList,
                systemAppList = systemAppList,
                isRefreshing = isRefreshing,
                onRefresh = { isRefreshing = true },
                onAppAction = { aAction ->
                    appAction = aAction
                },
                onMultiAppAction = { mAction ->
                    multiAction = mAction
                }
            )

            //AppDestinations.SETTINGS -> Text("Settings")
        }

    }


    LaunchedEffect(appAction) {
        reinstalling = false
        canExit = false
        termLoggerTitle = when (appAction) {
            is AppClickAction.AppInfoSettings -> "Opening AppInfo"
            is AppClickAction.Freeze -> "Freezing"
            is AppClickAction.Kill -> "War Machine"
            is AppClickAction.Launch -> "Launch Pad"
            is AppClickAction.Reinstall -> {
                reinstalling = true
                "Reinstalling App..,"
            }

            AppClickAction.ReinstallAll -> {
                reinstalling = true
                "Reinstalling Apps..,"
            }

            is AppClickAction.Share -> "Share App"
            is AppClickAction.UnFreeze -> "Defrosting"
            is AppClickAction.Uninstall -> "Uninstalling..,"

            else -> {
                logObserver = emptyList()
                reinstalling = false
                ""
            }
        }
        if (appAction != null) {
            processAppAction(
                context,
                shizukuManager.elevatableState,
                appAction!!,
                observer = {
                    logObserver += it
                },
                exit = {
                    shizukuManager.updateCacheSize()
                    canExit = true
                    isRefreshing = true
                    appAction = null
                }
            )
        }
    }

    var hasAffirmation by remember { mutableStateOf(false) }

    LaunchedEffect(hasAffirmation) {
        if (hasAffirmation) {
            canExit = false
            termLoggerTitle = when (multiAction) {
                is MultiAppAction.Freeze -> "Freezing Apps.,"
                is MultiAppAction.Kill -> "Killing Apps..,"
                is MultiAppAction.ReInstall -> {
                    "Reinstalling Apps..,"
                }

                is MultiAppAction.Share -> "Share Apps"
                is MultiAppAction.UnFreeze -> "UnFreezing Apps..,"
                is MultiAppAction.Uninstall -> "Uninstalling Apps..,"
                is MultiAppAction.ClearCache -> "Clearing Cache..,"
                else -> {
                    logObserver = emptyList()
                    ""
                }
            }
            if (multiAction != null) {
                processMultiAppAction(
                    context,
                    elevatableState = shizukuManager.elevatableState,
                    multiAction!!,
                    observer = {
                        logObserver += it
                    },
                    exit = {
                        multiAction = null
                        shizukuManager.updateCacheSize()
                        logObserver += "exiting shell"
                        canExit = true
                        isRefreshing = true
                        hasAffirmation = false
                    }
                )
            }
        }
    }

    if (multiAction != null) {
        MultiAppAffirmationDialog(
            multiAppAction = multiAction!!,
            onConfirm = {
                hasAffirmation = true
            },
            onRejected = {
                multiAction = null
                hasAffirmation = false
            }
        )
    }

    if (logObserver.isNotEmpty()) {
        TermLoggerDialog(
            canExit = canExit,
            title = termLoggerTitle,
            logObserver = logObserver,
            showTerminate = showTerminate,
            onTerminate = {
                stopLogger?.invoke()
                logObserver = emptyList()
                appAction = null
                multiAction = null
                canExit = false
            },
            done = {
                logObserver = emptyList()
                appAction = null
                multiAction = null
                canExit = false
            }
        )
    }

}

suspend fun processMultiAppAction(
    context: Context,
    elevatableState: ElevatableState = ElevatableState.NONE,
    multiAction: MultiAppAction,
    observer: (String) -> Unit,
    exit: () -> Unit
) {
    withContext(Dispatchers.IO) {
        when (multiAction) {
            is MultiAppAction.ClearCache -> {
                val appList =
                    multiAction.appList.filter { it.packageName != BuildConfig.APPLICATION_ID && it.packageName != "com.android.vending" }
                clearCache(
                    *appList.toTypedArray(),
                    elevatableState = elevatableState,
                    observer = observer,
                    exit = exit
                )
            }

            is MultiAppAction.Freeze -> {
                val selectedAppInfos = multiAction.appList
                val activeApps =
                    selectedAppInfos.filter { it.enabled && it.packageName != BuildConfig.APPLICATION_ID }
                context.disableApps(
                    *activeApps.toTypedArray(),
                    observer = observer,
                    exit = exit,
                    elevatableState = elevatableState
                )
            }

            is MultiAppAction.Kill -> {
                killApps(
                    *multiAction.appList.filter { it.packageName != BuildConfig.APPLICATION_ID }
                        .toTypedArray(),
                    observer = observer, exit = exit
                )
            }

            is MultiAppAction.ReInstall -> {
                reInstallAppsWithGoogle(
                    multiAction.appList.filter { it.installerPackageName != "com.android.vending" }
                        .toMutableList().apply {
                            val thor = firstOrNull { it.packageName == BuildConfig.APPLICATION_ID }
                            if (thor != null) {
                                remove(thor)
                                add(thor)
                            }
                        },
                    observer = observer,
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
                    observer = observer,
                    exit = exit
                )
            }

            is MultiAppAction.Uninstall -> {
                (multiAction).appList.filter { it.packageName != BuildConfig.APPLICATION_ID }
                    .forEach {
                        try {
                            if (it.isSystem) {
                                val result = uninstallSystemApp(it)
                                observer(
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

suspend fun processAppAction(
    context: Context,
    elevatableState: ElevatableState = ElevatableState.NONE,
    appAction: AppClickAction,
    observer: (String) -> Unit,
    exit: () -> Unit
) {
    withContext(Dispatchers.IO) {
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
                        observer = observer,
                        elevatableState = elevatableState,
                        exit = exit
                    )
                }
            }

            is AppClickAction.Share -> {
                if (appAction.appInfo.splitPublicSourceDirs.isEmpty())
                    shareApp(appAction.appInfo, context)
                else shareSplitApks(appAction.appInfo, context, observer, exit)
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
                    observer = observer,
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
                        observer("Failed to kill ${appAction.appInfo.appName}")
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
                                observer = observer
                            ) {
                                if (launchApp(appInfo.packageName).isSuccess.not()) {
                                    observer("Failed to launch ${appInfo.appName}")
                                }
                            }
                        } else {
                            observer("Failed to launch ${appInfo.appName}")
                        }
                    } else {
                        if (rootAvailable()) {
                            if (launchApp(appInfo.packageName).isSuccess.not()) {
                                observer("Failed to launch ${appInfo.appName}")
                            } else {
                                observer("Launching ${appInfo.appName}")
                            }
                        } else {
                            context.packageManager.getLaunchIntentForPackage(appInfo.packageName)
                                ?.let {
                                    context.startActivity(it)
                                } ?: run {
                                observer("Failed to launch ${appInfo.appName}")
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
                observer("Reinstalling ${appInfo.appName}")
                if (rootAvailable()) {
                    reInstallWithGoogle(appInfo, observer, exit)
                } else {
                    observer("Root not found")
                    observer("Root is Required to reinstall apps")
                    observer("Grant root access in manager app then restart Thor")
                    observer("\n")
                    exit()
                }
            }

            AppClickAction.ReinstallAll -> {}

            is AppClickAction.UnFreeze -> {
                context.enableApps(
                    appAction.appInfo,
                    elevatableState = elevatableState,
                    observer = observer,
                    exit = exit
                )
            }

            is AppClickAction.Uninstall -> {
                val it = appAction.appInfo
                try {
                    if (it.isSystem) {
                        val result = uninstallSystemApp(it)
                        observer(
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