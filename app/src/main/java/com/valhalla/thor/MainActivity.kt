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
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.UserAppInfo
import com.valhalla.thor.model.copyTo
import com.valhalla.thor.ui.screens.AppListScreen
import com.valhalla.thor.ui.theme.ThorTheme
import com.valhalla.thor.ui.widgets.AppAction
import kotlinx.coroutines.delay
import java.io.File

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
                                AppAction.Reinstall ->{
                                    //startActivity(Intent().setClass(this, ScriptRunner::class.java))
                                }
                                is AppAction.Launch -> {
                                    it.appInfo.packageName?.let { appPackage ->
                                        this.packageManager.getLaunchIntentForPackage(appPackage)?.let {
                                            startActivity(it)
                                        }?:run {
                                            Toast.makeText(this, "Failed to launch app", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                is AppAction.Share -> {
                                    // Share app
                                    it.appInfo.publicSourceDir?.let { sourcePath ->
                                        val sourceDir = File(sourcePath)
                                        if(sourceDir.exists()) {
                                            sourceDir.listFiles()?.filter { it.name.contains("apk") }?.let { apkFile ->
                                                apkFile.firstOrNull()?.let { apk ->
                                                    val tempFile = File(filesDir, apk.name)
                                                    if(apk.copyTo(tempFile)) {
                                                        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", tempFile)
                                                        val intent = Intent(Intent.ACTION_SEND)
                                                        intent.type =
                                                            "application/vnd.android.package-archive"
                                                        intent.putExtra(Intent.EXTRA_STREAM, uri)
                                                        startActivity(
                                                            Intent.createChooser(
                                                                intent,
                                                                "Share app Using"
                                                            )
                                                        )
                                                    }
                                                }?:run {
                                                    Toast.makeText(this, "Failed to share app apk not found", Toast.LENGTH_SHORT).show()
                                                }
                                            }?:run {
                                                Toast.makeText(this, "Failed to share app apk not found in public folder", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(this, "Failed to share app public folder not found", Toast.LENGTH_SHORT).show()
                                        }
                                    }

                                }
                                is AppAction.Uninstall -> {
                                    val appPackage = it.appInfo.packageName
                                    val intent = Intent(Intent.ACTION_DELETE)
                                    intent.data = "package:${appPackage}".toUri()
                                    startActivity(intent)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
