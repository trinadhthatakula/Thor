// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings.customization

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** Breathing room left around a row that a Move button has just scrolled back into view. */
private val RevealMargin = 12.dp

/**
 * Share of the body the pinned header may take before it starts scrolling inside its own bounds.
 *
 * A `Column` measures an unweighted child against whatever height is left, so a header that grows
 * — landscape, or a large display/font scale, where the hint wraps and the preview chips get taller
 * — can eat the whole body and leave the weighted list measured at zero height, with no scroll
 * anywhere to recover it. No ordinary configuration comes near this cap; it exists so the failure
 * degrades into a scrollable header rather than an empty screen.
 */
private const val HEADER_MAX_HEIGHT_FRACTION = 0.5f

/** Opacity applied to a hidden action's glyph and labels. */
private const val HIDDEN_ROW_ALPHA = 0.45f

/** Opacity of a Move button that has run out of list in its direction. */
private const val DISABLED_ICON_ALPHA = 0.38f

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

    // Seeded rather than left empty and filled by the effect below: an empty first frame renders the
    // list with no rows and the preview with its "all actions are hidden" copy, which flashes past
    // on every entry to the screen.
    val localActions = remember { mutableStateListOf<AppInfoActionId>().apply { addAll(currentOrder) } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val revealMarginPx = with(LocalDensity.current) { RevealMargin.roundToPx() }

    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }

    // An order that arrived mid-drag, held until the finger lifts. Deferred rather than dropped:
    // the effect below is keyed on `currentOrder`, so a value it declines to apply is not seen
    // again, and the case where that bites is the one that leaves no trace — a drag that settles
    // back where it started writes nothing, `currentOrder` never changes again, and `localActions`
    // stays diverged from what is actually stored until the screen is left. Any later drag then
    // persists that stale snapshot over the change it never applied.
    var deferredOrder by remember { mutableStateOf<List<AppInfoActionId>?>(null) }

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
            // Whatever was deferred during this drag is older than what the drag just built, so it
            // is dropped rather than applied: the only writers of this preference are this screen,
            // and none of them writes optimistically, so every value that can reach the deferral is
            // a snapshot from before the finger went down. Cleared here and not in the effect below
            // because `finishDrag` calls this synchronously, before the `draggingItemKey` change
            // recomposes — so the effect sees `null` and leaves the list alone. Left set, it applied
            // that older snapshot *after* this write, and a reorder the user had just finished
            // visibly snapped back until its own echo returned. A drag that moved nothing never
            // reaches here (`movedDuringGesture`), which is the case the deferral exists for.
            deferredOrder = null
            viewModel.setAppInfoActionsOrder(localActions.toList())
        }
    )

    /** Steps a row one slot in [delta]'s direction and keeps it on screen while it travels. */
    val moveBy: (index: Int, delta: Int) -> Unit = { index, delta ->
        val target = index + delta
        if (index in localActions.indices && target in localActions.indices) {
            val item = localActions.removeAt(index)
            localActions.add(target, item)
            viewModel.setAppInfoActionsOrder(localActions.toList())
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            scope.launch { listState.keepIndexVisible(target, revealMarginPx) }
        }
    }

    // Synchronize local snapshot with repository when not in active drag
    LaunchedEffect(currentOrder) {
        if (reorderState.draggingItemKey != null) {
            deferredOrder = currentOrder
        } else if (localActions.toList() != currentOrder) {
            deferredOrder = null
            localActions.clear()
            localActions.addAll(currentOrder)
        }
    }

    // Keyed on the drag, and doing nothing unless something was actually deferred: the drag's own
    // completion writes `localActions` back through the view model, and that write takes a few
    // frames to return through `prefs`, so an unconditional re-sync here would flash the pre-drag
    // order in the window between the two. `onDragCompleted` has already cleared the deferral in
    // that case, so what reaches here is a drag that moved nothing — the one drag whose own list is
    // not newer than what arrived.
    LaunchedEffect(reorderState.draggingItemKey) {
        if (reorderState.draggingItemKey != null) return@LaunchedEffect
        val pending = deferredOrder ?: return@LaunchedEffect
        deferredOrder = null
        if (localActions.toList() != pending) {
            localActions.clear()
            localActions.addAll(pending)
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

        // The drag hint, reset action and live preview stay on screen while the action list scrolls
        // underneath, so the preview always reflects the arrangement being edited.
        PinnedHeaderScaffold(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            header = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(8.dp))
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
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                            // Everything but the dragged row animates into its new slot. The
                            // dragged row is placed by hand through the translation below, and
                            // the swap already subtracts the slot change from it — animating
                            // the same move a second time makes the card lurch under the finger
                            // on every reorder.
                            .then(if (isBeingDragged) Modifier else Modifier.animateItem())
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
                            onMoveUp = { moveBy(index, -1) },
                            onMoveDown = { moveBy(index, 1) }
                        )
                    }
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

