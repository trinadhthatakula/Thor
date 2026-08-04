// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.presentation.theme.ThorTheme
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
        // Reached from outside the app and long-lived enough to still be open when the user changes
        // the language in a separate task; recreating keeps its strings in step. No-op above API 32.
        AppLocale.recreateOnChange(this, attachedLocaleTag)
        // Reset the app-scoped installer event bus to Idle on a fresh launch only
        // (savedInstanceState == null). Without this, a leftover Success/ReadyToInstall
        // from a previous install would be replayed by the SharedFlow and suppress the
        // Idle-gated auto-parse of the newly shared APK. On a configuration-change
        // recreation (savedInstanceState != null) we intentionally keep the current
        // state so an in-progress install isn't reset and re-parsed.
        if (savedInstanceState == null) {
            installerViewModel.resetState()
        }
        setContent {
            val prefs by preferenceRepository.userPreferences
                .collectAsStateWithLifecycle(initialValue = UserPreferences())

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
                PortableInstaller(
                    viewModel = installerViewModel,
                    onDismiss = {
                        finish()
                    }
                )
            }
        }
    }
}

