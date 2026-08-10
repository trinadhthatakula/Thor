// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import coil3.compose.AsyncImage
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.freezeNeedsConfirmation
import com.valhalla.thor.presentation.appList.AppInfoDetailBody
import com.valhalla.thor.presentation.appList.AppInfoDetailsViewModel
import com.valhalla.thor.presentation.appList.ExportBottomSheet
import com.valhalla.thor.presentation.utils.AppIconModel
import com.valhalla.thor.presentation.utils.getBloatRecommendationColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * The app sheet: identity, actions, and — once dragged up — the full tabbed detail body.
 *
 * There is one surface here, not two. It opens at the partial detent showing the header and the
 * action row; dragging it to [SheetValue.Expanded] reveals [AppInfoDetailBody] in the same sheet,
 * replacing the old jump to a separate details screen. The "Details" action reaches the same place
 * without a drag — see `onOpenDetails` for why settling the sheet is only half of that.
 *
 * **Layout.** Two requirements pull against each other here.
 *
 * The content has to reserve the full height ([Modifier.fillMaxSize]) from the very first frame.
 * material3 places the partial detent at `fullHeight - min(fullHeight / 2, sheetHeight)`, so a
 * wrap-height sheet whose content is shorter than half the window produces a partial anchor that
 * coincides with the expanded one — and `shouldPromoteToExpanded` then snaps the sheet straight to
 * Expanded. The anchors also have to stay *stable*: they must not move when the detail body
 * arrives, or the sheet re-settles under the user's finger.
 *
 * But a height-bounded [Column] does not clip its overflow, it *squeezes* it — non-weighted
 * children are measured against whatever is left, and a weighted child gets
 * `(target - fixed).coerceAtLeast(0)`. On a short window (landscape, split screen, a large font
 * scale) the header and action row alone outgrow the sheet, so a weighted detail body would measure
 * 0 dp and the action labels would be squeezed away with no gesture that recovers them. Measured on
 * a landscape Pixel-class window: ~287 dp of content area against ~340 dp of header plus actions.
 *
 * So the column fills the height *and* scrolls, and the detail body is sized against the sheet's own
 * height rather than against the leftover. Everything stays reachable at every window size, the
 * detail body always gets a full screen's worth of room for its tab row and lists, and the sheet's
 * measured height stops depending on its content — the anchors no longer move when the body
 * arrives. The sheet's nested-scroll connection keeps the gesture continuous: an upward drag
 * expands the sheet first, then scrolls the header away to bring the detail body up.
 *
 * **Loading.** [AppInfoDetailsViewModel.loadAppDetails] parses the manifest, components and
 * permissions — expensive enough that the old code hid it behind a user preference. It is gated on
 * the sheet actually heading for Expanded, so opening the sheet to tap Force-stop costs nothing.
 * The gate is the *data*, never the layout.
 *
 * The view model is scoped to this composable via [rememberViewModelStoreOwner] rather than to the
 * host, so the parsed detail is released when the sheet closes. That holds only as long as the
 * sheet re-enters composition after a configuration change: `rememberViewModelStoreProvider` skips
 * its cleanup while the parent lifecycle is DESTROYED, on the assumption the composable comes back.
 * Both hosts therefore keep the selected package in `rememberSaveable`; a plain `remember` there
 * would strand this store, and the [DetailedAppInfo][com.valhalla.thor.domain.model.DetailedAppInfo]
 * in it, on the host's own store until the nav entry pops.
 *
 * It owns the detail data and nothing else: every action still routes out through [onAppAction], so
 * the host keeps owning what freezing means and stays the single owner of the "Frozen — Add to
 * Freezer?" prompt.
 *
 * Freezer membership is host state, not sheet state: [isInFreezer] and [onToggleFreezerMembership]
 * pass straight through to [AppActionRow], where a null callback hides the action outright, so a
 * host that does not track membership offers nothing it cannot honour. The Freezer tab does track
 * it, and derives the selected app from `state.freezerApps` — removing an app from the freezer
 * drops it out of that list and takes this sheet with it. That host therefore dismisses on the
 * spot, so the teardown is its own decision rather than a race with the next emission.
 *
 * The membership control is the one action whose *meaning* the host sets rather than just its
 * effect, and the two hosts disagree today: the Apps tab moves watchlist membership and nothing
 * else, while the Freezer tab also restores the app on the way out. Each matches the surface it
 * replaced, so neither is a regression, but one label naming two operations is a product question
 * rather than a wiring one — written up in `docs/follow-ups/freezer-membership-toggle-semantics.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoSheet(
    appInfo: AppInfo,
    isRoot: Boolean = false,
    isShizuku: Boolean = false,
    isDhizuku: Boolean = false,
    isInFreezer: Boolean = false,
    onDismiss: () -> Unit,
    onAppAction: (AppClickAction) -> Unit = {},
    onToggleFreezerMembership: (() -> Unit)? = null
) {
    // Default enabledValues = {Hidden, PartiallyExpanded, Expanded}. The sheet opens at the partial
    // detent, which material3 pins at min(windowHeight / 2, contentHeight) — there is no peek
    // parameter, so whether the action row survives above the fold is a measurement, not a setting.
    //
    // The previous `enabledValues = {Expanded, Hidden}` carried a comment blaming
    // `skipPartiallyExpanded` for an "offset not initialized" crash. That parameter is not in this
    // file (it belonged to the deprecated `rememberModalBottomSheetState`, which pins the
    // deterministic-anchor flag off); the note predates the migration and no longer describes
    // anything here.
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    // Hoisted out of the Column modifier: the "Details" action has to drive this as well as the
    // sheet state, because expanding alone leaves the detail body below the fold.
    val contentScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Not keyed by package: both hosts interpose a null selection between two apps — the sheet's own
    // window is touch-modal, so a list tap cannot land while it is up — so the slot is never reused
    // across packages. Keying it would only buy a per-package entry on the host's ViewModelStore
    // that nothing ever removes; the load below is keyed on the package name, which is what
    // actually keeps view model and argument in step.
    val detailsViewModel = koinViewModel<AppInfoDetailsViewModel>(
        viewModelStoreOwner = rememberViewModelStoreOwner()
    )
    val detailsState by detailsViewModel.uiState.collectAsStateWithLifecycle()

    // Sticky: once the details have been asked for they stay on screen, so collapsing back to the
    // partial detent does not throw the work away. rememberSaveable carries that across a
    // configuration change, where the store — and so the parsed detail — survives with it.
    var detailsRequested by rememberSaveable { mutableStateOf(false) }
    // targetValue, not currentValue: the load starts as the drag settles rather than after it
    // lands, so the body is usually ready by the time it is on screen.
    LaunchedEffect(sheetState.targetValue) {
        if (sheetState.targetValue == SheetValue.Expanded) detailsRequested = true
    }
    // Guarded on what the view model already holds, not just on the keys: re-entering composition
    // after a rotation would otherwise re-run the whole manifest/component/permission parse over
    // data that is still right there.
    LaunchedEffect(detailsRequested, appInfo.packageName) {
        if (detailsRequested &&
            detailsState.detailedInfo?.appInfo?.packageName != appInfo.packageName
        ) {
            detailsViewModel.loadAppDetails(appInfo.packageName)
        }
    }

    var showUninstallConfirmation by remember { mutableStateOf(false) }
    var showReinstallWarning by remember { mutableStateOf(false) }
    var showClearDataConfirmation by remember { mutableStateOf(false) }
    var showFreezeConfirmation by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    val paneTitleText = appInfo.appName ?: appInfo.packageName

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp
        // contentWindowInsets is left at its default. The Components and Permissions tabs each
        // carry a search field, but the default already covers the keyboard: modalWindowInsets is
        // safeDrawing.only(Top + Bottom), and safeDrawing is systemBars ∪ ime ∪ displayCutout.
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Against the sheet's own height, not against what the header leaves over — see the
            // layout note on this function.
            val detailBodyHeight = maxHeight

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(contentScrollState)
                    .semantics { paneTitle = paneTitleText },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. Header (Icon + Title)
                AppHeader(appInfo)

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Action Buttons (Scrollable Row)
                AppActionRow(
                    appInfo = appInfo,
                    isRoot = isRoot,
                    isShizuku = isShizuku,
                    isDhizuku = isDhizuku,
                    isInFreezer = isInFreezer,
                    onLaunch = {
                        onAppAction(AppClickAction.Launch(appInfo))
                        onDismiss()
                    },
                    onSystemSettings = { onAppAction(AppClickAction.AppInfoSettings(appInfo)) },
                    onFreezeToggle = { shouldFreeze ->
                        // Only SYSTEM apps get the safety-warning dialog; unfreezing and user apps
                        // go straight through. `freezeNeedsConfirmation` also answers the newer
                        // half — whether the user has switched off the routine-tier confirmation —
                        // and it is the same call `AppInfoHeaderAndActions` makes, so the two
                        // surfaces cannot drift apart on when the dialog appears.
                        if (shouldFreeze &&
                            freezeNeedsConfirmation(
                                appInfo,
                                detailsState.skipRoutineFreezeConfirmation
                            )
                        ) {
                            showFreezeConfirmation = true
                        } else {
                            onAppAction(
                                if (shouldFreeze) AppClickAction.Freeze(appInfo)
                                else AppClickAction.UnFreeze(appInfo)
                            )
                            onDismiss()
                        }
                    },
                    onSuspendToggle = { shouldSuspend ->
                        onAppAction(
                            if (shouldSuspend) AppClickAction.Suspend(appInfo)
                            else AppClickAction.UnSuspend(appInfo)
                        )
                    },
                    onForceStop = { onAppAction(AppClickAction.Kill(appInfo)) },
                    onManagePermissions = { onAppAction(AppClickAction.ManagePermissions(appInfo)) },
                    onToggleFreezerMembership = onToggleFreezerMembership,
                    onClearCache = { onAppAction(AppClickAction.ClearCache(appInfo)) },
                    onClearData = { showClearDataConfirmation = true },
                    onFixStore = { showReinstallWarning = true },
                    onUninstall = {
                        if (appInfo.isSystem) showUninstallConfirmation = true
                        else {
                            onAppAction(AppClickAction.Uninstall(appInfo))
                            onDismiss()
                        }
                    },
                    onShare = { onAppAction(AppClickAction.Share(appInfo)) },
                    onExport = { showExportSheet = true },
                    // Details no longer leaves for another screen — it brings the body up in place,
                    // which matters because dragging is not an option for every user. The sheet's
                    // own semantics expand action covers assistive tech; this covers everyone else.
                    //
                    // Both halves of the drag, not just the first. `expand()` only moves the sheet
                    // anchor, and it is a plain no-op once currentValue is already Expanded — while
                    // on a short window (landscape, split screen, a large font scale) the action row
                    // is *only* reachable from Expanded, so on its own this control would be a
                    // guaranteed nothing there. The nested-scroll connection makes a real drag
                    // continue into the scroll column once the sheet settles; this does that part
                    // explicitly.
                    onOpenDetails = {
                        detailsRequested = true
                        scope.launch {
                            sheetState.expand()
                            // maxValue is exactly the header + action row: the body is one viewport
                            // tall and the last child, so scrolling to the end lands its top at the
                            // top of the viewport. It stays 0 until the body has been composed and
                            // measured — hence the wait, which is cancelled with the composition.
                            contentScrollState.animateScrollTo(
                                snapshotFlow { contentScrollState.maxValue }.first { it > 0 }
                            )
                        }
                    }
                )

                // 3. The detail body, below the fold until the sheet is expanded. Only added once
                // it has been asked for: an empty screen-height Box would otherwise let the column
                // scroll into nothing.
                if (detailsRequested) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(detailBodyHeight)
                    ) {
                        val details = detailsState.detailedInfo
                        when {
                            // Kept ahead of the error and loading branches so a failed or in-flight
                            // refresh never blanks details that are already on screen.
                            // obbProbe is passed explicitly even though it is defaulted. It is the
                            // same view model that ran the probe, so omitting it would pay the
                            // privileged round-trip and then render the card's "still probing"
                            // branch forever — on the surface most users reach, not the full screen.
                            details != null -> AppInfoDetailBody(
                                details = details,
                                obbProbe = detailsState.obbProbe,
                                modifier = Modifier.fillMaxSize()
                            )

                            detailsState.errorMessage != null -> Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = detailsState.errorMessage?.asString(context)
                                        ?: stringResource(R.string.unknown_error_occurred),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { detailsViewModel.loadAppDetails(appInfo.packageName) }) {
                                    Text(stringResource(R.string.retry_label))
                                }
                            }

                            else -> CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }

    // --- OVERLAYS ---

    if (showExportSheet) {
        ExportBottomSheet(appInfo = appInfo, onDismiss = { showExportSheet = false })
    }

    if (showClearDataConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearDataConfirmation = false },
            icon = {
                Icon(
                    painterResource(R.drawable.danger),
                    null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.clear_app_data_title)) },
            text = { Text(stringResource(R.string.clear_app_data_desc, appInfo.appName ?: "")) },
            confirmButton = {
                TextButton(onClick = {
                    onAppAction(AppClickAction.ClearData(appInfo))
                    showClearDataConfirmation = false
                    onDismiss()
                }) { Text(stringResource(R.string.clear_all_data)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClearDataConfirmation = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showUninstallConfirmation) {
        AppRiskDialog(
            app = appInfo,
            action = AppRiskAction.Uninstall,
            onConfirm = {
                onAppAction(AppClickAction.Uninstall(appInfo))
                showUninstallConfirmation = false
                onDismiss()
            },
            onDismiss = { showUninstallConfirmation = false }
        )
    }

    if (showFreezeConfirmation) {
        AppRiskDialog(
            app = appInfo,
            action = AppRiskAction.Freeze,
            onConfirm = {
                onAppAction(AppClickAction.Freeze(appInfo))
                showFreezeConfirmation = false
                onDismiss()
            },
            onDismiss = { showFreezeConfirmation = false }
        )
    }

    if (showReinstallWarning) {
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
            text = {
                Text(stringResource(R.string.risk_warning_desc))
            },
            confirmButton = {
                TextButton(onClick = {
                    onAppAction(AppClickAction.Reinstall(appInfo))
                    showReinstallWarning = false
                    onDismiss()
                }) { Text(stringResource(R.string.proceed)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showReinstallWarning = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun AppHeader(appInfo: AppInfo) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val valueLabel = stringResource(R.string.value_label)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        // Icon with a nice background
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = AppIconModel(appInfo.packageName),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(24.dp))

        // Title
        Text(
            text = appInfo.appName ?: stringResource(R.string.unknown),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            letterSpacing = (-1).sp,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(8.dp))

        // Metadata Chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (appInfo.splitPublicSourceDirs.isNotEmpty()) {
                StatusChip(
                    text = stringResource(R.string.status_split),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                )
            }
            if (!appInfo.enabled) {
                StatusChip(
                    text = stringResource(R.string.status_frozen),
                    color = MaterialTheme.colorScheme.errorContainer
                )
            }
            if (appInfo.isSuspended) {
                StatusChip(
                    text = stringResource(R.string.status_suspended),
                    color = MaterialTheme.colorScheme.secondaryContainer
                )
            }
            appInfo.bloatRecommendation?.let { recommendation ->
                val (color, textColor) = getBloatRecommendationColors(recommendation)
                StatusChip(
                    text = recommendation,
                    color = color,
                    textColor = textColor
                )
            }
            StatusChip(
                text = stringResource(R.string.version_format, appInfo.versionName ?: ""),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))

        // Package Name. Tap to copy — the one gesture that was still free here. Long-press is the
        // list's multi-select and a row tap opens this sheet, so the list itself has nowhere to put
        // this; the header the sheet already draws is where the package name is legible anyway.
        // Copying must not dismiss: `setClipEntry` suspends, and this scope dies with the sheet.
        //
        // onClickLabel, because the click target is a Text: without it TalkBack announces the
        // package name and "double tap to activate" and never says what activating does. The
        // package name is not a verb. minimumInteractiveComponentSize keeps the 48 dp touch target
        // labelMedium plus 4 dp of padding does not reach, and reserves it in layout only — the
        // glyphs stay the size they are.
        Text(
            text = appInfo.packageName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = com.valhalla.thor.presentation.theme.firaMonoFontFamily,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = stringResource(R.string.cd_copy_package_name)) {
                    // The Toast is INSIDE the coroutine, after the await. setClipEntry is a suspend
                    // call; toasting beside the launch instead of after it says "Copied" before the
                    // write has happened — and this is a bottom sheet, so the scope it runs in can
                    // die on dismissal between the two. That produced a success message with an
                    // unchanged clipboard, which is worse than no message at all: the user pastes
                    // whatever was there before and trusts it.
                    coroutineScope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(
                                android.content.ClipData.newPlainText(
                                    valueLabel,
                                    appInfo.packageName
                                )
                            )
                        )
                        Toast.makeText(context, R.string.toast_copy_saved, Toast.LENGTH_SHORT)
                            .show()
                    }
                }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        // UAD Description skipped by user request
    }
}

