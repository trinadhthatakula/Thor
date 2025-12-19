package com.valhalla.thor.presentation.appList

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.compose.rememberAsyncImagePainter
import coil3.request.crossfade
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.presentation.utils.AppIconFetcher
import com.valhalla.thor.presentation.utils.AppIconKeyer
import com.valhalla.thor.presentation.utils.getAppIcon
import com.valhalla.thor.presentation.widgets.AppInfoDialog
import com.valhalla.thor.presentation.widgets.AppList
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    modifier: Modifier = Modifier,
    title: String = "App List",
    icon: Int = R.drawable.thor_mono,
    viewModel: AppListViewModel = koinViewModel(),
    // These actions bubble up to MainScreen/HomeViewModel for execution
    onAppAction: (AppClickAction) -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Create a custom ImageLoader that knows how to fetch App Icons in the background.
    // We use 'remember' so we don't recreate the loader on every recomposition.
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(AppIconKeyer())
                add(AppIconFetcher.Factory(context))
            }
            .crossfade(true)
            .build()
    }

    // UI-Specific State (Dialogs that don't need to persist in VM)
    var reinstallCandidate: AppInfo? by remember { mutableStateOf(null) }

    Column(modifier.fillMaxWidth()) {

        // 1. Header Row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(icon),
                contentDescription = title,
                modifier = Modifier
                    .padding(5.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .padding(8.dp)
            )
            Text(
                text = title,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .padding(vertical = 10.dp)
                    .weight(1f),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Start
            )

            // App Type Switcher (User / System)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(horizontal = 5.dp)) {
                AppListType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        selected = state.appListType == type,
                        onClick = { viewModel.updateListType(type) }
                    ) {
                        Icon(
                            painter = painterResource(if (type == AppListType.USER) R.drawable.apps else R.drawable.android),
                            contentDescription = type.name
                        )
                    }
                }
            }
        }

        // 2. The List Content
        val refreshState = rememberPullToRefreshState()

        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.loadApps() },
            state = refreshState,
            modifier = Modifier.weight(1f) // Fill remaining space
        ) {
            // Using your existing AppList widget, but feeding it PURE STATE
            AppList(
                appListType = state.appListType,
                installers = state.availableInstallers,
                selectedFilter = state.selectedFilter,
                filterType = state.filterType,
                sortBy = state.sortBy,
                sortOrder = state.sortOrder,
                appList = state.displayedApps,
                isRoot = state.isRoot,
                isShizuku = state.isShizuku,
                startAsGrid = true,
                imageLoader = imageLoader,
                // Actions forwarded to ViewModel
                onFilterTypeChanged = viewModel::updateFilterType,
                onSortByChanged = viewModel::updateSort,
                onSortOrderSelected = viewModel::updateSortOrder,
                onFilterSelected ={it?.let { filter ->
                    viewModel.updateFilter(filter)
                }},
                onAppInfoSelected = { appInfo ->
                    viewModel.selectApp(appInfo.packageName)
                },
                onMultiAppAction = onMultiAppAction
            )
        }
    }

    // --- DIALOGS ---

    // 1. Loading Dialog (When fetching heavy App Details)
    if (state.isLoadingDetails) {
        Dialog(onDismissRequest = { /* Prevent dismiss while loading */ }) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                CircularProgressIndicator()
            }
        }
    }

    // 2. App Info Dialog (Only shows when details are ready)
    state.selectedAppDetails?.let { details ->
        AppInfoDialog(
            appInfo = details,
            onDismiss = { viewModel.clearSelection() },
            isRoot = state.isRoot,
            isShizuku = state.isShizuku,
            onAppAction = { action ->
                if (action is AppClickAction.Reinstall) {
                    // Intercept Reinstall to show confirmation locally
                    reinstallCandidate = action.appInfo
                    // Don't dismiss main dialog yet? Or dismiss it?
                    // Typically we dismiss the info dialog to show the alert
                    viewModel.clearSelection()
                } else {
                    // Forward all other actions (Freeze, Kill, etc)
                    onAppAction(action)
                    viewModel.clearSelection()
                }
            }
        )
    }

    // 3. Reinstall Confirmation Alert
    reinstallCandidate?.let { app ->
        AlertDialog(
            icon = {
                Image(
                    painter = rememberAsyncImagePainter(getAppIcon(app.packageName, context)),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
            },
            onDismissRequest = { reinstallCandidate = null },
            title = { Text("Reinstall with Play Store?") },
            text = {
                Text(
                    "This will attempt to reinstall ${app.appName} using the Google Play Store.",
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAppAction(AppClickAction.Reinstall(app))
                    reinstallCandidate = null
                }) {
                    Text("Reinstall")
                }
            },
            dismissButton = {
                TextButton(onClick = { reinstallCandidate = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}