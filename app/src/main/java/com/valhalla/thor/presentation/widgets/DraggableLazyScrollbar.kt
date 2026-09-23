// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A draggable scrollbar for a vertical lazy list. Place it over the list as a sibling in a [Box],
 * aligned to [Alignment.CenterEnd]. The 48 dp-wide pointer target follows only the thumb; the track does
 * not intercept taps on app rows underneath it.
 */
@Composable
fun DraggableLazyScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val items by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val total = info.totalItemsCount
            val start = info.viewportStartOffset
            val end = info.viewportEndOffset
            var first = Int.MAX_VALUE
            var last = Int.MIN_VALUE
            var fullyVisible = 0
            info.visibleItemsInfo.forEach { item ->
                val itemEnd = item.offset + item.size
                if (itemEnd > start && item.offset < end) {
                    first = minOf(first, item.index + 1)
                    last = maxOf(last, item.index + 1)
                    if (item.offset >= start && itemEnd <= end) fullyVisible++
                }
            }
            val fallback = (state.firstVisibleItemIndex + 1).coerceIn(1, max(1, total))
            val firstVisible = if (first == Int.MAX_VALUE) fallback else first
            val lastVisible = if (last == Int.MIN_VALUE) fallback else last
            VisibleItems(
                total, firstVisible, lastVisible,
                total > 0 && fullyVisible == total && firstVisible == 1 && lastVisible == total
            )
        }
    }
    DraggableLazyScrollbarContent(state, items, modifier)
}

/** The same scrollbar for a vertical lazy grid. The bubble counts visible apps, not grid rows. */
@Composable
fun DraggableLazyScrollbar(state: LazyGridState, modifier: Modifier = Modifier) {
    val items by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val total = info.totalItemsCount
            val start = info.viewportStartOffset
            val end = info.viewportEndOffset
            var first = Int.MAX_VALUE
            var last = Int.MIN_VALUE
            var fullyVisible = 0
            info.visibleItemsInfo.forEach { item ->
                val itemEnd = item.offset.y + item.size.height
                if (item.row >= 0 && itemEnd > start && item.offset.y < end) {
                    first = minOf(first, item.index + 1)
                    last = maxOf(last, item.index + 1)
                    if (item.offset.y >= start && itemEnd <= end) fullyVisible++
                }
            }
            val fallback = (state.firstVisibleItemIndex + 1).coerceIn(1, max(1, total))
            val firstVisible = if (first == Int.MAX_VALUE) fallback else first
            val lastVisible = if (last == Int.MIN_VALUE) fallback else last
            VisibleItems(
                total, firstVisible, lastVisible,
                total > 0 && fullyVisible == total && firstVisible == 1 && lastVisible == total
            )
        }
    }
    DraggableLazyScrollbarContent(state, items, modifier)
}

private data class VisibleItems(
    val total: Int,
    val first: Int,
    val last: Int,
    val allFullyVisible: Boolean
)

private class ScrollbarGeometry {
    var rootCoordinates: LayoutCoordinates? = null
    var thumbCoordinates: LayoutCoordinates? = null
    var insetPx: Int = 0
    var thumbTopPx: Int = 0
    var thumbHeightPx: Int = 0
    var travelPx: Int = 0

    fun pointerYInRoot(position: Offset): Float {
        val root = rootCoordinates
        val thumb = thumbCoordinates
        return if (root != null && thumb != null && root.isAttached && thumb.isAttached) {
            root.localPositionOf(thumb, position).y
        } else {
            insetPx + thumbTopPx + position.y
        }
    }
}

