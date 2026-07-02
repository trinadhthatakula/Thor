package com.valhalla.thor.presentation.appList

import android.content.Context
import android.icu.text.DateFormat
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.model.PermissionDetail
import com.valhalla.thor.presentation.theme.bodyFontFamily
import com.valhalla.thor.presentation.theme.firaMonoFontFamily
import com.valhalla.thor.presentation.utils.AppIconModel
import com.valhalla.thor.presentation.utils.getBloatRecommendationColors
import com.valhalla.thor.presentation.widgets.AnimateLottieRaw
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import androidx.compose.ui.platform.ClipEntry
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppInfoDetailsScreen(
    packageName: String,
    appName: String?,
    viewModel: AppInfoDetailsViewModel = koinViewModel(),
    sharedTransitionScope: SharedTransitionScope? = null,
    onBack: () -> Unit,
    onNavigateToPermissionManager: (packageName: String, appName: String) -> Unit,
    onAppAction: (AppClickAction) -> Unit,
    showOnlyHeaderAndActions: Boolean = false,
    showOnlyTabs: Boolean = false
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(packageName) {
        viewModel.loadAppDetails(packageName)
    }

    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { msg ->
            Toast.makeText(context, msg.asString(context), Toast.LENGTH_SHORT).show()
            viewModel.dismissMessage()
        }
    }

    var showClearDataConfirmation by remember { mutableStateOf(false) }
    var showUninstallConfirmation by remember { mutableStateOf(false) }
    var showFreezeConfirmation by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (!showOnlyHeaderAndActions && !showOnlyTabs) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_downward),
                            contentDescription = stringResource(R.string.cd_close)
                        )
                    }
                    Text(
                        text = stringResource(R.string.app_details_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            when {
                state.isLoading && state.detailedInfo == null -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Using a generic error icon since R.raw.error_state might not exist
                        Icon(
                            painter = painterResource(R.drawable.danger),
                            contentDescription = null,
                            modifier = Modifier.size(120.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = state.errorMessage?.asString(context) ?: stringResource(R.string.unknown_error_occurred),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { viewModel.loadAppDetails(packageName) }) {
                            Text(stringResource(R.string.retry_label))
                        }
                    }
                }

                state.detailedInfo != null -> {
                    val details = state.detailedInfo!!
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!showOnlyTabs) {
                            AppDetailsHeader(
                                appInfo = details.appInfo,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = null
                            )

                            AppDetailsActionRow(
                                appInfo = details.appInfo,
                                isRoot = state.isRoot,
                                isShizuku = state.isShizuku,
                                isDhizuku = state.isDhizuku,
                                isInFreezer = state.isInFreezer,
                                onLaunch = {
                                    onAppAction(AppClickAction.Launch(details.appInfo))
                                },
                                onSystemSettings = {
                                    onAppAction(AppClickAction.AppInfoSettings(details.appInfo))
                                },
                                onFreezeToggle = { shouldFreeze ->
                                    // Honor the requested target: unfreeze immediately,
                                    // only show the warning dialog when freezing.
                                    if (shouldFreeze) {
                                        showFreezeConfirmation = true
                                    } else {
                                        viewModel.toggleFreezerState(
                                            packageName,
                                            details.appInfo.appName,
                                            false
                                        )
                                    }
                                },
                                onSuspendToggle = { shouldSuspend ->
                                    if (shouldSuspend) onAppAction(AppClickAction.Suspend(details.appInfo))
                                    else onAppAction(AppClickAction.UnSuspend(details.appInfo))
                                },
                                onForceStop = {
                                    onAppAction(AppClickAction.Kill(details.appInfo))
                                },
                                onManagePermissions = {
                                    onNavigateToPermissionManager(packageName, details.appInfo.appName ?: "")
                                },
                                onToggleFreezerMembership = {
                                    viewModel.addOrRemoveFromFreezer(packageName)
                                },
                                onClearCache = {
                                    onAppAction(AppClickAction.ClearCache(details.appInfo))
                                },
                                onClearData = { showClearDataConfirmation = true },
                                onUninstall = { showUninstallConfirmation = true },
                                onShare = {
                                    onAppAction(AppClickAction.Share(details.appInfo))
                                },
                                onExport = { showExportSheet = true }
                            )
                        }

                        if (!showOnlyHeaderAndActions) {
                            var selectedTab by remember { mutableIntStateOf(0) }
                            val tabs = listOf(
                                stringResource(R.string.tab_overview_title),
                                stringResource(R.string.tab_components),
                                stringResource(R.string.tab_libs_features),
                                stringResource(R.string.action_permissions)
                            )

                            TabRow(
                                selectedTabIndex = selectedTab,
                                containerColor = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.primary,
                                indicator = { tabPositions ->
                                    TabRowDefaults.SecondaryIndicator(
                                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                divider = {}
                            ) {
                                tabs.forEachIndexed { index, title ->
                                    Tab(
                                        selected = selectedTab == index,
                                        onClick = { selectedTab = index },
                                        text = {
                                            Text(
                                                text = title,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium
                                            )
                                        }
                                    )
                                }
                            }

                            when (selectedTab) {
                                0 -> GeneralTabScreen(details)
                                1 -> ComponentsTabScreen(details)
                                2 -> LibsAndFeaturesTabScreen(details)
                                3 -> PermissionsTabScreen(details.permissions)
                            }
                        }
                    }
                }
            }
        }
    }

    // --- DIALOGS ---

    if (showClearDataConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearDataConfirmation = false },
            title = { Text(stringResource(R.string.clear_app_data_title)) },
            text = { Text(stringResource(R.string.dialog_clear_data_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearData(packageName)
                    showClearDataConfirmation = false
                }) {
                    Text(
                        stringResource(R.string.action_clear_data),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showUninstallConfirmation) {
        val appInfo = state.detailedInfo?.appInfo
        if (appInfo != null) {
            val recommendation = appInfo.bloatRecommendation?.lowercase()
            val isSystem = appInfo.isSystem
            val isUadFailed = isSystem && appInfo.isUadLoadFailed
            val isUnsafe = isSystem && recommendation == "unsafe"
            val isExpert = isSystem && recommendation == "expert" && !isUadFailed
            val isBlocked = isUnsafe || isUadFailed
            AlertDialog(
                onDismissRequest = { showUninstallConfirmation = false },
                title = {
                    Text(
                        text = when {
                            isBlocked -> stringResource(R.string.uninstall_blocked)
                            isExpert -> stringResource(R.string.uninstall_expert_warning)
                            isSystem -> stringResource(R.string.uninstall_system_app_title)
                            else -> stringResource(R.string.uninstall_app_title)
                        },
                        color = if (isBlocked || isExpert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (isSystem && !isUadFailed) {
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
                        } else if (isSystem) {
                            Text(
                                text = stringResource(R.string.uninstall_system_app_desc),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Text(
                                text = stringResource(
                                    R.string.uninstall_app_desc,
                                    appInfo.appName ?: packageName
                                ),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                },
                confirmButton = {
                    if (!isBlocked) {
                        TextButton(onClick = {
                            if (isSystem) {
                                onAppAction(AppClickAction.Uninstall(appInfo))
                            } else {
                                val intent =
                                    android.content.Intent(android.content.Intent.ACTION_DELETE).apply {
                                        data = android.net.Uri.parse("package:$packageName")
                                    }
                                context.startActivity(intent)
                            }
                            showUninstallConfirmation = false
                            // Close the details screen once uninstall is triggered
                            onBack()
                        }) {
                            Text(
                                text = if (isExpert) stringResource(R.string.uninstall_anyway) else if (isSystem) stringResource(R.string.yes) else stringResource(R.string.action_uninstall),
                                color = if (isExpert || !isSystem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showUninstallConfirmation = false
                    }) {
                        Text(if (isBlocked) stringResource(R.string.close) else if (isSystem) stringResource(R.string.no) else stringResource(R.string.cancel))
                    }
                }
            )
        }
    }

    if (showFreezeConfirmation) {
        val appInfo = state.detailedInfo?.appInfo
        if (appInfo != null) {
            val recommendation = appInfo.bloatRecommendation?.lowercase()
            val isSystem = appInfo.isSystem
            val isUadFailed = isSystem && appInfo.isUadLoadFailed
            val isUnsafe = isSystem && recommendation == "unsafe"
            val isExpert = isSystem && recommendation == "expert" && !isUadFailed
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
                        if (isSystem && !isUadFailed) {
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
                            viewModel.toggleFreezerState(packageName, appInfo.appName, true)
                            showFreezeConfirmation = false
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
    }

    if (showExportSheet) {
        state.detailedInfo?.appInfo?.let { appInfo ->
            ExportBottomSheet(appInfo = appInfo, onDismiss = { showExportSheet = false })
        }
    }
}

@Composable
private fun AppDetailsHeader(
    appInfo: AppInfo,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState(key = "icon-${appInfo.packageName}"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                }
            } else {
                Modifier
            }
            AsyncImage(
                model = AppIconModel(appInfo.packageName),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(sharedModifier)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            val textSharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "name-${appInfo.packageName}"),
                        animatedVisibilityScope = animatedVisibilityScope
                    ).skipToLookaheadSize()
                }
            } else {
                Modifier
            }
            Text(
                text = appInfo.appName ?: stringResource(R.string.unknown),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.then(textSharedModifier)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = appInfo.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = firaMonoFontFamily
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                if (appInfo.isSystem) {
                    StatusChip(
                        text = stringResource(R.string.chip_system),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        textColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                } else {
                    StatusChip(
                        text = stringResource(R.string.chip_user),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        textColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                if (!appInfo.enabled) {
                    StatusChip(
                        text = stringResource(R.string.frozen),
                        color = MaterialTheme.colorScheme.errorContainer
                    )
                }

                if (appInfo.isSuspended) {
                    StatusChip(
                        text = stringResource(R.string.suspended),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    )
                }

                if (appInfo.isDebuggable) {
                    StatusChip(
                        text = stringResource(R.string.chip_debug),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }

                appInfo.bloatRecommendation?.let { recommendation ->
                    val (chipColor, chipTextColor) = getBloatRecommendationColors(recommendation)
                    StatusChip(
                        text = recommendation,
                        color = chipColor,
                        textColor = chipTextColor
                    )
                }
                StatusChip(
                    text = stringResource(R.string.version_format, appInfo.versionName ?: ""),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = textColor
    )
}

@Composable
private fun AppDetailsActionRow(
    appInfo: AppInfo,
    isRoot: Boolean,
    isShizuku: Boolean,
    isDhizuku: Boolean,
    isInFreezer: Boolean,
    onLaunch: () -> Unit,
    onSystemSettings: () -> Unit,
    onFreezeToggle: (Boolean) -> Unit,
    onSuspendToggle: (Boolean) -> Unit,
    onForceStop: () -> Unit,
    onManagePermissions: () -> Unit,
    onToggleFreezerMembership: () -> Unit,
    onClearCache: () -> Unit,
    onClearData: () -> Unit,
    onUninstall: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit
) {
    val hasPrivilege = isRoot || isShizuku || isDhizuku
    val isFrozen = !appInfo.enabled
    val isSuspended = appInfo.isSuspended

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionItem(
            icon = R.drawable.open_in_new,
            label = stringResource(R.string.action_open),
            enabled = appInfo.enabled,
            onClick = onLaunch
        )

        ActionItem(
            icon = R.drawable.settings,
            label = stringResource(R.string.settings),
            onClick = onSystemSettings
        )

        if (hasPrivilege) {
            val freezeLabel =
                if (isFrozen) stringResource(R.string.action_unfreeze) else stringResource(R.string.action_freeze)
            val freezeIcon = if (isFrozen) R.drawable.freeze_off else R.drawable.frozen
            ActionItem(
                icon = freezeIcon,
                label = freezeLabel,
                onClick = { onFreezeToggle(!isFrozen) }
            )

            val suspendLabel =
                if (isSuspended) stringResource(R.string.action_unsuspend) else stringResource(R.string.action_suspend)
            val suspendIcon = if (isSuspended) R.drawable.bolt else R.drawable.warning
            ActionItem(
                icon = suspendIcon,
                label = suspendLabel,
                onClick = { onSuspendToggle(!isSuspended) }
            )

            if (appInfo.enabled) {
                ActionItem(
                    icon = R.drawable.force_close,
                    label = stringResource(R.string.action_force_stop),
                    onClick = onForceStop
                )
            }
        }

        ActionItem(
            icon = R.drawable.shield,
            label = stringResource(R.string.action_permissions),
            onClick = onManagePermissions
        )

        val freezerLabel =
            if (isInFreezer) stringResource(R.string.action_in_freezer) else stringResource(R.string.action_add_freezer)
        ActionItem(
            icon = R.drawable.snowflake,
            label = freezerLabel,
            tintColor = if (isInFreezer) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            onClick = onToggleFreezerMembership
        )

        if (hasPrivilege) {
            ActionItem(
                icon = R.drawable.clear_all,
                label = stringResource(R.string.action_clear_cache),
                onClick = onClearCache
            )
            ActionItem(
                icon = R.drawable.delete,
                label = stringResource(R.string.action_clear_data),
                onClick = onClearData
            )
        }

        ActionItem(
            icon = R.drawable.share,
            label = stringResource(R.string.action_share),
            onClick = onShare
        )

        ActionItem(
            icon = R.drawable.storage,
            label = stringResource(R.string.action_export),
            onClick = onExport
        )

        if (appInfo.packageName != BuildConfig.APPLICATION_ID) {
            ActionItem(
                icon = R.drawable.delete_forever,
                label = stringResource(R.string.action_uninstall),
                onClick = onUninstall
            )
        }
    }
}

@Composable
private fun ActionItem(
    icon: Int,
    label: String,
    enabled: Boolean = true,
    tintColor: Color? = null,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.5f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = (tintColor ?: MaterialTheme.colorScheme.primary).copy(alpha = alpha)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            maxLines = 1
        )
    }
}

@Composable
private fun GeneralTabScreen(details: DetailedAppInfo) {
    val appInfo = details.appInfo
    val context = LocalContext.current
    val installTime = remember(appInfo.firstInstallTime, context) { formatTime(appInfo.firstInstallTime, context) }
    val lastUpdateTime = remember(appInfo.lastUpdateTime, context) { formatTime(appInfo.lastUpdateTime, context) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        appInfo.bloatRecommendation?.let { recommendation ->
            item {
                InfoCard(
                    title = stringResource(R.string.debloat_recommendation),
                    value = recommendation
                )
            }
        }

        item {
            InfoCard(
                title = stringResource(R.string.info_app_version),
                value = "${appInfo.versionName} (${appInfo.versionCode})"
            )
        }
        item {
            InfoCard(
                title = stringResource(R.string.info_sdk_details),
                value = stringResource(
                    R.string.info_sdk_details_format,
                    appInfo.targetSdk,
                    appInfo.minSdk
                )
            )
        }
        appInfo.installSize?.let { bytes ->
            item {
                InfoCard(
                    title = stringResource(R.string.info_app_size),
                    value = android.text.format.Formatter.formatShortFileSize(context, bytes)
                )
            }
        }
        item {
            InfoCard(
                title = stringResource(R.string.info_installer_source),
                value = appInfo.installerPackageName ?: stringResource(R.string.unknown)
            )
        }
        item {
            InfoCard(
                title = stringResource(R.string.info_install_time),
                value = installTime
            )
        }
        item {
            InfoCard(
                title = stringResource(R.string.info_last_update_time),
                value = lastUpdateTime
            )
        }
        item {
            InfoCard(
                title = stringResource(R.string.info_apk_path),
                value = appInfo.sourceDir ?: stringResource(R.string.not_available)
            )
        }
        item {
            InfoCard(
                title = stringResource(R.string.info_data_dir),
                value = appInfo.dataDir ?: stringResource(R.string.not_available)
            )
        }
        appInfo.obbFilePath?.let { obb ->
            item {
                InfoCard(title = stringResource(R.string.info_obb_dir), value = obb)
            }
        }
        if (appInfo.sharedDataDir.isNotEmpty()) {
            item {
                InfoCard(
                    title = stringResource(R.string.info_shared_data_dir),
                    value = appInfo.sharedDataDir
                )
            }
        }
        details.signatureSha256?.let { sha256 ->
            item {
                InfoCard(title = stringResource(R.string.info_signature_sha256), value = sha256)
            }
        }
    }
}

@Composable
private fun PermissionsTabScreen(permissions: List<PermissionDetail>) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredPermissions = remember(searchQuery, permissions) {
        permissions.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    (it.label?.contains(searchQuery, ignoreCase = true) ?: false)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.permissions_search)) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.round_search),
                    contentDescription = null
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
            )
        )

        if (filteredPermissions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.no_permissions_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredPermissions) { perm ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val simpleName = perm.name.substringAfterLast('.')
                            val displayName = perm.label ?: simpleName
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = perm.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = firaMonoFontFamily
                            )
                            if (perm.protectionLevel.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(
                                        R.string.permission_protection_level,
                                        perm.protectionLevel
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            perm.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(horizontalAlignment = Alignment.End) {
                            StatusChip(
                                text = if (perm.isGranted) stringResource(R.string.permission_state_granted) else stringResource(
                                    R.string.permission_state_denied
                                ),
                                color = if (perm.isGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                                textColor = if (perm.isGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComponentsTabScreen(details: DetailedAppInfo) {
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.search_components_placeholder)) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.round_search),
                    contentDescription = null
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
            )
        )

        val (filteredActivities, filteredServices, filteredReceivers, filteredProviders) =
            remember(searchQuery, details) {
                val filter = { items: List<String> ->
                    if (searchQuery.isEmpty()) items
                    else items.filter { it.contains(searchQuery, ignoreCase = true) }
                }
                ComponentLists(
                    activities = filter(details.activities),
                    services = filter(details.services),
                    receivers = filter(details.receivers),
                    providers = filter(details.providers)
                )
            }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CollapsibleSection(
                    title = stringResource(R.string.section_activities_title, filteredActivities.size),
                    items = filteredActivities
                )
            }
            item {
                CollapsibleSection(
                    title = stringResource(R.string.section_services_title, filteredServices.size),
                    items = filteredServices
                )
            }
            item {
                CollapsibleSection(
                    title = stringResource(R.string.section_receivers_title, filteredReceivers.size),
                    items = filteredReceivers
                )
            }
            item {
                CollapsibleSection(
                    title = stringResource(R.string.section_providers_title, filteredProviders.size),
                    items = filteredProviders
                )
            }
        }
    }
}

@Composable
private fun CollapsibleSection(title: String, items: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { expanded = !expanded }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                painter = painterResource(
                    if (expanded) R.drawable.arrow_upward else R.drawable.arrow_downward
                ),
                contentDescription = if (expanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                if (items.isEmpty()) {
                    Text(
                        text = stringResource(R.string.components_none_declared),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    val clipboard = LocalClipboard.current
                    val context = LocalContext.current
                    val classNameLabel = stringResource(R.string.class_name_label)
                    items.forEach { className ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    coroutineScope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(
                                                android.content.ClipData.newPlainText(classNameLabel, className)
                                            )
                                        )
                                    }
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_copied_class_name),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .padding(vertical = 6.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = className,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = firaMonoFontFamily,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibsAndFeaturesTabScreen(details: DetailedAppInfo) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.section_native_libs_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (details.nativeLibs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_native_libs_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    details.nativeLibs.forEach { lib ->
                        Text(
                            text = lib,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = firaMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.section_req_features_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (details.reqFeatures.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_req_features_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    details.reqFeatures.forEach { feature ->
                        Text(
                            text = feature,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = firaMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, value: String) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val valueLabel = stringResource(R.string.value_label)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable {
                coroutineScope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(
                            android.content.ClipData.newPlainText(valueLabel, value)
                        )
                    )
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_copy_saved),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (value.contains("/") || value.contains(".")) firaMonoFontFamily else bodyFontFamily
        )
    }
}

private data class ComponentLists(
    val activities: List<String>,
    val services: List<String>,
    val receivers: List<String>,
    val providers: List<String>
)

private fun formatTime(timestamp: Long, context: Context): String {
    if (timestamp == 0L) return context.getString(R.string.not_available)
    return try {
        val formatter = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.MEDIUM,
            Locale.getDefault()
        )
        formatter.format(Date(timestamp))
    } catch (_: Exception) {
        context.getString(R.string.not_available)
    }
}
