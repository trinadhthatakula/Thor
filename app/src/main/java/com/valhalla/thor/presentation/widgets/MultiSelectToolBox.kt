package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
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
import androidx.compose.ui.res.stringResource
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
    isDhizuku: Boolean = false,
    onCancel: () -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {
    var hasFrozen by remember { mutableStateOf(selected.any { !it.enabled }) }
    var hasUnFrozen by remember { mutableStateOf(selected.any { it.enabled }) }
    var hasSuspended by remember { mutableStateOf(selected.any { it.isSuspended }) }
    var hasUnSuspended by remember { mutableStateOf(selected.any { !it.isSuspended }) }

    LaunchedEffect(selected) {
        hasFrozen = selected.any { !it.enabled }
        hasUnFrozen = selected.any { it.enabled }
        hasSuspended = selected.any { it.isSuspended }
        hasUnSuspended = selected.any { !it.isSuspended }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Close Action (Leftmost for easy exit)
            ToolBoxItem(
                icon = R.drawable.round_close,
                label = stringResource(R.string.close),
                onClick = onCancel
            )

            // ReInstall (Root only)
            if (isRoot) {
                ToolBoxItem(
                    icon = R.drawable.apk_install,
                    label = stringResource(R.string.action_reinstall),
                    onClick = { onMultiAppAction(MultiAppAction.ReInstall(selected)) }
                )
            }

            // Freeze/Unfreeze (Root OR Shizuku OR Dhizuku)
            if (isRoot || isShizuku || isDhizuku) {
                if (hasUnFrozen) {
                    ToolBoxItem(
                        icon = R.drawable.frozen,
                        label = stringResource(R.string.action_freeze),
                        onClick = { onMultiAppAction(MultiAppAction.Freeze(selected)) }
                    )
                }
                if (hasFrozen) {
                    ToolBoxItem(
                        icon = R.drawable.unfreeze,
                        label = stringResource(R.string.action_unfreeze),
                        onClick = { onMultiAppAction(MultiAppAction.UnFreeze(selected)) }
                    )
                }
                if (hasUnSuspended) {
                    ToolBoxItem(
                        icon = R.drawable.warning,
                        label = stringResource(R.string.action_suspend),
                        onClick = { onMultiAppAction(MultiAppAction.Suspend(selected)) }
                    )
                }
                if (hasSuspended) {
                    ToolBoxItem(
                        icon = R.drawable.bolt,
                        label = stringResource(R.string.action_unsuspend),
                        onClick = { onMultiAppAction(MultiAppAction.UnSuspend(selected)) }
                    )
                }
            }

            // Standard Actions
            ToolBoxItem(
                icon = R.drawable.clear_all,
                label = stringResource(R.string.action_cache),
                onClick = { onMultiAppAction(MultiAppAction.ClearCache(selected)) }
            )
            ToolBoxItem(
                icon = R.drawable.share,
                label = stringResource(R.string.action_share),
                onClick = { onMultiAppAction(MultiAppAction.Share(selected)) }
            )
            ToolBoxItem(
                icon = R.drawable.delete_forever,
                label = stringResource(R.string.action_uninstall),
                onClick = { onMultiAppAction(MultiAppAction.Uninstall(selected)) }
            )
            ToolBoxItem(
                icon = R.drawable.danger,
                label = stringResource(R.string.action_kill),
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
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            maxLines = 1
        )
    }
}
