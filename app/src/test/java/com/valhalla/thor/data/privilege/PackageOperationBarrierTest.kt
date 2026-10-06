// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.privilege

import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.repository.PackageOperationBarrier
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PackageOperationBarrierTest {
    @Test
    fun `retained clear work blocks a fresh claim without invoking its operation`() = runTest {
        var blocked = true
        val checks = mutableListOf<Pair<String, PackageOperationOwner>>()
        val coordinator = DefaultPackageOperationCoordinator(PackageOperationBarrier { name, owner ->
            checks += name to owner
            blocked
        })
        var entered = false

        val refused = coordinator.withPackageLease(PACKAGE, PackageOperationOwner.ARCHIVE_RESTORE, Duration.ZERO) {
            entered = true
        }

        assertEquals(PackageLeaseResult.Busy(PackageOperationOwner.CLEAR_DATA), refused)
        assertFalse(entered)
        assertEquals(listOf(PACKAGE to PackageOperationOwner.ARCHIVE_RESTORE), checks)
        blocked = false
        val accepted = coordinator.withPackageLease(PACKAGE, PackageOperationOwner.ARCHIVE_RESTORE, Duration.ZERO) {
            entered = true
        }
        assertTrue(accepted is PackageLeaseResult.Acquired<*>)
        assertTrue(entered)
    }

    @Test
    fun `a queued waiter rechecks a barrier created by its predecessor`() = runTest {
        var blocked = false
        val checks = mutableListOf<PackageOperationOwner>()
        val coordinator = DefaultPackageOperationCoordinator(PackageOperationBarrier { _, owner ->
            checks += owner
            blocked
        })
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            coordinator.withPackageLease(PACKAGE, PackageOperationOwner.CLEAR_DATA, Duration.ZERO) {
                releaseFirst.await()
            }
        }
        runCurrent()
        var waiterEntered = false
        val waiter = async {
            coordinator.withPackageLease(PACKAGE, PackageOperationOwner.ARCHIVE_RESTORE, 5.seconds) {
                waiterEntered = true
            }
        }
        runCurrent()
        assertEquals(listOf(PackageOperationOwner.CLEAR_DATA), checks)

        blocked = true
        releaseFirst.complete(Unit)
        runCurrent()

        assertTrue(first.await() is PackageLeaseResult.Acquired<*>)
        assertEquals(PackageLeaseResult.Busy(PackageOperationOwner.CLEAR_DATA), waiter.await())
        assertFalse(waiterEntered)
        assertEquals(listOf(PackageOperationOwner.CLEAR_DATA, PackageOperationOwner.ARCHIVE_RESTORE), checks)
    }

    @Test
    fun `suspending reconciliation retains its package claim without holding the global mutex`() = runTest {
        val releaseRead = CompletableDeferred<Unit>()
        val coordinator = DefaultPackageOperationCoordinator(PackageOperationBarrier { name, _ ->
            if (name == PACKAGE) releaseRead.await()
            false
        })
        val first = async {
            coordinator.withPackageLease(PACKAGE, PackageOperationOwner.FORCE_STOP, Duration.ZERO) { Unit }
        }
        runCurrent()
        assertFalse(first.isCompleted)

        val samePackage = coordinator.withPackageLease(PACKAGE, PackageOperationOwner.CLEAR_DATA, Duration.ZERO) {
            error("The reconciliation owns this package until its read finishes")
        }
        val unrelated = coordinator.withPackageLease("com.example.other", PackageOperationOwner.FORCE_STOP, Duration.ZERO) {
            "ran"
        }

        assertEquals(PackageLeaseResult.Busy(PackageOperationOwner.FORCE_STOP), samePackage)
        assertEquals(PackageLeaseResult.Acquired("ran"), unrelated)
        releaseRead.complete(Unit)
        assertTrue(first.await() is PackageLeaseResult.Acquired<*>)
    }

    @Test
    fun `cancelled reconciliation never enters the operation and releases only its lexical claim`() = runTest {
        var hold = true
        val releaseRead = CompletableDeferred<Unit>()
        val coordinator = DefaultPackageOperationCoordinator(PackageOperationBarrier { _, _ ->
            if (hold) releaseRead.await()
            false
        })
        var entered = false
        val first = async {
            coordinator.withPackageLease(PACKAGE, PackageOperationOwner.CLEAR_DATA, Duration.ZERO) {
                entered = true
            }
        }
        runCurrent()
        first.cancelAndJoin()
        assertFalse(entered)

        hold = false
        val next = coordinator.withPackageLease(PACKAGE, PackageOperationOwner.FORCE_STOP, Duration.ZERO) { "next" }
        assertEquals(PackageLeaseResult.Acquired("next"), next)
    }

    private companion object {
        const val PACKAGE = "com.example.target"
    }
}
