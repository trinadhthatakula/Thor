package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.presentation.utils.getAppIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoDialog(
    appInfo: AppInfo,
    isRoot: Boolean = false,
    isShizuku: Boolean = false,
    onDismiss: () -> Unit,
    onAppAction: (AppClickAction) -> Unit = {}
) {
    // FIX: skipPartiallyExpanded = true prevents the "offset not initialized" crash
    // by avoiding ambiguous anchor calculations for dynamic content.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showUninstallConfirmation by remember { mutableStateOf(false) }
    var showReinstallWarning by remember { mutableStateOf(false) }
    var showClearDataConfirmation by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp), // Add bottom padding for nav bar
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Header (Icon + Title)
            AppHeader(appInfo) {
                onAppAction(AppClickAction.AppInfoSettings(appInfo))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Action Buttons (Scrollable Row)
            AppActionRow(
                appInfo = appInfo,
                isRoot = isRoot,
                isShizuku = isShizuku,
                onAction = { action ->
                    // Intercept dangerous actions for local confirmation
                    when (action) {
                        is AppClickAction.Uninstall -> {
                            if (appInfo.isSystem) showUninstallConfirmation = true
                            else {
                                onAppAction(action)
                                onDismiss()
                            }
                        }

                        is AppClickAction.Reinstall -> showReinstallWarning = true
                        is AppClickAction.ClearData -> showClearDataConfirmation = true
                        else -> {
                            onAppAction(action)
                            if (action is AppClickAction.Launch) onDismiss()
                        }
                    }
                }
            )
        }
    }

    // --- ALERTS ---

    if (showClearDataConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearDataConfirmation = false },
            icon = {
                Icon(
                    painterResource(R.drawable.danger),
                    null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Clear App Data?") },
            text = { Text("This will permanently delete all data for ${appInfo.appName}. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onAppAction(AppClickAction.ClearData(appInfo))
                    showClearDataConfirmation = false
                    onDismiss()
                }) { Text("Clear All Data") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataConfirmation = false }) { Text("Cancel") }
            }
        )
    }

    if (showUninstallConfirmation) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirmation = false },
            title = { Text("Uninstall System App?") },
            text = { Text("This allows you to uninstall updates or factory reset this system app. Proceed?") },
            confirmButton = {
                TextButton(onClick = {
                    onAppAction(AppClickAction.Uninstall(appInfo))
                    showUninstallConfirmation = false
                    onDismiss()
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirmation = false }) { Text("No") }
            }
        )
    }

    if (showReinstallWarning) {
        AlertDialog(
            icon = {
                Icon(
                    painterResource(R.drawable.warning),
                    null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            onDismissRequest = { showReinstallWarning = false },
            title = { Text("Risk Warning") },
            text = {
                Text("This forces the installer record to 'Google Play Store'.\n\nUpdates may fail if the signature doesn't match the official store version.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onAppAction(AppClickAction.Reinstall(appInfo))
                    showReinstallWarning = false
                    onDismiss()
                }) { Text("Proceed") }
            },
            dismissButton = {
                TextButton(onClick = { showReinstallWarning = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AppHeader(
    appInfo: AppInfo,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
    ) {
        // Top row with settings and close? Or just settings.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Icon(
                    painterResource(R.drawable.settings), 
                    "Settings",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Icon with a nice background
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = rememberAsyncImagePainter(getAppIcon(appInfo.packageName, context)),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(24.dp))

        // Title
        Text(
            text = appInfo.appName ?: "Unknown",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            letterSpacing = (-1).sp
        )

        Spacer(Modifier.height(8.dp))

        // Metadata Chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (appInfo.splitPublicSourceDirs.isNotEmpty()) {
                StatusChip(text = "SPLIT", color = MaterialTheme.colorScheme.tertiaryContainer)
            }
            if (!appInfo.enabled) {
                StatusChip(text = "FROZEN", color = MaterialTheme.colorScheme.errorContainer)
            }
            StatusChip(
                text = "v${appInfo.versionName}", 
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))

        // Package Name
        Text(
            text = appInfo.packageName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = com.valhalla.thor.presentation.theme.firaMonoFontFamily
        )
    }
}

@Composable
private fun StatusChip(
    text: String, 
    color: Color,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(CircleShape)
            .background(color)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = textColor
    )
}

@Composable
private fun AppActionRow(
    appInfo: AppInfo,
    isRoot: Boolean,
    isShizuku: Boolean,
    onAction: (AppClickAction) -> Unit
) {
    val hasPrivilege = isRoot || isShizuku
    val isFrozen = !appInfo.enabled
    val isSuspended = appInfo.isSuspended // Need to ensure this is in AppInfo

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Standard Actions
        ActionItem(R.drawable.open_in_new, "Launch") { onAction(AppClickAction.Launch(appInfo)) }
        ActionItem(R.drawable.share, "Share") { onAction(AppClickAction.Share(appInfo)) }

        // 2. Privileged Actions
        if (hasPrivilege) {
            val (freezeIcon, freezeLabel) = if (isFrozen) R.drawable.freeze_off to "Unfreeze" else R.drawable.frozen to "Freeze"
            ActionItem(freezeIcon, freezeLabel) {
                onAction(if (isFrozen) AppClickAction.UnFreeze(appInfo) else AppClickAction.Freeze(appInfo))
            }

            val (suspendIcon, suspendLabel) = if (isSuspended) R.drawable.bolt to "Unsuspend" else R.drawable.warning to "Suspend"
            ActionItem(suspendIcon, suspendLabel) {
                onAction(if (isSuspended) AppClickAction.UnSuspend(appInfo) else AppClickAction.Suspend(appInfo))
            }

            if (appInfo.enabled) {
                ActionItem(R.drawable.danger, "Kill") { onAction(AppClickAction.Kill(appInfo)) }
            }

            ActionItem(R.drawable.clear_all, "Cache") { onAction(AppClickAction.ClearCache(appInfo)) }
            ActionItem(R.drawable.delete, "Data") { onAction(AppClickAction.ClearData(appInfo)) }
        }

        // 3. App Store Fix
        if (hasPrivilege && !appInfo.isSystem && appInfo.installerPackageName != "com.android.vending") {
            ActionItem(R.drawable.apk_install, "Fix Store") {
                onAction(AppClickAction.Reinstall(appInfo))
            }
        }

        // 4. Uninstall
        if (appInfo.packageName != "com.valhalla.thor") {
            ActionItem(R.drawable.delete_forever, "Uninstall") {
                onAction(AppClickAction.Uninstall(appInfo))
            }
        }
    }
}

@Composable
private fun ActionItem(icon: Int, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        // Use a Tonal Button style for better touch targets
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
