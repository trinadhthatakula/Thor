// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.os.BundleCompat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.valhalla.thor.presentation.security.AuthState
import com.valhalla.thor.presentation.security.SecurityViewModel
import com.valhalla.thor.presentation.security.BiometricScreen
import com.valhalla.thor.presentation.security.BiometricUnavailableScreen
import com.valhalla.thor.data.security.promptAuthenticators
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.R
import com.valhalla.thor.presentation.common.asActivityPreferences
import com.valhalla.thor.util.AppLocale
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class PortableInstallerActivity : ComponentActivity() {

    private val installerViewModel: InstallerViewModel by viewModel()
    private val securityViewModel: SecurityViewModel by viewModel()
    private data class PendingInput(val intent: Intent, val newIntent: Boolean)
    private var pendingInput by mutableStateOf<PendingInput?>(null)
    private val preferenceRepository: PreferenceRepository by inject()

    /** The locale tag this instance attached with; see [attachBaseContext]. */
    private var attachedLocaleTag: String? = null

    /**
     * Applies the chosen locale on API 28–32, where nothing else will.
     *
     * This activity is a second, independent entry point — users reach it from a file manager via
     * the `VIEW`/`SEND` filters on `.apk`/`.apks`/`.apkm`/`.apkp` — so it never inherits anything
     * `HomeActivity` did. Deliberately identical to the override in [com.valhalla.thor.HomeActivity]
     * and [com.valhalla.thor.presentation.launcher.FreezerLaunchActivity].
     */
    override fun attachBaseContext(newBase: Context) {
        attachedLocaleTag = AppLocale.tagFor(newBase)
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Protect startup until the saved opt-in is known; unprotected installs then clear this.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val activityPreferences = preferenceRepository.userPreferences
            .asActivityPreferences(lifecycleScope)
        // Reached from outside the app and long-lived enough to still be open when the user changes
        // the language in a separate task; recreating keeps its strings in step. No-op above API 32.
        AppLocale.recreateOnChange(this, attachedLocaleTag)
        val savedPendingIntent = savedInstanceState?.let {
            BundleCompat.getParcelable(it, "pending_intent", Intent::class.java)
        }
        pendingInput = PendingInput(
            savedPendingIntent ?: intent,
            newIntent = savedInstanceState?.getBoolean("pending_new_intent") == true,
        )
        setContent {
            val preferenceState by activityPreferences.collectAsStateWithLifecycle()
            val authState by securityViewModel.installerAuthState.collectAsStateWithLifecycle()
            val kernel by installerViewModel.kernelPackage.collectAsStateWithLifecycle()
            LaunchedEffect(authState) {
                if (authState == AuthState.NotRequired) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            LaunchedEffect(Unit) {
                securityViewModel.events.collect { Toast.makeText(this@PortableInstallerActivity,
                    it.asString(this@PortableInstallerActivity), Toast.LENGTH_LONG).show() }
            }
            LaunchedEffect(authState, pendingInput) {
                val input = pendingInput
                if (input != null && (authState == AuthState.NotRequired || authState == AuthState.Unlocked)) {
                    installerViewModel.receivePortableIntent(input.intent, newIntent = input.newIntent)
                    pendingInput = null
                }
            }
            LaunchedEffect(kernel != null) {
                setTitle(if (kernel != null) R.string.bolt else R.string.title_thor_installer)
            }
            InstallerPreferencesContent(preferences = preferenceState?.preferences) {
                when (authState) {
                    AuthState.Loading -> Unit
                    AuthState.NotRequired, AuthState.Unlocked -> PortableInstaller(
                        viewModel = installerViewModel,
                        onDismiss = { finish() },
                    )
                    AuthState.Unavailable -> BiometricUnavailableScreen(
                        onOpenSecuritySettings = ::openSecuritySettings,
                        onExit = { finish() },
                    )
                    AuthState.Locked, is AuthState.Error -> BiometricScreen(
                        isError = authState is AuthState.Error,
                        errorMessage = (authState as? AuthState.Error)?.message.orEmpty(),
                        onAuthenticated = securityViewModel::onAuthenticated,
                        onError = securityViewModel::onAuthError,
                        onRetry = securityViewModel::onRetry,
                        onExit = { finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Retain incoming files while locked. An authenticated activity keeps its current session,
        // matching Home's launch-based app lock; an already admitted job is never cancelled here.
        pendingInput = PendingInput(intent, newIntent = true)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable("pending_intent", pendingInput?.intent)
        outState.putBoolean("pending_new_intent", pendingInput?.newIntent == true)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        securityViewModel.refreshCapability()
    }

    private fun openSecuritySettings() {
        val targets = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(
                Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    promptAuthenticators(Build.VERSION.SDK_INT),
                ),
            )
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (target in targets) {
            try { startActivity(target); return }
            catch (_: ActivityNotFoundException) { /* Try the next system settings surface. */ }
        }
    }

}
