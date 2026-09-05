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
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.core.annotation.Single

/** Narrow WorkManager surface retained only for the one-shot legacy-chain cutover. */
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

/** Deprecated queue-shaped adapter; without a displayed request identity it deliberately does nothing. */
@Single
internal class SweepQueueCanceller(
    @Suppress("UNUSED_PARAMETER") store: PrivilegeSweepStore,
    private val cancellation: PrivilegeSweepCancellationCoordinator,
) {
    @Deprecated("A displayed request ID is required")
    suspend fun cancelQueue() = Unit

    suspend fun cancel(requestId: java.util.UUID) {
        cancellation.cancel(requestId)
    }
}

@Suppress("BlockingMethodInNonBlockingContext")
internal suspend fun Operation.awaitCompletion() {
    val future = result
    suspendCancellableCoroutine { continuation ->
        future.addListener(
            {
                try {
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
