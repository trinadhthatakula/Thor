// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.basicMarquee
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontWeight
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.thor.presentation.freezer.FreezerPrompt
import com.valhalla.thor.presentation.utils.ObserveAsEvents
import com.valhalla.thor.presentation.widgets.AppList
import com.valhalla.thor.presentation.widgets.FreezerPromptSnackbar
import com.valhalla.thor.data.manager.UsageAccessManager
import com.valhalla.thor.presentation.widgets.AppInfoDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.apps),
    icon: Int = R.drawable.thor_mono,
    viewModel: AppListViewModel = koinViewModel(),
    sharedTransitionScope: SharedTransitionScope? = null,
    onNavigateToAppInfo: (packageName: String, appName: String) -> Unit,
    // These actions bubble up to MainScreen/HomeViewModel for execution
    onAppAction: (AppClickAction) -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedAppForDialog by remember { mutableStateOf<AppInfo?>(null) }
    // One-off freezer prompt is driven by a transient event; the screen holds its own visibility
    // state so it isn't replayed on recomposition/config change.
    var freezerPrompt by remember { mutableStateOf<FreezerPrompt?>(null) }

    // Resolve installer identifiers to display strings here (keeps the ViewModel Context-free).
    val installerNameMap = remember(state.installerNameMap, context) {
        state.installerNameMap.mapValues { (_, label) -> label.asString(context) }
    }

    LaunchedEffect(Unit) {
        if (state.allUserApps.isEmpty() && state.allSystemApps.isEmpty() && state.isLoading) {
            viewModel.loadApps()
        }
    }

    // Handle one-off feedback (toasts + freezer prompt) delivered exactly once.
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is AppListEvent.ShowMessage ->
                Toast.makeText(context, event.message.asString(context), Toast.LENGTH_SHORT).show()

            is AppListEvent.ShowFreezerPrompt ->
                freezerPrompt = event.prompt
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {

            // 1. Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // LEFT: Brand/Title Block
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = title,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = (-1).sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).basicMarquee()
                    )
                }

                // RIGHT: Connected button group to switch between App List Types
                ConnectedButtonGroup(
                    items = AppListType.entries.map { type ->
                        ConnectedButtonGroupItem.Icon(
                            icon = ImageVector.vectorResource(if (type == AppListType.USER) R.drawable.apps else R.drawable.android),
                            contentDescription = stringResource(
                                if (type == AppListType.USER) R.string.chip_user else R.string.chip_system
                            )
                        )
                    },
                    selectedIndex = AppListType.entries.indexOf(state.appListType),
                    onItemSelected = { viewModel.updateListType(AppListType.entries[it]) },
                    modifier = Modifier.width(IntrinsicSize.Max)
                )
            }

            // 2. The List Content
            val refreshState = rememberPullToRefreshState()

            PullToRefreshBox(
                // isComputingSizes runs on a populated list, so surface it via the
                // pull-refresh spinner (the empty-state loader wouldn't show).
                isRefreshing = state.isLoading || state.isComputingSizes,
                onRefresh = { viewModel.loadApps() },
                state = refreshState,
                modifier = Modifier.weight(1f) // Fill remaining space
            ) {
                val animatedVisibilityScope = LocalNavAnimatedContentScope.current
                // Using your existing AppList widget, but feeding it PURE STATE
                AppList(
                    appListType = state.appListType,
                    installers = state.availableInstallers,
                    selectedFilter = state.selectedFilter,
                    filterType = state.filterType,
                    sortBy = state.sortBy,
                    sortOrder = state.sortOrder,
                    searchQuery = state.searchQuery,
                    isLoading = state.isLoading || state.isComputingSizes,
                    appList = state.displayedApps,
                    isRoot = state.isRoot,
                    isShizuku = state.isShizuku,
                    isDhizuku = state.isDhizuku,
                    isGrid = state.isGrid,
                    onToggleView = viewModel::toggleGridMode,
                    installerNameMap = installerNameMap,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    // Actions forwarded to ViewModel
                    onFilterTypeChanged = viewModel::updateFilterType,
                    onSortByChanged = viewModel::updateSort,
                    onSortOrderSelected = viewModel::updateSortOrder,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onFilterSelected = {
                        it?.let { filter ->
                            viewModel.updateFilter(filter)
                        }
                    },
                    onAppInfoSelected = { appInfo ->
                        if (state.useDetailedView) {
                            onNavigateToAppInfo(appInfo.packageName, appInfo.appName ?: "")
                        } else {
                            selectedAppForDialog = appInfo
                        }
                    },
                    onListTypeChanged = { viewModel.updateListType(it) },
                    onMultiAppAction = { action ->
                        if (action is MultiAppAction.Freeze || action is MultiAppAction.UnFreeze) {
                            viewModel.performMultiAction(action)
                        } else {
                            onMultiAppAction(action)
                        }
                    }
                )
            }
        }
        FreezerPromptSnackbar(
            visible = freezerPrompt != null,
            appName = freezerPrompt?.appName,
            onAddToFreezer = {
                freezerPrompt?.let { viewModel.addToFreezer(it.packageName) }
                freezerPrompt = null
            },
            onDismiss = { freezerPrompt = null },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )

        selectedAppForDialog?.let { app ->
            AppInfoDialog(
                appInfo = app,
                isRoot = state.isRoot,
                isShizuku = state.isShizuku,
                isDhizuku = state.isDhizuku,
                onDismiss = { selectedAppForDialog = null },
                onAppAction = { action ->
                    when {
                        action is AppClickAction.OpenDetails ->
                            onNavigateToAppInfo(app.packageName, app.appName ?: "")
                        // Freeze from the dialog goes through the local VM so it surfaces the
                        // "Frozen — Add to Freezer?" prompt instead of silently just disabling.
                        action is AppClickAction.Freeze ->
                            viewModel.freezeApp(action.appInfo.packageName, action.appInfo.appName, true)
                        else -> onAppAction(action)
                    }
                    selectedAppForDialog = null
                }
            )
        }

        val usageAccessManager = koinInject<UsageAccessManager>()
        if (state.needsUsageAccessPrompt) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissUsageAccessPrompt() },
                title = { Text(stringResource(R.string.usage_access_needed_title)) },
                text = { Text(stringResource(R.string.usage_access_prompt_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching { context.startActivity(usageAccessManager.usageAccessIntent()) }
                        viewModel.dismissUsageAccessPrompt()
                    }) { Text(stringResource(R.string.open_settings)) }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissUsageAccessPrompt() }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }
}