// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.FontPreset
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.presentation.theme.ThorTheme

/** Show startup feedback without drawing installer text before its saved font is known. */
@Composable
internal fun InstallerPreferencesContent(
    preferences: UserPreferences?,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (preferences?.themeMode ?: ThemeMode.SYSTEM) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    ThorTheme(
        darkTheme = darkTheme,
        dynamicColor = preferences?.useDynamicColor ?: false,
        amoledMode = preferences?.useAmoled ?: false,
        fontPreset = preferences?.fontPreset ?: FontPreset.ASGARD,
    ) {
        if (preferences == null) {
            val description = stringResource(R.string.log_initializing)
            // Keep the activity translucent so the source file manager remains behind it.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(shape = MaterialTheme.shapes.extraLarge) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(24.dp)
                            .semantics { contentDescription = description },
                    )
                }
            }
        } else {
            content()
        }
    }
}
