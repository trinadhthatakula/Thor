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
    fun createAllocatesQueueSequenceAndClaimsInInsertionOrder() = runBlocking {
        insertSweep(requestId = REQUEST_3, queueSequence = 0L)
        insertSweep(requestId = REQUEST_1, queueSequence = 0L)

        assertEquals(1L, requireNotNull(dao.load(REQUEST_3)).request.queueSequence)
        assertEquals(2L, requireNotNull(dao.load(REQUEST_1)).request.queueSequence)
        assertEquals(
            REQUEST_3,
            dao.claimOldestRunnableRequest(
                sessionToken = "session",
                claimToken = "claim",
                nowMs = 2_000L,
                leaseUntilMs = 3_000L,
            )?.requestId,
        )
    }

    @Test
    fun startBlockAndResumePreserveTargetsIdentityAndRequireMatchingReason() = runBlocking {
        insertSweep(requestId = REQUEST_1)
        val before = requireNotNull(dao.load(REQUEST_1))
        assertTrue(dao.markUnclaimedStartBlocked(REQUEST_1, StoredSweepBlockReason.START_BLOCKED_NOTIFICATION, 2_000))
        assertFalse(dao.resumeBlockedRequest(REQUEST_1, StoredSweepBlockReason.START_BLOCKED, 2_100))
        assertTrue(dao.resumeBlockedRequest(REQUEST_1, StoredSweepBlockReason.START_BLOCKED_NOTIFICATION, 2_200))
        val after = requireNotNull(dao.load(REQUEST_1))
        assertEquals(before.targets, after.targets)
        assertEquals(before.sources, after.sources)
        assertEquals(before.request.executionId, after.request.executionId)
        assertEquals(before.request.workId, after.request.workId)
        assertEquals(before.request.queueSequence, after.request.queueSequence)
        assertEquals(StoredSweepRequestState.QUEUED.name, after.request.state)
        assertNull(after.request.blockReason)
        var rejected = false
        try { dao.markUnclaimedStartBlocked(REQUEST_1, StoredSweepBlockReason.PRIVILEGE_AUTHORIZATION_REQUIRED, 2_300) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test
    fun privilegeBlockRequiresExactOwnerAndNoRunningTarget() = runBlocking {
        insertSweep(requestId = REQUEST_1, targetStates = List(2) { StoredSweepTargetState.PENDING })
        requireNotNull(dao.claimOldestRunnableRequest("session", "owner", 2_000, 9_000))
        assertFalse(dao.blockClaimedRequestForMissingPrivilege(REQUEST_1, "stale", 2_100))
        val target = requireNotNull(dao.claimNextPendingTarget(REQUEST_1, "owner", "target", 2_200, 9_000))
        assertFalse(dao.blockClaimedRequestForMissingPrivilege(REQUEST_1, "owner", 2_300))
        assertTrue(dao.completeClaimedTarget(REQUEST_1, target.ordinal, "owner", "target", successfulResult(2_400)))
        val before = requireNotNull(dao.load(REQUEST_1))
        assertTrue(dao.blockClaimedRequestForMissingPrivilege(REQUEST_1, "owner", 2_500))
        val after = requireNotNull(dao.load(REQUEST_1))
        assertEquals(before.targets, after.targets)
        assertEquals(before.request.succeeded, after.request.succeeded)
        assertEquals(StoredSweepBlockReason.PRIVILEGE_AUTHORIZATION_REQUIRED.name, after.request.blockReason)
        assertNull(after.request.claimToken)
        assertNull(after.request.serviceSessionToken)
        assertNull(after.request.claimedAtEpochMs)
        assertNull(after.request.claimLeaseExpiresAtEpochMs)
    }

    @Test
    fun blockAndResumeFailClosedForPartialOwnership() = runBlocking {
        for (requestOwnership in listOf(true, false)) {
            insertSweep(requestId = REQUEST_1)
            if (requestOwnership) seedQueuedRequestOwnership("partial", null, null)
            else seedPartialTargetOwnership(REQUEST_1, 0)
            val before = dao.load(REQUEST_1)
            assertFalse(dao.markUnclaimedStartBlocked(REQUEST_1, StoredSweepBlockReason.START_BLOCKED, 2_000))
            assertFalse(dao.markLegacyTargetsUnknown(REQUEST_1, listOf(0), SERVICE_EXECUTION_ID, 2_000))
            assertEquals(before, dao.load(REQUEST_1))
            database.openHelper.writableDatabase.execSQL("UPDATE sweep_requests SET state='BLOCKED', block_reason='START_BLOCKED' WHERE request_id=?", arrayOf(REQUEST_1))
            assertFalse(dao.resumeBlockedRequest(REQUEST_1, StoredSweepBlockReason.START_BLOCKED, 2_100))
            deleteSweep()
        }
    }

    @Test
    fun legacyConversionHandlesStartBlockedInheritedRowsAndIsAllOrNone() = runBlocking {
        insertSweep(requestId = REQUEST_1, targetStates = List(3) { StoredSweepTargetState.PENDING })
        assertTrue(dao.markUnclaimedStartBlocked(REQUEST_1, StoredSweepBlockReason.START_BLOCKED, 2_000))
        val before = requireNotNull(dao.load(REQUEST_1))
        assertFalse(dao.markLegacyTargetsUnknown(REQUEST_1, listOf(0, 99), SERVICE_EXECUTION_ID, 2_100))
        assertEquals(before, dao.load(REQUEST_1))
        assertFalse(dao.markLegacyTargetsUnknown(REQUEST_1, emptyList(), SERVICE_EXECUTION_ID, 2_100))
        assertTrue(dao.markLegacyTargetsUnknown(REQUEST_1, listOf(0, 0, 1), SERVICE_EXECUTION_ID, 2_200))
        val partial = requireNotNull(dao.load(REQUEST_1))
        assertEquals(StoredSweepRequestState.QUEUED.name, partial.request.state)
        assertNull(partial.request.blockReason)
        assertEquals(listOf(StoredSweepTargetState.LEGACY_UNKNOWN, StoredSweepTargetState.LEGACY_UNKNOWN, StoredSweepTargetState.PENDING), targetStates())
        assertEquals(before.sources, partial.sources)
        assertEquals(SERVICE_EXECUTION_ID, partial.request.executionId)
        assertEquals(before.request.workId, partial.request.workId)
        assertEquals(before.targets.map { it.ordinal to it.packageName }, partial.targets.map { it.ordinal to it.packageName })
        assertTrue(dao.markLegacyTargetsUnknown(REQUEST_1, listOf(0, 1), SERVICE_EXECUTION_ID, 2_200))
        assertTrue(dao.markLegacyTargetsUnknown(REQUEST_1, listOf(0, 1, 2), SERVICE_EXECUTION_ID, 2_300))
        val all = requireNotNull(dao.load(REQUEST_1))
        assertEquals(StoredSweepRequestState.BLOCKED.name, all.request.state)
        assertNull(all.request.blockReason)
        assertEquals(3, all.request.unresolved)
        assertTrue(all.targets.all { it.claimToken == null && it.claimLeaseExpiresAtEpochMs == null && it.finishedAtEpochMs == null && it.resultCode == null && !it.rootLaneDegraded })
        assertFalse(dao.resumeBlockedRequest(REQUEST_1, StoredSweepBlockReason.START_BLOCKED, 2_400))
    }

    @Test
    fun legacyConversionRejectsBrokenIdempotentPostconditionAndCompletedOrdinal() = runBlocking {
        for (column in listOf("finished_at_epoch_ms=5", "result_code='BAD'", "root_lane_degraded=1")) {
            insertSweep(requestId = REQUEST_1, targetStates = listOf(StoredSweepTargetState.LEGACY_UNKNOWN, StoredSweepTargetState.PENDING))
            database.openHelper.writableDatabase.execSQL("UPDATE sweep_targets SET $column WHERE request_id=? AND ordinal=0", arrayOf(REQUEST_1))
            val before = dao.load(REQUEST_1)
            assertFalse(dao.markLegacyTargetsUnknown(REQUEST_1, listOf(1, 0), SERVICE_EXECUTION_ID, 2_000))
            assertEquals(before, dao.load(REQUEST_1))
            deleteSweep()
        }
        insertSweep(requestId = REQUEST_1, targetStates = listOf(StoredSweepTargetState.SUCCEEDED, StoredSweepTargetState.PENDING))
        assertFalse(dao.markLegacyTargetsUnknown(REQUEST_1, listOf(1, 0), SERVICE_EXECUTION_ID, 2_000))
        assertTrue(dao.markLegacyTargetsUnknown(REQUEST_1, listOf(1), SERVICE_EXECUTION_ID, 2_000))
        assertEquals(1, requireNotNull(dao.load(REQUEST_1)).request.succeeded)
    }

    @Test
    fun startBlockClaimRaceHasOneWinnerAndCancellationCannotBeResumed() = runBlocking {
        insertSweep(requestId = REQUEST_1)
        val results = coroutineScope {
            val block = async(Dispatchers.IO) { dao.markUnclaimedStartBlocked(REQUEST_1, StoredSweepBlockReason.START_BLOCKED, 2_000) }
            val claim = async(Dispatchers.IO) { dao.claimOldestRunnableRequest("session", "owner", 2_000, 9_000) }
            block.await() to (claim.await() != null)
        }
        assertTrue(results.first xor results.second)
        dao.requestCancellation(REQUEST_1, 2_100)
        assertFalse(dao.markLegacyTargetsUnknown(REQUEST_1, listOf(0), SERVICE_EXECUTION_ID, 2_200))
        assertFalse(dao.blockClaimedRequestForMissingPrivilege(REQUEST_1, "owner", 2_200))
        assertFalse(dao.resumeBlockedRequest(REQUEST_1, StoredSweepBlockReason.START_BLOCKED, 2_200))
        assertEquals(StoredSweepRequestState.CANCELLED.name, requireNotNull(dao.load(REQUEST_1)).request.state)
    }

    @Test
    fun exactExitSettlementReloadsLaterActiveTargetAndPreservesWinningCompletion() = runBlocking {
        insertSweep(requestId = REQUEST_1, targetStates = List(2) { StoredSweepTargetState.PENDING })
        insertSweep(requestId = REQUEST_2, queueSequence = 2)
        val untouched = dao.load(REQUEST_2)
        requireNotNull(dao.claimOldestRunnableRequest("session", "owner", 2_000, 9_000))
        val first = requireNotNull(dao.claimNextPendingTarget(REQUEST_1, "owner", "t0", 2_100, 9_000))
        assertTrue(dao.completeClaimedTarget(REQUEST_1, first.ordinal, "owner", "t0", successfulResult(2_200)))
        requireNotNull(dao.claimNextPendingTarget(REQUEST_1, "owner", "t1", 2_300, 9_000))
        assertFalse(dao.settleClaimedRequestAfterExit(REQUEST_1, "stale", 2_400))
        dao.requestCancellation(REQUEST_1, 2_500)
        assertTrue(dao.settleClaimedRequestAfterExit(REQUEST_1, "owner", 2_600))
        assertEquals(listOf(StoredSweepTargetState.SUCCEEDED, StoredSweepTargetState.CANCELLED), targetStates())
        assertEquals(untouched, dao.load(REQUEST_2))
        assertNull(requireNotNull(dao.load(REQUEST_1)).request.claimToken)
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
    fun malformedPendingRequestsAreNotRunnableOrClaimable() = runBlocking {
        insertSweep(requestId = REQUEST_1, queueSequence = 1L)
        seedTargetOwnership(REQUEST_1, ordinal = 0, claimToken = "stray-token", leaseUntilMs = null)
        insertSweep(requestId = REQUEST_2, queueSequence = 2L)
        seedTargetOwnership(REQUEST_2, ordinal = 0, claimToken = null, leaseUntilMs = 9_000L)
        val before = listOf(
            requireNotNull(dao.load(REQUEST_1)),
            requireNotNull(dao.load(REQUEST_2)),
        )

        val runnable = dao.hasRunnableRequests()
        val claimed = dao.claimOldestRunnableRequest("session", "request-claim", 2_000L, 3_000L)

        assertFalse(runnable)
        assertNull(claimed)
        assertEquals(
            before,
            listOf(
                requireNotNull(dao.load(REQUEST_1)),
                requireNotNull(dao.load(REQUEST_2)),
            ),
        )
    }

    @Test
    fun partiallyOwnedQueuedRequestsAreNotRunnableOrClaimableAndRemainUnchanged() = runBlocking {
        val partialOwnershipCases: List<Pair<String, Triple<String?, String?, Long?>>> = listOf(
            "service session only" to Triple("orphan-session", null, null),
            "request claim only" to Triple(null, "orphan-claim", null),
            "request lease only" to Triple(null, null, 9_000L),
        )

        partialOwnershipCases.forEach { (label, ownership) ->
            insertSweep(requestId = REQUEST_1)
            seedQueuedRequestOwnership(
                sessionToken = ownership.first,
                claimToken = ownership.second,
                leaseUntilMs = ownership.third,
            )
            val before = requireNotNull(dao.load(REQUEST_1))

            assertFalse(label, dao.hasRunnableRequests())
            assertNull(
                label,
                dao.claimOldestRunnableRequest("valid-session", "valid-claim", 2_000L, 3_000L),
            )

            assertEquals(label, before, requireNotNull(dao.load(REQUEST_1)))
            deleteSweep()
        }

        insertSweep(requestId = REQUEST_1)
        assertTrue(dao.hasRunnableRequests())
        assertEquals(
            REQUEST_1,
            requireNotNull(
                dao.claimOldestRunnableRequest("valid-session", "valid-claim", 2_000L, 3_000L),
            ).requestId,
        )
    }

    @Test
    fun pendingTargetClaimRejectsIncompleteOwnership() = runBlocking {
        insertSweep(
            requestId = REQUEST_1,
            targetStates = listOf(
                StoredSweepTargetState.PENDING,
                StoredSweepTargetState.PENDING,
            ),
        )
        dao.claimOldestRunnableRequest("session", "request-claim", 2_000L, 4_000L)
        seedTargetOwnership(REQUEST_1, ordinal = 0, claimToken = "stray-token", leaseUntilMs = null)
        seedTargetOwnership(REQUEST_1, ordinal = 1, claimToken = null, leaseUntilMs = 9_000L)
        val before = requireNotNull(dao.load(REQUEST_1))

        val claimed = dao.claimNextPendingTarget(
            REQUEST_1,
            "request-claim",
            "target-claim",
            2_100L,
            4_100L,
        )

        assertNull(claimed)
        assertEquals(before, requireNotNull(dao.load(REQUEST_1)))
    }

    @Test
    fun pendingTargetClaimRejectsMalformedSibling() = runBlocking {
        insertSweep(
            requestId = REQUEST_1,
            targetStates = listOf(
                StoredSweepTargetState.PENDING,
                StoredSweepTargetState.SUCCEEDED,
            ),
        )
        dao.claimOldestRunnableRequest("session", "request-claim", 2_000L, 4_000L)
        seedTargetOwnership(REQUEST_1, ordinal = 1, claimToken = "stray-token", leaseUntilMs = null)
        val before = requireNotNull(dao.load(REQUEST_1))

        val claimed = dao.claimNextPendingTarget(
            REQUEST_1,
            "request-claim",
            "target-claim",
            2_100L,
            4_100L,
        )

        assertNull(claimed)
        assertEquals(before, requireNotNull(dao.load(REQUEST_1)))
    }

    @Test
    fun ordinaryClaimedMutationsRejectMalformedSibling() = runBlocking {
        insertSweep(
            requestId = REQUEST_1,
            targetStates = listOf(
                StoredSweepTargetState.PENDING,
                StoredSweepTargetState.SUCCEEDED,
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
        seedTargetOwnership(REQUEST_1, ordinal = 1, claimToken = "stray-token", leaseUntilMs = null)
        val before = requireNotNull(dao.load(REQUEST_1))

        val requestRenewed = dao.renewRequestClaim(REQUEST_1, "request-claim", 5_000L)
        val targetRenewed = dao.renewTargetClaim(REQUEST_1, 0, "target-claim", 5_100L)
        val completed = dao.completeClaimedTarget(
            REQUEST_1,
            0,
            "request-claim",
            "target-claim",
            successfulResult(2_200L),
        )

        assertFalse(requestRenewed)
        assertFalse(targetRenewed)
        assertFalse(completed)
        assertEquals(before, requireNotNull(dao.load(REQUEST_1)))
    }

    @Test
    fun malformedRunningTargetsCannotBeMutatedOrCancelled() = runBlocking {
        insertSweep(requestId = REQUEST_1, queueSequence = 1L)
        dao.claimOldestRunnableRequest("session-1", "request-1", 2_000L, 4_000L)
        dao.claimNextPendingTarget(REQUEST_1, "request-1", "target-1", 2_100L, 4_100L)
        seedTargetOwnership(REQUEST_1, ordinal = 0, claimToken = "target-1", leaseUntilMs = null)
        val beforeRenew = requireNotNull(dao.load(REQUEST_1))

        assertFalse(dao.renewTargetClaim(REQUEST_1, 0, "target-1", 5_000L))
        assertEquals(beforeRenew, requireNotNull(dao.load(REQUEST_1)))

        insertSweep(requestId = REQUEST_2, queueSequence = 2L)
        dao.claimOldestRunnableRequest("session-2", "request-2", 2_000L, 4_000L)
        dao.claimNextPendingTarget(REQUEST_2, "request-2", "target-2", 2_100L, 4_100L)
        seedTargetOwnership(REQUEST_2, ordinal = 0, claimToken = "target-2", leaseUntilMs = -1L)
        val beforeCompletion = requireNotNull(dao.load(REQUEST_2))

        assertFalse(
            dao.completeClaimedTarget(
                REQUEST_2,
                0,
                "request-2",
                "target-2",
                successfulResult(2_200L),
            ),
        )
        assertEquals(beforeCompletion, requireNotNull(dao.load(REQUEST_2)))

        insertSweep(requestId = REQUEST_3, queueSequence = 3L)
        dao.claimOldestRunnableRequest("session-3", "request-3", 2_000L, 4_000L)
        dao.claimNextPendingTarget(REQUEST_3, "request-3", "target-3", 2_100L, 4_100L)
        seedTargetOwnership(REQUEST_3, ordinal = 0, claimToken = "   ", leaseUntilMs = 4_100L)
        val beforeCancellation = requireNotNull(dao.load(REQUEST_3))

        assertTrue(dao.requestCancellation(REQUEST_3, 2_300L) is SweepCancellationDecision.NotFound)
        assertEquals(beforeCancellation, requireNotNull(dao.load(REQUEST_3)))
    }

    @Test
    fun malformedRunningTargetTokenAlphabetFailsClosedAtRecoveryAndCancellation() = runBlocking {
        MALFORMED_OWNERSHIP_TOKENS.forEachIndexed { index, (label, malformedToken) ->
            insertSweep(requestId = REQUEST_1, queueSequence = index.toLong() + 1L)
            dao.claimOldestRunnableRequest(
                "previous-session",
                "request-claim",
                2_000L,
                3_000L,
            )
            dao.claimNextPendingTarget(
                REQUEST_1,
                "request-claim",
                "target-claim",
                2_100L,
                3_100L,
            )
            seedTargetOwnership(REQUEST_1, 0, malformedToken, 3_100L)
            val before = requireNotNull(dao.load(REQUEST_1))
            var ownerChecks = 0

            val recovery = dao.recoverRequestClaims("current-session", 4_000L) { _, _ ->
                ownerChecks += 1
                false
            }
            val cancellation = dao.requestCancellation(REQUEST_1, 4_100L)

            assertTrue("$label target token produced a recovery candidate", recovery.isEmpty())
            assertEquals("$label target token reached the owner callback", 1, ownerChecks)
            assertTrue(
                "$label target token permitted cancellation",
                cancellation is SweepCancellationDecision.NotFound,
            )
            assertEquals(
                "$label target token changed persisted state",
                before,
                requireNotNull(dao.load(REQUEST_1)),
            )
            deleteSweep()
        }
    }

    @Test
    fun tabOwnedRunningTargetCancellationLeavesRequestAndTargetUnchanged() = runBlocking {
        insertSweep(requestId = REQUEST_1)
        dao.claimOldestRunnableRequest("previous-session", "request-claim", 2_000L, 3_000L)
        dao.claimNextPendingTarget(
            REQUEST_1,
            "request-claim",
            "target-claim",
            2_100L,
            3_100L,
        )
        seedTargetOwnership(REQUEST_1, 0, "\t", 3_100L)
        val before = requireNotNull(dao.load(REQUEST_1))

        val cancellation = dao.requestCancellation(REQUEST_1, 4_000L)

        assertTrue(cancellation is SweepCancellationDecision.NotFound)
        assertEquals(before.request, requireNotNull(dao.load(REQUEST_1)).request)
        assertEquals(before.targets, requireNotNull(dao.load(REQUEST_1)).targets)
    }

    @Test
    fun malformedPersistedRequestTokensFailClosedAtRecoveryAndCancellation() = runBlocking {
        MALFORMED_OWNERSHIP_TOKENS.forEachIndexed { index, (label, malformedToken) ->
            listOf("service session", "request claim").forEach { field ->
                listOf("recovery", "cancellation").forEach { boundary ->
                    insertSweep(requestId = REQUEST_1, queueSequence = index.toLong() + 1L)
                    dao.claimOldestRunnableRequest(
                        "previous-session",
                        "request-claim",
                        2_000L,
                        3_000L,
                    )
                    seedRequestOwnership(
                        sessionToken = if (field == "service session") {
                            malformedToken
                        } else {
                            "previous-session"
                        },
                        claimToken = if (field == "request claim") {
                            malformedToken
                        } else {
                            "request-claim"
                        },
                    )
                    val before = requireNotNull(dao.load(REQUEST_1))
                    var ownerChecks = 0

                    val rejected = when (boundary) {
                        "recovery" -> {
                            val candidates = dao.recoverRequestClaims(
                                "current-session",
                                4_000L,
                            ) { _, _ ->
                                ownerChecks += 1
                                false
                            }
                            candidates.isEmpty() && ownerChecks == 0
                        }

                        else -> dao.requestCancellation(REQUEST_1, 4_000L) is
                                SweepCancellationDecision.NotFound
                    }

                    assertTrue("$label $field token passed $boundary", rejected)
                    assertEquals(
                        "$label $field token changed state during $boundary",
                        before,
                        requireNotNull(dao.load(REQUEST_1)),
                    )
                    deleteSweep()
                }
            }
        }
    }

    @Test
    fun invalidSuppliedClaimTokensRejectBeforeMutation() = runBlocking {
        MALFORMED_OWNERSHIP_TOKENS.forEachIndexed { index, (label, malformedToken) ->
            listOf("service session", "request claim").forEach { field ->
                insertSweep(requestId = REQUEST_1, queueSequence = index.toLong() + 1L)
                val before = requireNotNull(dao.load(REQUEST_1))

                val failure = runCatching {
                    dao.claimOldestRunnableRequest(
                        sessionToken = if (field == "service session") {
                            malformedToken
                        } else {
                            "session"
                        },
                        claimToken = if (field == "request claim") {
                            malformedToken
                        } else {
                            "request-claim"
                        },
                        nowMs = 2_000L,
                        leaseUntilMs = 3_000L,
                    )
                }.exceptionOrNull()

                assertTrue("$label $field token was accepted", failure is IllegalArgumentException)
                assertEquals(before, requireNotNull(dao.load(REQUEST_1)))
                deleteSweep()
            }

            listOf("request claim", "target claim").forEach { field ->
                insertSweep(requestId = REQUEST_1, queueSequence = index.toLong() + 1L)
                dao.claimOldestRunnableRequest("session", "request-claim", 2_000L, 3_000L)
                val before = requireNotNull(dao.load(REQUEST_1))

                val failure = runCatching {
                    dao.claimNextPendingTarget(
                        requestId = REQUEST_1,
                        requestClaimToken = if (field == "request claim") {
                            malformedToken
                        } else {
                            "request-claim"
                        },
                        targetClaimToken = if (field == "target claim") {
                            malformedToken
                        } else {
                            "target-claim"
                        },
                        nowMs = 2_100L,
                        leaseUntilMs = 3_100L,
                    )
                }.exceptionOrNull()

                assertTrue("$label $field token was accepted", failure is IllegalArgumentException)
                assertEquals(before, requireNotNull(dao.load(REQUEST_1)))
                deleteSweep()
            }
        }
    }

    @Test
    fun invalidSuppliedOwnershipAuthoritiesRejectAtEveryMutationBoundary() = runBlocking {
        val malformedToken = "claim/token"

        suspend fun assertRejected(
            label: String,
            prepare: suspend () -> Unit = {},
            action: suspend () -> Unit,
        ) {
            insertSweep(requestId = REQUEST_1)
            dao.claimOldestRunnableRequest("session", "request-claim", 2_000L, 3_000L)
            dao.claimNextPendingTarget(
                REQUEST_1,
                "request-claim",
                "target-claim",
                2_100L,
                3_100L,
            )
            prepare()
            val before = requireNotNull(dao.load(REQUEST_1))

            val failure = runCatching { action() }.exceptionOrNull()

            assertTrue("$label accepted a malformed token", failure is IllegalArgumentException)
            assertEquals(
                "$label mutated persisted state",
                before,
                requireNotNull(dao.load(REQUEST_1))
            )
            deleteSweep()
        }

        assertRejected("request renewal") {
            dao.renewRequestClaim(REQUEST_1, malformedToken, 4_000L)
        }
        assertRejected("target renewal") {
            dao.renewTargetClaim(REQUEST_1, 0, malformedToken, 4_000L)
        }
        assertRejected("completion request ownership") {
            dao.completeClaimedTarget(
                REQUEST_1,
                0,
                malformedToken,
                "target-claim",
                successfulResult(2_200L),
            )
        }
        assertRejected("completion target ownership") {
            dao.completeClaimedTarget(
                REQUEST_1,
                0,
                "request-claim",
                malformedToken,
                successfulResult(2_200L),
            )
        }
        assertRejected("drain finalization") {
            dao.finishClaimedRequestIfDrained(REQUEST_1, malformedToken, 2_300L)
        }
        assertRejected("request recovery session") {
            dao.recoverRequestClaims(malformedToken, 4_000L) { _, _ -> false }
        }
    }

    @Test
    fun validOwnershipTokenAlphabetRemainsAccepted() = runBlocking {
        VALID_OWNERSHIP_TOKENS.forEachIndexed { index, token ->
            val requestId = "valid-request-$index"
            insertSweep(requestId = requestId, queueSequence = index.toLong() + 1L)

            assertNotNull(dao.claimOldestRunnableRequest(token, token, 2_000L, 3_000L))
            assertNotNull(dao.claimNextPendingTarget(requestId, token, token, 2_100L, 3_100L))
            assertTrue(dao.renewRequestClaim(requestId, token, 3_200L))
            assertTrue(dao.renewTargetClaim(requestId, 0, token, 3_200L))
            assertTrue(
                dao.completeClaimedTarget(
                    requestId,
                    0,
                    token,
                    token,
                    successfulResult(2_200L),
                ),
            )
            assertTrue(dao.finishClaimedRequestIfDrained(requestId, token, 2_300L))
        }
    }

    @Test
    fun malformedStoredTokensCannotBeUsedByTypedRecoveryCandidate() = runBlocking {
        listOf("service session", "request claim", "target claim").forEach { field ->
            insertSweep(requestId = REQUEST_1)
            dao.claimOldestRunnableRequest("previous-session", "request-claim", 2_000L, 3_000L)
            dao.claimNextPendingTarget(
                REQUEST_1,
                "request-claim",
                "target-claim",
                2_100L,
                3_100L,
            )
            val candidate = dao.recoverRequestClaims(
                "current-session",
                4_000L,
            ) { _, _ -> false }.single()
            val malformedToken = "claim/token"
            val malformedCandidate = when (field) {
                "service session" -> {
                    seedRequestOwnership(malformedToken, "request-claim")
                    candidate.copy(previousServiceSessionToken = malformedToken)
                }

                "request claim" -> {
                    seedRequestOwnership("previous-session", malformedToken)
                    candidate.copy(previousRequestClaimToken = malformedToken)
                }

                else -> {
                    seedTargetOwnership(REQUEST_1, 0, malformedToken, 3_100L)
                    candidate.copy(activeTargetClaimToken = malformedToken)
                }
            }
            val before = requireNotNull(dao.load(REQUEST_1))

            assertFalse(
                "$field malformed candidate was accepted",
                dao.recoverInterruptedTarget(
                    malformedCandidate,
                    StoredSweepRecovery.MarkUnknown(
                        SweepTargetResultCode("OUTCOME_UNKNOWN"),
                        recoveredAtEpochMs = 4_000L,
                    ),
                ),
            )
            assertEquals(before, requireNotNull(dao.load(REQUEST_1)))
            deleteSweep()
        }
    }

    @Test
    fun cancellationRejectsMalformedNonRunningTargets() = runBlocking {
        val cases = listOf(
            Triple(StoredSweepTargetState.PENDING, null, 9_000L),
            Triple(StoredSweepTargetState.UNKNOWN, "stray-token", null),
            Triple(StoredSweepTargetState.SUCCEEDED, "stray-token", 9_000L),
        )
        val outcomes = cases.map { (targetState, claimToken, leaseUntilMs) ->
            insertSweep(
                requestId = REQUEST_1,
                requestState = StoredSweepRequestState.QUEUED,
                targetStates = listOf(targetState),
            )
            seedTargetOwnership(REQUEST_1, 0, claimToken, leaseUntilMs)
            val before = requireNotNull(dao.load(REQUEST_1))

            val decision = runCatching { dao.requestCancellation(REQUEST_1, 2_000L) }
            val unchanged = before == requireNotNull(dao.load(REQUEST_1))
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM sweep_requests WHERE request_id = ?",
                arrayOf(REQUEST_1),
            )
            (decision.getOrNull() is SweepCancellationDecision.NotFound) to unchanged
        }

        assertEquals(List(cases.size) { true to true }, outcomes)
    }

    @Test
    fun drainFinalizationRejectsMalformedTerminalTargets() = runBlocking {
        val cases = listOf(
            REQUEST_1 to ("stray-token" to null),
            REQUEST_2 to (null to 9_000L),
        )
        val outcomes = cases.mapIndexed { index, (requestId, ownership) ->
            val requestClaim = "request-$index"
            val targetClaim = "target-$index"
            insertSweep(requestId = requestId, queueSequence = index.toLong() + 1L)
            dao.claimOldestRunnableRequest("session-$index", requestClaim, 2_000L, 4_000L)
            dao.claimNextPendingTarget(
                requestId,
                requestClaim,
                targetClaim,
                2_100L,
                4_100L,
            )
            dao.completeClaimedTarget(
                requestId,
                0,
                requestClaim,
                targetClaim,
                successfulResult(2_200L),
            )
            seedTargetOwnership(requestId, 0, ownership.first, ownership.second)
            val before = requireNotNull(dao.load(requestId))

            val finished = dao.finishClaimedRequestIfDrained(requestId, requestClaim, 2_300L)
            finished to (before == requireNotNull(dao.load(requestId)))
        }

        assertEquals(List(cases.size) { false to true }, outcomes)
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
    fun requestClaimWithoutTargetIsRecoveredForEarlierSession() = runBlocking {
        insertSweep(requestId = REQUEST_1)
        assertNotNull(
            dao.claimOldestRunnableRequest("previous-session", "previous-claim", 2_000L, 5_000L),
        )

        val recovery = dao.recoverRequestClaims("current-session", 2_500L) { _, _ -> false }

        assertTrue(recovery.isEmpty())
        val recovered = requireNotNull(dao.load(REQUEST_1)).request
        assertEquals(StoredSweepRequestState.QUEUED.name, recovered.state)
        assertNull(recovered.serviceSessionToken)
        assertNull(recovered.claimToken)
        assertNull(recovered.claimLeaseExpiresAtEpochMs)
        assertEquals(
            REQUEST_1,
            dao.claimOldestRunnableRequest(
                "current-session",
                "current-claim",
                2_600L,
                5_600L,
            )?.requestId,
        )
    }

    @Test
    fun sameSessionRequestBeforeLeaseExpiryIsNotRecovered() = runBlocking {
        insertSweep(requestId = REQUEST_1)
        dao.claimOldestRunnableRequest("current-session", "request-claim", 2_000L, 3_000L)

        val recovery = dao.recoverRequestClaims("current-session", 2_500L) { _, _ -> false }

        assertTrue(recovery.isEmpty())
        val request = requireNotNull(dao.load(REQUEST_1)).request
        assertEquals(StoredSweepRequestState.RUNNING.name, request.state)
        assertEquals("request-claim", request.claimToken)
        assertNull(
            dao.claimOldestRunnableRequest("other-session", "other-claim", 2_600L, 3_600L),
        )
    }

    @Test
    fun sameSessionRequestAfterLeaseExpiryIsRecoveredWithoutLocalOwner() = runBlocking {
        insertSweep(requestId = REQUEST_1)
        dao.claimOldestRunnableRequest("current-session", "expired-claim", 2_000L, 3_000L)

        val recovery = dao.recoverRequestClaims("current-session", 3_000L) { _, _ -> false }

        assertTrue(recovery.isEmpty())
        assertEquals(
            StoredSweepRequestState.QUEUED.name,
            requireNotNull(dao.load(REQUEST_1)).request.state
        )
        assertEquals(
            REQUEST_1,
            dao.claimOldestRunnableRequest(
                "current-session",
                "replacement-claim",
                3_100L,
                4_100L,
            )?.requestId,
        )
    }

    @Test
    fun earlierSessionRequestWithLiveLocalOwnerIsNotRecovered() = runBlocking {
        insertSweep(requestId = REQUEST_1)
        dao.claimOldestRunnableRequest("previous-session", "live-claim", 2_000L, 3_000L)

        val recovery =
            dao.recoverRequestClaims("current-session", 4_000L) { requestId, claimToken ->
                requestId == REQUEST_1 && claimToken == "live-claim"
            }

        assertTrue(recovery.isEmpty())
        val request = requireNotNull(dao.load(REQUEST_1)).request
        assertEquals(StoredSweepRequestState.RUNNING.name, request.state)
        assertEquals("previous-session", request.serviceSessionToken)
        assertEquals("live-claim", request.claimToken)
    }

    @Test
    fun completedTargetRequestRecoveryFinalizesCommittedState() = runBlocking {
        insertSweep(requestId = REQUEST_1)
        dao.claimOldestRunnableRequest("previous-session", "request-claim", 2_000L, 5_000L)
        dao.claimNextPendingTarget(
            REQUEST_1,
            "request-claim",
            "target-claim",
            2_100L,
            5_100L,
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

        val recovery = dao.recoverRequestClaims("current-session", 2_300L) { _, _ -> false }

        assertTrue(recovery.isEmpty())
        val request = requireNotNull(dao.load(REQUEST_1)).request
        assertEquals(StoredSweepRequestState.SUCCEEDED.name, request.state)
        assertEquals(StoredSweepRequestState.SUCCEEDED.name, request.terminalState)
        assertEquals(Aggregates(1, 0, 0, 0), aggregates())
    }

    @Test
    fun unknownOnlyRequestRecoveryBlocksWithoutInventingSuccess() = runBlocking {
        insertSweep(requestId = REQUEST_1)
        dao.claimOldestRunnableRequest("previous-session", "request-claim", 2_000L, 3_000L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sweep_targets SET state = 'UNKNOWN' WHERE request_id = ?",
            arrayOf(REQUEST_1),
        )

        val recovery = dao.recoverRequestClaims("current-session", 3_100L) { _, _ -> false }

        assertTrue(recovery.isEmpty())
        val request = requireNotNull(dao.load(REQUEST_1)).request
        assertEquals(StoredSweepRequestState.BLOCKED.name, request.state)
        assertNull(request.terminalState)
        assertNull(request.serviceSessionToken)
        assertNull(request.claimToken)
        assertFalse(dao.hasRunnableRequests())
    }

    @Test
    fun requestRecoveryPreservesRunningTargetOwnership() = runBlocking {
        insertSweep(requestId = REQUEST_1)
        dao.claimOldestRunnableRequest("previous-session", "request-claim", 2_000L, 3_000L)
        dao.claimNextPendingTarget(
            REQUEST_1,
            "request-claim",
            "target-claim",
            2_100L,
            3_100L,
        )

        val recovery = dao.recoverRequestClaims("current-session", 3_200L) { _, _ -> false }

        val candidate = recovery.single()
        assertEquals(REQUEST_1, candidate.requestId)
        assertEquals(PrivilegeSweepOperation.UNFREEZE, candidate.operation)
        assertEquals(0, candidate.activeTargetOrdinal)
        assertEquals("app.$REQUEST_1.0", candidate.packageName)
        assertEquals("previous-session", candidate.previousServiceSessionToken)
        assertEquals("request-claim", candidate.previousRequestClaimToken)
        assertEquals(3_000L, candidate.previousRequestClaimLeaseExpiresAtEpochMs)
        assertEquals("target-claim", candidate.activeTargetClaimToken)
        assertEquals(3_100L, candidate.activeTargetClaimLeaseExpiresAtEpochMs)
        assertFalse(
            dao.recoverInterruptedTarget(
                candidate.copy(activeTargetClaimToken = "stale-target-claim"),
                StoredSweepRecovery.MarkUnknown(
                    SweepTargetResultCode("STALE_CANDIDATE"),
                    recoveredAtEpochMs = 3_200L,
                ),
            ),
        )
        assertTrue(dao.renewRequestClaim(REQUEST_1, "request-claim", 4_000L))
        assertFalse(
            dao.recoverInterruptedTarget(
                candidate,
                StoredSweepRecovery.MarkUnknown(
                    SweepTargetResultCode("STALE_CANDIDATE"),
                    recoveredAtEpochMs = 3_200L,
                ),
            ),
        )
        assertFalse(
            dao.recoverInterruptedTarget(
                REQUEST_1,
                0,
                StoredSweepRecovery.MarkUnknown(
                    SweepTargetResultCode("BYPASS_REJECTED"),
                    recoveredAtEpochMs = 3_200L,
                ),
            ),
        )
        val snapshot = requireNotNull(dao.load(REQUEST_1))
        assertEquals(StoredSweepRequestState.RUNNING.name, snapshot.request.state)
        assertEquals("request-claim", snapshot.request.claimToken)
        assertEquals(StoredSweepTargetState.RUNNING.name, snapshot.targets.single().state)
        assertEquals("target-claim", snapshot.targets.single().claimToken)
        assertNull(
            dao.claimOldestRunnableRequest("current-session", "second-claim", 3_300L, 4_300L),
        )
    }

    @Test
    fun malformedClaimedRequestsFailClosed() = runBlocking {
        insertSweep(requestId = REQUEST_1, queueSequence = 1L)
        insertSweep(requestId = REQUEST_2, queueSequence = 2L)
        insertSweep(requestId = REQUEST_3, queueSequence = 3L)
        dao.claimOldestRunnableRequest("previous-session", "claim-1", 2_000L, 3_000L)
        dao.claimOldestRunnableRequest("previous-session", "claim-2", 2_000L, 3_000L)
        dao.claimOldestRunnableRequest("previous-session", "claim-3", 2_000L, 3_000L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sweep_requests SET service_session_token = NULL WHERE request_id = ?",
            arrayOf(REQUEST_1),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sweep_requests SET claim_token = NULL WHERE request_id = ?",
            arrayOf(REQUEST_2),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sweep_requests SET claim_lease_expires_at_epoch_ms = NULL WHERE request_id = ?",
            arrayOf(REQUEST_3),
        )
        var ownerChecks = 0

        val recovery = dao.recoverRequestClaims("current-session", 4_000L) { _, _ ->
            ownerChecks += 1
            false
        }

        assertTrue(recovery.isEmpty())
        assertEquals(0, ownerChecks)
        assertEquals(
            StoredSweepRequestState.RUNNING.name,
            requireNotNull(dao.load(REQUEST_1)).request.state
        )
        assertEquals(
            StoredSweepRequestState.RUNNING.name,
            requireNotNull(dao.load(REQUEST_2)).request.state
        )
        assertEquals(
            StoredSweepRequestState.RUNNING.name,
            requireNotNull(dao.load(REQUEST_3)).request.state
        )
    }

    @Test
    fun compatibilityReconciliationRejectsPartialUnknownOwnership() = runBlocking {
        val cases = listOf(
            REQUEST_1 to StoredSweepTargetState.UNKNOWN,
            REQUEST_2 to StoredSweepTargetState.LEGACY_UNKNOWN,
        )
        cases.forEachIndexed { index, (requestId, targetState) ->
            insertSweep(
                requestId = requestId,
                queueSequence = index.toLong() + 1L,
                requestState = StoredSweepRequestState.BLOCKED,
                targetStates = listOf(targetState),
            )
            seedPartialTargetOwnership(requestId, ordinal = 0)
            val before = requireNotNull(dao.load(requestId))

            assertFalse(
                dao.recoverInterruptedTarget(
                    requestId,
                    0,
                    StoredSweepRecovery.Requeue(
                        SweepTargetResultCode("RECONCILED_FOR_RETRY"),
                        recoveredAtEpochMs = 2_000L,
                    ),
                ),
            )
            assertEquals(before, requireNotNull(dao.load(requestId)))
        }
    }

    @Test
    fun explicitRetryRejectsPartialUnknownOwnership() = runBlocking {
        val cases = listOf(
            REQUEST_1 to StoredSweepTargetState.UNKNOWN,
            REQUEST_2 to StoredSweepTargetState.LEGACY_UNKNOWN,
        )
        cases.forEachIndexed { index, (requestId, targetState) ->
            insertSweep(
                requestId = requestId,
                queueSequence = index.toLong() + 1L,
                requestState = StoredSweepRequestState.BLOCKED,
                targetStates = listOf(targetState),
            )
            seedPartialTargetOwnership(requestId, ordinal = 0)
            val before = requireNotNull(dao.load(requestId))

            assertFalse(dao.authorizeUnknownTargetRetry(requestId, 0, 2_000L))
            assertEquals(before, requireNotNull(dao.load(requestId)))
        }
    }

    @Test
    fun requestRecoveryRejectsPartialPendingOwnership() = runBlocking {
        insertSweep(requestId = REQUEST_1)
        dao.claimOldestRunnableRequest("previous-session", "request-claim", 2_000L, 3_000L)
        seedPartialTargetOwnership(REQUEST_1, ordinal = 0)
        val before = requireNotNull(dao.load(REQUEST_1))

        val recovery = dao.recoverRequestClaims("current-session", 3_100L) { _, _ -> false }

        assertTrue(recovery.isEmpty())
        assertEquals(before, requireNotNull(dao.load(REQUEST_1)))
    }

    @Test
    fun postTargetRecoverySettlementRejectsAnotherPartiallyOwnedTarget() = runBlocking {
        val cases: List<Pair<String, StoredSweepRecovery>> = listOf(
            REQUEST_1 to StoredSweepRecovery.Requeue(
                SweepTargetResultCode("RETRY_AFTER_OWNER_LOSS"),
                recoveredAtEpochMs = 3_200L,
            ),
            REQUEST_2 to StoredSweepRecovery.MarkUnknown(
                SweepTargetResultCode("OUTCOME_UNKNOWN"),
                recoveredAtEpochMs = 3_200L,
            ),
            REQUEST_3 to StoredSweepRecovery.Completed(successfulResult(3_200L), 3_200L),
        )
        val outcomes = cases.map { (requestId, recovery) ->
            insertSweep(
                requestId = requestId,
                targetStates = listOf(
                    StoredSweepTargetState.PENDING,
                    StoredSweepTargetState.SUCCEEDED,
                ),
            )
            dao.claimOldestRunnableRequest(
                "previous-session",
                "request-claim",
                2_000L,
                3_000L,
            )
            dao.claimNextPendingTarget(
                requestId,
                "request-claim",
                "target-claim",
                2_100L,
                3_100L,
            )
            seedPartialTargetOwnership(requestId, ordinal = 1)
            val candidate = dao.recoverRequestClaims(
                "current-session",
                3_200L,
            ) { candidateRequestId, _ -> candidateRequestId != requestId }.single()
            val before = requireNotNull(dao.load(requestId))

            val recovered = dao.recoverInterruptedTarget(candidate, recovery)
            val unchanged = before == requireNotNull(dao.load(requestId))
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM sweep_requests WHERE request_id = ?",
                arrayOf(requestId),
            )
            recovered to unchanged
        }

        assertEquals(List(cases.size) { false to true }, outcomes)
    }

    @Test
    fun unknownTargetRetryDoesNotClearActiveOwnership() = runBlocking {
        insertSweep(
            requestId = REQUEST_1,
            requestState = StoredSweepRequestState.BLOCKED,
            targetStates = listOf(
                StoredSweepTargetState.LEGACY_UNKNOWN,
                StoredSweepTargetState.LEGACY_UNKNOWN,
            ),
        )
        assertTrue(
            dao.recoverInterruptedTarget(
                REQUEST_1,
                0,
                StoredSweepRecovery.Requeue(
                    SweepTargetResultCode("RECONCILED_FOR_RETRY"),
                    recoveredAtEpochMs = 2_000L,
                ),
            ),
        )
        dao.claimOldestRunnableRequest("current-session", "request-claim", 2_100L, 4_100L)
        dao.claimNextPendingTarget(
            REQUEST_1,
            "request-claim",
            "target-claim",
            2_200L,
            4_200L,
        )

        assertFalse(dao.authorizeUnknownTargetRetry(REQUEST_1, 1, 2_300L))
        val snapshot = requireNotNull(dao.load(REQUEST_1))
        assertEquals(StoredSweepRequestState.RUNNING.name, snapshot.request.state)
        assertEquals("request-claim", snapshot.request.claimToken)
        assertEquals(StoredSweepTargetState.RUNNING.name, snapshot.targets[0].state)
        assertEquals("target-claim", snapshot.targets[0].claimToken)
        assertEquals(StoredSweepTargetState.LEGACY_UNKNOWN.name, snapshot.targets[1].state)
        assertNull(
            dao.claimOldestRunnableRequest("other-session", "other-claim", 2_400L, 4_400L),
        )
    }

    @Test
    fun completedSuccessWinsCancellationBeforeExplicitFinish() = runBlocking {
        insertSweep(requestId = REQUEST_1)
        dao.claimOldestRunnableRequest("session", "request-claim", 2_000L, 4_000L)
        dao.claimNextPendingTarget(
            REQUEST_1,
            "request-claim",
            "target-claim",
            2_100L,
            4_100L,
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

        assertTrue(dao.requestCancellation(REQUEST_1, 2_300L) is SweepCancellationDecision.Settled)
        val request = requireNotNull(dao.load(REQUEST_1)).request
        assertEquals(StoredSweepRequestState.SUCCEEDED.name, request.state)
        assertEquals(StoredSweepRequestState.SUCCEEDED.name, request.terminalState)
        assertEquals(Aggregates(1, 0, 0, 0), aggregates())
    }

    @Test
    fun completedSuccessPlusPendingCancellationBecomesPartial() = runBlocking {
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
        assertTrue(
            dao.completeClaimedTarget(
                REQUEST_1,
                0,
                "request-claim",
                "target-claim",
                successfulResult(2_200L),
            ),
        )

        assertTrue(dao.requestCancellation(REQUEST_1, 2_300L) is SweepCancellationDecision.Settled)
        val snapshot = requireNotNull(dao.load(REQUEST_1))
        assertEquals(StoredSweepRequestState.PARTIAL.name, snapshot.request.state)
        assertEquals(StoredSweepRequestState.PARTIAL.name, snapshot.request.terminalState)
        assertEquals(
            listOf(StoredSweepTargetState.SUCCEEDED, StoredSweepTargetState.CANCELLED),
            snapshot.targets.sortedBy(SweepTargetEntity::ordinal)
                .map { StoredSweepTargetState.valueOf(it.state) },
        )
        assertEquals(Aggregates(1, 0, 0, 1), aggregates())
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

        val recoveryCandidate = dao.recoverRequestClaims(
            sessionToken = "replacement-session",
            nowMs = 4_200L,
        ) { _, _ -> false }.single()
        assertTrue(
            dao.recoverInterruptedTarget(
                recoveryCandidate,
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
        val recoveryCandidate = dao.recoverRequestClaims(
            sessionToken = "replacement-session",
            nowMs = 5_500L,
        ) { _, _ -> false }.single()
        assertTrue(
            dao.recoverInterruptedTarget(
                recoveryCandidate,
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

    private fun seedPartialTargetOwnership(
        requestId: String,
        ordinal: Int,
    ) = seedTargetOwnership(requestId, ordinal, claimToken = null, leaseUntilMs = 9_000L)

    private fun seedTargetOwnership(
        requestId: String,
        ordinal: Int,
        claimToken: String?,
        leaseUntilMs: Long?,
    ) {
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sweep_targets
            SET claim_token = ?,
                claim_lease_expires_at_epoch_ms = ?
            WHERE request_id = ? AND ordinal = ?
            """.trimIndent(),
            arrayOf<Any?>(claimToken, leaseUntilMs, requestId, ordinal),
        )
    }

    private fun seedQueuedRequestOwnership(
        sessionToken: String?,
        claimToken: String?,
        leaseUntilMs: Long?,
    ) {
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sweep_requests
            SET service_session_token = ?,
                claim_token = ?,
                claim_lease_expires_at_epoch_ms = ?
            WHERE request_id = ?
            """.trimIndent(),
            arrayOf<Any?>(sessionToken, claimToken, leaseUntilMs, REQUEST_1),
        )
    }

    private fun seedRequestOwnership(
        sessionToken: String,
        claimToken: String,
    ) {
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sweep_requests
            SET service_session_token = ?,
                claim_token = ?,
                claim_lease_expires_at_epoch_ms = 3000
            WHERE request_id = ?
            """.trimIndent(),
            arrayOf<Any?>(sessionToken, claimToken, REQUEST_1),
        )
    }

    private fun deleteSweep() {
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM sweep_requests WHERE request_id = ?",
            arrayOf(REQUEST_1),
        )
    }

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
        const val REQUEST_3 = "00000000-0000-0000-0000-000000000103"
        const val SERVICE_EXECUTION_ID = "00000000-0000-8000-8000-000000000301"

        val MALFORMED_OWNERSHIP_TOKENS = listOf(
            "spaces" to "   ",
            "tab" to "\t",
            "newline" to "\n",
            "mixed ASCII whitespace" to " \t\n",
            "Unicode whitespace" to " ",
            "punctuation" to "claim/token",
        )
        val VALID_OWNERSHIP_TOKENS = listOf(
            "00000000-0000-0000-0000-000000000201",
            "request-claim",
            "target_1",
            "session:1",
            "claim.1",
        )
    }
}
