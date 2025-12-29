package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.MultiAppAction

@Composable
fun MultiSelectToolBox(
    modifier: Modifier = Modifier,
    selected: List<AppInfo> = emptyList(),
    isRoot: Boolean = false,
    isShizuku: Boolean = false,
    onCancel: () -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {
    var hasFrozen by remember { mutableStateOf(selected.any { !it.enabled }) }
    var hasUnFrozen by remember { mutableStateOf(selected.any { it.enabled }) }

    LaunchedEffect(selected) {
        hasFrozen = selected.any { !it.enabled }
        hasUnFrozen = selected.any { it.enabled }
    }

    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Close Action (Leftmost for easy exit)
            ToolBoxItem(
                icon = R.drawable.round_close,
                label = "Close",
                onClick = onCancel
            )

            // ReInstall (Root only)
            if (isRoot) {
                ToolBoxItem(
                    icon = R.drawable.apk_install,
                    label = "ReInstall",
                    onClick = { onMultiAppAction(MultiAppAction.ReInstall(selected)) }
                )
            }

            // Freeze/Unfreeze (Root OR Shizuku)
            if (isRoot || isShizuku) {
                if (hasUnFrozen) {
                    ToolBoxItem(
                        icon = R.drawable.frozen,
                        label = "Freeze",
                        onClick = { onMultiAppAction(MultiAppAction.Freeze(selected)) }
                    )
                }
                if (hasFrozen) {
                    ToolBoxItem(
                        icon = R.drawable.unfreeze,
                        label = "UnFreeze",
                        onClick = { onMultiAppAction(MultiAppAction.UnFreeze(selected)) }
                    )
                }
            }

            // Standard Actions
            ToolBoxItem(
                icon = R.drawable.clear_all,
                label = "Cache",
                onClick = { onMultiAppAction(MultiAppAction.ClearCache(selected)) }
            )
            ToolBoxItem(
                icon = R.drawable.delete_forever,
                label = "Uninstall",
                onClick = { onMultiAppAction(MultiAppAction.Uninstall(selected)) }
            )
            ToolBoxItem(
                icon = R.drawable.danger,
                label = "Kill",
                onClick = { onMultiAppAction(MultiAppAction.Kill(selected)) }
            )
        }
    }
}

@Composable
private fun ToolBoxItem(
    icon: Int,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            modifier = Modifier
                .size(32.dp) // Slightly smaller than AppInfoDialog to fit bottom bar context
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                .padding(6.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}