package com.valhalla.thor.presentation.freezer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valhalla.thor.R
import com.valhalla.thor.presentation.common.components.ConnectedButtonGroup
import com.valhalla.thor.presentation.common.components.ConnectedButtonGroupItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezerSettingsSheet(
    isGrid: Boolean,
    autoFreezeEnabled: Boolean,
    hasPrivilege: Boolean,
    onToggleView: () -> Unit,
    onToggleAutoFreeze: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onUnfreezeAll: () -> Unit
) {
    var showUnfreezeConfirmation by remember { mutableStateOf(false) }

    if (showUnfreezeConfirmation) {
        AlertDialog(
            onDismissRequest = { showUnfreezeConfirmation = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Unfreeze All Apps?") },
            text = { Text("This will unfreeze (enable) all apps currently in the Freezer list. Are you sure you want to proceed?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUnfreezeAll()
                        showUnfreezeConfirmation = false
                        onDismiss()
                    }
                ) {
                    Text("Proceed")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnfreezeConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Freezer Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                letterSpacing = (-1).sp
            )
            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.view_mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                ConnectedButtonGroup(
                    items = listOf(
                        ConnectedButtonGroupItem.Icon(
                            R.drawable.grid_view,
                            stringResource(R.string.grid)
                        ),
                        ConnectedButtonGroupItem.Icon(
                            R.drawable.view_stream,
                            stringResource(R.string.list)
                        )
                    ),
                    selectedIndex = if (isGrid) 0 else 1,
                    onItemSelected = { onToggleView() }
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    showUnfreezeConfirmation = true
                },
                enabled = hasPrivilege,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Unfreeze All")
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto Freeze",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        text = if (hasPrivilege) "Freeze apps automatically when screen is locked" else "Privilege required (Root, Shizuku or Dhizuku)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasPrivilege) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f) else MaterialTheme.colorScheme.error
                    )
                }
                Switch(
                    checked = autoFreezeEnabled,
                    onCheckedChange = onToggleAutoFreeze,
                    enabled = hasPrivilege
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
