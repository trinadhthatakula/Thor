// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.nonInteractiveScrollbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.isActive
import com.valhalla.thor.domain.model.isFrozen
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.importableDisabledApps
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.thor.presentation.utils.ObserveAsEvents
import com.valhalla.thor.presentation.widgets.AppInfoSheet
import com.valhalla.thor.presentation.widgets.AppItemGrid
import com.valhalla.thor.presentation.widgets.AppItemList
import com.valhalla.thor.presentation.widgets.AppSearchBar
import com.valhalla.thor.presentation.widgets.gridMetricsFor
import com.valhalla.thor.presentation.widgets.FreezerPromptSnackbar
import com.valhalla.thor.presentation.widgets.FreezeLoggerDialog
import com.valhalla.thor.presentation.widgets.ScrollToTopOnChange
import org.koin.androidx.compose.koinViewModel

/** Sentinel id for "the editor is open on a profile that does not exist yet". */
private const val NEW_PROFILE_ID = -1L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezerScreen(
    modifier: Modifier = Modifier,
    viewModel: FreezerViewModel = koinViewModel(),
    sharedTransitionScope: SharedTransitionScope? = null,
    onAppAction: (AppClickAction) -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasPrivilege = state.isRoot || state.isShizuku || state.isDhizuku
    val noDisabledAppsFoundMessage = stringResource(R.string.no_disabled_apps_found)

    var selectedPackageName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedAppInfo =
        selectedPackageName?.let { pkg -> state.freezerApps.find { it.packageName == pkg } }
    var showManageSheet by rememberSaveable { mutableStateOf(false) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showProfilesSheet by rememberSaveable { mutableStateOf(false) }

    // The freeze-profile editor. null closes it; NEW_PROFILE_ID opens it on a fresh profile.
    // An id rather than the FreezeProfile itself so the whole thing survives a config change —
    // the row is re-resolved from state.profiles, which is also what keeps the editor honest if
    // the profile changed underneath it.
    var editorProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editorSeed by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })
    ) { mutableStateOf(emptySet<String>()) }
    // Whether closing the editor should land back on the profiles list. True when the editor was
    // opened from it; false for "save selection as profile", which starts from the app grid and
    // should return there.
    var editorReturnsToList by rememberSaveable { mutableStateOf(false) }
    // Identifies the *current* editor, not the profile it edits. A save that lands after its own
    // editor was dismissed by hand must not close whatever editor replaced it — see
    // FreezerEvent.ProfileSaveSucceeded. Saved with the rest of the editor state so the comparison
    // still holds across a config change mid-save.
    var editorSession by rememberSaveable { mutableIntStateOf(0) }

    // Every open goes through here so the session can never be forgotten at a new entry point —
    // there are three today, and the failure mode of a missed increment is silent (two editors
    // sharing an id, which is exactly the bug the id exists to prevent).
    val openProfileEditor = { profileId: Long, seed: Set<String>, returnsToList: Boolean ->
        editorSeed = seed
        editorReturnsToList = returnsToList
        editorSession++
        editorProfileId = profileId
    }

    // Cancel and a confirmed save unwind identically — the only difference is whether a write was
    // issued first — so the teardown lives in one place rather than being kept in step by hand.
    // Declared up here rather than beside the sheet because the event observer below closes on it.
    val closeProfileEditor = {
        editorProfileId = null
        // The picker's query is VM state so it survives the sheet; clear it or the next open
        // starts filtered by a search the user has forgotten making.
        viewModel.updateProfileEditorSearch("")
        showProfilesSheet = editorReturnsToList
    }

    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var hasCheckedAutoPrompt by rememberSaveable { mutableStateOf(false) }

    // Transient "Add to Freezer" prompt — driven by one-off events, not durable UiState, so it is
    // never replayed on recomposition. Deliberately not rememberSaveable: a one-off shouldn't
    // survive a config change.
    var freezerPrompt by remember { mutableStateOf<FreezerPrompt?>(null) }

    // Candidates for the import prompt below. The rule lives in the domain rather than inline here
    // because it is pure list logic that was silently wrong — a blanket `!isSystem` clause skipped
    // every system app Thor had frozen — and a filter written inside a Composable has nowhere to be
    // tested from. See importableDisabledApps for why each of its four conditions is there.
    val disabledAppsNotInFreezer = remember(state.allInstalledApps, state.freezerPackageNames) {
        importableDisabledApps(state.allInstalledApps, state.freezerPackageNames)
    }

    LaunchedEffect(state.isLoading, state.hasShownDisabledAppsPrompt, disabledAppsNotInFreezer) {
        if (!state.isLoading && !state.hasShownDisabledAppsPrompt && !hasCheckedAutoPrompt && disabledAppsNotInFreezer.isNotEmpty()) {
            showImportDialog = true
            hasCheckedAutoPrompt = true
        }
    }

    val displayedApps = remember(state.freezerApps, state.searchQuery, state.appListType) {
        val filteredByType =
            state.freezerApps.filter { it.isSystem == (state.appListType == AppListType.SYSTEM) }
        val filtered = if (state.searchQuery.isBlank()) filteredByType
        else filteredByType.filter {
            it.appName?.contains(state.searchQuery, ignoreCase = true) == true ||
                    it.packageName.contains(state.searchQuery, ignoreCase = true)
        }
        filtered.sortedBy { it.appName }
    }

    // Apps the "Freeze all" / "Unfreeze all" toolbar acts on. These route through the
    // shared batch action (MultiAppAction) so progress streams into the FreezeLoggerDialog;
    // the unsafe/UAD eligibility skip is applied once, centrally, by
    // MainViewModel.performCountedFreeze. Unfreeze restores by each app's actual state.
    // "Active" = freezable (enabled & not suspended); "frozen" = disabled OR suspended (GH#239).
    val appsToFreeze = remember(state.freezerApps) { state.freezerApps.filter { it.isActive } }
    val appsToUnfreeze = remember(state.freezerApps) { state.freezerApps.filter { it.isFrozen } }
    val hasEnabled = appsToFreeze.isNotEmpty()
    val hasDisabled = appsToUnfreeze.isNotEmpty()


    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is FreezerEvent.ShowToast ->
                Toast.makeText(context, event.message.asString(context), Toast.LENGTH_SHORT).show()

            is FreezerEvent.ShowFreezerPrompt ->
                freezerPrompt = FreezerPrompt(event.packageName, event.appName)

            // Two guards, for the two things a hand-dismissed editor can do to a write still in
            // flight. The null check keeps an already-closed sheet from unwinding again and
            // re-opening the profiles list underneath it. The session check keeps a *replacement*
            // editor from being closed by the previous one's write, which would throw away a draft
            // the user is in the middle of typing.
            is FreezerEvent.ProfileSaveSucceeded ->
                if (editorProfileId != null && event.editorSession == editorSession) {
                    closeProfileEditor()
                }
        }
    }

    BackHandler(state.multiSelection.isNotEmpty()) {
        viewModel.clearSelection()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // --- Header ---
                if (state.multiSelection.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = displayedApps.isNotEmpty() && displayedApps.all { it.packageName in state.multiSelection },
                            onCheckedChange = { checked ->
                                if (checked) viewModel.selectAll(displayedApps.map { it.packageName }) else viewModel.clearSelection()
                            }
                        )
                        Text(
                            text = stringResource(
                                R.string.selected_count,
                                state.multiSelection.size
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        )
                        FilledTonalIconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(
                                painterResource(R.drawable.round_close),
                                stringResource(R.string.cd_close)
                            )
                        }
                    }
                } else {
                    // Title + Freeze All
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.frozen),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.freezer),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = (-1).sp,
                                maxLines = 1,
                                modifier = Modifier.weight(1f).basicMarquee()
                            )
                        }
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

                    // Search bar — config icon opens settings sheet
                    AppSearchBar(
                        query = state.searchQuery,
                        onQueryChange = viewModel::updateSearchQuery,
                        onOpenConfig = { showSettingsSheet = true }
                    )
                }

                // --- App List / Empty State ---
                val animatedVisibilityScope = LocalNavAnimatedContentScope.current
                if (displayedApps.isEmpty() && !state.isLoading) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.frozen),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(
                                if (state.freezerApps.isEmpty()) stringResource(R.string.no_apps_in_freezer)
                                else stringResource(R.string.no_matching_apps_freezer),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.freezerApps.isEmpty()) {
                                Text(
                                    stringResource(R.string.add_to_freezer_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                // The other half of the discoverability fix: say what profiles
                                // are, while there is room to. Only the truly-empty branch gets
                                // this — a user who has filtered a populated watchlist down to
                                // nothing is not being onboarded — and only while they have no
                                // profiles, since past that it explains something they know.
                                if (state.profiles.isEmpty()) {
                                    Spacer(Modifier.size(24.dp))
                                    Text(
                                        stringResource(R.string.no_profiles_yet),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        stringResource(R.string.no_profiles_yet_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 32.dp)
                                    )
                                }
                            }
                        }
                    }
                } else if (state.isGrid) {
                    val metrics = gridMetricsFor(state.gridDensity)
                    val gridState = rememberLazyGridState()

                    ScrollToTopOnChange(state.searchQuery, state.appListType) {
                        gridState.scrollToItem(0)
                    }

                    // `scrollIndicatorState` is nullable because `ScrollableState` defaults it to
                    // null for states that drive no indicator; both lazy states override it with a
                    // real one, so the null branch is unreachable today. Branching beats `!!` — a
                    // future null then costs the scrollbar, not the whole list.
                    val gridScrollbar = gridState.scrollIndicatorState?.let {
                        Modifier.nonInteractiveScrollbar(
                            state = it,
                            orientation = Orientation.Vertical
                        )
                    } ?: Modifier
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = metrics.minCellSize),
                        state = gridState,
                        contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .then(gridScrollbar)
                    ) {
                        items(
                            displayedApps,
                            key = { it.packageName }) { app ->
                            AppItemGrid(
                                app = app,
                                isSelected = app.packageName in state.multiSelection,
                                onClick = {
                                    if (state.multiSelection.isNotEmpty())
                                        viewModel.toggleSelection(app.packageName)
                                    else
                                        selectedPackageName = app.packageName
                                },
                                onLongClick = { viewModel.toggleSelection(app.packageName) },
                                metrics = metrics,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    }
                } else {
                    val listState = rememberLazyListState()

                    ScrollToTopOnChange(state.searchQuery, state.appListType) {
                        listState.scrollToItem(0)
                    }

                    val listScrollbar = listState.scrollIndicatorState?.let {
                        Modifier.nonInteractiveScrollbar(
                            state = it,
                            orientation = Orientation.Vertical
                        )
                    } ?: Modifier
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .then(listScrollbar)
                    ) {
                        items(
                            displayedApps,
                            key = { it.packageName }) { app ->
                            AppItemList(
                                app = app,
                                isSelected = app.packageName in state.multiSelection,
                                onClick = {
                                    if (state.multiSelection.isNotEmpty())
                                        viewModel.toggleSelection(app.packageName)
                                    else
                                        selectedPackageName = app.packageName
                                },
                                onLongClick = { viewModel.toggleSelection(app.packageName) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    }
                }
            }

            // Frozen prompt snackbar
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

            // Floating multi-select toolbar
            if (state.multiSelection.isNotEmpty()) {
                val selectedApps = remember(state.freezerApps, state.multiSelection) {
                    state.freezerApps.filter { it.packageName in state.multiSelection }
                }
                FreezerSelectToolBox(
                    selected = selectedApps,
                    isRoot = state.isRoot,
                    isShizuku = state.isShizuku,
                    isDhizuku = state.isDhizuku,
                    freezerMode = state.freezerMode,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                    onCancel = { viewModel.clearSelection() },
                    onRemoveFromFreezer = {
                        viewModel.removeFromFreezer(state.multiSelection)
                    },
                    onSaveAsProfile = {
                        openProfileEditor(NEW_PROFILE_ID, state.multiSelection, false)
                        // Clear the selection only once the editor has the seed: the editor is a
                        // separate sheet, so leaving the Freezer in multi-select behind it means
                        // backing out lands on a toolbar for a selection the user has moved on from.
                        viewModel.clearSelection()
                    },
                    onMultiAppAction = { action ->
                        viewModel.clearSelection()
                        onMultiAppAction(action)
                    }
                )
            }

            // Floating toolbar for Add, Freeze, Unfreeze
            if (state.multiSelection.isEmpty()) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                        toolbarContainerColor = MaterialTheme.colorScheme.primary,
                        toolbarContentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 16.dp, end = 16.dp),
                    content = {
                        val iconButtonColors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f)
                        )
                        IconButton(
                            onClick = {
                                onMultiAppAction(
                                    MultiAppAction.Freeze(
                                        appsToFreeze,
                                        useSuspend = state.freezerMode == FreezerMode.SUSPEND
                                    )
                                )
                            },
                            enabled = hasEnabled && hasPrivilege,
                            colors = iconButtonColors
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.frozen),
                                contentDescription = stringResource(R.string.action_freeze)
                            )
                        }
                        IconButton(
                            onClick = { showManageSheet = true },
                            colors = iconButtonColors
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddCircle,
                                contentDescription = stringResource(R.string.add_to_freezer)
                            )
                        }
                        // Not privilege-gated: browsing, creating and editing profiles is
                        // ordinary list-keeping. The run buttons inside the sheet are the ones
                        // that need root/Shizuku/Dhizuku, and they gate themselves.
                        IconButton(
                            onClick = { showProfilesSheet = true },
                            colors = iconButtonColors
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.list_alt),
                                contentDescription = stringResource(R.string.freeze_profiles)
                            )
                        }
                        IconButton(
                            onClick = { onMultiAppAction(MultiAppAction.UnFreeze(appsToUnfreeze)) },
                            enabled = hasDisabled && hasPrivilege,
                            colors = iconButtonColors
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.unfreeze),
                                contentDescription = stringResource(R.string.action_unfreeze)
                            )
                        }
                    }
                )
            }
        }
    }

    // AppInfoSheet
    selectedAppInfo?.let { app ->
        AppInfoSheet(
            appInfo = app,
            isRoot = state.isRoot,
            isShizuku = state.isShizuku,
            isDhizuku = state.isDhizuku,
            isInFreezer = app.packageName in state.freezerPackageNames,
            freezerRemoveLabelRes = R.string.action_unfreeze_and_remove,
            onDismiss = { selectedPackageName = null },
            // Dismissing here is not optional, and this is the only action it's true of.
            // selectedAppInfo is resolved out of state.freezerApps, which is the watchlist
            // (`allApps.filter { it.packageName in pkgSet }`), so leaving the freezer drops this app
            // from that list and the sheet would go with it on the next emission. Doing it
            // ourselves, now, makes the teardown deliberate instead of a race with the flow.
            onToggleFreezerMembership = {
                viewModel.toggleManaged(
                    app.packageName,
                    add = app.packageName !in state.freezerPackageNames
                )
                selectedPackageName = null
            },
            onAppAction = { action ->
                // No clears below, same as the Apps tab: AppInfoSheet calls onDismiss() itself for
                // every terminal action, and the rest — suspend, force-stop, clear cache, share,
                // settings — are meant to leave the sheet up so you can see the result.
                //
                // Permissions used to be listed as one of those, which is what hid the bug: it is
                // the one action in that group that pushes a destination
                // (ThorRoute.PermissionManager, added to freezerBackStack in MainScreen), so the
                // sheet has to go with it. AppInfoSheet dismisses on it now.
                // Freezing and unfreezing don't touch membership, so neither can pull this app out
                // of state.freezerApps.
                when (action) {
                    is AppClickAction.Freeze ->
                        viewModel.freezeSingleApp(
                            app.packageName,
                            app.appName,
                            inFreezer = app.packageName in state.freezerPackageNames
                        )

                    is AppClickAction.UnFreeze ->
                        viewModel.unfreezeSingleApp(app.packageName, app.appName)

                    is AppClickAction.AddToHomeScreen -> {
                        viewModel.pinAppToLauncher(app)
                        selectedPackageName = null
                    }

                    else -> onAppAction(action)
                }
            }
        )
    }

    if (showManageSheet) {
        ManageFreezerSheet(
            allApps = state.allInstalledApps,
            freezerPackageNames = state.freezerPackageNames,
            searchQuery = state.manageSheetSearchQuery,
            gridDensity = state.gridDensity,
            onSearchChange = viewModel::updateManageSheetSearch,
            onToggle = { pkg, add -> viewModel.toggleManaged(pkg, add) },
            onDismiss = { showManageSheet = false }
        )
    }

    if (showProfilesSheet) {
        FreezeProfilesSheet(
            profiles = state.profiles,
            allApps = state.allInstalledApps,
            runningRequests = state.runningRequests,
            hasPrivilege = hasPrivilege,
            onRun = viewModel::runProfile,
            // Dismissed first: the kill lands in MainScreen's confirm dialog and then its progress
            // log, and neither is worth reading through a sheet that can no longer say anything
            // about the run. Freeze and unfreeze keep the sheet because their report *is* the row.
            onKill = { apps ->
                showProfilesSheet = false
                onMultiAppAction(MultiAppAction.Kill(apps))
            },
            onCreate = {
                openProfileEditor(NEW_PROFILE_ID, emptySet(), true)
                // Swap the editor in for the list rather than stacking it: two modal sheets at
                // once leaves the lower one's scrim eating the upper one's dismiss gesture.
                showProfilesSheet = false
            },
            onEdit = { profile ->
                openProfileEditor(profile.id, emptySet(), true)
                showProfilesSheet = false
            },
            onDelete = viewModel::deleteProfile,
            onDismiss = { showProfilesSheet = false }
        )
    }

    editorProfileId?.let { id ->
        // Re-resolved from state, so a profile deleted from another surface while the editor is
        // open falls back to create rather than editing a row that no longer exists.
        val editing = state.profiles.firstOrNull { it.id == id }
        FreezeProfileEditorSheet(
            profile = editing,
            initialSelection = editorSeed,
            existingNames = state.profiles.map { it.name },
            allApps = state.allInstalledApps,
            searchQuery = state.profileEditorSearchQuery,
            gridDensity = state.gridDensity,
            onSearchChange = viewModel::updateProfileEditorSearch,
            isSaving = state.profileSaveInFlight,
            // No close here. The sheet comes down on FreezerEvent.ProfileSaveSucceeded, so a write
            // the database refuses — a duplicate name, a foreign key — leaves the draft on screen
            // to be corrected instead of reporting itself into the void.
            // The session is captured here, at the tap, rather than read when the answer arrives —
            // by then it may already name a different editor.
            onSave = { name, packageNames ->
                if (editing == null) viewModel.createProfile(editorSession, name, packageNames)
                else viewModel.updateProfile(editorSession, editing.id, name, packageNames)
            },
            onDismiss = { closeProfileEditor() }
        )
    }

    if (showSettingsSheet) {
        FreezerSettingsSheet(
            isGrid = state.isGrid,
            autoFreezeEnabled = state.autoFreezeEnabled,
            hasPrivilege = hasPrivilege,
            showImportDisabledApps = disabledAppsNotInFreezer.isNotEmpty(),
            appListType = state.appListType,
            showLauncherPinActions = state.addFreezerToLauncher && viewModel.isPinSupported(),
            onToggleView = viewModel::toggleGridMode,
            onToggleAutoFreeze = viewModel::setAutoFreezeEnabled,
            freezerMode = state.freezerMode,
            onFreezerModeChange = viewModel::setFreezerMode,
            onDismiss = { showSettingsSheet = false },
            onUnfreezeAll = { onMultiAppAction(MultiAppAction.UnFreeze(appsToUnfreeze)) },
            onPinAllToLauncher = viewModel::pinAllToLauncher,
            pinAllCount = state.freezerApps.count { !it.isSystem },
            onPinFreezeAllShortcut = { viewModel.pinBulkShortcut(freeze = true) },
            onPinUnfreezeAllShortcut = { viewModel.pinBulkShortcut(freeze = false) },
            onImportDisabledApps = {
                showSettingsSheet = false
                if (disabledAppsNotInFreezer.isNotEmpty()) {
                    showImportDialog = true
                } else {
                    Toast.makeText(
                        context,
                        noDisabledAppsFoundMessage,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onListTypeChanged = viewModel::updateListType
        )
    }

    state.sweepProgress?.let { progress ->
        FreezeLoggerDialog(
            state = progress,
            onDismiss = viewModel::dismissSweepProgress,
            onCancelQueue = viewModel::cancelSweepQueue,
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.markDisabledAppsPromptShown()
                showImportDialog = false
            },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.frozen),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.import_disabled_apps_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.import_disabled_apps_desc,
                        disabledAppsNotInFreezer.size,
                        disabledAppsNotInFreezer.size
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addAppsToFreezer(disabledAppsNotInFreezer.map { it.packageName })
                        viewModel.markDisabledAppsPromptShown()
                        showImportDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.markDisabledAppsPromptShown()
                        showImportDialog = false
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
