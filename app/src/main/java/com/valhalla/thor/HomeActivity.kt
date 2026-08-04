// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
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
import com.valhalla.thor.data.security.promptAuthenticators
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.presentation.common.ShizukuPermissionHandler
import com.valhalla.thor.presentation.home.HomeViewModel
import com.valhalla.thor.presentation.main.MainScreen
import com.valhalla.thor.presentation.security.AuthState
import com.valhalla.thor.presentation.security.BiometricScreen
import com.valhalla.thor.presentation.security.BiometricUnavailableScreen
import com.valhalla.thor.presentation.security.SecurityViewModel
import com.valhalla.thor.presentation.settings.BillingProcessor
import com.valhalla.thor.presentation.theme.ThorTheme
import com.valhalla.thor.presentation.utils.ObserveAsEvents
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
    private val billingProcessor: BillingProcessor by inject()

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

                // The two things SecurityViewModel says, both about the app lock going away without
                // the user asking: it disarms a lock this device can never open, and it reports a
                // settings file it could not read — which drops the lock preference to `false` the
                // same way. Neither may happen silently; the user set that lock deliberately and is
                // entitled to know. Collected here rather than in a screen because both land
                // *before* any screen: they are what decides which of the branches below composes
                // at all.
                ObserveAsEvents(securityViewModel.events) { event ->
                    Toast.makeText(
                        this@HomeActivity,
                        event.asString(this@HomeActivity),
                        Toast.LENGTH_LONG
                    ).show()
                }

                when (authState) {
                    AuthState.NotRequired,
                    AuthState.Unlocked -> {
                        MainScreen(
                            homeViewModel = homeViewModel,
                            onExit = { finish() }
                        )
                    }

                    AuthState.Unavailable -> {
                        BiometricUnavailableScreen(
                            onOpenSecuritySettings = { openSecuritySettings() },
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
     * Sends the user to where they can enroll something the prompt accepts, which is the only thing
     * that clears [AuthState.Unavailable]. `ACTION_BIOMETRIC_ENROLL` carries the same authenticator
     * mask [promptAuthenticators] gates on, so the enrollment page it opens is the one that will
     * actually satisfy the check; it is API 30+, and below that (or on any OEM build that does not
     * resolve it) security settings is the closest thing. Falling all the way back to the settings
     * root is still better than swallowing the tap.
     */
    private fun openSecuritySettings() {
        val targets = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                        Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                        promptAuthenticators(Build.VERSION.SDK_INT)
                    )
                )
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in targets) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Logger.w("HomeActivity", "No activity for ${intent.action}: ${e.message}")
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
        // Before the Shizuku early-return, not after: this is how the user gets out of
        // AuthState.Unavailable. They leave to set a screen lock and come back, and only a
        // re-query turns that into an unlocked app rather than the same dead end.
        securityViewModel.refreshCapability()
        // Above the Shizuku early-return, which latches after the first resume — this one has to
        // run on *every* resume. A purchase completed while Thor's process was dead is never
        // reported by onPurchasesUpdated, and Google revokes and refunds anything left
        // unacknowledged for three days; coming back to the app is the first chance to catch it.
        // The store implementation does its work on a background scope and the foss one is a no-op.
        billingProcessor.refreshPurchases()
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
