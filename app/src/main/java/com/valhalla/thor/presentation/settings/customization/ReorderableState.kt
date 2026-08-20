// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings.customization

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How close to a viewport edge the dragged card has to get before the list starts scrolling. */
private val EdgeZone = 96.dp

/** Auto-scroll speed at the very edge of the viewport, tapering to zero at the far side of [EdgeZone]. */
private val MaxAutoScrollPerSecond = 600.dp

/**
 * How far the finger has to travel back the other way before the drag counts as having reversed.
 *
 * Pointer noise on a held finger arrives as alternating sub-pixel deltas; without a slop the
 * auto-scroller would flip direction on it several times a second.
 */
private val DirectionFlipSlop = 8.dp

/**
 * Ceiling on the frame delta fed to the auto-scroller. Without it, a dropped frame or a moment
 * spent off the frame clock turns into one large jump the next time the loop runs.
 */
private const val MAX_FRAME_SECONDS = 0.064f

/**
 * State holder for drag-and-drop reordering in a [androidx.compose.foundation.lazy.LazyColumn].
 *
 * The dragged row is moved with a `graphicsLayer` translation ([draggingItemOffset]) rather than by
 * relayout: the list keeps its normal item order and the caller reorders the backing list as the
 * card passes its neighbours. Two consequences fall out of that and drive most of this class:
 *
 * - The offset is measured from the row's *laid-out* slot, so anything that moves the slot has to be
 *   absorbed into the offset or the card drifts away from the finger. Both the auto-scroller and the
 *   reorder swap do that compensation explicitly.
 * - Nothing here runs unless something calls in. Pointer events stop arriving the moment the finger
 *   stops moving, which is precisely when a drag held against the edge of the screen most needs to
 *   scroll — so auto-scroll runs off the frame clock for the life of the gesture instead of being
 *   driven one step per pointer event.
 */
