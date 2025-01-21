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
import com.valhalla.thor.ui.widgets.AppAction
import com.valhalla.thor.ui.widgets.AppInfoDialog

@Composable
fun AppListScreen(
    appList: List<AppInfo>, modifier: Modifier = Modifier, onAppAction: (AppAction) -> Unit = {}
) {
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

    val installers = appList.map { it.installerPackageName }.distinct().toMutableList()
    installers.add(0, "All")
    var selectedFilter: String? by remember {
        mutableStateOf("All")
    }

    var filteredList by remember {
        mutableStateOf(appList.sortedBy { it.appName })
    }
    LaunchedEffect(selectedFilter) {
        filteredList = if (selectedFilter == "All") {
            appList.sortedBy { it.appName }
        } else {
            appList.filter { it.installerPackageName == selectedFilter }.sortedBy { it.appName }
        }
    }

    var selectedAppInfo: AppInfo? by remember {
        mutableStateOf(null)
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "App List",
                modifier = Modifier
                    .padding(10.dp)
                    .weight(1f),
                style = MaterialTheme.typography.titleLarge
            )
            IconButton(
                onClick = {
                    onAppAction(AppAction.Reinstall)
                }) {
                Icon(
                    painter = painterResource(R.drawable.apk_install),
                    "Re-Install Apps with Google Play"
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(5.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            installers.sortedBy { it ?: "z" }.forEach {
                FilterChip(
                    selected = it == selectedFilter, onClick = {
                    selectedFilter = it
                }, label = {
                    Text(text = popularInstallers[it] ?: it ?: "Unknown")
                }, modifier = Modifier.padding(horizontal = 5.dp)
                )
            }
        }
        val context = LocalContext.current
        LazyColumn {
            items(filteredList) {
                ListItem(leadingContent = {
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
                }, modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        selectedAppInfo = it
                    })
            }
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


fun getAppIcon(packageName: String?, context: Context): Drawable? {
    return packageName?.let {
        try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }
}
