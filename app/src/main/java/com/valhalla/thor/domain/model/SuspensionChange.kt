// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Successful removal of Thor's restriction does not mean every other restriction is gone. */
data class SuspensionChange(val remainingOwners: Set<String> = emptySet())

data class SuspensionOwner(val packageName: String, val userId: Int)

/** Null snapshots mean unreadable, never an empty owner list. */
data class OwnedSuspensionSnapshot(
    val owners: Set<SuspensionOwner>,
    val thorOwners: Set<SuspensionOwner>,
) {
    init { require(owners.containsAll(thorOwners)) }
}

enum class SuspensionFailureReason { OWNERSHIP_UNKNOWN, CANNOT_KEEP_INDEPENDENT, WRONG_PRIVILEGE, NOT_VERIFIED }

class SuspensionPolicyException(val reason: SuspensionFailureReason) : Exception(reason.name)

/**
 * Acts on one verified Thor entry, leaving all other owners alone. The transport must mark shared
 * identities and verify the marker when building thorOwners; a shell package name is not proof.
 * One mutation attempt per owner: an uncertain write is never replayed through another transport.
 */
suspend fun changeOwnedSuspension(
    suspended: Boolean,
    sdkInt: Int,
    actor: SuspensionOwner,
    read: suspend () -> OwnedSuspensionSnapshot?,
    write: suspend (SuspensionOwner, Boolean) -> Unit,
    canWrite: (SuspensionOwner) -> Boolean,
): SuspensionChange {
    fun unknown(): Nothing = throw SuspensionPolicyException(SuspensionFailureReason.OWNERSHIP_UNKNOWN)
    fun unverified(): Nothing = throw SuspensionPolicyException(SuspensionFailureReason.NOT_VERIFIED)
    val before = read() ?: unknown()
    val foreign = before.owners - before.thorOwners
    if (suspended) {
        if (before.thorOwners.isNotEmpty()) return SuspensionChange(foreign.map { it.packageName }.toSet())
        if ((actor in before.owners && actor !in before.thorOwners) ||
            (sdkInt < 30 && foreign.isNotEmpty())) {
            throw SuspensionPolicyException(SuspensionFailureReason.CANNOT_KEEP_INDEPENDENT)
        }
        if (actor !in before.thorOwners) {
            if (!canWrite(actor)) throw SuspensionPolicyException(SuspensionFailureReason.WRONG_PRIVILEGE)
            write(actor, true)
        }
        val after = read() ?: unverified()
        if (actor !in after.thorOwners) unverified()
        return SuspensionChange((after.owners - after.thorOwners).map { it.packageName }.toSet())
    }
    if (before.thorOwners.any { !canWrite(it) }) {
        throw SuspensionPolicyException(SuspensionFailureReason.WRONG_PRIVILEGE)
    }
    var after = before
    for (owner in before.thorOwners) {
        write(owner, false)
        after = read() ?: unverified()
        if (owner in after.owners) unverified()
    }
    if (after.thorOwners.isNotEmpty()) unverified()
    return SuspensionChange(after.owners.map { it.packageName }.toSet())
}
