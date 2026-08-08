// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.nonInteractiveScrollbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppGridDensity
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.asGeneralName
import com.valhalla.thor.domain.model.PermissionIndex
import com.valhalla.thor.domain.model.filterTypes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.asgard.expressivePress
import com.valhalla.thor.presentation.utils.AppIconModel
import com.valhalla.thor.presentation.utils.getBloatRecommendationColors

@Composable
fun AppList(
    modifier: Modifier = Modifier,
    appListType: AppListType,
    installers: List<String?>,
    appList: List<AppInfo>,
    selectedFilter: String?,
    filterType: FilterType = FilterType.Source,
    sortBy: SortBy = SortBy.NAME,
    sortOrder: SortOrder = SortOrder.ASCENDING,
    searchQuery: String = "",
    isLoading: Boolean = false,
    isGrid: Boolean = true,
    gridDensity: AppGridDensity = AppGridDensity.DEFAULT,
    isRoot: Boolean = false,
    isShizuku: Boolean = false,
    isDhizuku: Boolean = false,
    installerNameMap: Map<String, String> = emptyMap(),
    permissionIndex: PermissionIndex = PermissionIndex(),
    isLoadingPermissions: Boolean = false,
    permissionIndexFailed: Boolean = false,
    onSortOrderSelected: (SortOrder) -> Unit = {},
    onSortByChanged: (SortBy) -> Unit = {},
    onFilterSelected: (String?) -> Unit,
    onSearchQueryChange: (String) -> Unit = {},
    onFilterTypeChanged: (FilterType) -> Unit = {},
    onListTypeChanged: (AppListType) -> Unit = {},
    onAppInfoSelected: (AppInfo) -> Unit,
    onMultiAppAction: (MultiAppAction) -> Unit = {},
    onToggleView: () -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // 1. Local State
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    // Not rememberSaveable: AppInfo is not Parcelable/Serializable, so saving a non-empty
    // selection would throw NotSerializableException on rotation/process death. The selection is
    // intentionally transient — it is cleared on appListType change and via BackHandler.
    var multiSelection by remember { mutableStateOf(emptyList<AppInfo>()) }

    // Optimization: Use a Set for O(1) lookups
    val selectedPackageNames = remember(multiSelection) {
        multiSelection.mapTo(HashSet()) { it.packageName }
    }
    val isMultiSelectMode = multiSelection.isNotEmpty()

    // 2. Logic
    BackHandler(isMultiSelectMode) { multiSelection = emptyList() }

    LaunchedEffect(appListType) { multiSelection = emptyList() }

    // 3. UI Layout
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Search Bar
            AppSearchBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                onOpenConfig = { showFilterSheet = true }
            )

            // Multi-Select Header (Action Menu)
            Box(modifier = Modifier.fillMaxWidth()) {
                this@Column.AnimatedVisibility(
                    visible = isMultiSelectMode,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    MultiSelectHeader(
                        count = multiSelection.size,
                        isAllSelected = multiSelection.size == appList.size && appList.isNotEmpty(),
                        onSelectAll = { selectAll ->
                            multiSelection = if (selectAll) appList else emptyList()
                        },
                        onClear = { multiSelection = emptyList() }
                    )
                }
            }

            // System App Warning
            if (appListType == AppListType.SYSTEM && !isMultiSelectMode) {
                Text(
                    text = stringResource(R.string.system_apps_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            // Headers (Control Bar)
            this@Column.AnimatedVisibility(
                visible = !isMultiSelectMode,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AppQuickFilters(
                        installers = installers,
                        selectedFilter = selectedFilter,
                        filterType = filterType,
                        appListType = appListType,
                        installerNameMap = installerNameMap,
                        permissionIndex = permissionIndex,
                        isLoadingPermissions = isLoadingPermissions,
                        permissionIndexFailed = permissionIndexFailed,
                        onFilterSelected = onFilterSelected
                    )
                }
            }

            // App Content (Grid or List)
            if (appList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        ContainedLoadingIndicator()
                    } else {
                        EmptyStatePlaceholder(
                            isFiltering = searchQuery.isNotEmpty() || selectedFilter != "All"
                        )
                    }
                }
            } else {
                AppListContent(
                    list = appList,
                    isGrid = isGrid,
                    gridDensity = gridDensity,
                    selectedPackageNames = selectedPackageNames, // Pass Set instead of List
                    onAppClick = { app ->
                        if (isMultiSelectMode) {
                            multiSelection = toggleSelection(multiSelection, app)
                        } else {
                            onAppInfoSelected(app)
                        }
                    },
                    onAppLongClick = { app ->
                        if (!isMultiSelectMode) {
                            multiSelection = toggleSelection(multiSelection, app)
                        }
                    },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        }

        // Floating Action Toolbar (Multi-Select)
        if (isMultiSelectMode) {
            MultiSelectToolBox(
                selected = multiSelection,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 32.dp)
                    .align(Alignment.BottomEnd),
                isRoot = isRoot,
                isShizuku = isShizuku,
                isDhizuku = isDhizuku,
                onCancel = { multiSelection = emptyList() },
                onMultiAppAction = { action ->
                    onMultiAppAction(action)
                    multiSelection = emptyList()
                }
            )
        }
    }

    if (showFilterSheet) {
        AppFilterSheet(
            onDismiss = { showFilterSheet = false },
            filterType = filterType,
            sortBy = sortBy,
            sortOrder = sortOrder,
            isGrid = isGrid,
            appListType = appListType,
            onFilterTypeChanged = onFilterTypeChanged,
            onSortByChanged = onSortByChanged,
            onSortOrderChanged = onSortOrderSelected,
            onToggleView = onToggleView,
            onListTypeChanged = onListTypeChanged
        )
    }
}

