package com.valhalla.thor.ui.screens

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.valhalla.thor.ui.widgets.AppClickAction
import com.valhalla.thor.ui.widgets.AppInfoDialog
import androidx.core.content.edit

val popularInstallers = mapOf<String, String>(
    "com.android.vending" to "Google Play Store",
    "com.sec.android.app.samsungapps" to "Samsung Store",
    "com.huawei.appmarket" to "Huawei Store",
    "com.amazon.venezia" to "Amazon App Store",
    "com.miui.supermarket" to "Xiaomi Store",
    "com.xiaomi.discover" to "Xiaomi Discover",
    "com.oppo.market" to "Oppo Store",
    "com.vivo.sdkplugin" to "Vivo Store",
    "com.oneplus.appstore" to "OnePlus Store",
    "com.qualcomm.qti.appstore" to "Qualcomm Store",
    "com.sonymobile.playanywhere" to "Sony Store",
    "com.asus.appmarket" to "Asus Store",
    "com.zte.appstore" to "ZTE Store",
    "com.lenovo.leos.appstore" to "Lenovo Store",
    "com.htc.appmarket" to "HTC Store",
    "com.lge.appbox.client" to "LG Store",
    "com.nokia.nstore" to "Nokia Store",
    "com.miui.packageinstaller" to "Xiaomi Package Installer",
    "com.google.android.packageinstaller" to "Google Package Installer",
    "com.android.packageinstaller" to "Android Package Installer",
    "com.samsung.android.packageinstaller" to "Samsung Package Installer",
)

enum class AppListType {
    USER, SYSTEM
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    userAppList: List<AppInfo>,
    systemAppList: List<AppInfo>,
    modifier: Modifier = Modifier,
    onAppAction: (AppClickAction) -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onEggBroken: () -> Unit = {}
) {

    var selectedAppListType by remember {
        mutableStateOf(AppListType.USER)
    }

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
    LaunchedEffect(selectedFilter, selectedAppListType) {
        installers = if (selectedAppListType == AppListType.USER)
            userAppList.map { it.installerPackageName }.distinct().toMutableList()
                .apply { add(0, "All") }
        else {
            systemAppList.map { it.installerPackageName }.distinct()
        }
        filteredList = if (selectedFilter == "All") {
            if (selectedAppListType == AppListType.USER) userAppList.sortedBy { it.appName } else systemAppList.sortedBy { it.appName }
        } else {
            if (selectedAppListType == AppListType.USER) userAppList.filter { it.installerPackageName == selectedFilter }
                .sortedBy { it.appName } else
                systemAppList.filter { it.installerPackageName == selectedFilter }
                    .sortedBy { it.appName }
        }
    }

    var selectedAppInfo: AppInfo? by remember {
        mutableStateOf(null)
    }

    var titleEasterEgg by remember {
        mutableStateOf("App List")
    }
    var counter by remember {
        mutableIntStateOf(1)
    }
    var context = LocalContext.current
    LaunchedEffect(counter) {
        if (counter > 99) {
            counter = 1
            titleEasterEgg = "App List"

        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = titleEasterEgg,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        titleEasterEgg = when (titleEasterEgg) {
                            "App List" -> "Hey! You found me!"
                            "Hey! You found me!" -> "Now go back"
                            "Now go back" -> "Stop it"
                            "Stop it" -> "Ouch! Stop it"
                            "Ouch! Stop it" -> "I'm serious"
                            "I'm serious" -> "Fine! You win"
                            "Fine! You win" -> "I'm done"
                            else -> {
                                counter++.toString()
                            }
                        }
                    }
                    .padding(10.dp)
                    .weight(1f),
                style = MaterialTheme.typography.titleLarge
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(horizontal = 5.dp)) {
                AppListType.entries.forEachIndexed { index, appListType ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        selected = selectedAppListType == appListType,
                        onClick = {
                            selectedAppListType = appListType
                            selectedFilter = "All"
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
                filteredList = filteredList,
                onFilterSelected = {
                    selectedFilter = it
                },
                onAppInfoSelected = {
                    selectedAppInfo = it
                }
            )
        }

    }

    if (selectedAppInfo != null) {
        AppInfoDialog(
            appInfo = selectedAppInfo!!, onDismiss = {
                selectedAppInfo = null
            }, onAppAction = onAppAction
        )
    }

}

@Composable
fun AppList(
    appListType: AppListType,
    modifier: Modifier = Modifier,
    installers: List<String?>,
    selectedFilter: String?,
    filteredList: List<AppInfo>,
    onFilterSelected: (String?) -> Unit,
    onAppInfoSelected: (AppInfo) -> Unit
) {
    Column(modifier) {

        Row(
            modifier = Modifier
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
                    }, modifier = Modifier.padding(horizontal = 5.dp)
                )
            }
        }
        val context = LocalContext.current
        LazyColumn {
            items(filteredList) {
                ListItem(
                    leadingContent = {
                        Image(
                            painter = rememberDrawablePainter(getAppIcon(it.packageName, context)),
                            "App Icon",
                            modifier = Modifier
                                .padding(5.dp)
                                .size(50.dp)
                        )
                    }, headlineContent = {
                        Text(
                            it.appName ?: "Unknown"
                        )
                    }, supportingContent = {
                        Text(
                            it.packageName ?: "Unknown"
                        )
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onAppInfoSelected(it)
                        }
                )
            }
        }

    }
}


fun getAppIcon(packageName: String?, context: Context): Drawable? {
    return packageName?.let {
        try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
