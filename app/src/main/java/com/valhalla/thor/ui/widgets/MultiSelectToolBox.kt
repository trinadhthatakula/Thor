package com.valhalla.thor.ui.widgets

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.valhalla.thor.R
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.MultiAppAction
import com.valhalla.thor.model.rootAvailable
import com.valhalla.thor.model.shizuku.ShizukuManager
import com.valhalla.thor.model.shizuku.ShizukuState
import org.koin.androidx.compose.koinViewModel

@Composable
fun MultiSelectToolBox(
    modifier: Modifier = Modifier,
    selected: List<AppInfo> = emptyList(),
    shizukuManager : ShizukuManager = koinViewModel(),
    onCancel: () -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {
    var hasFrozen by remember { mutableStateOf(selected.any { it.enabled.not() }) }
    var hasUnFrozen by remember { mutableStateOf(selected.any { it.enabled }) }

    val shizukuState by shizukuManager.shizukuState.collectAsStateWithLifecycle()

    LaunchedEffect(selected) {
        hasFrozen = selected.any { it.enabled.not() }
        hasUnFrozen = selected.any { it.enabled }
    }

    ElevatedCard(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .padding(5.dp)
                .horizontalScroll(rememberScrollState())
        ) {

            Row {
                if (rootAvailable()) {
                    AppActionItem(
                        icon = R.drawable.apk_install,
                        text = "ReInstall",
                        onClick = {
                            onMultiAppAction(MultiAppAction.ReInstall(selected))
                        }
                    )
                }
                if(rootAvailable() || shizukuState == ShizukuState.Ready) {
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