// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.presentation.common.ShizukuPermissionHandler
import com.valhalla.thor.presentation.home.HomeViewModel
import com.valhalla.thor.presentation.main.MainScreen
import com.valhalla.thor.presentation.security.AuthState
import com.valhalla.thor.presentation.security.BiometricScreen
import com.valhalla.thor.presentation.security.SecurityViewModel
import com.valhalla.thor.presentation.theme.ThorTheme
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class HomeActivity : ComponentActivity() {

    private val privilegeManager: PrivilegeManager by inject()
    private val homeViewModel: HomeViewModel by viewModel()
    private val securityViewModel: SecurityViewModel by viewModel()
    private val preferenceRepository: PreferenceRepository by inject()

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
            val prefs by preferenceRepository.userPreferences.collectAsStateWithLifecycle(initialValue = UserPreferences())

            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (prefs.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
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

                    AuthState.Locked,
                    is AuthState.Error -> {
                        BiometricScreen(
                            isError = authState is AuthState.Error,
                            errorMessage = (authState as? AuthState.Error)?.message ?: "",
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

    /**
     * Ask for Shizuku only if root did not answer — read from [PrivilegeManager], not by probing.
     *
     * This used to call `systemRepository.isRootAvailable()` directly, which made it the *second*
     * caller of a probe `PrivilegeManager` already runs at startup. Both land in Odin's
     * `@Synchronized MainShell.get()`, so the two raced: whichever arrived first paid for shell
     * init and the other either waited on the monitor or found the shell already built. That is the
     * bimodality the #22 measurement found — a root probe taking 62-85ms in four cold starts out of
     * ten and 627-789ms in the other six, with nothing in between.
     *
     * Waiting on `isReady` costs nothing extra: the probe was already running, this is the same
     * result the rest of the app is waiting for, and `hasRequestedShizuku` still latches so the
     * request fires once per activity. The early return keeps repeated `onResume` calls from
     * stacking collectors on `lifecycleScope`, which outlives each pause.
     */
    override fun onResume() {
        super.onResume()
        if (hasRequestedShizuku) return
        lifecycleScope.launch {
            val privileges = privilegeManager.state.first { it.isReady }
            if (!privileges.root && !hasRequestedShizuku) {
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
