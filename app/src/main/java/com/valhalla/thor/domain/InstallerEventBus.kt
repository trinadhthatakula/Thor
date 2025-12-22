package com.valhalla.thor.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A Singleton Event Bus to bridge the gap between the Android System (BroadcastReceiver)
 * and our App Scope (ViewModel).
 * * Since BroadcastReceivers are instantiated by the OS, we cannot scope them to the ViewModel.
 * This Bus acts as the synapse.
 */
class InstallerEventBus {
    private val _events = MutableSharedFlow<InstallState>(replay = 1)
    val events = _events.asSharedFlow()

    suspend fun emit(state: InstallState) {
        _events.emit(state)
    }
}