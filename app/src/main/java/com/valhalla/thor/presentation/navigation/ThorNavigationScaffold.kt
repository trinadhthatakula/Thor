// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Clearance for scroll-content tails and action overlays, without shortening the page viewport. */
internal val LocalFloatingNavigationClearance = compositionLocalOf { 0.dp }

/** Docked navigation consumes bottom-bar space; floating navigation overlays the page as a FAB. */
@Composable
internal fun ThorNavigationScaffold(
    floatingNavigation: Boolean,
    navigationBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    var navigationHeightPx by remember(floatingNavigation) { mutableIntStateOf(0) }
    val navigation: @Composable () -> Unit = {
        // The Asgard bar already includes the user's floating margin. Compensate for Scaffold's
        // additional FAB gap so that margin is the only space between the bar and the safe edge.
        Box(Modifier
            .then(if (floatingNavigation) Modifier.offset(y = FloatingFabGap) else Modifier)
            .onSizeChanged { navigationHeightPx = it.height }) { navigationBar() }
    }
    Scaffold(
        modifier = modifier.imePadding(),
        snackbarHost = snackbarHost,
        bottomBar = {
            // An empty bottomBar slot otherwise measures zero and prevents Scaffold from using
            // its system-bottom inset for FAB positioning. Reserve only that inset when floating.
            Column(if (floatingNavigation) Modifier.navigationBarsPadding() else Modifier) {
                bottomBar()
                if (!floatingNavigation) navigation()
            }
        },
        floatingActionButton = { if (floatingNavigation) navigation() },
        floatingActionButtonPosition = FabPosition.Center,
    ) { innerPadding ->
        // The bar's measured height includes its floating margin, padding and label layout.
        // The default FAB gap was compensated above and must not be added again here.
        val clearance = if (floatingNavigation && navigationHeightPx > 0) {
            with(LocalDensity.current) { navigationHeightPx.toDp() }
        } else 0.dp
        CompositionLocalProvider(LocalFloatingNavigationClearance provides clearance) {
            content(innerPadding)
        }
    }
}

private val FloatingFabGap = 16.dp
