package com.valhalla.thor.ui.widgets

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
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.valhalla.thor.model.AppInfo
import com.valhalla.thor.model.AppListType
import com.valhalla.thor.model.getAppIcon
import com.valhalla.thor.model.getSplits
import com.valhalla.thor.model.popularInstallers

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
        val context = LocalContext.current
        LazyColumn {
            items(filteredList) {
                ListItem(
                    leadingContent = {
                        Image(
                            painter = rememberDrawablePainter(getAppIcon(it.packageName, context)),
                            "App Icon",
                            modifier = Modifier.Companion
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
                    modifier = Modifier.Companion
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            getSplits(it.packageName.toString())
                            onAppInfoSelected(it)
                        }
                )
            }
        }

    }
}