class ReorderableLazyListState(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    density: Density,
    private val haptics: HapticFeedback?,
    private val onMove: (fromKey: Any, toKey: Any) -> Unit,
    private val onDragCompleted: () -> Unit
) {
    var draggingItemKey by mutableStateOf<Any?>(null)
        private set

    var draggingItemOffset by mutableFloatStateOf(0f)
        private set

    private val edgeZonePx = with(density) { EdgeZone.toPx() }
    private val maxAutoScrollPxPerSecond = with(density) { MaxAutoScrollPerSecond.toPx() }
    private val directionFlipSlopPx = with(density) { DirectionFlipSlop.toPx() }

    /** Signed px/second. Recomputed whenever the card moves; zero while it is clear of both edges. */
    private var autoScrollVelocity = 0f
    private var autoScrollJob: Job? = null

    /** Which way the finger is currently travelling: +1 down, -1 up, 0 before the first move. */
    private var dragDirection = 0

    /** Distance travelled against [dragDirection] since the last move that agreed with it. */
    private var reversalDistance = 0f

    /**
     * Whether the gesture actually reordered anything. A press that crosses touch slop and settles
     * back where it started should not spend a DataStore write, nor bounce the whole list through
     * the caller's re-sync.
     */
    private var movedDuringGesture = false

    /**
     * Begins a drag on [key], unless one is already running.
     *
     * Every handle installs its own `pointerInput`, so two fingers on two handles are two
     * independent gesture detectors that both call in here. Letting the second one through reset the
     * key and the offset mid-gesture: the first finger's deltas then moved the *second* row, and
     * whichever finger lifted first cleared the whole state — so the other one went dead, and
     * [onDragCompleted] fired while a drag was still in progress or, if [movedDuringGesture] was
     * lost with the reset, never fired at all and the reorder was not persisted.
     */
    fun onDragStart(key: Any) {
        if (draggingItemKey != null) return
        draggingItemKey = key
        draggingItemOffset = 0f
        autoScrollVelocity = 0f
        dragDirection = 0
        reversalDistance = 0f
        movedDuringGesture = false
        haptics?.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        autoScrollJob?.cancel()
        autoScrollJob = scope.launch { runAutoScroll() }
    }

    fun onDrag(dragAmount: Float) {
        if (draggingItemKey == null) return
        trackDragDirection(dragAmount)
        draggingItemOffset += dragAmount
        // Nothing else this pass if a swap happened: `settleDropTarget` has already taken the slot
        // gap off the translation, but `listState.layoutInfo` still describes the order from before
        // it, and the velocity is computed from the two together. Mixing a pre-swap slot offset with
        // a post-swap translation puts the card a whole row away from where it is rendered — enough,
        // against a 96 dp edge zone, to start or stop the auto-scroller for a frame on nothing. The
        // last velocity holds until the next pointer event or frame reads a layout that agrees with
        // itself.
        if (settleDropTarget()) return
        updateAutoScrollVelocity()
    }

    fun onDragEnd(key: Any) = finishDrag(key)

    fun onDragCancel(key: Any) = finishDrag(key)

    /**
     * Ends the drag, if [key] is the one holding it — a gesture that [onDragStart] turned away must
     * not be able to end the gesture that won.
     */
    private fun finishDrag(key: Any) {
        if (draggingItemKey != key) return
        autoScrollJob?.cancel()
        autoScrollJob = null
        autoScrollVelocity = 0f
        draggingItemKey = null
        draggingItemOffset = 0f
        haptics?.performHapticFeedback(HapticFeedbackType.GestureEnd)
        if (movedDuringGesture) {
            movedDuringGesture = false
            onDragCompleted()
        }
    }

    /**
     * Scrolls the list while the dragged card sits in an edge zone, for as long as it sits there —
     * a finger held still against the bottom of the screen keeps scrolling instead of stalling.
     *
     * Driven by the frame clock and scaled by the real frame delta so the speed is the same on a
     * 60Hz and a 120Hz panel.
     */
    private suspend fun runAutoScroll() {
        var previousFrame = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, MAX_FRAME_SECONDS)
            previousFrame = frame

            val velocity = autoScrollVelocity
            if (velocity == 0f) continue

            val consumed = listState.scrollBy(velocity * seconds)
            // scrollBy moves the content, not the finger. Every visible item's offset shifts by
            // -consumed, so without adding it back here the card slides out from under the touch
            // point at exactly the speed the list is scrolling.
            if (consumed == 0f) continue // an end of the list; nothing moved, nothing to absorb
            draggingItemOffset += consumed
            // Same reason as in `onDrag`: a swap makes this frame's layout disagree with this
            // frame's translation, and the next frame is one vsync away.
            if (settleDropTarget()) continue
            updateAutoScrollVelocity()
        }
    }

    /**
     * Swaps the dragged row past the nearest neighbour whose midpoint it has crossed.
     *
     * One step per call rather than jumping straight to the furthest neighbour crossed: rows here
     * are not a uniform height (a two-line description makes one taller than its neighbour), and
     * the offset correction below — the gap between the two slots — is only exact for a single
     * adjacent swap. Repeated calls converge on the same place without accumulating that error.
     *
     * Returns true when it swapped, which tells the caller that [LazyListState.layoutInfo] is now a
     * frame behind the translation and must not be read again until it catches up.
     */
    private fun settleDropTarget(): Boolean {
        val key = draggingItemKey ?: return false
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val currentItem = visibleItems.firstOrNull { it.key == key } ?: return false
        val currentCenter = currentItem.offset + (currentItem.size / 2f) + draggingItemOffset

        val targetItem = if (draggingItemOffset > 0) {
            visibleItems
                .filter { it.key != key && it.index > currentItem.index }
                .firstOrNull { other ->
                    val otherCenter = other.offset + (other.size / 2f)
                    currentCenter > otherCenter
                }
        } else {
            visibleItems
                .filter { it.key != key && it.index < currentItem.index }
                .lastOrNull { other ->
                    val otherCenter = other.offset + (other.size / 2f)
                    currentCenter < otherCenter
                }
        } ?: return false

        // The row is about to be laid out in the target's slot, so the same amount comes off the
        // translation to leave it rendered where the finger currently holds it.
        draggingItemOffset -= (targetItem.offset - currentItem.offset).toFloat()
        movedDuringGesture = true
        haptics?.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        onMove(key, targetItem.key)
        return true
    }

    /**
     * Keeps [dragDirection] pointing the way the finger is travelling, ignoring reversals smaller
     * than [DirectionFlipSlop]. A move that agrees with the current direction clears the counter, so
     * the alternating sub-pixel deltas a still finger produces never accumulate into a flip.
     */
    private fun trackDragDirection(dragAmount: Float) {
        val moveDirection = when {
            dragAmount > 0f -> 1
            dragAmount < 0f -> -1
            else -> return
        }
        if (moveDirection == dragDirection) {
            reversalDistance = 0f
            return
        }
        reversalDistance += abs(dragAmount)
        if (dragDirection == 0 || reversalDistance >= directionFlipSlopPx) {
            dragDirection = moveDirection
            reversalDistance = 0f
        }
    }

    /**
     * Recomputes the auto-scroll speed from where the dragged card is *rendered* — its slot plus the
     * live translation — rather than from where it was laid out, which is what the user sees and
     * therefore what they are aiming at when they push toward an edge.
     */
    private fun updateAutoScrollVelocity() {
        if (draggingItemKey == null) {
            autoScrollVelocity = 0f
            return
        }
        val layoutInfo = listState.layoutInfo
        // No entry means the card has been dragged clear off the viewport, which only happens with
        // the finger already past the edge: hold the last velocity rather than stalling there.
        val currentItem = layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == draggingItemKey } ?: return

        val zone = edgeZonePx
            .coerceAtMost((layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 3f)
        if (zone <= 0f) {
            autoScrollVelocity = 0f
            return
        }

        val renderedTop = currentItem.offset + draggingItemOffset
        val renderedBottom = renderedTop + currentItem.size
        val pastBottom = renderedBottom - (layoutInfo.viewportEndOffset - zone)
        val pastTop = (layoutInfo.viewportStartOffset + zone) - renderedTop

        // Gated on the travel direction, not on the zone alone: a row that already sits in the
        // bottom edge zone when it is grabbed would otherwise scroll the list *down* while the user
        // drags it up, and with the scroller running off the frame clock that fight is continuous
        // rather than one step per pointer event.
        autoScrollVelocity = when {
            pastBottom > 0f && dragDirection > 0 ->
                (pastBottom / zone).coerceAtMost(1f) * maxAutoScrollPxPerSecond

            pastTop > 0f && dragDirection < 0 ->
                -(pastTop / zone).coerceAtMost(1f) * maxAutoScrollPxPerSecond

            else -> 0f
        }
    }
}

