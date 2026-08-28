// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.privilege

import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.PrivilegeExecutionTimeouts
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPackageOperationCoordinatorTest {

    @Test
    fun `different packages run concurrently`() = runTest(StandardTestDispatcher()) {
        val coordinator = DefaultPackageOperationCoordinator()
        val release = CompletableDeferred<Unit>()
        val entered = mutableSetOf<String>()

        val first = async {
            coordinator.withPackageLease(
                packageName = "com.example.first",
                owner = PackageOperationOwner.FORCE_STOP,
                admissionTimeout = Duration.ZERO,
            ) {
                entered += "first"
                release.await()
            }
        }
        val second = async {
            coordinator.withPackageLease(
                packageName = "com.example.second",
                owner = PackageOperationOwner.CLEAR_DATA,
                admissionTimeout = Duration.ZERO,
            ) {
                entered += "second"
                release.await()
            }
        }

        runCurrent()

        assertEquals(setOf("first", "second"), entered)
        release.complete(Unit)
        runCurrent()
        assertTrue(first.await() is PackageLeaseResult.Acquired<*>)
        assertTrue(second.await() is PackageLeaseResult.Acquired<*>)
    }

    @Test
    fun `same package reports current owner after zero admission timeout`() =
        runTest(StandardTestDispatcher()) {
            val coordinator = DefaultPackageOperationCoordinator()
            val release = CompletableDeferred<Unit>()
            var contenderEntered = false
            val holder = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.ARCHIVE_BACKUP,
                    admissionTimeout = Duration.ZERO,
                ) {
                    release.await()
                }
            }
            runCurrent()

            val result = coordinator.withPackageLease(
                packageName = "com.example.app",
                owner = PackageOperationOwner.FORCE_STOP,
                admissionTimeout = Duration.ZERO,
            ) {
                contenderEntered = true
            }

            assertEquals(
                PackageLeaseResult.Busy(PackageOperationOwner.ARCHIVE_BACKUP),
                result,
            )
            assertFalse(contenderEntered)
            release.complete(Unit)
            runCurrent()
            assertTrue(holder.await() is PackageLeaseResult.Acquired<*>)
        }

    @Test
    fun `sweep waits two seconds then reports busy`() = runTest(StandardTestDispatcher()) {
        val coordinator = DefaultPackageOperationCoordinator()
        val release = CompletableDeferred<Unit>()
        val holder = async {
            coordinator.withPackageLease(
                packageName = "com.example.app",
                owner = PackageOperationOwner.ARCHIVE_RESTORE,
                admissionTimeout = Duration.ZERO,
            ) {
                release.await()
            }
        }
        runCurrent()

        val waiter = async {
            coordinator.withPackageLease(
                packageName = "com.example.app",
                owner = PackageOperationOwner.OTHER_MUTATION,
                admissionTimeout = PrivilegeExecutionTimeouts.SWEEP_ADMISSION,
            ) {
                error("a timed-out sweep must not enter")
            }
        }
        runCurrent()

        advanceTimeBy(1_999.milliseconds)
        runCurrent()
        assertFalse(waiter.isCompleted)
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(
            PackageLeaseResult.Busy(PackageOperationOwner.ARCHIVE_RESTORE),
            waiter.await(),
        )
        release.complete(Unit)
        runCurrent()
        assertTrue(holder.await() is PackageLeaseResult.Acquired<*>)
    }

    @Test
    fun `archive waits five seconds then reports busy`() = runTest(StandardTestDispatcher()) {
        val coordinator = DefaultPackageOperationCoordinator()
        val release = CompletableDeferred<Unit>()
        val holder = async {
            coordinator.withPackageLease(
                packageName = "com.example.app",
                owner = PackageOperationOwner.FORCE_STOP,
                admissionTimeout = Duration.ZERO,
            ) {
                release.await()
            }
        }
        runCurrent()

        val waiter = async {
            coordinator.withPackageLease(
                packageName = "com.example.app",
                owner = PackageOperationOwner.ARCHIVE_BACKUP,
                admissionTimeout = PrivilegeExecutionTimeouts.ARCHIVE_ADMISSION,
            ) {
                error("a timed-out archive must not enter")
            }
        }
        runCurrent()

        advanceTimeBy(4_999.milliseconds)
        runCurrent()
        assertFalse(waiter.isCompleted)
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(
            PackageLeaseResult.Busy(PackageOperationOwner.FORCE_STOP),
            waiter.await(),
        )
        release.complete(Unit)
        runCurrent()
        assertTrue(holder.await() is PackageLeaseResult.Acquired<*>)
    }

    @Test
    fun `cancelled waiter never enters its block`() = runTest(StandardTestDispatcher()) {
        val coordinator = DefaultPackageOperationCoordinator()
        val release = CompletableDeferred<Unit>()
        var waiterEntered = false
        val holder = async {
            coordinator.withPackageLease(
                packageName = "com.example.app",
                owner = PackageOperationOwner.FORCE_STOP,
                admissionTimeout = Duration.ZERO,
            ) {
                release.await()
            }
        }
        runCurrent()
        val waiter = async {
            coordinator.withPackageLease(
                packageName = "com.example.app",
                owner = PackageOperationOwner.ARCHIVE_BACKUP,
                admissionTimeout = PrivilegeExecutionTimeouts.ARCHIVE_ADMISSION,
            ) {
                waiterEntered = true
            }
        }
        runCurrent()

        waiter.cancel(CancellationException("cancel the queued operation"))
        waiter.cancelAndJoin()
        val cancellation = try {
            waiter.await()
            null
        } catch (expected: CancellationException) {
            expected
        }
        release.complete(Unit)
        runCurrent()

        assertEquals("cancel the queued operation", cancellation?.message)
        assertFalse(waiterEntered)
        assertTrue(holder.await() is PackageLeaseResult.Acquired<*>)
        val next = coordinator.withPackageLease(
            packageName = "com.example.app",
            owner = PackageOperationOwner.CLEAR_CACHE,
            admissionTimeout = Duration.ZERO,
        ) {
            "entered"
        }
        assertEquals(PackageLeaseResult.Acquired("entered"), next)
    }

    @Test
    fun `release at timeout deadline never orphans package lease`() =
        runTest(StandardTestDispatcher()) {
            val coordinator = DefaultPackageOperationCoordinator()
            var laterWaiterEntered = false
            val holder = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.ARCHIVE_BACKUP,
                    admissionTimeout = Duration.ZERO,
                ) {
                    delay(PrivilegeExecutionTimeouts.SWEEP_ADMISSION)
                }
            }
            runCurrent()
            val deadlineWaiter = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.FORCE_STOP,
                    admissionTimeout = PrivilegeExecutionTimeouts.SWEEP_ADMISSION,
                ) {
                    "deadline waiter entered"
                }
            }
            val laterWaiter = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.CLEAR_DATA,
                    admissionTimeout = PrivilegeExecutionTimeouts.ARCHIVE_ADMISSION,
                ) {
                    laterWaiterEntered = true
                    "later waiter entered"
                }
            }
            runCurrent()

            advanceTimeBy(PrivilegeExecutionTimeouts.SWEEP_ADMISSION)
            val handoffContender = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.UNINSTALL,
                    admissionTimeout = Duration.ZERO,
                ) {
                    error("a zero-time contender must not overlap the deadline handoff")
                }
            }
            runCurrent()

            assertEquals(
                PackageLeaseResult.Busy(PackageOperationOwner.FORCE_STOP),
                handoffContender.await(),
            )
            assertTrue(holder.await() is PackageLeaseResult.Acquired<*>)
            assertTrue(deadlineWaiter.isCompleted)
            assertTrue("the deadline handoff orphaned the package lease", laterWaiterEntered)
            assertEquals(PackageLeaseResult.Acquired("later waiter entered"), laterWaiter.await())
            assertEquals(0, coordinator.entryCount())
        }

    @Test
    fun `cancelled handoff promotes later waiter without deadlock`() =
        runTest(StandardTestDispatcher()) {
            val coordinator = DefaultPackageOperationCoordinator()
            val releaseOwner = CompletableDeferred<Unit>()
            var cancelledWaiterEntered = false
            var laterWaiterEntered = false
            val holder = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.ARCHIVE_BACKUP,
                    admissionTimeout = Duration.ZERO,
                ) {
                    releaseOwner.await()
                }
            }
            runCurrent()
            val cancelledWaiter = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.ARCHIVE_RESTORE,
                    admissionTimeout = PrivilegeExecutionTimeouts.ARCHIVE_ADMISSION,
                ) {
                    cancelledWaiterEntered = true
                }
            }
            val laterWaiter = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.CLEAR_DATA,
                    admissionTimeout = PrivilegeExecutionTimeouts.ARCHIVE_ADMISSION,
                ) {
                    laterWaiterEntered = true
                    "later waiter entered"
                }
            }
            runCurrent()

            releaseOwner.complete(Unit)
            cancelledWaiter.cancel(CancellationException("cancel during handoff"))
            val handoffContender = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.UNINSTALL,
                    admissionTimeout = Duration.ZERO,
                ) {
                    error("a zero-time contender must not overlap the cancellation handoff")
                }
            }
            runCurrent()

            assertEquals(
                PackageLeaseResult.Busy(PackageOperationOwner.CLEAR_DATA),
                handoffContender.await(),
            )
            assertFalse(cancelledWaiterEntered)
            assertTrue(holder.await() is PackageLeaseResult.Acquired<*>)
            assertTrue("cancellation during handoff stranded the later waiter", laterWaiterEntered)
            assertEquals(PackageLeaseResult.Acquired("later waiter entered"), laterWaiter.await())
            assertEquals(0, coordinator.entryCount())
        }

    @Test
    fun `zero-time contender observes each queued owner handoff`() =
        runTest(StandardTestDispatcher()) {
            val coordinator = DefaultPackageOperationCoordinator()
            val releaseHolder = CompletableDeferred<Unit>()
            val releaseFirstWaiter = CompletableDeferred<Unit>()
            val releaseSecondWaiter = CompletableDeferred<Unit>()
            val holder = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.FORCE_STOP,
                    admissionTimeout = Duration.ZERO,
                ) {
                    releaseHolder.await()
                }
            }
            runCurrent()
            val firstWaiter = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.ARCHIVE_RESTORE,
                    admissionTimeout = PrivilegeExecutionTimeouts.ARCHIVE_ADMISSION,
                ) {
                    releaseFirstWaiter.await()
                }
            }
            val secondWaiter = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.CLEAR_DATA,
                    admissionTimeout = PrivilegeExecutionTimeouts.ARCHIVE_ADMISSION,
                ) {
                    releaseSecondWaiter.await()
                }
            }
            runCurrent()

            releaseHolder.complete(Unit)
            val firstContender = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.UNINSTALL,
                    admissionTimeout = Duration.ZERO,
                ) {
                    error("a zero-time contender must not overlap a queued owner")
                }
            }
            runCurrent()
            assertEquals(
                PackageLeaseResult.Busy(PackageOperationOwner.ARCHIVE_RESTORE),
                firstContender.await(),
            )

            releaseFirstWaiter.complete(Unit)
            val secondContender = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.REINSTALL,
                    admissionTimeout = Duration.ZERO,
                ) {
                    error("a zero-time contender must not overlap a queued owner")
                }
            }
            runCurrent()
            assertEquals(
                PackageLeaseResult.Busy(PackageOperationOwner.CLEAR_DATA),
                secondContender.await(),
            )

            releaseSecondWaiter.complete(Unit)
            runCurrent()
            assertTrue(holder.await() is PackageLeaseResult.Acquired<*>)
            assertTrue(firstWaiter.await() is PackageLeaseResult.Acquired<*>)
            assertTrue(secondWaiter.await() is PackageLeaseResult.Acquired<*>)
            assertEquals(0, coordinator.entryCount())
        }

    @Test
    fun `entry is removed only after last waiter or owner releases it`() =
        runTest(StandardTestDispatcher()) {
            val coordinator = DefaultPackageOperationCoordinator()
            val releaseOwner = CompletableDeferred<Unit>()
            val releaseWaiter = CompletableDeferred<Unit>()
            var waiterEntered = false
            val holder = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.FORCE_STOP,
                    admissionTimeout = Duration.ZERO,
                ) {
                    releaseOwner.await()
                }
            }
            runCurrent()
            val waiter = async {
                coordinator.withPackageLease(
                    packageName = "com.example.app",
                    owner = PackageOperationOwner.ARCHIVE_RESTORE,
                    admissionTimeout = PrivilegeExecutionTimeouts.ARCHIVE_ADMISSION,
                ) {
                    waiterEntered = true
                    releaseWaiter.await()
                }
            }
            runCurrent()

            assertEquals(1, coordinator.entryCount())
            releaseOwner.complete(Unit)
            runCurrent()
            assertTrue(waiterEntered)
            assertEquals(1, coordinator.entryCount())

            val overlapping = coordinator.withPackageLease(
                packageName = "com.example.app",
                owner = PackageOperationOwner.CLEAR_DATA,
                admissionTimeout = Duration.ZERO,
            ) {
                error("a new entry must not overlap the retained waiter entry")
            }
            assertEquals(
                PackageLeaseResult.Busy(PackageOperationOwner.ARCHIVE_RESTORE),
                overlapping,
            )

            releaseWaiter.complete(Unit)
            runCurrent()
            assertTrue(holder.await() is PackageLeaseResult.Acquired<*>)
            assertTrue(waiter.await() is PackageLeaseResult.Acquired<*>)
            assertEquals(0, coordinator.entryCount())

            val afterRelease = coordinator.withPackageLease(
                packageName = "com.example.app",
                owner = PackageOperationOwner.CLEAR_DATA,
                admissionTimeout = Duration.ZERO,
            ) {
                "entered"
            }
            assertEquals(PackageLeaseResult.Acquired("entered"), afterRelease)
            assertEquals(0, coordinator.entryCount())
        }

    private fun DefaultPackageOperationCoordinator.entryCount(): Int {
        val entries = javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(this)
        return (entries as Map<*, *>).size
    }
}
