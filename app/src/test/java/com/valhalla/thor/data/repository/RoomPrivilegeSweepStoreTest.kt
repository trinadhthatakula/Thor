// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.source.local.room.AppDatabase
import com.valhalla.thor.data.source.local.room.SweepRequestEntity
import com.valhalla.thor.data.source.local.room.SweepRequestSourceEntity
import com.valhalla.thor.data.source.local.room.SweepTargetEntity
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.repository.ClaimedPrivilegeSweepRequest
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeSweepCancellationDecision
import com.valhalla.thor.domain.repository.PrivilegeSweepRecovery
import com.valhalla.thor.domain.repository.PrivilegeSweepResultCode
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetResult
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetState
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetTerminalState
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import com.valhalla.thor.domain.repository.SweepCreateResult
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomPrivilegeSweepStoreTest {

    private lateinit var database: AppDatabase
    private lateinit var store: RoomPrivilegeSweepStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = RoomPrivilegeSweepStore(database.privilegeSweepDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `service transitions delegate IDs reasons and exact exit settlement`() = runTest {
        val request = newSnapshot(listOf("com.example.alpha", "com.example.beta"))
        store.createOrFindEquivalent(request)
        val id = request.requestId
        val start = com.valhalla.thor.domain.repository.PrivilegeSweepBlockReason.START_BLOCKED
        val privilege = com.valhalla.thor.domain.repository.PrivilegeSweepBlockReason.PRIVILEGE_AUTHORIZATION_REQUIRED
        assertTrue(store.markUnclaimedStartBlocked(id, start, 2))
        assertFalse(store.resumeBlockedRequest(id, privilege, 3))
        assertTrue(store.resumeBlockedRequest(id, start, 4))
        val claim = requireNotNull(store.claimOldestRunnableRequest("session", "owner", 5, 100))
        assertFalse(store.blockClaimedRequestForMissingPrivilege(id, "stale", 6))
        assertTrue(store.blockClaimedRequestForMissingPrivilege(id, claim.claimToken, 6))
        assertTrue(store.resumeBlockedRequest(id, privilege, 7))
        assertTrue(store.markLegacyTargetsUnknown(id, listOf(0), 8))
        val second = requireNotNull(store.claimOldestRunnableRequest("session", "owner2", 9, 100))
        requireNotNull(store.claimNextPendingTarget(id, second.claimToken, "target", 10, 100))
        assertTrue(store.settleClaimedRequestAfterExit(id, second.claimToken, 11))
        val row = requireNotNull(store.load(id))
        assertEquals(listOf(PrivilegeSweepTargetState.LEGACY_UNKNOWN, PrivilegeSweepTargetState.UNKNOWN), row.targetSnapshots.map { it.state })
        assertEquals(request.sourceAssociations, row.sourceAssociations)
        assertEquals(request.executionId, row.executionId)
        assertEquals(2, row.unresolved)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `snapshots retain legacy work id and expose neutral execution id with ordered targets`() =
        runTest {
            val request = newSnapshot(targets = listOf("com.example.alpha", "com.example.beta"))

            val stored =
                (store.createOrFindEquivalent(request) as SweepCreateResult.Created).snapshot
            val loaded = checkNotNull(store.load(request.requestId))

            assertEquals(request.workId, request.executionId)
            assertEquals(stored.workId, stored.executionId)
            assertEquals(request.executionId, loaded.executionId)
            assertEquals(listOf("com.example.alpha", "com.example.beta"), loaded.targets)
            assertEquals(listOf(0, 1), loaded.targetSnapshots.map { it.ordinal })
            assertEquals(
                listOf(PrivilegeSweepTargetState.PENDING, PrivilegeSweepTargetState.PENDING),
                loaded.targetSnapshots.map { it.state },
            )
            assertEquals(2, loaded.unresolved)
        }

    @Test
    @Suppress("DEPRECATION")
    fun `stored legacy work identity stays distinct from claimed execution identity`() = runTest {
        val requestId = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val workId = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val dao = database.privilegeSweepDao()
        dao.insertRequest(
            SweepRequestEntity(
                requestId = requestId.toString(),
                workId = workId.toString(),
                operation = PrivilegeSweepOperation.CLEAR_CACHE.name,
                freezerMode = null,
                userId = 10,
                sourceSurface = PrivilegeSweepSource.MAIN.name,
                createdAtEpochMs = 1L,
                terminalState = null,
                unresolved = 1,
                terminalAtEpochMs = null,
                retainUntilEpochMs = null,
                executionId = requestId.toString(),
            )
        )
        dao.insertTargets(
            listOf(
                SweepTargetEntity(
                    requestId = requestId.toString(),
                    ordinal = 0,
                    packageName = "com.example.identity",
                )
            )
        )
        dao.upsertSources(
            listOf(
                SweepRequestSourceEntity(
                    requestId = requestId.toString(),
                    sourceSurface = PrivilegeSweepSource.MAIN.name,
                    associatedAtEpochMs = 1L,
                )
            )
        )

        val stored = checkNotNull(store.load(requestId))
        val claimed = checkNotNull(
            store.claimOldestRunnableRequest(
                sessionToken = "session-identity",
                claimToken = "request-identity",
                nowMs = 2_000L,
                leaseUntilMs = 3_000L,
            )
        )

        assertEquals(requestId, stored.requestId)
        assertEquals(workId, stored.workId)
        assertEquals(workId, stored.executionId)
        assertEquals(requestId, claimed.executionId)
    }

    @Test
    fun `claim renew complete and finalize map every token and target result field`() = runTest {
        val request = newSnapshot(targets = listOf("com.example.alpha", "com.example.beta"))
        store.createOrFindEquivalent(request)

        val claimed = checkNotNull(
            store.claimOldestRunnableRequest(
                sessionToken = "session-1",
                claimToken = "request-1",
                nowMs = 2_000L,
                leaseUntilMs = 3_000L,
            )
        )
        assertClaimedRequest(request, claimed)
        assertTrue(store.renewRequestClaim(request.requestId, "request-1", 3_500L))
        assertFalse(store.renewRequestClaim(request.requestId, "stale-request", 3_600L))

        val first = checkNotNull(
            store.claimNextPendingTarget(
                requestId = request.requestId,
                requestClaimToken = "request-1",
                targetClaimToken = "target-1",
                nowMs = 2_100L,
                leaseUntilMs = 3_100L,
            )
        )
        assertEquals(request.requestId, first.requestId)
        assertEquals(0, first.ordinal)
        assertEquals("com.example.alpha", first.packageName)
        assertEquals("target-1", first.claimToken)
        assertTrue(store.renewTargetClaim(request.requestId, 0, "target-1", 3_700L))
        assertFalse(store.renewTargetClaim(request.requestId, 0, "stale-target", 3_800L))
        assertFalse(
            store.completeClaimedTarget(
                requestId = request.requestId,
                ordinal = 0,
                requestClaimToken = "request-1",
                targetClaimToken = "stale-target",
                result = targetResult(
                    PrivilegeSweepTargetTerminalState.SUCCEEDED,
                    "FIRST_OK",
                    finishedAtEpochMs = 2_200L,
                ),
            )
        )
        assertTrue(
            store.completeClaimedTarget(
                requestId = request.requestId,
                ordinal = 0,
                requestClaimToken = "request-1",
                targetClaimToken = "target-1",
                result = targetResult(
                    PrivilegeSweepTargetTerminalState.SUCCEEDED,
                    "FIRST_OK",
                    rootLaneDegraded = true,
                    finishedAtEpochMs = 2_200L,
                ),
            )
        )

        val second = checkNotNull(
            store.claimNextPendingTarget(
                requestId = request.requestId,
                requestClaimToken = "request-1",
                targetClaimToken = "target-2",
                nowMs = 2_300L,
                leaseUntilMs = 3_300L,
            )
        )
        assertEquals(1, second.ordinal)
        assertTrue(
            store.completeClaimedTarget(
                requestId = request.requestId,
                ordinal = 1,
                requestClaimToken = "request-1",
                targetClaimToken = "target-2",
                result = targetResult(
                    PrivilegeSweepTargetTerminalState.BUSY,
                    "SECOND_BUSY",
                    finishedAtEpochMs = 2_400L,
                ),
            )
        )
        assertTrue(store.finishClaimedRequestIfDrained(request.requestId, "request-1", 2_500L))
        assertFalse(store.finishClaimedRequestIfDrained(request.requestId, "request-1", 2_600L))

        val finished = checkNotNull(store.load(request.requestId))
        assertEquals(StoredSweepTerminal.PARTIAL, finished.terminalState)
        assertEquals(1, finished.succeeded)
        assertEquals(0, finished.failed)
        assertEquals(1, finished.busy)
        assertEquals(0, finished.unresolved)
        assertEquals(
            listOf("FIRST_OK", "SECOND_BUSY"),
            finished.targetSnapshots.map { it.resultCode?.value },
        )
        assertEquals(listOf(true, false), finished.targetSnapshots.map { it.rootLaneDegraded })
    }

    @Test
    fun `cancellation decisions preserve UUID state and active target ordinal`() = runTest {
        val missing = UUID.randomUUID()
        assertEquals(
            PrivilegeSweepCancellationDecision.NotFound,
            store.requestCancellation(missing, 1_000L),
        )

        val settledRequest = newSnapshot(targets = listOf("com.example.settled"))
        store.createOrFindEquivalent(settledRequest)
        assertEquals(
            PrivilegeSweepCancellationDecision.Settled(settledRequest.requestId),
            store.requestCancellation(settledRequest.requestId, 1_100L),
        )
        assertEquals(
            PrivilegeSweepCancellationDecision.AlreadyTerminal(
                settledRequest.requestId,
                com.valhalla.thor.domain.repository.PrivilegeSweepRequestState.CANCELLED,
            ),
            store.requestCancellation(settledRequest.requestId, 1_200L),
        )

        val activeRequest =
            newSnapshot(targets = listOf("com.example.active"), createdAtEpochMs = 2L)
        store.createOrFindEquivalent(activeRequest)
        checkNotNull(store.claimOldestRunnableRequest("session", "request", 2_000L, 3_000L))
        checkNotNull(
            store.claimNextPendingTarget(
                activeRequest.requestId,
                "request",
                "target",
                2_100L,
                3_100L,
            )
        )
        assertEquals(
            PrivilegeSweepCancellationDecision.InterruptActive(activeRequest.requestId, 0),
            store.requestCancellation(activeRequest.requestId, 2_200L),
        )
    }

    @Test
    fun `claim recovery maps frozen ownership and supports both recovery CAS forms`() = runTest {
        val request = newSnapshot(targets = listOf("com.example.recover"))
        store.createOrFindEquivalent(request)
        checkNotNull(store.claimOldestRunnableRequest("old-session", "old-request", 2_000L, 4_000L))
        checkNotNull(
            store.claimNextPendingTarget(
                request.requestId,
                "old-request",
                "old-target",
                2_100L,
                4_100L,
            )
        )
        var callbackRequest: UUID? = null
        var callbackClaim: String? = null

        val protected = store.recoverRequestClaims("new-session", 2_200L) { requestId, claimToken ->
            callbackRequest = requestId
            callbackClaim = claimToken
            true
        }
        assertTrue(protected.isEmpty())
        assertEquals(request.requestId, callbackRequest)
        assertEquals("old-request", callbackClaim)

        val candidate = store.recoverRequestClaims("new-session", 2_300L) { _, _ -> false }.single()
        assertEquals(request.requestId, candidate.requestId)
        assertEquals(0, candidate.activeTargetOrdinal)
        assertEquals("com.example.recover", candidate.packageName)
        assertEquals("old-session", candidate.previousServiceSessionToken)
        assertEquals("old-request", candidate.previousRequestClaimToken)
        assertEquals("old-target", candidate.activeTargetClaimToken)

        val unknown = PrivilegeSweepRecovery.MarkUnknown(
            PrivilegeSweepResultCode("RECOVERY_INSPECTION_UNAVAILABLE"),
            recoveredAtEpochMs = 2_400L,
        )
        assertTrue(store.recoverInterruptedTarget(candidate, unknown))
        assertFalse(store.recoverInterruptedTarget(candidate, unknown))
        assertEquals(
            PrivilegeSweepTargetState.UNKNOWN,
            checkNotNull(store.load(request.requestId)).targetSnapshots.single().state,
        )

        assertTrue(
            store.recoverInterruptedTarget(
                requestId = request.requestId,
                ordinal = 0,
                recovery = PrivilegeSweepRecovery.Requeue(
                    PrivilegeSweepResultCode("RECOVERY_RETRY_FREEZE"),
                    recoveredAtEpochMs = 2_500L,
                ),
            )
        )
        assertTrue(store.hasRunnableRequests())
    }

    @Test
    fun `explicit retry authorization and final drain preserve DAO decisions`() = runTest {
        var stopped = false
        assertTrue(store.finishDrainIfQueueEmpty { stopped = true })
        assertTrue(stopped)

        val request = newSnapshot(targets = listOf("com.example.retry"))
        store.createOrFindEquivalent(request)
        assertTrue(store.hasRunnableRequests())
        var calledWhileBusy = false
        assertFalse(store.finishDrainIfQueueEmpty { calledWhileBusy = true })
        assertFalse(calledWhileBusy)

        checkNotNull(store.claimOldestRunnableRequest("old-session", "old-request", 2_000L, 3_000L))
        checkNotNull(
            store.claimNextPendingTarget(
                request.requestId,
                "old-request",
                "old-target",
                2_100L,
                3_100L,
            )
        )
        val candidate = store.recoverRequestClaims("new-session", 2_200L) { _, _ -> false }.single()
        assertTrue(
            store.recoverInterruptedTarget(
                candidate,
                PrivilegeSweepRecovery.MarkUnknown(
                    PrivilegeSweepResultCode("CLEAR_CACHE_OUTCOME_UNKNOWN"),
                    recoveredAtEpochMs = 2_300L,
                ),
            )
        )
        assertTrue(store.authorizeUnknownTargetRetry(request.requestId, 0, 2_400L))
        assertFalse(store.authorizeUnknownTargetRetry(request.requestId, 0, 2_500L))
        assertTrue(store.hasRunnableRequests())
    }

    @Test
    fun `missing and stale claim aware operations preserve false and null results`() = runTest {
        val requestId = UUID.randomUUID()
        assertNull(
            store.claimNextPendingTarget(
                requestId,
                "request-token",
                "target-token",
                1_000L,
                2_000L,
            )
        )
        assertFalse(store.renewRequestClaim(requestId, "request-token", 2_000L))
        assertFalse(store.renewTargetClaim(requestId, 0, "target-token", 2_000L))
        assertFalse(
            store.completeClaimedTarget(
                requestId,
                0,
                "request-token",
                "target-token",
                targetResult(
                    PrivilegeSweepTargetTerminalState.FAILED,
                    "PACKAGE_ABSENT",
                    finishedAtEpochMs = 1_000L,
                ),
            )
        )
        assertFalse(
            store.recoverInterruptedTarget(
                requestId,
                0,
                PrivilegeSweepRecovery.MarkUnknown(
                    PrivilegeSweepResultCode("RECOVERY_INSPECTION_UNAVAILABLE"),
                    recoveredAtEpochMs = 1_000L,
                ),
            )
        )
        assertFalse(store.authorizeUnknownTargetRetry(requestId, 0, 1_000L))
        assertFalse(store.finishClaimedRequestIfDrained(requestId, "request-token", 1_000L))
    }

    @Test
    fun `result code rejects unsafe persisted values`() {
        listOf(
            "",
            "lowercase",
            "WITH-DASH",
            "WITH SPACE",
            "WITH.PUNCTUATION",
            "A".repeat(65),
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                PrivilegeSweepResultCode(value)
            }
        }
        assertEquals("A_9", PrivilegeSweepResultCode("A_9").value)
        assertEquals(64, PrivilegeSweepResultCode("A".repeat(64)).value.length)
    }

    private fun assertClaimedRequest(
        expected: NewPrivilegeSweepSnapshot,
        actual: ClaimedPrivilegeSweepRequest,
    ) {
        assertEquals(expected.requestId, actual.requestId)
        assertEquals(expected.executionId, actual.executionId)
        assertEquals(expected.operation, actual.operation)
        assertEquals(expected.freezerMode, actual.freezerMode)
        assertEquals(expected.userId, actual.userId)
        assertEquals(expected.source, actual.source)
        assertEquals(expected.sourceAssociations, actual.sourceAssociations)
        assertEquals(expected.targets.size, actual.targetCount)
        assertEquals(0, actual.succeeded)
        assertEquals(0, actual.failed)
        assertEquals(0, actual.busy)
        assertEquals(expected.targets.size, actual.unresolved)
        assertEquals("session-1", actual.serviceSessionToken)
        assertEquals("request-1", actual.claimToken)
        assertEquals(3_000L, actual.claimLeaseExpiresAtEpochMs)
        assertEquals(1, actual.attemptCount)
        assertEquals(expected.createdAtEpochMs, actual.createdAtEpochMs)
        assertEquals(2_000L, actual.claimedAtEpochMs)
    }

    private fun targetResult(
        terminalState: PrivilegeSweepTargetTerminalState,
        code: String,
        rootLaneDegraded: Boolean = false,
        finishedAtEpochMs: Long,
    ) = PrivilegeSweepTargetResult(
        terminalState = terminalState,
        resultCode = PrivilegeSweepResultCode(code),
        rootLaneDegraded = rootLaneDegraded,
        finishedAtEpochMs = finishedAtEpochMs,
    )

    private fun newSnapshot(
        targets: List<String>,
        operation: PrivilegeSweepOperation = PrivilegeSweepOperation.FREEZE,
        freezerMode: FreezerMode? = FreezerMode.FREEZE,
        createdAtEpochMs: Long = 1L,
    ) = NewPrivilegeSweepSnapshot(
        requestId = UUID.randomUUID(),
        workId = UUID.randomUUID(),
        operation = operation,
        freezerMode = freezerMode,
        userId = 10,
        source = PrivilegeSweepSource.MAIN,
        createdAtEpochMs = createdAtEpochMs,
        targets = targets,
        sourceAssociations = setOf(PrivilegeSweepSource.MAIN.name, "PROFILE:test"),
    )
}
