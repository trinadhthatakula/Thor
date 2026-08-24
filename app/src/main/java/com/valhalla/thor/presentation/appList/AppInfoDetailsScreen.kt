// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import android.content.Context
import android.icu.text.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.presentation.backup.AppBackupSheet
import com.valhalla.thor.presentation.widgets.AppActionRow
import com.valhalla.thor.presentation.widgets.AppHeaderIcon
import com.valhalla.thor.presentation.widgets.AppRiskAction
import com.valhalla.thor.presentation.widgets.AppRiskDialog
import com.valhalla.thor.presentation.widgets.FreezerPromptSnackbar
import com.valhalla.thor.presentation.widgets.StatusChip
import com.valhalla.thor.presentation.widgets.appHeaderIconGlowInset
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.ComponentControlBlocker
import com.valhalla.thor.domain.model.ComponentDetail
import com.valhalla.thor.domain.model.ComponentType
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PermissionDetail
import com.valhalla.thor.domain.model.freezeNeedsConfirmation
import com.valhalla.thor.presentation.theme.bodyFontFamily
import com.valhalla.thor.presentation.theme.firaMonoFontFamily
import com.valhalla.thor.presentation.utils.ObserveAsEvents
import com.valhalla.thor.presentation.utils.getBloatRecommendationColors
import com.valhalla.thor.util.AppLocale
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import androidx.compose.ui.platform.ClipEntry
import java.util.Date

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppInfoDetailsScreen(
    packageName: String,
    appName: String?,
    viewModel: AppInfoDetailsViewModel = koinViewModel(),
    sharedTransitionScope: SharedTransitionScope? = null,
    onBack: () -> Unit,
    onNavigateToPermissionManager: (packageName: String, appName: String) -> Unit,
    onAppAction: (AppClickAction) -> Unit,
    showOnlyHeaderAndActions: Boolean = false,
    showOnlyTabs: Boolean = false
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(packageName) {
        viewModel.loadAppDetails(packageName)
    }

    ObserveAsEvents(viewModel.events) { msg ->
        Toast.makeText(context, msg.asString(context), Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            if (!showOnlyHeaderAndActions && !showOnlyTabs) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_downward),
                            contentDescription = stringResource(R.string.cd_close)
                        )
                    }
                    Text(
                        text = stringResource(R.string.app_details_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            when {
                state.isLoading && state.detailedInfo == null -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Using a generic error icon since R.raw.error_state might not exist
                        Icon(
                            painter = painterResource(R.drawable.danger),
                            contentDescription = null,
                            modifier = Modifier.size(120.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = state.errorMessage?.asString(context) ?: stringResource(R.string.unknown_error_occurred),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { viewModel.loadAppDetails(packageName) }) {
                            Text(stringResource(R.string.retry_label))
                        }
                    }
                }

                state.detailedInfo != null -> {
                    val details = state.detailedInfo!!
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!showOnlyTabs) {
                            AppInfoHeaderAndActions(
                                appInfo = details.appInfo,
                                isRoot = state.isRoot,
                                isShizuku = state.isShizuku,
                                isDhizuku = state.isDhizuku,
                                isInFreezer = state.isInFreezer,
                                skipRoutineFreezeConfirmation =
                                    state.skipRoutineFreezeConfirmation,
                                onAppAction = onAppAction,
                                onFreeze = { shouldFreeze ->
                                    viewModel.toggleFreezerState(
                                        packageName,
                                        details.appInfo.appName,
                                        shouldFreeze
                                    )
                                },
                                onToggleFreezerMembership = {
                                    viewModel.addOrRemoveFromFreezer(packageName)
                                },
                                onClearData = { viewModel.clearData(packageName) },
                                onManagePermissions = {
                                    onNavigateToPermissionManager(
                                        packageName,
                                        details.appInfo.appName ?: ""
                                    )
                                },
                                onUninstallTriggered = onBack,
                                sharedTransitionScope = sharedTransitionScope
                            )
                        }

                        if (!showOnlyHeaderAndActions) {
                            AppInfoDetailBody(details, state.obbProbe)
                        }
                    }
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

}

