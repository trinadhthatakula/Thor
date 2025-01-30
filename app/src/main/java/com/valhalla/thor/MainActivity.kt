package com.valhalla.thor

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.AppInfoGrabber
import com.valhalla.thor.model.MultiAppAction
import com.valhalla.thor.model.NavBarItems
import com.valhalla.thor.model.disableApps
import com.valhalla.thor.model.enableApps
import com.valhalla.thor.model.hasMagisk
import com.valhalla.thor.model.launchApp
import com.valhalla.thor.model.reInstallAppsWithGoogle
import com.valhalla.thor.model.reInstallWithGoogle
import com.valhalla.thor.model.rootAvailable
import com.valhalla.thor.model.shareApp
import com.valhalla.thor.ui.screens.AppListScreen
import com.valhalla.thor.ui.screens.KBoxVerificationScreen
import com.valhalla.thor.ui.theme.ThorTheme
import com.valhalla.thor.ui.widgets.AppClickAction
import com.valhalla.thor.ui.widgets.TermLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

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
                    userApps = grabber.getUserApps()
                    systemApps = grabber.getSystemApps()
                    delay(1000)
                    isRefreshing = false
                }
            }

            var reinstalling by remember { mutableStateOf(false) }
            var logObserver by remember { mutableStateOf(emptyList<String>()) }
            var canExit by remember { mutableStateOf(false) }

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
                    title = "Key Status",
                    route = "key_search",
                    unselectedIcon = R.drawable.key_outline,
                    selectedIcon = R.drawable.key
                )
            )

            var selectedNavItem by remember {
                mutableStateOf(navBarItems.first())
            }

            ThorTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
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
                    if (selectedNavItem == navBarItems.first())
                        AppListScreen(
                            userApps,
                            systemApps,
                            isRefreshing = isRefreshing,
                            onRefresh = {
                                isRefreshing = true
                            },
                            modifier = Modifier.padding(innerPadding),
                            onAppAction = {
                                when (it) {
                                    AppClickAction.ReinstallAll -> {

                                    }

                                    is AppClickAction.Freeze -> {
                                        disableApps(it.appInfo)
                                        isRefreshing = true
                                    }

                                    is AppClickAction.UnFreeze -> {
                                        enableApps(it.appInfo)
                                        isRefreshing = true
                                    }

                                    is AppClickAction.Reinstall -> {
                                        if (rootAvailable() || hasMagisk()) lifecycleScope.launch {
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
                                        } else Toast.makeText(
                                            this,
                                            "Root not available\nPlease grant root in manager app",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }

                                    is AppClickAction.Launch -> {
                                        if (rootAvailable() || hasMagisk())
                                            launchApp(it.appInfo.packageName.toString()).let { result ->
                                                if (!result.isSuccess) {
                                                    Toast.makeText(
                                                        this,
                                                        "Failed to launch app",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        else
                                            it.appInfo.packageName.let { appPackage ->
                                                this.packageManager.getLaunchIntentForPackage(
                                                    appPackage
                                                )
                                                    ?.let {
                                                        startActivity(it)
                                                    } ?: run {
                                                    Toast.makeText(
                                                        this,
                                                        "Failed to launch app",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }

                                    }

                                    is AppClickAction.Share -> {
                                        shareApp(it.appInfo, this)
                                    }

                                    is AppClickAction.Uninstall -> {
                                        if (it.appInfo.isSystem) {
                                            Toast.makeText(
                                                this,
                                                "Cannot uninstall system app as of now",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            return@AppListScreen
                                        }
                                        val appPackage = it.appInfo.packageName
                                        val intent = Intent(Intent.ACTION_DELETE)
                                        intent.data = "package:${appPackage}".toUri()
                                        startActivity(intent)
                                    }
                                }
                            },
                            onEggBroken = {
                                getSharedPreferences("egg", MODE_PRIVATE).edit {
                                    putBoolean(
                                        "found",
                                        true
                                    )
                                }
                            },
                            onMultiAppAction = {
                                multiAction = it
                            }
                        )
                    else {
                        KBoxVerificationScreen(Modifier.padding(innerPadding))
                    }
                }

                if (multiAction != null) {

                    when (multiAction) {

                        is MultiAppAction.Uninstall -> {
                            (multiAction as MultiAppAction.Uninstall).appList.forEach {
                                val appPackage = it.packageName
                                val intent = Intent(Intent.ACTION_DELETE)
                                intent.data = "package:${appPackage}".toUri()
                                startActivity(intent)
                            }
                        }

                        is MultiAppAction.ReInstall -> {
                            val appList = (multiAction as MultiAppAction.ReInstall).appList
                            var hasAffirmation by remember { mutableStateOf(false) }
                            LaunchedEffect(hasAffirmation) {
                                if (hasAffirmation) {
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
                            if (!hasAffirmation) AlertDialog(
                                onDismissRequest = {},
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            hasAffirmation = true
                                        }
                                    ) {
                                        Text("Yes")
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            multiAction = null
                                        }
                                    ) {
                                        Text("No")
                                    }
                                },
                                title = {
                                    Text("Are You Sure?")
                                },
                                text = {
                                    Text("This will reinstall ${appList.size} apps with Google Play")
                                }
                            )
                        }

                        else -> {
                            Toast.makeText(this, "Work in progress", Toast.LENGTH_SHORT).show()
                        }
                    }

                }
                if (reinstalling) {
                    TermLogger(
                        Modifier,
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


