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
    val events: SharedFlow<InstallState>
        field = MutableSharedFlow<InstallState>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    suspend fun emit(state: InstallState) {
        events.emit(state)
    }

    /**
     * Synchronously resets the bus to [InstallState.Idle]. Never suspends thanks to the
     * extra buffer capacity + DROP_OLDEST overflow policy, so it is safe to call from
     * non-suspending contexts such as ViewModel.onCleared() where the coroutine scope is
     * already cancelled.
     */
    fun reset() {
        events.tryEmit(InstallState.Idle)
    }
}