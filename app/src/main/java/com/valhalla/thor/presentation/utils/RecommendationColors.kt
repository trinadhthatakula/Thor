// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun getBloatRecommendationColors(recommendation: String): Pair<Color, Color> {
    return when (recommendation.lowercase()) {
        "recommended" -> Color(0xFFC8E6C9) to Color(0xFF1B5E20)
        "advanced" -> Color(0xFFFFF9C4) to Color(0xFFF57F17)
        "expert" -> Color(0xFFFFE0B2) to Color(0xFFE65100)
        "unsafe" -> Color(0xFFFFCDD2) to Color(0xFFB71C1C)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
}
