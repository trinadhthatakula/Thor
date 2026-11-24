// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.presentation.utils.ApkVersionText
import com.valhalla.thor.presentation.utils.apkVersionCode
import com.valhalla.asgard.components.StatusChip as AsgardStatusChip

/**
 * The metadata chip shared by [AppInfoSheet]'s header and the details screen's.
 *
 * Barely more than Asgard's chip, and worth a name only for the default it pins. Asgard derives the
 * content colour from the container via `contentColorFor`, which answers `Color.Unspecified` for a
 * container outside the theme's colour roles — `outlineVariant`, say — leaving the label to fall
 * through to the ambient content colour. Defaulting to `onSurface` gives every chip that names a
 * container and nothing else one predictable answer, and keeps the two headers agreeing on it:
 * this was two byte-identical private copies, one in each file.
 */
@Composable
internal fun StatusChip(
    text: String,
    color: Color,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    AsgardStatusChip(text = text, containerColor = color, contentColor = textColor)
}

@Composable
internal fun AppVersionChip(app: AppInfo, sha256: String?) {
    ApkVersionText(
        version = app.versionName?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.version_format, it) },
        versionCode = apkVersionCode(app.versionCode),
        sha256 = sha256,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        centered = true,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
