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
 * Back a ViewModel's one-off events with a `MutableSharedFlow<T>(replay = 0)` (or a
 * `Channel(...).receiveAsFlow()`) exposed as a read-only [Flow]/`SharedFlow`, and collect them here
 * from the screen — keeping the screen's durable state in its single `StateFlow<UiState>`.
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
