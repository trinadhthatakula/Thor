package com.valhalla.thor.presentation.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppListType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardHeader(
    isRoot: Boolean,
    isShizuku: Boolean,
    selectedType: AppListType,
    onTypeChanged: (AppListType) -> Unit,
    onRestrictedStatusClick: () -> Unit, // Renamed for clarity: Triggers parent dialog
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // LEFT: Title Block
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.thor_mono),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Thor",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Dashboard",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // RIGHT: Controls (Status Icon + Switcher)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 1. Status Icon with Tooltip
            StatusIcon(
                isRoot = isRoot,
                isShizuku = isShizuku,
                onClick = onRestrictedStatusClick
            )

            Spacer(Modifier.width(12.dp))

            // 2. App Type Switcher
            SingleChoiceSegmentedButtonRow {
                AppListType.entries.forEachIndexed { index, type ->
                    val isSelected = selectedType == type
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        onClick = { onTypeChanged(type) },
                        selected = isSelected
                    ) {
                        Icon(
                            painter = painterResource(if (type == AppListType.USER) R.drawable.apps else R.drawable.android),
                            contentDescription = type.name
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusIcon(
    isRoot: Boolean,
    isShizuku: Boolean,
    onClick: () -> Unit
) {

    val (icon, color, tooltip) = when {
        isRoot -> Triple(
            R.drawable.magisk_icon,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "Root Access Granted"
        )

        isShizuku -> Triple(
            R.drawable.shizuku,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "Shizuku Access Granted"
        )

        else -> Triple(
            R.drawable.round_close,
            MaterialTheme.colorScheme.error,
            "Restricted Mode"
        )
    }

    val scope = rememberCoroutineScope()
    val tooltipState = rememberTooltipState()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { Text(tooltip) },
        state = tooltipState
    ) {
        IconButton(
            onClick = {
                if (!isRoot && !isShizuku) {
                    // If restricted, notify parent to show dialog
                    onClick()
                } else {
                    // If granted, just show the tooltip locally
                    scope.launch {
                        if (!tooltipState.isVisible) {
                            tooltipState.show()
                        }
                    }
                }
            }
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = tooltip,
                tint = color
            )
        }
    }
}