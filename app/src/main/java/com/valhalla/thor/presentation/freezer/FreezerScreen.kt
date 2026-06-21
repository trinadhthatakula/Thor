package com.valhalla.thor.presentation.freezer

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.SharedTransitionScope
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.MultiAppAction
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import com.valhalla.thor.presentation.common.components.ConnectedButtonGroup
import com.valhalla.thor.presentation.common.components.ConnectedButtonGroupItem
import com.valhalla.thor.presentation.widgets.AppInfoDialog
import com.valhalla.thor.presentation.widgets.AppItemGrid
import com.valhalla.thor.presentation.widgets.AppItemList
import com.valhalla.thor.presentation.widgets.AppSearchBar
import com.valhalla.thor.presentation.widgets.FreezerPromptSnackbar
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezerScreen(
    modifier: Modifier = Modifier,
    viewModel: FreezerViewModel = koinViewModel(),
    sharedTransitionScope: SharedTransitionScope? = null,
    onAppAction: (AppClickAction) -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasPrivilege = state.isRoot || state.isShizuku || state.isDhizuku

    var selectedPackageName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedAppInfo =
        selectedPackageName?.let { pkg -> state.freezerApps.find { it.packageName == pkg } }
    var showManageSheet by rememberSaveable { mutableStateOf(false) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var isGrid by rememberSaveable { mutableStateOf(true) }

    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var hasCheckedAutoPrompt by rememberSaveable { mutableStateOf(false) }

    val disabledAppsNotInFreezer = remember(state.allInstalledApps, state.freezerPackageNames) {
        state.allInstalledApps.filter { 
            !it.enabled && 
            it.packageName !in state.freezerPackageNames &&
            !it.isSystem
        }
    }

    LaunchedEffect(state.isLoading, state.hasShownDisabledAppsPrompt, disabledAppsNotInFreezer) {
        if (!state.isLoading && !state.hasShownDisabledAppsPrompt && !hasCheckedAutoPrompt && disabledAppsNotInFreezer.isNotEmpty()) {
            showImportDialog = true
            hasCheckedAutoPrompt = true
        }
    }

    val displayedApps = remember(state.freezerApps, state.searchQuery, state.appListType) {
        val filteredByType = state.freezerApps.filter { it.isSystem == (state.appListType == AppListType.SYSTEM) }
        if (state.searchQuery.isBlank()) filteredByType
        else filteredByType.filter {
            it.appName?.contains(state.searchQuery, ignoreCase = true) == true ||
                    it.packageName.contains(state.searchQuery, ignoreCase = true)
        }
    }


    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let {
            Toast.makeText(context, it.asString(context), Toast.LENGTH_SHORT).show()
            viewModel.dismissMessage()
        }
    }

    BackHandler(state.multiSelection.isNotEmpty()) {
        viewModel.clearSelection()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(modifier = modifier
            .fillMaxSize()
            .padding(innerPadding)) {
            Column(modifier = Modifier.fillMaxSize()) {

                // --- Header ---
                if (state.multiSelection.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = displayedApps.isNotEmpty() && displayedApps.all { it.packageName in state.multiSelection },
                            onCheckedChange = { checked ->
                                if (checked) viewModel.selectAll(displayedApps.map { it.packageName }) else viewModel.clearSelection()
                            }
                        )
                        Text(
                            text = stringResource(R.string.selected_count, state.multiSelection.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        )
                        FilledTonalIconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(painterResource(R.drawable.round_close), stringResource(R.string.cd_close))
                        }
                    }
                } else {
                    // Title + Freeze All
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.frozen),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.freezer),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = (-1).sp
                            )
                        }
                        ConnectedButtonGroup(
                            items = AppListType.entries.map { type ->
                                ConnectedButtonGroupItem.Icon(
                                    iconRes = if (type == AppListType.USER) R.drawable.apps else R.drawable.android,
                                    contentDescription = stringResource(
                                        if (type == AppListType.USER) R.string.chip_user else R.string.chip_system
                                    )
                                )
                            },
                            selectedIndex = AppListType.entries.indexOf(state.appListType),
                            onItemSelected = { viewModel.updateListType(AppListType.entries[it]) }
                        )
                    }

                    // Search bar — config icon opens settings sheet
                    AppSearchBar(
                        query = state.searchQuery,
                        onQueryChange = viewModel::updateSearchQuery,
                        onOpenConfig = { showSettingsSheet = true }
                    )
                }

                // --- App List / Empty State ---
                val animatedVisibilityScope = LocalNavAnimatedContentScope.current
                if (displayedApps.isEmpty() && !state.isLoading) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.frozen),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(
                                if (state.freezerApps.isEmpty()) "No apps in Freezer"
                                else "No matching apps",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.freezerApps.isEmpty()) {
                                Text(
                                    "Tap + to add apps",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                } else if (isGrid) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(
                            displayedApps.sortedBy { it.appName },
                            key = { it.packageName }) { app ->
                            AppItemGrid(
                                app = app,
                                isSelected = app.packageName in state.multiSelection,
                                onClick = {
                                    if (state.multiSelection.isNotEmpty())
                                        viewModel.toggleSelection(app.packageName)
                                    else
                                        selectedPackageName = app.packageName
                                },
                                onLongClick = { viewModel.toggleSelection(app.packageName) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(
                            displayedApps.sortedBy { it.appName },
                            key = { it.packageName }) { app ->
                            AppItemList(
                                app = app,
                                isSelected = app.packageName in state.multiSelection,
                                onClick = {
                                    if (state.multiSelection.isNotEmpty())
                                        viewModel.toggleSelection(app.packageName)
                                    else
                                        selectedPackageName = app.packageName
                                },
                                onLongClick = { viewModel.toggleSelection(app.packageName) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    }
                }
            }

            // Frozen prompt snackbar
            FreezerPromptSnackbar(
                visible = state.freezerPrompt != null,
                appName = state.freezerPrompt?.appName,
                onAddToFreezer = {
                    state.freezerPrompt?.let { viewModel.addToFreezer(it.packageName) }
                },
                onDismiss = viewModel::dismissFreezerPrompt,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            )

            // Floating multi-select toolbar
            if (state.multiSelection.isNotEmpty()) {
                val selectedApps =
                    state.freezerApps.filter { it.packageName in state.multiSelection }
                FreezerSelectToolBox(
                    selected = selectedApps,
                    isRoot = state.isRoot,
                    isShizuku = state.isShizuku,
                    isDhizuku = state.isDhizuku,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                    onCancel = { viewModel.clearSelection() },
                    onRemoveFromFreezer = {
                        viewModel.removeFromFreezer(state.multiSelection)
                    },
                    onMultiAppAction = { action ->
                        viewModel.clearSelection()
                        onMultiAppAction(action)
                    }
                )
            }

            // Floating toolbar for Add, Freeze, Unfreeze
            if (state.multiSelection.isEmpty()) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                        toolbarContainerColor = MaterialTheme.colorScheme.primary,
                        toolbarContentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 16.dp, end = 16.dp),
                    content = {
                        val iconButtonColors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f)
                        )
                        IconButton(
                            onClick = { viewModel.freezeAll() },
                            enabled = state.freezerApps.any { it.enabled } && hasPrivilege,
                            colors = iconButtonColors
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.frozen),
                                contentDescription = stringResource(R.string.action_freeze)
                            )
                        }
                        IconButton(
                            onClick = { showManageSheet = true },
                            colors = iconButtonColors
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddCircle,
                                contentDescription = stringResource(R.string.add_to_freezer)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.unfreezeAll() },
                            enabled = state.freezerApps.any { !it.enabled } && hasPrivilege,
                            colors = iconButtonColors
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.unfreeze),
                                contentDescription = stringResource(R.string.action_unfreeze)
                            )
                        }
                    }
                )
            }
        }
    }

    // AppInfoDialog
    selectedAppInfo?.let { app ->
        AppInfoDialog(
            appInfo = app,
            isRoot = state.isRoot,
            isShizuku = state.isShizuku,
            isDhizuku = state.isDhizuku,
            onDismiss = { selectedPackageName = null },
            onAppAction = { action ->
                when (action) {
                    is AppClickAction.Freeze -> {
                        viewModel.freezeSingleApp(
                            app.packageName,
                            app.appName,
                            inFreezer = app.packageName in state.freezerPackageNames
                        )
                        selectedPackageName = null
                    }

                    is AppClickAction.UnFreeze -> {
                        viewModel.unfreezeSingleApp(app.packageName, app.appName)
                        selectedPackageName = null
                    }

                    else -> {
                        onAppAction(action)
                        selectedPackageName = null
                    }
                }
            }
        )
    }

    if (showManageSheet) {
        ManageFreezerSheet(
            allApps = state.allInstalledApps,
            freezerPackageNames = state.freezerPackageNames,
            searchQuery = state.manageSheetSearchQuery,
            onSearchChange = viewModel::updateManageSheetSearch,
            onToggle = { pkg, add -> viewModel.toggleManaged(pkg, add) },
            onDismiss = { showManageSheet = false }
        )
    }

    if (showSettingsSheet) {
        FreezerSettingsSheet(
            isGrid = isGrid,
            autoFreezeEnabled = state.autoFreezeEnabled,
            hasPrivilege = hasPrivilege,
            showImportDisabledApps = disabledAppsNotInFreezer.isNotEmpty(),
            appListType = state.appListType,
            onToggleView = { isGrid = !isGrid },
            onToggleAutoFreeze = viewModel::setAutoFreezeEnabled,
            onDismiss = { showSettingsSheet = false },
            onUnfreezeAll = viewModel::unfreezeAll,
            onImportDisabledApps = {
                showSettingsSheet = false
                if (disabledAppsNotInFreezer.isNotEmpty()) {
                    showImportDialog = true
                } else {
                    Toast.makeText(context, context.getString(R.string.no_disabled_apps_found), Toast.LENGTH_SHORT).show()
                }
            },
            onListTypeChanged = viewModel::updateListType
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.markDisabledAppsPromptShown()
                showImportDialog = false
            },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.frozen),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.import_disabled_apps_title)) },
            text = { Text(stringResource(R.string.import_disabled_apps_desc, disabledAppsNotInFreezer.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addAppsToFreezer(disabledAppsNotInFreezer.map { it.packageName })
                        viewModel.markDisabledAppsPromptShown()
                        showImportDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.markDisabledAppsPromptShown()
                        showImportDialog = false
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
