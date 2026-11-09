// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import kotlinx.serialization.Serializable

@Serializable enum class NavigationPlacement { DOCKED, FLOATING }
@Serializable enum class NavigationAppearance { EXPANDING, SEGMENTED, STANDARD }
@Serializable enum class NavigationLabels { AUTOMATIC, SELECTED, ALWAYS, HIDDEN }
@Serializable enum class NavigationShape { DEFAULT, ROUND, SOFT, SQUARE }

/** App-owned, backed-up choices shared by compact navigation and the adaptive rail. */
@Serializable
data class NavigationBarPreferences(
    val placement: NavigationPlacement = NavigationPlacement.DOCKED,
    val appearance: NavigationAppearance = NavigationAppearance.EXPANDING,
    val labels: NavigationLabels = NavigationLabels.AUTOMATIC,
    val showIcons: Boolean = true,
    val containerShape: NavigationShape = NavigationShape.DEFAULT,
    val indicatorShape: NavigationShape = NavigationShape.DEFAULT,
    val spacing: Int = 4,
    val floatingMargin: Int = 12,
    val elevation: Int = 6,
    val contentPadding: Int = 8,
) {
    /** Text-only navigation keeps names visible without replacing the user's label choice. */
    val effectiveLabels: NavigationLabels
        get() = if (showIcons) labels else NavigationLabels.ALWAYS

    /** Clamps imported sizes to the editor's ranges while preserving appearance and label choices. */
    fun normalized(): NavigationBarPreferences = copy(
        spacing = spacing.coerceIn(0, 16),
        floatingMargin = floatingMargin.coerceIn(0, 32),
        elevation = elevation.coerceIn(0, 16),
        contentPadding = contentPadding.coerceIn(0, 24),
    )
}
