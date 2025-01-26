package com.valhalla.thor.ui.widgets

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.valhalla.thor.model.AppInfo

@Composable
fun UninstallConfirmationDialog(
    appInfo: AppInfo,
    onDismiss: () -> Unit,
    onAppAction: (AppClickAction) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onAppAction(AppClickAction.Uninstall(appInfo))
                    onDismiss()
                }
            ) {
                Text("Yes")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("No")
            }
        },
        title = {
            Text("Uninstall ${appInfo.appName}?")
        },
        text = {
            Text("Are you sure you want to uninstall ${appInfo.appName}?")
        }
    )
}