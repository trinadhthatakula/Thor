// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.basicMarquee
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontWeight
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.AppListType
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.core.net.toUri
import com.valhalla.asgard.components.AsgardBanner
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.thor.domain.model.GET_INSTALLED_APPS_PERMISSION
import com.valhalla.thor.domain.model.InstalledAppsPermission
import com.valhalla.thor.presentation.freezer.FreezerPrompt
import com.valhalla.thor.presentation.utils.ObserveAsEvents
import com.valhalla.thor.presentation.widgets.AppList
import com.valhalla.thor.presentation.widgets.FreezerPromptSnackbar
import com.valhalla.thor.data.manager.UsageAccessManager
import com.valhalla.thor.presentation.widgets.AppInfoSheet
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
    // Non-null only when this window actually has a detail pane to push the route into; null means
    // there is no second pane, so a tap opens AppInfoSheet in place instead. The host decides — see
    // MainScreen's hasDetailPane, which reads the pane count off the scaffold directive rather than
    // off a width breakpoint.
    onNavigateToAppInfo: ((packageName: String, appName: String) -> Unit)? = null,
    // These actions bubble up to MainScreen/HomeViewModel for execution
    onAppAction: (AppClickAction) -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The package, not the AppInfo, and rememberSaveable rather than remember: AppInfoSheet parks a
    // composable-scoped ViewModelStore on this entry's store and relies on re-entering composition
    // to release it, so the sheet has to come back after a configuration change. Resolving against
    // the live list also keeps the sheet's freeze/suspend state honest while it is open.
    //
    // Resolved against the whole scan, never `displayedApps` — the same shape FreezerScreen uses.
    // rememberSaveable outlives the process; `appListType` does not, it resets to USER. A package
    // picked off the system list would come back as a selection the filtered list cannot render and
    // no code path can clear, and would then spring the sheet open on a later, unrelated tap of the
    // System toggle. The filter is live, too: a background freeze or suspend that moves the app out
    // of an active State filter would tear the sheet out of composition mid-scroll, with no
    // dismissal animation and onDismiss never running.
    var selectedPackageForSheet by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedAppForSheet = selectedPackageForSheet?.let { pkg ->
        state.allUserApps.find { it.packageName == pkg }
            ?: state.allSystemApps.find { it.packageName == pkg }
    }
    // One-off freezer prompt is driven by a transient event; the screen holds its own visibility
    // state so it isn't replayed on recomposition/config change.
    var freezerPrompt by remember { mutableStateOf<FreezerPrompt?>(null) }

    // Resolved in composition: the event handler runs outside it and cannot call stringResource.
    val shareListTitle = stringResource(R.string.export_list_share)

    // Resolve installer identifiers to display strings here (keeps the ViewModel Context-free).
    val installerNameMap = remember(state.installerNameMap, context) {
        state.installerNameMap.mapValues { (_, label) -> label.asString(context) }
    }

    LaunchedEffect(Unit) {
        if (state.allUserApps.isEmpty() && state.allSystemApps.isEmpty() && state.isLoading) {
            // Screen entry: let the navigation transition settle before the scan starts.
            viewModel.loadApps(deferForTransition = true)
        }
    }

    // com.android.permission.GET_INSTALLED_APPS — an ordinary runtime permission on the ROMs that
    // define it, so an ordinary RequestPermission contract. This is the route for an unprivileged
    // user: a user who has already granted root or Shizuku will normally never reach it, because
    // SelfPermissionGranter takes every declared runtime permission as soon as a gateway is live —
    // asking someone to approve in a weaker form what they have already approved in a stronger one
    // is the ask this dialog exists to avoid, not to repeat.
    //
    // The result boolean is deliberately ignored and the truth re-read instead, the same way the
    // notification row does it in SettingsScreen. This permission is three-state on the ROMs that
    // define it, and a "while in use" grant answers `true` here and then stops being true the moment
    // Thor is backgrounded — which is precisely the state that truncates the package scan. Only the
    // checker's own read is worth believing.
    val installedAppsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.refreshInstalledAppsPermission()
    }

    // A grant made in system Settings never comes back through the launcher callback, and a
    // "while in use" grant silently lapses while Thor is away, so the state is re-read on every
    // resume rather than only after a request. Same idiom as the permissions section of
    // SettingsScreen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshInstalledAppsPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Handle one-off feedback (toasts + freezer prompt) delivered exactly once.
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is AppListEvent.ShowMessage ->
                Toast.makeText(context, event.message.asString(context), Toast.LENGTH_SHORT).show()

            is AppListEvent.ShowFreezerPrompt ->
                freezerPrompt = event.prompt

            is AppListEvent.ShareList -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = event.mime
                    putExtra(Intent.EXTRA_STREAM, event.uri.toUri())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, shareListTitle))
            }
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

            // 2. Package-visibility banner, above the search bar AppList draws below.
            //
            // Gated on Denied and nothing else. Unsupported is the state of every AOSP device Thor
            // runs on and must stay invisible there — the permission does not exist on a Pixel, so
            // there is nothing to grant and nothing to say. There is deliberately no rationale check
            // and no "permanently denied, open Settings" fallback either:
            // shouldShowRequestPermissionRationale() returns a hard false for a permission the
            // platform does not define, so the usual denied && !rationale recipe reads every Pixel
            // as permanently denied and nags forever. The availability probe upstream is what makes
            // the rationale question unnecessary rather than merely skipped.
            //
            // If the user denies, the banner simply stays and another tap re-prompts; whether a
            // dialog appears is the OS's call, not Thor's.
            AnimatedVisibility(
                visible = state.installedAppsPermission is InstalledAppsPermission.Denied,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                IncompleteAppListBanner(
                    onGrant = {
                        installedAppsPermissionLauncher.launch(GET_INSTALLED_APPS_PERMISSION)
                    }
                )
            }

            // 3. The List Content
            val refreshState = rememberPullToRefreshState()

            PullToRefreshBox(
                // isComputingSizes runs on a populated list, so surface it via the
                // pull-refresh spinner (the empty-state loader wouldn't show).
                // isManualRefreshing keeps the indicator readable: isLoading clears on the Room
                // cache emission, which lands long before the package rescan finishes.
                isRefreshing = state.isLoading || state.isComputingSizes || state.isManualRefreshing,
                // No deferForTransition: the user already made a deliberate gesture and nothing is
                // animating in, so the settle delay would just be dead spinner time.
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
                    // isLoadingPermissions counts as loading: while the sweep runs the Permission
                    // filter has no index, so every app is filtered out and the list would say
                    // "No matching apps found" — a wrong answer, not a pending one.
                    isLoading = state.isLoading || state.isComputingSizes ||
                            state.isLoadingPermissions,
                    appList = state.displayedApps,
                    isRoot = state.isRoot,
                    isShizuku = state.isShizuku,
                    isDhizuku = state.isDhizuku,
                    isGrid = state.isGrid,
                    gridDensity = state.gridDensity,
                    onToggleView = viewModel::toggleGridMode,
                    onExportList = viewModel::exportList,
                    onShareList = viewModel::shareList,
                    installerNameMap = installerNameMap,
                    permissionIndex = state.permissionIndex,
                    isLoadingPermissions = state.isLoadingPermissions,
                    permissionIndexFailed = state.permissionIndexFailed,
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
                        if (onNavigateToAppInfo != null) {
                            onNavigateToAppInfo(appInfo.packageName, appInfo.appName ?: "")
                        } else {
                            selectedPackageForSheet = appInfo.packageName
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

        selectedAppForSheet?.let { app ->
            AppInfoSheet(
                appInfo = app,
                isRoot = state.isRoot,
                isShizuku = state.isShizuku,
                isDhizuku = state.isDhizuku,
                isInFreezer = app.packageName in state.freezerPackageNames,
                onDismiss = { selectedPackageForSheet = null },
                onAppAction = { action ->
                    when {
                        // Freeze from the sheet goes through the local VM so it surfaces the
                        // "Frozen — Add to Freezer?" prompt instead of silently just disabling.
                        action is AppClickAction.Freeze ->
                            viewModel.freezeApp(action.appInfo.packageName, action.appInfo.appName, true)
                        else -> onAppAction(action)
                    }
                    // Deliberately no `selectedPackageForSheet = null` here. AppInfoSheet owns its
                    // own dismissal and already calls onDismiss() for every terminal action (launch,
                    // freeze, uninstall, clear data, fix store, manage permissions); the rest —
                    // suspend, force-stop, clear cache, share, system settings — are meant to leave
                    // the sheet up so you can see the result and keep going. Clearing
                    // unconditionally would close it for those too, and would do it by dropping the
                    // composable, so there'd be no exit animation either.
                    //
                    // Manage permissions belongs in the first list and was missing from it: the
                    // action is a destination push (ThorRoute.PermissionManager), and a sheet left
                    // standing behind a pushed screen re-materialises on the way back.
                },
                // No dismissal here, unlike the Freezer tab: this list is the whole scan, so the app
                // stays in it either way, and the selection resolves against allUserApps /
                // allSystemApps rather than a membership-derived list — nothing can yank the sheet
                // out from under the toggle.
                onToggleFreezerMembership = { viewModel.toggleFreezerMembership(app.packageName) }
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

/**
 * "App list may be incomplete" — shown only while [InstalledAppsPermission.Denied] holds.
 *
 * Same error-tinted treatment as `ReadOnlyBanner` on the permission-manager screen, because it makes
 * the same kind of claim: what is on screen is not the whole truth, and Thor cannot fix that on its
 * own. The [onGrant] button is the only route offered — no Settings deep link, because there is no
 * reliable way to tell "the user said no once" from "the dialog will not appear again" for a
 * permission AOSP has never heard of, and guessing wrong strands the user in a Settings screen with
 * no matching toggle.
 */
@Composable
private fun IncompleteAppListBanner(onGrant: () -> Unit) {
    AsgardBanner(
        title = stringResource(R.string.installed_apps_permission_title),
        description = stringResource(R.string.installed_apps_permission_desc),
        icon = ImageVector.vectorResource(R.drawable.warning),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        containerBrush = Brush.horizontalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
            )
        ),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        descriptionStyle = MaterialTheme.typography.bodySmall,
        action = {
            // The button's content colour is pinned rather than left to the TextButton default,
            // which is `primary` — a colour chosen to sit on the surface, not on this banner's
            // error-tinted gradient, and unreadable against it in several of Thor's themes.
            TextButton(
                onClick = onGrant,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) { Text(stringResource(R.string.installed_apps_permission_grant)) }
        }
    )
}