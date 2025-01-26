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
import androidx.compose.material3.Scaffold
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
import com.valhalla.thor.model.UserAppInfo
import com.valhalla.thor.model.getApkPath
import com.valhalla.thor.model.launchApp
import com.valhalla.thor.model.reInstallWithGoogle
import com.valhalla.thor.ui.screens.AppListScreen
import com.valhalla.thor.ui.theme.ThorTheme
import com.valhalla.thor.ui.widgets.AppClickAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                if(isRefreshing){
                    userApps = grabber.getUserApps()
                    systemApps = grabber.getSystemApps()
                    delay(1000)
                    isRefreshing = false
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Refreshed", Toast.LENGTH_SHORT).show()
                    }
                }
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
                                AppClickAction.ReinstallAll ->{

                                }
                                is AppClickAction.Reinstall ->{
                                    lifecycleScope.launch {
                                        withContext(Dispatchers.IO) {
                                            reInstallWithGoogle(it.appInfo.packageName.toString()).let { result ->
                                                if (!result.isSuccess) {
                                                    runOnUiThread {
                                                        Toast.makeText(this@MainActivity, "Failed to reinstall app", Toast.LENGTH_SHORT).show()
                                                    }
                                                    Log.e("MainActivity", "onCreate: failed to reinstall app ${result.err.joinToString("\n")}")
                                                }else{
                                                    runOnUiThread {
                                                        Toast.makeText(this@MainActivity, "Reinstalled app", Toast.LENGTH_SHORT).show()
                                                    }
                                                    isRefreshing = true
                                                }
                                            }
                                        }
                                    }
                                }
                                is AppClickAction.Launch -> {
                                    launchApp(it.appInfo.packageName.toString()).let { result ->
                                        if (!result.isSuccess) {
                                            Toast.makeText(this, "Failed to launch app", Toast.LENGTH_SHORT).show()
                                            Log.e("MainActivity", "onCreate: failed to launch app ${result.err.joinToString("\n")}")
                                        }
                                    }
                                }
                                is AppClickAction.Share -> {
                                    // Share app
                                    getApkPath(it.appInfo.packageName.toString()).let { result ->
                                        if (!result.isSuccess) {
                                            Toast.makeText(this, "Failed to get app Path", Toast.LENGTH_SHORT).show()
                                            Log.e("MainActivity", "onCreate: failed to get app Path ${result.err.joinToString("\n")}")
                                        }else{
                                            Log.d("MainActivity", "onCreate: success\n ${result.out.joinToString("\n")}")
                                        }
                                    }
                                }
                                is AppClickAction.Uninstall -> {
                                    if(it.appInfo.isSystem){
                                        Toast.makeText(this, "Cannot uninstall system app", Toast.LENGTH_SHORT).show()
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
            }
        }
    }


}
