package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        onDismissRequest = onRejected,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(48.dp),
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    "Confirm",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onRejected) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        icon = {
            icon?.let {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(it),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        title = {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp
            )
        },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        onDismissRequest = onRejected,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(48.dp),
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    "Confirm",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onRejected) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        title = {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp
            )
        },
        text = {
            Text(
                text = when (multiAppAction) {
                    is MultiAppAction.ClearCache -> {
                        "This will clear Cache of ${multiAppAction.appList.size} apps. Proceed?"
                    }

                    is MultiAppAction.Freeze -> {
                        val activeAppsCount = multiAppAction.appList.count { it.enabled }
                        "$activeAppsCount of ${multiAppAction.appList.size} apps are active. Freeze them?"
                    }

                    is MultiAppAction.Kill -> {
                        "Force stop ${multiAppAction.appList.size} apps?"
                    }

                    is MultiAppAction.ReInstall -> {
                        "Reinstall ${multiAppAction.appList.size} apps with Google Play Store signature?"
                    }

                    is MultiAppAction.Share -> "Share ${multiAppAction.appList.size} apps?"
                    is MultiAppAction.UnFreeze -> {
                        val frozenAppsCount = multiAppAction.appList.count { !it.enabled }
                        "$frozenAppsCount of ${multiAppAction.appList.size} apps are frozen. Unfreeze them?"
                    }

                    is MultiAppAction.Uninstall -> "Uninstall ${multiAppAction.appList.size} apps?"

                    is MultiAppAction.ClearData -> "Permanently clear data for ${multiAppAction.appList.size} apps? This cannot be undone."

                    is MultiAppAction.Suspend -> "Suspend ${multiAppAction.appList.size} apps? This will restrict background activities."

                    is MultiAppAction.UnSuspend -> "Unsuspend ${multiAppAction.appList.size} apps?"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}
