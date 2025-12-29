package com.valhalla.thor.presentation.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
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
import com.valhalla.thor.presentation.utils.AppIconModel
import com.valhalla.thor.presentation.utils.popularInstallers

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
    startAsGrid: Boolean = true,
    isRoot: Boolean = false,
    isShizuku: Boolean = false,
    imageLoader: ImageLoader,
    onSortOrderSelected: (SortOrder) -> Unit = {},
    onSortByChanged: (SortBy) -> Unit = {},
    onFilterSelected: (String?) -> Unit,
    onFilterTypeChanged: (FilterType) -> Unit = {},
    onAppInfoSelected: (AppInfo) -> Unit,
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {
    // 1. Local State
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isGrid by rememberSaveable { mutableStateOf(startAsGrid) }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    var multiSelection by rememberSaveable { mutableStateOf(emptyList<AppInfo>()) }

    val isMultiSelectMode = multiSelection.isNotEmpty()

    // 2. Logic
    BackHandler(isMultiSelectMode) { multiSelection = emptyList() }

    val displayedList = remember(appList, searchQuery) {
        if (searchQuery.isBlank()) appList
        else appList.filter {
            it.appName?.contains(searchQuery, true) == true ||
                    it.packageName.contains(searchQuery, true)
        }
    }

    LaunchedEffect(appListType) { multiSelection = emptyList() }

    // 3. UI Layout
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Search Bar
            AppSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it }
            )

            // System App Warning
            if (appListType == AppListType.SYSTEM) {
                Text(
                    text = "⚠\uFE0E System Apps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp)
                )
            }

            // Headers (Control Bar or MultiSelect Header)
            Box {
                this@Column.AnimatedVisibility(
                    visible = !isMultiSelectMode,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    AppControlBar(
                        installers = installers,
                        selectedFilter = selectedFilter,
                        filterType = filterType,
                        appListType = appListType,
                        isGrid = isGrid,
                        onToggleView = { isGrid = !isGrid },
                        onOpenFilters = { showFilterSheet = true },
                        onFilterSelected = onFilterSelected
                    )
                }

                this@Column.AnimatedVisibility(
                    visible = isMultiSelectMode,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    MultiSelectHeader(
                        count = multiSelection.size,
                        isAllSelected = multiSelection.size == displayedList.size && displayedList.isNotEmpty(),
                        onSelectAll = { selectAll ->
                            multiSelection = if (selectAll) displayedList else emptyList()
                        },
                        onClear = { multiSelection = emptyList() }
                    )
                }
            }

            // App Content (Grid or List)
            AppListContent(
                list = displayedList,
                isGrid = isGrid,
                multiSelection = multiSelection,
                imageLoader = imageLoader,
                onAppClick = { app ->
                    if (isMultiSelectMode) {
                        multiSelection = toggleSelection(multiSelection, app)
                    } else {
                        onAppInfoSelected(app)
                    }
                },
                onAppLongClick = { app ->
                    multiSelection = toggleSelection(multiSelection, app)
                }
            )
        }

        // Floating Action Toolbar (Multi-Select)
        if (isMultiSelectMode) {
            MultiSelectToolBox(
                selected = multiSelection,
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.BottomEnd),
                isRoot = isRoot,
                isShizuku = isShizuku,
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
            onFilterTypeChanged = onFilterTypeChanged,
            onSortByChanged = onSortByChanged,
            onSortOrderChanged = onSortOrderSelected
        )
    }
}

