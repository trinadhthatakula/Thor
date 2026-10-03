// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.RootRefreshStatus
import com.valhalla.thor.presentation.components.RootRefreshNotice
import com.valhalla.thor.presentation.home.components.AppDistributionChart
import com.valhalla.thor.presentation.home.components.DashboardHeader
import com.valhalla.thor.presentation.home.components.HomeActionsBento
import com.valhalla.thor.presentation.home.components.homeActionRows
import com.valhalla.thor.presentation.home.components.SupportCommunitySection
import com.valhalla.thor.presentation.home.components.SummaryStatRow
import com.valhalla.thor.presentation.home.components.RootManagerPickerDialog
import com.valhalla.thor.presentation.settings.SupportDeveloperHelper
import com.valhalla.thor.presentation.theme.LocalDarkTheme
import com.valhalla.thor.presentation.theme.greenDark
import com.valhalla.thor.presentation.theme.greenLight
import com.valhalla.thor.presentation.installer.InstallerViewModel
import com.valhalla.thor.presentation.installer.PortableInstaller
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HomeScreen(
    onNavigateToApps: () -> Unit,
    onNavigateToFreezer: () -> Unit,
    onNavigateToSuspendedApps: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onReinstallAll: () -> Unit,
    /**
     * Asks for the whole-device cache clear. No [AppListType] any more, and no dialog here either:
     * the operation clears system and user apps together — `pm trim-caches` picks its own victims —
     * so the confirmation that says so lives with the state machine that runs it, in `MainViewModel`
     * and `ClearAllCacheSheet`. This is only the tile.
     */
    onClearAllCache: () -> Unit,
    /**
     * Opens the Apps tab showing only what the given installer put there. Takes the list type as
     * well because the chart is drawn per type — a bar read off the System chart names apps the
     * Apps tab hides while it is on User, so handing over the filter alone lands on an empty list.
     */
    onFilterByInstaller: (AppListType, String) -> Unit,
    onNavigateToExtensionManager: () -> Unit,
    onNavigateToBackupRestoreHub: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
    installerViewModel: InstallerViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPrivilegeDialog by remember { mutableStateOf(false) }
    var showRootManagerPicker by remember { mutableStateOf(false) }

    var showInstallerSheet by remember { mutableStateOf(false) }
    var showSupportSheet by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            installerViewModel.parsePackage(it)
            showInstallerSheet = true
        }
    }

    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val isWideScreen = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val isExpanded = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val hasPrivilege = state.activePrivilegeMode != null
    // Root *or* Shizuku, which is wider than the `isRoot` this used to be. The tile no longer clears
    // one app at a time — it runs `pm trim-caches`, and PackageManagerService gates that on
    // CLEAR_APP_CACHE, a permission the shell uid holds. Dhizuku commands run as the owner app,
    // which lacks that permission, and the device-owner API has no equivalent.
    val canClearCache = state.activePrivilegeMode == PrivilegeMode.ROOT ||
        state.activePrivilegeMode == PrivilegeMode.SHIZUKU
    val reinstallVisible = state.activePrivilegeMode != null &&
        state.unknownInstallerCount > 0 && state.showReinstallCard
    // Both optional tiles hidden with no privilege leaves the bento with nothing to draw, so the
    // spacer that separates it from the summary row goes with it rather than leaving a bare gap.
    val hasHomeActions = homeActionRows(
        reinstallVisible = reinstallVisible,
        canClearCache = canClearCache,
        hasPrivilege = hasPrivilege,
        showInstaller = state.showInstallerTile,
        showExtensions = state.showExtensionsTile,
    ).isNotEmpty()

    // The bottom inset (nav-bar height + system navigation-bar insets) is already applied by
    // MainScreen, which hosts this screen inside Scaffold's Box(Modifier.padding(innerPadding)).
    // Adding another 80.dp + navigationBars here double-counted it and left a large empty gap
    // at the bottom of the scroll content, so this screen owns no bottom inset of its own.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier
                .then(if (isExpanded) Modifier.widthIn(max = 1200.dp) else Modifier)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
        // 1. Header (full width always)
        DashboardHeader(
            isRoot = state.isRootAvailable,
            isShizuku = state.isShizukuAvailable,
            isDhizuku = state.isDhizukuAvailable,
            activeMode = state.activePrivilegeMode,
            isPrivilegeReady = state.isPrivilegeReady,
            selectedType = state.selectedType,
            onTypeChanged = { viewModel.onTypeChanged(it) },
            onPrivilegeChanged = { viewModel.onPrivilegeModeChanged(it) },
            onRestrictedStatusClick = {
                viewModel.refreshManagerShortcuts()
                showPrivilegeDialog = true
            },
            onNavigateToQueue = onNavigateToQueue,
            extensionsUnlocked = state.extensionsUnlocked,
            onCrack = { viewModel.crackEasterEgg() },
            onShowSupport = { showSupportSheet = true }
        )

        RootRefreshNotice(
            status = state.rootAvailability.refreshStatus,
            onRetry = viewModel::refreshPrivileges,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(Modifier.height(8.dp))

        if (isWideScreen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Left Column: Stats & Actions
                Column(modifier = Modifier.weight(1.2f)) {
                    SummaryStatRow(
                        activeCount = state.activeAppCount,
                        frozenCount = state.frozenAppCount,
                        suspendedCount = state.suspendedAppCount,
                        onActiveClick = onNavigateToApps,
                        onFrozenClick = onNavigateToFreezer,
                        onSuspendedClick = onNavigateToSuspendedApps,
                        modifier = Modifier.padding(horizontal = 0.dp)
                    )

                    if (hasHomeActions) {
                        Spacer(Modifier.height(16.dp))

                        HomeActionsBento(
                            reinstallVisible = reinstallVisible,
                            canClearCache = canClearCache,
                            hasPrivilege = hasPrivilege,
                            unknownInstallerCount = state.unknownInstallerCount,
                            selectedTypeName = state.selectedType.name.lowercase(),
                            onReinstall = onReinstallAll,
                            onDismissReinstall = { viewModel.dismissReinstallCard() },
                            onInstall = { filePickerLauncher.launch(arrayOf("*/*")) },
                            onClearCache = onClearAllCache,
                            onNavigateToExtensionManager = onNavigateToExtensionManager,
                            onNavigateToBackupRestoreHub = onNavigateToBackupRestoreHub,
                            showInstaller = state.showInstallerTile,
                            showExtensions = state.showExtensionsTile,
                            narrowContainer = true,
                        )
                    }
                }

                // Right Column: Distribution & Support
                Column(modifier = Modifier.weight(1f)) {
                    AnimatedVisibility(state.distribution.isNotEmpty() && !state.isLoading) {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(48.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(24.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Text(
                                    text = stringResource(R.string.app_distribution),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = stringResource(
                                        R.string.total_apps,
                                        state.activeAppCount + state.frozenAppCount
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.height(24.dp))
                            AppDistributionChart(
                                slices = state.distribution,
                                onInstallerClick = { installer ->
                                    onFilterByInstaller(state.selectedType, installer)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    SupportCommunitySection(onSupportClick = { showSupportSheet = true })
                }
            }
        } else {
            // 2. Summary Cards
            SummaryStatRow(
                activeCount = state.activeAppCount,
                frozenCount = state.frozenAppCount,
                suspendedCount = state.suspendedAppCount,
                onActiveClick = onNavigateToApps,
                onFrozenClick = onNavigateToFreezer,
                onSuspendedClick = onNavigateToSuspendedApps,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            // --- ACTIONS ---
            if (hasHomeActions) {
                Spacer(Modifier.height(12.dp))

                HomeActionsBento(
                    reinstallVisible = reinstallVisible,
                    canClearCache = canClearCache,
                    hasPrivilege = hasPrivilege,
                    unknownInstallerCount = state.unknownInstallerCount,
                    selectedTypeName = state.selectedType.name.lowercase(),
                    onReinstall = onReinstallAll,
                    onDismissReinstall = { viewModel.dismissReinstallCard() },
                    onInstall = { filePickerLauncher.launch(arrayOf("*/*")) },
                    onClearCache = onClearAllCache,
                    onNavigateToExtensionManager = onNavigateToExtensionManager,
                    onNavigateToBackupRestoreHub = onNavigateToBackupRestoreHub,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    showInstaller = state.showInstallerTile,
                    showExtensions = state.showExtensionsTile,
                )
            }

            Spacer(Modifier.height(24.dp))

            // 3. Distribution Chart
            AnimatedVisibility(state.distribution.isNotEmpty() && !state.isLoading) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(48.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            text = stringResource(R.string.app_distribution),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(
                                R.string.total_apps,
                                state.activeAppCount + state.frozenAppCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    AppDistributionChart(
                        slices = state.distribution,
                        onInstallerClick = { installer ->
                            onFilterByInstaller(state.selectedType, installer)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 4. Social Links
            Spacer(Modifier.height(8.dp))
            SupportCommunitySection(
                modifier = Modifier.padding(horizontal = 24.dp),
                onSupportClick = { showSupportSheet = true }
            )
        }
        Spacer(Modifier.height(32.dp))
        }
    }

    // --- Dialogs ---
    // The two cache dialogs that used to live here are gone. They offered a choice — "user apps" or
    // "system apps" — that `pm trim-caches` cannot honour: PackageManagerService evicts by LRU across
    // the whole volume and takes no package argument. The replacement is `ClearAllCacheSheet`, hosted
    // by MainScreen and driven by `MainUiState.cacheClear`, which asks once and says plainly that
    // system apps are included too.

    if (showRootManagerPicker) {
        val picker by viewModel.rootManagerPicker.collectAsStateWithLifecycle()
        RootManagerPickerDialog(
            apps = picker.apps,
            isLoading = picker.isLoading,
            loadFailed = picker.loadFailed,
            onSelect = {
                viewModel.selectRootManager(it)
                showRootManagerPicker = false
            },
            onRetry = viewModel::loadRootManagerCandidates,
            onDismiss = { showRootManagerPicker = false }
        )
    }

    if (showPrivilegeDialog && !showRootManagerPicker) {
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(
            onDismissRequest = { showPrivilegeDialog = false },
            icon = { Icon(painterResource(R.drawable.privacy_tip), null) },
            title = { Text(stringResource(R.string.privilege_check)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.privilege_check_desc))
                    RootRefreshNotice(status = state.rootAvailability.refreshStatus)

                    if (state.installedManagers.isNotEmpty()) {
                        androidx.compose.material3.HorizontalDivider()

                        state.installedManagers.forEach { info ->
                            val isGranted = when (info.app.mode) {
                                PrivilegeMode.ROOT -> state.isRootAvailable
                                PrivilegeMode.SHIZUKU -> state.isShizukuAvailable
                                PrivilegeMode.DHIZUKU -> state.isDhizukuAvailable
                                PrivilegeMode.NONE -> false
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = info.app.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )

                                if (info.app.mode == PrivilegeMode.ROOT &&
                                    state.rootAvailability.refreshStatus != RootRefreshStatus.IDLE
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (state.rootAvailability.refreshStatus == RootRefreshStatus.CHECKING) {
                                                R.string.tile_checking
                                            } else {
                                                R.string.unknown
                                            }
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                } else if (isGranted) {
                                    Text(
                                        text = stringResource(R.string.permission_state_granted),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (info.app == com.valhalla.thor.domain.model.PrivilegeManagerApp.SHIZUKU && state.isShizukuBinderAlive) {
                                            androidx.compose.material3.TextButton(
                                                onClick = { viewModel.requestShizuku() }
                                            ) {
                                                Text(stringResource(R.string.installed_apps_permission_grant))
                                            }
                                        } else if (info.app == com.valhalla.thor.domain.model.PrivilegeManagerApp.DHIZUKU) {
                                            androidx.compose.material3.TextButton(
                                                onClick = { viewModel.requestDhizuku(context) }
                                            ) {
                                                Text(stringResource(R.string.installed_apps_permission_grant))
                                            }
                                        }
                                        androidx.compose.material3.TextButton(
                                            onClick = {
                                                viewModel.openManagerApp(context, info.installedPackageName)
                                            }
                                        ) {
                                            Text(stringResource(R.string.open_app))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.hasSelectedRootManager) {
                        androidx.compose.material3.HorizontalDivider()
                        val manager = state.selectedRootManager
                        if (manager == null) {
                            Text(stringResource(R.string.selected_root_manager_unavailable))
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.check_circle),
                                    contentDescription = stringResource(R.string.selected_root_manager),
                                    tint = if (LocalDarkTheme.current) greenDark else greenLight,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        manager.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        manager.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(onClick = {
                                    viewModel.openManagerApp(context, manager.packageName)
                                }) {
                                    Text(stringResource(R.string.open_app))
                                }
                            }
                        }
                    }
                    SplitButtonLayout(
                        leadingButton = {
                            SplitButtonDefaults.LeadingButton(
                                onClick = {
                                    viewModel.loadRootManagerCandidates()
                                    showRootManagerPicker = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.choose_root_manager_action), maxLines = 2)
                            }
                        },
                        trailingButton = {
                            SplitButtonDefaults.TrailingButton(
                                onClick = { viewModel.selectRootManager(null) },
                                enabled = state.hasSelectedRootManager
                            ) {
                                Text(stringResource(R.string.cd_clear), maxLines = 2)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::refreshPrivileges,
                    enabled = state.rootAvailability.refreshStatus != RootRefreshStatus.CHECKING
                ) {
                    Text(stringResource(
                        if (state.rootAvailability.refreshStatus == RootRefreshStatus.IDLE) {
                            R.string.refresh
                        } else {
                            R.string.retry_label
                        }
                    ))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPrivilegeDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showInstallerSheet) {
        PortableInstaller(
            onDismiss = { showInstallerSheet = false },
            viewModel = installerViewModel
        )
    }

    if (showSupportSheet) {
        SupportDeveloperHelper(
            onDismiss = { showSupportSheet = false }
        )
    }
}
