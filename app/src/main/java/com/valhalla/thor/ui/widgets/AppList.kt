package com.valhalla.thor.ui.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.valhalla.thor.R
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.AppListType
import com.valhalla.thor.model.MultiAppAction
import com.valhalla.thor.model.getAppIcon
import com.valhalla.thor.model.getSplits
import com.valhalla.thor.model.popularInstallers

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppList(
    appListType: AppListType,
    modifier: Modifier = Modifier,
    installers: List<String?>,
    selectedFilter: String?,
    filteredList: List<AppInfo>,
    onFilterSelected: (String?) -> Unit,
    onAppInfoSelected: (AppInfo) -> Unit,
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {

    var multiSelect by remember {
        mutableStateOf(emptyList<AppInfo>())
    }

    var tempList by remember {
        mutableStateOf(multiSelect)
    }
    var selectAll: Boolean by remember { mutableStateOf(false) }

    LaunchedEffect(appListType) {
        multiSelect = emptyList()
    }

    Box {

        Column(modifier) {

            AnimatedVisibility(multiSelect.isEmpty()) {
                Row(
                    modifier = Modifier.Companion
                        .fillMaxWidth()
                        .padding(5.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    installers.sortedBy { it ?: "z" }.forEach {
                        FilterChip(
                            selected = it == selectedFilter, onClick = {
                                onFilterSelected(it)
                            }, label = {
                                Text(
                                    text = popularInstallers[it] ?: it
                                    ?: if (appListType != AppListType.SYSTEM) "Unknown" else "System"
                                )
                            }, modifier = Modifier.Companion.padding(horizontal = 5.dp)
                        )
                    }
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
                                        .size(50.dp)
                                )
                            }
                        }, headlineContent = {
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
                        }, supportingContent = {
                            Text(
                                it.packageName
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
                                    if (filteredList.size > 1)
                                        multiSelect += it
                                }
                            )
                    )
                }
            }

        }

        if (multiSelect.isNotEmpty()) {
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
}


