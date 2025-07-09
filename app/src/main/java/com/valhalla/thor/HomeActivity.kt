package com.valhalla.thor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.valhalla.thor.model.shizuku.ShizukuState
import com.valhalla.thor.model.rootAvailable
import com.valhalla.thor.model.shizuku.shizukuManager
import com.valhalla.thor.ui.home.HomePage
import com.valhalla.thor.ui.theme.ThorTheme

class HomeActivity : ComponentActivity() {

    override fun onStart() {
        super.onStart()
        try {
            if (rootAvailable().not() && shizukuManager.shizukuStateRaw == ShizukuState.PermissionNeeded) {
                shizukuManager.requestPermission()
            }
        }catch (_: Exception){ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        setContent {
            ThorTheme {
                HomePage(
                    modifier = Modifier
                ) {
                    finish()
                }
            }
        }
    }
}
