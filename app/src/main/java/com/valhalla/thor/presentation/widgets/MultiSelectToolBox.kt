package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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

    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(5.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            Row {
                // ReInstall (Root only)
                if (isRoot) {
                    AppActionItem(
                        icon = R.drawable.apk_install,
                        text = "ReInstall",
                        onClick = {
                            onMultiAppAction(MultiAppAction.ReInstall(selected))
                        }
                    )
                }

                // Freeze/Unfreeze (Root OR Shizuku)
                if (isRoot || isShizuku) {
                    if (hasUnFrozen) {
                        AppActionItem(
                            icon = R.drawable.frozen,
                            text = "Freeze",
                            onClick = {
                                onMultiAppAction(MultiAppAction.Freeze(selected))
                            }
                        )
                    }
                    if (hasFrozen) {
                        AppActionItem(
                            icon = R.drawable.unfreeze,
                            text = "UnFreeze",
                            onClick = {
                                onMultiAppAction(MultiAppAction.UnFreeze(selected))
                            }
                        )
                    }
                }

                // Standard Actions (Always available or handled logic downstream)
                AppActionItem(
                    icon = R.drawable.clear_all,
                    text = "Cache",
                    onClick = {
                        onMultiAppAction(MultiAppAction.ClearCache(selected))
                    }
                )
                AppActionItem(
                    icon = R.drawable.delete_forever,
                    text = "Uninstall",
                    onClick = {
                        onMultiAppAction(MultiAppAction.Uninstall(selected))
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