/**
 * The app identity block and its action row, plus every confirmation those actions raise.
 *
 * Self-contained on purpose. A host supplies the state and the handful of callbacks that actually
 * mutate something, and gets the freeze / uninstall / clear-data / export dialogs for free. That is
 * what lets more than one surface offer these actions without a second copy of four AlertDialogs
 * drifting out of sync with this one.
 *
 * [onFreeze] is the "do it" callback: this composable decides whether a confirmation has to come
 * first, the host decides what freezing means.
 *
 * [skipRoutineFreezeConfirmation] only ever narrows *which* freezes ask; it can never widen what a
 * confirmed freeze is allowed to do. Required rather than defaulted to `false`, so a new host has to
 * say where it reads the setting from instead of silently inheriting "always ask".
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppInfoHeaderAndActions(
    appInfo: AppInfo,
    isRoot: Boolean,
    isShizuku: Boolean,
    isDhizuku: Boolean,
    isInFreezer: Boolean,
    skipRoutineFreezeConfirmation: Boolean,
    onAppAction: (AppClickAction) -> Unit,
    onFreeze: (Boolean) -> Unit,
    onToggleFreezerMembership: () -> Unit,
    onClearData: () -> Unit,
    onManagePermissions: () -> Unit,
    onUninstallTriggered: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val context = LocalContext.current
    val packageName = appInfo.packageName

    var showClearDataConfirmation by remember { mutableStateOf(false) }
    var showUninstallConfirmation by remember { mutableStateOf(false) }
    var showFreezeConfirmation by remember { mutableStateOf(false) }
    var showReinstallWarning by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }

    // Hoisted so the header icon's tap and long-press shortcuts are the *same* lambdas the row's
    // Open and Settings actions get, exactly as `AppInfoSheet` does it. See `AppHeaderIcon`.
    val onLaunchApp: () -> Unit = { onAppAction(AppClickAction.Launch(appInfo)) }
    val onOpenSystemSettings: () -> Unit = { onAppAction(AppClickAction.AppInfoSettings(appInfo)) }

    Column(modifier = modifier) {
        AppDetailsHeader(
            appInfo = appInfo,
            onOpen = onLaunchApp,
            onOpenSettings = onOpenSystemSettings,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope
        )

        AppActionRow(
            appInfo = appInfo,
            isRoot = isRoot,
            isShizuku = isShizuku,
            isDhizuku = isDhizuku,
            isInFreezer = isInFreezer,
            onLaunch = onLaunchApp,
            onSystemSettings = onOpenSystemSettings,
            onFreezeToggle = { shouldFreeze ->
                // Unfreeze immediately. When freezing, only SYSTEM apps get the
                // safety-warning dialog (instability / reboot-loop risk); user
                // apps are safe to freeze directly. Not "mirrors AppInfoSheet
                // gating" any more — it is literally the same call, which is what
                // keeps the two from drifting.
                if (shouldFreeze &&
                    freezeNeedsConfirmation(appInfo, skipRoutineFreezeConfirmation)
                ) {
                    showFreezeConfirmation = true
                } else {
                    onFreeze(shouldFreeze)
                }
            },
            onSuspendToggle = { shouldSuspend ->
                if (shouldSuspend) onAppAction(AppClickAction.Suspend(appInfo))
                else onAppAction(AppClickAction.UnSuspend(appInfo))
            },
            onForceStop = { onAppAction(AppClickAction.Kill(appInfo)) },
            onManagePermissions = onManagePermissions,
            onToggleFreezerMembership = onToggleFreezerMembership,
            onClearCache = { onAppAction(AppClickAction.ClearCache(appInfo)) },
            onClearData = { showClearDataConfirmation = true },
            onFixStore = { showReinstallWarning = true },
            onUninstall = { showUninstallConfirmation = true },
            onShare = { onAppAction(AppClickAction.Share(appInfo)) },
            onExport = { showExportSheet = true },
            // Not optional in practice, whatever the defaulted parameter suggests. `MainScreen`
            // pushes this screen's route only where a detail pane exists, so on those layouts an
            // app-list tap lands here and never opens `AppInfoSheet` — leaving this out is not "the
            // sheet carries backup and the details screen does not", it is "the app list has no
            // route to backup at all on a tablet". Not *no* route anywhere: `FreezerScreen` hosts
            // `AppInfoSheet` with no layout gate, so a watchlisted app could already reach backup
            // through that tab. Every app that is not on the watchlist could not.
            onBackup = { showBackupSheet = true }
        )
    }

    // --- DIALOGS ---

    if (showClearDataConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearDataConfirmation = false },
            title = { Text(stringResource(R.string.clear_app_data_title)) },
            text = { Text(stringResource(R.string.dialog_clear_data_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearData()
                    showClearDataConfirmation = false
                }) {
                    Text(
                        stringResource(R.string.action_clear_data),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showUninstallConfirmation) {
        AppRiskDialog(
            app = appInfo,
            action = AppRiskAction.Uninstall,
            onConfirm = {
                if (appInfo.isSystem) {
                    onAppAction(AppClickAction.Uninstall(appInfo))
                } else {
                    val intent =
                        android.content.Intent(android.content.Intent.ACTION_DELETE).apply {
                            data = "package:$packageName".toUri()
                        }
                    context.startActivity(intent)
                }
                showUninstallConfirmation = false
                // Close the surface hosting these actions once uninstall is triggered
                onUninstallTriggered()
            },
            onDismiss = { showUninstallConfirmation = false }
        )
    }

    if (showFreezeConfirmation) {
        AppRiskDialog(
            app = appInfo,
            action = AppRiskAction.Freeze,
            onConfirm = {
                onFreeze(true)
                showFreezeConfirmation = false
            },
            onDismiss = { showFreezeConfirmation = false }
        )
    }

    if (showReinstallWarning) {
        // Fix Store re-installs the app declaring Play as its installer. It is a real reinstall:
        // signature mismatches and the loss of a sideloaded version are both on the table, so it
        // never runs off a single tap.
        AlertDialog(
            icon = {
                Icon(
                    painterResource(R.drawable.warning),
                    null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            onDismissRequest = { showReinstallWarning = false },
            title = { Text(stringResource(R.string.risk_warning_title)) },
            text = { Text(stringResource(R.string.risk_warning_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    onAppAction(AppClickAction.Reinstall(appInfo))
                    showReinstallWarning = false
                }) { Text(stringResource(R.string.proceed)) }
            },
            dismissButton = {
                TextButton(onClick = { showReinstallWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showExportSheet) {
        ExportBottomSheet(appInfo = appInfo, onDismiss = { showExportSheet = false })
    }

    // The same call `AppInfoSheet` makes, deliberately without a second copy of anything: the sheet
    // reaches its own view model through Koin and scopes it to its own composition, so hosting it is
    // one call and the per-app scoping cannot drift between the two surfaces.
    if (showBackupSheet) {
        AppBackupSheet(
            packageName = appInfo.packageName,
            appLabel = appInfo.appName ?: appInfo.packageName,
            onDismiss = { showBackupSheet = false }
        )
    }
}

/**
 * The tabbed detail body: everything below the action row.
 *
 * Takes a fully-loaded [DetailedAppInfo], so a host is free to render the header and actions from a
 * cheap [AppInfo] it already has and only pay for this once the details land.
 *
 * [obbProbe] is **required with no default**, deliberately. Both hosts drive this from the same
 * `AppInfoDetailsViewModel` that runs the probe, and a default of `null` reads as "still probing" —
 * so a host that forgot to pass it would compile, pay the privileged round-trip and then render an
 * empty card forever. That is not hypothetical: `AppInfoSheet` did exactly that while the parameter
 * was optional. Requiring it turns the omission into a compile error. (Compose lint also wants
 * `modifier` to be the first optional parameter, which an optional `obbProbe` ahead of it violates —
 * the two constraints agree here.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoDetailBody(
    details: DetailedAppInfo,
    obbProbe: ObbProbe?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // rememberSaveable so the active tab survives rotation / config change.
        var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
        val tabs = listOf(
            stringResource(R.string.tab_overview_title),
            stringResource(R.string.tab_components),
            stringResource(R.string.tab_libs_features),
            stringResource(R.string.action_permissions)
        )

        SecondaryScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            edgePadding = 0.dp,
            indicator = {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(selectedTab),
                    color = MaterialTheme.colorScheme.primary
                )
            },
            divider = {}
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> GeneralTabScreen(details, obbProbe)
            1 -> ComponentsTabScreen(details)
            2 -> LibsAndFeaturesTabScreen(details)
            3 -> PermissionsTabScreen(details.permissions)
        }
    }
}

@Composable
private fun AppDetailsHeader(
    appInfo: AppInfo,
    onOpen: () -> Unit,
    onOpenSettings: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val valueLabel = stringResource(R.string.value_label)
    val iconSize = 72.dp
    // The icon reserves this much transparent margin on every side for its glow, so the card's own
    // 16 dp of padding is already more than paid for on the icon's side. Spending it rather than
    // adding to it keeps the icon 16 dp from the card edge instead of 32, keeps the gap to the app
    // name at 16 rather than doubling it, and — the reason it is worth the arithmetic — leaves the
    // name/package/chips column the width it had. This header also renders in `MainScreen`'s narrow
    // list pane, where 32 dp is the difference between a one-line app name and a wrapped one.
    val iconGlowInset = appHeaderIconGlowInset(iconSize)
    val iconGap = (16.dp - iconGlowInset).coerceAtLeast(0.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
            .padding(start = iconGap, top = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier.sharedElement(
                    sharedContentState = rememberSharedContentState(key = "icon-${appInfo.packageName}"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        } else {
            Modifier
        }
        // Tap opens the app, long-press opens its system settings page — the same shortcuts the
        // sheet's header carries, on the same widget, because these two headers are one header on
        // two surfaces. The glow's default diameter scales off the icon, which is what keeps it
        // inside this card's clip without a per-surface number.
        AppHeaderIcon(
            appInfo = appInfo,
            onOpen = onOpen,
            onOpenSettings = onOpenSettings,
            size = iconSize,
            cornerRadius = 20.dp,
            contentPadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            imageModifier = sharedModifier
        )

        Spacer(modifier = Modifier.width(iconGap))

        Column(modifier = Modifier.weight(1f)) {
            val textSharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "name-${appInfo.packageName}"),
                        animatedVisibilityScope = animatedVisibilityScope
                    ).skipToLookaheadSize()
                }
            } else {
                Modifier
            }
            Text(
                text = appInfo.appName ?: stringResource(R.string.unknown),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.then(textSharedModifier)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Tap to copy, exactly as the sheet's header does — the two headers are the same
            // header on two surfaces, and one of them being inert is the kind of difference nobody
            // finds on purpose. Same reasoning for the click label and the 48 dp target; see the
            // note in AppInfoSheet. labelSmall with 2 dp of padding is the smaller of the two, so
            // if either needed the minimum size it was this one.
            Text(
                text = appInfo.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = firaMonoFontFamily,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = stringResource(R.string.cd_copy_package_name)) {
                        // Toast inside the coroutine, after the await — see AppInfoSheet for why.
                        // setClipEntry suspends, so a Toast beside the launch reports a copy that
                        // has not happened yet and may never happen if the scope is cancelled.
                        coroutineScope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(
                                    android.content.ClipData.newPlainText(
                                        valueLabel,
                                        appInfo.packageName
                                    )
                                )
                            )
                            Toast.makeText(
                                context,
                                R.string.toast_copy_saved,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                if (appInfo.isSystem) {
                    StatusChip(
                        text = stringResource(R.string.chip_system),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        textColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                } else {
                    StatusChip(
                        text = stringResource(R.string.chip_user),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        textColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                if (!appInfo.enabled) {
                    StatusChip(
                        text = stringResource(R.string.frozen),
                        color = MaterialTheme.colorScheme.errorContainer
                    )
                }

                if (appInfo.isSuspended) {
                    StatusChip(
                        text = stringResource(R.string.suspended),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    )
                }

                if (appInfo.isDebuggable) {
                    StatusChip(
                        text = stringResource(R.string.chip_debug),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }

                appInfo.bloatRecommendation?.let { recommendation ->
                    val (chipColor, chipTextColor) = getBloatRecommendationColors(recommendation)
                    StatusChip(
                        text = recommendation,
                        color = chipColor,
                        textColor = chipTextColor
                    )
                }
                StatusChip(
                    text = stringResource(R.string.version_format, appInfo.versionName ?: ""),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GeneralTabScreen(details: DetailedAppInfo, obbProbe: ObbProbe?) {
    val appInfo = details.appInfo
    val context = LocalContext.current
    val installTime = remember(appInfo.firstInstallTime, context) { formatTime(appInfo.firstInstallTime, context) }
    val lastUpdateTime = remember(appInfo.lastUpdateTime, context) { formatTime(appInfo.lastUpdateTime, context) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        appInfo.bloatRecommendation?.let { recommendation ->
            item {
                InfoCard(
                    title = stringResource(R.string.debloat_recommendation),
                    value = recommendation
                )
            }
        }

        // Directly under the tier, because on its own a tier is a verdict without a reason: "Expert"
        // does not tell you that the package is the vendor's OTA client. Blank rather than absent is
        // the common shape here — 64 of the 5364 UAD entries carry an empty description — and an
        // "Why this is flagged" card with nothing under it is worse than no card, so it is dropped.
        appInfo.bloatDescription?.takeIf { it.isNotBlank() }?.let { description ->
            item {
                InfoCard(
                    title = stringResource(R.string.info_bloat_description),
                    value = description,
                    monospace = false
                )
            }
        }

        item {
            InfoCard(
                title = stringResource(R.string.info_package_name),
                value = appInfo.packageName
            )
        }
        item {
            InfoCard(
                title = stringResource(R.string.info_app_version),
                value = "${appInfo.versionName} (${appInfo.versionCode})"
            )
        }
        item {
            InfoCard(
                title = stringResource(R.string.info_sdk_details),
                value = stringResource(
                    R.string.info_sdk_details_format,
                    appInfo.targetSdk,
                    appInfo.minSdk
                )
            )
        }
        appInfo.installSize?.let { bytes ->
            item {
                InfoCard(
                    title = stringResource(R.string.info_app_size),
                    value = android.text.format.Formatter.formatShortFileSize(context, bytes)
                )
            }
        }
        item {
            InfoCard(
                title = stringResource(R.string.info_installer_source),
                value = appInfo.installerPackageName ?: stringResource(R.string.unknown)
            )
        }
        item {
            InfoCard(
                title = stringResource(R.string.info_install_time),
                value = installTime
            )
        }
        item {
            InfoCard(
                title = stringResource(R.string.info_last_update_time),
                value = lastUpdateTime
            )
        }
        item {
            InfoCard(
                title = stringResource(R.string.info_apk_path),
                value = appInfo.sourceDir ?: stringResource(R.string.not_available)
            )
        }
        item {
            InfoCard(
                title = stringResource(R.string.info_data_dir),
                value = appInfo.dataDir ?: stringResource(R.string.not_available)
            )
        }
        // Not appInfo.obbFilePath: that is computed with File(...).exists(), which returns false
        // for another package's OBB directory on Android 11+ regardless of whether one exists —
        // so this card was simply absent for every game on a modern device.
        when (val probe = obbProbe) {
            null -> Unit // still probing
            ObbProbe.None -> item {
                InfoCard(
                    title = stringResource(R.string.info_obb_dir),
                    value = stringResource(R.string.info_obb_none)
                )
            }

            is ObbProbe.Undetermined -> item {
                InfoCard(
                    title = stringResource(R.string.info_obb_dir),
                    value = stringResource(R.string.info_obb_unknown)
                )
            }

            // otherEntryCount is deliberately not rendered here. It answers "what won't be packed",
            // which is only actionable while choosing an export format, so the export sheet shows it
            // and this read-only card does not. The consequence is that an OBB directory holding
            // nothing but non-.obb content reads "0 B of game data" — accurate about the expansion
            // files, which is what this card is about, and still discloses that the directory exists.
            is ObbProbe.Present -> item {
                InfoCard(
                    title = stringResource(R.string.info_obb_dir),
                    value = stringResource(
                        R.string.info_obb_present,
                        "Android/obb/${appInfo.packageName}",
                        Formatter.formatShortFileSize(
                            context,
                            probe.files.sumOf { it.sizeBytes }
                        )
                    )
                )
            }
        }
        if (appInfo.sharedDataDir.isNotEmpty()) {
            item {
                InfoCard(
                    title = stringResource(R.string.info_shared_data_dir),
                    value = appInfo.sharedDataDir
                )
            }
        }
        details.signatureSha256?.let { sha256 ->
            item {
                InfoCard(title = stringResource(R.string.info_signature_sha256), value = sha256)
            }
        }
    }
}

@Composable
private fun PermissionsTabScreen(permissions: List<PermissionDetail>) {
    // rememberSaveable so the search query survives rotation / config change.
    var searchQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val filteredPermissions = remember(searchQuery, permissions) {
        permissions.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    (it.label?.contains(searchQuery, ignoreCase = true) ?: false)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.permissions_search)) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.round_search),
                    contentDescription = null
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
            )
        )

        if (filteredPermissions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.no_permissions_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredPermissions) { perm ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val simpleName = perm.name.substringAfterLast('.')
                            val displayName = perm.label ?: simpleName
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = perm.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = firaMonoFontFamily
                            )
                            if (perm.protectionLevel.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(
                                        R.string.permission_protection_level,
                                        perm.protectionLevel
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            perm.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(horizontalAlignment = Alignment.End) {
                            StatusChip(
                                text = if (perm.isGranted) stringResource(R.string.permission_state_granted) else stringResource(
                                    R.string.permission_state_denied
                                ),
                                color = if (perm.isGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                                textColor = if (perm.isGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComponentsTabScreen(details: DetailedAppInfo) {
    val packageName = details.appInfo.packageName
    // Keyed on the package, so opening a second app's details from the same host (the wide-layout
    // detail pane reuses one host) gets its own instance rather than the previous app's ledger and
    // in-flight state. Same scoping AppBackupSheet already uses.
    val viewModel: ComponentControlViewModel = koinViewModel(key = packageName)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(packageName, details.components) {
        viewModel.load(packageName, details.components)
    }

    ObserveAsEvents(viewModel.events) { msg ->
        Toast.makeText(context, msg.asString(context), Toast.LENGTH_SHORT).show()
    }

    // rememberSaveable so the search query survives rotation / config change.
    var searchQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.search_components_placeholder)) },
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.round_search),
                    contentDescription = null
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
            )
        )

        val filtered = remember(searchQuery, state.snapshot) {
            val filter = { items: List<ComponentDetail> ->
                if (searchQuery.isEmpty()) items
                else items.filter { it.className.contains(searchQuery, ignoreCase = true) }
            }
            ComponentLists(
                activities = filter(state.snapshot.activities),
                services = filter(state.snapshot.services),
                receivers = filter(state.snapshot.receivers),
                providers = filter(state.snapshot.providers)
            )
        }

        // rememberSaveable so the expanded/collapsed sections survive rotation / config changes.
        var activitiesExpanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        var servicesExpanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        var receiversExpanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        var providersExpanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

        val clipboard = LocalClipboard.current
        val coroutineScope = rememberCoroutineScope()
        val classNameLabel = stringResource(R.string.class_name_label)
        val onCopyClassName: (String) -> Unit = { className ->
            coroutineScope.launch {
                clipboard.setClipEntry(
                    ClipEntry(
                        android.content.ClipData.newPlainText(classNameLabel, className)
                    )
                )
            }
            Toast.makeText(
                context,
                (R.string.toast_copied_class_name),
                Toast.LENGTH_SHORT
            ).show()
        }

        ComponentControlBanner(
            blocker = state.capability.blocker,
            restrictedCount = state.restrictedCount,
            onRestoreAll = viewModel::requestRestoreAll
        )

        val activitiesTitle =
            stringResource(R.string.section_activities_title, filtered.activities.size)
        val servicesTitle =
            stringResource(R.string.section_services_title, filtered.services.size)
        val receiversTitle =
            stringResource(R.string.section_receivers_title, filtered.receivers.size)
        val providersTitle =
            stringResource(R.string.section_providers_title, filtered.providers.size)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            componentSection(
                keyPrefix = "activities",
                type = ComponentType.ACTIVITY,
                title = activitiesTitle,
                items = filtered.activities,
                state = state,
                expanded = activitiesExpanded,
                onToggle = { activitiesExpanded = !activitiesExpanded },
                onCopy = onCopyClassName,
                viewModel = viewModel
            )
            componentSection(
                keyPrefix = "services",
                type = ComponentType.SERVICE,
                title = servicesTitle,
                items = filtered.services,
                state = state,
                expanded = servicesExpanded,
                onToggle = { servicesExpanded = !servicesExpanded },
                onCopy = onCopyClassName,
                viewModel = viewModel
            )
            componentSection(
                keyPrefix = "receivers",
                type = ComponentType.RECEIVER,
                title = receiversTitle,
                items = filtered.receivers,
                state = state,
                expanded = receiversExpanded,
                onToggle = { receiversExpanded = !receiversExpanded },
                onCopy = onCopyClassName,
                viewModel = viewModel
            )
            componentSection(
                keyPrefix = "providers",
                type = ComponentType.PROVIDER,
                title = providersTitle,
                items = filtered.providers,
                state = state,
                expanded = providersExpanded,
                onToggle = { providersExpanded = !providersExpanded },
                onCopy = onCopyClassName,
                viewModel = viewModel
            )
        }
    }

    state.pendingConsent?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::onDisclaimerDismissed,
            title = { Text(stringResource(R.string.component_disclaimer_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.component_disclaimer_message,
                        pending.component.shortName
                    )
                )
            },
            confirmButton = {
                Button(onClick = viewModel::onDisclaimerConfirmed) {
                    Text(stringResource(R.string.component_disclaimer_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDisclaimerDismissed) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (state.showRestoreAllConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestoreAll,
            title = { Text(stringResource(R.string.component_restore_all_title)) },
            text = { Text(stringResource(R.string.component_restore_all_message)) },
            confirmButton = {
                Button(onClick = viewModel::confirmRestoreAll) {
                    Text(stringResource(R.string.component_restore_all_action))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRestoreAll) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * The one-line status strip above the four sections.
 *
 * Renders nothing when there is nothing to say — the common case for a rooted device that has not
 * changed anything yet. When Thor cannot act it explains why *once*, at the top, rather than
 * repeating a disabled control on every one of several hundred rows; and when Thor has changed
 * something it says how much and offers the single undo.
 */
