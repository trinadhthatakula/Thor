package com.valhalla.thor

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.UserAppInfo
import com.valhalla.thor.model.getApkPath
import com.valhalla.thor.model.launchApp
import com.valhalla.thor.model.reInstallWithGoogle
import com.valhalla.thor.model.rootAvailable
import com.valhalla.thor.ui.screens.AppListScreen
import com.valhalla.thor.ui.theme.ThorTheme
import com.valhalla.thor.ui.widgets.AnimateLottieRaw
import com.valhalla.thor.ui.widgets.AppClickAction
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

            val grabber = UserAppInfo(this)

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
                                    if (rootAvailable())
                                        lifecycleScope.launch {
                                            reinstalling = true
                                            withContext(Dispatchers.IO) {
                                                reInstallWithGoogle(it.appInfo.packageName.toString()).let { result ->
                                                    reinstalling = false
                                                    if (!result.isSuccess) {
                                                        runOnUiThread {
                                                            Toast.makeText(
                                                                this@MainActivity,
                                                                "Failed to reinstall app",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                        Log.e(
                                                            "MainActivity",
                                                            "onCreate: failed to reinstall app ${
                                                                result.err.joinToString("\n")
                                                            }"
                                                        )
                                                    } else {
                                                        runOnUiThread {
                                                            Toast.makeText(
                                                                this@MainActivity,
                                                                "Reinstalled app",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                        isRefreshing = true
                                                    }
                                                }
                                            }
                                        }
                                    else {
                                        Toast.makeText(
                                            this,
                                            "Root access not available",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }

                                is AppClickAction.Launch -> {
                                    if (rootAvailable())
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
                                        it.appInfo.packageName?.let { appPackage ->
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
                                    // Share app
                                    getApkPath(it.appInfo.packageName.toString()).let { result ->
                                        if (!result.isSuccess) {
                                            Toast.makeText(
                                                this,
                                                "Failed to get app Path",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            Log.e(
                                                "MainActivity",
                                                "onCreate: failed to get app Path ${
                                                    result.err.joinToString("\n")
                                                }"
                                            )
                                        } else {
                                            Log.d(
                                                "MainActivity",
                                                "onCreate: success\n ${result.out.joinToString("\n")}"
                                            )
                                        }
                                    }
                                }

                                is AppClickAction.Uninstall -> {
                                    if (it.appInfo.isSystem) {
                                        Toast.makeText(
                                            this,
                                            "Cannot uninstall system app",
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
                        }
                    )
                }

                if (reinstalling) {
                    ModalBottomSheet(
                        onDismissRequest = {}
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AnimateLottieRaw(
                                resId = R.raw.rearrange,
                                shouldLoop = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp)
                            )
                            Text(
                                "Reinstalling...",
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                        }
                    }
                }

            }


        }
    }


}
