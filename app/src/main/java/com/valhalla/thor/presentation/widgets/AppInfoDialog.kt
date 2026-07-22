// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.asgard.components.AsgardActionItem
import com.valhalla.asgard.components.StatusChip as AsgardStatusChip
import com.valhalla.thor.data.launcher.FreezerShortcutManager
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.domain.repository.PreferenceRepository
import org.koin.compose.koinInject
import androidx.compose.ui.platform.LocalContext
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
import com.valhalla.thor.presentation.utils.getBloatRecommendationColors

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
    // FIX: skipPartiallyExpanded = true prevents the "offset not initialized" crash
    // by avoiding ambiguous anchor calculations for dynamic content.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(
            SheetValue.Expanded, SheetValue.Hidden
        )
    )

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
            AppHeader(appInfo) {
                onAppAction(AppClickAction.AppInfoSettings(appInfo))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Action Buttons (Scrollable Row)
            AppActionRow(
                appInfo = appInfo,
                isRoot = isRoot,
                isShizuku = isShizuku,
                isDhizuku = isDhizuku,
                onExport = { showExportSheet = true },
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

                        is AppClickAction.Freeze -> {
                            if (appInfo.isSystem) showFreezeConfirmation = true
                            else {
                                onAppAction(action)
                                onDismiss()
                            }
                        }

                        is AppClickAction.Reinstall -> showReinstallWarning = true
                        is AppClickAction.ClearData -> showClearDataConfirmation = true
                        else -> {
                            onAppAction(action)
                            if (action is AppClickAction.Launch || action is AppClickAction.OpenDetails) onDismiss()
                        }
                    }
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
        val recommendation = appInfo.bloatRecommendation?.lowercase()
        val isUadFailed = appInfo.isSystem && appInfo.isUadLoadFailed
        val isUnsafe = recommendation == "unsafe"
        val isExpert = recommendation == "expert" && !isUadFailed
        val isBlocked = isUnsafe || isUadFailed
        AlertDialog(
            onDismissRequest = { showUninstallConfirmation = false },
            title = {
                Text(
                    text = when {
                        isBlocked -> stringResource(R.string.uninstall_blocked)
                        isExpert -> stringResource(R.string.uninstall_expert_warning)
                        else -> stringResource(R.string.uninstall_system_app_title)
                    },
                    color = if (isBlocked || isExpert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!isUadFailed) {
                        appInfo.bloatRecommendation?.let { rec ->
                            val (color, textColor) = getBloatRecommendationColors(rec)
                            StatusChip(
                                text = rec,
                                color = color,
                                textColor = textColor
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    if (isUadFailed) {
                        Text(
                            text = stringResource(R.string.uad_load_failed_desc),
                            textAlign = TextAlign.Center
                        )
                    } else if (isUnsafe) {
                        Text(
                            text = stringResource(R.string.warning_unsafe_uninstall),
                            textAlign = TextAlign.Center
                        )
                    } else if (isExpert) {
                        Text(
                            text = stringResource(R.string.warning_expert_uninstall),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.uninstall_system_app_desc),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            },
            confirmButton = {
                if (!isBlocked) {
                    TextButton(onClick = {
                        onAppAction(AppClickAction.Uninstall(appInfo))
                        showUninstallConfirmation = false
                        onDismiss()
                    }) {
                        Text(
                            text = if (isExpert) stringResource(R.string.uninstall_anyway) else stringResource(R.string.yes),
                            color = if (isExpert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUninstallConfirmation = false
                }) {
                    Text(if (isBlocked) stringResource(R.string.close) else stringResource(R.string.no))
                }
            }
        )
    }

    if (showFreezeConfirmation) {
        val recommendation = appInfo.bloatRecommendation?.lowercase()
        val isUadFailed = appInfo.isSystem && appInfo.isUadLoadFailed
        val isUnsafe = recommendation == "unsafe"
        val isExpert = recommendation == "expert" && !isUadFailed
        val isBlocked = isUnsafe || isUadFailed
        AlertDialog(
            onDismissRequest = { showFreezeConfirmation = false },
            title = {
                Text(
                    text = when {
                        isBlocked -> stringResource(R.string.freeze_blocked)
                        isExpert -> stringResource(R.string.freeze_expert_warning)
                        else -> stringResource(R.string.freeze_system_app_title)
                    },
                    color = if (isBlocked || isExpert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!isUadFailed) {
                        appInfo.bloatRecommendation?.let { rec ->
                            val (color, textColor) = getBloatRecommendationColors(rec)
                            StatusChip(
                                text = rec,
                                color = color,
                                textColor = textColor
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    if (isUadFailed) {
                        Text(
                            text = stringResource(R.string.uad_load_failed_freeze_desc),
                            textAlign = TextAlign.Center
                        )
                    } else if (isUnsafe) {
                        Text(
                            text = stringResource(R.string.freeze_unsafe_desc),
                            textAlign = TextAlign.Center
                        )
                    } else if (isExpert) {
                        Text(
                            text = stringResource(R.string.freeze_expert_desc),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.freeze_system_app_desc),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            },
            confirmButton = {
                if (!isBlocked) {
                    TextButton(onClick = {
                        onAppAction(AppClickAction.Freeze(appInfo))
                        showFreezeConfirmation = false
                        onDismiss()
                    }) {
                        Text(
                            text = if (isExpert) stringResource(R.string.freeze_anyway) else stringResource(R.string.yes),
                            color = if (isExpert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFreezeConfirmation = false
                }) {
                    Text(if (isBlocked) stringResource(R.string.close) else stringResource(R.string.no))
                }
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
private fun AppHeader(
    appInfo: AppInfo,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
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
                    stringResource(R.string.cd_settings),
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

@Composable
private fun AppActionRow(
    appInfo: AppInfo,
    isRoot: Boolean,
    isShizuku: Boolean,
    isDhizuku: Boolean,
    onExport: () -> Unit,
    onAction: (AppClickAction) -> Unit
) {
    val hasPrivilege = isRoot || isShizuku || isDhizuku
    val isFrozen = !appInfo.enabled
    val isSuspended = appInfo.isSuspended // Need to ensure this is in AppInfo

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top
    ) {
        // 1. Standard Actions
        ActionItem(R.drawable.open_in_new, stringResource(R.string.action_launch)) {
            onAction(
                AppClickAction.Launch(appInfo)
            )
        }

        // 2. Privileged Actions
        if (hasPrivilege) {
            val (freezeIcon, freezeLabel) = if (isFrozen) R.drawable.freeze_off to stringResource(R.string.action_unfreeze) else R.drawable.frozen to stringResource(
                R.string.action_freeze
            )
            ActionItem(freezeIcon, freezeLabel) {
                onAction(
                    if (isFrozen) AppClickAction.UnFreeze(appInfo) else AppClickAction.Freeze(
                        appInfo
                    )
                )
            }

            val (suspendIcon, suspendLabel) = if (isSuspended) R.drawable.bolt to stringResource(R.string.action_unsuspend) else R.drawable.warning to stringResource(
                R.string.action_suspend
            )
            ActionItem(suspendIcon, suspendLabel) {
                onAction(
                    if (isSuspended) AppClickAction.UnSuspend(appInfo) else AppClickAction.Suspend(
                        appInfo
                    )
                )
            }

            if (appInfo.enabled) {
                ActionItem(R.drawable.danger, stringResource(R.string.action_kill)) {
                    onAction(
                        AppClickAction.Kill(appInfo)
                    )
                }
            }

            ActionItem(R.drawable.clear_all, stringResource(R.string.action_cache)) {
                onAction(
                    AppClickAction.ClearCache(appInfo)
                )
            }
            ActionItem(R.drawable.delete, stringResource(R.string.action_data)) {
                onAction(
                    AppClickAction.ClearData(appInfo)
                )
            }
        }

        // 3. App Store Fix
        if (hasPrivilege && !appInfo.isSystem && appInfo.installerPackageName != "com.android.vending") {
            ActionItem(R.drawable.apk_install, stringResource(R.string.fix_store)) {
                onAction(AppClickAction.Reinstall(appInfo))
            }
        }

        // 4. Uninstall
        if (appInfo.packageName != "com.valhalla.thor") {
            ActionItem(R.drawable.delete_forever, stringResource(R.string.action_uninstall)) {
                onAction(AppClickAction.Uninstall(appInfo))
            }
        }

        ActionItem(R.drawable.share, stringResource(R.string.action_share)) {
            onAction(
                AppClickAction.Share(appInfo)
            )
        }

        ActionItem(R.drawable.storage, stringResource(R.string.action_export)) {
            onExport()
        }

        ActionItem(R.drawable.shield, stringResource(R.string.action_permissions)) {
            onAction(
                AppClickAction.ManagePermissions(appInfo)
            )
        }

        ActionItem(R.drawable.list_alt, stringResource(R.string.action_details)) {
            onAction(
                AppClickAction.OpenDetails(appInfo)
            )
        }

        // Freezer launcher shortcut — self-contained so it's available wherever this dialog shows
        // (app list, freezer, …). Gated on the feature setting + launcher pin support + user apps.
        val shortcutManager = koinInject<FreezerShortcutManager>()
        val preferenceRepository = koinInject<PreferenceRepository>()
        val prefs by preferenceRepository.userPreferences.collectAsStateWithLifecycle(UserPreferences())
        if (prefs.addFreezerToLauncher && !appInfo.isSystem && shortcutManager.isPinSupported()) {
            ActionItem(R.drawable.home, stringResource(R.string.add_to_home_screen)) {
                shortcutManager.pinAppShortcut(appInfo.packageName, appInfo.appName ?: appInfo.packageName)
            }
        }
    }
}

@Composable
private fun ActionItem(icon: Int, label: String, onClick: () -> Unit) {
    AsgardActionItem(
        icon = ImageVector.vectorResource(icon),
        label = label,
        onClick = onClick,
    )
}