@Composable
private fun ComponentControlBanner(
    blocker: ComponentControlBlocker,
    restrictedCount: Int,
    onRestoreAll: () -> Unit
) {
    val blockerText = when (blocker) {
        ComponentControlBlocker.NONE -> null
        // "Still checking" is not a refusal, and saying so would be wrong a fraction of a second
        // later. The controls are simply inert until the probe lands.
        ComponentControlBlocker.NOT_READY -> null
        ComponentControlBlocker.NO_PRIVILEGE ->
            stringResource(R.string.component_blocker_no_privilege)

        ComponentControlBlocker.SHIZUKU_NOT_ROOT ->
            stringResource(R.string.component_blocker_shizuku_not_root)

        ComponentControlBlocker.DHIZUKU_UNSUPPORTED ->
            stringResource(R.string.component_blocker_dhizuku)
    }

    if (blockerText == null && restrictedCount == 0) return

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (blockerText != null) {
            Text(
                text = blockerText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (restrictedCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = androidx.compose.ui.res.pluralStringResource(
                        R.plurals.component_restricted_count,
                        restrictedCount,
                        restrictedCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRestoreAll) {
                    Text(stringResource(R.string.component_restore_all_action))
                }
            }
        }
    }
}

/**
 * Emits a collapsible component section (activities / services / receivers / providers) directly
 * into the parent [LazyColumn]. The header is a single lazy item and, when expanded, every
 * component row is emitted as its own lazy item via [itemsIndexed] so only the on-screen rows are
 * composed and measured. Previously each section was one LazyColumn item whose expanded body
 * iterated the whole list inside a [Column], composing/measuring every row eagerly on the main
 * thread (visible jank / potential OOM for very large system apps).
 */
