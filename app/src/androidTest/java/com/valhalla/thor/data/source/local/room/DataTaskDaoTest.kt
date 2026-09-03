// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskCheckpoint
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
import com.valhalla.thor.domain.model.StoredDataDestination
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.model.StoredRestoreSource
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataTaskDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: DataTaskDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        dao = database.dataTaskDao()
    }

    @After
    fun tearDown() {
        database.close()
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
                2_200L,
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
                2_200L,
            ),
        )
        assertEquals(
            1,
            dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim-1", 2_300L, 3_300L)?.ordinal,
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

        assertTrue(dao.recoverClaims("new-session", 3_000L).isEmpty())
        val decision = dao.requestCancellation(TASK_1, 3_100L)
        assertTrue(decision is DataTaskCancellationDecision.AlreadyTerminal)
        val snapshot = (decision as DataTaskCancellationDecision.AlreadyTerminal).snapshot
        assertEquals(DataTaskState.CANCELLED, snapshot.state)
        assertTrue(snapshot.items.all { it.state == DataTaskItemState.CANCELLED })
    }

    @Test
    fun cancellationRejectsNonCancellationSettlement() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        dao.claimOldestRunnableTask("session", "task-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "task-claim", "item-claim", 2_100L, 3_100L)
        dao.requestCancellation(TASK_1, 2_200L)

        assertFalse(
            dao.settleClaimedTask(
                TASK_1,
                "task-claim",
                0,
                "item-claim",
                DataTaskRunOutcome.WaitingForAuthentication(DataTaskResultCode("AUTH_REQUIRED")),
                2_300L,
            ),
        )
        val decision = dao.requestCancellation(TASK_1, 2_400L)
        assertEquals(
            DataTaskState.CANCEL_REQUESTED,
            (decision as DataTaskCancellationDecision.InterruptActive).snapshot.state,
        )
    }

    @Test
    fun destructiveRecoveryWithoutBreadcrumbFailsActiveItemAndCancelsPendingItems() = runBlocking {
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
            ),
        )

        val recovered = dao.recoverClaims("new-session", 3_200L)

        assertTrue(recovered.single().recovery is DataTaskRecovery.Failed)
        val decision = dao.requestCancellation(TASK_1, 3_300L)
        val snapshot = (decision as DataTaskCancellationDecision.AlreadyTerminal).snapshot
        assertEquals(DataTaskState.FAILED, snapshot.state)
        assertEquals(DataTaskItemState.FAILED, snapshot.items[0].state)
        assertEquals("RECOVERY_BREADCRUMB_MISSING", snapshot.items[0].resultCode?.value)
        assertEquals(3_200L, snapshot.items[0].finishedAtEpochMs)
        assertEquals(DataTaskItemState.CANCELLED, snapshot.items[1].state)
    }

    @Test
    fun finalEmptyCheckSeesAConcurrentInsert() = runBlocking {
        assertFalse(dao.hasRunnableTasks())
        val inserted = CompletableDeferred<Unit>()

        coroutineScope {
            val producer = async {
                dao.insertTask(newExportTask(TASK_1))
                inserted.complete(Unit)
            }
            val observer = async {
                inserted.await()
                dao.hasRunnableTasks()
            }
            producer.await()
            assertTrue(observer.await())
        }
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
            source = StoredRestoreSource.PrivateCopy("inputs/archive.thor"),
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

    private companion object {
        const val TASK_1 = "00000000-0000-0000-0000-000000000001"
        const val TASK_2 = "00000000-0000-0000-0000-000000000002"
    }
}
