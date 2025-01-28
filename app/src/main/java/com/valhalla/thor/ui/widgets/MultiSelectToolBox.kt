package com.valhalla.thor.ui.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.MultiAppAction

@Composable
fun MultiSelectToolBox(
    modifier: Modifier = Modifier.Companion,
    selected: List<AppInfo> = emptyList(),
    onCancel: () -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {
    ElevatedCard(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.Companion
                .padding(5.dp)
        ) {

            Row {
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
                AppActionItem(
                    icon = R.drawable.share,
                    text = "Share",
                    onClick = {
                        onMultiAppAction(MultiAppAction.Share(selected))
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