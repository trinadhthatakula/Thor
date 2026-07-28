// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.freezeTier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.asgard.components.StatusChip
import com.valhalla.thor.presentation.utils.getBloatRecommendationColors
import com.valhalla.thor.presentation.widgets.AppIcon
import com.valhalla.thor.presentation.widgets.AppSearchBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageFreezerSheet(
    allApps: List<AppInfo>,
    freezerPackageNames: Set<String>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onToggle: (packageName: String, add: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedType by rememberSaveable { mutableStateOf(AppListType.USER) }

    // The app awaiting a blocked/expert confirmation, or null. Plain remember rather than
    // rememberSaveable: AppInfo is not Saveable, and a dialog that survives process death would
    // outlive the list it was raised from anyway — losing it on rotation is the safe direction.
    var pendingApp by remember { mutableStateOf<AppInfo?>(null) }

    val filtered = remember(allApps, searchQuery, selectedType) {
        val typeFiltered = allApps.filter { it.isSystem == (selectedType == AppListType.SYSTEM) }
        if (searchQuery.isBlank()) typeFiltered
        else typeFiltered.filter {
            it.appName?.contains(searchQuery, ignoreCase = true) == true ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden
    )
    val coroutineScope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
                    text = stringResource(R.string.manage_freezer),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                    letterSpacing = (-1).sp
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
                    selectedIndex = AppListType.entries.indexOf(selectedType),
                    onItemSelected = { selectedType = AppListType.entries[it] },
                    modifier = Modifier.width(IntrinsicSize.Max)
                )
            }
            Spacer(Modifier.height(8.dp))

            AppSearchBar(
                query = searchQuery,
                onQueryChange = onSearchChange,
                modifier = Modifier.onFocusChanged { focusState ->
                    if (focusState.hasFocus) {
                        coroutineScope.launch {
                            sheetState.expand()
                        }
                    }
                }
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
                    onClick = {
                        // Ask once, here, rather than on every later freeze. A watchlist entry
                        // is a standing instruction: the QS tile and the launcher Freeze-all
                        // shortcut act on it with no UI at all, so add time is the last moment
                        // a warning can reach the user. Removal is never gated — unfreezing is
                        // the way out of a bad state.
                        val tier = app.freezeTier
                        if (!inFreezer && tier != FreezeTier.NORMAL) pendingApp = app
                        else onToggle(app.packageName, !inFreezer)
                    }
                )
            }
        }
    }

    pendingApp?.let { app ->
        FreezeTierDialog(
            app = app,
            onConfirm = {
                onToggle(app.packageName, true)
                pendingApp = null
            },
            onDismiss = { pendingApp = null }
        )
    }
}

/**
 * The blocked / expert confirmation for adding a system app to the Freezer.
 *
 * Mirrors the freeze confirmation in AppInfoDialog, and deliberately so: blocked apps get no
 * confirm button at all, expert apps get a red "Freeze anyway". [FreezerViewModel.toggleManaged]
 * repeats the blocked check because a dialog is advice, not enforcement.
 */
@Composable
private fun FreezeTierDialog(
    app: AppInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val tier = app.freezeTier
    val isBlocked = tier == FreezeTier.BLOCKED
    // Blocked splits two ways for the body text only: no usable UAD data at all vs. an app the
    // list names as unsafe. The verdict is identical either way.
    val isUadFailed = app.isUadLoadFailed
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (isBlocked) R.string.freeze_blocked else R.string.freeze_expert_warning
                ),
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!isUadFailed) {
                    app.bloatRecommendation?.let { rec ->
                        val (color, textColor) = getBloatRecommendationColors(rec)
                        StatusChip(text = rec, containerColor = color, contentColor = textColor)
                        Spacer(Modifier.height(12.dp))
                    }
                }
                Text(
                    text = stringResource(
                        when {
                            isUadFailed -> R.string.uad_load_failed_freeze_desc
                            isBlocked -> R.string.freeze_unsafe_desc
                            else -> R.string.freeze_expert_desc
                        }
                    ),
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            if (!isBlocked) {
                TextButton(onClick = onConfirm) {
                    Text(
                        text = stringResource(R.string.freeze_anyway),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (isBlocked) R.string.close else R.string.no))
            }
        }
    )
}

@Composable
private fun FreezerManageItem(
    app: AppInfo,
    inFreezer: Boolean,
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
            AppIcon(app.packageName, app.enabled, app.isSuspended, 56.dp)
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
