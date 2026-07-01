package com.valhalla.thor.presentation.main

import android.content.Intent
import android.provider.Settings
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
import com.valhalla.thor.domain.model.MultiAppAction
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
import com.valhalla.thor.presentation.extension.ExtensionManagerScreen
import com.valhalla.thor.presentation.settings.BillingProcessor
import com.valhalla.thor.presentation.settings.SupportDeveloperHelper
import com.valhalla.thor.presentation.widgets.AffirmationDialog
import com.valhalla.thor.presentation.widgets.MultiAppAffirmationDialog
import com.valhalla.thor.presentation.widgets.TermLoggerDialog
import com.valhalla.thor.presentation.widgets.ThankYouDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel = koinViewModel(),
    homeViewModel: HomeViewModel = koinViewModel(),
    appListViewModel: AppListViewModel = koinViewModel(),
    freezerViewModel: FreezerViewModel = koinViewModel(),
    onExit: () -> Unit,
    billingProcessor: BillingProcessor = koinInject(),
) {
    val state by mainViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // --- Safety Gates (Dialog State) ---
    var pendingMultiAction by remember { mutableStateOf<MultiAppAction?>(null) }
    var pendingSingleAction by remember { mutableStateOf<AppClickAction?>(null) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    var isExtensionActive by remember { mutableStateOf(false) }

    // --- Navigation 3 Setup (Multiple Backstacks) ---
    var activeDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

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

    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()

    val adaptiveInfo = currentWindowAdaptiveInfoV2()
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
    val canGoBackInActiveTab = (backStacks[activeTab]?.size ?: 0) > 1 && !isExtensionActive
    BackHandler(enabled = canGoBackInActiveTab) {
        val stack = backStacks[activeTab]
        if (stack != null && stack.size > 1) {
            stack.removeLastOrNull()
        }
    }

    // 2. Switch to Home tab if at the root of a non-Home tab
    val isNonStartRoot = activeDestination != AppDestinations.HOME && (backStacks[activeTab]?.size ?: 0) == 1
    BackHandler(enabled = isNonStartRoot) {
        activeDestination = AppDestinations.HOME
    }

    // 3. Show exit confirmation dialog if at the root of the Home tab
    val isAtRoot = activeDestination == AppDestinations.HOME && (backStacks[ThorRoute.Home]?.size ?: 0) == 1
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
                            type = "application/vnd.android.package-archive"
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
                }
            }
        }
    }

    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { msg ->
            Toast.makeText(context, msg.asString(context), Toast.LENGTH_SHORT).show()
            mainViewModel.consumeMessage()
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
                        onReinstallAll = {
                            checkAndProcessAction(
                                AppClickAction.ReinstallAll,
                                { pendingSingleAction = it },
                                { mainViewModel.onAppAction(it) }
                            )
                        },
                        onClearAllCache = { type -> mainViewModel.clearAllCache(type) }
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
                            onNavigateToAppInfo = { pkg, name ->
                                appsBackStack.add(ThorRoute.AppInfoDetails(pkg, name))
                            },
                            onAppAction = { action ->
                                if (action is AppClickAction.ManagePermissions) {
                                    appsBackStack.add(
                                        ThorRoute.PermissionManager(
                                            action.appInfo.packageName,
                                            action.appInfo.appName ?: ""
                                        )
                                    )
                                } else if (action is AppClickAction.OpenDetails) {
                                    appsBackStack.add(
                                        ThorRoute.AppInfoDetails(
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
                    val activeDetailRoute = freezerBackStack.lastOrNull() as? ThorRoute.AppInfoDetails
                    if (isLandscapePhone && activeDetailRoute != null) {
                        AppInfoDetailsScreen(
                            packageName = activeDetailRoute.packageName,
                            appName = activeDetailRoute.appName,
                            sharedTransitionScope = sharedScope,
                            onBack = {
                                if (freezerBackStack.size > 1) {
                                    freezerBackStack.removeLastOrNull()
                                }
                            },
                            onNavigateToPermissionManager = { pkg, name ->
                                freezerBackStack.add(ThorRoute.PermissionManager(pkg, name))
                            },
                            onAppAction = { action ->
                                checkAndProcessAction(action, { pendingSingleAction = it }) {
                                    mainViewModel.onAppAction(it)
                                }
                            },
                            showOnlyHeaderAndActions = true
                        )
                    } else {
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
                                } else if (action is AppClickAction.OpenDetails) {
                                    freezerBackStack.add(
                                        ThorRoute.AppInfoDetails(
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

                entry<ThorRoute.Settings> {
                    SettingsScreen(
                        onNavigateToExtensionManager = {
                            settingsBackStack.add(ThorRoute.ExtensionManager)
                        }
                    )
                }

                entry<ThorRoute.ExtensionManager>(
                    metadata = ListDetailSceneStrategy.detailPane()
                ) {
                    ExtensionManagerScreen(
                        onBack = {
                            if (settingsBackStack.size > 1) {
                                settingsBackStack.removeLastOrNull()
                            }
                        },
                        onExtensionActiveChanged = { isActive ->
                            isExtensionActive = isActive
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
                        showOnlyTabs = isLandscapePhone
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

                        AppClickAction.ReinstallAll -> Triple(
                            stringResource(R.string.reinstall_all),
                            stringResource(R.string.risk_warning_desc),
                            R.drawable.apk_install
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

                if (state.loggerState.isVisible) {
                    TermLoggerDialog(
                        title = state.loggerState.title,
                        logs = state.loggerState.logs,
                        isOperationComplete = state.loggerState.isComplete,
                        onDismiss = { mainViewModel.dismissLogger() }
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
        is AppClickAction.Kill,
        AppClickAction.ReinstallAll -> onRequireConfirmation(action)

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