// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import androidx.work.OneTimeWorkRequest
import com.valhalla.thor.domain.model.FreezeState
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PackageOperationCoordinator
import com.valhalla.thor.domain.repository.PrivilegeSweepRecovery
import com.valhalla.thor.domain.repository.PrivilegeSweepRecoveryCandidate
import com.valhalla.thor.domain.repository.PrivilegeSweepResultCode
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetResult
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetState
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetTerminalState
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.StoredPrivilegeSweepTarget
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import com.valhalla.thor.domain.repository.SweepCreateResult
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeSweepReconcilerTest {

    @Test
    fun `freeze and unfreeze recovery maps every observed state inside the package lease`() =
        runTest {
            val cases = listOf(
                RecoveryCase(
                    PrivilegeSweepOperation.FREEZE,
                    FreezeState.FROZEN,
                    completed(
                        PrivilegeSweepTargetTerminalState.SUCCEEDED,
                        "RECOVERED_ALREADY_FROZEN"
                    ),
                ),
                RecoveryCase(
                    PrivilegeSweepOperation.FREEZE,
                    FreezeState.ACTIVE,
                    requeue("RECOVERY_RETRY_FREEZE"),
                ),
                RecoveryCase(
                    PrivilegeSweepOperation.FREEZE,
                    FreezeState.ABSENT,
                    completed(PrivilegeSweepTargetTerminalState.FAILED, "PACKAGE_ABSENT"),
                ),
                RecoveryCase(
                    PrivilegeSweepOperation.UNFREEZE,
                    FreezeState.ACTIVE,
                    completed(
                        PrivilegeSweepTargetTerminalState.SUCCEEDED,
                        "RECOVERED_ALREADY_ACTIVE"
                    ),
                ),
                RecoveryCase(
                    PrivilegeSweepOperation.UNFREEZE,
                    FreezeState.FROZEN,
                    requeue("RECOVERY_RETRY_UNFREEZE"),
                ),
                RecoveryCase(
                    PrivilegeSweepOperation.UNFREEZE,
                    FreezeState.ABSENT,
                    completed(PrivilegeSweepTargetTerminalState.FAILED, "PACKAGE_ABSENT"),
                ),
            )

            cases.forEach { case ->
                val trace = mutableListOf<String>()
                val snapshot = stored(operation = case.operation)
                val candidate = candidate(snapshot)
                val store = FakeStore(snapshot, listOf(candidate))
                val coordinator = RecordingCoordinator(trace = trace)
                val reconciler = reconciler(
                    store = store,
                    stateReader = RecordingStateReader(case.state, trace),
                    coordinator = coordinator,
                )

                reconciler.reconcileInterruptedClaims(
                    "current-session",
                    { _, _ -> false }) { _, _, _, _ ->
                    error("reinstall verifier must not run for ${case.operation}")
                }

                assertEquals(case.operation.name, listOf(case.expected), store.candidateRecoveries)
                assertEquals(
                    case.operation.name,
                    listOf(
                        "lease:start:${case.operation.owner()}",
                        "state:$PACKAGE",
                        "lease:end:${case.operation.owner()}",
                    ),
                    trace,
                )
                assertEquals(case.operation.name, 1, coordinator.leaseCalls)
            }
        }

    @Test
    fun `reinstall recovery verifies postcondition inside lease without replay`() = runTest {
        val cases = listOf(
            ReinstallPostcondition.SATISFIED to completed(
                PrivilegeSweepTargetTerminalState.SUCCEEDED,
                "REINSTALL_POSTCONDITION_VERIFIED",
            ),
            ReinstallPostcondition.NOT_SATISFIED to requeue("RECOVERY_RETRY_REINSTALL"),
            ReinstallPostcondition.UNKNOWN to unknown("REINSTALL_POSTCONDITION_UNKNOWN"),
        )

        cases.forEach { (postcondition, expected) ->
            val trace = mutableListOf<String>()
            val snapshot = stored(operation = PrivilegeSweepOperation.REINSTALL)
            val store = FakeStore(snapshot, listOf(candidate(snapshot)))
            val coordinator = RecordingCoordinator(trace = trace)
            val reconciler = reconciler(
                store,
                RecordingStateReader(FreezeState.ACTIVE, trace),
                coordinator,
            )

            reconciler.reconcileInterruptedClaims(
                "current-session",
                { _, _ -> false }) { packageName, userId, executionId, requestId ->
                trace += "verify:$packageName:$userId:$executionId:$requestId"
                postcondition
            }

            assertEquals(listOf(expected), store.candidateRecoveries)
            assertEquals(
                listOf(
                    "lease:start:${PackageOperationOwner.REINSTALL}",
                    "verify:$PACKAGE:${snapshot.userId}:${snapshot.executionId}:${snapshot.requestId}",
                    "lease:end:${PackageOperationOwner.REINSTALL}",
                ),
                trace,
            )
        }
    }

    @Test
    fun `interrupted clear cache is marked unknown without inspection lease or replay`() = runTest {
        val snapshot = stored(operation = PrivilegeSweepOperation.CLEAR_CACHE)
        val store = FakeStore(snapshot, listOf(candidate(snapshot)))
        val stateReader = RecordingStateReader(FreezeState.ACTIVE)
        val coordinator = RecordingCoordinator()
        var verifierCalls = 0

        reconciler(store, stateReader, coordinator).reconcileInterruptedClaims(
            "current-session",
            { _, _ -> false },
        ) { _, _, _, _ ->
            verifierCalls++
            ReinstallPostcondition.SATISFIED
        }

        assertEquals(listOf(unknown("CLEAR_CACHE_OUTCOME_UNKNOWN")), store.candidateRecoveries)
        assertEquals(0, stateReader.calls)
        assertEquals(0, coordinator.leaseCalls)
        assertEquals(0, verifierCalls)
    }

    @Test
    fun `inspection failure and package lease contention become sanitized unknown outcomes`() =
        runTest {
            val throwingSnapshot = stored(operation = PrivilegeSweepOperation.FREEZE)
            val throwingStore = FakeStore(throwingSnapshot, listOf(candidate(throwingSnapshot)))
            reconciler(
                throwingStore,
                RecordingStateReader(failure = IllegalStateException("dynamic path /data/local/tmp")),
                RecordingCoordinator(),
            ).reconcileInterruptedClaims("current-session", { _, _ -> false }, verifier())
            assertEquals(
                listOf(unknown("RECOVERY_INSPECTION_UNAVAILABLE")),
                throwingStore.candidateRecoveries,
            )

            val busySnapshot = stored(operation = PrivilegeSweepOperation.REINSTALL)
            val busyStore = FakeStore(busySnapshot, listOf(candidate(busySnapshot)))
            var verifierCalls = 0
            reconciler(
                busyStore,
                RecordingStateReader(FreezeState.ACTIVE),
                RecordingCoordinator(busyOwner = PackageOperationOwner.ARCHIVE_BACKUP),
            ).reconcileInterruptedClaims("current-session", { _, _ -> false }) { _, _, _, _ ->
                verifierCalls++
                ReinstallPostcondition.SATISFIED
            }
            assertEquals(
                listOf(unknown("REINSTALL_POSTCONDITION_UNKNOWN")),
                busyStore.candidateRecoveries,
            )
            assertEquals(0, verifierCalls)
        }

    @Test
    fun `known live owner is protected before inspection`() = runTest {
        val snapshot = stored(operation = PrivilegeSweepOperation.FREEZE)
        val candidate = candidate(snapshot)
        val store = FakeStore(snapshot, listOf(candidate))
        val stateReader = RecordingStateReader(FreezeState.FROZEN)
        val coordinator = RecordingCoordinator()
        var callbackArguments: Pair<UUID, String>? = null

        reconciler(store, stateReader, coordinator).reconcileInterruptedClaims(
            currentSessionToken = "current-session",
            localOwnerIsLive = { requestId, requestClaimToken ->
                callbackArguments = requestId to requestClaimToken
                true
            },
            reinstallVerifier = verifier(),
        )

        assertEquals(snapshot.requestId to "old-request", callbackArguments)
        assertTrue(store.candidateRecoveries.isEmpty())
        assertEquals(0, stateReader.calls)
        assertEquals(0, coordinator.leaseCalls)
    }

    @Test
    fun `terminal targets are not inspected or replayed`() = runTest {
        val snapshot = stored(
            operation = PrivilegeSweepOperation.FREEZE,
            targetState = PrivilegeSweepTargetState.SUCCEEDED,
            targetResultCode = "ALREADY_DONE",
        )
        val store = FakeStore(snapshot, listOf(candidate(snapshot)))
        val stateReader = RecordingStateReader(FreezeState.ACTIVE)
        val coordinator = RecordingCoordinator()

        reconciler(store, stateReader, coordinator).reconcileInterruptedClaims(
            "current-session",
            { _, _ -> false },
            verifier(),
        )

        assertTrue(store.candidateRecoveries.isEmpty())
        assertEquals(0, stateReader.calls)
        assertEquals(0, coordinator.leaseCalls)
    }

    @Test
    fun `stale recovery CAS is attempted once without same pass retry`() = runTest {
        val snapshot = stored(operation = PrivilegeSweepOperation.FREEZE)
        val store = FakeStore(snapshot, listOf(candidate(snapshot))).apply {
            candidateRecoveryResult = false
        }

        reconciler(store).reconcileInterruptedClaims(
            "current-session",
            { _, _ -> false },
            verifier(),
        )

        assertEquals(1, store.candidateRecoveryAttempts)
        assertEquals(listOf(requeue("RECOVERY_RETRY_FREEZE")), store.candidateRecoveries)
    }

    @Test
    fun `unknown and migrated legacy unknown targets reconcile but pending and terminal do not`() =
        runTest {
            listOf(
                PrivilegeSweepTargetState.UNKNOWN,
                PrivilegeSweepTargetState.LEGACY_UNKNOWN,
            ).forEach { state ->
                val snapshot = stored(
                    operation = PrivilegeSweepOperation.UNFREEZE,
                    targetState = state,
                )
                val store = FakeStore(snapshot)
                assertTrue(
                    reconciler(
                        store,
                        RecordingStateReader(FreezeState.ACTIVE),
                    ).reconcileUnknownTarget(snapshot.requestId, 0, verifier())
                )
                assertEquals(
                    listOf(
                        completed(
                            PrivilegeSweepTargetTerminalState.SUCCEEDED,
                            "RECOVERED_ALREADY_ACTIVE"
                        )
                    ),
                    store.unownedRecoveries,
                )
            }

            listOf(
                stored(
                    operation = PrivilegeSweepOperation.FREEZE,
                    targetState = PrivilegeSweepTargetState.PENDING,
                ),
                stored(
                    operation = PrivilegeSweepOperation.FREEZE,
                    targetState = PrivilegeSweepTargetState.FAILED,
                    targetResultCode = "PACKAGE_ABSENT",
                ),
                stored(
                    operation = PrivilegeSweepOperation.FREEZE,
                    targetState = PrivilegeSweepTargetState.UNKNOWN,
                    terminal = StoredSweepTerminal.FAILED,
                ),
            ).forEach { snapshot ->
                val stateReader = RecordingStateReader(FreezeState.FROZEN)
                val store = FakeStore(snapshot)
                assertFalse(
                    reconciler(store, stateReader).reconcileUnknownTarget(
                        snapshot.requestId,
                        0,
                        verifier(),
                    )
                )
                assertEquals(0, stateReader.calls)
                assertTrue(store.unownedRecoveries.isEmpty())
            }
        }

    @Test
    fun `owned unknown target is not inspected or reconciled`() = runTest {
        val unowned = stored(
            operation = PrivilegeSweepOperation.FREEZE,
            targetState = PrivilegeSweepTargetState.UNKNOWN,
        )
        val owned = unowned.copy(
            targetSnapshots = listOf(
                unowned.targetSnapshots.single().copy(
                    claimToken = "still-owned",
                    claimLeaseExpiresAtEpochMs = 3_000L,
                )
            )
        )
        val stateReader = RecordingStateReader(FreezeState.FROZEN)
        val coordinator = RecordingCoordinator()
        val store = FakeStore(owned)

        assertFalse(
            reconciler(store, stateReader, coordinator).reconcileUnknownTarget(
                owned.requestId,
                0,
                verifier(),
            )
        )
        assertEquals(0, stateReader.calls)
        assertEquals(0, coordinator.leaseCalls)
        assertTrue(store.unownedRecoveries.isEmpty())
    }

    @Test
    fun `unknown reinstall requeues only when verifier proves postcondition unsatisfied`() =
        runTest {
            val snapshot = stored(
                operation = PrivilegeSweepOperation.REINSTALL,
                targetState = PrivilegeSweepTargetState.LEGACY_UNKNOWN,
            )
            val store = FakeStore(snapshot)

            assertTrue(
                reconciler(store).reconcileUnknownTarget(snapshot.requestId, 0) { _, _, _, _ ->
                    ReinstallPostcondition.NOT_SATISFIED
                }
            )
            assertEquals(listOf(requeue("RECOVERY_RETRY_REINSTALL")), store.unownedRecoveries)
        }

    @Test
    fun `unknown clear cache remains non runnable without inspection`() = runTest {
        val snapshot = stored(
            operation = PrivilegeSweepOperation.CLEAR_CACHE,
            targetState = PrivilegeSweepTargetState.UNKNOWN,
        )
        val store = FakeStore(snapshot)
        val stateReader = RecordingStateReader(FreezeState.ACTIVE)
        val coordinator = RecordingCoordinator()

        assertTrue(
            reconciler(store, stateReader, coordinator).reconcileUnknownTarget(
                snapshot.requestId,
                0,
                verifier(),
            )
        )
        assertEquals(listOf(unknown("CLEAR_CACHE_OUTCOME_UNKNOWN")), store.unownedRecoveries)
        assertEquals(0, stateReader.calls)
        assertEquals(0, coordinator.leaseCalls)
    }

    @Test
    fun `legacy WorkManager reconciliation stays separate from claim recovery`() = runTest {
        val snapshot = stored(operation = PrivilegeSweepOperation.FREEZE)
        val store = FakeStore(snapshot, listOf(candidate(snapshot)))
        val work = FakeWorkManager(mapOf(snapshot.executionId to SweepWorkState.CANCELLED))

        PrivilegeSweepReconciler(store, work, FixedClock(), PrivilegeSweepProcessGate()).reconcile()

        assertEquals(
            listOf(Triple(snapshot.requestId, StoredSweepTerminal.CANCELLED, NOW_MS)),
            store.legacyFinishes,
        )
        assertEquals(1, store.deleteExpiredCalls)
        assertEquals(0, store.recoverRequestClaimsCalls)
        assertTrue(store.candidateRecoveries.isEmpty())
    }

    private fun reconciler(
        store: FakeStore,
        stateReader: PrivilegeSweepPackageStateReader = RecordingStateReader(FreezeState.ACTIVE),
        coordinator: PackageOperationCoordinator = RecordingCoordinator(),
    ) = PrivilegeSweepReconciler(
        store = store,
        workManager = FakeWorkManager(),
        clock = FixedClock(),
        gate = PrivilegeSweepProcessGate(),
        stateReader = stateReader,
        packageOperationCoordinator = coordinator,
    )

    private fun verifier() = PrivilegeSweepReinstallPostconditionVerifier { _, _, _, _ ->
        ReinstallPostcondition.SATISFIED
    }

    private fun completed(
        terminal: PrivilegeSweepTargetTerminalState,
        code: String,
    ) = PrivilegeSweepRecovery.Completed(
        result = PrivilegeSweepTargetResult(
            terminalState = terminal,
            resultCode = PrivilegeSweepResultCode(code),
            rootLaneDegraded = false,
            finishedAtEpochMs = NOW_MS,
        ),
        recoveredAtEpochMs = NOW_MS,
    )

    private fun requeue(code: String) = PrivilegeSweepRecovery.Requeue(
        resultCode = PrivilegeSweepResultCode(code),
        recoveredAtEpochMs = NOW_MS,
    )

    private fun unknown(code: String) = PrivilegeSweepRecovery.MarkUnknown(
        resultCode = PrivilegeSweepResultCode(code),
        recoveredAtEpochMs = NOW_MS,
    )

    private fun candidate(snapshot: StoredPrivilegeSweep) = PrivilegeSweepRecoveryCandidate(
        requestId = snapshot.requestId,
        operation = snapshot.operation,
        freezerMode = snapshot.freezerMode,
        userId = snapshot.userId,
        activeTargetOrdinal = 0,
        packageName = PACKAGE,
        previousServiceSessionToken = "old-session",
        previousRequestClaimToken = "old-request",
        previousRequestClaimLeaseExpiresAtEpochMs = 3_000L,
        activeTargetClaimToken = "old-target",
        activeTargetClaimLeaseExpiresAtEpochMs = 3_100L,
    )

    private fun stored(
        operation: PrivilegeSweepOperation,
        targetState: PrivilegeSweepTargetState = PrivilegeSweepTargetState.RUNNING,
        targetResultCode: String? = null,
        terminal: StoredSweepTerminal? = null,
    ): StoredPrivilegeSweep {
        val requestId = UUID.randomUUID()
        return StoredPrivilegeSweep(
            requestId = requestId,
            workId = UUID.randomUUID(),
            operation = operation,
            freezerMode = if (operation == PrivilegeSweepOperation.FREEZE) FreezerMode.FREEZE else null,
            userId = 10,
            source = PrivilegeSweepSource.MAIN,
            createdAtEpochMs = 1L,
            targets = listOf(PACKAGE),
            terminalState = terminal,
            succeeded = if (targetState == PrivilegeSweepTargetState.SUCCEEDED) 1 else 0,
            failed = if (targetState == PrivilegeSweepTargetState.FAILED) 1 else 0,
            busy = if (targetState == PrivilegeSweepTargetState.BUSY) 1 else 0,
            unresolved = if (targetState in TERMINAL_TARGET_STATES) 0 else 1,
            terminalAtEpochMs = if (terminal == null) null else NOW_MS,
            retainUntilEpochMs = null,
            targetSnapshots = listOf(
                StoredPrivilegeSweepTarget(
                    requestId = requestId,
                    ordinal = 0,
                    packageName = PACKAGE,
                    state = targetState,
                    claimToken = if (targetState == PrivilegeSweepTargetState.RUNNING) "old-target" else null,
                    claimLeaseExpiresAtEpochMs =
                        if (targetState == PrivilegeSweepTargetState.RUNNING) 3_100L else null,
                    attemptCount = if (targetState == PrivilegeSweepTargetState.RUNNING) 1 else 0,
                    startedAtEpochMs =
                        if (targetState == PrivilegeSweepTargetState.RUNNING) 2_100L else null,
                    finishedAtEpochMs = if (targetState in TERMINAL_TARGET_STATES) NOW_MS else null,
                    resultCode = targetResultCode?.let(::PrivilegeSweepResultCode),
                    rootLaneDegraded = false,
                )
            ),
        )
    }

    private fun PrivilegeSweepOperation.owner(): PackageOperationOwner = when (this) {
        PrivilegeSweepOperation.FREEZE -> PackageOperationOwner.FREEZE
        PrivilegeSweepOperation.UNFREEZE -> PackageOperationOwner.UNFREEZE
        PrivilegeSweepOperation.CLEAR_CACHE -> PackageOperationOwner.CLEAR_CACHE
        PrivilegeSweepOperation.REINSTALL -> PackageOperationOwner.REINSTALL
    }

    private data class RecoveryCase(
        val operation: PrivilegeSweepOperation,
        val state: FreezeState,
        val expected: PrivilegeSweepRecovery,
    )

    private class FixedClock : PrivilegeSweepClock {
        override fun nowMs(): Long = NOW_MS
    }

    private class RecordingStateReader(
        private val state: FreezeState? = null,
        private val trace: MutableList<String>? = null,
        private val failure: Exception? = null,
    ) : PrivilegeSweepPackageStateReader {
        var calls = 0
            private set

        override fun stateOf(packageName: String): FreezeState {
            calls++
            trace?.add("state:$packageName")
            failure?.let { throw it }
            return checkNotNull(state)
        }
    }

    private class RecordingCoordinator(
        private val busyOwner: PackageOperationOwner? = null,
        private val trace: MutableList<String>? = null,
    ) : PackageOperationCoordinator {
        var leaseCalls = 0
            private set

        override suspend fun <T> withPackageLease(
            packageName: String,
            owner: PackageOperationOwner,
            admissionTimeout: Duration,
            block: suspend () -> T,
        ): PackageLeaseResult<T> {
            leaseCalls++
            busyOwner?.let { return PackageLeaseResult.Busy(it) }
            trace?.add("lease:start:$owner")
            return try {
                PackageLeaseResult.Acquired(block())
            } finally {
                trace?.add("lease:end:$owner")
            }
        }
    }

    private class FakeWorkManager(
        private val states: Map<UUID, SweepWorkState?> = emptyMap(),
    ) : PrivilegeSweepWorkManager {
        override suspend fun enqueue(work: OneTimeWorkRequest): Boolean = error("not used")
        override fun observeState(workId: UUID): Flow<SweepWorkState?> = flowOf(states[workId])
        override suspend fun currentState(workId: UUID): SweepWorkState? = states[workId]
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private class FakeStore(
        snapshot: StoredPrivilegeSweep,
        private val recoveryCandidates: List<PrivilegeSweepRecoveryCandidate> = emptyList(),
    ) : PrivilegeSweepStore {
        private val snapshots = mutableMapOf(snapshot.requestId to snapshot)
        val candidateRecoveries = mutableListOf<PrivilegeSweepRecovery>()
        val unownedRecoveries = mutableListOf<PrivilegeSweepRecovery>()
        val legacyFinishes = mutableListOf<Triple<UUID, StoredSweepTerminal, Long>>()
        var candidateRecoveryResult = true
        var candidateRecoveryAttempts = 0
        var recoverRequestClaimsCalls = 0
        var deleteExpiredCalls = 0

        override suspend fun createOrFindEquivalent(
            snapshot: NewPrivilegeSweepSnapshot,
        ): SweepCreateResult = error("not used")

        override suspend fun load(requestId: UUID): StoredPrivilegeSweep? = snapshots[requestId]

        override fun observe(requestId: UUID): Flow<StoredPrivilegeSweep?> =
            flowOf(snapshots[requestId])

        override fun observeRetained(): Flow<List<StoredPrivilegeSweep>> =
            flowOf(snapshots.values.toList())

        override fun observeRetained(source: PrivilegeSweepSource): Flow<List<StoredPrivilegeSweep>> =
            observeRetained()

        override suspend fun recoverRequestClaims(
            sessionToken: String,
            nowMs: Long,
            localOwnerIsLive: (UUID, String) -> Boolean,
        ): List<PrivilegeSweepRecoveryCandidate> {
            recoverRequestClaimsCalls++
            return recoveryCandidates.filterNot {
                localOwnerIsLive(it.requestId, it.previousRequestClaimToken)
            }
        }

        override suspend fun recoverInterruptedTarget(
            candidate: PrivilegeSweepRecoveryCandidate,
            recovery: PrivilegeSweepRecovery,
        ): Boolean {
            candidateRecoveryAttempts++
            candidateRecoveries += recovery
            return candidateRecoveryResult
        }

        override suspend fun recoverInterruptedTarget(
            requestId: UUID,
            ordinal: Int,
            recovery: PrivilegeSweepRecovery,
        ): Boolean {
            unownedRecoveries += recovery
            return true
        }

        override suspend fun resetForRun(requestId: UUID): StoredPrivilegeSweep? =
            snapshots[requestId]

        override suspend fun recordAttempt(
            requestId: UUID,
            outcome: SweepAttemptOutcome,
        ): Boolean = true

        override suspend fun finish(
            requestId: UUID,
            terminal: StoredSweepTerminal,
            nowMs: Long,
        ): Boolean {
            legacyFinishes += Triple(requestId, terminal, nowMs)
            return true
        }

        override suspend fun cancelAllNonterminal(nowMs: Long): List<UUID> = emptyList()

        override suspend fun delete(requestId: UUID) {
            snapshots.remove(requestId)
        }

        override suspend fun deleteExpired(nowMs: Long): Int {
            deleteExpiredCalls++
            return 0
        }
    }

    private companion object {
        const val PACKAGE = "com.example.app"
        const val NOW_MS = 5_000L
        val TERMINAL_TARGET_STATES = setOf(
            PrivilegeSweepTargetState.SUCCEEDED,
            PrivilegeSweepTargetState.FAILED,
            PrivilegeSweepTargetState.BUSY,
            PrivilegeSweepTargetState.CANCELLED,
        )
    }
}
