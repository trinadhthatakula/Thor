package com.valhalla.thor.ui.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
import com.valhalla.thor.model.MultiAppAction
import com.valhalla.thor.model.shizuku.ShizukuManager
import com.valhalla.thor.model.stopLogger
import com.valhalla.thor.ui.screens.AppListScreen
import com.valhalla.thor.ui.screens.FreezerScreen
import com.valhalla.thor.ui.screens.HomeActions
import com.valhalla.thor.ui.screens.HomeScreen
import com.valhalla.thor.ui.widgets.AffirmationDialog
import com.valhalla.thor.ui.widgets.MultiAppAffirmationDialog
import com.valhalla.thor.ui.widgets.TermLoggerDialog
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun HomePage(
    modifier: Modifier = Modifier,
    shizukuManager: ShizukuManager = koinViewModel(),
    homeViewModel: HomeViewModel = koinViewModel(),
    onExit: () -> Unit
) {

    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState {
        AppDestinations.entries.size
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect {
            homeViewModel.selectDestination(it)
        }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current


    BackHandler {
        if (uiState.selectedDestinationIndex != AppDestinations.HOME.ordinal) {
            scope.launch {
                pagerState.animateScrollToPage(AppDestinations.HOME.ordinal)
            }
        } else {
            if (uiState.logObserver.isEmpty())
                homeViewModel.showExitDialog(true)
        }
    }

    if (uiState.showExitDialog) {
        AffirmationDialog(
            text = "You want to exit?",
            onConfirm = { onExit() },
            onRejected = { homeViewModel.showExitDialog(false) }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppDestinations.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = uiState.selectedDestinationIndex == dest.ordinal,
                        label = {
                            Text(stringResource(dest.label))
                        },
                        icon = {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                    TooltipAnchorPosition.Above
                                ),
                                tooltip = { PlainTooltip { Text(stringResource(dest.contentDescription)) } },
                                state = rememberTooltipState(),
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(if (uiState.selectedDestinationIndex == dest.ordinal) dest.selectedIcon else dest.icon),
                                    stringResource(dest.label)
                                )
                            }
                        },
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(dest.ordinal)
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        HorizontalPager(pagerState) { page ->
            when (page) {
                AppDestinations.HOME.ordinal -> HomeScreen(
                    modifier = modifier.padding(paddingValues),
                    userAppList = uiState.userApps,
                    systemAppList = uiState.systemApps,
                    onHomeActions = { homeAction ->
                        when (homeAction) {
                            is HomeActions.ActiveApps -> {
                                scope.launch {
                                    pagerState.animateScrollToPage(AppDestinations.APPS.ordinal)
                                }
                            }

                            HomeActions.BKI -> {}
                            is HomeActions.FrozenApps -> {
                                scope.launch {
                                    pagerState.animateScrollToPage(AppDestinations.FREEZER.ordinal)
                                }
                            }

                            HomeActions.ReinstallAll -> {
                                homeViewModel.onMultiAppAction(MultiAppAction.ReInstall(uiState.userApps))
                            }

                            is HomeActions.ShowToast -> {
                                Toast.makeText(
                                    context,
                                    homeAction.text,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            HomeActions.SwitchAutoReinstall -> {

                            }

                            is HomeActions.ClearCache -> {
                                val appInfo = homeAction.appInfos
                                homeViewModel.onMultiAppAction(
                                    MultiAppAction.ClearCache(
                                        appInfo.filter { ap -> ap.packageName != BuildConfig.APPLICATION_ID }
                                    )
                                )
                            }
                        }
                    }
                )

                AppDestinations.APPS.ordinal -> AppListScreen(
                    modifier = modifier.padding(paddingValues),
                    userAppList = uiState.userApps,
                    systemAppList = uiState.systemApps,
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { homeViewModel.refreshAppList() },
                    onAppAction = { aAction ->
                        homeViewModel.processAppAction(
                            context,
                            shizukuManager.elevatableState,
                            aAction,
                            exit = {
                                shizukuManager.updateCacheSize()
                                homeViewModel.apply {
                                    refreshAppList()
                                    permitExit()
                                    clearLogger()
                                }
                            }
                        )
                    },
                    onMultiAppAction = { mAction ->
                        homeViewModel.onMultiAppAction(mAction)
                    }
                )

                AppDestinations.FREEZER.ordinal -> FreezerScreen(
                    icon = R.drawable.frozen,
                    modifier = modifier.padding(paddingValues),
                    userAppList = uiState.userApps,
                    systemAppList = uiState.systemApps,
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { homeViewModel.refreshAppList() },
                    onAppAction = { aAction ->
                        homeViewModel.processAppAction(
                            context,
                            shizukuManager.elevatableState,
                            aAction,
                            exit = {
                                shizukuManager.updateCacheSize()
                                homeViewModel.apply {
                                    refreshAppList()
                                    permitExit()
                                    clearLogger()
                                }
                            }
                        )
                    },
                    onMultiAppAction = { mAction ->
                        homeViewModel.onMultiAppAction(mAction)
                    }
                )

                else -> {}

                //AppDestinations.SETTINGS -> Text("Settings")
            }
        }

    }

    if (uiState.showConfirmation) {
        uiState.multiAppAction?.let { multiAction ->
            MultiAppAffirmationDialog(
                multiAppAction = multiAction,
                onConfirm = {
                    homeViewModel.processMultiAppAction(
                        context,
                        elevatableState = shizukuManager.elevatableState,
                        multiAction,
                        exit = {
                            homeViewModel.clearActions()
                            shizukuManager.updateCacheSize()
                            homeViewModel.refreshAppList()
                        }
                    )
                },
                onRejected = {
                    homeViewModel.clearActions()
                }
            )
        }
    }

    if (uiState.logObserver.isNotEmpty()) {
        TermLoggerDialog(
            uiState = uiState,
            onTerminate = {
                stopLogger?.invoke()
                homeViewModel.clearActions()
            },
            done = {
                homeViewModel.clearActions()
            }
        )
    }

}
