// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.MainThread
import com.valhalla.superuser.ipc.RootService
import com.valhalla.thor.rootservice.IThorRootService
import com.valhalla.thor.rootservice.ThorRootService
import com.valhalla.thor.util.DefaultRootLifecycleDiagnostics
import com.valhalla.thor.util.RootBindingPhase
import com.valhalla.thor.util.RootLifecycleDiagnostics
import com.valhalla.thor.util.RootLifecycleEvent
import com.valhalla.thor.util.recordSafely
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Calls and callbacks belong to main; implementations must finish bookkeeping before callbacks. */
internal interface RootServiceBinding {
    @MainThread fun bind(connection: ServiceConnection)
    @MainThread fun unbind(connection: ServiceConnection)
}

internal class OdinRootServiceBinding(private val context: Context) : RootServiceBinding {
    override fun bind(connection: ServiceConnection) {
        RootService.bind(Intent(context, ThorRootService::class.java), callbackExecutor, connection)
    }

    override fun unbind(connection: ServiceConnection) = RootService.unbind(connection)

    private companion object {
        // Odin's default executor can call back inline while it is iterating its connection map.
        // Always post, so a callback may safely release its exact connection after that iteration.
        val callbackExecutor: Executor by lazy {
            val handler = Handler(Looper.getMainLooper())
            Executor { callback -> handler.post(callback) }
        }
    }
}

/** Owns one cached connection; cancelling a bind waiter never transfers it to a later attempt. */
internal class RootServiceConnectionOwner(
    private val binding: RootServiceBinding,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val bindTimeoutMillis: Long = 10_000L,
    private val diagnostics: RootLifecycleDiagnostics = DefaultRootLifecycleDiagnostics,
) {
    private val mutex = Mutex()
    // Read and written only on main, including cache hits and service callbacks.
    private var current: Attempt? = null

    suspend fun getService(): IThorRootService? = mutex.withLock {
        val result = try {
            withTimeoutOrNull(bindTimeoutMillis) {
                BindResult(suspendCancellableCoroutine { continuation ->
                    val attempt = Attempt(continuation)
                    continuation.invokeOnCancellation { attempt.retire() }
                    // Posting instead of withContext lets the waiter cancel even if main is stalled.
                    // Its cancellation flag also prevents a queued bind from starting afterwards.
                    mainDispatcher.dispatch(EmptyCoroutineContext) {
                        try {
                            if (attempt.abandoned.get() || !continuation.isActive) return@dispatch
                            current?.let { previous ->
                                val cached = previous.service
                                val alive = runCatching { cached?.asBinder()?.isBinderAlive == true }
                                    .getOrDefault(false)
                                if (!previous.abandoned.get() && alive) {
                                    record(RootBindingPhase.CACHE_HIT, cached = true)
                                    continuation.resume(cached)
                                    return@dispatch
                                }
                                previous.retireOnMain()
                            }
                            if (attempt.abandoned.get() || !continuation.isActive) return@dispatch
                            current = attempt
                            attempt.bindStarted = true
                            record(RootBindingPhase.STARTED)
                            binding.bind(attempt)
                        } catch (failure: Exception) {
                            record(RootBindingPhase.BIND_FAILED)
                            attempt.retireOnMain()
                            attempt.fail(failure)
                        }
                    }
                })
            }
        } catch (cancelled: CancellationException) {
            record(RootBindingPhase.WAITER_CANCELLED)
            throw cancelled
        }
        if (result == null) record(RootBindingPhase.WAIT_TIMED_OUT)
        result?.service
    }

    // A null service callback is a completed bind response, distinct from the wait's own timeout.
    private data class BindResult(val service: IThorRootService?)

    internal fun record(phase: RootBindingPhase, cached: Boolean = false) {
        diagnostics.recordSafely(RootLifecycleEvent.Binding(phase, cached))
    }

    private inner class Attempt(
        private val continuation: CancellableContinuation<IThorRootService?>,
    ) : ServiceConnection {
        val abandoned = AtomicBoolean(false)
        var service: IThorRootService? = null
        var bindStarted = false
        private var connected = false
        private var responseClaimed = false
        private var earlyUnbound = false
        private var connectedUnbound = false

        fun retire() {
            abandoned.set(true)
            // Independent of the cancelled caller, and always queued even when already on main.
            mainDispatcher.dispatch(EmptyCoroutineContext) { retireOnMain() }
        }

        fun retireOnMain() {
            abandoned.set(true)
            if (current === this) current = null
            service = null
            if (!bindStarted) return
            if (connected) {
                if (connectedUnbound) return
                connectedUnbound = true
            } else {
                if (earlyUnbound) return
                earlyUnbound = true
            }
            try {
                binding.unbind(this)
                record(RootBindingPhase.UNBOUND)
            } catch (_: Exception) {
                if (connected) connectedUnbound = false else earlyUnbound = false
                record(RootBindingPhase.UNBIND_FAILED)
            }
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (connected) return
            connected = true
            // Odin unbind does not remove a pending bind task. Even a successful early unbind
            // can be a no-op: release again now that Odin has registered this late connection.
            if (abandoned.get() || current !== this || !continuation.isActive) {
                record(RootBindingPhase.LATE_CONNECTED)
                retireOnMain()
                return
            }
            val connectedService = try {
                IThorRootService.Stub.asInterface(binder)
            } catch (failure: Exception) {
                record(RootBindingPhase.BIND_FAILED)
                retireOnMain()
                fail(failure)
                return
            }
            if (connectedService == null) {
                onNullBinding(name)
                return
            }
            service = connectedService
            responseClaimed = true
            record(RootBindingPhase.CONNECTED)
            continuation.resume(connectedService) { _, _, _ -> retire() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            record(RootBindingPhase.DISCONNECTED)
            // Odin already removed the registration. Never clear a replacement's cached binder.
            abandoned.set(true)
            connectedUnbound = connected
            if (current === this) current = null
            service = null
            completeEmpty()
        }

        override fun onNullBinding(name: ComponentName?) {
            record(RootBindingPhase.NULL_BINDING)
            retireOnMain()
            completeEmpty()
        }

        override fun onBindingDied(name: ComponentName?) {
            record(RootBindingPhase.BINDING_DIED)
            retireOnMain()
            completeEmpty()
        }

        private fun completeEmpty() {
            if (responseClaimed) return
            responseClaimed = true
            continuation.resume(null)
        }

        fun fail(failure: Exception) {
            if (responseClaimed) return
            responseClaimed = true
            continuation.resumeWithException(failure)
        }
    }
}