// --- SUB-COMPONENTS ---

@Composable
private fun AppQuickFilters(
    installers: List<String?>,
    selectedFilter: String?,
    filterType: FilterType,
    appListType: AppListType,
    installerNameMap: Map<String, String>,
    permissionIndex: PermissionIndex,
    isLoadingPermissions: Boolean,
    permissionIndexFailed: Boolean,
    onFilterSelected: (String?) -> Unit
) {
    // The permission chips are the only ones that have to be read off the device, so they are the
    // only ones with a "not there yet", a "it went wrong" and a "not there at all" state to show
    // instead. Failure is worth its own sentence: the toast that announced it is long gone by the
    // time the user looks at the empty row, and "no groups on this device" would be a lie.
    if (filterType == FilterType.Permission && permissionIndex.isEmpty) {
        Text(
            text = stringResource(
                when {
                    isLoadingPermissions -> R.string.permission_filter_loading
                    permissionIndexFailed -> R.string.permission_filter_failed
                    else -> R.string.permission_filter_unavailable
                }
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
            // Deliberately allowed to wrap. Every one of these strings is a sentence, and the
            // Spanish, French and Arabic translations run well past a phone's width — one line plus
            // an ellipsis cut them in half.
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val chips: List<String?> = when (filterType) {
            FilterType.Source -> installers
            FilterType.State -> FilterType.State.types
            FilterType.Permission -> listOf("All") + permissionIndex.orderedGroups
        }

        chips.forEach { item ->
            val label = when (filterType) {
                FilterType.Source -> {
                    when (item) {
                        "All" -> stringResource(R.string.filter_all)
                        "PLAY STORE" -> stringResource(R.string.play_store)
                        "F-DROID" -> stringResource(R.string.f_droid)
                        "SIDELOADED" -> stringResource(R.string.sideloaded)
                        "OTHERS" -> stringResource(R.string.others)
                        else -> installerNameMap[item] ?: item
                        ?: if (appListType != AppListType.SYSTEM) stringResource(R.string.others) else stringResource(R.string.system_apps)
                    }
                }

                // The platform's own label for the group, so the chip reads exactly like the
                // permission dialog the user has already seen — in their language, for free.
                FilterType.Permission -> when (item) {
                    "All" -> stringResource(R.string.filter_all)
                    else -> permissionIndex.groupLabels[item] ?: item.orEmpty()
                }

                FilterType.State -> when (item) {
                    "All" -> stringResource(R.string.filter_all)
                    "Active" -> stringResource(R.string.active)
                    "Frozen" -> stringResource(R.string.frozen)
                    "Suspended" -> stringResource(R.string.suspended)
                    else -> item ?: ""
                }
            }

            FilterChip(
                selected = item == selectedFilter,
                onClick = { onFilterSelected(item) },
                label = { Text(label) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppSearchBar(
    modifier: Modifier = Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenConfig: (() -> Unit)? = null
) {
    var localQuery by remember(query) { mutableStateOf(query) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val isImeVisible = WindowInsets.isImeVisible

    BackHandler(enabled = isImeVisible || localQuery.isNotEmpty()) {
        if (isImeVisible) keyboardController?.hide()
        else {
            localQuery = ""
            onQueryChange("")
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(4.dp)
        ) {
            BasicTextField(
                value = localQuery,
                onValueChange = {
                    localQuery = it
                    onQueryChange(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Search
                ),
                decorationBox = { innerTextField ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.round_search),
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (localQuery.isEmpty()) {
                                Text(
                                    stringResource(R.string.search_apps),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            innerTextField()
                        }
                        if (localQuery.isNotEmpty()) {
                            Icon(
                                painter = painterResource(R.drawable.round_close),
                                contentDescription = stringResource(R.string.cd_clear),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        localQuery = ""
                                        onQueryChange("")
                                    }
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            )
        }

        if (onOpenConfig != null) {
            IconButton(
                onClick = onOpenConfig,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Icon(
                    painter = painterResource(R.drawable.filter_list),
                    contentDescription = stringResource(R.string.cd_config),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun MultiSelectHeader(
    count: Int,
    isAllSelected: Boolean,
    onSelectAll: (Boolean) -> Unit,
    onClear: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Checkbox(
            checked = isAllSelected,
            onCheckedChange = onSelectAll
        )
        Text(
            text = stringResource(R.string.selected_count, count),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        IconButton(onClick = onClear) {
            Icon(
                painterResource(R.drawable.round_close),
                stringResource(R.string.cd_close),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun AppListContent(
    list: List<AppInfo>,
    isGrid: Boolean,
    gridDensity: AppGridDensity,
    selectedPackageNames: Set<String>,
    onAppClick: (AppInfo) -> Unit,
    onAppLongClick: (AppInfo) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // Shared padding for list/grid
    val padding = PaddingValues(bottom = 100.dp, top = 8.dp)

    // Hoisted only so the scrollbar can read it. Both containers created their own identical state
    // before, and both branches still mint a fresh one, so switching grid <-> list resets the scroll
    // position exactly as it always did.
    //
    // `scrollIndicatorState` is nullable because `ScrollableState` defaults it to null for states
    // that drive no indicator; both lazy states override it with a real one, so the null branch is
    // unreachable today. Branching beats `!!` — a future null then costs the scrollbar, not the list.
    if (isGrid) {
        val metrics = gridMetricsFor(gridDensity)
        val gridState = rememberLazyGridState()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = metrics.minCellSize),
            state = gridState,
            contentPadding = padding,
            modifier = gridState.scrollIndicatorState?.let {
                Modifier.nonInteractiveScrollbar(state = it, orientation = Orientation.Vertical)
            } ?: Modifier
        ) {
            items(list, key = { it.packageName }) { app ->
                AppItemGrid(
                    app = app,
                    isSelected = selectedPackageNames.contains(app.packageName),
                    onClick = { onAppClick(app) },
                    onLongClick = { onAppLongClick(app) },
                    metrics = metrics,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        }
    } else {
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            contentPadding = padding,
            modifier = listState.scrollIndicatorState?.let {
                Modifier.nonInteractiveScrollbar(state = it, orientation = Orientation.Vertical)
            } ?: Modifier
        ) {
            items(list, key = { it.packageName }) { app ->
                AppItemList(
                    app = app,
                    isSelected = selectedPackageNames.contains(app.packageName),
                    onClick = { onAppClick(app) },
                    onLongClick = { onAppLongClick(app) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        }
    }
}

/**
 * The size every badge on a *list* row is drawn at — the status badge and the UAD tier chip.
 *
 * Fixed rather than density-scaled, unlike its grid counterpart in [AppGridMetrics]: a list row is
 * floored at `ListTokens.ItemTwoLineContainerHeight` (72 dp) whatever the icon does, so nothing in
 * this layout gets denser and a smaller badge would only get harder to see.
 */
private val AppRowBadgeSize = 16.dp

/**
 * The widest a list row's UAD tier chip may draw.
 *
 * Load-bearing, not cosmetic. In [AppItemList] the chip is an *unweighted* sibling of the app name,
 * and `Row` measures unweighted children first against the full incoming width — the name's
 * `weight(1f, fill = false)` only ever sees what is left, and legitimately resolves to 0 dp. Asgard's
 * `StatusChip` is `maxLines = 1` with an ellipsis but declares no maximum width, so without a cap a
 * long enough tier takes the whole row and the app name disappears entirely. The tier is not a fixed
 * vocabulary: `UadHelper.buildUadMap` copies a debloat extension's `recommendation` in verbatim, so
 * the string is only bounded by whatever the extension returns.
 *
 * 128 dp holds the longest tier the bundled list ships ("Recommended") with headroom at the default
 * font scale. Past that the chip ellipsises — which is the right thing to lose, since the tier's
 * colour still carries the whole signal while a nameless row carries none.
 */
private val AppRowTierChipMaxWidth = 128.dp

/**
 * Every dp a grid tile is built from, for one [AppGridDensity].
 *
 * A bundle rather than a lone `minSize`, because the numbers are load-bearing on each other:
 * `Modifier.size` is declared with `enforceIncoming = true`, so an icon whose cell cannot hold it
 * is silently coerced smaller while [cornerRadius] stays put and the tile renders as a pill. The
 * invariant every row of [gridMetricsFor] satisfies is
 * `minCellSize - 2 * outerPadding - 2 * innerPadding >= iconSize` — which is exactly where today's
 * `100.dp` came from: `56 + 2 * 16 + 2 * 6`.
 *
 * [outerPadding] is also the grid's gutter. None of the four grids passes a `horizontalArrangement`,
 * so the space between two tiles is these paddings back to back and nothing else.
 *
 * The label's own line is not in here. It ellipsizes at every step (both grid labels set
 * `TextOverflow.Ellipsis`), and its worst case is the *current* one — a 100 dp cell leaves it 56 dp
 * — so it needs no budget of its own; it takes whatever the tile has left.
 */
internal data class AppGridMetrics(
    /** `GridCells.Adaptive(minSize = )`. A cell is never narrower than this, only wider. */
    val minCellSize: Dp,
    val iconSize: Dp,
    /** The tile's padding *outside* its background — i.e. the gap between two tiles. */
    val outerPadding: Dp,
    /** The tile's padding *inside* its background, between the edge and the icon. */
    val innerPadding: Dp,
    val cornerRadius: Dp,
    /** Gap between the icon and the label. */
    val labelSpacing: Dp,
    /** The status badge and the UAD tier dot, both drawn in a corner of the icon. */
    val badgeSize: Dp
) {
    /**
     * The multi-select tick, which sits in the same corner as the badge but reads as a control
     * rather than a marker, so it is drawn half again as large.
     *
     * Derived rather than tabulated because the one value that matters is already pinned: at
     * [AppGridDensity.DEFAULT] this is 24 dp, which is `Icon`'s own default size and therefore what
     * both grids drew before this type existed.
     */
    val selectionSize: Dp get() = badgeSize * 1.5f
}

/**
 * The dp table behind [AppGridDensity], kept here rather than in `domain/` because `Dp` is a Compose
 * type — the same split `settleDelayFor` uses for `AnimationIntensity`.
 *
 * [AppGridDensity.DEFAULT] is today's rendering to the dp, deliberately: a user who never opens the
 * setting must see the screen they had before it shipped.
 */
internal fun gridMetricsFor(density: AppGridDensity): AppGridMetrics = when (density) {
    AppGridDensity.COMPACT -> AppGridMetrics(
        minCellSize = 80.dp,
        iconSize = 40.dp,
        outerPadding = 4.dp,
        innerPadding = 8.dp,
        cornerRadius = 20.dp,
        labelSpacing = 4.dp,
        badgeSize = 12.dp
    )

    AppGridDensity.DEFAULT -> AppGridMetrics(
        minCellSize = 100.dp,
        iconSize = 56.dp,
        outerPadding = 6.dp,
        innerPadding = 16.dp,
        cornerRadius = 32.dp,
        labelSpacing = 8.dp,
        badgeSize = 16.dp
    )

    AppGridDensity.LARGE -> AppGridMetrics(
        minCellSize = 128.dp,
        iconSize = 72.dp,
        outerPadding = 8.dp,
        innerPadding = 18.dp,
        cornerRadius = 36.dp,
        labelSpacing = 10.dp,
        badgeSize = 20.dp
    )
}

/**
 * The one status badge an app row shows, or nothing when the app is in its ordinary state.
 *
 * Extracted because the list and the grid carried near-identical copies of this `if` chain, and
 * "near-identical" is what let them drift: both copies tested `isSystem && !isInstalled` *first*
 * and painted a red "Uninstalled" danger icon for it, so one frozen system app looked different
 * from another depending only on which mechanic had frozen it. The two call sites differ in their
 * modifier and nothing else, so that is the only thing they still pass in.
 *
 * The `!isInstalled` branch is gone rather than merged, because it was unreachable once `!enabled`
 * is tested first: [AppInfo.enabled] folds `FLAG_INSTALLED` in (see `AppInfoMapper` and
 * `AppRepositoryImpl`), so a package that is not installed for this user *always* reads as not
 * enabled. It could never describe a state this branch does not already cover — and it could not be
 * kept as a distinct one either, since a system app removed with `pm uninstall --user N` is
 * indistinguishable from one the vendor shipped removed. `FreezerMode.isFrozen` has always treated
 * both as the same thing; this is the list agreeing with it.
 *
 * Order matters: an app can be disabled *and* suspended (a freezer mode switch does that), and
 * "frozen" is the more fundamental of the two, so it wins.
 */
@Composable
private fun AppStatusBadge(app: AppInfo, modifier: Modifier = Modifier) {
    when {
        !app.enabled -> Icon(
            painterResource(R.drawable.frozen),
            stringResource(R.string.cd_frozen),
            modifier = modifier,
            tint = MaterialTheme.colorScheme.primary
        )

        app.isSuspended -> Icon(
            painterResource(R.drawable.bolt),
            stringResource(R.string.cd_suspended),
            modifier = modifier,
            tint = MaterialTheme.colorScheme.secondary
        )
    }
}

/**
 * The UAD safety tier for a system app — "Recommended", "Advanced", "Expert", "Unsafe" — or nothing.
 *
 * It used to be painted only after you opened an app, which is precisely when it no longer helps:
 * the decision a tier informs is the one taken over a multi-selected *list* of system apps, not the
 * one taken on the single app you already went looking for.
 *
 * Two shapes, one decision. A ~100.dp grid cell has no room for a word, so the grid gets a dot in
 * the icon's free top-left corner (the top-right is already an either/or between the selection tick
 * and [AppStatusBadge]); the list, which has a whole row, gets the same [StatusChip] the details
 * screen and the risk dialog draw, so the three agree letter for letter — up to
 * [AppRowTierChipMaxWidth], past which the row's copy ellipsises so the app name survives. What must
 * not fork is the gate and the colour above — hence one composable rather than two.
 *
 * The gate is `AppRiskDialog`'s, deliberately: a tier only means anything where it changes what Thor
 * will let you do, and `FreezePolicy.freezeTierOf` discards it outright for a user app. `isUadLoadFailed`
 * is redundant today — a failed load leaves `uadMap` empty and every recommendation null — but it is
 * the condition that *states* the rule, and a partially-loaded list would otherwise badge some rows
 * and not others with nothing to say which.
 *
 * The tier is the raw string from the UAD list, untranslated, matching all three existing renders.
 * Extensions can contribute an arbitrary one (`UadHelper.buildUadMap`), which is why nothing here
 * exhausts over a fixed set — an unrecognised tier falls through to a neutral colour rather than
 * being dropped or crashing.
 *
 * Cold start: the first frame comes from the Room cache, which stores no bloat fields
 * (`AppRepositoryImpl.getAllApps`), so every app arrives with a null recommendation and this draws
 * nothing until the package rescan lands. That is the intended trade — a badge that appears a beat
 * late is recoverable, a badge that says "Recommended" because the tier had not loaded yet is not.
 */
@Composable
private fun UadTierBadge(app: AppInfo, modifier: Modifier = Modifier, asDot: Boolean = false) {
    if (!app.isSystem || app.isUadLoadFailed) return
    val tier = app.bloatRecommendation?.takeIf { it.isNotBlank() } ?: return
    val (containerColor, contentColor) = getBloatRecommendationColors(tier)

    if (asDot) {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(2.dp)
                .semantics { contentDescription = tier }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(containerColor, CircleShape)
            )
        }
    } else {
        // The cap belongs here rather than at the call site: the chip is measured before the app
        // name it sits beside, so an uncapped one takes the name's space rather than its own —
        // see [AppRowTierChipMaxWidth].
        Box(modifier.widthIn(max = AppRowTierChipMaxWidth)) {
            StatusChip(text = tier, color = containerColor, textColor = contentColor)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppItemList(
    app: AppInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    ListItem(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(24.dp))
            .expressivePress(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            ),
        leadingContent = {
            AppIcon(
                packageName = app.packageName,
                isEnabled = app.enabled,
                isSuspended = app.isSuspended,
                size = 48.dp,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        },
        supportingContent = {
            Text(
                app.packageName,
                maxLines = 1,
                // Without an overflow a one-line package name is hard-cut mid-glyph, and package
                // names are long enough that this fires on ordinary rows, not edge cases.
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            if (isSelected) {
                Icon(
                    painterResource(R.drawable.check_circle),
                    stringResource(R.string.cd_selected),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val textSharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "name-${app.packageName}"),
                        animatedVisibilityScope = animatedVisibilityScope
                    ).skipToLookaheadSize()
                }
            } else {
                Modifier
            }
            Text(
                app.appName ?: stringResource(R.string.unknown),
                maxLines = 1,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(textSharedModifier)
            )
            UadTierBadge(
                app = app,
                modifier = Modifier.padding(start = 8.dp)
            )
            AppStatusBadge(
                app = app,
                modifier = Modifier
                    .size(AppRowBadgeSize)
                    .padding(start = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppItemGrid(
    app: AppInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    metrics: AppGridMetrics = gridMetricsFor(AppGridDensity.DEFAULT),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(metrics.outerPadding)
            .expressivePress(interactionSource)
            .clip(RoundedCornerShape(metrics.cornerRadius))
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .combinedClickable(
                interactionSource = interactionSource,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(metrics.innerPadding)
    ) {
        Box {
            AppIcon(
                packageName = app.packageName,
                isEnabled = app.enabled,
                isSuspended = app.isSuspended,
                size = metrics.iconSize,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
            if (isSelected) {
                Icon(
                    painterResource(R.drawable.check_circle),
                    stringResource(R.string.cd_selected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(metrics.selectionSize)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            } else {
                // Status Indicator. The badge is the direct child of this Box, so `align` still
                // reaches the Icon that AppStatusBadge emits — parent data travels with the
                // modifier, not with the call site.
                AppStatusBadge(
                    app = app,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(metrics.badgeSize)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .padding(2.dp)
                )
            }
            // Outside the selection branch above, unlike the status badge: a tier is at its most
            // useful while rows are being ticked for a bulk debloat, which is exactly when the
            // top-right corner is showing a tick instead.
            UadTierBadge(
                app = app,
                asDot = true,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(metrics.badgeSize)
            )
        }
        Spacer(Modifier.height(metrics.labelSpacing))
        val textSharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "name-${app.packageName}"),
                    animatedVisibilityScope = animatedVisibilityScope
                ).skipToLookaheadSize()
            }
        } else {
            Modifier
        }
        Text(
            text = app.appName ?: stringResource(R.string.unknown),
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            textAlign = TextAlign.Center,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.then(textSharedModifier)
        )
    }
}

@Composable
internal fun AppIcon(
    packageName: String,
    isEnabled: Boolean,
    isSuspended: Boolean,
    size: androidx.compose.ui.unit.Dp,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // Hoisted static matrices to avoid recreation
    val greyScaleMatrix = remember { ColorMatrix().apply { setToSaturation(0f) } }
    val dullMatrix = remember { ColorMatrix().apply { setToSaturation(0.3f) } }

    val imageModifier = Modifier
        .size(size)
        .then(if (isSuspended && isEnabled) Modifier.graphicsLayer(alpha = 0.7f) else Modifier)

    val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                sharedContentState = rememberSharedContentState(key = "icon-$packageName"),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    } else {
        Modifier
    }

    Box(contentAlignment = Alignment.Center) {
        AsyncImage(
            model = AppIconModel(packageName),
            contentDescription = null,
            modifier = imageModifier.then(sharedModifier),
            colorFilter = when {
                !isEnabled -> ColorFilter.colorMatrix(greyScaleMatrix)
                isSuspended -> ColorFilter.colorMatrix(dullMatrix)
                else -> null
            },
            error = painterResource(R.drawable.android)
        )
    }
}

private fun toggleSelection(currentSelection: List<AppInfo>, item: AppInfo): List<AppInfo> {
    return if (currentSelection.contains(item)) currentSelection - item else currentSelection + item
}

@Composable
private fun EmptyStatePlaceholder(
    isFiltering: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(
            painter = painterResource(
                if (isFiltering) R.drawable.round_search else R.drawable.apps
            ),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isFiltering) stringResource(R.string.no_matching_apps) else stringResource(R.string.no_apps_display),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isFiltering) {
            Text(
                text = stringResource(R.string.adjust_filters_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private enum class SheetTab { FILTERS, SORT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppFilterSheet(
    onDismiss: () -> Unit,
    filterType: FilterType,
    sortBy: SortBy,
    sortOrder: SortOrder,
    isGrid: Boolean,
    appListType: AppListType,
    onFilterTypeChanged: (FilterType) -> Unit,
    onSortByChanged: (SortBy) -> Unit,
    onSortOrderChanged: (SortOrder) -> Unit,
    onToggleView: () -> Unit,
    onListTypeChanged: (AppListType) -> Unit
) {
    var activeTab by remember { mutableStateOf(SheetTab.FILTERS) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                stringResource(R.string.configuration),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                letterSpacing = (-1).sp
            )
            Spacer(Modifier.height(24.dp))

            // 1. App Type Selector (Top Row)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.app_source),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                ConnectedButtonGroup(
                    items = AppListType.entries.map { type ->
                        ConnectedButtonGroupItem.Icon(
                            icon = ImageVector.vectorResource(if (type == AppListType.USER) R.drawable.apps else R.drawable.android),
                            contentDescription = stringResource(
                                if (type == AppListType.USER) R.string.chip_user else R.string.chip_system
                            )
                        )
                    },
                    selectedIndex = AppListType.entries.indexOf(appListType),
                    onItemSelected = { onListTypeChanged(AppListType.entries[it]) },
                    modifier = Modifier.width(IntrinsicSize.Max)
                )
            }

            Spacer(Modifier.height(24.dp))

            // 2. View Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.view_mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                ConnectedButtonGroup(
                    items = listOf(
                        ConnectedButtonGroupItem.Icon(
                            ImageVector.vectorResource(R.drawable.grid_view),
                            stringResource(R.string.grid)
                        ),
                        ConnectedButtonGroupItem.Icon(
                            ImageVector.vectorResource(R.drawable.view_stream),
                            stringResource(R.string.list)
                        )
                    ),
                    selectedIndex = if (isGrid) 0 else 1,
                    onItemSelected = { onToggleView() },
                    modifier = Modifier.width(IntrinsicSize.Max)
                )
            }

            Spacer(Modifier.height(32.dp))

            ConnectedButtonGroup(
                items = SheetTab.entries.map { ConnectedButtonGroupItem.Label(stringResource(if (it == SheetTab.FILTERS) R.string.filters else R.string.sort_by)) },
                selectedIndex = SheetTab.entries.indexOf(activeTab),
                onItemSelected = { activeTab = SheetTab.entries[it] },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            when (activeTab) {
                SheetTab.FILTERS -> {
                    LazyColumn(
                        modifier = Modifier.height(200.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filterTypes) { type ->
                            ListItem(
                                trailingContent = {
                                    if (filterType == type) Icon(
                                        painterResource(R.drawable.check_circle),
                                        null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                                            alpha = 0.5f
                                        )
                                    )
                                    .clickable { onFilterTypeChanged(type) },
                                colors = androidx.compose.material3.ListItemDefaults.colors(
                                    containerColor = Color.Transparent
                                )
                            ) {
                                Text(
                                    stringResource(type.asGeneralName()),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                SheetTab.SORT -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.order),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = sortOrder == SortOrder.ASCENDING,
                            onClick = { onSortOrderChanged(SortOrder.ASCENDING) },
                            label = { Text(stringResource(R.string.ascending)) },
                            leadingIcon = { Icon(painterResource(R.drawable.arrow_upward), null) }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = sortOrder == SortOrder.DESCENDING,
                            onClick = { onSortOrderChanged(SortOrder.DESCENDING) },
                            label = { Text(stringResource(R.string.descending)) },
                            leadingIcon = { Icon(painterResource(R.drawable.arrow_downward), null) }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier.height(200.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(SortBy.entries) { item ->
                            ListItem(
                                trailingContent = {
                                    if (sortBy == item) Icon(
                                        painterResource(R.drawable.check_circle),
                                        null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                                            alpha = 0.5f
                                        )
                                    )
                                    .clickable { onSortByChanged(item) },
                                colors = androidx.compose.material3.ListItemDefaults.colors(
                                    containerColor = Color.Transparent
                                )
                            ) {
                                Text(
                                    stringResource(item.asGeneralName()),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.done)) }
        }
    }
}
