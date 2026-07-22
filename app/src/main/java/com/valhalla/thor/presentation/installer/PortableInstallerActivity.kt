// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.valhalla.thor.presentation.theme.ThorTheme
import org.koin.androidx.viewmodel.ext.android.viewModel

class PortableInstallerActivity : ComponentActivity() {

    private val installerViewModel: InstallerViewModel by viewModel()

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
            ThorTheme {
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