private fun LazyListScope.componentSection(
    keyPrefix: String,
    type: ComponentType,
    title: String,
    items: List<ComponentDetail>,
    state: ComponentControlUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: (String) -> Unit,
    viewModel: ComponentControlViewModel
) {
    item(key = "$keyPrefix-header", contentType = "component_header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(
                    if (expanded) RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                    else RoundedCornerShape(20.dp)
                )
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable { onToggle() }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                painter = painterResource(
                    if (expanded) R.drawable.arrow_upward else R.drawable.arrow_downward
                ),
                contentDescription = if (expanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (expanded) {
        if (items.isEmpty()) {
            item(key = "$keyPrefix-empty", contentType = "component_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.components_none_declared),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        } else {
            itemsIndexed(
                items = items,
                // Index is part of the key: a package's component list can contain duplicate class
                // names (PackageManager returns them un-deduped), and a duplicate LazyColumn key
                // throws IllegalArgumentException and crashes the screen.
                key = { index, component -> "$keyPrefix-$index-${component.className}" },
                contentType = { _, _ -> "component" }
            ) { index, component ->
                val isLast = index == items.lastIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isLast) Modifier.clip(
                                RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
                            ) else Modifier
                        )
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = if (isLast) 12.dp else 0.dp)
                ) {
                    ComponentRow(
                        type = type,
                        component = component,
                        state = state,
                        onCopy = onCopy,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    item(key = "$keyPrefix-spacer") {
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * One component: its name, what is unusual about it, and what can be done to it.
 *
 * The whole row stays tap-to-copy, as it always was — the class name is the reason most people open
 * this tab, and moving that onto the overflow to make room for the new controls would trade a
 * one-tap action people already use for one they may never use. The new affordances are additive: a
 * trailing Open button for activities, and an overflow for everything else.
 */
@Composable
private fun ComponentRow(
    type: ComponentType,
    component: ComponentDetail,
    state: ComponentControlUiState,
    onCopy: (String) -> Unit,
    viewModel: ComponentControlViewModel
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    val capability = state.capability
    val isBusy = state.busyClassName == component.className
    val isDrifted = state.isDrifted(component)
    val isThors = state.overrides.containsKey(component.className)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCopy(component.className) }
            .padding(vertical = 6.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = component.className,
                style = MaterialTheme.typography.labelSmall,
                color = if (component.enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = firaMonoFontFamily
            )
            ComponentBadges(
                component = component,
                isThors = isThors,
                isDrifted = isDrifted
            )
        }

        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(16.dp),
                strokeWidth = 2.dp
            )
        } else if (type == ComponentType.ACTIVITY) {
            val canLaunch = capability.canLaunch(component) && component.enabled
            TextButton(
                onClick = { viewModel.launch(context, component) },
                enabled = canLaunch
            ) {
                Text(
                    text = if (component.launchRequiresRoot)
                        stringResource(R.string.component_action_force_open)
                    else stringResource(R.string.component_action_open),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = stringResource(
                        R.string.cd_component_actions,
                        component.shortName
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.component_action_copy)) },
                    onClick = {
                        menuExpanded = false
                        onCopy(component.className)
                    }
                )
                // Offered for an *exported* activity too: an ordinary startActivity still fails when
                // the target app is frozen or suspended, and the shell route is the way through.
                if (type == ComponentType.ACTIVITY &&
                    capability.canForceLaunch &&
                    !component.launchRequiresRoot
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.component_action_force_open)) },
                        onClick = {
                            menuExpanded = false
                            viewModel.forceLaunch(component)
                        }
                    )
                }
                if (type == ComponentType.SERVICE && capability.canStopService) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.component_action_stop_now)) },
                        onClick = {
                            menuExpanded = false
                            viewModel.stopService(component)
                        }
                    )
                }
                if (capability.canSetComponentState) {
                    if (component.enabled) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.component_action_disable)) },
                            onClick = {
                                menuExpanded = false
                                viewModel.requestDisable(type, component)
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.component_action_enable)) },
                            onClick = {
                                menuExpanded = false
                                viewModel.enable(component)
                            }
                        )
                    }
                    if (component.isOverridden) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.component_action_reset)) },
                            onClick = {
                                menuExpanded = false
                                viewModel.resetToDefault(component)
                            }
                        )
                    }
                }
                if (isDrifted) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.component_action_forget)) },
                        onClick = {
                            menuExpanded = false
                            viewModel.forget(component)
                        }
                    )
                }
            }
        }
    }
}

