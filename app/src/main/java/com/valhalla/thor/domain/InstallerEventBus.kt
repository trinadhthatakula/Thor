package com.valhalla.thor.domain

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.koin.core.annotation.Single

/**
 * A Singleton Event Bus to bridge the gap between the Android System (BroadcastReceiver)
 * and our App Scope (ViewModel).
 * * Since BroadcastReceivers are instantiated by the OS, we cannot scope them to the ViewModel.
 * This Bus acts as the synapse.
 */
@Single
class InstallerEventBus {
    private val _events = MutableSharedFlow<InstallState>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<InstallState> = _events

    suspend fun emit(state: InstallState) {
        _events.emit(state)
    }

    /**
     * Synchronously resets the bus to [InstallState.Idle]. Never suspends thanks to the
     * extra buffer capacity + DROP_OLDEST overflow policy, so it is safe to call from
     * non-suspending contexts such as ViewModel.onCleared() where the coroutine scope is
     * already cancelled.
     */
    fun reset() {
        _events.tryEmit(InstallState.Idle)
    }
}