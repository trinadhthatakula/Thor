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
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import java.util.concurrent.atomic.AtomicBoolean
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
class PrivilegeSweepDaoTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase
    private lateinit var dao: PrivilegeSweepDao

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        database = buildDatabase()
        dao = database.privilegeSweepDao()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun legacyUnknownTargetIsNotClaimedUntilReconciledOrExplicitlyResumed() = runBlocking {
        insertSweep(
            requestId = REQUEST_1,
            requestState = StoredSweepRequestState.QUEUED,
            targetStates = listOf(StoredSweepTargetState.LEGACY_UNKNOWN),
        )

        assertFalse(dao.hasRunnableRequests())
        assertNull(dao.claimOldestRunnableRequest("session", "claim", 2_000L, 3_000L))
        assertTrue(
            dao.recoverInterruptedTarget(
                REQUEST_1,
                0,
                StoredSweepRecovery.Requeue(
                    SweepTargetResultCode("RECONCILED_FOR_RETRY"),
                    recoveredAtEpochMs = 2_100L,
                ),
            ),
        )

        val reconciledRequest = requireNotNull(
            dao.claimOldestRunnableRequest("session-1", "request-claim-1", 2_200L, 3_200L),
        )
        assertEquals(REQUEST_1, reconciledRequest.requestId)
        assertEquals(
            0,
            requireNotNull(
                dao.claimNextPendingTarget(
                    REQUEST_1,
                    "request-claim-1",
                    "target-claim-1",
                    2_300L,
                    3_300L,
                ),
            ).ordinal,
        )

        insertSweep(
            requestId = REQUEST_2,
            queueSequence = 2L,
            requestState = StoredSweepRequestState.BLOCKED,
            targetStates = listOf(StoredSweepTargetState.UNKNOWN),
        )
        assertNull(dao.claimOldestRunnableRequest("session-2", "request-claim-2", 2_400L, 3_400L))
        assertTrue(dao.authorizeUnknownTargetRetry(REQUEST_2, 0, 2_500L))
        assertEquals(
            REQUEST_2,
            dao.claimOldestRunnableRequest(
                "session-2",
                "request-claim-2",
                2_600L,
                3_600L,
            )?.requestId,
        )
    }

    @Test
    fun requestAndTargetClaimsEachHaveOneWinner() = runBlocking {
        insertSweep(requestId = REQUEST_1)
        val contenderDatabase = buildDatabase()
        val contenderDao = contenderDatabase.privilegeSweepDao()

        try {
            val requestWinners = coroutineScope {
                listOf(
                    async(Dispatchers.IO) {
                        dao.claimOldestRunnableRequest("session-a", "request-a", 2_000L, 4_000L)
                    },
                    async(Dispatchers.IO) {
                        contenderDao.claimOldestRunnableRequest(
                            "session-b",
                            "request-b",
                            2_000L,
                            4_000L,
                        )
                    },
                ).awaitAll().filterNotNull()
            }
            assertEquals(1, requestWinners.size)
            val requestWinner = requestWinners.single()
            assertEquals(REQUEST_1, requestWinner.requestId)
            assertEquals(PrivilegeSweepOperation.UNFREEZE, requestWinner.operation)
            assertEquals(PrivilegeSweepSource.MAIN, requestWinner.source)
            assertEquals(setOf("MAIN", "QS_TILE"), requestWinner.sourceAssociations)
            assertEquals(1, requestWinner.targetCount)

            val targetWinners = coroutineScope {
                listOf(
                    async(Dispatchers.IO) {
                        dao.claimNextPendingTarget(
                            REQUEST_1,
                            requestWinner.claimToken,
                            "target-a",
                            2_100L,
                            4_100L,
                        )
                    },
                    async(Dispatchers.IO) {
                        contenderDao.claimNextPendingTarget(
                            REQUEST_1,
                            requestWinner.claimToken,
                            "target-b",
                            2_100L,
                            4_100L,
                        )
                    },
                ).awaitAll().filterNotNull()
            }
            assertEquals(1, targetWinners.size)
            assertEquals(0, targetWinners.single().ordinal)
        } finally {
            contenderDatabase.close()
        }
    }

    @Test
    fun staleRequestOrTargetTokenCannotWrite() = runBlocking {
        insertSweep(requestId = REQUEST_1)
        assertNotNull(
            dao.claimOldestRunnableRequest("session", "request-claim", 2_000L, 5_000L),
        )
        assertNotNull(
            dao.claimNextPendingTarget(
                REQUEST_1,
                "request-claim",
                "target-claim",
                2_100L,
                5_100L,
            ),
        )

        assertFalse(dao.renewRequestClaim(REQUEST_1, "stale-request", 6_000L))
        assertTrue(dao.renewRequestClaim(REQUEST_1, "request-claim", 6_000L))
        assertFalse(dao.renewTargetClaim(REQUEST_1, 0, "stale-target", 6_000L))
        assertTrue(dao.renewTargetClaim(REQUEST_1, 0, "target-claim", 6_000L))
        assertFalse(
            dao.completeClaimedTarget(
                REQUEST_1,
                0,
                "stale-request",
                "target-claim",
                successfulResult(2_200L),
            ),
        )
        assertFalse(
            dao.completeClaimedTarget(
                REQUEST_1,
                0,
                "request-claim",
                "stale-target",
                successfulResult(2_200L),
            ),
        )
        assertTrue(
            dao.completeClaimedTarget(
                REQUEST_1,
                0,
                "request-claim",
                "target-claim",
                successfulResult(2_200L),
            ),
        )
    }

    @Test
    fun completionAfterCancellationCannotResurrectTarget() = runBlocking {
        insertSweep(
            requestId = REQUEST_1,
            targetStates = listOf(
                StoredSweepTargetState.PENDING,
                StoredSweepTargetState.PENDING,
            ),
        )
        dao.claimOldestRunnableRequest("session", "request-claim", 2_000L, 4_000L)
        dao.claimNextPendingTarget(
            REQUEST_1,
            "request-claim",
            "target-claim",
            2_100L,
            4_100L,
        )

        val decision = dao.requestCancellation(REQUEST_1, 2_200L)
        assertTrue(decision is SweepCancellationDecision.InterruptActive)
        assertEquals(
            0,
            (decision as SweepCancellationDecision.InterruptActive).activeTargetOrdinal,
        )
        assertEquals(
            listOf(StoredSweepTargetState.RUNNING, StoredSweepTargetState.CANCELLED),
            targetStates(),
        )
        assertFalse(
            dao.completeClaimedTarget(
                REQUEST_1,
                0,
                "request-claim",
                "target-claim",
                successfulResult(2_300L),
            ),
        )

        assertTrue(
            dao.recoverInterruptedTarget(
                REQUEST_1,
                0,
                StoredSweepRecovery.Requeue(
                    SweepTargetResultCode("INTERRUPTED"),
                    recoveredAtEpochMs = 4_200L,
                ),
            ),
        )
        val settled = requireNotNull(dao.load(REQUEST_1))
        assertEquals(StoredSweepRequestState.CANCELLED.name, settled.request.state)
        assertEquals(StoredSweepRequestState.CANCELLED.name, settled.request.terminalState)
        assertEquals(
            listOf(StoredSweepTargetState.CANCELLED, StoredSweepTargetState.CANCELLED),
            targetStates(),
        )
        assertFalse(
            dao.completeClaimedTarget(
                REQUEST_1,
                0,
                "request-claim",
                "target-claim",
                successfulResult(4_300L),
            ),
        )
    }

    @Test
    fun aggregatesAlwaysEqualTargetStates() = runBlocking {
        insertSweep(
            requestId = REQUEST_1,
            targetStates = List(4) { StoredSweepTargetState.PENDING },
        )
        dao.claimOldestRunnableRequest("session", "request-claim", 2_000L, 5_500L)

        completeNextTarget(
            targetClaimToken = "target-0",
            terminalState = StoredSweepTargetTerminalState.SUCCEEDED,
            nowMs = 2_100L,
        )
        assertEquals(Aggregates(1, 0, 0, 3), aggregates())

        completeNextTarget(
            targetClaimToken = "target-1",
            terminalState = StoredSweepTargetTerminalState.FAILED,
            nowMs = 2_200L,
        )
        assertEquals(Aggregates(1, 1, 0, 2), aggregates())

        completeNextTarget(
            targetClaimToken = "target-2",
            terminalState = StoredSweepTargetTerminalState.BUSY,
            nowMs = 2_300L,
        )
        assertEquals(Aggregates(1, 1, 1, 1), aggregates())

        assertEquals(
            3,
            dao.claimNextPendingTarget(
                REQUEST_1,
                "request-claim",
                "target-3",
                2_400L,
                5_400L,
            )?.ordinal,
        )
        assertTrue(
            dao.recoverInterruptedTarget(
                REQUEST_1,
                3,
                StoredSweepRecovery.MarkUnknown(
                    SweepTargetResultCode("OUTCOME_UNKNOWN"),
                    recoveredAtEpochMs = 5_500L,
                ),
            ),
        )
        assertEquals(Aggregates(1, 1, 1, 1), aggregates())
        assertFalse(dao.hasRunnableRequests())

        assertTrue(dao.authorizeUnknownTargetRetry(REQUEST_1, 3, 5_600L))
        assertEquals(Aggregates(1, 1, 1, 1), aggregates())
        assertNotNull(
            dao.claimOldestRunnableRequest("new-session", "new-request-claim", 5_700L, 7_000L),
        )
        assertEquals(
            3,
            dao.claimNextPendingTarget(
                REQUEST_1,
                "new-request-claim",
                "target-3-retry",
                5_800L,
                6_800L,
            )?.ordinal,
        )
        assertTrue(
            dao.completeClaimedTarget(
                REQUEST_1,
                3,
                "new-request-claim",
                "target-3-retry",
                successfulResult(5_900L),
            ),
        )
        assertEquals(Aggregates(2, 1, 1, 0), aggregates())
        assertTrue(dao.finishClaimedRequestIfDrained(REQUEST_1, "new-request-claim", 6_000L))
        val finished = requireNotNull(dao.load(REQUEST_1)).request
        assertEquals(StoredSweepRequestState.PARTIAL.name, finished.state)
        assertEquals(StoredSweepRequestState.PARTIAL.name, finished.terminalState)
        assertEquals(Aggregates(2, 1, 1, 0), aggregates())
    }

    @Test
    fun finalEmptyCheckSeesAConcurrentInsert() = runBlocking {
        assertFalse(dao.hasRunnableRequests())
        val finalTransactionEntered = CompletableDeferred<Unit>()
        val releaseFinalTransaction = CompletableDeferred<Unit>()
        val insertionCompleted = CompletableDeferred<Unit>()
        val stopDecisionMade = AtomicBoolean(false)
        val writerProbe = WriterTransactionProbe()
        val inserterDatabase = buildDatabase(WriterTrackingOpenHelperFactory(writerProbe))
        val inserterDao = inserterDatabase.privilegeSweepDao()
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
                    insertSweep(inserterDao, REQUEST_1)
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
                assertTrue(dao.hasRunnableRequests())
                assertEquals(
                    REQUEST_1,
                    dao.claimOldestRunnableRequest(
                        "current-session",
                        "current-claim",
                        2_000L,
                        3_000L,
                    )?.requestId,
                )
            }
        } finally {
            releaseFinalTransaction.complete(Unit)
            inserterDatabase.close()
        }
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

    private suspend fun insertSweep(
        targetDao: PrivilegeSweepDao = dao,
        requestId: String,
        queueSequence: Long = 1L,
        requestState: StoredSweepRequestState = StoredSweepRequestState.QUEUED,
        targetStates: List<StoredSweepTargetState> = listOf(StoredSweepTargetState.PENDING),
    ) {
        val createdAt = 1_000L + queueSequence
        targetDao.createOrFindEquivalent(
            request = SweepRequestEntity(
                requestId = requestId,
                workId = "work-$requestId",
                operation = PrivilegeSweepOperation.UNFREEZE.name,
                freezerMode = null,
                userId = 0,
                sourceSurface = PrivilegeSweepSource.MAIN.name,
                createdAtEpochMs = createdAt,
                terminalState = null,
                succeeded = targetStates.count { it == StoredSweepTargetState.SUCCEEDED },
                failed = targetStates.count { it == StoredSweepTargetState.FAILED },
                busy = targetStates.count { it == StoredSweepTargetState.BUSY },
                unresolved = targetStates.count {
                    it !in setOf(
                        StoredSweepTargetState.SUCCEEDED,
                        StoredSweepTargetState.FAILED,
                        StoredSweepTargetState.BUSY,
                    )
                },
                terminalAtEpochMs = null,
                retainUntilEpochMs = null,
                payloadSchemaVersion = 1,
                queueSequence = queueSequence,
                state = requestState.name,
                executionId = "execution-$requestId",
                updatedAtEpochMs = createdAt,
            ),
            targets = targetStates.mapIndexed { ordinal, state ->
                SweepTargetEntity(
                    requestId = requestId,
                    ordinal = ordinal,
                    packageName = "app.$requestId.$ordinal",
                    state = state.name,
                )
            },
            sources = listOf(
                SweepRequestSourceEntity(requestId, PrivilegeSweepSource.MAIN.name, createdAt),
                SweepRequestSourceEntity(
                    requestId,
                    PrivilegeSweepSource.QS_TILE.name,
                    createdAt + 1
                ),
            ),
        )
    }

    private fun successfulResult(nowMs: Long): StoredSweepTargetResult =
        StoredSweepTargetResult(
            terminalState = StoredSweepTargetTerminalState.SUCCEEDED,
            resultCode = SweepTargetResultCode("RESULT_SUCCEEDED"),
            rootLaneDegraded = false,
            finishedAtEpochMs = nowMs,
        )

    private suspend fun completeNextTarget(
        targetClaimToken: String,
        terminalState: StoredSweepTargetTerminalState,
        nowMs: Long,
    ) {
        val target = requireNotNull(
            dao.claimNextPendingTarget(
                REQUEST_1,
                "request-claim",
                targetClaimToken,
                nowMs,
                5_400L,
            ),
        )
        assertTrue(
            dao.completeClaimedTarget(
                REQUEST_1,
                target.ordinal,
                "request-claim",
                targetClaimToken,
                StoredSweepTargetResult(
                    terminalState = terminalState,
                    resultCode = SweepTargetResultCode("RESULT_${terminalState.name}"),
                    rootLaneDegraded = false,
                    finishedAtEpochMs = nowMs + 1,
                ),
            ),
        )
    }

    private fun aggregates(): Aggregates =
        requireNotNull(runBlocking { dao.load(REQUEST_1) }).request.let {
            Aggregates(it.succeeded, it.failed, it.busy, it.unresolved)
        }

    private fun targetStates(): List<StoredSweepTargetState> =
        requireNotNull(runBlocking { dao.load(REQUEST_1) }).targets
            .sortedBy(SweepTargetEntity::ordinal)
            .map { StoredSweepTargetState.valueOf(it.state) }

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

    private data class Aggregates(
        val succeeded: Int,
        val failed: Int,
        val busy: Int,
        val unresolved: Int,
    )

    private companion object {
        const val DATABASE_NAME = "privilege-sweep-dao-test.db"
        const val REQUEST_1 = "00000000-0000-0000-0000-000000000101"
        const val REQUEST_2 = "00000000-0000-0000-0000-000000000102"
    }
}
