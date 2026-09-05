// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import org.koin.core.annotation.Single

internal sealed interface DataTaskUncertainClaimRelease {
    val claimToken: String

    data class Provisional(override val claimToken: String) : DataTaskUncertainClaimRelease

    data class Active(val claim: DataSyncClaim) : DataTaskUncertainClaimRelease {
        override val claimToken: String = claim.claimToken
    }
}

/** Process-wide authority for claims still owned by this process, across service generations. */
@Single
class DataTaskOwnerRegistry {
    private data class Owner(
        var taskId: UUID?,
        var child: Job?,
        var cancellationRequested: Boolean = false,
    )

    private val lock = Any()
    private val owners = mutableMapOf<String, Owner>()
    private val provisionalCancellations = mutableSetOf<UUID>()
    private val staleSettlementReservations = mutableSetOf<UUID>()
    private val uncertainClaimReleases = mutableMapOf<String, DataTaskUncertainClaimRelease>()
    private var laneOwnerToken: String? = null
    private var laneCompletion: CompletableDeferred<Unit>? = null

    /** Suspends without polling until this generation owns the process-wide serial data lane. */
    suspend fun acquireLane(generationToken: String) {
        require(generationToken.isNotBlank()) { "generationToken must not be blank" }
        while (true) {
            val waitForOwner = synchronized(lock) {
                when (laneOwnerToken) {
                    null -> {
                        laneOwnerToken = generationToken
                        laneCompletion = CompletableDeferred()
                        null
                    }

                    generationToken -> null
                    else -> requireNotNull(laneCompletion)
                }
            }
            if (waitForOwner == null) return
            waitForOwner.await()
        }
    }

    fun releaseLane(generationToken: String) {
        val completed = synchronized(lock) {
            if (laneOwnerToken != generationToken) return
            laneOwnerToken = null
            laneCompletion.also { laneCompletion = null }
        }
        completed?.complete(Unit)
    }

    fun registerProvisional(claimToken: String) {
        synchronized(lock) {
            check(claimToken !in owners) { "claim token already registered" }
            owners[claimToken] = Owner(taskId = null, child = null)
        }
    }

    fun bindTask(taskId: UUID, claimToken: String): Boolean = synchronized(lock) {
        val owner = owners[claimToken] ?: return@synchronized false
        if (owner.taskId != null && owner.taskId != taskId) return@synchronized false
        owner.taskId = taskId
        if (
            provisionalCancellations.remove(taskId) ||
            taskId in staleSettlementReservations
        ) {
            owner.cancellationRequested = true
        }
        true
    }

    fun attachChild(taskId: UUID, claimToken: String, child: Job): Boolean {
        val cancelOnAttach = synchronized(lock) {
            val owner = owners[claimToken] ?: return false
            if (owner.taskId != taskId) return false
            owner.child = child
            owner.cancellationRequested
        }
        if (cancelOnAttach) child.cancel()
        return true
    }

    /** A provisional token is live for any task during the claim-CAS-to-bind window. */
    fun isLive(taskId: UUID, claimToken: String): Boolean = synchronized(lock) {
        owners[claimToken]?.let { owner -> owner.taskId == null || owner.taskId == taskId } == true
    }

    /**
     * Cancels a bound owner and returns whether that exact task has a proven local owner.
     *
     * A provisional claim cannot prove which task it owns. Cancellation is retained for a later
     * matching bind, but callers may still reconcile a stale durable claim.
     */
    fun cancelActive(taskId: UUID): Boolean {
        var foundBoundOwner = false
        val child = synchronized(lock) {
            val owner = owners.values.firstOrNull { it.taskId == taskId }
            if (owner == null) {
                if (owners.values.any { it.taskId == null }) provisionalCancellations += taskId
                return@synchronized null
            }
            foundBoundOwner = true
            owner.cancellationRequested = true
            owner.child
        }
        child?.cancel()
        return foundBoundOwner
    }

    /**
     * Atomically fences an exact stale-claim settlement against task binding.
     *
     * A task that binds while the reservation is held inherits cancellation. A task already bound
     * is cancelled instead and prevents the stale Room transition from running.
     */
    fun reserveStaleSettlement(taskId: UUID): Boolean {
        var child: Job? = null
        val reserved = synchronized(lock) {
            val owner = owners.values.firstOrNull { it.taskId == taskId }
            if (owner != null) {
                owner.cancellationRequested = true
                child = owner.child
                false
            } else {
                staleSettlementReservations.add(taskId)
            }
        }
        child?.cancel()
        return reserved
    }

    fun finishStaleSettlement(taskId: UUID, settled: Boolean) {
        synchronized(lock) {
            staleSettlementReservations.remove(taskId)
            if (settled) {
                provisionalCancellations.remove(taskId)
            } else {
                // A provisional claim may have registered while Room was unavailable and bind after
                // this reservation ends. Retain cancellation for that exact task.
                provisionalCancellations += taskId
            }
        }
    }

    fun cancelActiveOwnedBy(claimToken: String): Boolean {
        val child = synchronized(lock) {
            val owner = owners[claimToken] ?: return false
            owner.cancellationRequested = true
            owner.child
        }
        child?.cancel()
        return true
    }

    internal fun retainUncertainClaimRelease(release: DataTaskUncertainClaimRelease) {
        synchronized(lock) {
            uncertainClaimReleases[release.claimToken] = release
        }
    }

    internal fun pendingUncertainClaimReleases(): List<DataTaskUncertainClaimRelease> =
        synchronized(lock) { uncertainClaimReleases.values.toList() }

    internal fun clearUncertainClaimRelease(claimToken: String) {
        synchronized(lock) {
            uncertainClaimReleases.remove(claimToken)
        }
    }

    internal fun hasUncertainClaimRelease(claimToken: String): Boolean = synchronized(lock) {
        claimToken in uncertainClaimReleases
    }

    fun unregister(taskId: UUID, claimToken: String) {
        synchronized(lock) {
            if (owners[claimToken]?.taskId == taskId) owners.remove(claimToken)
        }
    }

    fun unregisterProvisional(claimToken: String) {
        synchronized(lock) {
            if (owners[claimToken]?.taskId == null) owners.remove(claimToken)
        }
    }
}
