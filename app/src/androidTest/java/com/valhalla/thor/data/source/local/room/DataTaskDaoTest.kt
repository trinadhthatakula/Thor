// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
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
import com.valhalla.thor.domain.model.StoredDataDestination
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.model.StoredRestoreSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
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
            ),
        )

        val recovery = dao.recoverClaims("new-session", 3_200L).single()
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
    fun currentSessionExpiredRunningLeaseRemainsOwned() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        dao.claimOldestRunnableTask("live-session", "live-claim", 2_000L, 3_000L)

        assertTrue(dao.recoverClaims("live-session", 4_000L).isEmpty())
        assertNull(
            dao.claimOldestRunnableTask(
                "other-session",
                "other-claim",
                4_000L,
                5_000L,
            ),
        )
        val task = loadPersistedTask()
        assertEquals(DataTaskState.RUNNING.name, task.state)
        assertEquals("live-session", task.serviceSessionToken)
        assertEquals("live-claim", task.claimToken)
    }

    @Test
    fun currentSessionExpiredCancellationLeaseRemainsOwned() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        dao.claimOldestRunnableTask("live-session", "live-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "live-claim", "item-claim", 2_100L, 3_100L)
        dao.requestCancellation(TASK_1, 2_200L)

        assertTrue(dao.recoverClaims("live-session", 4_000L).isEmpty())
        assertNull(
            dao.claimOldestRunnableTask(
                "other-session",
                "other-claim",
                4_000L,
                5_000L,
            ),
        )
        val task = loadPersistedTask()
        assertEquals(DataTaskState.CANCEL_REQUESTED.name, task.state)
        assertEquals("live-session", task.serviceSessionToken)
        assertEquals("live-claim", task.claimToken)
    }

    @Test
    fun deadOwnerMustBeRecoveredBeforeExportCanBeClaimed() = runBlocking {
        dao.insertTask(newExportTask(TASK_1))
        dao.claimOldestRunnableTask("dead-session", "dead-claim", 2_000L, 3_000L)
        dao.claimNextPendingItem(TASK_1, "dead-claim", "dead-item-claim", 2_100L, 3_100L)

        assertNull(
            dao.claimOldestRunnableTask(
                "new-session",
                "new-claim-before-recovery",
                4_000L,
                5_000L,
            ),
        )
        val recovery = dao.recoverClaims("new-session", 4_000L).single()
        assertEquals(DataTaskRecovery.Resume, recovery.recovery)
        assertEquals("dead-session", recovery.previousServiceSessionToken)
        assertEquals(
            UUID.fromString(TASK_1),
            dao.claimOldestRunnableTask(
                "new-session",
                "new-claim-after-recovery",
                4_100L,
                5_100L,
            )?.taskId,
        )
    }

    @Test
    fun finalEmptyCheckSeesAConcurrentInsert() = runBlocking {
        assertFalse(dao.hasRunnableTasks())
        val rowInserted = CompletableDeferred<Unit>()
        val allowInsertCommit = CompletableDeferred<Unit>()
        val finalTransactionAttempted = CompletableDeferred<Unit>()
        val finalTransactionSignal = AtomicReference<CompletableDeferred<Unit>?>(null)
        val stopDecisionMade = AtomicBoolean(false)
        val observerDatabase = buildDatabase { sql, _ ->
            if (sql == "BEGIN IMMEDIATE TRANSACTION") {
                finalTransactionSignal.get()?.complete(Unit)
            }
        }
        observerDatabase.openHelper.writableDatabase
        val observerDao = observerDatabase.dataTaskDao()

        try {
            coroutineScope {
                val producer = async(Dispatchers.IO) {
                    database.withTransaction {
                        dao.insertTask(newExportTask(TASK_1))
                        rowInserted.complete(Unit)
                        allowInsertCommit.await()
                    }
                }
                rowInserted.await()
                finalTransactionSignal.set(finalTransactionAttempted)
                val observer = async(Dispatchers.IO) {
                    observerDao.finishDrainIfQueueEmpty { stopDecisionMade.set(true) }
                }
                finalTransactionAttempted.await()

                assertFalse(observer.isCompleted)
                assertFalse(stopDecisionMade.get())

                allowInsertCommit.complete(Unit)
                producer.await()
                assertFalse(observer.await())
                assertFalse(stopDecisionMade.get())
            }
        } finally {
            allowInsertCommit.complete(Unit)
            observerDatabase.close()
        }
    }

    private fun openDatabase() {
        database = buildDatabase()
        dao = database.dataTaskDao()
    }

    private fun buildDatabase(
        queryCallback: RoomDatabase.QueryCallback? = null,
    ): AppDatabase {
        val builder = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DATABASE_NAME,
        ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        queryCallback?.let { callback ->
            builder.setQueryCallback(callback) { command -> command.run() }
        }
        return builder.build()
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private fun loadPersistedTask(): PersistedTask =
        database.openHelper.readableDatabase.query(
            """
            SELECT state, interruption, result_code, service_session_token, claim_token,
                   claim_lease_expires_at_epoch_ms
            FROM data_tasks
            WHERE task_id = ?
            """.trimIndent(),
            arrayOf(TASK_1),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Missing task $TASK_1" }
            PersistedTask(
                state = cursor.getString(cursor.getColumnIndexOrThrow("state")),
                interruption = cursor.getString(cursor.getColumnIndexOrThrow("interruption")),
                resultCode = cursor.stringOrNull("result_code"),
                serviceSessionToken = cursor.stringOrNull("service_session_token"),
                claimToken = cursor.stringOrNull("claim_token"),
                claimLeaseExpiresAtEpochMs = cursor.longOrNull("claim_lease_expires_at_epoch_ms"),
            )
        }

    private fun loadPersistedItem(): PersistedItem =
        database.openHelper.readableDatabase.query(
            """
            SELECT state, claim_token, claim_lease_expires_at_epoch_ms
            FROM data_task_items
            WHERE task_id = ? AND ordinal = ?
            """.trimIndent(),
            arrayOf<Any?>(TASK_1, 0),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Missing item $TASK_1/0" }
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

    private companion object {
        const val DATABASE_NAME = "data-task-dao-test"
        const val TASK_1 = "00000000-0000-0000-0000-000000000001"
        const val TASK_2 = "00000000-0000-0000-0000-000000000002"
    }
}
