// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.koin.core.annotation.Single

/** One platform session attempt; cancelling a waiter does not settle the install. */
internal class InstallSessionCompletion(val sessionId: Int, val token: String) {
    private val terminal = CompletableDeferred<InstallState>()

    suspend fun await(): InstallState = terminal.await()

    internal fun complete(state: InstallState) {
        terminal.complete(state)
    }
}

/**
 * A Singleton Event Bus to bridge the gap between the Android System (BroadcastReceiver)
 * and our App Scope (ViewModel).
 * * Since BroadcastReceivers are instantiated by the OS, we cannot scope them to the ViewModel.
 * This Bus acts as the synapse.
 */
@Single
class InstallerEventBus {
    private val sessions = ConcurrentHashMap<String, InstallSessionCompletion>()

    val events: SharedFlow<InstallState>
        field = MutableSharedFlow<InstallState>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    /**
     * The most recently emitted state, or null before the first emission.
     *
     * Never suspends and never collects — `replay = 1` means the buffer holds exactly the current
     * state. For a poll that needs to know whether the install it is waiting on has already failed,
     * collecting would mean racing a collector against the thing being polled; reading the replay
     * cache asks the same question once, cheaply.
     */
    val latest: InstallState? get() = events.replayCache.lastOrNull()

    suspend fun emit(state: InstallState) {
        events.emit(state)
    }

    /** Register before commit so an immediate receiver result cannot be lost. */
    internal fun registerSession(sessionId: Int): InstallSessionCompletion {
        require(sessionId >= 0) { "Invalid install session ID" }
        return InstallSessionCompletion(sessionId, UUID.randomUUID().toString()).also {
            check(sessions.putIfAbsent(it.token, it) == null) { "Duplicate install attempt token" }
        }
    }

    internal fun unregisterSession(completion: InstallSessionCompletion) {
        sessions.remove(completion.token, completion)
    }

    /** UI events stay shared; only the exact session attempt may settle its ownership. */
    internal suspend fun emitSessionResult(sessionId: Int, token: String?, state: InstallState) {
        emit(state)
        if (state != InstallState.Success && state !is InstallState.Error) return
        val completion = token?.let(sessions::get) ?: return
        if (completion.sessionId == sessionId && sessions.remove(token, completion)) {
            completion.complete(state)
        }
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
