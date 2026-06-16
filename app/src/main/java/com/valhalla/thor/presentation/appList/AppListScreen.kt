package com.valhalla.thor.presentation.appList

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.presentation.widgets.AppList
import com.valhalla.thor.presentation.widgets.FreezerPromptSnackbar
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.apps),
    icon: Int = R.drawable.thor_mono,
    viewModel: AppListViewModel = koinViewModel(),
    sharedTransitionScope: SharedTransitionScope,
    onNavigateToAppInfo: (packageName: String, appName: String) -> Unit,
    // These actions bubble up to MainScreen/HomeViewModel for execution
    onAppAction: (AppClickAction) -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (state.allUserApps.isEmpty() && state.allSystemApps.isEmpty() && state.isLoading) {
            viewModel.loadApps()
        }
    }

    // Handle Feedback (Toasts)
    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { message ->
            Toast.makeText(context, message.asString(context), Toast.LENGTH_SHORT).show()
            viewModel.dismissMessage()
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = (-1).sp
                    )
                }

                // RIGHT: App Type Switcher moved to config
                Spacer(Modifier.width(48.dp))
            }

            // 2. The List Content
            val refreshState = rememberPullToRefreshState()

            PullToRefreshBox(
                isRefreshing = state.isLoading,
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
                    isLoading = state.isLoading,
                    appList = state.displayedApps,
                    isRoot = state.isRoot,
                    isShizuku = state.isShizuku,
                    isDhizuku = state.isDhizuku,
                    startAsGrid = true,
                    installerNameMap = state.installerNameMap,
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
                        onNavigateToAppInfo(appInfo.packageName, appInfo.appName ?: "")
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
    }
}