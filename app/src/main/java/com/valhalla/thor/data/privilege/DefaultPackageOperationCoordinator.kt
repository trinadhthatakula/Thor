// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.privilege

import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.repository.PackageOperationCoordinator
import java.util.ArrayDeque
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single

@Single(binds = [PackageOperationCoordinator::class])
internal class DefaultPackageOperationCoordinator : PackageOperationCoordinator {
    private val stateMutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    override suspend fun <T> withPackageLease(
        packageName: String,
        owner: PackageOperationOwner,
        admissionTimeout: Duration,
        block: suspend () -> T,
    ): PackageLeaseResult<T> {
        val claim = Claim(owner)
        var entry: Entry? = null
        var acquiredImmediately = false
        var immediateBusyOwner: PackageOperationOwner? = null

        try {
            stateMutex.withLock {
                val currentEntry = entries.getOrPut(packageName, ::Entry)
                entry = currentEntry
                currentEntry.references++
                when {
                    currentEntry.active == null -> {
                        check(currentEntry.mutex.tryLock()) {
                            "An idle package entry must have an unlocked mutex"
                        }
                        activate(currentEntry, claim)
                        acquiredImmediately = true
                    }

                    admissionTimeout == Duration.ZERO -> {
                        immediateBusyOwner = checkNotNull(currentEntry.owner) {
                            "A locked package entry must have an owner"
                        }
                        claim.status = ClaimStatus.RELEASED
                        currentEntry.references--
                    }

                    else -> {
                        claim.status = ClaimStatus.WAITING
                        currentEntry.waiters.addLast(claim)
                    }
                }
            }

            immediateBusyOwner?.let { return PackageLeaseResult.Busy(it) }
            if (!acquiredImmediately) {
                val signalled = withTimeoutOrNull(admissionTimeout) {
                    claim.admitted.await()
                    true
                } ?: false
                val timeoutOwner = stateMutex.withLock {
                    when (claim.status) {
                        ClaimStatus.ACTIVE -> null
                        ClaimStatus.WAITING -> {
                            check(!signalled) { "A signalled claim must be active" }
                            val waitingEntry = checkNotNull(entry)
                            releaseWaitingClaim(entry = waitingEntry, claim = claim)
                            checkNotNull(waitingEntry.owner) {
                                "A timed-out package entry must have an owner"
                            }
                        }

                        ClaimStatus.RELEASED -> error("A pending claim was released unexpectedly")
                        ClaimStatus.NEW -> error("A registered claim must not remain new")
                    }
                }
                timeoutOwner?.let { return PackageLeaseResult.Busy(it) }
            }

            return PackageLeaseResult.Acquired(block())
        } finally {
            val registeredEntry = entry
            if (registeredEntry != null) {
                withContext(NonCancellable) {
                    stateMutex.withLock {
                        releaseClaim(
                            packageName = packageName,
                            entry = registeredEntry,
                            claim = claim,
                        )
                    }
                }
            }
        }
    }

    private fun activate(entry: Entry, claim: Claim) {
        check(entry.active == null) { "A package entry cannot have two active claims" }
        entry.active = claim
        entry.owner = claim.owner
        claim.status = ClaimStatus.ACTIVE
    }

    private fun releaseClaim(packageName: String, entry: Entry, claim: Claim) {
        when (claim.status) {
            ClaimStatus.ACTIVE -> {
                check(entry.active === claim) { "Only the active package claim can release ownership" }
                claim.status = ClaimStatus.RELEASED
                entry.references--
                val next = entry.waiters.pollFirst()
                if (next == null) {
                    entry.active = null
                    entry.owner = null
                    entry.mutex.unlock()
                } else {
                    entry.active = null
                    activate(entry, next)
                    check(next.admitted.complete(Unit)) { "A queued claim must be admitted once" }
                }
            }

            ClaimStatus.WAITING -> releaseWaitingClaim(entry, claim)
            ClaimStatus.RELEASED -> Unit
            ClaimStatus.NEW -> error("A registered claim must not remain new")
        }

        if (entry.references == 0) {
            check(entry.active == null && entry.waiters.isEmpty() && !entry.mutex.isLocked) {
                "A referenced package entry cannot be removed"
            }
            entries.remove(packageName, entry)
        }
    }

    private fun releaseWaitingClaim(entry: Entry, claim: Claim) {
        check(entry.waiters.remove(claim)) { "A waiting claim must remain queued until release" }
        claim.status = ClaimStatus.RELEASED
        entry.references--
    }

    private class Entry(
        val mutex: Mutex = Mutex(),
        var owner: PackageOperationOwner? = null,
        var active: Claim? = null,
        val waiters: ArrayDeque<Claim> = ArrayDeque(),
        var references: Int = 0,
    )

    private class Claim(
        val owner: PackageOperationOwner,
        val admitted: CompletableDeferred<Unit> = CompletableDeferred(),
        var status: ClaimStatus = ClaimStatus.NEW,
    )

    private enum class ClaimStatus {
        NEW,
        WAITING,
        ACTIVE,
        RELEASED,
    }
}