@Composable
fun rememberReorderableLazyListState(
    listState: LazyListState,
    onMove: (fromKey: Any, toKey: Any) -> Unit,
    onDragCompleted: () -> Unit
): ReorderableLazyListState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    // The state holder outlives any single composition, so it must not close over the callbacks it
    // was created with — those are re-created every recomposition and the originals go stale the
    // moment a caller's lambda captures something that changes.
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDragCompleted by rememberUpdatedState(onDragCompleted)

    return remember(listState, scope, density, haptics) {
        ReorderableLazyListState(
            listState = listState,
            scope = scope,
            density = density,
            haptics = haptics,
            onMove = { fromKey, toKey -> currentOnMove(fromKey, toKey) },
            onDragCompleted = { currentOnDragCompleted() }
        )
    }
}

/**
 * Marks a composable as the grab point for reordering [key].
 *
 * Vertical-only detection: the list scrolls on one axis and the drag follows one axis, so claiming
 * a horizontal swipe would consume a gesture this handle cannot act on.
 */
fun Modifier.dragHandle(
    key: Any,
    state: ReorderableLazyListState
): Modifier = pointerInput(key, state) {
    detectVerticalDragGestures(
        onDragStart = { state.onDragStart(key) },
        onDragEnd = { state.onDragEnd(key) },
        onDragCancel = { state.onDragCancel(key) },
        onVerticalDrag = { change, dragAmount ->
            change.consume()
            state.onDrag(dragAmount)
        }
    )
}
