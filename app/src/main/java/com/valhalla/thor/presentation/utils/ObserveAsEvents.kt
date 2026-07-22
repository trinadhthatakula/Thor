// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

/**
 * Collects one-off UI events from [flow] only while the composition is at least STARTED, so events
 * (toasts, snackbars, navigation, prompts) are delivered exactly once and are never re-triggered by
 * recomposition or replayed across a configuration change.
 *
 * Back a ViewModel's one-off events with a `Channel<T>(Channel.BUFFERED).receiveAsFlow()` (NOT a
 * `MutableSharedFlow(replay = 0)` — that silently DROPS any event emitted while no collector is
 * subscribed, e.g. before this collector reaches STARTED or during a rotation gap; `extraBufferCapacity`
 * only relieves back-pressure for already-subscribed collectors, it does not retain for a late one).
 * A buffered Channel buffers regardless of subscribers and delivers to the collector when it
 * (re)subscribes. Expose it as a read-only [Flow] and keep durable state in the single `StateFlow<UiState>`.
 *
 * @param key1 optional extra restart key if the [onEvent] handler closes over state that must stay
 *   current (e.g. a freshly created SnackbarHostState); usually unnecessary.
 */
@Composable
fun <T> ObserveAsEvents(flow: Flow<T>, key1: Any? = null, onEvent: (T) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // rememberUpdatedState: the LaunchedEffect keys on flow/lifecycleOwner (stable), so without this
    // a handler that closes over changing state (context, snackbar host, ...) would go stale.
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(flow, lifecycleOwner, key1) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { currentOnEvent(it) }
        }
    }
}
