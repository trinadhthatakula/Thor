// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.R
import com.valhalla.thor.presentation.common.asActivityPreferences
import com.valhalla.thor.util.AppLocale
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class PortableInstallerActivity : ComponentActivity() {

    private val installerViewModel: InstallerViewModel by viewModel()
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
        val activityPreferences = preferenceRepository.userPreferences
            .asActivityPreferences(lifecycleScope)
        // Reached from outside the app and long-lived enough to still be open when the user changes
        // the language in a separate task; recreating keeps its strings in step. No-op above API 32.
        AppLocale.recreateOnChange(this, attachedLocaleTag)
        installerViewModel.receivePortableIntent(intent)
        setContent {
            val preferenceState by activityPreferences.collectAsStateWithLifecycle()
            val kernel by installerViewModel.kernelPackage.collectAsStateWithLifecycle()
            LaunchedEffect(kernel != null) {
                setTitle(if (kernel != null) R.string.bolt else R.string.title_thor_installer)
            }
            InstallerPreferencesContent(preferences = preferenceState?.preferences) {
                PortableInstaller(
                    viewModel = installerViewModel,
                    onDismiss = {
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        installerViewModel.receivePortableIntent(intent, newIntent = true)
    }
}
