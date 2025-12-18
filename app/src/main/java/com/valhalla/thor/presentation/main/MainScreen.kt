package com.valhalla.thor.presentation.main

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.presentation.appList.AppListScreen
import com.valhalla.thor.presentation.freezer.FreezerScreen
import com.valhalla.thor.presentation.home.AppDestinations
import com.valhalla.thor.presentation.home.HomeScreen
import com.valhalla.thor.presentation.widgets.AffirmationDialog
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.presentation.widgets.MultiAppAffirmationDialog
import com.valhalla.thor.presentation.widgets.TermLoggerDialog
import org.koin.androidx.compose.koinViewModel
import androidx.core.net.toUri

@Composable
fun MainScreen(
    onExit: () -> Unit
) {
    val navController = rememberNavController()
    val mainViewModel: MainViewModel = koinViewModel()
    val state by mainViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // --- Safety Gates (Dialog State) ---
    var pendingMultiAction by remember { mutableStateOf<MultiAppAction?>(null) }
    var pendingSingleAction by remember { mutableStateOf<AppClickAction?>(null) }

    // 1. Handle One-Time Side Effects (Navigation/Intents)
    LaunchedEffect(Unit) {
        mainViewModel.effect.collect { effect ->
            when (effect) {
                is MainSideEffect.LaunchApp -> {
                    val intent = context.packageManager.getLaunchIntentForPackage(effect.packageName)
                    if (intent != null) {
                        context.startActivity(intent)
                    } else {
                        Toast.makeText(context, "Cannot launch app", Toast.LENGTH_SHORT).show()
                    }
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
                    context.startActivity(Intent.createChooser(intent, "Share App"))
                }
            }
        }
    }

    // 2. Handle Feedback (Toasts)
    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            mainViewModel.consumeMessage()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                AppDestinations.entries.forEach { dest ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                painterResource(if (currentRoute == dest.name) dest.selectedIcon else dest.icon),
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(dest.label)) },
                        selected = currentRoute == dest.name,
                        onClick = {
                            navController.navigate(dest.name) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->

        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {

            NavHost(
                navController = navController,
                startDestination = AppDestinations.HOME.name
            ) {
                composable(AppDestinations.HOME.name) {
                    HomeScreen(
                        onNavigateToApps = { navController.navigate(AppDestinations.APPS.name) },
                        onNavigateToFreezer = { navController.navigate(AppDestinations.FREEZER.name) },
                        onReinstallAll = {
                            // Reinstall All is a "Safe" action (it scans first), so we let it pass to VM directly.
                            // The VM will show a Logger which acts as confirmation.
                            mainViewModel.onAppAction(AppClickAction.ReinstallAll)
                        },
                        onClearAllCache = { type ->
                            // Batch Cache Clear is safe enough to just show the Logger,
                            // but if you wanted a dialog, you'd add a pendingAction here.
                            mainViewModel.clearAllCache(type)
                        }
                    )
                }

                composable(AppDestinations.APPS.name) {
                    AppListScreen(
                        // Intercept actions for safety check
                        onAppAction = { action ->
                            checkAndProcessAction(action, { pendingSingleAction = it }) {
                                mainViewModel.onAppAction(it)
                            }
                        },
                        // Intercept Multi-Actions for Confirmation Dialog
                        onMultiAppAction = { pendingMultiAction = it }
                    )
                }

                composable(AppDestinations.FREEZER.name) {
                    FreezerScreen(
                        onAppAction = { action ->
                            checkAndProcessAction(action, { pendingSingleAction = it }) {
                                mainViewModel.onAppAction(it)
                            }
                        },
                        onMultiAppAction = { pendingMultiAction = it }
                    )
                }
            }

            // --- GLOBAL OVERLAYS ---

            // 1. Batch Confirmation Dialog
            if (pendingMultiAction != null) {
                MultiAppAffirmationDialog(
                    multiAppAction = pendingMultiAction!!,
                    onConfirm = {
                        mainViewModel.onMultiAppAction(pendingMultiAction!!)
                        pendingMultiAction = null
                    },
                    onRejected = {
                        pendingMultiAction = null
                    }
                )
            }

            // 2. Single Action Confirmation Dialog (For risky actions like Kill)
            if (pendingSingleAction != null) {
                val action = pendingSingleAction!!
                // Determine text based on action type
                val (title, text, icon) = when(action) {
                    is AppClickAction.Kill -> Triple("Kill App?", "Force stop ${action.appInfo.appName}? This may cause data loss.", R.drawable.danger)
                    else -> Triple("Confirm", "Are you sure?", R.drawable.thor_mono)
                }

                AffirmationDialog(
                    title = title,
                    text = text,
                    icon = icon,
                    onConfirm = {
                        mainViewModel.onAppAction(action)
                        pendingSingleAction = null
                    },
                    onRejected = {
                        pendingSingleAction = null
                    }
                )
            }

            // 3. Terminal Logger (For Heavy Tasks)
            if (state.loggerState.isVisible) {
                TermLoggerDialog(
                    title = state.loggerState.title,
                    logs = state.loggerState.logs,
                    isOperationComplete = state.loggerState.isComplete,
                    onDismiss = { mainViewModel.dismissLogger() }
                )
            }
        }
    }
}

/**
 * Decides if a single app action needs manual confirmation (Dialog) or can execute immediately.
 */
private fun checkAndProcessAction(
    action: AppClickAction,
    onRequireConfirmation: (AppClickAction) -> Unit,
    onExecute: (AppClickAction) -> Unit
) {
    when (action) {
        // KILL is risky -> Confirm
        is AppClickAction.Kill -> onRequireConfirmation(action)

        // REINSTALL is risky but AppListScreen already handles confirmation locally for now.
        // If we moved that logic here, we'd add it case.

        // UNINSTALL (System) is risky, but AppInfoDialog handles that locally too.

        // OTHERS (Launch, Share, Freeze, ClearCache) -> Execute immediately
        else -> onExecute(action)
    }
}