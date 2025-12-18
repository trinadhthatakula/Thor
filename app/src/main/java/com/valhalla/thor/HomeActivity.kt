package com.valhalla.thor

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.presentation.common.ShizukuPermissionHandler
import com.valhalla.thor.presentation.main.MainScreen
import com.valhalla.thor.presentation.theme.ThorTheme
import org.koin.android.ext.android.inject

class HomeActivity : ComponentActivity() {

    private val systemRepository: SystemRepository by inject()
    private val requestCode = 1001

    private val shizukuHandler = ShizukuPermissionHandler(
        onPermissionGranted = {
            Log.d("HomeActivity", "Shizuku Ready")
        },
        onBinderDead = {
            Log.w("HomeActivity", "Shizuku Binder Died")
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()

        // 1. Register listeners immediately
        shizukuHandler.register()

        setContent {
            ThorTheme {
                MainScreen(
                    onExit = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Only bother with Shizuku if we don't have Root.
        // We do this in onResume so if the user comes back from the Shizuku manager,
        // we catch the permission grant immediately.
        if (!systemRepository.isRootAvailable()) {
            shizukuHandler.checkAndRequestPermission(requestCode)
        }
    }

    override fun onDestroy() {
        // 3. Clean up to prevent leaks
        shizukuHandler.unregister()
        super.onDestroy()
    }
}