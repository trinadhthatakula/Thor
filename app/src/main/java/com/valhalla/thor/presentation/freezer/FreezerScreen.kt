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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.request.crossfade
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.presentation.utils.AppIconFetcher
import com.valhalla.thor.presentation.utils.AppIconKeyer
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
    onAppAction: (AppClickAction) -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedPackageName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedAppInfo = selectedPackageName?.let { pkg -> state.freezerApps.find { it.packageName == pkg } }
    var showManageSheet by rememberSaveable { mutableStateOf(false) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var isGrid by rememberSaveable { mutableStateOf(true) }

    val displayedApps = remember(state.freezerApps, state.searchQuery) {
        if (state.searchQuery.isBlank()) state.freezerApps
        else state.freezerApps.filter {
            it.appName?.contains(state.searchQuery, ignoreCase = true) == true ||
                    it.packageName.contains(state.searchQuery, ignoreCase = true)
        }
    }

    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(AppIconKeyer())
                add(AppIconFetcher.Factory(context))
            }
            .crossfade(true)
            .build()
    }

    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.dismissMessage()
        }
    }

    BackHandler(state.multiSelection.isNotEmpty()) {
        viewModel.clearSelection()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (state.multiSelection.isEmpty()) {
                FloatingActionButton(
                    onClick = { showManageSheet = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = "Manage Freezer")
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = modifier.fillMaxSize().padding(innerPadding)) {
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
                            checked = state.multiSelection.size == state.freezerApps.size && state.freezerApps.isNotEmpty(),
                            onCheckedChange = { checked ->
                                if (checked) viewModel.selectAll() else viewModel.clearSelection()
                            }
                        )
                        Text(
                            text = "${state.multiSelection.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                        FilledTonalIconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(painterResource(R.drawable.round_close), "Close")
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
                        Button(
                            onClick = { viewModel.freezeAll() },
                            shape = RoundedCornerShape(12.dp),
                            enabled = state.freezerApps.isNotEmpty()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.frozen),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Freeze All")
                        }
                    }

                    // Search bar — config icon opens settings sheet
                    AppSearchBar(
                        query = state.searchQuery,
                        onQueryChange = viewModel::updateSearchQuery,
                        onOpenConfig = { showSettingsSheet = true }
                    )
                }

                // --- App List / Empty State ---
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
                        items(displayedApps.sortedBy { it.appName }, key = { it.packageName }) { app ->
                            AppItemGrid(
                                app = app,
                                isSelected = app.packageName in state.multiSelection,
                                imageLoader = imageLoader,
                                onClick = {
                                    if (state.multiSelection.isNotEmpty())
                                        viewModel.toggleSelection(app.packageName)
                                    else
                                        selectedPackageName = app.packageName
                                },
                                onLongClick = { viewModel.toggleSelection(app.packageName) }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(displayedApps.sortedBy { it.appName }, key = { it.packageName }) { app ->
                            AppItemList(
                                app = app,
                                isSelected = app.packageName in state.multiSelection,
                                imageLoader = imageLoader,
                                onClick = {
                                    if (state.multiSelection.isNotEmpty())
                                        viewModel.toggleSelection(app.packageName)
                                    else
                                        selectedPackageName = app.packageName
                                },
                                onLongClick = { viewModel.toggleSelection(app.packageName) }
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
                val selectedApps = state.freezerApps.filter { it.packageName in state.multiSelection }
                FreezerSelectToolBox(
                    selected = selectedApps,
                    isRoot = state.isRoot,
                    isShizuku = state.isShizuku,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding( bottom = 16.dp),
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
        }
    }

    // AppInfoDialog
    selectedAppInfo?.let { app ->
        AppInfoDialog(
            appInfo = app,
            isRoot = state.isRoot,
            isShizuku = state.isShizuku,
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
            imageLoader = imageLoader,
            onSearchChange = viewModel::updateManageSheetSearch,
            onToggle = { pkg, add -> viewModel.toggleManaged(pkg, add) },
            onDismiss = { showManageSheet = false }
        )
    }

    if (showSettingsSheet) {
        FreezerSettingsSheet(
            isGrid = isGrid,
            autoFreezeEnabled = state.autoFreezeEnabled,
            onToggleView = { isGrid = !isGrid },
            onToggleAutoFreeze = viewModel::setAutoFreezeEnabled,
            onDismiss = { showSettingsSheet = false },
            onUnfreezeAll = viewModel::unfreezeAll
        )
    }
}
