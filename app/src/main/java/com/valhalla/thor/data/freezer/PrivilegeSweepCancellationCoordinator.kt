// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.data.service.ServiceStartResult
import com.valhalla.thor.domain.repository.PrivilegeSweepCancellationDecision
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import java.util.UUID
import org.koin.core.annotation.Single

internal interface PrivilegeSweepCancellationActions {
    suspend fun request(requestId: UUID): PrivilegeSweepCancellationDecision
    fun cancelActive(requestId: UUID): Boolean
    fun wake(requestId: UUID): ServiceStartResult
    suspend fun reconcileStaleClaim(requestId: UUID)
}

@Single
internal class RoomPrivilegeSweepCancellationActions(
    private val store: PrivilegeSweepStore,
    private val owners: PrivilegeSweepOwnerRegistry,
    private val wakeSignal: PrivilegeQueueWakeSignal,
    private val reconciler: PrivilegeSweepReconciler,
    private val verifier: PrivilegeSweepReinstallPostconditionVerifier,
    private val clock: PrivilegeSweepClock,
) : PrivilegeSweepCancellationActions {
    override suspend fun request(requestId: UUID): PrivilegeSweepCancellationDecision =
        store.requestCancellation(requestId, clock.nowMs())

    override fun cancelActive(requestId: UUID): Boolean = owners.cancelActive(requestId)

    override fun wake(requestId: UUID): ServiceStartResult = wakeSignal.wake(requestId)

    override suspend fun reconcileStaleClaim(requestId: UUID) {
        reconciler.reconcileInterruptedClaims(
            currentSessionToken = owners.sessionToken,
            localOwnerIsLive = owners::isLive,
            reinstallVerifier = verifier,
            requestId = requestId,
        )
    }
}

/** Persists one request's cancellation before interrupting its exact process-local owner. */
@Single
internal class PrivilegeSweepCancellationCoordinator(
    private val actions: PrivilegeSweepCancellationActions,
) {
    internal constructor(
        requestCancellation: suspend (UUID) -> PrivilegeSweepCancellationDecision,
        cancelActive: (UUID) -> Boolean,
        wake: (UUID) -> ServiceStartResult,
        reconcileStaleClaim: suspend () -> Unit,
    ) : this(
        object : PrivilegeSweepCancellationActions {
            override suspend fun request(requestId: UUID) = requestCancellation(requestId)
            override fun cancelActive(requestId: UUID) = cancelActive(requestId)
            override fun wake(requestId: UUID) = wake(requestId)
            override suspend fun reconcileStaleClaim(requestId: UUID) = reconcileStaleClaim()
        }
    )

    suspend fun cancel(requestId: UUID): PrivilegeSweepCancellationDecision {
        val decision = actions.request(requestId)
        actions.wake(requestId)
        val locallyOwned = when (decision) {
            is PrivilegeSweepCancellationDecision.Settled,
            is PrivilegeSweepCancellationDecision.InterruptActive,
                -> actions.cancelActive(requestId)

            PrivilegeSweepCancellationDecision.NotFound,
            is PrivilegeSweepCancellationDecision.AlreadyTerminal,
                -> false
        }
        if (decision is PrivilegeSweepCancellationDecision.InterruptActive && !locallyOwned) {
            actions.reconcileStaleClaim(requestId)
        }
        return decision
    }
}