@Composable
private fun DraggableLazyScrollbarContent(
    state: ScrollableState,
    items: VisibleItems,
    modifier: Modifier
) {
    val indicator = state.scrollIndicatorState ?: return
    val contentSize = indicator.contentSize
    val viewportSize = indicator.viewportSize
    if (items.total <= 0 || items.allFullyVisible || contentSize == Int.MAX_VALUE ||
        viewportSize == Int.MAX_VALUE || viewportSize <= 0 ||
        (!state.canScrollForward && !state.canScrollBackward)
    ) return

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val geometry = remember(state) { ScrollbarGeometry() }
    var heightPx by remember { mutableIntStateOf(0) }
    var bubbleHeightPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val active = dragging || focused

    val insetPx = with(density) { 8.dp.roundToPx() }
    val minThumbPx = with(density) { 52.dp.roundToPx() }
    val trackHeightPx = (heightPx - insetPx * 2).coerceAtLeast(0)
    val estimatedContent = max(contentSize, viewportSize + 1)
    val thumbHeightPx = if (trackHeightPx > 0) {
        (trackHeightPx.toFloat() * viewportSize / estimatedContent)
            .roundToInt().coerceAtLeast(minThumbPx).coerceAtMost(trackHeightPx)
    } else 0
    val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0)
    val minTravelPx = with(density) { 32.dp.roundToPx() }
    val maxOffset = (estimatedContent - viewportSize).coerceAtLeast(1)
    val progress by remember(state, indicator, maxOffset) {
        derivedStateOf {
            when {
                !state.canScrollBackward -> 0f
                !state.canScrollForward -> 1f
                else -> (indicator.scrollOffset.toFloat() / maxOffset).coerceIn(0f, 1f)
            }
        }
    }
    val thumbTopPx = (progress * travelPx).roundToInt()
    geometry.insetPx = insetPx
    geometry.thumbTopPx = thumbTopPx
    geometry.thumbHeightPx = thumbHeightPx
    geometry.travelPx = travelPx

    val first = items.first.coerceIn(1, items.total)
    val last = items.last.coerceIn(first, items.total)
    val visibleRange = stringResource(R.string.scrollbar_visible_range, first, last, items.total)
    val scrollAppsLabel = stringResource(R.string.scrollbar_scroll_apps)
    val quietColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val accentColor = MaterialTheme.colorScheme.primary
    val thumbColor by animateColorAsState(
        targetValue = if (active) accentColor else quietColor,
        animationSpec = tween(160),
        label = "Scrollbar thumb color"
    )
    val thumbWidth by animateDpAsState(
        targetValue = if (active) 18.dp else 6.dp,
        animationSpec = tween(160),
        label = "Scrollbar thumb width"
    )
    val trackWidth by animateDpAsState(
        targetValue = if (active) 6.dp else 3.dp,
        animationSpec = tween(160),
        label = "Scrollbar track width"
    )
    val detailAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(120),
        label = "Scrollbar details"
    )
    val trackHeight = with(density) { trackHeightPx.toDp() }
    val thumbHeight = with(density) { thumbHeightPx.toDp() }
    val thumbY = with(density) { (insetPx + thumbTopPx).toDp() }
    val bubbleTopPx = (insetPx + thumbTopPx + thumbHeightPx / 2 - bubbleHeightPx / 2)
        .coerceIn(0, (heightPx - bubbleHeightPx).coerceAtLeast(0))
    val bubbleY = with(density) { bubbleTopPx.toDp() }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(48.dp)
            .onSizeChanged { heightPx = it.height }
            .onGloballyPositioned { geometry.rootCoordinates = it }
    ) {
        // Keep measuring the rail, but leave rows touchable when the thumb has no useful travel.
        if (travelPx < minTravelPx) return@Box

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset {
                    IntOffset((-(19.dp - trackWidth / 2)).roundToPx(), 8.dp.roundToPx())
                }
                .width(trackWidth)
                .height(trackHeight)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f), CircleShape)
        )

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-54).dp, y = bubbleY)
                // Measure the whole localized range, then pin its trailing edge beside the rail.
                .wrapContentWidth(align = Alignment.End, unbounded = true)
                .onSizeChanged { bubbleHeightPx = it.height }
                .graphicsLayer { alpha = detailAlpha }
                .clearAndSetSemantics { }
                .background(accentColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(visibleRange, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = thumbY)
                .width(48.dp)
                .height(thumbHeight)
                .onGloballyPositioned { geometry.thumbCoordinates = it }
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val page = indicator.viewportSize.toFloat()
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> scope.launch { state.scrollByWithPriority(-page / 5f) }
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> scope.launch { state.scrollByWithPriority(page / 5f) }
                        AndroidKeyEvent.KEYCODE_PAGE_UP -> scope.launch { state.scrollByWithPriority(-page) }
                        AndroidKeyEvent.KEYCODE_PAGE_DOWN -> scope.launch { state.scrollByWithPriority(page) }
                        AndroidKeyEvent.KEYCODE_MOVE_HOME -> scope.launch { state.scrollToFraction(0f) }
                        AndroidKeyEvent.KEYCODE_MOVE_END -> scope.launch { state.scrollToFraction(1f) }
                        else -> return@onKeyEvent false
                    }
                    true
                }
                .semantics {
                    contentDescription = scrollAppsLabel
                    stateDescription = visibleRange
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    setProgress { target ->
                        scope.launch { state.scrollToFraction(target) }
                        true
                    }
                }
                .focusable()
                .pointerInput(state, items.total) {
                    coroutineScope {
                        val gestureScope = this
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            dragging = true
                            val grabOffset = (geometry.pointerYInRoot(down.position) -
                                geometry.insetPx - geometry.thumbTopPx)
                                .coerceIn(0f, geometry.thumbHeightPx.toFloat())
                            val targets = Channel<Float>(Channel.CONFLATED)
                            gestureScope.launch {
                                state.scroll(MutatePriority.UserInput) {
                                    for (target in targets) {
                                        seekToFraction(state, target)
                                    }
                                }
                            }
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val top = (geometry.pointerYInRoot(change.position) - geometry.insetPx - grabOffset)
                                        .coerceIn(0f, geometry.travelPx.toFloat())
                                    val target = if (geometry.travelPx > 0) top / geometry.travelPx else 0f
                                    targets.trySend(target)
                                    change.consume()
                                }
                            } finally {
                                targets.close()
                                dragging = false
                            }
                        }
                    }
                }
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset {
                        IntOffset((-(19.dp - thumbWidth / 2)).roundToPx(), 0)
                    }
                    .width(thumbWidth)
                    .fillMaxHeight()
                    .background(thumbColor, CircleShape)
                    .then(
                        if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .graphicsLayer { alpha = detailAlpha }
                        .width(3.dp)
                        .height(22.dp)
                        .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f), CircleShape)
                )
            }
        }
    }
}

