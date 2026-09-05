// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.data.source.local.room.ClaimedSweepRequest
import com.valhalla.thor.data.source.local.room.ClaimedSweepTarget
import com.valhalla.thor.data.source.local.room.PrivilegeSweepDao
import com.valhalla.thor.data.source.local.room.StoredSweepRecovery
import com.valhalla.thor.data.source.local.room.StoredSweepTargetResult
import com.valhalla.thor.data.source.local.room.StoredSweepTargetTerminalState
import com.valhalla.thor.data.source.local.room.SweepCancellationDecision
import com.valhalla.thor.data.source.local.room.SweepRequestEntity
import com.valhalla.thor.data.source.local.room.SweepRequestRecoveryCandidate
import com.valhalla.thor.data.source.local.room.SweepRequestSourceEntity
import com.valhalla.thor.data.source.local.room.SweepRequestWithTargets
import com.valhalla.thor.data.source.local.room.SweepTargetEntity
import com.valhalla.thor.data.source.local.room.SweepTargetResultCode
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.SWEEP_RESULT_RETENTION
import com.valhalla.thor.domain.repository.ClaimedPrivilegeSweepRequest
import com.valhalla.thor.domain.repository.ClaimedPrivilegeSweepTarget
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeSweepCancellationDecision
import com.valhalla.thor.domain.repository.PrivilegeSweepRecovery
import com.valhalla.thor.domain.repository.PrivilegeSweepRecoveryCandidate
import com.valhalla.thor.domain.repository.PrivilegeSweepRequestState
import com.valhalla.thor.domain.repository.PrivilegeSweepResultCode
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetResult
import com.valhalla.thor.domain.repository.PrivilegeSweepTargetState
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.StoredPrivilegeSweepTarget
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import com.valhalla.thor.domain.repository.SweepCreateResult
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single(binds = [PrivilegeSweepStore::class])
class RoomPrivilegeSweepStore(
    private val dao: PrivilegeSweepDao,
) : PrivilegeSweepStore {

    override suspend fun createOrFindEquivalent(
        snapshot: NewPrivilegeSweepSnapshot,
    ): SweepCreateResult {
        val requestId = snapshot.requestId.toString()
        val request = SweepRequestEntity(
            requestId = requestId,
            workId = snapshot.executionId.toString(),
            operation = snapshot.operation.name,
            freezerMode = snapshot.freezerMode?.name,
            userId = snapshot.userId,
            sourceSurface = snapshot.source.name,
            createdAtEpochMs = snapshot.createdAtEpochMs,
            terminalState = null,
            succeeded = 0,
            failed = 0,
            busy = 0,
            unresolved = snapshot.targets.size,
            terminalAtEpochMs = null,
            retainUntilEpochMs = null,
            executionId = snapshot.executionId.toString(),
        )
        val targets = snapshot.targets.mapIndexed { ordinal, packageName ->
            SweepTargetEntity(
                requestId = requestId,
                ordinal = ordinal,
                packageName = packageName,
            )
        }
        val result = dao.createOrFindEquivalent(
            request = request,
            targets = targets,
            sources = snapshot.sourceAssociations.map { sourceAssociation ->
                SweepRequestSourceEntity(
                    requestId = requestId,
                    sourceSurface = sourceAssociation,
                    associatedAtEpochMs = snapshot.createdAtEpochMs,
                )
            },
        )
        val stored = result.snapshot.toDomain()
        return if (result.created) {
            SweepCreateResult.Created(stored)
        } else {
            SweepCreateResult.Equivalent(stored)
        }
    }

    override suspend fun load(requestId: UUID): StoredPrivilegeSweep? =
        dao.load(requestId.toString())?.toDomain()

    override fun observe(requestId: UUID): Flow<StoredPrivilegeSweep?> =
        dao.observe(requestId.toString()).map { it?.toDomain() }

    override fun observeRetained(): Flow<List<StoredPrivilegeSweep>> =
        dao.observeRetained().map { snapshots -> snapshots.map { it.toDomain() } }

    override fun observeRetained(
        source: PrivilegeSweepSource,
    ): Flow<List<StoredPrivilegeSweep>> =
        dao.observeRetained(source.name).map { snapshots -> snapshots.map { it.toDomain() } }

    override suspend fun claimOldestRunnableRequest(
        sessionToken: String,
        claimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): ClaimedPrivilegeSweepRequest? = dao.claimOldestRunnableRequest(
        sessionToken = sessionToken,
        claimToken = claimToken,
        nowMs = nowMs,
        leaseUntilMs = leaseUntilMs,
    )?.toDomain()

    override suspend fun claimNextPendingTarget(
        requestId: UUID,
        requestClaimToken: String,
        targetClaimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): ClaimedPrivilegeSweepTarget? = dao.claimNextPendingTarget(
        requestId = requestId.toString(),
        requestClaimToken = requestClaimToken,
        targetClaimToken = targetClaimToken,
        nowMs = nowMs,
        leaseUntilMs = leaseUntilMs,
    )?.toDomain()

    override suspend fun renewRequestClaim(
        requestId: UUID,
        claimToken: String,
        leaseUntilMs: Long,
    ): Boolean = dao.renewRequestClaim(requestId.toString(), claimToken, leaseUntilMs)

    override suspend fun renewTargetClaim(
        requestId: UUID,
        ordinal: Int,
        claimToken: String,
        leaseUntilMs: Long,
    ): Boolean = dao.renewTargetClaim(requestId.toString(), ordinal, claimToken, leaseUntilMs)

    override suspend fun completeClaimedTarget(
        requestId: UUID,
        ordinal: Int,
        requestClaimToken: String,
        targetClaimToken: String,
        result: PrivilegeSweepTargetResult,
    ): Boolean = dao.completeClaimedTarget(
        requestId = requestId.toString(),
        ordinal = ordinal,
        requestClaimToken = requestClaimToken,
        targetClaimToken = targetClaimToken,
        result = result.toRoom(),
    )

    override suspend fun requestCancellation(
        requestId: UUID,
        nowMs: Long,
    ): PrivilegeSweepCancellationDecision =
        dao.requestCancellation(requestId.toString(), nowMs).toDomain()

    override suspend fun recoverRequestClaims(
        sessionToken: String,
        nowMs: Long,
        localOwnerIsLive: (UUID, String) -> Boolean,
    ): List<PrivilegeSweepRecoveryCandidate> = dao.recoverRequestClaims(
        sessionToken = sessionToken,
        nowMs = nowMs,
        localOwnerIsLive = { requestId, requestClaimToken ->
            localOwnerIsLive(UUID.fromString(requestId), requestClaimToken)
        },
    ).map { it.toDomain() }

    override suspend fun recoverInterruptedTarget(
        requestId: UUID,
        ordinal: Int,
        recovery: PrivilegeSweepRecovery,
    ): Boolean = dao.recoverInterruptedTarget(
        requestId = requestId.toString(),
        ordinal = ordinal,
        recovery = recovery.toRoom(),
    )

    override suspend fun recoverInterruptedTarget(
        candidate: PrivilegeSweepRecoveryCandidate,
        recovery: PrivilegeSweepRecovery,
    ): Boolean = dao.recoverInterruptedTarget(candidate.toRoom(), recovery.toRoom())

    override suspend fun authorizeUnknownTargetRetry(
        requestId: UUID,
        ordinal: Int,
        nowMs: Long,
    ): Boolean = dao.authorizeUnknownTargetRetry(requestId.toString(), ordinal, nowMs)

    override suspend fun finishClaimedRequestIfDrained(
        requestId: UUID,
        claimToken: String,
        nowMs: Long,
    ): Boolean = dao.finishClaimedRequestIfDrained(requestId.toString(), claimToken, nowMs)

    override suspend fun hasRunnableRequests(): Boolean = dao.hasRunnableRequests()

    override suspend fun finishDrainIfQueueEmpty(onQueueEmpty: () -> Unit): Boolean =
        dao.finishDrainIfQueueEmpty(onQueueEmpty)

    @Deprecated("Compatibility for PrivilegeSweepWorker; remove in Task 12")
    override suspend fun resetForRun(requestId: UUID): StoredPrivilegeSweep? =
        dao.resetForRun(requestId.toString())?.toDomain()

    @Deprecated("Compatibility for PrivilegeSweepWorker; remove in Task 12")
    override suspend fun recordAttempt(requestId: UUID, outcome: SweepAttemptOutcome): Boolean =
        dao.recordAttempt(requestId.toString(), outcome) == 1

    @Deprecated("Compatibility for WorkManager reconciliation; remove in Task 12")
    override suspend fun finish(
        requestId: UUID,
        terminal: StoredSweepTerminal,
        nowMs: Long,
    ): Boolean = dao.finish(
        requestId = requestId.toString(),
        terminalState = terminal.name,
        nowMs = nowMs,
        retainUntilEpochMs = nowMs + SWEEP_RESULT_RETENTION.inWholeMilliseconds,
    ) == 1

    override suspend fun cancelAllNonterminal(nowMs: Long): List<UUID> =
        dao.cancelAllNonterminal(
            terminalState = StoredSweepTerminal.CANCELLED.name,
            nowMs = nowMs,
            retainUntilEpochMs = nowMs + SWEEP_RESULT_RETENTION.inWholeMilliseconds,
        ).map(UUID::fromString)

    override suspend fun delete(requestId: UUID) {
        dao.delete(requestId.toString())
    }

    override suspend fun deleteExpired(nowMs: Long): Int = dao.deleteExpired(nowMs)

    private fun SweepRequestWithTargets.toDomain(): StoredPrivilegeSweep {
        val orderedTargets = targets.sortedBy(SweepTargetEntity::ordinal)
        require(orderedTargets.map(SweepTargetEntity::ordinal) == orderedTargets.indices.toList()) {
            "Stored sweep target ordinals must be contiguous"
        }
        return StoredPrivilegeSweep(
            requestId = UUID.fromString(request.requestId),
            workId = UUID.fromString(request.executionId),
            operation = PrivilegeSweepOperation.valueOf(request.operation),
            freezerMode = request.freezerMode?.let(FreezerMode::valueOf),
            userId = request.userId,
            source = PrivilegeSweepSource.valueOf(request.sourceSurface),
            createdAtEpochMs = request.createdAtEpochMs,
            targets = orderedTargets.map(SweepTargetEntity::packageName),
            terminalState = request.terminalState?.let(StoredSweepTerminal::valueOf),
            succeeded = request.succeeded,
            failed = request.failed,
            busy = request.busy,
            unresolved = request.unresolved,
            terminalAtEpochMs = request.terminalAtEpochMs,
            retainUntilEpochMs = request.retainUntilEpochMs,
            sourceAssociations = sources.mapTo(
                linkedSetOf(),
                SweepRequestSourceEntity::sourceSurface
            ),
            targetSnapshots = orderedTargets.map { it.toDomain() },
        )
    }

    private fun SweepTargetEntity.toDomain(): StoredPrivilegeSweepTarget =
        StoredPrivilegeSweepTarget(
            requestId = UUID.fromString(requestId),
            ordinal = ordinal,
            packageName = packageName,
            state = PrivilegeSweepTargetState.valueOf(state),
            claimToken = claimToken,
            claimLeaseExpiresAtEpochMs = claimLeaseExpiresAtEpochMs,
            attemptCount = attemptCount,
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            resultCode = resultCode?.let(::PrivilegeSweepResultCode),
            rootLaneDegraded = rootLaneDegraded,
        )

    private fun ClaimedSweepRequest.toDomain(): ClaimedPrivilegeSweepRequest =
        ClaimedPrivilegeSweepRequest(
            requestId = UUID.fromString(requestId),
            queueSequence = queueSequence,
            payloadSchemaVersion = payloadSchemaVersion,
            executionId = UUID.fromString(executionId),
            operation = operation,
            freezerMode = freezerMode,
            userId = userId,
            source = source,
            sourceAssociations = sourceAssociations,
            targetCount = targetCount,
            succeeded = succeeded,
            failed = failed,
            busy = busy,
            unresolved = unresolved,
            serviceSessionToken = serviceSessionToken,
            claimToken = claimToken,
            claimLeaseExpiresAtEpochMs = claimLeaseExpiresAtEpochMs,
            attemptCount = attemptCount,
            createdAtEpochMs = createdAtEpochMs,
            claimedAtEpochMs = claimedAtEpochMs,
        )

    private fun ClaimedSweepTarget.toDomain(): ClaimedPrivilegeSweepTarget =
        ClaimedPrivilegeSweepTarget(
            requestId = UUID.fromString(requestId),
            ordinal = ordinal,
            packageName = packageName,
            claimToken = claimToken,
            claimLeaseExpiresAtEpochMs = claimLeaseExpiresAtEpochMs,
            attemptCount = attemptCount,
            startedAtEpochMs = startedAtEpochMs,
        )

    private fun SweepRequestRecoveryCandidate.toDomain(): PrivilegeSweepRecoveryCandidate =
        PrivilegeSweepRecoveryCandidate(
            requestId = UUID.fromString(requestId),
            operation = operation,
            freezerMode = freezerMode,
            userId = userId,
            activeTargetOrdinal = activeTargetOrdinal,
            packageName = packageName,
            previousServiceSessionToken = previousServiceSessionToken,
            previousRequestClaimToken = previousRequestClaimToken,
            previousRequestClaimLeaseExpiresAtEpochMs = previousRequestClaimLeaseExpiresAtEpochMs,
            activeTargetClaimToken = activeTargetClaimToken,
            activeTargetClaimLeaseExpiresAtEpochMs = activeTargetClaimLeaseExpiresAtEpochMs,
        )

    private fun PrivilegeSweepRecoveryCandidate.toRoom(): SweepRequestRecoveryCandidate =
        SweepRequestRecoveryCandidate(
            requestId = requestId.toString(),
            operation = operation,
            freezerMode = freezerMode,
            userId = userId,
            activeTargetOrdinal = activeTargetOrdinal,
            packageName = packageName,
            previousServiceSessionToken = previousServiceSessionToken,
            previousRequestClaimToken = previousRequestClaimToken,
            previousRequestClaimLeaseExpiresAtEpochMs = previousRequestClaimLeaseExpiresAtEpochMs,
            activeTargetClaimToken = activeTargetClaimToken,
            activeTargetClaimLeaseExpiresAtEpochMs = activeTargetClaimLeaseExpiresAtEpochMs,
        )

    private fun PrivilegeSweepTargetResult.toRoom(): StoredSweepTargetResult =
        StoredSweepTargetResult(
            terminalState = StoredSweepTargetTerminalState.valueOf(terminalState.name),
            resultCode = SweepTargetResultCode(resultCode.value),
            rootLaneDegraded = rootLaneDegraded,
            finishedAtEpochMs = finishedAtEpochMs,
        )

    private fun PrivilegeSweepRecovery.toRoom(): StoredSweepRecovery = when (this) {
        is PrivilegeSweepRecovery.Completed -> StoredSweepRecovery.Completed(
            result = result.toRoom(),
            recoveredAtEpochMs = recoveredAtEpochMs,
        )

        is PrivilegeSweepRecovery.Requeue -> StoredSweepRecovery.Requeue(
            resultCode = SweepTargetResultCode(resultCode.value),
            recoveredAtEpochMs = recoveredAtEpochMs,
        )

        is PrivilegeSweepRecovery.MarkUnknown -> StoredSweepRecovery.MarkUnknown(
            resultCode = SweepTargetResultCode(resultCode.value),
            recoveredAtEpochMs = recoveredAtEpochMs,
        )
    }

    private fun SweepCancellationDecision.toDomain(): PrivilegeSweepCancellationDecision =
        when (this) {
            SweepCancellationDecision.NotFound -> PrivilegeSweepCancellationDecision.NotFound
            is SweepCancellationDecision.AlreadyTerminal ->
                PrivilegeSweepCancellationDecision.AlreadyTerminal(
                    requestId = UUID.fromString(requestId),
                    state = PrivilegeSweepRequestState.valueOf(state.name),
                )

            is SweepCancellationDecision.Settled ->
                PrivilegeSweepCancellationDecision.Settled(UUID.fromString(requestId))

            is SweepCancellationDecision.InterruptActive ->
                PrivilegeSweepCancellationDecision.InterruptActive(
                    requestId = UUID.fromString(requestId),
                    activeTargetOrdinal = activeTargetOrdinal,
                )
        }
}
