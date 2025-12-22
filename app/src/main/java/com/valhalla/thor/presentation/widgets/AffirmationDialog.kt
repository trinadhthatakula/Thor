package com.valhalla.thor.presentation.widgets

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.valhalla.thor.domain.model.MultiAppAction

@Composable
fun AffirmationDialog(
    modifier: Modifier = Modifier,
    title: String = "Are you sure?",
    text: String = "Some Message",
    icon: Int? = null,
    onConfirm: () -> Unit,
    onRejected: () -> Unit
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = {},
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                }
            ) {
                Text("Yes")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onRejected()
                }
            ) {
                Text("No")
            }
        },
        icon = {
            icon?.let {
                Icon(
                    painter = painterResource(it),
                    ""
                )
            }
        },
        title = {
            Text(title)
        },
        text = {
            Text(text)
        }
    )
}

@Composable
fun MultiAppAffirmationDialog(
    modifier: Modifier = Modifier,
    multiAppAction: MultiAppAction,
    title: String = "Are you sure?",
    onConfirm: () -> Unit,
    onRejected: () -> Unit
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = {},
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                }
            ) {
                Text("Yes")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onRejected()
                }
            ) {
                Text("No")
            }
        },
        title = {
            Text(title)
        },
        text = {
            Text(
                when (multiAppAction) {

                    is MultiAppAction.ClearCache -> {
                        val appCount = multiAppAction.appList.size - 1
                        "This will clear Cache of $appCount apps, Do you want to continue?"
                    }

                    is MultiAppAction.Freeze -> {
                        val activeAppsCount = multiAppAction.appList.count { it.enabled }
                        "$activeAppsCount of ${multiAppAction.appList.size} apps are active, Do you want to freeze them?"
                    }

                    is MultiAppAction.Kill -> {
                        "You want to kill ${multiAppAction.appList.size} apps?"
                    }

                    is MultiAppAction.ReInstall -> {
                        val otherApps =
                            multiAppAction.appList.filter { it.installerPackageName != "com.android.vending" }
                        "${otherApps.size} of ${multiAppAction.appList.size} are not installed from Play store, you want to reinstall them with Google Play store?"
                    }

                    is MultiAppAction.Share -> "You want to share ${multiAppAction.appList.size} apps?"
                    is MultiAppAction.UnFreeze -> {
                        val frozenAppsCount = multiAppAction.appList.count { it.enabled.not() }
                        "$frozenAppsCount of ${multiAppAction.appList.size} apps are frozen, Do you want to un freeze them?"
                    }

                    is MultiAppAction.Uninstall -> "You want to Uninstall ${multiAppAction.appList.size} apps?"
                })
        }
    )
}