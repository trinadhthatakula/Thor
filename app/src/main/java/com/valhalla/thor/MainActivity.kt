package com.valhalla.thor

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.AppInfoGrabber
import com.valhalla.thor.model.MultiAppAction
import com.valhalla.thor.model.hasMagisk
import com.valhalla.thor.model.launchApp
import com.valhalla.thor.model.reInstallAppsWithGoogle
import com.valhalla.thor.model.reInstallWithGoogle
import com.valhalla.thor.model.rootAvailable
import com.valhalla.thor.model.shareApp
import com.valhalla.thor.ui.screens.AppListScreen
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

            ThorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
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

                                is AppClickAction.Reinstall -> {
                                    //if (rootAvailable() || hasMagisk())
                                        lifecycleScope.launch {
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
                                        }
                                    /* else {
                                        Toast.makeText(
                                            this,
                                            "Root access not available\nPlease grant root access and restart this app",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }*/
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
                                                Log.e(
                                                    "MainActivity",
                                                    "onCreate: failed to launch app ${
                                                        result.err.joinToString("\n")
                                                    }"
                                                )
                                            }
                                        }
                                    else
                                        it.appInfo.packageName.let { appPackage ->
                                            this.packageManager.getLaunchIntentForPackage(appPackage)
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
                }

                if (multiAction != null) {

                    if (multiAction is MultiAppAction.ReInstall) {
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
                        if (!hasAffirmation)
                            AlertDialog(
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


