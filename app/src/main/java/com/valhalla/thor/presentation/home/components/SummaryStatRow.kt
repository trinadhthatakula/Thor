// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valhalla.asgard.animateExpressiveResize
import com.valhalla.asgard.components.AsgardStatTile
import com.valhalla.thor.R

@Composable
fun SummaryStatRow(
    activeCount: Int,
    frozenCount: Int,
    suspendedCount: Int,
    onActiveClick: () -> Unit,
    onFrozenClick: () -> Unit,
    onSuspendedClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatTile(
            label = stringResource(R.string.active),
            count = activeCount,
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = onActiveClick,
        )
        if (frozenCount > 0)
            StatTile(
                label = stringResource(R.string.frozen),
                count = frozenCount,
                valueColor = MaterialTheme.colorScheme.secondary,
                onClick = onFrozenClick,
            )
        if (suspendedCount > 0)
            StatTile(
                label = stringResource(R.string.suspended),
                count = suspendedCount,
                valueColor = MaterialTheme.colorScheme.tertiary,
                onClick = onSuspendedClick,
            )
    }
}

/**
 * Dashboard stat tile — thin wrapper over Asgard's [AsgardStatTile] that keeps Thor's dashboard
 * look (large count-up number above an uppercased label, 32dp squircle, generous padding).
 */
@Composable
private fun RowScope.StatTile(
    label: String,
    count: Int,
    valueColor: Color,
    onClick: () -> Unit,
) {
    AsgardStatTile(
        label = label.uppercase(),
        value = count.toString(),
        modifier = Modifier
            .weight(1f)
            .animateExpressiveResize(),
        onClick = onClick,
        animateValue = true,
        valueFirst = true,
        shape = RoundedCornerShape(32.dp),
        contentPadding = PaddingValues(24.dp),
        valueColor = valueColor,
        valueStyle = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.ExtraBold),
    )
}
