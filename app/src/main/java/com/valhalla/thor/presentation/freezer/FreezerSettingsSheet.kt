package com.valhalla.thor.presentation.freezer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valhalla.thor.R
import com.valhalla.thor.data.launcher.FreezerShortcutContract
import com.valhalla.thor.domain.model.AppListType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.valhalla.asgard.components.AsgardActionItem
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezerSettingsSheet(
    isGrid: Boolean,
    autoFreezeEnabled: Boolean,
    hasPrivilege: Boolean,
    showImportDisabledApps: Boolean,
    appListType: AppListType,
    showLauncherPinActions: Boolean = false,
    onToggleView: () -> Unit,
    onToggleAutoFreeze: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onUnfreezeAll: () -> Unit,
    onImportDisabledApps: () -> Unit,
    onListTypeChanged: (AppListType) -> Unit,
    onPinAllToLauncher: () -> Unit = {},
    onPinFreezeAllShortcut: () -> Unit = {},
    onPinUnfreezeAllShortcut: () -> Unit = {},
    pinAllCount: Int = 0
) {
    var showUnfreezeConfirmation by remember { mutableStateOf(false) }
    var showPinAllConfirmation by remember { mutableStateOf(false) }

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
            title = { Text(stringResource(R.string.unfreeze_all_confirmation_title)) },
            text = { Text(stringResource(R.string.unfreeze_all_confirmation_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUnfreezeAll()
                        showUnfreezeConfirmation = false
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.proceed))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnfreezeConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showPinAllConfirmation) {
        AlertDialog(
            onDismissRequest = { showPinAllConfirmation = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.home),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.pin_all_confirm_title)) },
            text = { Text(stringResource(R.string.pin_all_confirm_desc, pinAllCount)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onPinAllToLauncher()
                        showPinAllConfirmation = false
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinAllConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
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
                text = stringResource(R.string.freezer_settings),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                letterSpacing = (-1).sp
            )
            Spacer(Modifier.height(24.dp))

            // 1. App Type Selector (User vs System)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.apps),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                ConnectedButtonGroup(
                    items = AppListType.entries.map { type ->
                        ConnectedButtonGroupItem.Icon(
                            icon = ImageVector.vectorResource(if (type == AppListType.USER) R.drawable.apps else R.drawable.android),
                            contentDescription = stringResource(
                                if (type == AppListType.USER) R.string.chip_user else R.string.chip_system
                            )
                        )
                    },
                    selectedIndex = AppListType.entries.indexOf(appListType),
                    onItemSelected = { onListTypeChanged(AppListType.entries[it]) },
                    modifier = Modifier.width(IntrinsicSize.Max)
                )
            }

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
                            ImageVector.vectorResource(R.drawable.grid_view),
                            stringResource(R.string.grid)
                        ),
                        ConnectedButtonGroupItem.Icon(
                            ImageVector.vectorResource(R.drawable.view_stream),
                            stringResource(R.string.list)
                        )
                    ),
                    selectedIndex = if (isGrid) 0 else 1,
                    onItemSelected = { onToggleView() },
                    modifier = Modifier.width(IntrinsicSize.Max)
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
                Text(stringResource(R.string.unfreeze_all))
            }

            if (showImportDisabledApps) {
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        onImportDisabledApps()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.import_disabled_apps_button))
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.auto_freeze),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        text = if (hasPrivilege) stringResource(R.string.auto_freeze_desc) else stringResource(
                            R.string.privilege_required_warning
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasPrivilege) MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = 0.8f
                        ) else MaterialTheme.colorScheme.error
                    )
                }
                Switch(
                    checked = autoFreezeEnabled,
                    onCheckedChange = onToggleAutoFreeze,
                    enabled = hasPrivilege
                )
            }

            // Launcher-shortcut actions — a dedicated "Shortcuts" section, shown only when the
            // launcher integration is enabled and the current launcher supports pinning. Rendered as
            // action-item rows (matching the app-info dialog) rather than full-width buttons.
            if (showLauncherPinActions) {
                Spacer(Modifier.height(24.dp))

                Text(
                    stringResource(R.string.shortcuts),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.Top
                ) {
                    ShortcutActionItem(
                        icon = R.drawable.frozen,
                        label = stringResource(R.string.freeze_all_apps),
                        tileColor = Color(FreezerShortcutContract.FREEZE_TILE_COLOR),
                        onClick = onPinFreezeAllShortcut
                    )
                    ShortcutActionItem(
                        icon = R.drawable.unfreeze,
                        label = stringResource(R.string.unfreeze_all_apps),
                        tileColor = Color(FreezerShortcutContract.UNFREEZE_TILE_COLOR),
                        onClick = onPinUnfreezeAllShortcut
                    )
                    ShortcutActionItem(
                        icon = R.drawable.home,
                        label = stringResource(R.string.shortcut_add_all),
                        tileColor = Color(0xFF607D8B),
                        onClick = { showPinAllConfirmation = true }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ShortcutActionItem(icon: Int, label: String, tileColor: Color, onClick: () -> Unit) {
    // Preview the actual pinned-shortcut tile: white glyph on the same coloured background.
    AsgardActionItem(
        icon = ImageVector.vectorResource(icon),
        label = label,
        onClick = onClick,
        containerColor = tileColor,
        iconTint = Color.White,
    )
}
