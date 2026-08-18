// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings.customization

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.asgard.components.AsgardActionItem
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfoActionId
import com.valhalla.thor.presentation.settings.SettingsIconBox
import com.valhalla.thor.presentation.settings.SettingsTopBar
import com.valhalla.thor.presentation.settings.SettingsViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * Dedicated customization screen allowing users to reorder and toggle AppInfo sheet actions.
 */
@Composable
fun AppInfoActionsCustomizationScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = state.prefs

    val currentOrder = prefs.appInfoActionsOrder
    val hiddenActions = prefs.hiddenAppInfoActions

    val localActions = remember { mutableStateListOf<AppInfoActionId>() }
    val listState = rememberLazyListState()

    var showResetConfirmation by remember { mutableStateOf(false) }

    val reorderState = rememberReorderableLazyListState(
        listState = listState,
        onMove = { fromKey, toKey ->
            val fromIndex = localActions.indexOfFirst { it.name == fromKey }
            val toIndex = localActions.indexOfFirst { it.name == toKey }
            if (fromIndex != -1 && toIndex != -1 && fromIndex != toIndex) {
                val item = localActions.removeAt(fromIndex)
                localActions.add(toIndex, item)
            }
        },
        onDragCompleted = {
            viewModel.setAppInfoActionsOrder(localActions.toList())
        }
    )

    // Synchronize local snapshot with repository when not in active drag
    LaunchedEffect(currentOrder) {
        if (reorderState.draggingItemKey == null) {
            localActions.clear()
            localActions.addAll(currentOrder)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsTopBar(
            title = stringResource(R.string.customization_app_info_actions),
            onBack = onBack
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header summary & Preview Card
            item(key = "header_preview") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.customization_drag_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledIconButton(
                            onClick = { showResetConfirmation = true },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.settings_backup_restore),
                                contentDescription = stringResource(R.string.reset_to_default),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Live Preview Section
                    ActionRowPreviewCard(
                        actions = localActions.filterNot { it in hiddenActions }
                    )
                }
            }

            // Reorderable action items
            itemsIndexed(
                items = localActions,
                key = { _, action -> action.name }
            ) { index, action ->
                val isHidden = action in hiddenActions
                val isBeingDragged = reorderState.draggingItemKey == action.name

                val elevation by animateDpAsState(
                    targetValue = if (isBeingDragged) 12.dp else 0.dp,
                    label = "item_elevation"
                )
                val scale by animateFloatAsState(
                    targetValue = if (isBeingDragged) 1.03f else 1.0f,
                    label = "item_scale"
                )

                Box(
                    modifier = Modifier
                        .animateItem()
                        .zIndex(if (isBeingDragged) 10f else 1f)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            if (isBeingDragged) {
                                translationY = reorderState.draggingItemOffset
                            }
                        }
                        .shadow(elevation, RoundedCornerShape(24.dp))
                ) {
                    CustomizableActionItemRow(
                        action = action,
                        index = index,
                        totalCount = localActions.size,
                        isVisible = !isHidden,
                        isBeingDragged = isBeingDragged,
                        reorderState = reorderState,
                        onVisibilityChanged = { visible ->
                            viewModel.setAppInfoActionVisibility(action, visible)
                        },
                        onMoveUp = {
                            if (index > 0) {
                                val item = localActions.removeAt(index)
                                localActions.add(index - 1, item)
                                viewModel.setAppInfoActionsOrder(localActions.toList())
                            }
                        },
                        onMoveDown = {
                            if (index < localActions.size - 1) {
                                val item = localActions.removeAt(index)
                                localActions.add(index + 1, item)
                                viewModel.setAppInfoActionsOrder(localActions.toList())
                            }
                        }
                    )
                }
            }
        }
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.settings_backup_restore),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.reset_actions_confirm_title)) },
            text = { Text(stringResource(R.string.reset_actions_confirm_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetAppInfoActionsCustomization()
                        showResetConfirmation = false
                    }
                ) {
                    Text(stringResource(R.string.reset_to_default))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ActionRowPreviewCard(
    actions: List<AppInfoActionId>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.customization_preview_title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        if (actions.isEmpty()) {
            Text(
                text = "All actions are hidden",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                actions.forEach { action ->
                    AsgardActionItem(
                        icon = ImageVector.vectorResource(action.defaultIconRes),
                        label = stringResource(action.titleRes),
                        onClick = {},
                        iconTint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomizableActionItemRow(
    action: AppInfoActionId,
    index: Int,
    totalCount: Int,
    isVisible: Boolean,
    isBeingDragged: Boolean,
    reorderState: ReorderableLazyListState,
    onVisibilityChanged: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isBeingDragged) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Instant tactile drag handle
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                .dragHandle(action.name, reorderState),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.drag_handle),
                contentDescription = stringResource(R.string.customization_drag_hint),
                modifier = Modifier.size(20.dp),
                tint = if (isBeingDragged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(12.dp))

        // Action Icon
        SettingsIconBox(icon = action.defaultIconRes)

        Spacer(Modifier.width(12.dp))

        // Action Label & Description
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = stringResource(action.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(action.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        // Accessibility Move Buttons for screen readers & discrete stepping
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (index > 0) {
                IconButton(
                    onClick = onMoveUp,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_upward),
                        contentDescription = "Move up",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (index < totalCount - 1) {
                IconButton(
                    onClick = onMoveDown,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_downward),
                        contentDescription = "Move down",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // Visibility Toggle
        Switch(
            checked = isVisible,
            onCheckedChange = onVisibilityChanged
        )
    }
}
