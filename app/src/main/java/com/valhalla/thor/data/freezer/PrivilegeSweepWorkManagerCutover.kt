// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

/** One-shot barrier that retires the legacy WorkManager sweep chain before service claims begin. */
@Single
internal class PrivilegeSweepWorkManagerCutover(
    private val fence: LegacyPrivilegeSweepExecutionFence,
    private val queueWorkManager: SweepQueueWorkManager,
    private val store: PrivilegeSweepStore,
    private val clock: PrivilegeSweepClock,
    private val gate: PrivilegeSweepProcessGate,
) {
    private val mutex = Mutex()
    private var outcome: Result<Unit>? = null

    suspend fun awaitCompleted() {
        mutex.withLock {
            outcome?.let { completed ->
                completed.getOrThrow()
                return
            }
            gate.serialized {
                fence.closeAdmission()
                val legacyRequests = store.observeRetained().first()
                    .filter { snapshot ->
                        snapshot.terminalState == null &&
                                snapshot.claimToken == null &&
                                !snapshot.executionId.isPrivilegeServiceExecutionId()
                    }
                queueWorkManager.cancelQueue()
                fence.awaitQuiescence()
                val nowMs = clock.nowMs()
                legacyRequests.forEach { legacy ->
                    val current = store.load(legacy.requestId) ?: return@forEach
                    if (current.terminalState != null || current.claimToken != null) return@forEach
                    val ambiguousOrdinals = current.targetSnapshots
                        .filter { target ->
                            target.state == PrivilegeSweepTargetState.PENDING ||
                                    target.state == PrivilegeSweepTargetState.LEGACY_UNKNOWN
                        }
                        .map { it.ordinal }
                    if (ambiguousOrdinals.isNotEmpty()) {
                        val converted = store.markLegacyTargetsUnknown(
                            requestId = current.requestId,
                            ambiguousOrdinals = ambiguousOrdinals,
                            serviceExecutionId = newPrivilegeServiceExecutionId(),
                            nowMs = nowMs,
                        )
                        if (!converted) {
                            val latest = store.load(current.requestId)
                            check(
                                latest == null ||
                                        latest.terminalState != null ||
                                        latest.executionId.isPrivilegeServiceExecutionId() ||
                                        (latest.serviceSessionToken != null && latest.claimToken != null)
                            ) { "Legacy sweep reconciliation lost ownership" }
                        }
                    }
                }
            }
            outcome = Result.success(Unit)
        }
    }
}
