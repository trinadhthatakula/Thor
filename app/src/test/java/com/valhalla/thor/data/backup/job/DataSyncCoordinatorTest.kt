// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.model.StoredDataDestination
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.model.StoredRestoreSource
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataSyncCoordinatorTest {

    @Test
    fun `repeated wakes share one FIFO drain and wait for both gates before execution`() = runTest {
        val events = mutableListOf<String>()
        val legacyOpen = CompletableDeferred<Unit>()
        val sweepOpen = CompletableDeferred<Unit>()
        val claims = ArrayDeque(listOf(claim(1), claim(2)))
        var concurrent = 0
        var maxConcurrent = 0
        val coordinator = coordinator(
            awaitLegacyDrain = {
                events += "legacy"
                legacyOpen.await()
            },
            awaitLaunchSweep = {
                events += "sweep"
                sweepOpen.await()
                true
            },
            claimNext = { _, token ->
                if (claims.isEmpty()) null else claims.removeFirst().copy(claimToken = token)
            },
            executeClaim = { claimed, _ ->
                concurrent += 1
                maxConcurrent = maxOf(maxConcurrent, concurrent)
                events += "run:${claimed.taskId}"
                concurrent -= 1
                completed()
            },
        )

        repeat(20) { coordinator.wake {} }
        advanceUntilIdle()
        assertEquals(listOf("legacy"), events)

        legacyOpen.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("legacy", "sweep"), events)

        sweepOpen.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, maxConcurrent)
        assertEquals(listOf(TASK_1, TASK_2), events.filter { it.startsWith("run:") }.map {
            UUID.fromString(it.removePrefix("run:"))
        })
        assertEquals(1, coordinator.drainLaunchCountForTest)
    }

    @Test
    fun `claimed task is announced before its runner starts`() = runTest {
        val events = mutableListOf<String>()
        val claims = ArrayDeque(listOf(claim(1)))
        val coordinator = coordinator(
            claimNext = { _, token ->
                if (claims.isEmpty()) null else claims.removeFirst().copy(claimToken = token)
            },
            executeClaim = { claimed, _ ->
                events += "run:${claimed.taskId}"
                completed()
            },
        )

        coordinator.wake(
            onClaimed = { taskId, _ -> events += "active:$taskId" },
            onDrained = {},
        )
        advanceUntilIdle()

        assertEquals(listOf("active:$TASK_1", "run:$TASK_1"), events)
    }

    @Test
    fun `ordinary runner failure settles and later queue entry still runs`() = runTest {
        val claims = ArrayDeque(listOf(claim(1), claim(2)))
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            claimNext = { _, token ->
                if (claims.isEmpty()) null else claims.removeFirst().copy(claimToken = token)
            },
            executeClaim = { claimed, _ ->
                events += "run:${claimed.taskId}"
                if (claimed.taskId == TASK_1) error("boom")
                completed()
            },
            persistOutcome = { claimed, outcome ->
                events += "settle:${claimed.taskId}:${outcome::class.simpleName}"
            },
        )

        coordinator.wake {}
        advanceUntilIdle()

        assertTrue(events.any { it == "settle:$TASK_1:TaskFailed" })
        assertTrue(events.any { it == "run:$TASK_2" })
    }

    @Test
    fun `claim token is registered before claim and stays live through cleanup and settlement`() =
        runTest {
            val registry = DataTaskOwnerRegistry()
            val events = mutableListOf<String>()
            val claims = ArrayDeque(listOf(claim(1)))
            val coordinator = coordinator(
                registry = registry,
                claimNext = { _, token ->
                    if (claims.isEmpty()) {
                        null
                    } else {
                        assertTrue(registry.isLive(TASK_1, token))
                        claims.removeFirst().copy(claimToken = token)
                    }
                },
                executeClaim = { _, _ -> completed() },
                cleanupClaim = { claimed ->
                    assertTrue(registry.isLive(claimed.taskId, claimed.claimToken))
                    events += "cleanup"
                },
                persistOutcome = { claimed, _ ->
                    assertTrue(registry.isLive(claimed.taskId, claimed.claimToken))
                    events += "settle"
                },
            )

            coordinator.wake {}
            advanceUntilIdle()

            assertEquals(listOf("settle", "cleanup"), events)
            assertFalse(registry.isLive(TASK_1, coordinator.lastClaimTokenForTest!!))
        }

    @Test
    fun `timeout cancels active child settles after cleanup and prevents another claim`() =
        runTest {
            val events = mutableListOf<String>()
            val claims = ArrayDeque(listOf(claim(1), claim(2)))
            val running = CompletableDeferred<Unit>()
            var claimCalls = 0
            val coordinator = coordinator(
                claimNext = { _, token ->
                    claimCalls += 1
                    if (claims.isEmpty()) null else claims.removeFirst().copy(claimToken = token)
                },
                executeClaim = { _, _ ->
                    try {
                        running.await()
                        completed()
                    } finally {
                        events += "child-cancelled"
                    }
                },
                persistTimeoutInterruption = {
                    events += "interrupt"
                    true
                },
                timeoutOutcome = {
                    DataTaskRunOutcome.WaitingForAuthentication(
                        DataTaskResultCode("SERVICE_TIMEOUT")
                    )
                },
                cleanupClaim = { events += "cleanup" },
                persistOutcome = { _, outcome -> events += "settle:${outcome::class.simpleName}" },
            )

            coordinator.wake { events += "stop" }
            testScheduler.runCurrent()
            coordinator.stopClaimsAndInterrupt()
            advanceUntilIdle()

            assertEquals(1, claimCalls)
            assertEquals(
                listOf(
                    "interrupt",
                    "child-cancelled",
                    "cleanup",
                    "settle:WaitingForAuthentication",
                    "stop",
                ),
                events,
            )
        }

    @Test
    fun `timeout settles a claim that commits while claim call is being cancelled`() = runTest {
        val claimEntered = CompletableDeferred<Unit>()
        val releaseClaim = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var executed = false
        val coordinator = coordinator(
            claimNext = { _, token ->
                claimEntered.complete(Unit)
                try {
                    releaseClaim.await()
                } catch (_: CancellationException) {
                    withContext(NonCancellable) { releaseClaim.await() }
                }
                claim(1).copy(claimToken = token)
            },
            executeClaim = { _, _ ->
                executed = true
                completed()
            },
            persistTimeoutInterruption = {
                events += "interrupt"
                true
            },
            timeoutOutcome = {
                DataTaskRunOutcome.WaitingForAuthentication(
                    DataTaskResultCode("SERVICE_TIMEOUT")
                )
            },
            cleanupClaim = { events += "cleanup" },
            persistOutcome = { _, outcome -> events += "settle:${outcome::class.simpleName}" },
        )

        coordinator.wake { events += "stop" }
        testScheduler.runCurrent()
        claimEntered.await()
        coordinator.stopClaimsAndInterrupt()
        releaseClaim.complete(Unit)
        advanceUntilIdle()

        assertFalse(executed)
        assertEquals(
            listOf("interrupt", "cleanup", "settle:WaitingForAuthentication", "stop"),
            events,
        )
    }

    @Test
    fun `timeout outcome follows operation-specific recovery policy`() {
        val backup = StoredDataTaskDetail.ArchiveBackup(
            packageName = "com.example.app",
            dataClassIds = listOf("apk"),
            includeBundle = false,
            kdfSaltBase64 = "c2FsdA==",
            destination = StoredDataDestination.ArchiveStore,
            deterministicStagingIdentity = "backup-stage",
        )
        val awaitingRestore = restoreDetail(StoredRestoreSource.AwaitingTransientGrant)
        val durableRestore =
            restoreDetail(StoredRestoreSource.PrivateCopy("data_tasks/task/source.thor"))
        val breadcrumb = RestoreMutationBreadcrumb("com.example.app", "Example", 900L)
        val destructiveCheckpoint = DataTaskCheckpoint(
            stage = DataTaskStage.RESTORING,
            completed = 1,
            total = 2,
            activeItemOrdinal = 0,
            activeItemLabel = "Example",
            destructiveStarted = true,
            restoreMutationBreadcrumb = breadcrumb,
            recordedAtEpochMs = 1_000L,
        )
        val export = StoredDataTaskDetail.AppExport(
            requestedFormat = BundleFormat.APK,
            destination = StoredDataDestination.Downloads,
            namingLabel = "Example",
            publicationPolicy = DataTaskPublicationPolicy.PUBLIC_DOCUMENT,
            deterministicStagingIdentity = "export-stage",
        )

        assertTrue(
            dataSyncTimeoutOutcome(DataTaskKind.ARCHIVE_BACKUP, backup, null) is
                    DataTaskRunOutcome.WaitingForAuthentication
        )
        assertTrue(
            dataSyncTimeoutOutcome(DataTaskKind.ARCHIVE_RESTORE, awaitingRestore, null) is
                    DataTaskRunOutcome.WaitingForSource
        )
        assertTrue(
            dataSyncTimeoutOutcome(DataTaskKind.ARCHIVE_RESTORE, durableRestore, null) is
                    DataTaskRunOutcome.WaitingForAuthentication
        )
        assertEquals(
            DataTaskRunOutcome.InterruptedReview(DataTaskResultCode("SERVICE_TIMEOUT"), breadcrumb),
            dataSyncTimeoutOutcome(
                DataTaskKind.ARCHIVE_RESTORE,
                durableRestore.copy(mutationBreadcrumb = breadcrumb),
                destructiveCheckpoint,
            ),
        )
        assertEquals(
            DataTaskRunOutcome.OwnershipLost,
            dataSyncTimeoutOutcome(DataTaskKind.APP_EXPORT, export, null),
        )
    }

    @Test
    fun `ordinary drain infrastructure failure stops the service generation`() = runTest {
        var stops = 0
        val coordinator = coordinator(
            recoverClaims = { _, _ -> error("room unavailable") },
        )

        coordinator.wake { stops += 1 }
        advanceUntilIdle()

        assertEquals(1, stops)
    }

    @Test
    fun `recovery always receives shared registry liveness lookup`() = runTest {
        val registry = DataTaskOwnerRegistry()
        registry.registerProvisional(CLAIM)
        registry.bindTask(TASK_1, CLAIM)
        var observed = false
        val coordinator = coordinator(
            registry = registry,
            recoverClaims = { _, isLive -> observed = isLive(TASK_1, CLAIM) },
        )

        coordinator.wake {}
        advanceUntilIdle()

        assertTrue(observed)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        registry: DataTaskOwnerRegistry = DataTaskOwnerRegistry(),
        awaitLegacyDrain: suspend () -> Unit = {},
        awaitLaunchSweep: suspend () -> Boolean = { true },
        recoverClaims: suspend (String, (UUID, String) -> Boolean) -> Unit = { _, _ -> },
        claimNext: suspend (String, String) -> DataSyncClaim? = { _, _ -> null },
        executeClaim: suspend (DataSyncClaim, DataTaskCheckpointSink) -> DataTaskRunOutcome = { _, _ ->
            completed()
        },
        persistTimeoutInterruption: suspend (DataSyncClaim) -> Boolean = { true },
        timeoutOutcome: suspend (DataSyncClaim) -> DataTaskRunOutcome = {
            DataTaskRunOutcome.Cancelled
        },
        persistOutcome: suspend (DataSyncClaim, DataTaskRunOutcome) -> Unit = { _, _ -> },
        cleanupClaim: suspend (DataSyncClaim) -> Unit = {},
    ) = DataSyncCoordinator(
        dispatcher = StandardTestDispatcher(testScheduler),
        ownerRegistry = registry,
        awaitLegacyDrain = awaitLegacyDrain,
        awaitLaunchSweep = awaitLaunchSweep,
        recoverClaims = recoverClaims,
        claimNext = claimNext,
        executeClaim = executeClaim,
        persistTimeoutInterruption = persistTimeoutInterruption,
        timeoutOutcome = timeoutOutcome,
        persistOutcome = persistOutcome,
        cleanupClaim = cleanupClaim,
        checkpointSink = { DataTaskCheckpointSink { com.valhalla.thor.data.backup.job.DataTaskSinkWrite.APPLIED } },
        finishDrainIfEmpty = { onEmpty -> onEmpty(); true },
        sessionToken = "session",
        claimTokenFactory = { "claim-${nextClaim++}" },
        nowMs = { 1_000L },
    )

    private fun restoreDetail(source: StoredRestoreSource) =
        StoredDataTaskDetail.ArchiveRestore(
            expectedPackageName = "com.example.app",
            dataClassIds = listOf("apk"),
            restoreObb = false,
            source = source,
            mutationBreadcrumb = null,
            deterministicStagingIdentity = "restore-stage",
        )

    private fun claim(number: Int) = DataSyncClaim(
        taskId = if (number == 1) TASK_1 else TASK_2,
        claimToken = "unbound-$number",
    )

    private fun completed() = DataTaskRunOutcome.ItemCompleted(
        DataTaskItemResult(
            terminalState = DataTaskItemTerminalState.SUCCEEDED,
            resultCode = DataTaskResultCode("DONE"),
            warnings = emptyList(),
            outputs = emptyList(),
            finishedAtEpochMs = 1_000L,
        )
    )

    private companion object {
        val TASK_1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000091")
        val TASK_2: UUID = UUID.fromString("00000000-0000-0000-0000-000000000092")
        const val CLAIM = "claim-live"
        var nextClaim = 0
    }
}
