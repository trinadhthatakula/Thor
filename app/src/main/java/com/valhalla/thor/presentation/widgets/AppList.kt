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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.SortBy
import com.valhalla.thor.domain.model.SortOrder
import com.valhalla.thor.domain.model.asGeneralName
import com.valhalla.thor.domain.model.filterTypes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.asgard.expressivePress
import com.valhalla.thor.presentation.utils.AppIconModel

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
    isRoot: Boolean = false,
    isShizuku: Boolean = false,
    isDhizuku: Boolean = false,
    installerNameMap: Map<String, String> = emptyMap(),
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
    onFilterSelected: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val chips = if (filterType == FilterType.Source) installers
        else (filterType as? FilterType.State)?.types ?: emptyList()

        chips.forEach { item ->
            val label = when {
                filterType == FilterType.Source -> {
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

                else -> when (item) {
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
    selectedPackageNames: Set<String>,
    onAppClick: (AppInfo) -> Unit,
    onAppLongClick: (AppInfo) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // Shared padding for list/grid
    val padding = PaddingValues(bottom = 100.dp, top = 8.dp)

    if (isGrid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            contentPadding = padding
        ) {
            items(list, key = { it.packageName }) { app ->
                AppItemGrid(
                    app = app,
                    isSelected = selectedPackageNames.contains(app.packageName),
                    onClick = { onAppClick(app) },
                    onLongClick = { onAppLongClick(app) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        }
    } else {
        LazyColumn(contentPadding = padding) {
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
            if (app.isSystem && !app.isInstalled) {
                Icon(
                    painterResource(R.drawable.danger),
                    stringResource(R.string.cd_uninstalled),
                    modifier = Modifier
                        .size(16.dp)
                        .padding(start = 4.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            } else if (!app.enabled) {
                Icon(
                    painterResource(R.drawable.frozen),
                    stringResource(R.string.cd_frozen),
                    modifier = Modifier
                        .size(16.dp)
                        .padding(start = 4.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else if (app.isSuspended) {
                Icon(
                    painterResource(R.drawable.bolt),
                    stringResource(R.string.cd_suspended),
                    modifier = Modifier
                        .size(16.dp)
                        .padding(start = 4.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
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
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(6.dp)
            .expressivePress(interactionSource)
            .clip(RoundedCornerShape(32.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .combinedClickable(
                interactionSource = interactionSource,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(16.dp)
    ) {
        Box {
            AppIcon(
                packageName = app.packageName,
                isEnabled = app.enabled,
                isSuspended = app.isSuspended,
                size = 56.dp,
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
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            } else {
                // Status Indicator
                if (app.isSystem && !app.isInstalled) {
                    Icon(
                        painterResource(R.drawable.danger),
                        stringResource(R.string.cd_uninstalled),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(16.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(2.dp)
                    )
                } else if (!app.enabled) {
                    Icon(
                        painterResource(R.drawable.frozen),
                        stringResource(R.string.cd_frozen),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(16.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(2.dp)
                    )
                } else if (app.isSuspended) {
                    Icon(
                        painterResource(R.drawable.bolt),
                        stringResource(R.string.cd_suspended),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(16.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(2.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
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
                                    type.asGeneralName(),
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
                                    item.asGeneralName(),
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
