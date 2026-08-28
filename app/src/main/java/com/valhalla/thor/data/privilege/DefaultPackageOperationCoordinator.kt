// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.privilege

import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.repository.PackageOperationCoordinator
import kotlin.time.Duration
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
        var acquired = false
        val entry = stateMutex.withLock {
            entries.getOrPut(packageName, ::Entry).also {
                it.references++
                acquired = it.mutex.tryLock()
                if (acquired) it.owner = owner
            }
        }

        try {
            if (!acquired && admissionTimeout != Duration.ZERO) {
                acquired = waitForEntry(entry, admissionTimeout)
                if (acquired) {
                    stateMutex.withLock {
                        entry.owner = owner
                    }
                }
            }
            if (!acquired) {
                val currentOwner = stateMutex.withLock {
                    checkNotNull(entry.owner) { "A locked package entry must have an owner" }
                }
                return PackageLeaseResult.Busy(currentOwner)
            }

            return PackageLeaseResult.Acquired(block())
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    if (acquired) entry.mutex.unlock()
                    entry.references--
                    if (entry.references == 0) {
                        entries.remove(packageName, entry)
                    }
                }
            }
        }
    }

    private suspend fun waitForEntry(entry: Entry, admissionTimeout: Duration): Boolean =
        withTimeoutOrNull(admissionTimeout) {
            entry.mutex.lock()
            true
        } ?: false

    private class Entry(
        val mutex: Mutex = Mutex(),
        var owner: PackageOperationOwner? = null,
        var references: Int = 0,
    )
}
