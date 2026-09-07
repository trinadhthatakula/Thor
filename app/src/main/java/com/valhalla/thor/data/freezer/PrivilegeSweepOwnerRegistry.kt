// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import org.koin.core.annotation.Single

/** Process-wide authority for the single privilege queue owner across service generations. */
@Single
internal class PrivilegeSweepOwnerRegistry {
    /** One truthful process session, shared by execution and cancellation recovery. */
    val sessionToken: String = UUID.randomUUID().toString()

    private data class Owner(
        val claimToken: String,
        var requestId: UUID? = null,
        var child: Job? = null,
        var cancellationRequested: Boolean = false,
    )

    private val lock = Any()
    private var owner: Owner? = null
    private val provisionalCancellations = mutableSetOf<UUID>()
    private var laneOwnerToken: String? = null
    private var laneCompletion: CompletableDeferred<Unit>? = null

    suspend fun acquireLane(generationToken: String) {
        require(generationToken.isNotBlank()) { "generationToken must not be blank" }
        while (true) {
            val wait = synchronized(lock) {
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
            if (wait == null) return
            wait.await()
        }
    }

    fun releaseLane(generationToken: String) {
        val completion = synchronized(lock) {
            if (laneOwnerToken != generationToken) return
            laneOwnerToken = null
            laneCompletion.also { laneCompletion = null }
        }
        completion?.complete(Unit)
    }

    fun registerProvisional(claimToken: String): Boolean = synchronized(lock) {
        require(claimToken.isNotBlank()) { "claimToken must not be blank" }
        if (owner != null) return@synchronized false
        owner = Owner(claimToken)
        true
    }

    fun bindRequest(requestId: UUID, claimToken: String): Boolean = synchronized(lock) {
        val current = owner ?: return@synchronized false
        if (current.claimToken != claimToken) return@synchronized false
        if (current.requestId != null && current.requestId != requestId) return@synchronized false
        current.requestId = requestId
        if (provisionalCancellations.remove(requestId)) current.cancellationRequested = true
        true
    }

    fun attachChild(requestId: UUID, claimToken: String, child: Job): Boolean {
        val cancelOnAttach = synchronized(lock) {
            val current = owner ?: return false
            if (current.claimToken != claimToken || current.requestId != requestId) return false
            current.child = child
            current.cancellationRequested
        }
        if (cancelOnAttach) child.cancel()
        return true
    }

    fun isLive(requestId: UUID, claimToken: String): Boolean = synchronized(lock) {
        owner?.let { current ->
            current.claimToken == claimToken &&
                    (current.requestId == null || current.requestId == requestId)
        } == true
    }

    fun cancelActive(requestId: UUID): Boolean {
        var provenOwner = false
        val child = synchronized(lock) {
            val current = owner
            if (current == null || current.requestId == null) {
                if (current != null) provisionalCancellations += requestId
                return@synchronized null
            }
            if (current.requestId != requestId) return@synchronized null
            provenOwner = true
            current.cancellationRequested = true
            current.child
        }
        child?.cancel()
        return provenOwner
    }

    fun unregister(requestId: UUID, claimToken: String) {
        synchronized(lock) {
            val current = owner
            if (current?.claimToken == claimToken && current.requestId == requestId) owner = null
        }
    }

    fun unregisterProvisional(claimToken: String) {
        synchronized(lock) {
            val current = owner
            if (current?.claimToken == claimToken && current.requestId == null) owner = null
        }
    }

    internal fun ownedRequestForTest(): UUID? = synchronized(lock) { owner?.requestId }
}