/**
 * The chips under a component's name.
 *
 * Only what is *unusual* gets a chip. An enabled, exported activity that ships enabled is the
 * overwhelming majority of rows and carries none — a list where every row is decorated tells the
 * reader nothing about which rows to look at.
 */
@Composable
private fun ComponentBadges(
    component: ComponentDetail,
    isThors: Boolean,
    isDrifted: Boolean
) {
    val chips = buildList {
        when {
            // Thor's own row wins over the generic "disabled": it is the one the user can undo, and
            // saying who did it is the entire point of keeping the ledger.
            isThors && !component.enabled -> add(
                stringResource(R.string.component_badge_restricted) to
                        MaterialTheme.colorScheme.tertiaryContainer
            )

            isDrifted -> add(
                stringResource(R.string.component_badge_drift) to
                        MaterialTheme.colorScheme.secondaryContainer
            )

            !component.enabled -> add(
                stringResource(R.string.component_badge_disabled) to
                        MaterialTheme.colorScheme.errorContainer
            )
        }
        if (!component.exported) {
            add(
                stringResource(R.string.component_badge_not_exported) to
                        MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
        if (!component.manifestDefaultEnabled) {
            add(
                stringResource(R.string.component_badge_off_by_default) to
                        MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
    }
    if (chips.isEmpty()) return

    Row(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEach { (text, color) ->
            StatusChip(text = text, color = color)
        }
    }
}

@Composable
private fun LibsAndFeaturesTabScreen(details: DetailedAppInfo) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.section_native_libs_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (details.nativeLibs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_native_libs_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    details.nativeLibs.forEach { lib ->
                        Text(
                            text = lib,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = firaMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.section_req_features_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (details.reqFeatures.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_req_features_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    details.reqFeatures.forEach { feature ->
                        Text(
                            text = feature,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = firaMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * One labelled fact about the app, tap to copy.
 *
 * [monospace] defaults to the heuristic this card has always used — a value with a `/` or a `.` in
 * it is an identifier or a path, and reads better in Fira Mono. The parameter exists because the
 * heuristic is wrong for exactly one caller: the UAD description is English prose, and prose
 * containing a full stop is the common case, not the exception.
 */
@Composable
private fun InfoCard(
    title: String,
    value: String,
    monospace: Boolean = value.contains("/") || value.contains(".")
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val valueLabel = stringResource(R.string.value_label)
    // Labelled with the card's own title, so every card announces what it copies — "Copy
    // Package name", "Copy Installer" — rather than the generic "activate" TalkBack falls back to.
    // A fixed "Copy value" would be a verb without an object on a screen that is a list of values.
    val copyLabel = stringResource(R.string.cd_copy_field, title)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClickLabel = copyLabel) {
                // Toast after the await, not beside the launch — setClipEntry suspends, and a
                // success message that outruns the write tells the user their clipboard holds
                // something it does not. Same fix as the two package-name headers; this helper is
                // the third surface and backs all fourteen InfoCard call sites on this screen.
                coroutineScope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(
                            android.content.ClipData.newPlainText(valueLabel, value)
                        )
                    )
                    Toast.makeText(
                        context,
                        R.string.toast_copy_saved,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (monospace) firaMonoFontFamily else bodyFontFamily
        )
    }
}

private data class ComponentLists(
    val activities: List<ComponentDetail>,
    val services: List<ComponentDetail>,
    val receivers: List<ComponentDetail>,
    val providers: List<ComponentDetail>
)

/**
 * An install timestamp as a medium date and time.
 *
 * The locale comes from [AppLocale.localeOf] — the `Configuration` this very [context] resolves its
 * resources with — and not from `Locale.getDefault()`. Below API 33 the process default is the
 * *device's* language regardless of what the in-app picker chose, so the default put an English
 * date directly beneath the French label produced by the `stringResource` two lines up. On 33+ the
 * platform merges the per-app locale into the process default and the two agree, so nothing changes
 * there.
 *
 * This is also what makes the row consistent with the APK size beside it, which already goes
 * through `android.text.format.Formatter.formatShortFileSize(context, …)` and therefore has always
 * read its locale off the Context.
 */
private fun formatTime(timestamp: Long, context: Context): String {
    if (timestamp == 0L) return context.getString(R.string.not_available)
    return try {
        val formatter = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.MEDIUM,
            AppLocale.localeOf(context)
        )
        formatter.format(Date(timestamp))
    } catch (_: Exception) {
        context.getString(R.string.not_available)
    }
}
