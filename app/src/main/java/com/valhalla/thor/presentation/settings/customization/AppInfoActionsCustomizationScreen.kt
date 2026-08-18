// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings.customization

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.asgard.components.AsgardActionItem
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfoActionId
import com.valhalla.thor.presentation.settings.SettingsIconBox
import com.valhalla.thor.presentation.settings.SettingsTopBar
import com.valhalla.thor.presentation.settings.SettingsViewModel
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

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

    var showResetConfirmation by remember { mutableStateOf(false) }

    // Reorder state tracking
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragAccumulatedY by remember { mutableFloatStateOf(0f) }

    val listState = rememberLazyListState()

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
                        TextButton(onClick = { showResetConfirmation = true }) {
                            Icon(
                                painter = painterResource(R.drawable.settings_backup_restore),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.reset_to_default),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    // Live Preview Section
                    ActionRowPreviewCard(
                        actions = currentOrder.filterNot { it in hiddenActions }
                    )
                }
            }

            // Reorderable action items
            itemsIndexed(
                items = currentOrder,
                key = { _, action -> action.name }
            ) { index, action ->
                val isHidden = action in hiddenActions
                val isBeingDragged = draggingIndex == index

                val elevation by animateDpAsState(
                    targetValue = if (isBeingDragged) 8.dp else 0.dp,
                    label = "item_elevation"
                )
                val scale by animateFloatAsState(
                    targetValue = if (isBeingDragged) 1.02f else 1.0f,
                    label = "item_scale"
                )

                Box(
                    modifier = Modifier
                        .zIndex(if (isBeingDragged) 1f else 0f)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .offset {
                            if (isBeingDragged) {
                                IntOffset(x = 0, y = dragAccumulatedY.roundToInt())
                            } else {
                                IntOffset.Zero
                            }
                        }
                        .shadow(elevation, RoundedCornerShape(24.dp))
                ) {
                    CustomizableActionItemRow(
                        action = action,
                        index = index,
                        totalCount = currentOrder.size,
                        isVisible = !isHidden,
                        isBeingDragged = isBeingDragged,
                        onVisibilityChanged = { visible ->
                            viewModel.setAppInfoActionVisibility(action, visible)
                        },
                        onMoveUp = {
                            if (index > 0) {
                                val mutable = currentOrder.toMutableList()
                                val item = mutable.removeAt(index)
                                mutable.add(index - 1, item)
                                viewModel.setAppInfoActionsOrder(mutable)
                            }
                        },
                        onMoveDown = {
                            if (index < currentOrder.size - 1) {
                                val mutable = currentOrder.toMutableList()
                                val item = mutable.removeAt(index)
                                mutable.add(index + 1, item)
                                viewModel.setAppInfoActionsOrder(mutable)
                            }
                        },
                        onDragStart = {
                            draggingIndex = index
                            dragAccumulatedY = 0f
                        },
                        onDrag = { dragAmount ->
                            dragAccumulatedY += dragAmount.y
                            val itemHeightPx = 200f // Approximate threshold in px per item
                            val currentIndex = draggingIndex ?: return@CustomizableActionItemRow
                            val targetIndex = (currentIndex + (dragAccumulatedY / itemHeightPx).roundToInt())
                                .coerceIn(0, currentOrder.size - 1)

                            if (targetIndex != currentIndex) {
                                val mutable = currentOrder.toMutableList()
                                val item = mutable.removeAt(currentIndex)
                                mutable.add(targetIndex, item)
                                viewModel.setAppInfoActionsOrder(mutable)
                                draggingIndex = targetIndex
                                dragAccumulatedY = 0f
                            }
                        },
                        onDragEnd = {
                            draggingIndex = null
                            dragAccumulatedY = 0f
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
    onVisibilityChanged: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (androidx.compose.ui.geometry.Offset) -> Unit,
    onDragEnd: () -> Unit,
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
        // Drag Handle
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onDrag = { _, dragAmount -> onDrag(dragAmount) }
                    )
                },
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

        // Accessibility Move Buttons
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
