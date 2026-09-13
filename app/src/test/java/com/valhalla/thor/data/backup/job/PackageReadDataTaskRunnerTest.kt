// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.SharePrepareFormat
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PackageReadDataTaskRunnerTest {
    @Test
    fun `a read lease encloses the complete runner and blocks only same package mutations`() = runTest {
        val packages = DefaultPackageOperationCoordinator()
        var ran = false
        val runner = PackageReadDataTaskRunner(
            delegate = delegate {
                ran = true
                val samePackage = packages.withPackageLease(
                    PACKAGE_NAME, PackageOperationOwner.UNINSTALL, Duration.ZERO,
                ) { "same package mutation entered during a bundle read" }
                assertEquals(PackageLeaseResult.Busy(PackageOperationOwner.BUNDLE_READ), samePackage)
                val otherPackage = packages.withPackageLease(
                    "com.example.other", PackageOperationOwner.UNINSTALL, Duration.ZERO,
                ) { "unrelated" }
                assertEquals(PackageLeaseResult.Acquired("unrelated"), otherPackage)
                DataTaskRunOutcome.OwnershipLost
            },
            packages = packages,
        )

        assertEquals(DataTaskRunOutcome.OwnershipLost, runner.run(request(), sink()))
        assertTrue(ran)
        assertEquals(
            PackageLeaseResult.Acquired("released"),
            packages.withPackageLease(PACKAGE_NAME, PackageOperationOwner.UNINSTALL, Duration.ZERO) {
                "released"
            },
        )
    }

    @Test
    fun `ARCHIVE admission waits five seconds then fails this item without running any read`() = runTest {
        val packages = DefaultPackageOperationCoordinator()
        val ownerEntered = CompletableDeferred<Unit>()
        val releaseOwner = CompletableDeferred<Unit>()
        val owner = backgroundScope.launch {
            packages.withPackageLease(PACKAGE_NAME, PackageOperationOwner.REINSTALL, Duration.ZERO) {
                ownerEntered.complete(Unit)
                releaseOwner.await()
            }
        }
        ownerEntered.await()
        var reads = 0
        val result = async {
            PackageReadDataTaskRunner(
                delegate { reads++; DataTaskRunOutcome.OwnershipLost },
                packages,
            ).run(request(), sink())
        }
        runCurrent()
        advanceTimeBy(4_999L)
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(0, reads)
        advanceTimeBy(1L)
        runCurrent()

        val outcome = result.await() as DataTaskRunOutcome.ItemCompleted
        assertEquals(DataTaskItemTerminalState.FAILED, outcome.result.terminalState)
        assertEquals("PACKAGE_OPERATION_BUSY", outcome.result.resultCode.value)
        assertTrue(outcome.result.outputs.isEmpty())
        assertEquals(0, reads)
        releaseOwner.complete(Unit)
        owner.join()
    }

    @Test
    fun `cancelling a bundle read releases its package lease before another mutation enters`() = runTest {
        val packages = DefaultPackageOperationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val runner = PackageReadDataTaskRunner(
            delegate {
                entered.complete(Unit)
                awaitCancellation()
            },
            packages,
        )
        val reading = launch { runner.run(request(), sink()) }
        entered.await()
        reading.cancelAndJoin()

        assertEquals(
            PackageLeaseResult.Acquired(true),
            packages.withPackageLease(PACKAGE_NAME, PackageOperationOwner.REINSTALL, Duration.ZERO) {
                true
            },
        )
    }

    @Test
    fun `export and share hold the read lease through suspended cancellation cleanup`() = runTest {
        for (kind in listOf(DataTaskKind.APP_EXPORT, DataTaskKind.SHARE_PREPARE)) {
            val packages = DefaultPackageOperationCoordinator()
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val runner = PackageReadDataTaskRunner(delegate(kind) {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        cleaning.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }, packages)
            val running = launch { runner.run(request(), sink()) }
            entered.await()
            running.cancel()
            cleaning.await()
            assertEquals(PackageLeaseResult.Busy(PackageOperationOwner.BUNDLE_READ),
                packages.withPackageLease(PACKAGE_NAME, PackageOperationOwner.REINSTALL, Duration.ZERO) { true })
            releaseCleanup.complete(Unit)
            running.join()
            assertEquals(PackageLeaseResult.Acquired(true),
                packages.withPackageLease(PACKAGE_NAME, PackageOperationOwner.REINSTALL, Duration.ZERO) { true })
        }
    }

    @Test
    fun `a failed export releases the lease and a cancelled waiter never reads`() = runTest {
        val packages = DefaultPackageOperationCoordinator()
        val failure = PackageReadDataTaskRunner(delegate(DataTaskKind.APP_EXPORT) {
            throw IllegalStateException("read failed")
        }, packages)
        try { failure.run(request(), sink()); org.junit.Assert.fail("expected read failure") }
        catch (_: IllegalStateException) { }
        val held = CompletableDeferred<Unit>()
        val owner = launch {
            packages.withPackageLease(PACKAGE_NAME, PackageOperationOwner.REINSTALL, Duration.ZERO) {
                held.complete(Unit); awaitCancellation()
            }
        }
        held.await()
        var reads = 0
        val waiting = launch { PackageReadDataTaskRunner(delegate { reads++; DataTaskRunOutcome.OwnershipLost }, packages).run(request(), sink()) }
        runCurrent()
        waiting.cancelAndJoin()
        owner.cancelAndJoin()
        assertEquals(0, reads)
        assertEquals(PackageLeaseResult.Acquired(true),
            packages.withPackageLease(PACKAGE_NAME, PackageOperationOwner.UNINSTALL, Duration.ZERO) { true })
    }

    private fun delegate(kind: DataTaskKind = DataTaskKind.SHARE_PREPARE, block: suspend () -> DataTaskRunOutcome) = object : DataTaskRunner {
        override val kind = kind
        override suspend fun run(
            request: DataTaskExecutionRequest,
            checkpoints: DataTaskCheckpointSink,
        ): DataTaskRunOutcome = block()
    }

    private fun request() = DataTaskExecutionRequest(
        taskId = TASK_ID,
        payload = DataTaskExecutionPayload.SharePrepare(
            requestedFormat = SharePrepareFormat.AUTO,
            publicationPolicy = DataTaskPublicationPolicy.PRIVATE_SHARE_WITH_24_HOUR_EXPIRY,
        ),
        item = DataTaskExecutionItem(0, PACKAGE_NAME, "Example", "item-$TASK_ID-0", 1),
        taskAttemptCount = 1,
        resumedFrom = null,
    )

    private fun sink() = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED }

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
        val TASK_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
    }
}
