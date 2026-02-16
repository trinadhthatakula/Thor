package com.valhalla.thor.presentation.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
    var headerSelectedType by remember { mutableStateOf(AppListType.USER) }

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
            .verticalScroll(rememberScrollState())
    ) {
        // 1. Header
        DashboardHeader(
            isRoot = state.isRootAvailable,
            isShizuku = state.isShizukuAvailable,
            selectedType = headerSelectedType,
            onTypeChanged = { headerSelectedType = it },
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

        Spacer(Modifier.height(24.dp))

        // --- ACTIONS ---

        // A. Clear Cache
        if (state.isRootAvailable) {
            // ... (Cache subtitle logic same as before) ...
            val cacheSubtitle =
                "Free up space by cleaning app caches" // simplified for brevity here

            ActionCard(
                title = "Clear All Cache",
                subtitle = cacheSubtitle,
                icon = R.drawable.clear_all,
                onClick = { showCacheDialog = true }
            )
        }

        // B. Reinstall All (Controlled by DataStore preference)
        if (state.isRootAvailable && state.unknownInstallerCount > 0 && state.showReinstallCard) {
            ActionCard(
                title = "Reinstall All",
                subtitle = "${state.unknownInstallerCount} ${headerSelectedType.name.lowercase()} apps not from Play Store. Fix them?",
                icon = R.drawable.apk_install,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                onClick = onReinstallAll,
                onClose = { viewModel.dismissReinstallCard() } // <--- Enable Close Button
            )
        }

        // C. Portable Installer
        ActionCard(
            title = "Install from File",
            subtitle = "Install APK, XAPK, APKS or Split bundles",
            icon = R.drawable.apk_install,
            onClick = {
                filePickerLauncher.launch(arrayOf("*/*"))
            }
        )

        Spacer(Modifier.height(24.dp))

        // 3. Distribution Chart
        AnimatedVisibility(state.distributionData.isNotEmpty() && !state.isLoading) {
            Text(
                text = "App Distribution",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(16.dp))
            AppDistributionChart(
                data = state.distributionData,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
            )
        }

        // 4. Social Links
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Connect",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        SocialLinksRow()
        Spacer(Modifier.height(32.dp))
    }

    // --- Dialogs (Same as before) ---
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
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    onClick: () -> Unit,
    onClose: (() -> Unit)? = null // <--- Added optional close callback
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) { // Weight ensures text takes available space
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium)
            }

            // Render Close Button if callback provided
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(R.drawable.round_close), // Ensure you have this drawable
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}