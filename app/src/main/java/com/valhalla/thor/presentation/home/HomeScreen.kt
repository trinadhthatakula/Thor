package com.valhalla.thor.presentation.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.presentation.home.components.AppDistributionChart
import com.valhalla.thor.presentation.home.components.DashboardHeader
import com.valhalla.thor.presentation.home.components.SupportCommunitySection
import com.valhalla.thor.presentation.home.components.SummaryStatRow
import com.valhalla.thor.presentation.settings.SupportDeveloperHelper
import com.valhalla.thor.presentation.installer.InstallerViewModel
import com.valhalla.thor.presentation.installer.PortableInstaller
import org.koin.androidx.compose.koinViewModel

@Composable
fun HomeScreen(
    onNavigateToApps: () -> Unit,
    onNavigateToFreezer: () -> Unit,
    onReinstallAll: () -> Unit,
    onClearAllCache: (AppListType) -> Unit,
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
            installerViewModel.installFile(it)
            showInstallerSheet = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 100.dp) // Nav bar space
    ) {
        // 1. Header
        DashboardHeader(
            isRoot = state.isRootAvailable,
            isShizuku = state.isShizukuAvailable,
            isDhizuku = state.isDhizukuAvailable,
            activeMode = state.activePrivilegeMode,
            selectedType = state.selectedType,
            onTypeChanged = { viewModel.onTypeChanged(it) },
            onPrivilegeChanged = { viewModel.onPrivilegeModeChanged(it) },
            onRestrictedStatusClick = { showPrivilegeDialog = true }
        )

        Spacer(Modifier.height(8.dp))

        // 2. Summary Cards
        SummaryStatRow(
            activeCount = state.activeAppCount,
            frozenCount = state.frozenAppCount,
            suspendedCount = state.suspendedAppCount,
            onActiveClick = onNavigateToApps,
            onFrozenClick = onNavigateToFreezer,
            onSuspendedClick = onNavigateToFreezer // For now just go to freezer
        )

        Spacer(Modifier.height(12.dp))

        // --- ACTIONS ---

        // B. Reinstall All (Warning style card)
        AnimatedVisibility(state.activePrivilegeMode != null && state.unknownInstallerCount > 0 && state.showReinstallCard) {
            Column {
                ActionCard(
                    title = stringResource(R.string.reinstall_all),
                    subtitle = stringResource(
                        R.string.reinstall_all_subtitle,
                        state.unknownInstallerCount,
                        state.selectedType.name.lowercase()
                    ),
                    icon = R.drawable.apk_install,
                    isWarning = true,
                    onClick = onReinstallAll,
                    onClose = { viewModel.dismissReinstallCard() }
                )
                Spacer(Modifier.height(12.dp))
            }

        }

        // C. Portable Installer (Primary style card)
        ActionCard(
            title = stringResource(R.string.install_from_file),
            subtitle = stringResource(R.string.install_from_file_subtitle),
            icon = R.drawable.apk_install,
            isPrimary = true,
            onClick = {
                filePickerLauncher.launch(arrayOf("*/*"))
            }
        )

        AnimatedVisibility(state.activePrivilegeMode == PrivilegeMode.ROOT) {
            Column {
                Spacer(Modifier.height(12.dp))
                ActionCard(
                    title = stringResource(R.string.clear_all_cache),
                    subtitle = stringResource(R.string.clear_all_cache_subtitle),
                    icon = R.drawable.clear_all,
                    onClick = { showCacheDialog = true }
                )
            }
        }

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
        Spacer(Modifier.height(32.dp))
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

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    icon: Int,
    isPrimary: Boolean = false,
    isWarning: Boolean = false,
    onClick: () -> Unit,
    onClose: (() -> Unit)? = null
) {
    val containerColor = when {
        isPrimary -> MaterialTheme.colorScheme.primaryContainer
        isWarning -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.1f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val contentColor = when {
        isPrimary -> MaterialTheme.colorScheme.onPrimaryContainer
        isWarning -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(containerColor)
            .then(
                if (isWarning) {
                    Modifier.background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.05f),
                                Color.Transparent
                            )
                        )
                    )
                } else Modifier
            )
            .clickable(onClick = onClick)
            .padding(if (isPrimary) 24.dp else 20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        if (isPrimary) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                    )
                    .padding(if (isPrimary) 16.dp else 12.dp)
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(if (isPrimary) 24.dp else 20.dp),
                    tint = if (isPrimary) MaterialTheme.colorScheme.primaryContainer else contentColor
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = if (isPrimary) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = contentColor
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPrimary) contentColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(R.drawable.round_close),
                        contentDescription = stringResource(R.string.dismiss),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (isPrimary) {
                Icon(
                    painter = painterResource(R.drawable.open_in_new), // Arrow forward fallback
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.4f)
                )
            }
        }
    }
}
