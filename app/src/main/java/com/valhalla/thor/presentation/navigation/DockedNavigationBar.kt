// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.navigation

import android.os.Build
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView

/** Extends the docked surface behind the system bar without padding its content a second time. */
@Composable
internal fun DockedNavigationBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val window = if (LocalView.current.isInEditMode) null else LocalActivity.current?.window
    val systemBar = remember(window) { window?.let(::DockedNavigationSystemBar) }
    DisposableEffect(systemBar) {
        onDispose { systemBar?.restore() }
    }
    // The theme can reapply edge-to-edge styles after a theme change. Capture that new style
    // before making the system bar transparent again, so leaving the docked bar restores it.
    SideEffect { systemBar?.apply() }

    Box(modifier) {
        Spacer(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        )
        content()
    }
}

@Suppress("DEPRECATION")
private class DockedNavigationSystemBar(private val window: Window) {
    private var previousColor: Int? = null
    private var previousContrast: Boolean? = null

    fun apply() {
        val color = window.navigationBarColor
        if (previousColor == null || color != android.graphics.Color.TRANSPARENT) previousColor = color
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contrast = window.isNavigationBarContrastEnforced
            if (previousContrast == null || contrast) previousContrast = contrast
            window.isNavigationBarContrastEnforced = false
        }
        // Android 9 uses navigationBarColor; newer versions can also add a contrast scrim.
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    fun restore() {
        previousColor?.let { window.navigationBarColor = it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            previousContrast?.let { window.isNavigationBarContrastEnforced = it }
        }
    }
}
