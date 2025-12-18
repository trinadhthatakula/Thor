package com.valhalla.thor

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.presentation.main.MainScreen
import com.valhalla.thor.presentation.theme.ThorTheme
import org.koin.android.ext.android.inject
import rikka.shizuku.Shizuku

class HomeActivity : ComponentActivity() {

    // We inject the Repository to check status, but we don't need a ViewModel here
    // because MainScreen has its own MainViewModel.
    private val systemRepository: SystemRepository by inject()

    private val requestCode = 1001

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == requestCode) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                Log.d("HomeActivity", "Shizuku permission granted")
                // In a reactive setup, the Repository/Gateway would automatically pick this up
                // next time isShizukuAvailable() is called.
                // If we need to refresh UI immediately, the MainViewModel's polling or
                // lifecycle observation will handle it.
            } else {
                Log.d("HomeActivity", "Shizuku permission denied")
            }
        }
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d("HomeActivity", "Shizuku binder received")
        // Gateway will now return true for isShizukuAvailable()
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d("HomeActivity", "Shizuku binder dead")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()

        // Register Shizuku listeners immediately
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)

        // Initial Check: If Root is missing, try to hook into Shizuku
        checkShizukuInitialization()

        setContent {
            ThorTheme {
                MainScreen(
                    onExit = { finish() }
                )
            }
        }
    }

    private fun checkShizukuInitialization() {
        if (!systemRepository.isRootAvailable()) {
            try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    // Good to go
                } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                    // Users usually know what they are doing with Shizuku,
                    // but ideally, we show a UI rationale.
                    // For auto-init, we just request it.
                    Shizuku.requestPermission(requestCode)
                } else {
                    Shizuku.requestPermission(requestCode)
                }
            } catch (e: Exception) {
                // Shizuku likely not installed or binder not ready
                Log.e("HomeActivity", "Shizuku init failed", e)
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        super.onDestroy()
    }
}