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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.core.net.toUri
import com.valhalla.thor.ui.widgets.AffirmationDialog
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
import com.valhalla.thor.ui.widgets.MultiAppAffirmationDialog
import com.valhalla.thor.model.AppInfoGrabber
import com.valhalla.thor.model.MultiAppAction
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
import com.valhalla.thor.model.uninstallSystemApp
import com.valhalla.thor.ui.screens.AppListScreen
import com.valhalla.thor.ui.screens.HomeActions
import com.valhalla.thor.ui.screens.HomeScreen
import com.valhalla.thor.ui.widgets.AppClickAction
import com.valhalla.thor.ui.widgets.TermLoggerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.collections.plus

@Composable
fun HomePage(
    modifier: Modifier = Modifier,
    onExit: () -> Unit
) {

    var selectedDestination: AppDestinations by remember {
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
                            if(dest == AppDestinations.SETTINGS)
                                Toast.makeText(context,"coming soon", Toast.LENGTH_SHORT).show()
                            else
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
                    }
                }
            )

            AppDestinations.APPS -> AppListScreen(
                modifier = modifier.padding(it),
                userAppList = userAppList,
                systemAppList = systemAppList,
                isRefreshing = isRefreshing,
                onRefresh = { isRefreshing = true },
                onAppAction = {
                    appAction = it
                },
                onMultiAppAction = {
                    multiAction = it
                }
            )

            AppDestinations.FREEZER -> AppListScreen(
                title = "Frozen Apps",
                icon =R.drawable.frozen,
                modifier = modifier.padding(it),
                userAppList = userAppList.filter { it.enabled.not() },
                systemAppList = systemAppList.filter{ it.enabled.not() },
                isRefreshing = isRefreshing,
                onRefresh = { isRefreshing = true },
                onAppAction = {
                    appAction = it
                },
                onMultiAppAction = {
                    multiAction = it
                }
            )
            AppDestinations.SETTINGS -> Text("Settings")
        }

    }


    LaunchedEffect(appAction) {
        reinstalling = false
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
            null -> {
                logObserver = emptyList()
                reinstalling = false
                ""
            }
        }
        if (appAction != null) {
            processAppAction(
                context, appAction!!,
                observer = {
                    logObserver += it
                },
                exit = {
                    canExit = true
                    isRefreshing = true
                }
            )
        }
    }

    var hasAffirmation by remember { mutableStateOf(false) }

    LaunchedEffect(hasAffirmation) {
        if (hasAffirmation) {
            canExit = false
            reinstalling == false
            termLoggerTitle = when (multiAction) {
                is MultiAppAction.Freeze -> "Freezing Apps.,"
                is MultiAppAction.Kill -> "Killing Apps..,"
                is MultiAppAction.ReInstall -> {
                    reinstalling = true
                    "Reinstalling Apps..,"
                }

                is MultiAppAction.Share -> "Share Apps"
                is MultiAppAction.UnFreeze -> "UnFreezing Apps..,"
                is MultiAppAction.Uninstall -> "Uninstalling Apps..,"
                null -> {
                    logObserver = emptyList()
                    reinstalling = false
                    ""
                }
            }
            if (multiAction != null) {
                processMultiAppAction(
                    context,
                    multiAction!!,
                    observer = {
                        logObserver += it
                    },
                    exit = {
                        logObserver += "exiting shell"
                        canExit = true
                        isRefreshing = true
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
        ) {
            logObserver = emptyList()
            appAction = null
            multiAction = null
            canExit = false
        }
    }

}


suspend fun processMultiAppAction(
    context: Context,
    multiAction: MultiAppAction,
    observer: (String) -> Unit,
    exit: () -> Unit
) {
    withContext(Dispatchers.IO) {
        when (multiAction) {
            is MultiAppAction.Freeze -> {
                val selectedAppInfos = multiAction.appList
                val activeApps =
                    selectedAppInfos.filter { it.enabled && it.packageName != BuildConfig.APPLICATION_ID }
                context.disableApps(
                    *activeApps.toTypedArray(),
                    observer = observer,
                    exit = exit
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
                    multiAction.appList.filter { it.installerPackageName != "com.google.vending" }
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

            is MultiAppAction.Share -> {}
            is MultiAppAction.UnFreeze -> {
                val selectedAppInfos = multiAction.appList
                val frozenApps = selectedAppInfos.filter { it.enabled.not() }
                context.enableApps(
                    *frozenApps.toTypedArray(),
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
    appAction: AppClickAction,
    observer: (String) -> Unit,
    exit: () -> Unit
) {
    withContext(Dispatchers.IO) {
        when (appAction) {
            is AppClickAction.AppInfoSettings -> {
                openAppInfoScreen(
                    context,
                    appAction.appInfo
                )
                exit()
            }

            is AppClickAction.Freeze -> {
                context.disableApps(appAction.appInfo, exit = exit)
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
                        if (rootAvailable()) {
                            context.enableApps(appInfo, exit = {
                                if (launchApp(appInfo.packageName).isSuccess.not()) {
                                    observer("Failed to launch ${appInfo.appName}")
                                }
                            })
                        } else {
                            observer("Failed to launch ${appInfo.appName}")
                        }
                    } else {
                        if (rootAvailable())
                            if (launchApp(appInfo.packageName).isSuccess.not()) {
                                observer("Failed to launch ${appInfo.appName}")
                            } else
                                context.packageManager.getLaunchIntentForPackage(appInfo.packageName)
                                    ?.let {
                                        context.startActivity(it)
                                    } ?: run {
                                    observer("Failed to launch ${appInfo.appName}")
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
            is AppClickAction.Share -> {
                shareApp(appAction.appInfo, context)
            }

            is AppClickAction.UnFreeze -> {
                context.enableApps(appAction.appInfo, exit = exit)
            }

            is AppClickAction.Uninstall -> {
                val appInfo = appAction.appInfo
                try {
                    if (appInfo.isSystem) {
                        val result = uninstallSystemApp(appInfo)
                        observer(
                            "Uninstalling ${appInfo.appName} : $result"
                        )
                    } else {
                        val appPackage = appInfo.packageName
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