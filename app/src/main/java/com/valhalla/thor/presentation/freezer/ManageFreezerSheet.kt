package com.valhalla.thor.presentation.freezer

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.presentation.common.components.ConnectedButtonGroup
import com.valhalla.thor.presentation.common.components.ConnectedButtonGroupItem
import com.valhalla.thor.presentation.widgets.AppIcon
import com.valhalla.thor.presentation.widgets.AppSearchBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageFreezerSheet(
    allApps: List<AppInfo>,
    freezerPackageNames: Set<String>,
    searchQuery: String,
    imageLoader: ImageLoader,
    onSearchChange: (String) -> Unit,
    onToggle: (packageName: String, add: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedType by rememberSaveable { mutableStateOf(AppListType.USER) }

    val filtered = remember(allApps, searchQuery, selectedType) {
        val typeFiltered = allApps.filter { it.isSystem == (selectedType == AppListType.SYSTEM) }
        if (searchQuery.isBlank()) typeFiltered
        else typeFiltered.filter {
            it.appName?.contains(searchQuery, ignoreCase = true) == true ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp,
        contentWindowInsets = { BottomSheetDefaults.modalWindowInsets.union(WindowInsets.ime) }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Manage Freezer",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                    letterSpacing = (-1).sp
                )
                ConnectedButtonGroup(
                    items = AppListType.entries.map { type ->
                        ConnectedButtonGroupItem.Icon(
                            iconRes = if (type == AppListType.USER) R.drawable.apps else R.drawable.android,
                            contentDescription = type.name
                        )
                    },
                    selectedIndex = AppListType.entries.indexOf(selectedType),
                    onItemSelected = { selectedType = AppListType.entries[it] }
                )
            }
            Spacer(Modifier.height(8.dp))

            AppSearchBar(
                query = searchQuery,
                onQueryChange = onSearchChange
            )
            Spacer(Modifier.height(4.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, bottom = 32.dp
            )
        ) {
            items(filtered.sortedBy { it.appName }, key = { it.packageName }) { app ->
                val inFreezer = app.packageName in freezerPackageNames
                FreezerManageItem(
                    app = app,
                    inFreezer = inFreezer,
                    imageLoader = imageLoader,
                    onClick = { onToggle(app.packageName, !inFreezer) }
                )
            }
        }
    }
}

@Composable
private fun FreezerManageItem(
    app: AppInfo,
    inFreezer: Boolean,
    imageLoader: ImageLoader,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(
                if (inFreezer) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(16.dp)
    ) {
        Box {
            AppIcon(app.packageName, app.enabled, app.isSuspended, 56.dp, imageLoader)
            if (inFreezer) {
                Icon(
                    painter = painterResource(R.drawable.check_circle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.appName ?: app.packageName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}
