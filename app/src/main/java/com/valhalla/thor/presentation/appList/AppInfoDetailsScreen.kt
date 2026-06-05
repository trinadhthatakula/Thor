package com.valhalla.thor.presentation.appList

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import com.valhalla.thor.R
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.model.PermissionDetail
import com.valhalla.thor.presentation.theme.bodyFontFamily
import com.valhalla.thor.presentation.theme.firaMonoFontFamily
import com.valhalla.thor.presentation.utils.getAppIcon
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppInfoDetailsScreen(
    packageName: String,
    appName: String,
    onBack: () -> Unit,
    onNavigateToPermissionManager: (packageName: String, appName: String) -> Unit,
    viewModel: AppInfoDetailsViewModel = org.koin.androidx.compose.koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
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

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.round_close),
                        contentDescription = stringResource(R.string.cd_close),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.app_details_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        contentWindowInsets = WindowInsets(0,0,0,0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (state.isLoading && state.detailedInfo == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (state.errorMessage != null && state.detailedInfo == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.danger),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = state.errorMessage?.asString(context) ?: stringResource(R.string.unknown_error_occurred),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    TextButton(onClick = { viewModel.loadAppDetails(packageName) }) {
                        Text(stringResource(R.string.retry_label))
                    }
                }
            } else {
                state.detailedInfo?.let { details ->
                    val appInfo = details.appInfo
                    val coroutineScope = rememberCoroutineScope()
                    val tabTitles = listOf(
                        stringResource(R.string.tab_overview_title),
                        stringResource(R.string.permissions_title),
                        stringResource(R.string.tab_components),
                        stringResource(R.string.tab_libs_features)
                    )
                    val pagerState = rememberPagerState(pageCount = { tabTitles.size })

                    Column(modifier = Modifier.fillMaxSize()) {
                        // 1. Header (Icon + Name + Chips)
                        AppDetailsHeader(appInfo = appInfo)

                        // 2. Action buttons
                        AppDetailsActionRow(
                            appInfo = appInfo,
                            isRoot = state.isRoot,
                            isShizuku = state.isShizuku,
                            isDhizuku = state.isDhizuku,
                            isInFreezer = state.isInFreezer,
                            onLaunch = {
                                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                                if (intent != null) context.startActivity(intent)
                                else Toast.makeText(context, context.getString(R.string.cannot_launch_app), Toast.LENGTH_SHORT).show()
                            },
                            onSystemSettings = {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:$packageName")
                                }
                                context.startActivity(intent)
                            },
                            onFreezeToggle = {
                                viewModel.toggleFreezerState(packageName, appInfo.appName, it)
                            },
                            onSuspendToggle = {
                                viewModel.toggleSuspendState(packageName, it)
                            },
                            onForceStop = {
                                viewModel.forceStopApp(packageName)
                            },
                            onManagePermissions = {
                                onNavigateToPermissionManager(packageName, appInfo.appName ?: "")
                            },
                            onToggleFreezerMembership = {
                                viewModel.addOrRemoveFromFreezer(packageName)
                            },
                            onClearCache = {
                                viewModel.clearCache(packageName)
                            },
                            onClearData = {
                                showClearDataConfirmation = true
                            },
                            onUninstall = {
                                showUninstallConfirmation = true
                            },
                            onShare = {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, "Market link: market://details?id=$packageName")
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.share_via)))
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 3. SecondaryScrollableTabRow
                        SecondaryScrollableTabRow(
                            selectedTabIndex = pagerState.currentPage,
                            containerColor = MaterialTheme.colorScheme.background,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            edgePadding = 16.dp
                        ) {
                            tabTitles.forEachIndexed { index, title ->
                                Tab(
                                    selected = pagerState.currentPage == index,
                                    onClick = {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    },
                                    text = {
                                        Text(
                                            text = title,
                                            fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Medium,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                    }
                                )
                            }
                        }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) { page ->
                            when (page) {
                                0 -> GeneralTabScreen(details = details)
                                1 -> PermissionsTabScreen(permissions = details.permissions)
                                2 -> ComponentsTabScreen(details = details)
                                3 -> LibsAndFeaturesTabScreen(details = details)
                            }
                        }
                    }
                }
            }
        }
    }

    // --- ALERTS ---
    if (showClearDataConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearDataConfirmation = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.danger),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.clear_app_data_title)) },
            text = { Text(stringResource(R.string.dialog_clear_data_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearData(packageName)
                    showClearDataConfirmation = false
                }) { Text(stringResource(R.string.action_clear_data), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showUninstallConfirmation) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirmation = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.delete_forever),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.uninstall_app_title)) },
            text = { Text(stringResource(R.string.uninstall_app_desc, appName)) },
            confirmButton = {
                TextButton(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_DELETE).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    context.startActivity(intent)
                    showUninstallConfirmation = false
                }) { Text(stringResource(R.string.action_uninstall), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun AppDetailsHeader(appInfo: AppInfo) {
    val context = LocalContext.current
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
            Image(
                painter = rememberAsyncImagePainter(getAppIcon(appInfo.packageName, context)),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = appInfo.appName ?: "Unknown",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = appInfo.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = firaMonoFontFamily
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Chips row
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                if (appInfo.isSystem) {
                    StatusChip(text = stringResource(R.string.chip_system), color = MaterialTheme.colorScheme.tertiaryContainer, textColor = MaterialTheme.colorScheme.onTertiaryContainer)
                } else {
                    StatusChip(text = stringResource(R.string.chip_user), color = MaterialTheme.colorScheme.primaryContainer, textColor = MaterialTheme.colorScheme.onPrimaryContainer)
                }

                if (!appInfo.enabled) {
                    StatusChip(text = stringResource(R.string.frozen), color = MaterialTheme.colorScheme.errorContainer)
                }

                if (appInfo.isSuspended) {
                    StatusChip(text = stringResource(R.string.suspended), color = MaterialTheme.colorScheme.secondaryContainer)
                }

                if (appInfo.isDebuggable) {
                    StatusChip(text = stringResource(R.string.chip_debug), color = MaterialTheme.colorScheme.outlineVariant)
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
    onShare: () -> Unit
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
        // 1. Launch / Open
        ActionItem(
            icon = R.drawable.open_in_new,
            label = stringResource(R.string.action_open),
            enabled = appInfo.enabled,
            onClick = onLaunch
        )

        // 1b. System Settings
        ActionItem(
            icon = R.drawable.settings,
            label = stringResource(R.string.settings),
            onClick = onSystemSettings
        )

        // 2. Freeze / Unfreeze
        if (hasPrivilege) {
            val freezeLabel = if (isFrozen) stringResource(R.string.action_unfreeze) else stringResource(R.string.action_freeze)
            val freezeIcon = if (isFrozen) R.drawable.freeze_off else R.drawable.frozen
            ActionItem(
                icon = freezeIcon,
                label = freezeLabel,
                onClick = { onFreezeToggle(!isFrozen) }
            )

            // 3. Suspend / Unsuspend
            val suspendLabel = if (isSuspended) stringResource(R.string.action_unsuspend) else stringResource(R.string.action_suspend)
            val suspendIcon = if (isSuspended) R.drawable.bolt else R.drawable.warning
            ActionItem(
                icon = suspendIcon,
                label = suspendLabel,
                onClick = { onSuspendToggle(!isSuspended) }
            )

            // 4. Force Stop
            if (appInfo.enabled) {
                ActionItem(
                    icon = R.drawable.force_close,
                    label = stringResource(R.string.action_force_stop),
                    onClick = onForceStop
                )
            }
        }

        // 5. Manage Permissions
        ActionItem(
            icon = R.drawable.shield,
            label = stringResource(R.string.action_permissions),
            onClick = onManagePermissions
        )

        // 6. Toggle Freezer
        val freezerLabel = if (isInFreezer) stringResource(R.string.action_in_freezer) else stringResource(R.string.action_add_freezer)
        ActionItem(
            icon = R.drawable.snowflake,
            label = freezerLabel,
            tintColor = if (isInFreezer) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            onClick = onToggleFreezerMembership
        )

        // 7. Clear Cache / Data
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

        // 8. Share
        ActionItem(
            icon = R.drawable.share,
            label = stringResource(R.string.action_share),
            onClick = onShare
        )

        // 9. Uninstall
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
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            InfoCard(title = stringResource(R.string.info_app_version), value = "${appInfo.versionName} (${appInfo.versionCode})")
        }
        item {
            InfoCard(title = stringResource(R.string.info_sdk_details), value = stringResource(R.string.info_sdk_details_format, appInfo.targetSdk, appInfo.minSdk))
        }
        item {
            InfoCard(title = stringResource(R.string.info_installer_source), value = appInfo.installerPackageName ?: stringResource(R.string.unknown))
        }
        item {
            InfoCard(title = stringResource(R.string.info_install_time), value = formatTime(appInfo.firstInstallTime, context))
        }
        item {
            InfoCard(title = stringResource(R.string.info_last_update_time), value = formatTime(appInfo.lastUpdateTime, context))
        }
        item {
            InfoCard(title = stringResource(R.string.info_apk_path), value = appInfo.sourceDir ?: stringResource(R.string.not_available))
        }
        item {
            InfoCard(title = stringResource(R.string.info_data_dir), value = appInfo.dataDir ?: stringResource(R.string.not_available))
        }
        appInfo.obbFilePath?.let { obb ->
            item {
                InfoCard(title = stringResource(R.string.info_obb_dir), value = obb)
            }
        }
        if (appInfo.sharedDataDir.isNotEmpty()) {
            item {
                InfoCard(title = stringResource(R.string.info_shared_data_dir), value = appInfo.sharedDataDir)
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
    val filteredPermissions = permissions.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
                (it.label?.contains(searchQuery, ignoreCase = true) ?: false)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.permissions_search)) },
            leadingIcon = { Icon(painterResource(R.drawable.round_search), contentDescription = null) },
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
                            perm.description?.let { desc ->
                                if (desc.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(horizontalAlignment = Alignment.End) {
                            StatusChip(
                                text = if (perm.isGranted) stringResource(R.string.permission_state_granted) else stringResource(R.string.permission_state_denied),
                                color = if (perm.isGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                                textColor = if (perm.isGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            StatusChip(
                                text = perm.protectionLevel,
                                color = when (perm.protectionLevel) {
                                    "Dangerous" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                    "Signature" -> MaterialTheme.colorScheme.tertiaryContainer
                                    "Normal" -> MaterialTheme.colorScheme.secondaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                textColor = MaterialTheme.colorScheme.onSurface
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
    val filter = { items: List<String> ->
        if (searchQuery.isEmpty()) items
        else items.filter { it.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.search_components_placeholder)) },
            leadingIcon = { Icon(painterResource(R.drawable.round_search), contentDescription = null) },
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

        val filteredActivities = filter(details.activities)
        val filteredServices = filter(details.services)
        val filteredReceivers = filter(details.receivers)
        val filteredProviders = filter(details.providers)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CollapsibleSection(title = stringResource(R.string.section_activities_title, filteredActivities.size), items = filteredActivities)
            }
            item {
                CollapsibleSection(title = stringResource(R.string.section_services_title, filteredServices.size), items = filteredServices)
            }
            item {
                CollapsibleSection(title = stringResource(R.string.section_receivers_title, filteredReceivers.size), items = filteredReceivers)
            }
            item {
                CollapsibleSection(title = stringResource(R.string.section_providers_title, filteredProviders.size), items = filteredProviders)
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
                    items.forEach { className ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    coroutineScope.launch {
                                        clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("class name", className)))
                                    }
                                    Toast.makeText(context, context.getString(R.string.toast_copied_class_name), Toast.LENGTH_SHORT).show()
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable {
                coroutineScope.launch {
                    clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("value", value)))
                }
                Toast.makeText(context, context.getString(R.string.toast_copy_saved), Toast.LENGTH_SHORT).show()
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

private fun formatTime(timestamp: Long, context: Context): String {
    if (timestamp == 0L) return context.getString(R.string.not_available)
    return try {
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault())
        formatter.format(Date(timestamp))
    } catch (e: Exception) {
        context.getString(R.string.not_available)
    }
}
