package com.valhalla.thor.ui.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.MultiAppAction

@Composable
fun MultiSelectToolBox(
    modifier: Modifier = Modifier,
    selected: List<AppInfo> = emptyList(),
    onCancel: () -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {
    var hasFrozen by remember { mutableStateOf(selected.any { it.enabled.not() }) }
    var hasUnFrozen by remember { mutableStateOf(selected.any { it.enabled }) }

    LaunchedEffect(selected) {
        hasFrozen = selected.any { it.enabled.not() }
        hasUnFrozen = selected.any { it.enabled }
    }

    ElevatedCard(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.Companion
                .padding(5.dp)
        ) {

            Row {
                if (!selected.first().isSystem) {
                    AppActionItem(
                        icon = R.drawable.apk_install,
                        text = "ReInstall",
                        onClick = {
                            onMultiAppAction(MultiAppAction.ReInstall(selected))
                        }
                    )
                    AppActionItem(
                        icon = R.drawable.delete_forever,
                        text = "Uninstall",
                        onClick = {
                            onMultiAppAction(MultiAppAction.Uninstall(selected))
                        }
                    )
                    if (hasUnFrozen)
                        AppActionItem(
                            icon = R.drawable.frozen,
                            text = "Freeze",
                        ) {
                            onMultiAppAction(MultiAppAction.Freeze(selected))
                        }
                    if (hasFrozen)
                        AppActionItem(
                            icon = R.drawable.unfreeze,
                            text = "UnFreeze",
                        ) {
                            onMultiAppAction(MultiAppAction.UnFreeze(selected))
                        }
                }
                AppActionItem(
                    icon = R.drawable.share,
                    text = "Share",
                    onClick = {
                        onMultiAppAction(MultiAppAction.Share(selected))
                    }
                )
                AppActionItem(
                    icon = R.drawable.danger,
                    text = "Kill Apps",
                    onClick = {
                        onMultiAppAction(MultiAppAction.Kill(selected))
                    }
                )
                AppActionItem(
                    icon = R.drawable.round_close,
                    text = "Close",
                    onClick = {
                        onCancel()
                    }
                )
            }

        }
    }
}