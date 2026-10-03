// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain

import java.util.UUID
import com.valhalla.thor.domain.repository.RetainedOperationLease
import com.valhalla.thor.domain.repository.retainOperationLeases
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/** Caller waiting and committed-session ownership have separate lifetimes. */
internal class InstallSessionCompletion(
    val sessionId: Int,
    val token: String,
    internal val interactive: Boolean,
    private val retainedLease: RetainedOperationLease,
) {
    private val result = CompletableDeferred<InstallState>()
    // Guarded by InstallerEventBus.sessionLock together with membership in its session map.
    internal var callerAttached = true

    suspend fun await(): InstallState = result.await()

    internal fun complete(state: InstallState) {
        result.complete(state)
    }

    internal suspend fun releaseOwnership() = retainedLease.release()
}

/**
 * A Singleton Event Bus to bridge the gap between the Android System (BroadcastReceiver)
 * and our App Scope (ViewModel).
 * * Since BroadcastReceivers are instantiated by the OS, we cannot scope them to the ViewModel.
 * This Bus acts as the synapse.
 */
@Single
class InstallerEventBus {
    private val sessionLock = Any()
    private val sessions = mutableMapOf<String, InstallSessionCompletion>()

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

    /** Capture all current lease scopes before commit can outlive the caller. */
    internal suspend fun registerSession(
        sessionId: Int,
        interactive: Boolean = true,
    ): InstallSessionCompletion {
        require(sessionId >= 0) { "Invalid install session ID" }
        val retention = retainOperationLeases()
        return try {
            InstallSessionCompletion(sessionId, UUID.randomUUID().toString(), interactive, retention).also {
                synchronized(sessionLock) {
                    check(!sessions.containsKey(it.token)) { "Duplicate install attempt token" }
                    sessions[it.token] = it
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { retention.release() }
            throw failure
        }
    }

    /** Only an attempt that never entered commit may discard its registration. */
    internal suspend fun unregisterSession(completion: InstallSessionCompletion) {
        val removed = synchronized(sessionLock) { sessions.remove(completion.token, completion) }
        if (removed) withContext(NonCancellable) { completion.releaseOwnership() }
    }

    /** A cancelled or timed-out caller no longer owns foreground confirmation delivery. */
    internal fun detachSession(completion: InstallSessionCompletion) {
        synchronized(sessionLock) {
            if (sessions[completion.token] === completion) completion.callerAttached = false
        }
    }

    /** Pending confirmation can end a background wait, but cannot release accepted work. */
    internal suspend fun emitSessionPendingUserAction(
        sessionId: Int,
        token: String?,
        publishConfirmation: (() -> Unit)?,
    ) {
        synchronized(sessionLock) {
            val completion = token?.let(sessions::get)
                ?.takeIf { it.sessionId == sessionId } ?: return@synchronized
            if (!completion.interactive) {
                completion.callerAttached = false
                completion.complete(InstallState.UserConfirmationRequired)
            } else if (completion.callerAttached && publishConfirmation != null) {
                publishConfirmation()
                events.tryEmit(InstallState.UserConfirmationRequired)
            }
        }
    }

    /** Only a matching attached attempt publishes UI; detached attempts still release ownership. */
    internal suspend fun emitSessionResult(sessionId: Int, token: String?, state: InstallState) {
        if (state != InstallState.Success && state !is InstallState.Error) return
        val completion = synchronized(sessionLock) {
            token?.let(sessions::get)?.takeIf { it.sessionId == sessionId }
                ?.also {
                    if (it.callerAttached) events.tryEmit(state)
                    sessions.remove(it.token)
                }
        } ?: return
        withContext(NonCancellable) {
            completion.releaseOwnership()
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
