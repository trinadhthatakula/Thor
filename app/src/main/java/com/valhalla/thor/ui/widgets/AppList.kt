package com.valhalla.thor.ui.widgets

import android.R.attr.label
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.valhalla.thor.R
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.AppListType
import com.valhalla.thor.model.FilterType
import com.valhalla.thor.model.MultiAppAction
import com.valhalla.thor.model.SortBy
import com.valhalla.thor.model.SortOrder
import com.valhalla.thor.model.asGeneralName
import com.valhalla.thor.model.filterTypes
import com.valhalla.thor.model.getAppIcon
import com.valhalla.thor.model.getSplits
import com.valhalla.thor.model.popularInstallers

@OptIn(
    ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun AppList(
    appListType: AppListType,
    modifier: Modifier = Modifier,
    installers: List<String?>,
    selectedFilter: String?,
    appList: List<AppInfo>,
    filterType: FilterType = FilterType.Source,
    sortBy: SortBy = SortBy.NAME,
    sortOrder: SortOrder = SortOrder.ASCENDING,
    startAsGrid: Boolean = false,
    onSortOrderSelected: (SortOrder) -> Unit = {},
    onSortByChanged: (SortBy) -> Unit = {},
    onFilterSelected: (String?) -> Unit,
    onFilterTypeChanged: (FilterType) -> Unit = {},
    onAppInfoSelected: (AppInfo) -> Unit,
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {

    var filteredList by remember {
        mutableStateOf(appList)
    }

    var multiSelect by rememberSaveable {
        mutableStateOf(emptyList<AppInfo>())
    }

    var tempList by rememberSaveable {
        mutableStateOf(multiSelect)
    }
    var selectAll: Boolean by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(appListType) {
        multiSelect = emptyList()
    }

    Box(modifier = modifier.fillMaxSize()) {

        Column {

            var query by remember { mutableStateOf("") }
            LaunchedEffect(query, appList) {
                filteredList = if (query.isNotEmpty()) {
                    appList.filter {
                        it.appName?.contains(query, true) == true
                                || it.packageName.contains(query, true)
                    }
                } else appList
            }

            Card(
                modifier = Modifier
                    .padding(5.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(50)
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                    },
                    decorationBox = { tf ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {}) {
                                Icon(
                                    painterResource(R.drawable.round_search),
                                    "Search Icon",
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (query.isEmpty()) {
                                    Text(
                                        "Search any App",
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                                    )
                                }
                                tf()
                            }
                            if (query.isNotEmpty())
                                IconButton(
                                    onClick = {
                                        query = ""
                                    }
                                ) {
                                    Icon(
                                        painterResource(R.drawable.round_close),
                                        "clear"
                                    )
                                }
                        }
                    },
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                    keyboardOptions = KeyboardOptions.Default.copy(
                        keyboardType = KeyboardType.Text,
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Search
                    )
                )
            }


            if (appListType == AppListType.SYSTEM) {
                TypeWriterText(
                    text = "⚠\uFE0E System Apps",
                    delay = 50,
                    modifier = Modifier
                        .padding(horizontal = 15.dp)
                        .padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                )
            }

            var isGrid by rememberSaveable { mutableStateOf(startAsGrid) }

            AnimatedVisibility(multiSelect.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.filter_list),
                        "Filter",
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable {
                                showFilters = true
                            }
                    )
                    Row(
                        modifier = Modifier.Companion
                            .weight(1f)
                            .padding(5.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        if (filterType == FilterType.Source)
                            installers.sortedBy { it ?: "z" }.forEach {
                                FilterChip(
                                    selected = it == selectedFilter, onClick = {
                                        onFilterSelected(it)
                                    }, label = {
                                        Text(
                                            text = popularInstallers[it] ?: it
                                            ?: if (appListType != AppListType.SYSTEM) "Others" else "System"
                                        )
                                    }, modifier = Modifier.Companion.padding(horizontal = 5.dp)
                                )
                            }
                        else if (filterType == FilterType.State)
                            (filterType as FilterType.State).types.forEach {
                                FilterChip(
                                    selected = it == selectedFilter, onClick = {
                                        onFilterSelected(it)
                                    }, label = {
                                        Text(
                                            text = it
                                        )
                                    }, modifier = Modifier.Companion.padding(horizontal = 5.dp)
                                )
                            }
                    }

                    Icon(
                        painterResource(if (isGrid) R.drawable.view_stream else R.drawable.grid_view),
                        "Filter",
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable {
                                isGrid = !isGrid
                            }
                    )
                }

            }
            AnimatedVisibility(multiSelect.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Checkbox(
                        checked = selectAll,
                        onCheckedChange = {
                            selectAll = it
                            multiSelect = if (it) {
                                tempList = multiSelect
                                filteredList
                            } else {
                                tempList
                            }
                        },
                        modifier = Modifier.padding(5.dp)
                    )
                    Text(
                        multiSelect.size.toString(), modifier = Modifier.padding(5.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            multiSelect = emptyList()
                        },
                        modifier = Modifier.padding(5.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.round_close),
                            contentDescription = "Close",
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

            }
            val context = LocalContext.current
            if (!isGrid)
                LazyColumn {
                    items(filteredList) {
                        ListItem(
                            leadingContent = {
                                Box {
                                    Image(
                                        painter = rememberDrawablePainter(
                                            getAppIcon(
                                                it.packageName,
                                                context
                                            )
                                        ),
                                        "App Icon",
                                        modifier = Modifier.Companion
                                            .padding(5.dp)
                                            .size(50.dp),
                                        colorFilter = if (it.enabled) null else ColorFilter.colorMatrix(
                                            ColorMatrix().apply {
                                                setToSaturation(0f)
                                            }
                                        )
                                    )
                                }
                            },
                            headlineContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.horizontalScroll(rememberScrollState())
                                ) {
                                    if (!it.enabled)
                                        Icon(
                                            painterResource(R.drawable.frozen),
                                            "Frozen app",
                                            modifier = Modifier
                                                .size(20.dp)
                                                .padding(2.dp)
                                        )
                                    Text(
                                        it.appName ?: "Unknown",
                                        maxLines = 1
                                    )
                                    if (it.splitPublicSourceDirs.isNotEmpty()) {
                                        Text(
                                            text = "${it.splitPublicSourceDirs.size} Splits",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier
                                                .padding(horizontal = 2.dp)
                                                .background(
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                    RoundedCornerShape(50)
                                                )
                                                .padding(horizontal = 8.dp, vertical = 2.5.dp),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            },
                            supportingContent = {
                                Text(
                                    it.packageName,
                                    maxLines = 1
                                )
                            },
                            trailingContent = {
                                if (multiSelect.contains(it))
                                    Image(
                                        painterResource(R.drawable.check_circle),
                                        "check",
                                        modifier = Modifier
                                            .size(30.dp)
                                    )
                            },
                            modifier = Modifier.Companion
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                }
                                .combinedClickable(
                                    onClick = {
                                        if (multiSelect.isEmpty()) {
                                            getSplits(it.packageName.toString())
                                            onAppInfoSelected(it)
                                        } else {
                                            if (multiSelect.contains(it))
                                                multiSelect -= it
                                            else
                                                multiSelect += it
                                        }
                                    }, onLongClick = {
                                        multiSelect += it
                                    }
                                )
                        )
                    }
                }
            else
                LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 100.dp)) {
                    items(filteredList, key = { it.packageName }) {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (multiSelect.isEmpty()) {
                                            getSplits(it.packageName)
                                            onAppInfoSelected(it)
                                        } else {
                                            if (multiSelect.contains(it))
                                                multiSelect -= it
                                            else
                                                multiSelect += it
                                        }
                                    }, onLongClick = {
                                        multiSelect += it
                                    }
                                )
                        ) {
                            Box {
                                Image(
                                    painter = rememberDrawablePainter(
                                        getAppIcon(
                                            it.packageName,
                                            context
                                        )
                                    ),
                                    "App Icon",
                                    modifier = Modifier.Companion
                                        .padding(5.dp)
                                        .size(50.dp),
                                    colorFilter = if (it.enabled) null else ColorFilter.colorMatrix(
                                        ColorMatrix().apply {
                                            setToSaturation(0f)
                                        }
                                    )
                                )

                                if (multiSelect.contains(it))
                                    Image(
                                        painterResource(R.drawable.check_circle),
                                        "check",
                                        modifier = Modifier
                                            .size(30.dp)
                                            .align(Alignment.BottomEnd)
                                    )
                            }
                            Row {
                                Text(
                                    "${if (it.enabled.not()) "❅" else ""}${it.appName ?: "Unknown"}",
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodyMediumEmphasized,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                )

                            }

                        }
                    }
                }
        }

        if (multiSelect.isNotEmpty()) {
            if (multiSelect.size == 1)
                selectAll = false
            MultiSelectToolBox(
                selected = multiSelect,
                modifier = Modifier
                    .padding(10.dp)
                    .align(Alignment.BottomEnd),
                onCancel = {
                    multiSelect = emptyList()
                },
                onMultiAppAction = {
                    onMultiAppAction(it)
                    multiSelect = emptyList()
                }
            )
        }

    }

    if (showFilters) {
        var title by remember { mutableStateOf("Filters") }
        var isFilters by remember { mutableStateOf(true) }
        ModalBottomSheet(
            onDismissRequest = {
                showFilters = false
            }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(5.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .padding(10.dp)
                        .weight(1f),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isFilters.not()) {
                    val rotation: Float by animateFloatAsState(
                        targetValue = sortOrder.angle(),
                        label = "Sort Order Rotation"
                    )
                    RotatableActionItem (
                        icon = R.drawable.arrow_upward,
                        rotation = rotation,
                        text = sortOrder.asGeneralName()
                    ) {
                        onSortOrderSelected(sortOrder.flip())
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(horizontal = 5.dp)
            ) {
                FilterChip(
                    selected = isFilters,
                    onClick = {
                        isFilters = true
                        title = "Filters"
                    },
                    label = {
                        Text("Filters")
                    }
                )
                FilterChip(
                    selected = !isFilters,
                    onClick = {
                        isFilters = false
                        title = "Sort By"
                    },
                    label = {
                        Text("Sort By")
                    }
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 250.dp)
            ) {
                if (isFilters) {
                    filterTypes.forEach { fType ->
                        Card(
                            Modifier
                                .padding(5.dp)
                                .clickable {
                                    onFilterTypeChanged(fType)
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (filterType == fType) {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(5.dp)
                                    .fillMaxWidth()
                                    .padding(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = fType.asGeneralName(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                if (filterType == fType) {
                                    Image(
                                        painterResource(R.drawable.check_circle),
                                        "selected filter type",
                                    )
                                }
                            }

                        }
                    }
                    Spacer(modifier = Modifier.height(100.dp))
                } else {
                    SortBy.entries.forEach { sBy ->
                        Card(
                            Modifier
                                .padding(5.dp)
                                .clickable {
                                    onSortByChanged(sBy)
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (sortBy == sBy) {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(5.dp)
                                    .fillMaxWidth()
                                    .padding(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sBy.asGeneralName(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                if (sortBy == sBy) {
                                    Image(
                                        painterResource(R.drawable.check_circle),
                                        "selected sort sBy",
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        showFilters = false
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                        .padding(bottom = 10.dp)
                ) {
                    Text("Apply")
                }
            }


        }
    }


}


