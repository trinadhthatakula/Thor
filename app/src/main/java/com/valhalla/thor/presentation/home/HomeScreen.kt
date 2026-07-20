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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.presentation.home.components.AppDistributionChart
import com.valhalla.thor.presentation.home.components.DashboardHeader
import com.valhalla.thor.presentation.home.components.HomeActionsBento
import com.valhalla.thor.presentation.home.components.SupportCommunitySection
import com.valhalla.thor.presentation.home.components.SummaryStatRow
import com.valhalla.thor.presentation.settings.SupportDeveloperHelper
import com.valhalla.thor.presentation.installer.InstallerViewModel
import com.valhalla.thor.presentation.installer.PortableInstaller
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HomeScreen(
    onNavigateToApps: () -> Unit,
    onNavigateToFreezer: () -> Unit,
    onReinstallAll: () -> Unit,
    onClearAllCache: (AppListType) -> Unit,
    onNavigateToExtensionManager: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
    installerViewModel: InstallerViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCacheDialog by remember { mutableStateOf(false) }
    var showSystemCacheConfirmDialog by remember { mutableStateOf(false) }
    var showPrivilegeDialog by remember { mutableStateOf(false) }

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
    val isRoot = state.activePrivilegeMode == PrivilegeMode.ROOT
    val reinstallVisible = state.activePrivilegeMode != null &&
        state.unknownInstallerCount > 0 && state.showReinstallCard

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
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .then(if (isExpanded) Modifier.widthIn(max = 1200.dp) else Modifier)
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
            onRestrictedStatusClick = { showPrivilegeDialog = true },
            extensionsUnlocked = state.extensionsUnlocked,
            onCrack = { viewModel.crackEasterEgg() },
            onShowSupport = { showSupportSheet = true }
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
                        onSuspendedClick = onNavigateToFreezer,
                        modifier = Modifier.padding(horizontal = 0.dp)
                    )

                    Spacer(Modifier.height(16.dp))

                    HomeActionsBento(
                        reinstallVisible = reinstallVisible,
                        isRoot = isRoot,
                        hasPrivilege = hasPrivilege,
                        unknownInstallerCount = state.unknownInstallerCount,
                        selectedTypeName = state.selectedType.name.lowercase(),
                        onReinstall = onReinstallAll,
                        onDismissReinstall = { viewModel.dismissReinstallCard() },
                        onInstall = { filePickerLauncher.launch(arrayOf("*/*")) },
                        onClearCache = { showCacheDialog = true },
                        onNavigateToExtensionManager = onNavigateToExtensionManager,
                    )
                }

                // Right Column: Distribution & Support
                Column(modifier = Modifier.weight(1f)) {
                    AnimatedVisibility(state.distributionData.isNotEmpty() && !state.isLoading) {
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
                                data = state.distributionData,
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
                onSuspendedClick = onNavigateToFreezer,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(Modifier.height(12.dp))

            // --- ACTIONS ---
            HomeActionsBento(
                reinstallVisible = reinstallVisible,
                isRoot = isRoot,
                hasPrivilege = hasPrivilege,
                unknownInstallerCount = state.unknownInstallerCount,
                selectedTypeName = state.selectedType.name.lowercase(),
                onReinstall = onReinstallAll,
                onDismissReinstall = { viewModel.dismissReinstallCard() },
                onInstall = { filePickerLauncher.launch(arrayOf("*/*")) },
                onClearCache = { showCacheDialog = true },
                onNavigateToExtensionManager = onNavigateToExtensionManager,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(Modifier.height(24.dp))

            // 3. Distribution Chart
            AnimatedVisibility(state.distributionData.isNotEmpty() && !state.isLoading) {
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
                        data = state.distributionData,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 4. Social Links
            Spacer(Modifier.height(8.dp))
            SupportCommunitySection(onSupportClick = { showSupportSheet = true })
        }
        Spacer(Modifier.height(32.dp))
        }
    }

    // --- Dialogs ---
    if (showCacheDialog) {
        AlertDialog(
            onDismissRequest = { showCacheDialog = false },
            icon = { Icon(painterResource(R.drawable.clear_all), null) },
            title = { Text(stringResource(R.string.clear_all_cache)) },
            text = { Text(stringResource(R.string.clear_cache_prompt)) },
            confirmButton = {
                Button(onClick = {
                    onClearAllCache(AppListType.USER)
                    showCacheDialog = false
                }) { Text(stringResource(R.string.user_apps)) }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showCacheDialog = false
                    showSystemCacheConfirmDialog = true
                }) { Text(stringResource(R.string.system_apps)) }
            }
        )
    }

    if (showSystemCacheConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSystemCacheConfirmDialog = false },
            icon = { Icon(painterResource(R.drawable.warning), null) },
            title = { Text(stringResource(R.string.clear_system_cache_title)) },
            text = { Text(stringResource(R.string.clear_system_cache_desc)) },
            confirmButton = {
                Button(onClick = {
                    onClearAllCache(AppListType.SYSTEM)
                    showSystemCacheConfirmDialog = false
                }) { Text(stringResource(R.string.proceed)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showSystemCacheConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showPrivilegeDialog) {
        AlertDialog(
            onDismissRequest = { showPrivilegeDialog = false },
            icon = { Icon(painterResource(R.drawable.privacy_tip), null) },
            title = { Text(stringResource(R.string.privilege_check)) },
            text = {
                Text(stringResource(R.string.privilege_check_desc))
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.loadDashboardData()
                    showPrivilegeDialog = false
                }) {
                    Text(stringResource(R.string.refresh))
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