// --- SUB-COMPONENTS ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppSearchBar(
    query: String,
    onQueryChange: (String) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val isImeVisible = WindowInsets.isImeVisible

    BackHandler(enabled = isImeVisible || query.isNotEmpty()) {
        if (isImeVisible) keyboardController?.hide()
        else onQueryChange("")
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp), // More rounded modern look
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text("Search apps...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            painter = painterResource(R.drawable.round_close),
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onQueryChange("") }
                                .padding(4.dp)
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun AppControlBar(
    installers: List<String?>,
    selectedFilter: String?,
    filterType: FilterType,
    appListType: AppListType,
    isGrid: Boolean,
    onToggleView: () -> Unit,
    onOpenFilters: () -> Unit,
    onFilterSelected: (String?) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        IconButton(onClick = onOpenFilters) {
            Icon(painterResource(R.drawable.filter_list), "Filters")
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val chips = if (filterType == FilterType.Source) installers
            else (filterType as? FilterType.State)?.types ?: emptyList()

            chips.forEach { item ->
                val label = when {
                    filterType == FilterType.Source -> popularInstallers[item] ?: item
                    ?: if (appListType != AppListType.SYSTEM) "Others" else "System"
                    else -> item ?: ""
                }

                FilterChip(
                    selected = item == selectedFilter,
                    onClick = { onFilterSelected(item) },
                    label = { Text(label) }
                )
            }
        }

        IconButton(onClick = onToggleView) {
            Icon(
                painterResource(if (isGrid) R.drawable.view_stream else R.drawable.grid_view),
                "Toggle View"
            )
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
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Checkbox(
            checked = isAllSelected,
            onCheckedChange = onSelectAll
        )
        Text(
            text = "$count Selected",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        )
        IconButton(onClick = onClear) {
            Icon(
                painterResource(R.drawable.round_close),
                "Close",
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun AppListContent(
    list: List<AppInfo>,
    isGrid: Boolean,
    multiSelection: List<AppInfo>,
    imageLoader: ImageLoader,
    onAppClick: (AppInfo) -> Unit,
    onAppLongClick: (AppInfo) -> Unit
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
                    isSelected = multiSelection.contains(app),
                    imageLoader = imageLoader,
                    onClick = { onAppClick(app) },
                    onLongClick = { onAppLongClick(app) }
                )
            }
        }
    } else {
        LazyColumn(contentPadding = padding) {
            items(list, key = { it.packageName }) { app ->
                AppItemList(
                    app = app,
                    isSelected = multiSelection.contains(app),
                    imageLoader = imageLoader,
                    onClick = { onAppClick(app) },
                    onLongClick = { onAppLongClick(app) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppItemList(
    app: AppInfo,
    isSelected: Boolean,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surface
            ),
        leadingContent = {
            AppIcon(app.packageName, app.enabled, 48.dp, imageLoader)
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.appName ?: "Unknown", maxLines = 1)
                if (!app.enabled) {
                    Icon(
                        painterResource(R.drawable.frozen),
                        "Frozen",
                        modifier = Modifier.size(16.dp).padding(start = 4.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        supportingContent = { Text(app.packageName, maxLines = 1) },
        trailingContent = {
            if (isSelected) {
                Icon(painterResource(R.drawable.check_circle), "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppItemGrid(
    app: AppInfo,
    isSelected: Boolean,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp)
    ) {
        Box {
            AppIcon(app.packageName, app.enabled, 56.dp, imageLoader)
            if (isSelected) {
                Icon(
                    painterResource(R.drawable.check_circle),
                    "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = app.appName ?: "Unknown",
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AppIcon(
    packageName: String,
    isEnabled: Boolean,
    size: androidx.compose.ui.unit.Dp,
    imageLoader: ImageLoader
) {
    val colorMatrix = remember { ColorMatrix().apply { setToSaturation(0f) } }
    Box(contentAlignment = Alignment.Center) {
        AsyncImage(
            model = AppIconModel(packageName),
            imageLoader = imageLoader,
            contentDescription = null,
            modifier = Modifier.size(size),
            colorFilter = if (!isEnabled) ColorFilter.colorMatrix(colorMatrix) else null,
            error = painterResource(R.drawable.android)
        )
    }
}

private fun toggleSelection(currentSelection: List<AppInfo>, item: AppInfo): List<AppInfo> {
    return if (currentSelection.contains(item)) currentSelection - item else currentSelection + item
}

private enum class SheetTab { FILTERS, SORT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppFilterSheet(
    onDismiss: () -> Unit,
    filterType: FilterType,
    sortBy: SortBy,
    sortOrder: SortOrder,
    onFilterTypeChanged: (FilterType) -> Unit,
    onSortByChanged: (SortBy) -> Unit,
    onSortOrderChanged: (SortOrder) -> Unit
) {
    var activeTab by remember { mutableStateOf(SheetTab.FILTERS) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Configuration", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = activeTab == SheetTab.FILTERS,
                    onClick = { activeTab = SheetTab.FILTERS },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Filters") }
                SegmentedButton(
                    selected = activeTab == SheetTab.SORT,
                    onClick = { activeTab = SheetTab.SORT },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("Sort By") }
            }

            Spacer(Modifier.height(16.dp))

            when (activeTab) {
                SheetTab.FILTERS -> {
                    LazyColumn {
                        items(filterTypes) { type ->
                            ListItem(
                                headlineContent = { Text(type.asGeneralName()) },
                                trailingContent = {
                                    if (filterType == type) Icon(painterResource(R.drawable.check_circle), null)
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onFilterTypeChanged(type) }
                            )
                        }
                    }
                }
                SheetTab.SORT -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Order:", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = sortOrder == SortOrder.ASCENDING,
                            onClick = { onSortOrderChanged(SortOrder.ASCENDING) },
                            label = { Text("Ascending") },
                            leadingIcon = { Icon(painterResource(R.drawable.arrow_upward), null) }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = sortOrder == SortOrder.DESCENDING,
                            onClick = { onSortOrderChanged(SortOrder.DESCENDING) },
                            label = { Text("Descending") },
                            leadingIcon = { Icon(painterResource(R.drawable.arrow_downward), null) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn {
                        items(SortBy.entries) { item ->
                            ListItem(
                                headlineContent = { Text(item.asGeneralName()) },
                                trailingContent = {
                                    if (sortBy == item) Icon(painterResource(R.drawable.check_circle), null)
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSortByChanged(item) }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Done") }
        }
    }
}