package com.valhalla.thor.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.valhalla.thor.R
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.AppListType
import com.valhalla.thor.model.MultiAppAction
import com.valhalla.thor.model.getAppIcon
import com.valhalla.thor.ui.widgets.AppClickAction
import com.valhalla.thor.ui.widgets.AppInfoDialog
import com.valhalla.thor.ui.widgets.AppList
import com.valhalla.thor.ui.widgets.TypeWriterText

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    modifier: Modifier = Modifier,
    title: String = "App List",
    icon: Int = R.drawable.thor_mono,
    customSelection: AppListType? = null,
    userAppList: List<AppInfo>,
    systemAppList: List<AppInfo>,
    isRefreshing: Boolean = false,
    onAppAction: (AppClickAction) -> Unit = {},
    onRefresh: () -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {

    var installers by remember {
        mutableStateOf(
            userAppList.map { it.installerPackageName }.distinct().toMutableList()
                .apply { add(0, "All") }.toList()
        )
    }
    var selectedFilter: String? by remember {
        mutableStateOf("All")
    }

    var filteredList by remember {
        mutableStateOf(userAppList.sortedBy { it.appName })
    }

    var selectedAppListType: AppListType by rememberSaveable {
        mutableStateOf(
            customSelection ?: AppListType.USER
        )
    }

    LaunchedEffect(selectedFilter, selectedAppListType, isRefreshing) {
        if (selectedAppListType == AppListType.SYSTEM)
            selectedFilter = "All"
        installers = if (selectedAppListType == AppListType.USER)
            userAppList.map { it.installerPackageName }.distinct().toMutableList()
                .apply { add(0, "All") }
        else {
            systemAppList.map { it.installerPackageName }.distinct()
        }
        if (selectedFilter != null && selectedFilter != "Unknown" && installers.contains(
                selectedFilter
            ).not()
        ) selectedFilter = "All"
        filteredList = if (selectedFilter == "All") {
            if (selectedAppListType == AppListType.USER) userAppList.sortedBy { it.appName } else systemAppList.sortedBy { it.appName }
        } else {
            if (selectedAppListType == AppListType.USER) userAppList.filter { it.installerPackageName == selectedFilter }
                .sortedBy { it.appName } else
                systemAppList.filter { it.installerPackageName == selectedFilter }
                    .sortedBy { it.appName }
        }
    }

    when(selectedAppListType){
        AppListType.USER -> {
            if(userAppList.isEmpty()) selectedAppListType = AppListType.SYSTEM
        }
        AppListType.SYSTEM -> {
            if(systemAppList.isEmpty()) selectedAppListType = AppListType.USER
        }
    }

    var context = LocalContext.current


    var selectedAppInfo: AppInfo? by remember {
        mutableStateOf(null)
    }

    Column(modifier.fillMaxWidth()) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(icon),
                title,
                modifier = Modifier
                    .padding(5.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable {

                    }
                    .padding(8.dp)
            )
            TypeWriterText(
                text = title,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .padding(vertical = 10.dp)
                    .weight(1f),
                delay = 25,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Start
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(horizontal = 5.dp)) {
                AppListType.entries.forEachIndexed { index, appListType ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        selected = selectedAppListType == appListType,
                        onClick = {
                            if (selectedAppListType == appListType) return@SegmentedButton
                            when (appListType) {
                                AppListType.USER -> {
                                    if (userAppList.isNotEmpty()) {
                                        selectedAppListType = appListType
                                    }else {
                                        Toast.makeText(
                                            context,
                                            "empty list",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                AppListType.SYSTEM -> {
                                    if (systemAppList.isNotEmpty()) {
                                        selectedAppListType = appListType
                                    }else {
                                        Toast.makeText(
                                            context,
                                            "empty list",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(if (appListType == AppListType.USER) R.drawable.apps else R.drawable.android),
                            appListType.name
                        )
                    }
                }

            }
        }

        val state = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = state
        ) {
            AppList(
                appListType = selectedAppListType,
                installers = installers,
                selectedFilter = selectedFilter,
                appList = filteredList,
                onFilterSelected = {
                    selectedFilter = it
                },
                onAppInfoSelected = {
                    selectedAppInfo = it
                },
                onMultiAppAction = onMultiAppAction
            )
        }

    }

    var reinstallAppInfo: AppInfo? by remember {
        mutableStateOf(null)
    }

    if (selectedAppInfo != null) {
        AppInfoDialog(
            appInfo = selectedAppInfo!!,
            onDismiss = {
                selectedAppInfo = null
            },
            onAppAction = {
                if (it is AppClickAction.Reinstall) {
                    reinstallAppInfo = it.appInfo
                } else {
                    onAppAction(it)
                }
            },

            )
    }

    if (reinstallAppInfo != null) {
        AlertDialog(
            icon = {
                Image(
                    painter = rememberDrawablePainter(
                        getAppIcon(
                            reinstallAppInfo!!.packageName,
                            context
                        )
                    ),
                    reinstallAppInfo?.appName.toString(),
                    modifier = Modifier
                        .size(48.dp)
                        .padding(5.dp)
                )
            },
            onDismissRequest = { reinstallAppInfo = null },
            title = { Text("Are you sure?") },
            text = {
                Text(
                    "You want to reinstall ${reinstallAppInfo!!.appName} with Google Play?",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val temp = reinstallAppInfo
                    reinstallAppInfo = null
                    onAppAction(AppClickAction.Reinstall(temp!!))
                }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = { reinstallAppInfo = null }) {
                    Text("No")
                }
            }
        )
    }

}

