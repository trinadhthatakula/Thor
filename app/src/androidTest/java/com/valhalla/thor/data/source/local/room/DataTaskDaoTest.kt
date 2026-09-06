// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskInterruption
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemState
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.NewDataTaskOutput
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.model.SharePrepareFormat
import com.valhalla.thor.domain.model.StoredDataDestination
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.model.StoredRestoreSource
import com.valhalla.thor.domain.model.privateRestoreSourceRelativePath
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataTaskDaoTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase
    private lateinit var dao: DataTaskDao

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun claimOrderUsesSequenceThenUuid() = runBlocking {
        val highUuid = "00000000-0000-0000-0000-000000000002"
        val lowUuid = "00000000-0000-0000-0000-000000000001"
        val first = dao.insertTask(newExportTask(highUuid, createdAtEpochMs = 1_000L))
        val second = dao.insertTask(newExportTask(lowUuid, createdAtEpochMs = 1_000L))

        assertTrue(first.queueSequence < second.queueSequence)
        assertEquals(
            UUID.fromString(highUuid),
            dao.claimOldestRunnableTask("session-1", "claim-1", 2_000L, 3_000L)?.taskId,
        )
        assertEquals(
            UUID.fromString(lowUuid),
            dao.claimOldestRunnableTask("session-1", "claim-2", 2_000L, 3_000L)?.taskId,
        )
    }

    @Test
    fun concurrentClaimersHaveOneWinner() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        val start = CompletableDeferred<Unit>()

        val claims = coroutineScope {
            listOf("claim-a", "claim-b").map { claimToken ->
                async {
                    start.await()
                    dao.claimOldestRunnableTask("session", claimToken, 2_000L, 3_000L)
                }
            }.also { start.complete(Unit) }.awaitAll()
        }

        assertEquals(1, claims.count { it != null })
        assertEquals(1, claims.mapNotNull { it?.claimToken }.distinct().size)
    }

    @Test
    fun staleTokenCannotCheckpointOrComplete() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        assertNotNull(dao.claimOldestRunnableTask("session", "task-claim", 2_000L, 3_000L))
        assertNotNull(
            dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim", 2_100L, 3_100L),
        )

        assertFalse(
            dao.checkpointClaimedTask(
                TASK_1,
                "stale-task-claim",
                0,
                "item-claim",
                checkpoint(),
                3_200L,
                transactionNowMs = { 2_200L },
            ),
        )
        assertFalse(
            dao.completeClaimedItem(
                TASK_1,
                0,
                "task-claim",
                "stale-item-claim",
                successfulItemResult(2_300L),
            ),
        )
    }

    @Test
    fun blockedTaskDoesNotBlockLaterRunnableTask() = runBlocking {
        dao.insertTask(newExportTask(TASK_1, initialState = DataTaskState.WAITING_FOR_AUTH))
        dao.insertTask(newExportTask(TASK_2))

        val claimed = dao.claimOldestRunnableTask("session", "claim", 2_000L, 3_000L)

        assertEquals(UUID.fromString(TASK_2), claimed?.taskId)
    }

    @Test
    fun cancellationAndCompletionHaveOneDurableWinner() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        dao.claimOldestRunnableTask("session", "task-claim-1", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "task-claim-1", "item-claim-1", 2_100L, 3_100L)

        assertTrue(
            dao.settleClaimedTask(
                TASK_1,
                "task-claim-1",
                0,
                "item-claim-1",
                DataTaskRunOutcome.ItemCompleted(successfulItemResult(2_200L)),
                3_200L,
            ),
        )
        val completedFirst = dao.requestCancellation(TASK_1, 2_300L)
        assertTrue(completedFirst is DataTaskCancellationDecision.AlreadyTerminal)
        assertEquals(
            DataTaskState.READY,
            (completedFirst as DataTaskCancellationDecision.AlreadyTerminal).snapshot.state,
        )

        dao.insertTask(newExportTask(TASK_2))
        dao.claimOldestRunnableTask("session", "task-claim-2", 3_000L, 4_000L)
        dao.claimNextPendingItem(TASK_2, "task-claim-2", "item-claim-2", 3_100L, 4_100L)

        val cancellationFirst = dao.requestCancellation(TASK_2, 3_200L)
        assertTrue(cancellationFirst is DataTaskCancellationDecision.InterruptActive)
        assertFalse(
            dao.completeClaimedItem(
                TASK_2,
                0,
                "task-claim-2",
                "item-claim-2",
                successfulItemResult(3_300L),
            ),
        )
        val stillCancelled = dao.requestCancellation(TASK_2, 3_400L)
        assertEquals(
            DataTaskState.CANCEL_REQUESTED,
            (stillCancelled as DataTaskCancellationDecision.InterruptActive).snapshot.state,
        )
    }

    @Test
    fun settledItemKeepsTaskRunnableWhenAnotherItemIsPending() = runBlocking {
        dao.insertTask(newExportTask(TASK_1, itemCount = 2))
        dao.claimOldestRunnableTask("session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim-0", 2_100L, 3_100L)

        assertTrue(
            dao.settleClaimedTask(
                TASK_1,
                "task-claim",
                0,
                "item-claim-0",
                DataTaskRunOutcome.ItemCompleted(successfulItemResult(2_200L)),
                3_200L,
            ),
        )
        assertEquals(DataTaskState.QUEUED, dao.loadTask(TASK_1)!!.state)
        assertNull(dao.claimNextPendingItem(TASK_1, "task-claim", "stale-item-claim", 3_300L, 4_300L))
        assertNotNull(dao.claimOldestRunnableTask("session", "next-task-claim", 3_300L, 4_300L))
        assertEquals(
            1,
            dao.claimNextPendingItem(TASK_1, "next-task-claim", "item-claim-1", 3_400L, 4_400L)?.ordinal,
        )
    }

    @Test
    fun activeCancellationCancelsPendingItemsImmediately() = runBlocking {
        dao.insertTask(newExportTask(TASK_1, itemCount = 2))
        dao.claimOldestRunnableTask("session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim", 2_100L, 3_100L)

        val decision = dao.requestCancellation(TASK_1, 2_200L)

        assertTrue(decision is DataTaskCancellationDecision.InterruptActive)
        val snapshot = (decision as DataTaskCancellationDecision.InterruptActive).snapshot
        assertEquals(DataTaskItemState.RUNNING, snapshot.items[0].state)
        assertEquals(DataTaskItemState.CANCELLED, snapshot.items[1].state)
    }

    @Test
    fun interruptedCancellationRecoversAsTerminalCancellation() = runBlocking {
        dao.insertTask(newExportTask(TASK_1, itemCount = 2))
        dao.claimOldestRunnableTask("old-session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim", 2_100L, 3_100L)
        dao.requestCancellation(TASK_1, 2_200L)

        val recovery = dao.recoverClaims("new-session", 3_000L) { _, _ -> false }.single()
        assertEquals(UUID.fromString(TASK_1), recovery.taskId)
        assertEquals(DataTaskKind.APP_EXPORT, recovery.kind)
        assertEquals(0, recovery.interruptedItemOrdinal)
        assertEquals("old-session", recovery.previousServiceSessionToken)
        assertFalse(recovery.destructiveStarted)
        assertNull(recovery.restoreMutationBreadcrumb)
        assertEquals(DataTaskRecovery.Cancelled, recovery.recovery)
        reopenDatabase()

        val recoveredTask = loadPersistedTask()
        assertEquals(DataTaskState.CANCELLED.name, recoveredTask.state)
        assertNull(recoveredTask.serviceSessionToken)
        assertNull(recoveredTask.claimToken)
        assertNull(recoveredTask.claimLeaseExpiresAtEpochMs)
        val recoveredItem = loadPersistedItem()
        assertEquals(DataTaskItemState.CANCELLED.name, recoveredItem.state)
        assertNull(recoveredItem.claimToken)
        assertNull(recoveredItem.claimLeaseExpiresAtEpochMs)
        val decision = dao.requestCancellation(TASK_1, 3_100L)
        assertTrue(decision is DataTaskCancellationDecision.AlreadyTerminal)
        val snapshot = (decision as DataTaskCancellationDecision.AlreadyTerminal).snapshot
        assertEquals(DataTaskState.CANCELLED, snapshot.state)
        assertTrue(snapshot.items.all { it.state == DataTaskItemState.CANCELLED })
    }

    @Test
    fun cancellationAtomicallyDefeatsEveryStaleNonCancellationOutcome() = runBlocking {
        val outcomes = listOf(
            DataTaskRunOutcome.ItemCompleted(successfulItemResult(2_300L)),
            DataTaskRunOutcome.ItemCompleted(
                successfulItemResult(2_300L).copy(
                    terminalState = DataTaskItemTerminalState.FAILED,
                    resultCode = DataTaskResultCode("STALE_FAILURE"),
                    outputs = emptyList(),
                )
            ),
            DataTaskRunOutcome.WaitingForAuthentication(DataTaskResultCode("AUTH_REQUIRED")),
            DataTaskRunOutcome.WaitingForSource(DataTaskResultCode("SOURCE_REQUIRED")),
            DataTaskRunOutcome.TaskFailed(DataTaskResultCode("STALE_TASK_FAILURE")),
            DataTaskRunOutcome.OwnershipLost,
        )

        outcomes.forEachIndexed { index, outcome ->
            val taskId = taskId(100 + index)
            dao.insertTask(newExportTask(taskId))
            dao.claimOldestRunnableTask("session", "task-claim-$index", 2_000L, 3_000L)
            dao.claimNextPendingItem(
                taskId,
                "task-claim-$index",
                "item-claim-$index",
                2_100L,
                3_100L,
            )
            dao.requestCancellation(taskId, 2_200L)

            assertTrue(
                dao.settleClaimedTask(
                    taskId,
                    "task-claim-$index",
                    0,
                    "item-claim-$index",
                    outcome,
                    2_400L,
                ),
            )

            val task = loadPersistedTask(taskId)
            assertEquals(DataTaskState.CANCELLED.name, task.state)
            assertEquals(DataTaskInterruption.NONE.name, task.interruption)
            assertEquals("CANCELLED", task.resultCode)
            assertNull(task.serviceSessionToken)
            assertNull(task.claimToken)
            assertNull(task.claimLeaseExpiresAtEpochMs)
            val item = loadPersistedItem(taskId)
            assertEquals(DataTaskItemState.CANCELLED.name, item.state)
            assertNull(item.claimToken)
            assertNull(item.claimLeaseExpiresAtEpochMs)
            val snapshot = requireNotNull(dao.loadTask(taskId))
            assertEquals(0, snapshot.completed)
            assertEquals(1, snapshot.total)
            assertEquals(DataTaskResultCode("CANCELLED"), snapshot.items.single().resultCode)
            assertTrue(snapshot.outputs.isEmpty())
        }
    }

    @Test
    fun cancellationStillRejectsStaleNormalOutcomeTokens() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        dao.claimOldestRunnableTask("session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim", 2_100L, 3_100L)
        dao.requestCancellation(TASK_1, 2_200L)

        assertFalse(
            dao.settleClaimedTask(
                TASK_1,
                "stale-task-claim",
                0,
                "item-claim",
                DataTaskRunOutcome.ItemCompleted(successfulItemResult(2_300L)),
                2_400L,
            ),
        )

        val task = loadPersistedTask()
        assertEquals(DataTaskState.CANCEL_REQUESTED.name, task.state)
        assertEquals("task-claim", task.claimToken)
        val item = loadPersistedItem()
        assertEquals(DataTaskItemState.RUNNING.name, item.state)
        assertEquals("item-claim", item.claimToken)
    }

    @Test
    fun destructiveCancellationDefeatsStaleSuccessWithInterruptedReview() = runBlocking {
        val breadcrumb = RestoreMutationBreadcrumb(
            packageName = "com.example.app",
            appLabel = "Example",
            startedAtEpochMs = 2_150L,
        )
        dao.insertTask(newRestoreTask())
        dao.claimOldestRunnableTask("session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim", 2_100L, 3_100L)
        assertTrue(
            dao.checkpointClaimedTask(
                TASK_1,
                "task-claim",
                0,
                "item-claim",
                checkpoint().copy(
                    destructiveStarted = true,
                    restoreMutationBreadcrumb = breadcrumb,
                ),
                3_200L,
                transactionNowMs = { 2_200L },
            ),
        )
        dao.requestCancellation(TASK_1, 2_300L)

        assertTrue(
            dao.settleClaimedTask(
                TASK_1,
                "task-claim",
                0,
                "item-claim",
                DataTaskRunOutcome.ItemCompleted(successfulItemResult(2_350L)),
                2_400L,
            ),
        )
        reopenDatabase()

        val task = loadPersistedTask()
        assertEquals(DataTaskState.INTERRUPTED_REVIEW.name, task.state)
        assertEquals(DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW.name, task.interruption)
        assertEquals("DESTRUCTIVE_RESTORE_REVIEW", task.resultCode)
        assertNull(task.serviceSessionToken)
        assertNull(task.claimToken)
        assertNull(task.claimLeaseExpiresAtEpochMs)
        val item = loadPersistedItem()
        assertEquals(DataTaskItemState.RUNNING.name, item.state)
        assertNull(item.claimToken)
        assertNull(item.claimLeaseExpiresAtEpochMs)
        assertEquals(breadcrumb, loadPersistedBreadcrumb())
        val snapshot = requireNotNull(dao.loadTask(TASK_1))
        assertNull(snapshot.terminalAtEpochMs)
        assertTrue(snapshot.outputs.isEmpty())
        assertEquals(
            StoredRestoreSource.PrivateCopy(privateRestoreSourceRelativePath(UUID.fromString(TASK_1))),
            (snapshot.detail as StoredDataTaskDetail.ArchiveRestore).source,
        )
    }

    @Test
    fun destructiveCancellationWithoutBreadcrumbFailsClosedAfterStaleOutcome() = runBlocking {
        dao.insertTask(newRestoreTask())
        dao.claimOldestRunnableTask("session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim", 2_100L, 3_100L)
        assertTrue(
            dao.checkpointClaimedTask(
                TASK_1,
                "task-claim",
                0,
                "item-claim",
                checkpoint().copy(destructiveStarted = true),
                3_200L,
                transactionNowMs = { 2_200L },
            ),
        )
        dao.requestCancellation(TASK_1, 2_300L)

        assertTrue(
            dao.settleClaimedTask(
                TASK_1,
                "task-claim",
                0,
                "item-claim",
                DataTaskRunOutcome.WaitingForAuthentication(DataTaskResultCode("AUTH_REQUIRED")),
                2_400L,
            ),
        )
        reopenDatabase()

        val task = loadPersistedTask()
        assertEquals(DataTaskState.INTERRUPTED_REVIEW.name, task.state)
        assertEquals(DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW.name, task.interruption)
        assertEquals("RECOVERY_BREADCRUMB_MISSING", task.resultCode)
        assertNull(task.claimToken)
        val item = loadPersistedItem()
        assertEquals(DataTaskItemState.RUNNING.name, item.state)
        assertNull(item.claimToken)
        val snapshot = requireNotNull(dao.loadTask(TASK_1))
        assertNull(snapshot.terminalAtEpochMs)
        assertNull(snapshot.items[0].resultCode)
        assertEquals(
            StoredRestoreSource.PrivateCopy(privateRestoreSourceRelativePath(UUID.fromString(TASK_1))),
            (snapshot.detail as StoredDataTaskDetail.ArchiveRestore).source,
        )
    }

    @Test
    fun destructiveCancellationAllowsClaimOwnerToSettleInterruptedReview() = runBlocking {
        val breadcrumb = RestoreMutationBreadcrumb(
            packageName = "com.example.app",
            appLabel = "Example",
            startedAtEpochMs = 2_150L,
        )
        dao.insertTask(newRestoreTask())
        dao.claimOldestRunnableTask("session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim", 2_100L, 3_100L)
        assertTrue(
            dao.checkpointClaimedTask(
                TASK_1,
                "task-claim",
                0,
                "item-claim",
                checkpoint().copy(
                    destructiveStarted = true,
                    restoreMutationBreadcrumb = breadcrumb,
                ),
                3_200L,
                transactionNowMs = { 2_200L },
            ),
        )
        dao.requestCancellation(TASK_1, 2_300L)

        assertTrue(
            dao.settleClaimedTask(
                TASK_1,
                "task-claim",
                0,
                "item-claim",
                DataTaskRunOutcome.InterruptedReview(
                    resultCode = DataTaskResultCode("EXPLICIT_RESTORE_REVIEW"),
                    breadcrumb = breadcrumb,
                ),
                2_400L,
            ),
        )
        reopenDatabase()

        val task = loadPersistedTask()
        assertEquals(DataTaskState.INTERRUPTED_REVIEW.name, task.state)
        assertEquals(DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW.name, task.interruption)
        assertEquals("EXPLICIT_RESTORE_REVIEW", task.resultCode)
        assertNull(task.serviceSessionToken)
        assertNull(task.claimToken)
        assertNull(task.claimLeaseExpiresAtEpochMs)
        val activeItem = loadPersistedItem()
        assertEquals(DataTaskItemState.RUNNING.name, activeItem.state)
        assertNull(activeItem.claimToken)
        assertNull(activeItem.claimLeaseExpiresAtEpochMs)
        assertEquals(breadcrumb, loadPersistedBreadcrumb())
    }

    @Test
    fun destructiveCancellationRejectsStaleInterruptedReviewSettlement() = runBlocking {
        val breadcrumb = RestoreMutationBreadcrumb(
            packageName = "com.example.app",
            appLabel = "Example",
            startedAtEpochMs = 2_150L,
        )
        dao.insertTask(newRestoreTask())
        dao.claimOldestRunnableTask("session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim", 2_100L, 3_100L)
        assertTrue(
            dao.checkpointClaimedTask(
                TASK_1,
                "task-claim",
                0,
                "item-claim",
                checkpoint().copy(
                    destructiveStarted = true,
                    restoreMutationBreadcrumb = breadcrumb,
                ),
                3_200L,
                transactionNowMs = { 2_200L },
            ),
        )
        dao.requestCancellation(TASK_1, 2_300L)

        assertFalse(
            dao.settleClaimedTask(
                TASK_1,
                "stale-task-claim",
                0,
                "item-claim",
                DataTaskRunOutcome.InterruptedReview(
                    resultCode = DataTaskResultCode("DESTRUCTIVE_RESTORE_REVIEW"),
                    breadcrumb = breadcrumb,
                ),
                2_400L,
            ),
        )
        val decision = dao.requestCancellation(TASK_1, 2_500L)
        assertEquals(
            DataTaskState.CANCEL_REQUESTED,
            (decision as DataTaskCancellationDecision.InterruptActive).snapshot.state,
        )
    }

    @Test
    fun destructiveCancellationRecoveryPersistsInterruptedReview() = runBlocking {
        val breadcrumb = RestoreMutationBreadcrumb(
            packageName = "com.example.app",
            appLabel = "Example",
            startedAtEpochMs = 2_150L,
        )
        dao.insertTask(newRestoreTask())
        dao.claimOldestRunnableTask("old-session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim", 2_100L, 3_100L)
        assertTrue(
            dao.checkpointClaimedTask(
                TASK_1,
                "task-claim",
                0,
                "item-claim",
                checkpoint().copy(
                    destructiveStarted = true,
                    restoreMutationBreadcrumb = breadcrumb,
                ),
                3_200L,
                transactionNowMs = { 2_200L },
            ),
        )
        dao.requestCancellation(TASK_1, 2_300L)

        val recovery = dao.recoverClaims("new-session", 3_200L) { _, _ -> false }.single()
        assertTrue(recovery.recovery is DataTaskRecovery.InterruptedReview)
        assertEquals(breadcrumb, recovery.restoreMutationBreadcrumb)
        reopenDatabase()

        val task = loadPersistedTask()
        assertEquals(DataTaskState.INTERRUPTED_REVIEW.name, task.state)
        assertEquals(DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW.name, task.interruption)
        assertNull(task.serviceSessionToken)
        assertNull(task.claimToken)
        assertNull(task.claimLeaseExpiresAtEpochMs)
        val activeItem = loadPersistedItem()
        assertEquals(DataTaskItemState.RUNNING.name, activeItem.state)
        assertNull(activeItem.claimToken)
        assertNull(activeItem.claimLeaseExpiresAtEpochMs)
        assertEquals(breadcrumb, loadPersistedBreadcrumb())
    }

    @Test
    fun nonDestructiveRestoreCancellationRecoveryRemainsCancelled() = runBlocking {
        val restoreTask = newRestoreTask()
        dao.insertTask(
            restoreTask.copy(
                initialState = DataTaskState.STAGING_SOURCE,
                detail = (restoreTask.detail as StoredDataTaskDetail.ArchiveRestore).copy(
                    source = StoredRestoreSource.AwaitingTransientGrant,
                ),
            ),
        )
        dao.claimOldestRunnableTask("old-session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim", 2_100L, 3_100L)
        dao.requestCancellation(TASK_1, 2_200L)

        val recovery = dao.recoverClaims("new-session", 3_000L) { _, _ -> false }.single()
        assertEquals(UUID.fromString(TASK_1), recovery.taskId)
        assertEquals(DataTaskKind.ARCHIVE_RESTORE, recovery.kind)
        assertEquals(0, recovery.interruptedItemOrdinal)
        assertEquals("old-session", recovery.previousServiceSessionToken)
        assertFalse(recovery.destructiveStarted)
        assertNull(recovery.restoreMutationBreadcrumb)
        assertEquals(DataTaskRecovery.Cancelled, recovery.recovery)
        reopenDatabase()

        val recoveredTask = loadPersistedTask()
        assertEquals(DataTaskState.CANCELLED.name, recoveredTask.state)
        assertEquals(
            listOf(UUID.fromString(TASK_1)),
            dao.uncommittedRestoreSourceCleanupTaskIds(),
        )
        assertNull(recoveredTask.serviceSessionToken)
        assertNull(recoveredTask.claimToken)
        assertNull(recoveredTask.claimLeaseExpiresAtEpochMs)
        val recoveredItem = loadPersistedItem()
        assertEquals(DataTaskItemState.CANCELLED.name, recoveredItem.state)
        assertNull(recoveredItem.claimToken)
        assertNull(recoveredItem.claimLeaseExpiresAtEpochMs)
        val decision = dao.requestCancellation(TASK_1, 3_100L)
        val snapshot = (decision as DataTaskCancellationDecision.AlreadyTerminal).snapshot
        assertEquals(DataTaskState.CANCELLED, snapshot.state)
        assertTrue(snapshot.items.all { it.state == DataTaskItemState.CANCELLED })
    }

    @Test
    fun destructiveRecoveryWithoutBreadcrumbEntersReviewAndPreservesSource() = runBlocking {
        dao.insertTask(newRestoreTask())
        dao.claimOldestRunnableTask("old-session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim", 2_100L, 3_100L)
        assertTrue(
            dao.checkpointClaimedTask(
                TASK_1,
                "task-claim",
                0,
                "item-claim",
                checkpoint().copy(destructiveStarted = true),
                3_200L,
                transactionNowMs = { 2_200L },
            ),
        )

        val recovered = dao.recoverClaims("new-session", 3_200L) { _, _ -> false }

        assertEquals(
            DataTaskRecovery.InterruptedReview(
                breadcrumb = null,
                resultCode = DataTaskResultCode("RECOVERY_BREADCRUMB_MISSING"),
            ),
            recovered.single().recovery,
        )
        reopenDatabase()
        val task = loadPersistedTask()
        assertEquals(DataTaskState.INTERRUPTED_REVIEW.name, task.state)
        assertEquals(DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW.name, task.interruption)
        assertEquals("RECOVERY_BREADCRUMB_MISSING", task.resultCode)
        assertNull(task.serviceSessionToken)
        assertNull(task.claimToken)
        assertNull(task.claimLeaseExpiresAtEpochMs)
        val activeItem = loadPersistedItem()
        assertEquals(DataTaskItemState.RUNNING.name, activeItem.state)
        assertNull(activeItem.claimToken)
        assertNull(activeItem.claimLeaseExpiresAtEpochMs)
        val snapshot = requireNotNull(dao.loadTask(TASK_1))
        assertNull(snapshot.terminalAtEpochMs)
        assertNull(snapshot.items[0].resultCode)
        assertNull(snapshot.items[0].finishedAtEpochMs)
        assertEquals(
            StoredRestoreSource.PrivateCopy(privateRestoreSourceRelativePath(UUID.fromString(TASK_1))),
            (snapshot.detail as StoredDataTaskDetail.ArchiveRestore).source,
        )
    }

    @Test
    fun destructiveRecoveryWithBreadcrumbPersistsReviewAcrossReopen() = runBlocking {
        val breadcrumb = RestoreMutationBreadcrumb(
            packageName = "com.example.app",
            appLabel = "Example",
            startedAtEpochMs = 2_150L,
        )
        dao.insertTask(newRestoreTask())
        dao.claimOldestRunnableTask("old-session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim", 2_100L, 3_100L)
        assertTrue(
            dao.checkpointClaimedTask(
                TASK_1,
                "task-claim",
                0,
                "item-claim",
                checkpoint().copy(
                    destructiveStarted = true,
                    restoreMutationBreadcrumb = breadcrumb,
                ),
                3_200L,
                transactionNowMs = { 2_200L },
            ),
        )

        val recovery = dao.recoverClaims("new-session", 3_200L) { _, _ -> false }.single()
        assertTrue(recovery.recovery is DataTaskRecovery.InterruptedReview)
        assertEquals(breadcrumb, recovery.restoreMutationBreadcrumb)
        reopenDatabase()

        val task = loadPersistedTask()
        assertEquals(DataTaskState.INTERRUPTED_REVIEW.name, task.state)
        assertEquals(DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW.name, task.interruption)
        assertEquals("DESTRUCTIVE_RESTORE_REVIEW", task.resultCode)
        assertNull(task.serviceSessionToken)
        assertNull(task.claimToken)
        assertNull(task.claimLeaseExpiresAtEpochMs)
        val activeItem = loadPersistedItem()
        assertEquals(DataTaskItemState.RUNNING.name, activeItem.state)
        assertNull(activeItem.claimToken)
        assertNull(activeItem.claimLeaseExpiresAtEpochMs)
        assertEquals(breadcrumb, loadPersistedBreadcrumb())
        assertNull(
            dao.claimOldestRunnableTask(
                "newer-session",
                "newer-claim",
                3_300L,
                4_300L,
            ),
        )
    }

    @Test
    fun explicitResumeAfterDestructiveRecoveryRearmsTheInterruptedItem() = runBlocking {
        val breadcrumb = RestoreMutationBreadcrumb(
            packageName = "com.example.app",
            appLabel = "Example",
            startedAtEpochMs = 2_150L,
        )
        dao.insertTask(newRestoreTask())
        dao.claimOldestRunnableTask("old-session", "old-task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "old-task-claim", "old-item-claim", 2_100L, 3_100L)
        assertTrue(
            dao.checkpointClaimedTask(
                TASK_1,
                "old-task-claim",
                0,
                "old-item-claim",
                checkpoint().copy(
                    destructiveStarted = true,
                    restoreMutationBreadcrumb = breadcrumb,
                ),
                3_200L,
                transactionNowMs = { 2_200L },
            ),
        )
        dao.recoverClaims("new-session", 3_200L) { _, _ -> false }

        assertTrue(
            dao.resumeFromUserAction(
                taskId = TASK_1,
                expectedState = DataTaskState.INTERRUPTED_REVIEW,
                expectedInterruption = DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW,
                nowMs = 3_300L,
            ),
        )
        val claim = requireNotNull(
            dao.claimOldestRunnableWork(
                sessionToken = "new-session",
                taskClaimToken = "new-task-claim",
                itemClaimToken = "new-item-claim",
                nowMs = 3_400L,
                leaseUntilMs = 4_400L,
            ),
        )

        assertEquals(UUID.fromString(TASK_1), claim.task.taskId)
        assertEquals(0, claim.item.ordinal)
        val checkpoint = requireNotNull(claim.task.lastCheckpoint)
        assertFalse(checkpoint.destructiveStarted)
        assertNull(checkpoint.restoreMutationBreadcrumb)
    }

    @Test
    fun priorSessionClaimWithLiveLocalOwnerIsNotRecovered() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        dao.claimOldestRunnableTask("prior-session", "prior-claim", 2_000L, 3_000L)

        val recovery = dao.recoverClaims("current-session", 4_000L) { taskId, claimToken ->
            taskId == TASK_1 && claimToken == "prior-claim"
        }

        assertTrue(recovery.isEmpty())
        val task = loadPersistedTask()
        assertEquals(DataTaskState.RUNNING.name, task.state)
        assertEquals("prior-session", task.serviceSessionToken)
        assertEquals("prior-claim", task.claimToken)
    }

    @Test
    fun priorSessionClaimWithoutLocalOwnerUsesOperationSpecificRecovery() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        dao.claimOldestRunnableTask("prior-session", "prior-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "prior-claim", "prior-item-claim", 2_100L, 3_100L)

        val recovery = dao.recoverClaims("current-session", 2_500L) { _, _ -> false }.single()

        assertEquals(DataTaskRecovery.Resume, recovery.recovery)
        assertEquals("prior-session", recovery.previousServiceSessionToken)
        assertEquals(
            UUID.fromString(TASK_1),
            dao.claimOldestRunnableTask(
                "current-session",
                "current-claim",
                2_600L,
                3_600L,
            )?.taskId,
        )
    }

    @Test
    fun currentSessionExpiredClaimWithoutLocalOwnerIsRecovered() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        dao.claimOldestRunnableTask("current-session", "expired-claim", 2_000L, 3_000L)

        val recovery = dao.recoverClaims("current-session", 4_000L) { _, _ -> false }.single()

        assertEquals(DataTaskRecovery.Resume, recovery.recovery)
        assertEquals("current-session", recovery.previousServiceSessionToken)
        assertEquals(DataTaskState.QUEUED.name, loadPersistedTask().state)
    }

    @Test
    fun currentSessionUnexpiredClaimWithoutLocalOwnerIsNotRecovered() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        dao.claimOldestRunnableTask("current-session", "unexpired-claim", 2_000L, 3_000L)

        val recovery = dao.recoverClaims("current-session", 2_500L) { _, _ -> false }

        assertTrue(recovery.isEmpty())
        val task = loadPersistedTask()
        assertEquals(DataTaskState.RUNNING.name, task.state)
        assertEquals("current-session", task.serviceSessionToken)
        assertEquals("unexpired-claim", task.claimToken)
    }

    @Test
    fun currentSessionExpiredClaimWithLiveLocalOwnerIsNotRecovered() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        dao.claimOldestRunnableTask("current-session", "live-claim", 2_000L, 3_000L)

        val recovery = dao.recoverClaims("current-session", 4_000L) { taskId, claimToken ->
            taskId == TASK_1 && claimToken == "live-claim"
        }

        assertTrue(recovery.isEmpty())
        val task = loadPersistedTask()
        assertEquals(DataTaskState.RUNNING.name, task.state)
        assertEquals("current-session", task.serviceSessionToken)
        assertEquals("live-claim", task.claimToken)
    }

    @Test
    fun recoveryFailsClosedWhenOwnershipIdentifiersAreMissing() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        dao.insertTask(newExportTask(TASK_2))
        dao.claimOldestRunnableTask("prior-session", "claim-without-token", 2_000L, 3_000L)
        dao.claimOldestRunnableTask("prior-session", "claim-without-session", 2_000L, 3_000L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE data_tasks SET claim_token = NULL WHERE task_id = ?",
            arrayOf(TASK_1),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE data_tasks SET service_session_token = NULL WHERE task_id = ?",
            arrayOf(TASK_2),
        )
        val callbackInvoked = AtomicBoolean(false)

        val recovery = dao.recoverClaims("current-session", 4_000L) { _, _ ->
            callbackInvoked.set(true)
            false
        }

        assertTrue(recovery.isEmpty())
        assertFalse(callbackInvoked.get())
        val missingClaimToken = loadPersistedTask(TASK_1)
        assertEquals(DataTaskState.RUNNING.name, missingClaimToken.state)
        assertEquals("prior-session", missingClaimToken.serviceSessionToken)
        assertNull(missingClaimToken.claimToken)
        val missingSessionToken = loadPersistedTask(TASK_2)
        assertEquals(DataTaskState.RUNNING.name, missingSessionToken.state)
        assertNull(missingSessionToken.serviceSessionToken)
        assertEquals("claim-without-session", missingSessionToken.claimToken)
    }

    @Test
    fun publicLoadReturnsItemsAndOutputsInStableOrder() = runBlocking {
        dao.insertTask(newExportTask(TASK_1, itemCount = 2))
        insertOutput(
            taskId = TASK_1,
            outputId = "00000000-0000-0000-0000-000000000012",
            itemOrdinal = 1,
            displayName = "second-item.apk",
        )
        insertOutput(
            taskId = TASK_1,
            outputId = "00000000-0000-0000-0000-000000000011",
            itemOrdinal = 0,
            displayName = "later-id.apk",
        )
        insertOutput(
            taskId = TASK_1,
            outputId = "00000000-0000-0000-0000-000000000010",
            itemOrdinal = 0,
            displayName = "earlier-id.apk",
        )

        val snapshot = requireNotNull(dao.loadTask(TASK_1))

        assertEquals(listOf(0, 1), snapshot.items.map { it.ordinal })
        assertEquals(
            listOf(
                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                UUID.fromString("00000000-0000-0000-0000-000000000012"),
            ),
            snapshot.outputs.map { it.outputId },
        )
    }

    @Test
    fun observationReloadsAfterTaskDetailItemAndOutputChanges() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        val observed = Channel<DataTaskSnapshot?>(Channel.UNLIMITED)
        val observer = launch(Dispatchers.IO) {
            dao.observeTask(TASK_1).collect(observed::send)
        }

        try {
            assertEquals("package:com.example.app", nextObservation(observed)?.targetKey)

            inDatabaseTransaction {
                execSQL(
                    "UPDATE data_tasks SET target_key = ? WHERE task_id = ?",
                    arrayOf("package:changed", TASK_1),
                )
            }
            assertEquals("package:changed", nextObservation(observed)?.targetKey)

            inDatabaseTransaction {
                execSQL(
                    "UPDATE export_task_details SET naming_label = ? WHERE task_id = ?",
                    arrayOf("Changed detail", TASK_1),
                )
            }
            assertEquals(
                "Changed detail",
                (nextObservation(observed)?.detail as StoredDataTaskDetail.AppExport).namingLabel,
            )

            inDatabaseTransaction {
                execSQL(
                    "UPDATE data_task_items SET display_label = ? WHERE task_id = ? AND ordinal = 0",
                    arrayOf("Changed item", TASK_1),
                )
            }
            assertEquals("Changed item", nextObservation(observed)?.items?.single()?.displayLabel)

            insertOutput(
                taskId = TASK_1,
                outputId = "00000000-0000-0000-0000-000000000013",
                itemOrdinal = 0,
                displayName = "observed.apk",
            )
            assertEquals("observed.apk", nextObservation(observed)?.outputs?.single()?.displayName)
        } finally {
            observer.cancel()
            observed.cancel()
        }
    }

    @Test
    fun startBlockedCompareAndSetAcceptsOnlyExactUnownedRunnableRows() = runBlocking {
        val acceptedCases = listOf(
            DataTaskState.QUEUED to DataTaskState.START_BLOCKED,
            DataTaskState.STAGING_SOURCE to DataTaskState.START_BLOCKED_NOTIFICATION,
        )
        acceptedCases.forEachIndexed { index, (expected, blocked) ->
            val taskId = taskId(10 + index)
            val before = dao.insertTask(newExportTask(taskId, initialState = expected))

            assertTrue(dao.compareAndSetStartBlocked(taskId, expected, blocked, 2_000L + index))

            val after = requireNotNull(dao.loadTask(taskId))
            assertEquals(
                before.copy(state = blocked, updatedAtEpochMs = 2_000L + index),
                after,
            )
        }

        val staleTask = taskId(20)
        dao.insertTask(newExportTask(staleTask))
        assertFalse(
            dao.compareAndSetStartBlocked(
                staleTask,
                DataTaskState.STAGING_SOURCE,
                DataTaskState.START_BLOCKED,
                3_000L,
            ),
        )
        assertEquals(DataTaskState.QUEUED, dao.loadTask(staleTask)?.state)

        listOf(
            Ownership(serviceSessionToken = "session"),
            Ownership(claimToken = "claim"),
            Ownership(claimLeaseExpiresAtEpochMs = 9_000L),
            Ownership("session", "claim", 9_000L),
        ).forEachIndexed { index, ownership ->
            val taskId = taskId(30 + index)
            dao.insertTask(newExportTask(taskId))
            setOwnership(taskId, ownership)

            assertFalse(
                dao.compareAndSetStartBlocked(
                    taskId,
                    DataTaskState.QUEUED,
                    DataTaskState.START_BLOCKED,
                    4_000L,
                ),
            )
            assertEquals(DataTaskState.QUEUED.name, loadPersistedTask(taskId).state)
        }

        val cancelledTask = taskId(40)
        dao.insertTask(newExportTask(cancelledTask))
        inDatabaseTransaction {
            execSQL(
                "UPDATE data_tasks SET cancel_requested_at_epoch_ms = 1 WHERE task_id = ?",
                arrayOf(cancelledTask),
            )
        }
        assertFalse(
            dao.compareAndSetStartBlocked(
                cancelledTask,
                DataTaskState.QUEUED,
                DataTaskState.START_BLOCKED,
                4_100L,
            ),
        )

        val terminalTask = taskId(41)
        dao.insertTask(newExportTask(terminalTask))
        setTerminalState(terminalTask, DataTaskState.SUCCEEDED, 4_200L)
        assertFalse(
            dao.compareAndSetStartBlocked(
                terminalTask,
                DataTaskState.QUEUED,
                DataTaskState.START_BLOCKED,
                4_300L,
            ),
        )
        assertEquals(DataTaskState.SUCCEEDED, dao.loadTask(terminalTask)?.state)

        val invalidExpected = runCatching {
            dao.compareAndSetStartBlocked(
                staleTask,
                DataTaskState.RUNNING,
                DataTaskState.START_BLOCKED,
                4_400L,
            )
        }.exceptionOrNull()
        assertTrue(invalidExpected is IllegalArgumentException)
        val invalidTarget = runCatching {
            dao.compareAndSetStartBlocked(
                staleTask,
                DataTaskState.QUEUED,
                DataTaskState.FAILED,
                4_500L,
            )
        }.exceptionOrNull()
        assertTrue(invalidTarget is IllegalArgumentException)
    }

    @Test
    fun acknowledgementAcceptsOnlyTerminalStatesAndIsIdempotentWithoutChangingOutputs() =
        runBlocking {
            val terminalStates = listOf(
                DataTaskState.SUCCEEDED,
                DataTaskState.PARTIAL,
                DataTaskState.FAILED,
                DataTaskState.CANCELLED,
                DataTaskState.EXPIRED,
            )
            terminalStates.forEachIndexed { index, state ->
                val taskId = taskId(50 + index)
                dao.insertTask(newExportTask(taskId))
                setTerminalState(taskId, state, 5_000L + index)
                insertOutput(
                    taskId = taskId,
                    outputId = taskId(150 + index),
                    itemOrdinal = 0,
                    displayName = "kept-$index.apk",
                )

                val first = requireNotNull(dao.acknowledgeTerminalTask(taskId, 6_000L + index))
                val second = requireNotNull(dao.acknowledgeTerminalTask(taskId, 7_000L + index))

                assertEquals(6_000L + index, first.acknowledgedAtEpochMs)
                assertEquals(first, second)
                assertEquals("kept-$index.apk", second.outputs.single().displayName)
            }

            (DataTaskState.entries - terminalStates.toSet()).forEachIndexed { index, state ->
                val taskId = taskId(70 + index)
                dao.insertTask(newExportTask(taskId, initialState = state))

                assertNull(dao.acknowledgeTerminalTask(taskId, 8_000L + index))
                assertNull(dao.loadTask(taskId)?.acknowledgedAtEpochMs)
            }
            assertNull(dao.acknowledgeTerminalTask(taskId(999), 9_000L))
        }

    @Test
    fun terminalSettlementsRecordTheTwentyFourHourRetentionDeadline() = runBlocking {
        val unclaimedCancellation = taskId(90)
        dao.insertTask(newExportTask(unclaimedCancellation))
        dao.requestCancellation(unclaimedCancellation, 10_000L)
        assertTerminalRetention(unclaimedCancellation, DataTaskState.CANCELLED, 10_000L)

        val claimedFailure = taskId(91)
        dao.insertTask(newExportTask(claimedFailure))
        dao.claimOldestRunnableTask("session", "failure-task", 11_000L, 12_000L)
        dao.claimNextPendingItem(claimedFailure, "failure-task", "failure-item", 11_100L, 12_100L)
        assertTrue(
            dao.settleClaimedTask(
                claimedFailure,
                "failure-task",
                0,
                "failure-item",
                DataTaskRunOutcome.TaskFailed(DataTaskResultCode("FAILED")),
                11_200L,
            ),
        )
        assertTerminalRetention(claimedFailure, DataTaskState.FAILED, 11_200L)

        val successful = taskId(92)
        dao.insertTask(newExportTask(successful))
        dao.claimOldestRunnableTask("session", "success-task", 12_000L, 13_000L)
        dao.claimNextPendingItem(successful, "success-task", "success-item", 12_100L, 13_100L)
        assertTrue(
            dao.settleClaimedTask(
                successful,
                "success-task",
                0,
                "success-item",
                DataTaskRunOutcome.ItemCompleted(
                    successfulItemResult(12_200L).copy(outputs = emptyList()),
                ),
                12_200L,
            ),
        )
        assertTerminalRetention(successful, DataTaskState.SUCCEEDED, 12_200L)

        val expiring = taskId(93)
        dao.insertTask(newExportTask(expiring).copy(
            kind = DataTaskKind.SHARE_PREPARE,
            targetKey = "share:$expiring",
            detail = StoredDataTaskDetail.SharePrepare(
                requestedFormat = SharePrepareFormat.AUTO,
                publicationPolicy = DataTaskPublicationPolicy.PRIVATE_SHARE_WITH_24_HOUR_EXPIRY,
                deterministicStagingIdentity = "stage-$expiring",
            ),
        ))
        val expiresAtEpochMs = 86_413_200L // 13_200 + 24 hours.
        val prepared = successfulItemResult(13_200L).let { result ->
            result.copy(outputs = result.outputs.map { output ->
                output.copy(
                    privateRelativePath = "share_ready/item-$expiring-0/com.example.app.0/Example.apk",
                    expiresAtEpochMs = expiresAtEpochMs,
                )
            })
        }
        dao.claimOldestRunnableTask("session", "ready-task", 13_000L, 14_000L)
        dao.claimNextPendingItem(expiring, "ready-task", "ready-item", 13_100L, 14_100L)
        assertTrue(
            dao.settleClaimedTask(
                expiring,
                "ready-task",
                0,
                "ready-item",
                DataTaskRunOutcome.ItemCompleted(prepared),
                13_200L,
            ),
        )
        val ready = requireNotNull(dao.loadTask(expiring))
        assertNull(ready.retainUntilEpochMs)
        val outputIds = ready.outputs.map { it.outputId.toString() }
        assertEquals(prepared.outputs.map { it.outputId.toString() }, outputIds)
        assertFalse(dao.markReadyTaskExpiredAfterCleanup(expiring, emptyList(), expiresAtEpochMs))
        assertFalse(dao.markReadyTaskExpiredAfterCleanup(expiring, listOf(taskId(999)), expiresAtEpochMs))
        assertEquals(ready, dao.loadTask(expiring))
        assertTrue(dao.markReadyTaskExpiredAfterCleanup(expiring, outputIds, expiresAtEpochMs))
        assertTerminalRetention(expiring, DataTaskState.EXPIRED, expiresAtEpochMs)
    }

    @Test
    fun publicExportRejectsPrivateShareExpiryWithoutChangingItsOutput() = runBlocking {
        val publicExport = taskId(94)
        dao.insertTask(newExportTask(publicExport))
        dao.claimOldestRunnableTask("session", "public-task", 13_000L, 14_000L)
        dao.claimNextPendingItem(publicExport, "public-task", "public-item", 13_100L, 14_100L)
        assertTrue(dao.settleClaimedTask(
            publicExport, "public-task", 0, "public-item",
            DataTaskRunOutcome.ItemCompleted(successfulItemResult(13_200L)), 13_200L,
        ))
        val ready = requireNotNull(dao.loadTask(publicExport))
        val outputIds = ready.outputs.map { it.outputId.toString() }
        assertEquals(DataTaskKind.APP_EXPORT, ready.kind)
        assertEquals(DataTaskState.READY, ready.state)
        assertEquals(DataTaskPublicationPolicy.PUBLIC_DOCUMENT,
            (ready.detail as StoredDataTaskDetail.AppExport).publicationPolicy)
        assertNull(dao.readyShareRetentionSnapshot(publicExport, 23_200L))
        assertFalse(dao.markReadyTaskExpiredAfterCleanup(publicExport, outputIds, 23_200L))
        assertFalse(dao.markReadyTaskExpiredAfterCleanup(ready, outputIds, 23_200L))
        assertEquals(ready, dao.loadTask(publicExport))
    }

    @Test
    fun finalEmptyCheckSerializesBeforeConcurrentInsert() = runBlocking {
        assertFalse(dao.hasRunnableTasks())
        val finalTransactionEntered = CompletableDeferred<Unit>()
        val releaseFinalTransaction = CompletableDeferred<Unit>()
        val insertionCompleted = CompletableDeferred<Unit>()
        val stopDecisionMade = AtomicBoolean(false)
        val writerProbe = WriterTransactionProbe()
        val inserterDatabase = buildDatabase(WriterTrackingOpenHelperFactory(writerProbe))
        val inserterDao = inserterDatabase.dataTaskDao()
        inserterDatabase.openHelper.writableDatabase

        try {
            coroutineScope {
                val finalDrain = async(Dispatchers.IO) {
                    dao.finishDrainIfQueueEmpty {
                        finalTransactionEntered.complete(Unit)
                        runBlocking { releaseFinalTransaction.await() }
                        stopDecisionMade.set(true)
                    }
                }
                finalTransactionEntered.await()
                writerProbe.arm()
                val insertion = async(Dispatchers.IO) {
                    inserterDao.insertTask(newExportTask(TASK_1))
                    insertionCompleted.complete(Unit)
                }
                writerProbe.transactionAttempted.await()

                try {
                    assertFalse(writerProbe.transactionAcquired.isCompleted)
                    assertFalse(insertionCompleted.isCompleted)
                    assertFalse(insertion.isCompleted)
                    assertFalse(finalDrain.isCompleted)
                    assertFalse(stopDecisionMade.get())
                } finally {
                    releaseFinalTransaction.complete(Unit)
                }
                assertTrue(finalDrain.await())
                assertTrue(stopDecisionMade.get())
                writerProbe.transactionAcquired.await()
                insertion.await()
                assertTrue(insertionCompleted.isCompleted)
                assertTrue(dao.hasRunnableTasks())
                assertEquals(
                    UUID.fromString(TASK_1),
                    dao.claimOldestRunnableTask(
                        "current-session",
                        "current-claim",
                        2_000L,
                        3_000L,
                    )?.taskId,
                )
            }
        } finally {
            releaseFinalTransaction.complete(Unit)
            inserterDatabase.close()
        }
    }

    private suspend fun nextObservation(
        observed: Channel<DataTaskSnapshot?>,
    ): DataTaskSnapshot? = withTimeout(5_000L) { observed.receive() }

    private fun inDatabaseTransaction(block: SupportSQLiteDatabase.() -> Unit) {
        database.runInTransaction {
            database.openHelper.writableDatabase.block()
        }
    }

    private fun insertOutput(
        taskId: String,
        outputId: String,
        itemOrdinal: Int,
        displayName: String,
    ) {
        inDatabaseTransaction {
            execSQL(
                """
                INSERT INTO data_task_outputs(
                    output_id, task_id, item_ordinal, private_relative_path, display_name,
                    mime_type, byte_size, state, expires_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    outputId,
                    taskId,
                    itemOrdinal,
                    "outputs/$displayName",
                    displayName,
                    "application/vnd.android.package-archive",
                    42L,
                    DataTaskOutputState.READY.name,
                    100_000L,
                ),
            )
        }
    }

    private fun setOwnership(taskId: String, ownership: Ownership) {
        inDatabaseTransaction {
            execSQL(
                """
                UPDATE data_tasks
                SET service_session_token = ?, claim_token = ?, claim_lease_expires_at_epoch_ms = ?
                WHERE task_id = ?
                """.trimIndent(),
                arrayOf<Any?>(
                    ownership.serviceSessionToken,
                    ownership.claimToken,
                    ownership.claimLeaseExpiresAtEpochMs,
                    taskId,
                ),
            )
        }
    }

    private fun setTerminalState(taskId: String, state: DataTaskState, terminalAtEpochMs: Long) {
        inDatabaseTransaction {
            execSQL(
                """
                UPDATE data_tasks
                SET state = ?, updated_at_epoch_ms = ?, terminal_at_epoch_ms = ?,
                    retain_until_epoch_ms = ?
                WHERE task_id = ?
                """.trimIndent(),
                arrayOf<Any?>(
                    state.name,
                    terminalAtEpochMs,
                    terminalAtEpochMs,
                    terminalAtEpochMs + TERMINAL_RETENTION_MS,
                    taskId,
                ),
            )
        }
    }

    private suspend fun assertTerminalRetention(
        taskId: String,
        expectedState: DataTaskState,
        terminalAtEpochMs: Long,
    ) {
        val snapshot = requireNotNull(dao.loadTask(taskId))
        assertEquals(expectedState, snapshot.state)
        assertEquals(terminalAtEpochMs, snapshot.terminalAtEpochMs)
        assertEquals(terminalAtEpochMs + TERMINAL_RETENTION_MS, snapshot.retainUntilEpochMs)
    }

    private fun taskId(suffix: Int): String =
        "00000000-0000-0000-0000-${suffix.toString().padStart(12, '0')}"

    private fun openDatabase() {
        database = buildDatabase()
        dao = database.dataTaskDao()
    }

    private fun buildDatabase(
        openHelperFactory: SupportSQLiteOpenHelper.Factory? = null,
    ): AppDatabase {
        val builder = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DATABASE_NAME,
        ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        openHelperFactory?.let(builder::openHelperFactory)
        return builder.build()
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private fun loadPersistedTask(taskId: String = TASK_1): PersistedTask =
        database.openHelper.readableDatabase.query(
            """
            SELECT state, interruption, result_code, service_session_token, claim_token,
                   claim_lease_expires_at_epoch_ms
            FROM data_tasks
            WHERE task_id = ?
            """.trimIndent(),
            arrayOf(taskId),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Missing task $taskId" }
            PersistedTask(
                state = cursor.getString(cursor.getColumnIndexOrThrow("state")),
                interruption = cursor.getString(cursor.getColumnIndexOrThrow("interruption")),
                resultCode = cursor.stringOrNull("result_code"),
                serviceSessionToken = cursor.stringOrNull("service_session_token"),
                claimToken = cursor.stringOrNull("claim_token"),
                claimLeaseExpiresAtEpochMs = cursor.longOrNull("claim_lease_expires_at_epoch_ms"),
            )
        }

    private fun loadPersistedItem(taskId: String = TASK_1): PersistedItem =
        database.openHelper.readableDatabase.query(
            """
            SELECT state, claim_token, claim_lease_expires_at_epoch_ms
            FROM data_task_items
            WHERE task_id = ? AND ordinal = ?
            """.trimIndent(),
            arrayOf<Any?>(taskId, 0),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Missing item $taskId/0" }
            PersistedItem(
                state = cursor.getString(cursor.getColumnIndexOrThrow("state")),
                claimToken = cursor.stringOrNull("claim_token"),
                claimLeaseExpiresAtEpochMs = cursor.longOrNull("claim_lease_expires_at_epoch_ms"),
            )
        }

    private fun loadPersistedBreadcrumb(): RestoreMutationBreadcrumb =
        database.openHelper.readableDatabase.query(
            """
            SELECT mutation_package_name, mutation_app_label, mutation_started_at_epoch_ms
            FROM archive_task_details
            WHERE task_id = ?
            """.trimIndent(),
            arrayOf(TASK_1),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Missing archive detail for $TASK_1" }
            RestoreMutationBreadcrumb(
                packageName = cursor.getString(cursor.getColumnIndexOrThrow("mutation_package_name")),
                appLabel = cursor.getString(cursor.getColumnIndexOrThrow("mutation_app_label")),
                startedAtEpochMs = cursor.getLong(
                    cursor.getColumnIndexOrThrow("mutation_started_at_epoch_ms")
                ),
            )
        }

    private fun android.database.Cursor.stringOrNull(columnName: String): String? {
        val column = getColumnIndexOrThrow(columnName)
        return if (isNull(column)) null else getString(column)
    }

    private fun android.database.Cursor.longOrNull(columnName: String): Long? {
        val column = getColumnIndexOrThrow(columnName)
        return if (isNull(column)) null else getLong(column)
    }

    private fun newExportTask(
        taskId: String,
        createdAtEpochMs: Long = 1_000L,
        initialState: DataTaskState = DataTaskState.QUEUED,
        itemCount: Int = 1,
    ) = NewDataTaskRow(
        taskId = taskId,
        payloadSchemaVersion = 1,
        kind = DataTaskKind.APP_EXPORT,
        targetKey = "package:com.example.app",
        initialState = initialState,
        detail = StoredDataTaskDetail.AppExport(
            requestedFormat = BundleFormat.APK,
            destination = StoredDataDestination.Downloads,
            namingLabel = "Example",
            publicationPolicy = DataTaskPublicationPolicy.PUBLIC_DOCUMENT,
            deterministicStagingIdentity = "stage-$taskId",
        ),
        items = List(itemCount) { ordinal ->
            NewDataTaskItem(
                ordinal = ordinal,
                packageName = "com.example.app.$ordinal",
                displayLabel = "Example $ordinal",
                deterministicStagingIdentity = "item-$taskId-$ordinal",
            )
        },
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun newRestoreTask() = NewDataTaskRow(
        taskId = TASK_1,
        payloadSchemaVersion = 1,
        kind = DataTaskKind.ARCHIVE_RESTORE,
        targetKey = "package:com.example.app",
        initialState = DataTaskState.QUEUED,
        detail = StoredDataTaskDetail.ArchiveRestore(
            expectedPackageName = "com.example.app",
            dataClassIds = listOf("apk", "data"),
            restoreObb = true,
            source = StoredRestoreSource.PrivateCopy(
                privateRestoreSourceRelativePath(
                    UUID.fromString(
                        TASK_1
                    )
                )
            ),
            mutationBreadcrumb = null,
            deterministicStagingIdentity = "stage-$TASK_1",
        ),
        items = List(2) { ordinal ->
            NewDataTaskItem(
                ordinal = ordinal,
                packageName = "com.example.app.$ordinal",
                displayLabel = "Example $ordinal",
                deterministicStagingIdentity = "item-$TASK_1-$ordinal",
            )
        },
        createdAtEpochMs = 1_000L,
    )

    private fun checkpoint() = DataTaskCheckpoint(
        stage = DataTaskStage.WRITING,
        completed = 1,
        total = 1,
        activeItemOrdinal = 0,
        activeItemLabel = "Example",
        destructiveStarted = false,
        restoreMutationBreadcrumb = null,
        recordedAtEpochMs = 2_200L,
    )

    private fun successfulItemResult(finishedAtEpochMs: Long) = DataTaskItemResult(
        terminalState = DataTaskItemTerminalState.SUCCEEDED,
        resultCode = DataTaskResultCode("OK"),
        warnings = emptyList(),
        outputs = listOf(
            NewDataTaskOutput(
                outputId = UUID.nameUUIDFromBytes("output-$finishedAtEpochMs".toByteArray()),
                privateRelativePath = "outputs/app.apk",
                displayName = "Example.apk",
                mimeType = "application/vnd.android.package-archive",
                byteSize = 42L,
                state = DataTaskOutputState.READY,
                expiresAtEpochMs = finishedAtEpochMs + 10_000L,
            ),
        ),
        finishedAtEpochMs = finishedAtEpochMs,
    )

    private class WriterTransactionProbe {
        val transactionAttempted = CompletableDeferred<Unit>()
        val transactionAcquired = CompletableDeferred<Unit>()
        private val armed = AtomicBoolean(false)

        fun arm() {
            check(armed.compareAndSet(false, true)) { "Writer transaction probe already armed" }
        }

        fun onTransactionAttempted() {
            if (armed.get()) transactionAttempted.complete(Unit)
        }

        fun onTransactionAcquired() {
            if (armed.get()) transactionAcquired.complete(Unit)
        }
    }

    private class WriterTrackingOpenHelperFactory(
        private val probe: WriterTransactionProbe,
        private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
    ) : SupportSQLiteOpenHelper.Factory {
        override fun create(
            configuration: SupportSQLiteOpenHelper.Configuration,
        ): SupportSQLiteOpenHelper = WriterTrackingOpenHelper(
            delegate = delegate.create(configuration),
            probe = probe,
        )
    }

    private class WriterTrackingOpenHelper(
        private val delegate: SupportSQLiteOpenHelper,
        private val probe: WriterTransactionProbe,
    ) : SupportSQLiteOpenHelper by delegate {
        override val writableDatabase: SupportSQLiteDatabase
            get() = WriterTrackingDatabase(delegate.writableDatabase, probe)

        override val readableDatabase: SupportSQLiteDatabase
            get() = WriterTrackingDatabase(delegate.readableDatabase, probe)
    }

    private class WriterTrackingDatabase(
        private val delegate: SupportSQLiteDatabase,
        private val probe: WriterTransactionProbe,
    ) : SupportSQLiteDatabase by delegate {
        override fun beginTransactionNonExclusive() {
            probe.onTransactionAttempted()
            delegate.beginTransactionNonExclusive()
            probe.onTransactionAcquired()
        }
    }

    private data class PersistedTask(
        val state: String,
        val interruption: String,
        val resultCode: String?,
        val serviceSessionToken: String?,
        val claimToken: String?,
        val claimLeaseExpiresAtEpochMs: Long?,
    )

    private data class PersistedItem(
        val state: String,
        val claimToken: String?,
        val claimLeaseExpiresAtEpochMs: Long?,
    )

    private data class Ownership(
        val serviceSessionToken: String? = null,
        val claimToken: String? = null,
        val claimLeaseExpiresAtEpochMs: Long? = null,
    )

    private companion object {
        const val DATABASE_NAME = "data-task-dao-test"
        const val TASK_1 = "00000000-0000-0000-0000-000000000001"
        const val TASK_2 = "00000000-0000-0000-0000-000000000002"
        const val TERMINAL_RETENTION_MS = 24L * 60L * 60L * 1000L
    }
}
