package com.valhalla.thor.presentation.main

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.valhalla.thor.presentation.freezer.FreezerScreen
import com.valhalla.thor.presentation.home.AppDestinations
import com.valhalla.thor.presentation.home.HomeScreen
import com.valhalla.thor.presentation.settings.SettingsScreen
import com.valhalla.thor.presentation.home.HomeViewModel
import com.valhalla.thor.presentation.widgets.AffirmationDialog
import com.valhalla.thor.presentation.widgets.MultiAppAffirmationDialog
import com.valhalla.thor.presentation.widgets.TermLoggerDialog
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun MainScreen(
    mainViewModel: MainViewModel = koinViewModel(),
    homeViewModel: HomeViewModel = koinViewModel(),
    onExit: () -> Unit,
) {
    val state by mainViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // --- Safety Gates (Dialog State) ---
    var pendingMultiAction by remember { mutableStateOf<MultiAppAction?>(null) }
    var pendingSingleAction by remember { mutableStateOf<AppClickAction?>(null) }
    var showExitConfirmation by remember { mutableStateOf(false) }

    // 1. Handle Back Button (Return to Home before Exiting)
    BackHandler(enabled = state.selectedDestination != AppDestinations.HOME) {
        mainViewModel.onDestinationSelected(AppDestinations.HOME)
    }
    // Secondary BackHandler for Home tab to Exit
    BackHandler(enabled = state.selectedDestination == AppDestinations.HOME) {
        showExitConfirmation = true
    }

    // 4. Handle Side Effects
    LaunchedEffect(Unit) {
        mainViewModel.effect.collect { effect ->
            when (effect) {
                is MainSideEffect.LaunchApp -> {
                    val intent =
                        context.packageManager.getLaunchIntentForPackage(effect.packageName)
                    if (intent != null) context.startActivity(intent)
                    else Toast.makeText(context, "Cannot launch app", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            mainViewModel.consumeMessage()
        }
    }

    Scaffold(
        bottomBar = {
            ThorNavigationBar(
                destinations = AppDestinations.entries,
                selectedDestination = state.selectedDestination,
                onDestinationSelected = { dest ->
                    mainViewModel.onDestinationSelected(dest)
                }
            )
        }
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            when (state.selectedDestination) {
                AppDestinations.HOME -> {
                    HomeScreen(
                        viewModel = homeViewModel,
                        onNavigateToApps = {
                            mainViewModel.onDestinationSelected(AppDestinations.APPS)
                        },
                        onNavigateToFreezer = {
                            mainViewModel.onDestinationSelected(AppDestinations.FREEZER)
                        },
                        onReinstallAll = { mainViewModel.onAppAction(AppClickAction.ReinstallAll) },
                        onClearAllCache = { type -> mainViewModel.clearAllCache(type) }
                    )
                }

                AppDestinations.APPS -> {
                    AppListScreen(
                        onAppAction = { action ->
                            checkAndProcessAction(action, { pendingSingleAction = it }) {
                                mainViewModel.onAppAction(it)
                            }
                        },
                        onMultiAppAction = { pendingMultiAction = it }
                    )
                }

                AppDestinations.FREEZER -> {
                    FreezerScreen(
                        onAppAction = { action ->
                            checkAndProcessAction(action, { pendingSingleAction = it }) {
                                mainViewModel.onAppAction(it)
                            }
                        },
                        onMultiAppAction = { pendingMultiAction = it }
                    )
                }

                AppDestinations.SETTINGS -> {
                    SettingsScreen()
                }
            }

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
                        "Kill App?",
                        "Force stop ${action.appInfo.appName}? This may cause data loss.",
                        R.drawable.danger
                    )

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
                    title = "Exit Thor?",
                    text = "Are you sure you want to close the application?",
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
        is AppClickAction.Kill -> onRequireConfirmation(action)
        else -> onExecute(action)
    }
}