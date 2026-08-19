// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup.hub

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.data.source.local.room.AppEntity
import com.valhalla.thor.domain.repository.BackupArchiveItem
import com.valhalla.thor.domain.repository.BackupArchiveKind
import com.valhalla.thor.presentation.home.components.BentoTile
import com.valhalla.thor.presentation.widgets.AppIcon
import java.text.DateFormat
import java.util.Date
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreHubScreen(
    onBack: () -> Unit,
    onOpenBackupSheet: (packageName: String, appLabel: String) -> Unit,
    onOpenRestoreSheet: (uriString: String?) -> Unit,
    viewModel: BackupRestoreHubViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val safFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onOpenRestoreSheet(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.backup_and_restore),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshArchives() }) {
                        Icon(
                            painter = painterResource(R.drawable.clear_all),
                            contentDescription = stringResource(R.string.cd_clear),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                windowInsets = WindowInsets(0,0,0,0)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0,0,0,0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // 1. HERO ACTION CARDS
            HeroActionCards(
                onBackupClick = { viewModel.showAppPicker() },
                onRestoreClick = { safFilePickerLauncher.launch(arrayOf("*/*")) },
            )

            // 2. BACKUPS SECTION HEADER
            BackupsSectionHeader(
                totalCount = state.archives.size,
                totalSizeBytes = state.totalSizeBytes,
            )

            // 3. DYNAMIC FILTER CHIPS (Visible only when both kinds are present)
            if (state.showFilterChips) {
                DynamicFilterChips(
                    activeFilter = state.activeFilter,
                    onFilterSelect = { viewModel.setFilter(it) },
                )
            }

            // 4. ARCHIVES LIST OR EMPTY STATE
            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else if (state.archives.isEmpty()) {
                EmptyStateKnowledgeCards()
            } else {
                val itemsToRender = state.filteredArchives
                if (itemsToRender.isEmpty()) {
                    Text(
                        text = stringResource(R.string.backup_hub_no_archives),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.animateContentSize(),
                    ) {
                        itemsToRender.forEach { item ->
                            ArchiveItemCard(
                                item = item,
                                onRestore = { onOpenRestoreSheet(item.uriString) },
                                onShare = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "*/*"
                                        putExtra(Intent.EXTRA_STREAM, item.uriString.toUri())
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, item.displayName))
                                },
                                onDelete = { viewModel.requestDeleteArchive(item) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Modal App Picker Sheet
    if (state.isAppPickerVisible) {
        AppPickerBottomSheet(
            searchQuery = state.appPickerSearchQuery,
            apps = state.filteredInstalledApps,
            onSearchChange = { viewModel.updateAppPickerSearch(it) },
            onAppSelect = { app ->
                viewModel.hideAppPicker()
                onOpenBackupSheet(app.packageName, app.appName ?: app.packageName)
            },
            onDismiss = { viewModel.hideAppPicker() },
        )
    }

    // Delete Confirmation Dialog
    state.archiveToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteArchive() },
            title = { Text(stringResource(R.string.backup_hub_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.backup_hub_delete_confirm_message,
                        target.displayName,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmDeleteArchive() }
                ) {
                    Text(
                        stringResource(R.string.backup_hub_action_delete),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteArchive() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun HeroActionCards(
    onBackupClick: () -> Unit,
    onRestoreClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BentoTile(
            title = stringResource(R.string.backup_hub_hero_backup_title),
            subtitle = stringResource(R.string.backup_hub_hero_backup_desc),
            icon = R.drawable.settings_backup_restore,
            isPrimary = true,
            showSubtitle = true,
            onClick = onBackupClick,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )

        BentoTile(
            title = stringResource(R.string.backup_hub_hero_restore_title),
            subtitle = stringResource(R.string.backup_hub_hero_restore_desc),
            icon = R.drawable.apk_install,
            isPrimary = false,
            showSubtitle = true,
            onClick = onRestoreClick,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun BackupsSectionHeader(
    totalCount: Int,
    totalSizeBytes: Long,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = stringResource(R.string.backup_hub_section_backups),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Downloads/Thor",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (totalCount > 0) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = "$totalCount (${Formatter.formatShortFileSize(context, totalSizeBytes)})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DynamicFilterChips(
    activeFilter: BackupHubFilter,
    onFilterSelect: (BackupHubFilter) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = activeFilter == BackupHubFilter.ALL,
            onClick = { onFilterSelect(BackupHubFilter.ALL) },
            label = { Text(stringResource(R.string.backup_hub_filter_all)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        )
        FilterChip(
            selected = activeFilter == BackupHubFilter.DATA_BACKUPS,
            onClick = { onFilterSelect(BackupHubFilter.DATA_BACKUPS) },
            label = { Text(stringResource(R.string.backup_hub_filter_backups)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        )
        FilterChip(
            selected = activeFilter == BackupHubFilter.APP_BUNDLES,
            onClick = { onFilterSelect(BackupHubFilter.APP_BUNDLES) },
            label = { Text(stringResource(R.string.backup_hub_filter_bundles)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        )
    }
}

@Composable
private fun ArchiveItemCard(
    item: BackupArchiveItem,
    onRestore: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val formattedSize = remember(item.sizeBytes) { Formatter.formatShortFileSize(context, item.sizeBytes) }
    val formattedDate = remember(item.dateModifiedEpochSec) {
        if (item.dateModifiedEpochSec > 0) {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.dateModifiedEpochSec * 1000))
        } else {
            ""
        }
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Leading Type Tag
                val isDataBackup = item.kind == BackupArchiveKind.DATA_BACKUP
                Surface(
                    shape = CircleShape,
                    color = if (isDataBackup) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                ) {
                    Text(
                        text = if (isDataBackup) {
                            stringResource(R.string.backup_hub_badge_data_backup)
                        } else {
                            stringResource(R.string.backup_hub_badge_app_bundle)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isDataBackup) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }

                Text(
                    text = "$formattedSize · $formattedDate",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // File Name & Package
            Column {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!item.packageName.isNullOrBlank()) {
                    Text(
                        text = item.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.share),
                            contentDescription = stringResource(R.string.backup_hub_action_share),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.delete),
                            contentDescription = stringResource(R.string.backup_hub_action_delete),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onRestore,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.settings_backup_restore),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.job_restoring),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStateKnowledgeCards() {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Main Header Banner
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.settings_backup_restore),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.backup_hub_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = stringResource(R.string.backup_hub_empty_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Educational Knowledge Tip Cards
        TipCard(
            title = stringResource(R.string.backup_hub_tip_thorbak_title),
            desc = stringResource(R.string.backup_hub_tip_thorbak_desc),
            iconRes = R.drawable.settings_backup_restore,
        )

        TipCard(
            title = stringResource(R.string.backup_hub_tip_bundle_title),
            desc = stringResource(R.string.backup_hub_tip_bundle_desc),
            iconRes = R.drawable.apk_install,
        )

        TipCard(
            title = stringResource(R.string.backup_hub_tip_discovery_title),
            desc = stringResource(R.string.backup_hub_tip_discovery_desc),
            iconRes = R.drawable.clear_all,
        )
    }
}

@Composable
private fun TipCard(
    title: String,
    desc: String,
    iconRes: Int,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 2.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerBottomSheet(
    searchQuery: String,
    apps: List<AppEntity>,
    onSearchChange: (String) -> Unit,
    onAppSelect: (AppEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.backup_hub_pick_app_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text(stringResource(R.string.backup_hub_search_apps_placeholder)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.round_search),
                        contentDescription = null,
                    )
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(apps, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onAppSelect(app) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AppIcon(
                            packageName = app.packageName,
                            isEnabled = app.enabled,
                            isSuspended = app.isSuspended,
                            size = 40.dp,
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.appName ?: app.packageName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        app.versionName?.let { ver ->
                            Text(
                                text = ver,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
