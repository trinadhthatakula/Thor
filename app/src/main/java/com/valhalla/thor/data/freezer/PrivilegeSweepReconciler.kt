// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

internal enum class SweepWorkState {
    BLOCKED,
    ENQUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

/** The narrow WorkManager surface needed by launch, observation, and reconciliation. */
internal interface PrivilegeSweepWorkManager {
    suspend fun enqueue(work: androidx.work.OneTimeWorkRequest): Boolean
    fun observeState(workId: UUID): Flow<SweepWorkState?>
    suspend fun currentState(workId: UUID): SweepWorkState?
}

/** One process-wide gate shared by every sweep launch and reconciliation entry point. */
@Single
internal class PrivilegeSweepProcessGate {
    private val mutex = Mutex()

    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }
}

internal fun interface PrivilegeSweepClock {
    fun nowMs(): Long
}

@Single(binds = [PrivilegeSweepClock::class])
internal class WallPrivilegeSweepClock : PrivilegeSweepClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** Repairs Room snapshots from WorkManager truth, then prunes terminal snapshots past retention. */
@Single
internal class PrivilegeSweepReconciler(
    private val store: PrivilegeSweepStore,
    private val workManager: PrivilegeSweepWorkManager,
    private val clock: PrivilegeSweepClock,
    private val gate: PrivilegeSweepProcessGate,
) {
    suspend fun reconcile() {
        gate.serialized { reconcileInsideGate() }
    }

    /** Called only by a launch that already owns [gate]. */
    internal suspend fun reconcileInsideGate() {
        val nowMs = clock.nowMs()
        store.observeRetained().first().forEach { snapshot ->
            if (snapshot.terminalState != null) return@forEach
            when (workManager.currentState(snapshot.workId)) {
                SweepWorkState.BLOCKED,
                SweepWorkState.ENQUEUED,
                SweepWorkState.RUNNING,
                -> Unit

                SweepWorkState.CANCELLED ->
                    store.finish(snapshot.requestId, StoredSweepTerminal.CANCELLED, nowMs)

                SweepWorkState.SUCCEEDED,
                SweepWorkState.FAILED,
                null,
                -> store.finish(snapshot.requestId, StoredSweepTerminal.FAILED, nowMs)
            }
        }
        // Repair first so a row that became terminal above receives its full retention window.
        store.deleteExpired(nowMs)
    }
}
