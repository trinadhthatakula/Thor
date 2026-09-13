// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.data.security.promptAuthenticators
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.presentation.common.ShizukuPermissionHandler
import com.valhalla.thor.presentation.common.asActivityPreferences
import com.valhalla.thor.presentation.home.HomeViewModel
import com.valhalla.thor.presentation.main.MainScreen
import com.valhalla.thor.presentation.main.toDestination
import com.valhalla.thor.presentation.security.AuthState
import com.valhalla.thor.presentation.security.BiometricScreen
import com.valhalla.thor.presentation.security.BiometricUnavailableScreen
import com.valhalla.thor.presentation.security.SecurityViewModel
import com.valhalla.thor.presentation.settings.BillingProcessor
import com.valhalla.thor.presentation.theme.ThorTheme
import com.valhalla.thor.presentation.utils.ObserveAsEvents
import com.valhalla.thor.util.AppLocale
import com.valhalla.thor.util.AppScanRevision
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

    /**
     * The `.thorbak` this activity was opened on, or null for an ordinary launch.
     *
     * Read from `intent` rather than from `onNewIntent`: [HomeActivity] declares no `launchMode`, so
     * it is `standard` — a VIEW intent creates a new instance rather than delivering to the existing
     * one, and that new instance's `intent` is the one carrying the URI.
     *
     * The grant that arrives with a VIEW intent lives as long as this **task**, not as long as the
     * process, and it is not persistable. A restore whose task the user swipes away mid-job fails with
     * a "could not open that file" message — which is the reason §8.5's breadcrumb exists to cover the
     * data side.
     */
    private val pendingRestoreUri: String? by lazy {
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.toString()
    }

    /** The locale tag this instance attached with; see [attachBaseContext]. */
    private var attachedLocaleTag: String? = null

    /**
     * Applies the chosen locale on API 28–32, where nothing else will.
     *
     * Spelled identically in [com.valhalla.thor.presentation.installer.PortableInstallerActivity]
     * and [com.valhalla.thor.presentation.launcher.FreezerLaunchActivity]: an entry point that
     * omits this renders in the system locale while the rest of the app does not, and "the language
     * picker works" quietly stops being true for whoever arrives through that door.
     */
    override fun attachBaseContext(newBase: Context) {
        attachedLocaleTag = AppLocale.tagFor(newBase)
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private val shizukuHandler = ShizukuPermissionHandler(
        onPermissionGranted = {
            Logger.d("HomeActivity", "Shizuku Ready")
            AppScanRevision.bump()
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
        val splashScreen = installSplashScreen()
        // Start one subscription before composition. The first snapshot pins the start tab;
        // subsequent snapshots update appearance without moving the user's current screen.
        val activityPreferences = preferenceRepository.userPreferences
            .asActivityPreferences(lifecycleScope)
        // Hold the frame until the app lock has answered. The preference comes from DataStore, which
        // answers a beat after `setContent` returns, so without this the branch the user sees first
        // is decided by a race rather than by the preference — and the losing outcome is the app,
        // fully composed, behind a lock that had not finished switching on.
        //
        // The first preference snapshot supplies both the start tab and typography. Waiting for
        // it avoids composing navigation against a guess or flashing fonts the user replaced.
        splashScreen.setKeepOnScreenCondition {
            securityViewModel.authState.value == AuthState.Loading || activityPreferences.value == null
        }
        enableEdgeToEdge()
        // Settings lives inside this activity, so a language change never leaves it: without this
        // the new locale would only appear on the next cold start. A no-op above API 32, where the
        // platform relaunches the activity itself.
        AppLocale.recreateOnChange(this, attachedLocaleTag)
        shizukuHandler.register()

        setContent {
            val preferenceState by activityPreferences.collectAsStateWithLifecycle()
            val authState by securityViewModel.authState.collectAsStateWithLifecycle()

            // Observe authentication even while preferences are loading. Every state except
            // NotRequired protects the Recents snapshot, screenshots, and recordings; a missing
            // preference snapshot must never be treated as an unlocked app.
            LaunchedEffect(authState) {
                if (authState == AuthState.NotRequired) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            // Security notices can arrive before any screen or theme is ready.
            ObserveAsEvents(securityViewModel.events) { event ->
                Toast.makeText(
                    this@HomeActivity,
                    event.asString(this@HomeActivity),
                    Toast.LENGTH_LONG
                ).show()
            }

            val readyPreferences = preferenceState ?: return@setContent
            val prefs = readyPreferences.preferences
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
                fontPreset = prefs.fontPreset,
            ) {
                when (authState) {
                    AuthState.Loading -> {
                        // Deliberately nothing but a backdrop: the splash is still up (its
                        // keep-on-screen condition reads this same state), and composing MainScreen
                        // here is the defect this state exists to stop — its first composition
                        // consumes the restored navigation state, which is then gone by the time the
                        // user authenticates. Filled rather than empty so that a frame between the
                        // splash exiting and MainScreen laying out cannot flash the light window
                        // background of `Theme.Thor` at a dark-theme user.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        )
                    }

                    AuthState.NotRequired,
                    AuthState.Unlocked -> {
                        MainScreen(
                            startDestination = readyPreferences.initialDefaultTab.toDestination(),
                            pendingRestoreUri = pendingRestoreUri,
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
        // Re-probe privileges (e.g. user went to KernelSU/Magisk/APatch manager to grant root,
        // or authorized Shizuku/Dhizuku externally).
        //
        // Gated on isReady, which is the difference between "probe again" and "probe twice".
        // PrivilegeManager starts its first probe from its own init, i.e. from
        // ThorApplication.onCreate, so on a cold start that probe is still in flight when the
        // first onResume lands — refreshing here would bump the trigger and buy a second full
        // root/Shizuku/Dhizuku sweep for an answer already on its way. That is the duplicate
        // c86aa565 ("perf(privilege): one root probe per cold start") removed. isReady latches
        // true on the first emission and never goes back, so every *later* resume — the ones
        // where the user actually did go and grant something — still re-probes.
        if (privilegeManager.state.value.isReady) {
            privilegeManager.refresh()
        }
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
