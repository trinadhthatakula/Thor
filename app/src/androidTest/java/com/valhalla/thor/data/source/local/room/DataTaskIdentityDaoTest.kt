// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskInterruption
import com.valhalla.thor.domain.model.DataTaskItemState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.model.StoredDataDestination
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.model.StoredRestoreSource
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataTaskIdentityDaoTest {
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
    fun activeTaskIdentitySurvivesProcessLocalStateLoss() = runBlocking {
        dao.insertTask(newExportTask())
        database.close()
        openDatabase()

        assertEquals(
            UUID.fromString(TASK_ID),
            dao.observeActiveTaskId(
                kind = DataTaskKind.APP_EXPORT,
                targetKey = "package:com.example.app",
            ).first(),
        )
    }

    @Test
    fun privateRestoreSourceCommitRequiresBothLiveClaimTokens() = runBlocking {
        dao.insertTask(newRestoreTask())
        dao.claimOldestRunnableTask("session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_ID, "task-claim", "item-claim", 2_100L, 3_100L)

        assertFalse(
            dao.commitPrivateRestoreSource(
                TASK_ID,
                "stale-task-claim",
                0,
                "item-claim",
                PRIVATE_SOURCE,
                2_200L,
            )
        )
        assertFalse(
            dao.commitPrivateRestoreSource(
                TASK_ID,
                "task-claim",
                0,
                "stale-item-claim",
                PRIVATE_SOURCE,
                2_200L,
            )
        )
        assertTrue(
            dao.commitPrivateRestoreSource(
                TASK_ID,
                "task-claim",
                0,
                "item-claim",
                PRIVATE_SOURCE,
                2_200L,
            )
        )

        val detail = dao.loadTask(TASK_ID)?.detail as StoredDataTaskDetail.ArchiveRestore
        assertEquals(StoredRestoreSource.PrivateCopy(PRIVATE_SOURCE), detail.source)
    }

    @Test
    fun atomicWorkClaimDefersCancellationUntilTaskAndItemClaimsCommit() = runBlocking {
        dao.insertTask(newExportTask())
        val taskClaimed = CompletableDeferred<Unit>()
        val cancellationStarted = CompletableDeferred<Unit>()
        val allowItemClaim = CompletableDeferred<Unit>()
        val acquisition = async {
            dao.claimOldestRunnableWork(
                sessionToken = "session",
                taskClaimToken = TASK_CLAIM,
                itemClaimToken = ITEM_CLAIM,
                nowMs = 2_000L,
                leaseUntilMs = 3_000L,
                afterTaskClaimed = {
                    taskClaimed.complete(Unit)
                    allowItemClaim.await()
                },
            )
        }
        taskClaimed.await()
        val cancellation = async {
            cancellationStarted.complete(Unit)
            dao.requestCancellation(TASK_ID, 2_100L)
        }
        cancellationStarted.await()
        yield()
        allowItemClaim.complete(Unit)

        val claimed = requireNotNull(acquisition.await())
        val decision = cancellation.await()
        val snapshot = requireNotNull(dao.loadTask(TASK_ID))
        assertEquals(TASK_ID, claimed.task.taskId.toString())
        assertEquals(0, claimed.item.ordinal)
        assertTrue(decision is DataTaskCancellationDecision.InterruptActive)
        assertEquals(DataTaskState.CANCEL_REQUESTED, snapshot.state)
        assertEquals(DataTaskItemState.RUNNING, snapshot.items.single().state)
    }

    @Test
    fun provisionalTaskClaimIsReleasedAfterTransitionFailureBeforeItemClaim() = runBlocking {
        dao.insertTask(newExportTask())
        assertEquals(
            UUID.fromString(TASK_ID),
            dao.claimOldestRunnableTask("session", TASK_CLAIM, 2_000L, 3_000L)?.taskId,
        )

        assertTrue(dao.settleClaimAcquisitionFailure(TASK_CLAIM, 2_100L))

        val snapshot = requireNotNull(dao.loadTask(TASK_ID))
        assertEquals(DataTaskState.QUEUED, snapshot.state)
        assertEquals(DataTaskItemState.PENDING, snapshot.items.single().state)
        assertEquals(DataTaskInterruption.SERVICE_TIMEOUT, snapshot.interruption)
    }

    @Test
    fun timeoutExactOwnerSettlesAfterLeaseExpiryAndRecoveredOwnerRejectsStaleTokens() =
        runBlocking {
            dao.insertTask(newExportTask())
            claimTaskAndItem(TASK_ID)

            assertTrue(dao.settleClaimTimeout(TASK_ID, TASK_CLAIM, 0, ITEM_CLAIM, 4_000L))
            val timedOut = requireNotNull(dao.loadTask(TASK_ID))
            assertEquals(DataTaskState.QUEUED, timedOut.state)
            assertEquals(DataTaskItemState.PENDING, timedOut.items.single().state)

            val recovered = requireNotNull(
                dao.claimOldestRunnableWork(
                    sessionToken = "replacement-session",
                    taskClaimToken = "replacement-task",
                    itemClaimToken = "replacement-item",
                    nowMs = 4_100L,
                    leaseUntilMs = 5_100L,
                )
            )
            assertEquals(UUID.fromString(TASK_ID), recovered.task.taskId)
            assertFalse(dao.settleClaimTimeout(TASK_ID, TASK_CLAIM, 0, ITEM_CLAIM, 5_200L))
            assertEquals(DataTaskState.RUNNING, dao.loadTask(TASK_ID)?.state)
        }

    @Test
    fun timeoutReloadsDestructiveRestoreCheckpointWrittenAfterClaimSnapshot() = runBlocking {
        dao.insertTask(newRestoreTask())
        claimTaskAndItem(TASK_ID)
        val breadcrumb = RestoreMutationBreadcrumb("com.example.app", "Example", 2_150L)
        assertTrue(
            dao.checkpointClaimedTask(
                taskId = TASK_ID,
                taskClaimToken = TASK_CLAIM,
                itemOrdinal = 0,
                itemClaimToken = ITEM_CLAIM,
                checkpoint = DataTaskCheckpoint(
                    stage = DataTaskStage.RESTORING,
                    completed = 1,
                    total = 2,
                    activeItemOrdinal = 0,
                    activeItemLabel = "Example",
                    destructiveStarted = true,
                    restoreMutationBreadcrumb = breadcrumb,
                    recordedAtEpochMs = 2_200L,
                ),
                leaseUntilMs = 4_000L,
            )
        )

        assertTrue(
            dao.settleClaimTimeout(TASK_ID, TASK_CLAIM, 0, ITEM_CLAIM, 2_300L)
        )

        val task = requireNotNull(dao.loadTask(TASK_ID))
        assertEquals(DataTaskState.INTERRUPTED_REVIEW, task.state)
        assertEquals(DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW, task.interruption)
        assertEquals(
            breadcrumb,
            (task.detail as StoredDataTaskDetail.ArchiveRestore).mutationBreadcrumb,
        )
    }

    @Test
    fun timeoutWinsBeforeNormalResultAndReleasesExportForRetry() = runBlocking {
        dao.insertTask(newExportTask())
        claimTaskAndItem(TASK_ID)

        assertTrue(dao.settleClaimTimeout(TASK_ID, TASK_CLAIM, 0, ITEM_CLAIM, 2_200L))
        assertFalse(
            dao.settleClaimedTask(
                TASK_ID,
                TASK_CLAIM,
                0,
                ITEM_CLAIM,
                DataTaskRunOutcome.TaskFailed(DataTaskResultCode("LATE_RESULT")),
                2_300L,
            )
        )

        val task = requireNotNull(dao.loadTask(TASK_ID))
        assertEquals(DataTaskState.QUEUED, task.state)
        assertEquals(DataTaskInterruption.SERVICE_TIMEOUT, task.interruption)
        assertEquals(DataTaskItemState.PENDING, task.items.single().state)
    }

    @Test
    fun normalResultWinsBeforeTimeoutAndRejectsLateTimeoutToken() = runBlocking {
        dao.insertTask(newExportTask())
        claimTaskAndItem(TASK_ID)

        assertTrue(
            dao.settleClaimedTask(
                TASK_ID,
                TASK_CLAIM,
                0,
                ITEM_CLAIM,
                DataTaskRunOutcome.TaskFailed(DataTaskResultCode("FAILED_FIRST")),
                2_200L,
            )
        )
        assertFalse(dao.settleClaimTimeout(TASK_ID, TASK_CLAIM, 0, ITEM_CLAIM, 2_300L))

        assertEquals(DataTaskState.FAILED, dao.loadTask(TASK_ID)?.state)
    }

    @Test
    fun staleTimeoutClaimTokensMutateNothing() = runBlocking {
        dao.insertTask(newExportTask())
        claimTaskAndItem(TASK_ID)

        assertFalse(
            dao.settleClaimTimeout(TASK_ID, "stale-task", 0, ITEM_CLAIM, 2_200L)
        )
        assertFalse(
            dao.settleClaimTimeout(TASK_ID, TASK_CLAIM, 0, "stale-item", 2_200L)
        )

        val task = requireNotNull(dao.loadTask(TASK_ID))
        assertEquals(DataTaskState.RUNNING, task.state)
        assertEquals(DataTaskInterruption.NONE, task.interruption)
        assertEquals(DataTaskItemState.RUNNING, task.items.single().state)
    }

    @Test
    fun cancellationAfterRunnerReturnAtomicallyWinsNormalSettlement() = runBlocking {
        dao.insertTask(newExportTask())
        claimTaskAndItem(TASK_ID)
        dao.requestCancellation(TASK_ID, 2_200L)

        assertTrue(
            dao.settleClaimedTask(
                TASK_ID,
                TASK_CLAIM,
                0,
                ITEM_CLAIM,
                DataTaskRunOutcome.TaskFailed(DataTaskResultCode("RUNNER_RETURNED")),
                2_300L,
            )
        )

        val task = requireNotNull(dao.loadTask(TASK_ID))
        assertEquals(DataTaskState.CANCELLED, task.state)
        assertEquals(DataTaskItemState.CANCELLED, task.items.single().state)
    }

    @Test
    fun destructiveCancellationSettlementFailsClosedToInterruptedReview() = runBlocking {
        dao.insertTask(newRestoreTask())
        claimTaskAndItem(TASK_ID)
        val breadcrumb = RestoreMutationBreadcrumb("com.example.app", "Example", 2_150L)
        assertTrue(
            dao.checkpointClaimedTask(
                taskId = TASK_ID,
                taskClaimToken = TASK_CLAIM,
                itemOrdinal = 0,
                itemClaimToken = ITEM_CLAIM,
                checkpoint = DataTaskCheckpoint(
                    stage = DataTaskStage.RESTORING,
                    completed = 1,
                    total = 2,
                    activeItemOrdinal = 0,
                    activeItemLabel = "Example",
                    destructiveStarted = true,
                    restoreMutationBreadcrumb = breadcrumb,
                    recordedAtEpochMs = 2_200L,
                ),
                leaseUntilMs = 4_000L,
            )
        )
        dao.requestCancellation(TASK_ID, 2_300L)

        assertTrue(
            dao.settleClaimedTask(
                TASK_ID,
                TASK_CLAIM,
                0,
                ITEM_CLAIM,
                DataTaskRunOutcome.Cancelled,
                2_400L,
            )
        )

        val task = requireNotNull(dao.loadTask(TASK_ID))
        assertEquals(DataTaskState.INTERRUPTED_REVIEW, task.state)
        assertEquals(DataTaskInterruption.DESTRUCTIVE_RESTORE_REVIEW, task.interruption)
        assertEquals(DataTaskItemState.RUNNING, task.items.single().state)
        assertEquals(
            breadcrumb,
            (task.detail as StoredDataTaskDetail.ArchiveRestore).mutationBreadcrumb
        )
    }

    @Test
    fun timeoutAtomicallySettlesCancelRequestedClaim() = runBlocking {
        dao.insertTask(newExportTask())
        claimTaskAndItem(TASK_ID)
        dao.requestCancellation(TASK_ID, 2_200L)

        assertTrue(dao.settleClaimTimeout(TASK_ID, TASK_CLAIM, 0, ITEM_CLAIM, 2_300L))

        val task = requireNotNull(dao.loadTask(TASK_ID))
        assertEquals(DataTaskState.CANCELLED, task.state)
        assertEquals(DataTaskItemState.CANCELLED, task.items.single().state)
    }

    @Test
    fun stickyNullIntentPromotionFailureBlocksOldestOwnedTask() = runBlocking {
        dao.insertTask(newExportTask())
        dao.insertTask(newExportTask(SECOND_TASK_ID))
        claimTaskAndItem(TASK_ID)

        val blocked = dao.blockCurrentStart(
            taskId = null,
            blockedState = DataTaskState.START_BLOCKED_NOTIFICATION,
            nowMs = 2_200L,
        )

        assertEquals(UUID.fromString(TASK_ID), blocked?.taskId)
        assertEquals(DataTaskState.START_BLOCKED_NOTIFICATION, blocked?.state)
        assertEquals(DataTaskItemState.PENDING, blocked?.items?.single()?.state)
        assertEquals(DataTaskState.QUEUED, dao.loadTask(SECOND_TASK_ID)?.state)
    }

    private suspend fun claimTaskAndItem(taskId: String) {
        dao.claimOldestRunnableTask("session", TASK_CLAIM, 2_000L, 3_000L)
        dao.claimNextPendingItem(taskId, TASK_CLAIM, ITEM_CLAIM, 2_100L, 3_100L)
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME).build()
        dao = database.dataTaskDao()
    }

    private fun newExportTask(taskId: String = TASK_ID) = NewDataTaskRow(
        taskId = taskId,
        payloadSchemaVersion = 1,
        kind = DataTaskKind.APP_EXPORT,
        targetKey = "package:com.example.app",
        initialState = DataTaskState.QUEUED,
        detail = StoredDataTaskDetail.AppExport(
            requestedFormat = BundleFormat.APK,
            destination = StoredDataDestination.Downloads,
            namingLabel = "Example",
            publicationPolicy = DataTaskPublicationPolicy.PUBLIC_DOCUMENT,
            deterministicStagingIdentity = "stage-$taskId",
        ),
        items = listOf(
            NewDataTaskItem(
                ordinal = 0,
                packageName = "com.example.app",
                displayLabel = "Example",
                deterministicStagingIdentity = "item-$taskId-0",
            )
        ),
        createdAtEpochMs = 1_000L,
    )

    private fun newRestoreTask() = NewDataTaskRow(
        taskId = TASK_ID,
        payloadSchemaVersion = 1,
        kind = DataTaskKind.ARCHIVE_RESTORE,
        targetKey = "package:com.example.app",
        initialState = DataTaskState.STAGING_SOURCE,
        detail = StoredDataTaskDetail.ArchiveRestore(
            expectedPackageName = "com.example.app",
            dataClassIds = listOf("apk"),
            restoreObb = false,
            source = StoredRestoreSource.AwaitingTransientGrant,
            mutationBreadcrumb = null,
            deterministicStagingIdentity = "stage-$TASK_ID",
        ),
        items = listOf(
            NewDataTaskItem(
                ordinal = 0,
                packageName = "com.example.app",
                displayLabel = "Example",
                deterministicStagingIdentity = "item-$TASK_ID-0",
            )
        ),
        createdAtEpochMs = 1_000L,
    )

    private companion object {
        const val DATABASE_NAME = "data-task-identity-test"
        const val TASK_ID = "00000000-0000-0000-0000-000000000191"
        const val SECOND_TASK_ID = "00000000-0000-0000-0000-000000000192"
        const val TASK_CLAIM = "task-claim"
        const val ITEM_CLAIM = "item-claim"
        const val PRIVATE_SOURCE = "data_tasks/$TASK_ID/restore-source.thor"
    }
}
