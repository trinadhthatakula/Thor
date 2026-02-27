package com.valhalla.thor

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.presentation.common.ShizukuPermissionHandler
import com.valhalla.thor.presentation.home.HomeViewModel
import com.valhalla.thor.presentation.main.MainScreen
import com.valhalla.thor.presentation.security.AuthState
import com.valhalla.thor.presentation.security.BiometricScreen
import com.valhalla.thor.presentation.security.SecurityViewModel
import com.valhalla.thor.presentation.settings.SettingsViewModel
import com.valhalla.thor.presentation.theme.ThorTheme
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class HomeActivity : FragmentActivity() {

    private val systemRepository: SystemRepository by inject()
    private val homeViewModel: HomeViewModel by viewModel()
    private val securityViewModel: SecurityViewModel by viewModel()
    private val settingsViewModel: SettingsViewModel by viewModel()

    private val requestCode = 1001
    private var hasRequestedShizuku = false

    private val shizukuHandler = ShizukuPermissionHandler(
        onPermissionGranted = {
            Logger.d("HomeActivity", "Shizuku Ready")
            homeViewModel.loadDashboardData()
        },
        onPermissionDenied = {
            Logger.d("HomeActivity", "Shizuku Denied")
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
            val prefs by settingsViewModel.preferences.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (prefs.themeMode) {
                ThemeMode.LIGHT  -> false
                ThemeMode.DARK   -> true
                ThemeMode.SYSTEM -> systemDark
            }

            ThorTheme(
                darkTheme = darkTheme,
                dynamicColor = prefs.useDynamicColor,
                amoledMode = prefs.useAmoled,
            ) {
                val authState by securityViewModel.authState.collectAsStateWithLifecycle()

                when (authState) {
                    AuthState.NotRequired,
                    AuthState.Unlocked -> {
                        MainScreen(
                            homeViewModel = homeViewModel,
                            onExit = { finish() }
                        )
                    }

                    AuthState.Locked -> {
                        BiometricScreen(
                            isError = false,
                            errorMessage = "",
                            onAuthenticated = { securityViewModel.onAuthenticated() },
                            onError = { message ->
                                Logger.e("HomeActivity", "Biometric error: $message")
                                securityViewModel.onAuthError(message)
                            },
                            onRetry = { securityViewModel.onRetry() },
                            onExit = { finish() }
                        )
                    }

                    is AuthState.Error -> {
                        BiometricScreen(
                            isError = true,
                            errorMessage = (authState as AuthState.Error).message,
                            onAuthenticated = { securityViewModel.onAuthenticated() },
                            onError = { message ->
                                Logger.e("HomeActivity", "Biometric error: $message")
                                securityViewModel.onAuthError(message)
                            },
                            onRetry = { securityViewModel.onRetry() },
                            onExit = { finish() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
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
