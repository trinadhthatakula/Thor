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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.presentation.home.components.AppDistributionChart
import com.valhalla.thor.presentation.home.components.DashboardHeader
import com.valhalla.thor.presentation.home.components.SocialLinksRow
import com.valhalla.thor.presentation.home.components.SummaryStatRow
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
    var showPrivilegeDialog by remember { mutableStateOf(false) }

    var showInstallerSheet by remember { mutableStateOf(false) }

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
            onActiveClick = onNavigateToApps,
            onFrozenClick = onNavigateToFreezer
        )

        Spacer(Modifier.height(12.dp))

        // --- ACTIONS ---

        // B. Reinstall All (Warning style card)
        if (state.activePrivilegeMode != null && state.unknownInstallerCount > 0 && state.showReinstallCard) {
            ActionCard(
                title = "Reinstall All",
                subtitle = "${state.unknownInstallerCount} ${state.selectedType.name.lowercase()} apps not from Play Store. Fix them?",
                icon = R.drawable.apk_install,
                isWarning = true,
                onClick = onReinstallAll,
                onClose = { viewModel.dismissReinstallCard() }
            )
            Spacer(Modifier.height(12.dp))
        }

        // C. Portable Installer (Primary style card)
        ActionCard(
            title = "Install from File",
            subtitle = "Install APK, XAPK, APKS or Split bundles",
            icon = R.drawable.apk_install,
            isPrimary = true,
            onClick = {
                filePickerLauncher.launch(arrayOf("*/*"))
            }
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
                        text = "App Distribution",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "TOTAL: ${state.activeAppCount + state.frozenAppCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(24.dp))
                AppDistributionChart(
                    data = state.distributionData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                )
            }
        }

        // 4. Social Links
        Spacer(Modifier.height(24.dp))
        SocialLinksRow()
        Spacer(Modifier.height(32.dp))
    }

    // --- Dialogs ---
    if (showCacheDialog) {
        AlertDialog(
            onDismissRequest = { showCacheDialog = false },
            icon = { Icon(painterResource(R.drawable.clear_all), null) },
            title = { Text("Clear All Cache") },
            text = { Text("Which apps would you like to clear?") },
            confirmButton = {
                Button(onClick = {
                    onClearAllCache(AppListType.USER)
                    showCacheDialog = false
                }) { Text("User Apps") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    onClearAllCache(AppListType.SYSTEM)
                    showCacheDialog = false
                }) { Text("System Apps") }
            }
        )
    }

    if (showPrivilegeDialog) {
        AlertDialog(
            onDismissRequest = { showPrivilegeDialog = false },
            icon = { Icon(painterResource(R.drawable.privacy_tip), null) },
            title = { Text("Privilege Check") },
            text = {
                Text("Thor requires Root or Shizuku access to function correctly.\n\nPlease grant access in your manager app and click Refresh.")
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.loadDashboardData()
                    showPrivilegeDialog = false
                }) {
                    Text("Refresh")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPrivilegeDialog = false }) {
                    Text("Cancel")
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
                        contentDescription = "Dismiss",
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