private fun scrollDelta(indicator: ScrollIndicatorState, fraction: Float): Float {
    val viewport = indicator.viewportSize.coerceAtLeast(0)
    val maxOffset = (indicator.contentSize - viewport).coerceAtLeast(0)
    val current = indicator.scrollOffset.coerceAtLeast(0)
    val target = fraction.coerceIn(0f, 1f)
    return when {
        target <= 0f -> -current.toFloat() - viewport
        target >= 1f -> maxOffset.toFloat() - current + viewport
        else -> target * maxOffset - current
    }
}

private fun ScrollScope.seekToFraction(state: ScrollableState, fraction: Float) {
    if (!fraction.isFinite()) return
    val target = fraction.coerceIn(0f, 1f)
    // Lazy lists estimate their full content size from currently measured rows. A single seek to
    // either endpoint can stop short when that estimate changes, so keep going until the real end.
    repeat(16) {
        if ((target <= 0f && !state.canScrollBackward) ||
            (target >= 1f && !state.canScrollForward)
        ) return
        val indicator = state.scrollIndicatorState ?: return
        if (scrollBy(scrollDelta(indicator, target)) == 0f) return
        if (target > 0f && target < 1f) return
    }
}

private suspend fun ScrollableState.scrollToFraction(fraction: Float) {
    scroll(MutatePriority.UserInput) {
        seekToFraction(this@scrollToFraction, fraction)
    }
}

private suspend fun ScrollableState.scrollByWithPriority(delta: Float) {
    scroll(MutatePriority.UserInput) { scrollBy(delta) }
}
