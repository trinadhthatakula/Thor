package com.valhalla.thor

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.presentation.common.ShizukuPermissionHandler
import com.valhalla.thor.presentation.home.HomeViewModel
import com.valhalla.thor.presentation.main.MainScreen
import com.valhalla.thor.presentation.theme.ThorTheme
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class HomeActivity : ComponentActivity() {

    private val systemRepository: SystemRepository by inject()
    private val homeViewModel: HomeViewModel by viewModel()

    private val requestCode = 1001
    private var hasRequestedShizuku = false

    private val shizukuHandler = ShizukuPermissionHandler(
        onPermissionGranted = {
            Logger.d("HomeActivity", "Shizuku Ready")
            homeViewModel.loadDashboardData()
        },
        onPermissionDenied = {
            Logger.d("HomeActivity", "Shizuku Denied")
            // We stop asking automatically. User must click "Refresh" in dashboard manually now.
        },
        onBinderDead = {
            Logger.w("HomeActivity", "Shizuku Binder Died")
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()

        shizukuHandler.register()

        setContent {
            ThorTheme {
                MainScreen(
                    homeViewModel = homeViewModel,
                    onExit = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            // Only ask automatically ONCE per session if not rooted.
            if (!systemRepository.isRootAvailable() && !hasRequestedShizuku) {
                hasRequestedShizuku = true
                shizukuHandler.checkAndRequestPermission(requestCode)
            }
        }
    }

    override fun onDestroy() {
        shizukuHandler.unregister()
        super.onDestroy()
    }
}