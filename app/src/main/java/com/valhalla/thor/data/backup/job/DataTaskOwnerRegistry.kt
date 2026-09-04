// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import java.util.UUID
import kotlinx.coroutines.Job
import org.koin.core.annotation.Single

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
        if (provisionalCancellations.remove(taskId)) owner.cancellationRequested = true
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

    fun cancelActive(taskId: UUID): Boolean {
        val child = synchronized(lock) {
            val owner = owners.values.firstOrNull { it.taskId == taskId }
            if (owner == null) {
                if (owners.values.none { it.taskId == null }) return false
                provisionalCancellations += taskId
                return@synchronized null
            }
            owner.cancellationRequested = true
            owner.child
        }
        child?.cancel()
        return true
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
