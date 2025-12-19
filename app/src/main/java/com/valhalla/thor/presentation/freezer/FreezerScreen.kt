package com.valhalla.thor.presentation.freezer

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
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
import com.valhalla.thor.presentation.widgets.AppList
import com.valhalla.thor.presentation.widgets.AppInfoDialog
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

    // Local State for Dialogs
    var selectedAppInfo by remember { mutableStateOf<AppInfo?>(null) }
    var reinstallCandidate by remember { mutableStateOf<AppInfo?>(null) }

    LaunchedEffect(Unit) {
        if (state.allUserApps.isEmpty() && state.allSystemApps.isEmpty() && state.isLoading) {
            viewModel.loadApps()
        }
    }

    // Create a custom ImageLoader that knows how to fetch App Icons in the background.
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(AppIconKeyer())
                add(AppIconFetcher.Factory(context))
            }
            .crossfade(true)
            .build()
    }

    // Handle Feedback (Toasts from ViewModel actions)
    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.dismissMessage()
        }
    }

    Column(modifier.fillMaxWidth()) {

        // --- Header ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.frozen),
                contentDescription = "Freezer",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(5.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .padding(8.dp)
            )
            Text(
                text = "Deep Freezer",
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .padding(vertical = 10.dp)
                    .weight(1f),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Start
            )

            // App Source Switcher
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

        // --- List Content ---
        val refreshState = rememberPullToRefreshState()

        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.loadApps() },
            state = refreshState,
            modifier = Modifier.weight(1f)
        ) {
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
                imageLoader = imageLoader,
                // Filtering / Sorting Actions
                onFilterTypeChanged = viewModel::updateFilterType,
                onSortByChanged = viewModel::updateSort,
                onSortOrderSelected = viewModel::updateSortOrder,
                onFilterSelected ={ it?.let { filter-> viewModel.updateFilter(filter) }},
                // Multi-Selection Actions
                onMultiAppAction = { action ->
                    if (action is MultiAppAction.Freeze || action is MultiAppAction.UnFreeze) {
                        viewModel.performMultiAction(action)
                    } else {
                        onMultiAppAction(action) // Forward others (e.g. Uninstall All)
                    }
                },
                // Single App Selection (Opens Dialog)
                onAppInfoSelected = { app ->
                    selectedAppInfo = app
                }
            )
        }
    }

    // --- DIALOGS ---

    // 1. App Info Dialog
    selectedAppInfo?.let { app ->
        AppInfoDialog(
            appInfo = app,
            isRoot = state.isRoot,
            isShizuku = state.isShizuku,
            onDismiss = { selectedAppInfo = null },
            onAppAction = { action ->
                when (action) {
                    // CASE A: Local Logic (Freeze/Unfreeze)
                    is AppClickAction.Freeze -> {
                        viewModel.toggleAppFreezeState(action.appInfo)
                        selectedAppInfo = null
                    }
                    is AppClickAction.UnFreeze -> {
                        viewModel.toggleAppFreezeState(action.appInfo)
                        selectedAppInfo = null
                    }
                    // CASE B: Reinstall (Needs Confirmation)
                    is AppClickAction.Reinstall -> {
                        reinstallCandidate = action.appInfo
                        selectedAppInfo = null // Dismiss info dialog, show confirmation
                    }
                    // CASE C: Forward everything else (Launch, Uninstall, etc.)
                    else -> {
                        onAppAction(action)
                        selectedAppInfo = null
                    }
                }
            }
        )
    }

    // 2. Reinstall Confirmation (Matches AppListScreen behavior)
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
            title = { Text("Reinstall App?") },
            text = {
                Text(
                    "Do you want to reinstall ${app.appName} using the Google Play Store?",
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAppAction(AppClickAction.Reinstall(app)) // Forward to Main -> HomeViewModel
                    reinstallCandidate = null
                }) {
                    Text("Yes")
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