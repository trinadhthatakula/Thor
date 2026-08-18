// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings.customization

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * State holder for smooth, jitter-free drag-and-drop reordering in LazyColumn.
 */
class ReorderableLazyListState(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (fromKey: Any, toKey: Any) -> Unit,
    private val onDragCompleted: () -> Unit
) {
    var draggingItemKey by mutableStateOf<Any?>(null)
        private set

    var draggingItemOffset by mutableFloatStateOf(0f)
        private set

    private val scrollChannel = Channel<Float>(Channel.CONFLATED)

    init {
        scope.launch {
            while (true) {
                val scrollAmount = scrollChannel.receive()
                listState.scrollBy(scrollAmount)
            }
        }
    }

    fun onDragStart(key: Any) {
        draggingItemKey = key
        draggingItemOffset = 0f
    }

    fun onDrag(dragAmount: Float) {
        val key = draggingItemKey ?: return
        draggingItemOffset += dragAmount

        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val currentItem = visibleItems.firstOrNull { it.key == key } ?: return
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
        }

        if (targetItem != null) {
            val delta = (targetItem.offset - currentItem.offset).toFloat()
            draggingItemOffset -= delta
            onMove(key, targetItem.key)
        }

        // Auto-scroll near edges
        val viewportStart = listState.layoutInfo.viewportStartOffset
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        val itemTop = currentItem.offset + draggingItemOffset
        val itemBottom = itemTop + currentItem.size

        val scrollStep = 24f
        if (itemTop < viewportStart + 120f) {
            scrollChannel.trySend(-scrollStep)
        } else if (itemBottom > viewportEnd - 120f) {
            scrollChannel.trySend(scrollStep)
        }
    }

    fun onDragEnd() {
        if (draggingItemKey != null) {
            draggingItemKey = null
            draggingItemOffset = 0f
            onDragCompleted()
        }
    }

    fun onDragCancel() {
        if (draggingItemKey != null) {
            draggingItemKey = null
            draggingItemOffset = 0f
            onDragCompleted()
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
    return remember(listState) {
        ReorderableLazyListState(
            listState = listState,
            scope = scope,
            onMove = onMove,
            onDragCompleted = onDragCompleted
        )
    }
}

fun Modifier.dragHandle(
    key: Any,
    state: ReorderableLazyListState
): Modifier = pointerInput(key, state) {
    detectDragGestures(
        onDragStart = { state.onDragStart(key) },
        onDragEnd = { state.onDragEnd() },
        onDragCancel = { state.onDragCancel() },
        onDrag = { change, dragAmount ->
            change.consume()
            state.onDrag(dragAmount.y)
        }
    )
}
