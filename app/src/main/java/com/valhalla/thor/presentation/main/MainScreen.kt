// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.main

import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.snap
import com.valhalla.thor.domain.model.AnimationIntensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.DefaultTab
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.repository.InstallerLabelResolver
import com.valhalla.thor.presentation.appList.AppInfoDetailsScreen
import com.valhalla.thor.presentation.appList.AppListScreen
import com.valhalla.thor.presentation.appList.AppListViewModel
import com.valhalla.thor.presentation.freezer.FreezerScreen
import com.valhalla.thor.presentation.freezer.FreezerViewModel
import com.valhalla.thor.presentation.home.AppDestinations
import com.valhalla.thor.presentation.home.HomeScreen
import com.valhalla.thor.presentation.home.HomeViewModel
import com.valhalla.thor.presentation.navigation.ThorRoute
import com.valhalla.asgard.navigation.AsgardNavItem
import com.valhalla.asgard.navigation.AsgardNavigationBar
import com.valhalla.asgard.navigation.AsgardNavigationRail
import com.valhalla.thor.presentation.permission.PermissionManagerScreen
import com.valhalla.thor.presentation.settings.SettingsScreen
import com.valhalla.thor.presentation.backup.AppBackupSheet
import com.valhalla.thor.presentation.backup.ArchiveRestoreSheet
import com.valhalla.thor.presentation.extension.ExtensionBrowseScreen
import com.valhalla.thor.presentation.extension.ExtensionManagerScreen
import com.valhalla.thor.presentation.settings.BillingProcessor
import com.valhalla.thor.presentation.settings.SupportDeveloperHelper
import com.valhalla.thor.presentation.widgets.AffirmationDialog
import com.valhalla.thor.presentation.widgets.ClearAllCacheSheet
import com.valhalla.thor.presentation.widgets.MultiAppAffirmationDialog
import com.valhalla.thor.presentation.widgets.ExportProgressBar
import com.valhalla.thor.presentation.widgets.FreezeLoggerDialog
import com.valhalla.thor.presentation.widgets.TermLoggerDialog
import com.valhalla.thor.presentation.widgets.ThankYouDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Resolves the persisted [DefaultTab] onto the nav bar's [AppDestinations].
 *
 * The single join between the two enums. It lives here rather than on either enum because
 * [DefaultTab] is a domain type and must not see [AppDestinations], which carries `R.string` and
 * `R.drawable` ids.
 */
internal fun DefaultTab.toDestination(): AppDestinations = when (this) {
    DefaultTab.HOME -> AppDestinations.HOME
    DefaultTab.APPS -> AppDestinations.APPS
    DefaultTab.FREEZER -> AppDestinations.FREEZER
    DefaultTab.SETTINGS -> AppDestinations.SETTINGS
}

