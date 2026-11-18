// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.valhalla.asgard.navigation.*
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.presentation.home.AppDestinations

/** Builds the editor's preview destinations with localized labels and their selected icons. */
@Composable
internal fun thorNavigationItems(): List<AsgardNavItem> = AppDestinations.entries.map {
    AsgardNavItem(
        icon = ImageVector.vectorResource(it.icon),
        selectedIcon = ImageVector.vectorResource(it.selectedIcon),
        label = stringResource(it.label),
        contentDescription = stringResource(it.contentDescription),
    )
}

/** The preview and the actual navigation resolve exactly the same app-owned settings. */
@Composable
internal fun navigationConfiguration(
    preferences: NavigationBarPreferences,
    rail: Boolean = false,
    railLabelsFit: Boolean = true,
    preview: Boolean = false,
    scaffoldFloating: Boolean = false,
): AsgardNavigationConfiguration {
    val prefs = preferences.normalized()
    return AsgardNavigationConfiguration(
        placement = when (prefs.placement) {
            NavigationPlacement.DOCKED -> AsgardNavigationPlacement.Docked
            NavigationPlacement.FLOATING -> AsgardNavigationPlacement.Floating
        },
        appearance = when (prefs.appearance) {
            NavigationAppearance.EXPANDING -> AsgardNavigationAppearance.Expanding
            NavigationAppearance.SEGMENTED -> AsgardNavigationAppearance.Segmented
            NavigationAppearance.STANDARD -> AsgardNavigationAppearance.Standard
        },
        labels = when (prefs.effectiveLabels) {
            NavigationLabels.AUTOMATIC -> if (rail && railLabelsFit) AsgardNavigationLabels.Always else AsgardNavigationLabels.SelectedCaptionWhenNeeded
            NavigationLabels.SELECTED -> AsgardNavigationLabels.Selected
            NavigationLabels.ALWAYS -> AsgardNavigationLabels.Always
            NavigationLabels.HIDDEN -> AsgardNavigationLabels.Hidden
        },
        showIcons = prefs.showIcons,
        containerShape = navigationShape(prefs.containerShape),
        indicatorShape = navigationShape(prefs.indicatorShape),
        contentPadding = PaddingValues(prefs.contentPadding.dp),
        floatingMargin = prefs.floatingMargin.dp,
        spacing = prefs.spacing.dp,
        elevation = prefs.elevation.dp,
        insets = if (preview || rail || scaffoldFloating) AsgardNavigationInsets.None else AsgardNavigationInsets.NavigationBars,
    )
}

/** Resolves a saved shape choice; null lets Asgard use its appearance-specific default. */
@Composable
private fun navigationShape(shape: NavigationShape): Shape? = when (shape) {
    NavigationShape.DEFAULT -> null
    NavigationShape.ROUND -> RoundedCornerShape(50)
    NavigationShape.SOFT -> MaterialTheme.shapes.small
    NavigationShape.SQUARE -> RectangleShape
}
