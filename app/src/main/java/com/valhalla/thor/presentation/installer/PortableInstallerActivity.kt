// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

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
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class PortableInstallerActivity : ComponentActivity() {

    private val installerViewModel: InstallerViewModel by viewModel()
    private val preferenceRepository: PreferenceRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

