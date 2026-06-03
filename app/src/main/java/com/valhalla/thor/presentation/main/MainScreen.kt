package com.valhalla.thor.presentation.main

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.presentation.appList.AppListScreen
import com.valhalla.thor.presentation.appList.AppListViewModel
import com.valhalla.thor.presentation.freezer.FreezerScreen
import com.valhalla.thor.presentation.freezer.FreezerViewModel
import com.valhalla.thor.presentation.home.AppDestinations
import com.valhalla.thor.presentation.home.HomeScreen
import com.valhalla.thor.presentation.home.HomeViewModel
import com.valhalla.thor.presentation.settings.SettingsScreen
import com.valhalla.thor.presentation.widgets.AffirmationDialog
import com.valhalla.thor.presentation.widgets.MultiAppAffirmationDialog
import com.valhalla.thor.presentation.widgets.TermLoggerDialog
import com.valhalla.thor.presentation.permission.PermissionManagerScreen
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import com.valhalla.thor.presentation.navigation.ThorRoute
import org.koin.androidx.compose.koinViewModel

@Composable
fun MainScreen(
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

    // --- Navigation 3 Setup ---
    val homeBackStack = rememberNavBackStack(ThorRoute.Home)
    val appsBackStack = rememberNavBackStack(ThorRoute.Apps)
    val freezerBackStack = rememberNavBackStack(ThorRoute.Freezer)
    val settingsBackStack = rememberNavBackStack(ThorRoute.Settings)

    var activeTab by remember { mutableStateOf<ThorRoute>(ThorRoute.Home) }

    val activeBackStack: NavBackStack<NavKey> = when (activeTab) {
        ThorRoute.Home -> homeBackStack
        ThorRoute.Apps -> appsBackStack
        ThorRoute.Freezer -> freezerBackStack
        ThorRoute.Settings -> settingsBackStack
        else -> homeBackStack
    }

    val selectedDestination = when (activeTab) {
        ThorRoute.Home -> AppDestinations.HOME
        ThorRoute.Apps -> AppDestinations.APPS
        ThorRoute.Freezer -> AppDestinations.FREEZER
        ThorRoute.Settings -> AppDestinations.SETTINGS
        else -> AppDestinations.HOME
    }

    val showBottomBar = activeBackStack.lastOrNull()?.let {
        it == ThorRoute.Home || it == ThorRoute.Apps || it == ThorRoute.Freezer || it == ThorRoute.Settings
    } ?: true

    // Handle Back Press to Show Exit Confirmation when at the root of Home stack
    val isAtRoot = activeBackStack.size == 1 && activeTab == ThorRoute.Home
    BackHandler(enabled = isAtRoot) {
        showExitConfirmation = true
    }

    // Handle Back Press to return to Home stack when at the root of another tab
    val isNonStartRoot = activeBackStack.size == 1 && activeTab != ThorRoute.Home
    BackHandler(enabled = isNonStartRoot) {
        activeTab = ThorRoute.Home
    }

    val canNotLaunchApp = stringResource(R.string.cannot_launch_app)
    val shareApp = stringResource(R.string.share_app)

    // 4. Handle Side Effects
    LaunchedEffect(Unit) {
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

                is MainSideEffect.NormalUninstall -> {
                    val intent = Intent(Intent.ACTION_DELETE).apply {
                        data = "package:${effect.packageName}".toUri()
                    }
                    context.startActivity(intent)
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

    val entryProvider = entryProvider<NavKey> {
        entry<ThorRoute.Home> {
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToApps = {
                    activeTab = ThorRoute.Apps
                },
                onNavigateToFreezer = {
                    activeTab = ThorRoute.Freezer
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

        entry<ThorRoute.Apps> {
            AppListScreen(
                viewModel = appListViewModel,
                onAppAction = { action ->
                    if (action is AppClickAction.ManagePermissions) {
                        activeBackStack.add(
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

        entry<ThorRoute.Freezer> {
            FreezerScreen(
                viewModel = freezerViewModel,
                onAppAction = { action ->
                    if (action is AppClickAction.ManagePermissions) {
                        activeBackStack.add(
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
            SettingsScreen()
        }

        entry<ThorRoute.PermissionManager> { route ->
            PermissionManagerScreen(
                packageName = route.packageName,
                appName = route.appName,
                onBack = { activeBackStack.removeLastOrNull() }
            )
        }
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                ThorNavigationBar(
                    destinations = AppDestinations.entries,
                    selectedDestination = selectedDestination,
                    onDestinationSelected = { dest ->
                        activeTab = when (dest) {
                            AppDestinations.HOME -> ThorRoute.Home
                            AppDestinations.APPS -> ThorRoute.Apps
                            AppDestinations.FREEZER -> ThorRoute.Freezer
                            AppDestinations.SETTINGS -> ThorRoute.Settings
                        }
                    }
                )
            }
        }
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            NavDisplay(
                backStack = activeBackStack,
                onBack = { activeBackStack.removeLastOrNull() },
                entryProvider = entryProvider,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(300)
                    )) togetherWith (fadeOut(animationSpec = tween(300)) + slideOutHorizontally(
                        targetOffsetX = { -it },
                        animationSpec = tween(300)
                    ))
                },
                popTransitionSpec = {
                    (fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                        initialOffsetX = { -it },
                        animationSpec = tween(300)
                    )) togetherWith (fadeOut(animationSpec = tween(300)) + slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(300)
                    ))
                },
                predictivePopTransitionSpec = {
                    (fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                        initialOffsetX = { -it },
                        animationSpec = tween(300)
                    )) togetherWith (fadeOut(animationSpec = tween(300)) + slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(300)
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