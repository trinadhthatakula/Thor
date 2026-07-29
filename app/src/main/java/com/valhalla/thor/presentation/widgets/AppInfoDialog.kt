// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.valhalla.asgard.components.StatusChip as AsgardStatusChip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.presentation.appList.ExportBottomSheet
import com.valhalla.thor.presentation.utils.AppIconModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoDialog(
    appInfo: AppInfo,
    isRoot: Boolean = false,
    isShizuku: Boolean = false,
    isDhizuku: Boolean = false,
    onDismiss: () -> Unit,
    onAppAction: (AppClickAction) -> Unit = {}
) {
    // Default enabledValues = {Hidden, PartiallyExpanded, Expanded}. The sheet now opens at the
    // partial detent, which material3 pins at min(windowHeight / 2, contentHeight) — there is no
    // peek parameter, so whether the action row survives above the fold is a measurement, not a
    // setting. That measurement is the point of this change.
    //
    // The previous `enabledValues = {Expanded, Hidden}` carried a comment blaming
    // `skipPartiallyExpanded` for an "offset not initialized" crash. That parameter is not in this
    // file (it belonged to the deprecated `rememberModalBottomSheetState`, which pins the
    // deterministic-anchor flag off); the note predates the migration and no longer describes
    // anything here.
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    var showUninstallConfirmation by remember { mutableStateOf(false) }
    var showReinstallWarning by remember { mutableStateOf(false) }
    var showClearDataConfirmation by remember { mutableStateOf(false) }
    var showFreezeConfirmation by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp), // Add bottom padding for nav bar
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Header (Icon + Title)
            AppHeader(appInfo)

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Action Buttons (Scrollable Row)
            AppActionRow(
                appInfo = appInfo,
                isRoot = isRoot,
                isShizuku = isShizuku,
                isDhizuku = isDhizuku,
                onLaunch = {
                    onAppAction(AppClickAction.Launch(appInfo))
                    onDismiss()
                },
                onSystemSettings = { onAppAction(AppClickAction.AppInfoSettings(appInfo)) },
                onFreezeToggle = { shouldFreeze ->
                    // Only SYSTEM apps get the safety-warning dialog; unfreezing and user apps
                    // go straight through.
                    if (shouldFreeze && appInfo.isSystem) {
                        showFreezeConfirmation = true
                    } else {
                        onAppAction(
                            if (shouldFreeze) AppClickAction.Freeze(appInfo)
                            else AppClickAction.UnFreeze(appInfo)
                        )
                        onDismiss()
                    }
                },
                onSuspendToggle = { shouldSuspend ->
                    onAppAction(
                        if (shouldSuspend) AppClickAction.Suspend(appInfo)
                        else AppClickAction.UnSuspend(appInfo)
                    )
                },
                onForceStop = { onAppAction(AppClickAction.Kill(appInfo)) },
                onManagePermissions = { onAppAction(AppClickAction.ManagePermissions(appInfo)) },
                onClearCache = { onAppAction(AppClickAction.ClearCache(appInfo)) },
                onClearData = { showClearDataConfirmation = true },
                onFixStore = { showReinstallWarning = true },
                onUninstall = {
                    if (appInfo.isSystem) showUninstallConfirmation = true
                    else {
                        onAppAction(AppClickAction.Uninstall(appInfo))
                        onDismiss()
                    }
                },
                onShare = { onAppAction(AppClickAction.Share(appInfo)) },
                onExport = { showExportSheet = true },
                onOpenDetails = {
                    onAppAction(AppClickAction.OpenDetails(appInfo))
                    onDismiss()
                }
            )
        }
    }

    // --- OVERLAYS ---

    if (showExportSheet) {
        ExportBottomSheet(appInfo = appInfo, onDismiss = { showExportSheet = false })
    }

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
            title = { Text(stringResource(R.string.clear_app_data_title)) },
            text = { Text(stringResource(R.string.clear_app_data_desc, appInfo.appName ?: "")) },
            confirmButton = {
                TextButton(onClick = {
                    onAppAction(AppClickAction.ClearData(appInfo))
                    showClearDataConfirmation = false
                    onDismiss()
                }) { Text(stringResource(R.string.clear_all_data)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClearDataConfirmation = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showUninstallConfirmation) {
        AppRiskDialog(
            app = appInfo,
            action = AppRiskAction.Uninstall,
            onConfirm = {
                onAppAction(AppClickAction.Uninstall(appInfo))
                showUninstallConfirmation = false
                onDismiss()
            },
            onDismiss = { showUninstallConfirmation = false }
        )
    }

    if (showFreezeConfirmation) {
        AppRiskDialog(
            app = appInfo,
            action = AppRiskAction.Freeze,
            onConfirm = {
                onAppAction(AppClickAction.Freeze(appInfo))
                showFreezeConfirmation = false
                onDismiss()
            },
            onDismiss = { showFreezeConfirmation = false }
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
            title = { Text(stringResource(R.string.risk_warning_title)) },
            text = {
                Text(stringResource(R.string.risk_warning_desc))
            },
            confirmButton = {
                TextButton(onClick = {
                    onAppAction(AppClickAction.Reinstall(appInfo))
                    showReinstallWarning = false
                    onDismiss()
                }) { Text(stringResource(R.string.proceed)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showReinstallWarning = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun AppHeader(appInfo: AppInfo) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        // Icon with a nice background
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = AppIconModel(appInfo.packageName),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(24.dp))

        // Title
        Text(
            text = appInfo.appName ?: stringResource(R.string.unknown),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            letterSpacing = (-1).sp,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(8.dp))

        // Metadata Chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (appInfo.splitPublicSourceDirs.isNotEmpty()) {
                StatusChip(
                    text = stringResource(R.string.status_split),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                )
            }
            if (!appInfo.enabled) {
                StatusChip(
                    text = stringResource(R.string.status_frozen),
                    color = MaterialTheme.colorScheme.errorContainer
                )
            }
            if (appInfo.isSuspended) {
                StatusChip(
                    text = stringResource(R.string.status_suspended),
                    color = MaterialTheme.colorScheme.secondaryContainer
                )
            }
            appInfo.bloatRecommendation?.let { recommendation ->
                val (color, textColor) = when (recommendation.lowercase()) {
                    "recommended" -> Color(0xFFC8E6C9) to Color(0xFF1B5E20)
                    "advanced" -> Color(0xFFFFF9C4) to Color(0xFFF57F17)
                    "expert" -> Color(0xFFFFE0B2) to Color(0xFFE65100)
                    "unsafe" -> Color(0xFFFFCDD2) to Color(0xFFB71C1C)
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
                }
                StatusChip(
                    text = recommendation,
                    color = color,
                    textColor = textColor
                )
            }
            StatusChip(
                text = stringResource(R.string.version_format, appInfo.versionName ?: ""),
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

        // UAD Description skipped by user request
    }
}

@Composable
private fun StatusChip(
    text: String,
    color: Color,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    AsgardStatusChip(text = text, containerColor = color, contentColor = textColor)
}

