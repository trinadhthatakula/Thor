// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.Context
import androidx.work.Operation
import androidx.work.WorkManager
import com.valhalla.thor.domain.model.THOR_SWEEP_CHAIN
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/** The narrow WorkManager surface needed to cancel the complete durable sweep queue. */
internal fun interface SweepQueueWorkManager {
    suspend fun cancelQueue()
}

@Single(binds = [SweepQueueWorkManager::class])
internal class WorkManagerSweepQueueWorkManager(
    private val context: Context,
) : SweepQueueWorkManager {
    override suspend fun cancelQueue() {
        WorkManager.getInstance(context)
            .cancelUniqueWork(THOR_SWEEP_CHAIN)
            .awaitCompletion()
    }
}

/** Terminalizes Room before cancelling the WorkManager chain under the shared process gate. */
@Single
internal class SweepQueueCanceller(
    private val store: PrivilegeSweepStore,
    private val clock: PrivilegeSweepClock,
    private val gate: PrivilegeSweepProcessGate,
    private val workManager: SweepQueueWorkManager,
) {
    suspend fun cancelQueue() {
        val callerJob = currentCoroutineContext()[Job]
        gate.serialized {
            // Once Room is terminal, no launch or reconciliation may observe the half-cancelled
            // queue until WorkManager's cancellation Operation has also settled.
            withContext(NonCancellable) {
                store.cancelAllNonterminal(clock.nowMs())
                workManager.cancelQueue()
            }
        }
        callerJob?.ensureActive()
    }
}

@Suppress("BlockingMethodInNonBlockingContext")
internal suspend fun Operation.awaitCompletion() {
    val future = result
    suspendCancellableCoroutine { continuation ->
        future.addListener(
            {
                try {
                    // The listener runs only after completion, so this get cannot block.
                    future.get()
                    continuation.resume(Unit)
                } catch (e: ExecutionException) {
                    continuation.resumeWithException(e.cause ?: e)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            },
            Runnable::run,
        )
    }
}