/**
 * @param startDestination the tab to open on, already resolved from `UserPreferences.defaultTab`.
 *   Required rather than defaulted, and resolved by the caller behind the splash gate, because the
 *   first composition is the only chance to pick it: reading the preference from here would compose
 *   Home first and then jump.
 * @param pendingRestoreUri the `.thorbak` this launch was opened on, or null for an ordinary launch.
 *   Required rather than defaulted for the same reason [startDestination] is — a default would
 *   compile at the one call site that has to pass it, and the only symptom would be that opening a
 *   backup file lands on Home.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MainScreen(
    startDestination: AppDestinations,
    pendingRestoreUri: String?,
    mainViewModel: MainViewModel = koinViewModel(),
    homeViewModel: HomeViewModel = koinViewModel(),
    appListViewModel: AppListViewModel = koinViewModel(),
    freezerViewModel: FreezerViewModel = koinViewModel(),
    onExit: () -> Unit,
) {
    val state by mainViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // --- Safety Gates (Dialog State) ---
    var pendingMultiAction by remember { mutableStateOf<MultiAppAction?>(null) }
    var pendingSingleAction by remember { mutableStateOf<AppClickAction?>(null) }
    var showExitConfirmation by remember { mutableStateOf(false) }

    // --- Navigation 3 Setup (Multiple Backstacks) ---
    // Seeded from [startDestination], and deliberately *not* keyed on it: rememberSaveable's restored
    // value has to win after a rotation or process death, because the tab the user is standing on
    // beats the tab they chose to open on. Keying it would also re-run the seed — and play
    // NavDisplay's slide transition — every time any unrelated preference changed.
    var activeDestination by rememberSaveable { mutableStateOf(startDestination) }

    val activeTab = when (activeDestination) {
        AppDestinations.HOME -> ThorRoute.Home
        AppDestinations.APPS -> ThorRoute.Apps
        AppDestinations.FREEZER -> ThorRoute.Freezer
        AppDestinations.SETTINGS -> ThorRoute.Settings
    }

    val homeBackStack = rememberNavBackStack(ThorRoute.Home)
    val appsBackStack = rememberNavBackStack(ThorRoute.Apps)
    val freezerBackStack = rememberNavBackStack(ThorRoute.Freezer)
    val settingsBackStack = rememberNavBackStack(ThorRoute.Settings)

    val backStacks = remember {
        mapOf(
            ThorRoute.Home to homeBackStack,
            ThorRoute.Apps to appsBackStack,
            ThorRoute.Freezer to freezerBackStack,
            ThorRoute.Settings to settingsBackStack
        )
    }

    val currentBackStack = backStacks[activeTab] ?: homeBackStack

    // Consumed once. rememberSaveable, not remember: `MainViewModel` survives a rotation with the
    // sheet's state on it, so re-running this would reopen a sheet the user had already dismissed.
    //
    // No tab switch any more. This used to jump to Settings and push a route there, because the
    // restore *screen* had to live in some tab's back stack; the sheet is hosted above all four, so
    // the tab the user opened Thor on is left alone.
    var restoreUriConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pendingRestoreUri) {
        val uri = pendingRestoreUri
        if (uri != null && !restoreUriConsumed) {
            restoreUriConsumed = true
            mainViewModel.openRestoreSheet(uri)
        }
    }

    val adaptiveInfo = currentWindowAdaptiveInfoV2()

    // One directive, computed here and handed to the strategy, so that "is there a detail pane?"
    // is read off the same object that decides the layout instead of being guessed from a
    // breakpoint. calculatePaneScaffoldDirective allows two horizontal partitions only at Expanded
    // width — Compact *and* Medium both get one — and ListDetailSceneStrategy declines to build a
    // scene at a single partition (shouldHandleSinglePaneLayout defaults to false), so NavDisplay
    // falls through to rendering the top entry alone. isWideScreen (>= 600 dp) is therefore the
    // wrong question to ask: on a 600-839 dp window it is true while no detail pane exists.
    val paneDirective = calculatePaneScaffoldDirective(adaptiveInfo)
    val hasDetailPane = paneDirective.maxHorizontalPartitions > 1

    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = paneDirective)

    val isWideScreen = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val showNavRailLabel = adaptiveInfo.windowSizeClass.isHeightAtLeastBreakpoint(600)
    
    val configuration = LocalConfiguration.current
    val isLandscapePhone = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            configuration.smallestScreenWidthDp < 600

    val selectedDestination = activeDestination

    // Map Thor's AppDestinations (drawable + string resources) onto Asgard's resource-agnostic nav items.
    val navItems = AppDestinations.entries.map { d ->
        AsgardNavItem(
            icon = ImageVector.vectorResource(d.icon),
            selectedIcon = ImageVector.vectorResource(d.selectedIcon),
            label = stringResource(d.label),
            contentDescription = stringResource(d.contentDescription),
        )
    }
    val selectedNavIndex = AppDestinations.entries.indexOf(selectedDestination)

    val handleDestinationSelected = { dest: AppDestinations ->
        val route = when (dest) {
            AppDestinations.HOME -> ThorRoute.Home
            AppDestinations.APPS -> ThorRoute.Apps
            AppDestinations.FREEZER -> ThorRoute.Freezer
            AppDestinations.SETTINGS -> ThorRoute.Settings
        }
        if (activeDestination == dest) {
            val stack = backStacks[route]
            if (stack != null && stack.size > 1) {
                stack.subList(1, stack.size).clear()
            }
        } else {
            activeDestination = dest
        }
    }

    val showBottomBar = currentBackStack.lastOrNull()?.let {
        it == ThorRoute.Home || it == ThorRoute.Apps || it == ThorRoute.Freezer || it == ThorRoute.Settings
    } ?: true

    // System Back Press Handler: 
    // 1. Pop from the active stack if there are sub-screens (size > 1)
    val canGoBackInActiveTab = (backStacks[activeTab]?.size ?: 0) > 1
    BackHandler(enabled = canGoBackInActiveTab) {
        val stack = backStacks[activeTab]
        if (stack != null && stack.size > 1) {
            stack.removeLastOrNull()
        }
    }

    // 2. Switch to the start tab if at the root of any other tab.
    // Follows [startDestination] rather than a hardcoded HOME: back has to land on the tab the app
    // opens on, or a user whose default is Freezer gets a Home screen they never asked to see and
    // then has to press back a second time to leave.
    val isNonStartRoot = activeDestination != startDestination && (backStacks[activeTab]?.size ?: 0) == 1
    BackHandler(enabled = isNonStartRoot) {
        activeDestination = startDestination
    }

    // 3. Show exit confirmation dialog if at the root of the start tab
    val isAtRoot = activeDestination == startDestination && (backStacks[activeTab]?.size ?: 0) == 1
    BackHandler(enabled = isAtRoot) {
        showExitConfirmation = true
    }

    val canNotLaunchApp = stringResource(R.string.cannot_launch_app)
    val shareApp = stringResource(R.string.share_app)

    // 4. Handle Side Effects
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            mainViewModel.effect.collect { effect ->
                when (effect) {
                    is MainSideEffect.LaunchApp -> {
                        val intent =
                            context.packageManager.getLaunchIntentForPackage(effect.packageName)
                        if (intent != null) context.startActivity(intent)
                        else Toast.makeText(context, canNotLaunchApp, Toast.LENGTH_SHORT).show()
                    }

                    is MainSideEffect.OpenAppSettings -> {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = "package:${effect.packageName}".toUri()
                        }
                        context.startActivity(intent)
                    }

                    is MainSideEffect.ShareApp -> {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = effect.mime
                            putExtra(Intent.EXTRA_STREAM, effect.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, shareApp))
                    }

                    is MainSideEffect.ShareApps -> {
                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "*/*"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(effect.uris))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, shareApp))
                    }

                    is MainSideEffect.NormalUninstall -> {
                        val intent = Intent(Intent.ACTION_DELETE).apply {
                            data = "package:${effect.packageName}".toUri()
                        }
                        context.startActivity(intent)
                    }

                    is MainSideEffect.Message -> {
                        Toast.makeText(
                            context,
                            effect.text.asString(context),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (isWideScreen) {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInHorizontally(initialOffsetX = { -it }),
                exit = slideOutHorizontally(targetOffsetX = { -it })
            ) {
                AsgardNavigationRail(
                    items = navItems,
                    selectedIndex = selectedNavIndex,
                    onSelect = { handleDestinationSelected(AppDestinations.entries[it]) },
                    showLabel = showNavRailLabel
                )
            }
        }

        Scaffold(
            modifier = Modifier.weight(1f),
            bottomBar = {
                Column {
                    // Above the navigation bar and outside its AnimatedVisibility: an export
                    // outlives the screen that started it, so it must stay visible on a screen
                    // that has hidden the bar, and on a wide layout that never had one. It is
                    // also the only cancel affordance there is.
                    AnimatedVisibility(
                        visible = state.exportProgress != null,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it })
                    ) {
                        // Retained across the exit animation so the bar slides out showing its
                        // final numbers instead of blanking the instant the run clears.
                        val lastProgress = remember { mutableStateOf(state.exportProgress) }
                        state.exportProgress?.let { lastProgress.value = it }
                        lastProgress.value?.let { progress ->
                            ExportProgressBar(
                                state = progress,
                                onCancel = { mainViewModel.cancelExport() }
                            )
                        }
                    }
                    if (!isWideScreen) {
                        AnimatedVisibility(
                            visible = showBottomBar,
                            enter = slideInVertically(initialOffsetY = { it }),
                            exit = slideOutVertically(targetOffsetY = { it })
                        ) {
                            AsgardNavigationBar(
                                items = navItems,
                                selectedIndex = selectedNavIndex,
                                onSelect = { handleDestinationSelected(AppDestinations.entries[it]) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
        val spatialSpec = when (state.prefs.animationIntensity) {
            AnimationIntensity.LOW -> snap<IntOffset>()
            AnimationIntensity.MEDIUM,
            AnimationIntensity.HIGH -> MaterialTheme.motionScheme.slowSpatialSpec<IntOffset>()
        }
        val effectsSpec = when (state.prefs.animationIntensity) {
            AnimationIntensity.LOW -> snap<Float>()
            AnimationIntensity.MEDIUM,
            AnimationIntensity.HIGH -> MaterialTheme.motionScheme.slowEffectsSpec<Float>()
        }
        val useSharedTransitions = state.prefs.animationIntensity == AnimationIntensity.HIGH

        SharedTransitionLayout {
            val sharedScope = if (useSharedTransitions) this@SharedTransitionLayout else null
            val entryProvider = entryProvider<NavKey> {
                entry<ThorRoute.Home> {
                    HomeScreen(
                        viewModel = homeViewModel,
                        onNavigateToApps = {
                            activeDestination = AppDestinations.APPS
                        },
                        onNavigateToFreezer = {
                            activeDestination = AppDestinations.FREEZER
                        },
                        // No confirmation dialog: the action now scans, then opens a picker that
                        // names every app it would touch. Confirming a list beats confirming a
                        // warning about a list you were never shown.
                        onReinstallAll = { mainViewModel.onAppAction(AppClickAction.ReinstallAll) },
                        // No type argument any more: `pm trim-caches` takes no package list and
                        // PackageManagerService evicts by LRU across the volume, so "user apps only"
                        // was never a promise Thor could keep. The tap opens the confirmation sheet.
                        onClearAllCache = { mainViewModel.requestClearAllCaches() },
                        onFilterByInstaller = { type, installer ->
                            appListViewModel.showAppsFromInstaller(type, installer)
                            activeDestination = AppDestinations.APPS
                        },
                        onNavigateToExtensionManager = {
                            homeBackStack.add(ThorRoute.ExtensionManager)
                        }
                    )
                }

                entry<ThorRoute.Apps>(
                    metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { AppDetailPlaceholder() })
                ) {
                    val activeDetailRoute = appsBackStack.lastOrNull() as? ThorRoute.AppInfoDetails
                    if (isLandscapePhone && activeDetailRoute != null) {
                        AppInfoDetailsScreen(
                            packageName = activeDetailRoute.packageName,
                            appName = activeDetailRoute.appName,
                            sharedTransitionScope = sharedScope,
                            onBack = {
                                if (appsBackStack.size > 1) {
                                    appsBackStack.removeLastOrNull()
                                }
                            },
                            onNavigateToPermissionManager = { pkg, name ->
                                appsBackStack.add(ThorRoute.PermissionManager(pkg, name))
                            },
                            onAppAction = { action ->
                                checkAndProcessAction(action, { pendingSingleAction = it }) {
                                    mainViewModel.onAppAction(it)
                                }
                            },
                            showOnlyHeaderAndActions = true
                        )
                    } else {
                        AppListScreen(
                            viewModel = appListViewModel,
                            sharedTransitionScope = sharedScope,
                            // Only push the details route where a detail pane can actually show it.
                            // Without a second partition the route does not sit beside the list, it
                            // replaces it full-screen — the jump this whole change exists to remove.
                            // A null callback keeps the tap on AppInfoSheet, which now carries the
                            // same tabbed body anyway.
                            onNavigateToAppInfo = if (hasDetailPane) {
                                { pkg, name -> appsBackStack.add(ThorRoute.AppInfoDetails(pkg, name)) }
                            } else {
                                null
                            },
                            onAppAction = { action ->
                                if (action is AppClickAction.ManagePermissions) {
                                    appsBackStack.add(
                                        ThorRoute.PermissionManager(
                                            action.appInfo.packageName,
                                            action.appInfo.appName ?: ""
                                        )
                                    )
                                } else {
                                    checkAndProcessAction(action, { pendingSingleAction = it }) {
                                        mainViewModel.onAppAction(it)
                                    }
                                }
                            },
                            onMultiAppAction = { pendingMultiAction = it }
                        )
                    }
                }

                entry<ThorRoute.Freezer>(
                    metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { AppDetailPlaceholder() })
                ) {
                    // No landscape detail-pane branch here any more: the freezer's only route to
                    // ThorRoute.AppInfoDetails was the sheet's "Details" action, which now expands
                    // the sheet in place instead. Nothing pushes that route onto freezerBackStack.
                    FreezerScreen(
                        viewModel = freezerViewModel,
                        sharedTransitionScope = sharedScope,
                        onAppAction = { action ->
                            if (action is AppClickAction.ManagePermissions) {
                                freezerBackStack.add(
                                    ThorRoute.PermissionManager(
                                        action.appInfo.packageName,
                                        action.appInfo.appName ?: ""
                                    )
                                )
                            } else {
                                checkAndProcessAction(action, { pendingSingleAction = it }) {
                                    mainViewModel.onAppAction(it)
                                }
                            }
                        },
                        onMultiAppAction = { pendingMultiAction = it }
                    )
                }

                entry<ThorRoute.Settings> {
                    SettingsScreen(
                        onNavigateToExtensionManager = {
                            settingsBackStack.add(ThorRoute.ExtensionManager)
                        },
                        onOpenRestore = { mainViewModel.openRestoreSheet() }
                    )
                }

                // A shim, and the only thing left of the restore *route*. Restore is a sheet now
                // (hosted in GLOBAL OVERLAYS below), so nothing pushes this — but a back stack saved
                // by the previous build can still hold it: `rememberNavBackStack` state is persisted
                // for task restoration, which survives an app update. Deleting the route outright
                // would make that stack fail to deserialise, so the entry stays for one release and
                // forwards to the sheet instead of rendering anything.
                entry<ThorRoute.ArchiveRestore> { route ->
                    LaunchedEffect(route) {
                        if (currentBackStack.size > 1) {
                            currentBackStack.removeLastOrNull()
                        }
                        mainViewModel.openRestoreSheet(route.uriString)
                    }
                }

                entry<ThorRoute.ExtensionManager>(
                    metadata = ListDetailSceneStrategy.detailPane()
                ) {
                    ExtensionManagerScreen(
                        onBack = {
                            if (currentBackStack.size > 1) {
                                currentBackStack.removeLastOrNull()
                            }
                        },
                        onBrowse = {
                            currentBackStack.add(ThorRoute.ExtensionBrowse)
                        }
                    )
                }

                entry<ThorRoute.ExtensionBrowse>(
                    metadata = ListDetailSceneStrategy.detailPane()
                ) {
                    ExtensionBrowseScreen(
                        onBack = {
                            if (currentBackStack.size > 1) {
                                currentBackStack.removeLastOrNull()
                            }
                        }
                    )
                }

                entry<ThorRoute.PermissionManager>(
                    metadata = ListDetailSceneStrategy.detailPane()
                ) { route ->
                    PermissionManagerScreen(
                        packageName = route.packageName,
                        appName = route.appName,
                        sharedTransitionScope = sharedScope,
                        onBack = {
                            if (currentBackStack.size > 1) {
                                currentBackStack.removeLastOrNull()
                            }
                        }
                    )
                }

                entry<ThorRoute.AppInfoDetails>(
                    metadata = ListDetailSceneStrategy.detailPane()
                ) { route ->
                    AppInfoDetailsScreen(
                        packageName = route.packageName,
                        appName = route.appName,
                        sharedTransitionScope = sharedScope,
                        onBack = {
                            if (currentBackStack.size > 1) {
                                currentBackStack.removeLastOrNull()
                            }
                        },
                        onNavigateToPermissionManager = { pkg, name ->
                            currentBackStack.add(ThorRoute.PermissionManager(pkg, name))
                        },
                        onAppAction = { action ->
                            checkAndProcessAction(action, { pendingSingleAction = it }) {
                                mainViewModel.onAppAction(it)
                            }
                        },
                        // showOnlyTabs suppresses this screen's top bar *and* its header and action
                        // row, on the understanding that the list pane is rendering those instead
                        // (the isLandscapePhone branch on entry<ThorRoute.Apps>). With no second
                        // partition there is no list pane doing that, and NavDisplay renders this
                        // entry alone — tabs with no title, no actions and no way back. Nothing
                        // pushes the route without a detail pane any more, but a window can still
                        // shrink under a route that is already on the stack (unfolding, resizing a
                        // split-screen window), so the condition has to be checked here too.
                        showOnlyTabs = isLandscapePhone && hasDetailPane
                    )
                }
            }

            // Decorate entries for each back stack
            val homeDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                rememberViewModelStoreNavEntryDecorator()
            )
            val homeEntries = rememberDecoratedNavEntries(
                backStack = homeBackStack,
                entryDecorators = homeDecorators,
                entryProvider = entryProvider
            )

            val appsDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                rememberViewModelStoreNavEntryDecorator()
            )
            val appsEntries = rememberDecoratedNavEntries(
                backStack = appsBackStack,
                entryDecorators = appsDecorators,
                entryProvider = entryProvider
            )

            val freezerDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                rememberViewModelStoreNavEntryDecorator()
            )
            val freezerEntries = rememberDecoratedNavEntries(
                backStack = freezerBackStack,
                entryDecorators = freezerDecorators,
                entryProvider = entryProvider
            )

            val settingsDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                rememberViewModelStoreNavEntryDecorator()
            )
            val settingsEntries = rememberDecoratedNavEntries(
                backStack = settingsBackStack,
                entryDecorators = settingsDecorators,
                entryProvider = entryProvider
            )

            val entries = remember(activeTab, homeEntries, appsEntries, freezerEntries, settingsEntries) {
                when (activeTab) {
                    ThorRoute.Home -> homeEntries
                    ThorRoute.Apps -> appsEntries
                    ThorRoute.Freezer -> freezerEntries
                    ThorRoute.Settings -> settingsEntries
                    else -> homeEntries
                }
            }

            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                NavDisplay(
                    entries = entries,
                    onBack = {
                        if (currentBackStack.size > 1) {
                            currentBackStack.removeLastOrNull()
                        }
                    },
                    sceneStrategies = listOf(listDetailStrategy),
                    transitionSpec = {
                        (fadeIn(animationSpec = effectsSpec) + slideInHorizontally(
                            initialOffsetX = { it },
                            animationSpec = spatialSpec
                        )) togetherWith (fadeOut(animationSpec = effectsSpec) + slideOutHorizontally(
                            targetOffsetX = { -it },
                            animationSpec = spatialSpec
                        ))
                    },
                    popTransitionSpec = {
                        (fadeIn(animationSpec = effectsSpec) + slideInHorizontally(
                            initialOffsetX = { -it },
                            animationSpec = spatialSpec
                        )) togetherWith (fadeOut(animationSpec = effectsSpec) + slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = spatialSpec
                        ))
                    },
                    predictivePopTransitionSpec = {
                        (fadeIn(animationSpec = effectsSpec) + slideInHorizontally(
                            initialOffsetX = { -it },
                            animationSpec = spatialSpec
                        )) togetherWith (fadeOut(animationSpec = effectsSpec) + slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = spatialSpec
                        ))
                    }
                )

                // --- GLOBAL OVERLAYS (Unchanged) ---
                if (pendingMultiAction != null) {
                    MultiAppAffirmationDialog(
                        multiAppAction = pendingMultiAction!!,
                        onConfirm = {
                            mainViewModel.onMultiAppAction(pendingMultiAction!!)
                            pendingMultiAction = null
                        },
                        onRejected = { pendingMultiAction = null }
                    )
                }

                if (pendingSingleAction != null) {
                    val action = pendingSingleAction!!
                    val (title, text, icon) = when (action) {
                        is AppClickAction.Kill -> Triple(
                            stringResource(R.string.kill_app_title),
                            stringResource(R.string.kill_app_desc, action.appInfo.appName ?: ""),
                            R.drawable.danger
                        )

                        else -> Triple(
                            stringResource(R.string.confirm),
                            stringResource(R.string.are_you_sure),
                            R.drawable.thor_mono
                        )
                    }

                    AffirmationDialog(
                        title = title,
                        text = text,
                        icon = icon,
                        onConfirm = {
                            mainViewModel.onAppAction(action)
                            pendingSingleAction = null
                        },
                        onRejected = { pendingSingleAction = null }
                    )
                }

                state.fixStoreSelection?.let { selection ->
                    val labelResolver: InstallerLabelResolver = koinInject()
                    FixStoreSheet(
                        selection = selection,
                        labelFor = labelResolver::labelFor,
                        onToggle = { mainViewModel.toggleFixStoreTarget(it) },
                        onSetAll = { mainViewModel.setAllFixStoreTargets(it) },
                        onConfirm = { mainViewModel.confirmFixStore() },
                        onDismiss = { mainViewModel.dismissFixStorePicker() }
                    )
                }

                if (state.loggerState.isVisible) {
                    TermLoggerDialog(
                        title = state.loggerState.title,
                        logs = state.loggerState.logs,
                        isOperationComplete = state.loggerState.isComplete,
                        isStopping = state.loggerState.isStopping,
                        onStop = if (state.loggerState.canStop) {
                            { mainViewModel.requestStopBatch() }
                        } else {
                            null
                        },
                        onDismiss = { mainViewModel.dismissLogger() }
                    )
                }

                // Hosted here rather than in HomeScreen because the clear outlives the screen that
                // started it: switching tabs mid-operation must not cancel it or lose the byte
                // count. The state lives in MainViewModel for the same reason.
                state.cacheClear?.let { cacheClear ->
                    ClearAllCacheSheet(
                        state = cacheClear,
                        // Formatted here, not in the ViewModel: Formatter needs a Context, and the
                        // short form is locale-aware, so it has to be resolved at draw time.
                        // Zero is formatted like any other number rather than being filtered out —
                        // the sheet has a sentence for "there was nothing left", and dropping it to
                        // null here would put a measured zero in the *unmeasured* branch and tell a
                        // user who has usage access to go and grant usage access.
                        formattedFreedBytes = (cacheClear as? CacheClearState.Done)
                            ?.freedBytes
                            ?.let { Formatter.formatShortFileSize(context, it) },
                        onConfirm = { mainViewModel.confirmClearAllCaches() },
                        onDismiss = { mainViewModel.dismissCacheClear() }
                    )
                }

                // Restore is a sheet, and it is hosted here rather than in a tab's back stack because
                // a restore outlives the section it was started from: the Settings row, an incoming
                // `.thorbak`, and a tap on a running restore's notification all open the same one,
                // and switching tabs mid-restore must not tear it down. This is also what makes the
                // suppression in ObserveInterruptedRestoreUseCase load-bearing — the Settings section
                // really is composed underneath, so it must not announce the restore that is on
                // screen above it.
                state.restoreSheet?.let { restore ->
                    ArchiveRestoreSheet(
                        uriString = restore.uriString,
                        onDismiss = { mainViewModel.dismissRestoreSheet() }
                    )
                }

                // Only ever opened by a notification tap — see [BackupSheetState]. The in-app route
                // is the copy the app-info surfaces host themselves.
                state.backupSheet?.let { backup ->
                    AppBackupSheet(
                        packageName = backup.packageName,
                        appLabel = backup.appLabel,
                        onDismiss = { mainViewModel.dismissBackupSheet() }
                    )
                }

                if (state.freezeLoggerState.isVisible) {
                    FreezeLoggerDialog(
                        isFreeze = state.freezeLoggerState.isFreeze,
                        total = state.freezeLoggerState.total,
                        processed = state.freezeLoggerState.processed,
                        failed = state.freezeLoggerState.failed,
                        isComplete = state.freezeLoggerState.isComplete,
                        onDismiss = { mainViewModel.dismissFreezeLogger() }
                    )
                }

                if (showExitConfirmation) {
                    AffirmationDialog(
                        title = stringResource(R.string.exit_thor_title),
                        text = stringResource(R.string.exit_thor_desc),
                        icon = R.drawable.exit_to_app,
                        onConfirm = {
                            showExitConfirmation = false
                            onExit()
                        },
                        onRejected = { showExitConfirmation = false }
                    )
                }

                // Resolved here rather than in MainScreen's parameter defaults: a default argument
                // is evaluated during the first composition, and instantiating the store flavour's
                // processor there put the Play billing bind on the first-frame main thread purely
                // to observe one flag.
                val billingProcessor: BillingProcessor = koinInject()
                val showThankYouDialog by billingProcessor.showThankYouDialog.collectAsStateWithLifecycle()
                if (showThankYouDialog) {
                    ThankYouDialog(
                        onDismiss = { billingProcessor.dismissThankYouDialog() }
                    )
                }

                if (state.showSupportDeveloperPrompt) {
                    SupportDeveloperHelper(
                        onDismiss = { mainViewModel.markSupportDeveloperPromptShown() }
                    )
                }
            }
        }
    }
    }
}

private fun checkAndProcessAction(
    action: AppClickAction,
    onRequireConfirmation: (AppClickAction) -> Unit,
    onExecute: (AppClickAction) -> Unit
) {
    when (action) {
        // ReinstallAll used to be here too. It confirms through its own picker now, which names
        // the apps instead of warning about them.
        is AppClickAction.Kill -> onRequireConfirmation(action)

        else -> onExecute(action)
    }
}

@Composable
private fun AppDetailPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(R.drawable.thor_mono),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.select_app_details),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}