/**
 * A pinned [header] above a [body] that takes the rest of the space.
 *
 * The header is unweighted, and a `Column` measures an unweighted child against everything that is
 * left — so a header that grows past the viewport (landscape, or a large display/font scale) would
 * be handed the whole height and the weighted [body] would be measured at zero, with no scroll
 * anywhere to recover it. Capping the header at [headerMaxHeightFraction] of the available height
 * and letting it scroll inside that cap keeps the body non-empty at any configuration, while a
 * header that fits — the normal case — is untouched and does not scroll.
 */
@Composable
internal fun PinnedHeaderScaffold(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    headerMaxHeightFraction: Float = HEADER_MAX_HEIGHT_FRACTION,
    body: @Composable () -> Unit
) {
    BoxWithConstraints(modifier) {
        val headerMaxHeight = maxHeight * headerMaxHeightFraction
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = headerMaxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                header()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                body()
            }
        }
    }
}

/**
 * Brings the row at [index] back into view after a Move button has stepped something into it.
 *
 * Deliberately measured against the pre-move layout: the click handler runs before the list is
 * remeasured, and the slot the moved row is about to occupy is the one [index] holds right now.
 * Without this, holding down Move down walks the row past the edge of the viewport and the user
 * loses sight of the thing they are moving.
 */
private suspend fun LazyListState.keepIndexVisible(index: Int, marginPx: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
        return
    }
    val top = item.offset - marginPx
    val bottom = item.offset + item.size + marginPx
    val delta = when {
        top < info.viewportStartOffset -> top - info.viewportStartOffset
        bottom > info.viewportEndOffset -> bottom - info.viewportEndOffset
        else -> 0
    }
    if (delta != 0) animateScrollBy(delta.toFloat())
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
                text = stringResource(R.string.all_actions_hidden),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Each entry is an AsgardActionItem, which is clickable by construction, so left alone
            // the preview hands assistive technology sixteen buttons that do nothing before the
            // first real control on the screen. Collapsed to one node reading the order out loud,
            // which is the only thing the preview is here to convey.
            val spokenOrder = actions.map { stringResource(it.titleRes) }.joinToString(", ")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .clearAndSetSemantics { contentDescription = spokenOrder },
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
    val title = stringResource(action.titleRes)

    // A hidden action keeps its place in the list because it is still reorderable, so the switch is
    // the only thing distinguishing it from a visible one — not something you can scan across
    // sixteen rows. Dimming the glyph and the labels makes the state readable at a glance.
    val contentAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else HIDDEN_ROW_ALPHA,
        label = "row_content_alpha"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Instant tactile drag handle. Cleared from the semantics tree because a drag is not a
        // gesture TalkBack or a keyboard can perform, and the Move buttons below already expose
        // reordering to both — left in, it would put the same unusable node in front of every row.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                .dragHandle(action.name, reorderState)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.drag_handle),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isBeingDragged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(12.dp))

        // Action Icon
        SettingsIconBox(
            icon = action.defaultIconRes,
            modifier = Modifier.alpha(contentAlpha)
        )

        Spacer(Modifier.width(12.dp))

        // Action Label & Description
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(contentAlpha)
        ) {
            Text(
                text = title,
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

        // Accessibility Move Buttons for screen readers & discrete stepping. Stacked rather than
        // side by side: two 32dp buttons in a row cost twice the width in a layout that already has
        // a handle, a glyph and a switch competing with the labels, and up-over-down matches the
        // direction each one moves the row. Both are always present and disabled at the ends of the
        // list, so the switch column does not jog sideways on the first and last rows.
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MoveButton(
                icon = R.drawable.arrow_upward,
                contentDescription = stringResource(R.string.cd_move_up),
                enabled = index > 0,
                onClick = onMoveUp
            )
            MoveButton(
                icon = R.drawable.arrow_downward,
                contentDescription = stringResource(R.string.cd_move_down),
                enabled = index < totalCount - 1,
                onClick = onMoveDown
            )
        }

        Spacer(Modifier.width(8.dp))

        // Visibility Toggle. Named after the action it governs: on its own a Switch is announced
        // with its state and no subject, and there are sixteen of them on this screen.
        Switch(
            checked = isVisible,
            onCheckedChange = onVisibilityChanged,
            modifier = Modifier.semantics { contentDescription = title }
        )
    }
}

@Composable
private fun MoveButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            // Passing an explicit tint opts out of IconButton's disabled colour, so the disabled
            // state has to be carried here or a Move button at the end of the list looks live.
            tint = MaterialTheme.colorScheme.onSurfaceVariant
                .copy(alpha = if (enabled) 1f else DISABLED_ICON_ALPHA)
        )
    }
}
