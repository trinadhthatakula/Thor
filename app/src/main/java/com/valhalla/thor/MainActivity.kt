package com.valhalla.thor

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.AppInfoGrabber
import com.valhalla.thor.model.AppListener
import com.valhalla.thor.model.MultiAppAction
import com.valhalla.thor.model.NavBarItems
import com.valhalla.thor.model.disableApps
import com.valhalla.thor.model.enableApps
import com.valhalla.thor.model.hasMagisk
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
import com.valhalla.thor.ui.theme.ThorTheme
import com.valhalla.thor.ui.widgets.AppClickAction
import com.valhalla.thor.ui.widgets.TermLoggerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    override fun onStart() {
        super.onStart()
        if (getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("can_reinstall", false) == true
        ) {
            registerReceiver(AppListener.getInstance())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {

            val grabber = AppInfoGrabber(this)

            var userApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
            var systemApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
            var isRefreshing by remember { mutableStateOf(false) }

            userApps = grabber.getUserApps()
            systemApps = grabber.getSystemApps()

            LaunchedEffect(isRefreshing) {
                if (isRefreshing) {
                    withContext(Dispatchers.IO) {
                        userApps = grabber.getUserApps()
                        systemApps = grabber.getSystemApps()
                        delay(1000)
                        isRefreshing = false
                    }
                }
            }

            var reinstalling by remember { mutableStateOf(false) }
            var logObserver by remember { mutableStateOf(emptyList<String>()) }
            var canExit by remember { mutableStateOf(false) }

            var appAction: AppClickAction? by remember {
                mutableStateOf(null)
            }
            var multiAction: MultiAppAction? by remember {
                mutableStateOf(null)
            }

            val navBarItems = listOf(
                NavBarItems(
                    title = "Home",
                    route = "home",
                    unselectedIcon = R.drawable.home_outline,
                    selectedIcon = R.drawable.home
                ),
                NavBarItems(
                    title = "Apps",
                    route = "apps",
                    unselectedIcon = R.drawable.apps,
                    selectedIcon = R.drawable.apps
                )
            )

            var selectedNavItem by remember {
                mutableStateOf(navBarItems.first())
            }

            var termLoggerTitle by remember { mutableStateOf("Reinstalling..,") }
            var homeAction: HomeActions? by remember { mutableStateOf(null) }

            BackHandler {
                if (homeAction != null) {
                    homeAction = null
                }
            }

            ThorTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (homeAction == null)
                            NavigationBar {
                                navBarItems.forEach {
                                    NavigationBarItem(
                                        selected = selectedNavItem == it,
                                        onClick = {
                                            selectedNavItem = it
                                        },
                                        icon = {
                                            Icon(
                                                painterResource(if (selectedNavItem == it) it.selectedIcon else it.unselectedIcon),
                                                it.title
                                            )
                                        },
                                        label = {
                                            Text(it.title)
                                        }
                                    )
                                }
                            }
                    }
                ) { innerPadding ->
                    if (homeAction != null) {
                        if (homeAction is HomeActions.ActiveApps) {
                            ///show Active Apps
                            AppListScreen(
                                customSelection = (homeAction as HomeActions.ActiveApps).appListType,
                                title = "Active Apps",
                                userAppList = userApps.filter { it.enabled },
                                systemAppList = systemApps.filter { it.enabled },
                                modifier = Modifier.padding(innerPadding),
                                isRefreshing = isRefreshing,
                                onRefresh = {
                                    isRefreshing = true
                                },
                                onAppAction = {
                                    appAction = it
                                },
                                onMultiAppAction = {
                                    multiAction = it
                                }
                            )
                        } else {
                            AppListScreen(
                                customSelection = (homeAction as HomeActions.FrozenApps).appListType,
                                icon = R.drawable.frozen,
                                title = "Frozen Apps",
                                userAppList = userApps.filter { it.enabled.not() },
                                systemAppList = systemApps.filter { it.enabled.not() },
                                modifier = Modifier.padding(innerPadding),
                                isRefreshing = isRefreshing,
                                onRefresh = {
                                    isRefreshing = true
                                },
                                onAppAction = {
                                    appAction = it
                                },
                                onMultiAppAction = {
                                    multiAction = it
                                }
                            )
                        }
                    } else if (selectedNavItem == navBarItems.first()) {
                        HomeScreen(
                            modifier = Modifier.padding(innerPadding),
                            userApps, systemApps
                        ) { hAction ->

                            when (hAction) {
                                is HomeActions.ActiveApps -> {
                                    homeAction = hAction
                                }

                                is HomeActions.FrozenApps -> {
                                    homeAction = hAction
                                }

                                is HomeActions.ReinstallAll -> {
                                    multiAction =
                                        MultiAppAction.ReInstall(userApps.filter { it.installerPackageName != "com.android.vending" })
                                }

                                else -> {}
                            }

                        }
                    } else {
                        AppListScreen(
                            userAppList = userApps,
                            systemAppList = systemApps,
                            isRefreshing = isRefreshing,
                            onRefresh = {
                                isRefreshing = true
                            },
                            modifier = Modifier.padding(innerPadding),
                            onAppAction = {
                                appAction = it
                            },
                            onMultiAppAction = {
                                multiAction = it
                            }
                        )
                    }

                    LaunchedEffect(appAction) {
                        if (appAction != null) {
                            try {
                                when (appAction!!) {
                                    is AppClickAction.AppInfoSettings -> {
                                        openAppInfoScreen(
                                            this@MainActivity,
                                            (appAction as AppClickAction.AppInfoSettings).appInfo
                                        )
                                    }

                                    is AppClickAction.Kill -> {
                                        val it: AppClickAction.Kill = appAction as AppClickAction.Kill
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            val killResult = killApp(it.appInfo)
                                            withContext(Dispatchers.Main) {
                                                if (killResult.isEmpty()) {
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "Killed ${it.appInfo.appName}",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "Failed to kill ${it.appInfo.appName}",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    }

                                    AppClickAction.ReinstallAll -> {

                                    }

                                    is AppClickAction.Freeze -> {
                                        val it: AppClickAction.Freeze =
                                            appAction as AppClickAction.Freeze
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            disableApps(it.appInfo)
                                            isRefreshing = true
                                        }
                                    }

                                    is AppClickAction.UnFreeze -> {
                                        val it: AppClickAction.UnFreeze =
                                            appAction as AppClickAction.UnFreeze
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            enableApps(it.appInfo)
                                            isRefreshing = true
                                        }
                                    }

                                    is AppClickAction.Reinstall -> {
                                        val it: AppClickAction.Reinstall =
                                            appAction as AppClickAction.Reinstall
                                        if (rootAvailable() || hasMagisk()) lifecycleScope.launch {
                                            termLoggerTitle = "Reinstalling Apps..,"
                                            logObserver = emptyList()
                                            reinstalling = true
                                            withContext(Dispatchers.IO) {
                                                reInstallWithGoogle(
                                                    it.appInfo,
                                                    observer = {
                                                        logObserver += it
                                                    },
                                                    exit = {
                                                        canExit = true
                                                        isRefreshing = true
                                                    })
                                            }
                                        } else runOnUiThread {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Root not available\nPlease grant root in manager app",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }

                                    is AppClickAction.Launch -> {
                                        val it: AppClickAction.Launch =
                                            appAction as AppClickAction.Launch
                                        if (it.appInfo.enabled.not()) {
                                            if (rootAvailable()) {
                                                lifecycleScope.launch {
                                                    enableApps(it.appInfo, exit = {
                                                        isRefreshing = true
                                                        launchApp(it.appInfo.packageName.toString()).let { result ->
                                                            if (!result.isSuccess) {
                                                                runOnUiThread {
                                                                    Toast.makeText(
                                                                        this@MainActivity,
                                                                        "Failed to launch app",
                                                                        Toast.LENGTH_SHORT
                                                                    ).show()
                                                                }
                                                            }
                                                        }
                                                    })
                                                }

                                            } else {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "App is Frozen",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        } else
                                            if (rootAvailable() || hasMagisk())
                                                launchApp(it.appInfo.packageName.toString()).let { result ->
                                                    if (!result.isSuccess) {
                                                        runOnUiThread {
                                                            Toast.makeText(
                                                                this@MainActivity,
                                                                "Failed to launch app",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                }
                                            else
                                                it.appInfo.packageName.let { appPackage ->
                                                    this@MainActivity.packageManager.getLaunchIntentForPackage(
                                                        appPackage
                                                    )?.let {
                                                            startActivity(it)
                                                        } ?: run {
                                                        runOnUiThread {
                                                            Toast.makeText(
                                                                this@MainActivity,
                                                                "Failed to launch app",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                }

                                    }

                                    is AppClickAction.Share -> {
                                        val it: AppClickAction.Share = appAction as AppClickAction.Share
                                        shareApp(it.appInfo, this@MainActivity)
                                    }

                                    is AppClickAction.Uninstall -> {
                                        lifecycleScope.launch {
                                            try {
                                                val it: AppClickAction.Uninstall =
                                                    appAction as AppClickAction.Uninstall
                                                if (it.appInfo.isSystem) {
                                                    uninstallSystemApp(it.appInfo)
                                                } else {
                                                    val appPackage = it.appInfo.packageName
                                                    val intent = Intent(Intent.ACTION_DELETE)
                                                    intent.data = "package:${appPackage}".toUri()
                                                    startActivity(intent)
                                                }
                                                isRefreshing = true
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }

                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                appAction = null
                            }
                        }
                    }


                    if (multiAction != null) {

                        when (multiAction) {

                            is MultiAppAction.Kill -> {
                                val appList =
                                    (multiAction as MultiAppAction.Kill).appList.toMutableList()
                                val thorAppInfo =
                                    appList.firstOrNull { it.packageName == BuildConfig.APPLICATION_ID }
                                if (thorAppInfo != null) {
                                    appList -= thorAppInfo
                                }
                                var hasAffirmation by remember { mutableStateOf(false) }
                                LaunchedEffect(hasAffirmation) {
                                    if (hasAffirmation) {
                                        termLoggerTitle = "Killing Apps..,"
                                        logObserver = emptyList()
                                        canExit = false
                                        multiAction = null
                                        reinstalling = true
                                        withContext(Dispatchers.IO) {
                                            killApps(
                                                *appList.toTypedArray(),
                                                observer = {
                                                    logObserver += it
                                                },
                                                exit = {
                                                    logObserver += "Exiting Shell"
                                                    canExit = true
                                                    isRefreshing = true
                                                }
                                            )
                                        }
                                    }
                                }
                                if (!hasAffirmation) AffirmationDialog(
                                    text = "This will kill ${appList.size} apps",
                                    onConfirm = { hasAffirmation = true },
                                    onRejected = { multiAction = null }
                                )
                            }

                            is MultiAppAction.Uninstall -> {
                                (multiAction as MultiAppAction.Uninstall).appList.forEach {
                                    try {
                                        if (it.isSystem) {
                                            termLoggerTitle = "Uninstalling Apps..,"
                                            val result = uninstallSystemApp(it)
                                            logObserver += if (result.isEmpty())
                                                "Uninstalled ${it.appName}"
                                            else
                                                "Failed to uninstall ${it.appName}"
                                        } else {
                                            val appPackage = it.packageName
                                            val intent = Intent(Intent.ACTION_DELETE)
                                            intent.data = "package:${appPackage}".toUri()
                                            startActivity(intent)
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    } finally {
                                        isRefreshing = true
                                    }
                                }
                            }

                            is MultiAppAction.ReInstall -> {
                                val appList =
                                    (multiAction as MultiAppAction.ReInstall).appList.toMutableList()
                                val thorAppInfo =
                                    appList.firstOrNull { it.packageName == BuildConfig.APPLICATION_ID }
                                if (thorAppInfo != null) {
                                    appList -= thorAppInfo
                                    appList += thorAppInfo
                                }
                                var hasAffirmation by remember { mutableStateOf(false) }
                                LaunchedEffect(hasAffirmation) {
                                    if (hasAffirmation) {
                                        termLoggerTitle = "Reinstalling Apps..,"
                                        logObserver = emptyList()
                                        canExit = false
                                        multiAction = null
                                        reinstalling = true
                                        withContext(Dispatchers.IO) {
                                            reInstallAppsWithGoogle(
                                                appList,
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
                                }
                                if (!hasAffirmation) AffirmationDialog(
                                    text = "This will reinstall ${appList.size} apps with Google Play",
                                    onConfirm = { hasAffirmation = true },
                                    onRejected = { multiAction = null }
                                )
                            }

                            is MultiAppAction.UnFreeze -> {
                                if (rootAvailable()) {
                                    val selectedAppInfos =
                                        (multiAction as MultiAppAction.UnFreeze).appList
                                    val frozenApps = selectedAppInfos.filter { it.enabled.not() }
                                    var hasAffirmation by remember { mutableStateOf(false) }
                                    LaunchedEffect(hasAffirmation) {
                                        if (hasAffirmation) {
                                            termLoggerTitle = "UnFreezing Apps..,"
                                            logObserver = emptyList()
                                            canExit = false
                                            multiAction = null
                                            reinstalling = true
                                            withContext(Dispatchers.IO) {
                                                enableApps(
                                                    *frozenApps.toTypedArray(),
                                                    observer = {
                                                        logObserver += it
                                                    },
                                                    exit = {
                                                        logObserver += "Exiting Shell"
                                                        canExit = true
                                                        isRefreshing = true
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    if (!hasAffirmation) {
                                        AffirmationDialog(
                                            text = "${frozenApps.size} of ${selectedAppInfos.size} apps are frozen do you want to unfreeze them?",
                                            onConfirm = { hasAffirmation = true },
                                            onRejected = { multiAction = null }
                                        )
                                    }
                                } else {
                                    Toast.makeText(this, "Root not available", Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }

                            is MultiAppAction.Freeze -> {
                                if (rootAvailable()) {
                                    val selectedAppInfos =
                                        (multiAction as MultiAppAction.Freeze).appList
                                    val thorAppInfo =
                                        selectedAppInfos.firstOrNull { it.packageName == BuildConfig.APPLICATION_ID }
                                    val activeApps =
                                        selectedAppInfos.filter { it.enabled }.toMutableList()
                                    if (thorAppInfo != null) {
                                        if (activeApps.contains(thorAppInfo)) {
                                            activeApps -= thorAppInfo
                                        }
                                    }
                                    var hasAffirmation by remember { mutableStateOf(false) }
                                    LaunchedEffect(hasAffirmation) {
                                        if (hasAffirmation) {
                                            termLoggerTitle = "Freezing Apps"
                                            logObserver = emptyList()
                                            canExit = false
                                            multiAction = null
                                            reinstalling = true
                                            withContext(Dispatchers.IO) {
                                                disableApps(
                                                    *activeApps.toTypedArray(),
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
                                    }
                                    if (!hasAffirmation) {
                                        AffirmationDialog(
                                            text = "${activeApps.size} of ${selectedAppInfos.size} apps are not frozen do you want to freeze them?",
                                            onConfirm = { hasAffirmation = true },
                                            onRejected = { multiAction = null }
                                        )
                                    }
                                } else {
                                    Toast.makeText(this, "Root not available", Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }

                            else -> {
                                Toast.makeText(this, "Work in progress", Toast.LENGTH_SHORT).show()
                            }
                        }

                    }
                    if (reinstalling) {
                        TermLoggerDialog(
                            Modifier,
                            termLoggerTitle,
                            canExit,
                            logObserver,
                            doneReinstalling = {
                                reinstalling = false
                                canExit = false
                            }
                        )
                    }
                }

            }
        }
    }
}

@Composable
fun AffirmationDialog(
    modifier: Modifier = Modifier,
    title: String = "Are you sure?",
    text: String = "Some Message",
    onConfirm: () -> Unit,
    onRejected: () -> Unit
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = {},
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                }
            ) {
                Text("Yes")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onRejected()
                }
            ) {
                Text("No")
            }
        },
        title = {
            Text(title)
        },
        text = {
            Text(text)
        }
    )
}


