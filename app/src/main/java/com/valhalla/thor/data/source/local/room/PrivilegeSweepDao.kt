// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import kotlinx.coroutines.flow.Flow

data class SweepRequestWithTargets(
    @Embedded val request: SweepRequestEntity,
    @Relation(
        parentColumn = "request_id",
        entityColumn = "request_id",
    )
    val targets: List<SweepTargetEntity>,
    @Relation(
        parentColumn = "request_id",
        entityColumn = "request_id",
    )
    val sources: List<SweepRequestSourceEntity>,
)

data class SweepRequestCreation(
    val snapshot: SweepRequestWithTargets,
    val created: Boolean,
)

@Dao
abstract class PrivilegeSweepDao {

    @Insert
    abstract suspend fun insertRequest(request: SweepRequestEntity)

    @Query("SELECT COALESCE(MAX(queue_sequence), 0) + 1 FROM sweep_requests")
    protected abstract suspend fun nextQueueSequence(): Long

    @Insert
    abstract suspend fun insertTargets(targets: List<SweepTargetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSources(sources: List<SweepRequestSourceEntity>)

    @Transaction
    @Query(
        """
        SELECT * FROM sweep_requests
        WHERE terminal_state IS NULL
          AND operation = :operation
          AND user_id = :userId
          AND ((freezer_mode IS NULL AND :freezerMode IS NULL) OR freezer_mode = :freezerMode)
        ORDER BY created_at_epoch_ms ASC, request_id ASC
        """
    )
    abstract suspend fun findEquivalentCandidates(
        operation: String,
        freezerMode: String?,
        userId: Int,
    ): List<SweepRequestWithTargets>

    /** The query, exact target comparison, and both inserts share one Room transaction. */
    @Transaction
    open suspend fun createOrFindEquivalent(
        request: SweepRequestEntity,
        targets: List<SweepTargetEntity>,
        sources: List<SweepRequestSourceEntity>,
    ): SweepRequestCreation {
        val packageNames =
            targets.sortedBy(SweepTargetEntity::ordinal).map(SweepTargetEntity::packageName)
        val equivalent = findEquivalentCandidates(
            operation = request.operation,
            freezerMode = request.freezerMode,
            userId = request.userId,
        ).firstOrNull { candidate ->
            candidate.targets
                .sortedBy(SweepTargetEntity::ordinal)
                .map(SweepTargetEntity::packageName) == packageNames
        }
        if (equivalent != null) {
            upsertSources(sources.map { it.copy(requestId = equivalent.request.requestId) })
            return SweepRequestCreation(
                equivalent.copy(
                    sources = (equivalent.sources + sources.map {
                        it.copy(requestId = equivalent.request.requestId)
                    }).distinctBy(SweepRequestSourceEntity::sourceSurface)
                ),
                created = false,
            )
        }

        val sequencedRequest = if (request.queueSequence == 0L) {
            request.copy(queueSequence = nextQueueSequence())
        } else {
            request
        }
        insertRequest(sequencedRequest)
        insertTargets(targets)
        upsertSources(sources)
        return SweepRequestCreation(
            snapshot = SweepRequestWithTargets(sequencedRequest, targets, sources),
            created = true,
        )
    }

    @Transaction
    @Query("SELECT * FROM sweep_requests WHERE request_id = :requestId")
    abstract suspend fun load(requestId: String): SweepRequestWithTargets?

    @Transaction
    @Query("SELECT * FROM sweep_requests WHERE request_id = :requestId")
    abstract fun observe(requestId: String): Flow<SweepRequestWithTargets?>

    @Transaction
    @Query("SELECT * FROM sweep_requests ORDER BY created_at_epoch_ms DESC, request_id DESC")
    abstract fun observeRetained(): Flow<List<SweepRequestWithTargets>>

    @Transaction
    @Query(
        """
        SELECT sweep_requests.* FROM sweep_requests
        INNER JOIN sweep_request_sources
            ON sweep_request_sources.request_id = sweep_requests.request_id
        WHERE sweep_request_sources.source_surface = :sourceSurface
        ORDER BY sweep_request_sources.associated_at_epoch_ms DESC, sweep_requests.request_id DESC
        """
    )
    abstract fun observeRetained(sourceSurface: String): Flow<List<SweepRequestWithTargets>>

    @Transaction
    open suspend fun acknowledgeTerminalRequest(
        requestId: String,
        nowMs: Long,
    ): SweepRequestWithTargets? {
        val request = loadRequestEntity(requestId) ?: return null
        if (request.state !in ACKNOWLEDGEABLE_REQUEST_STATES) return null
        if (acknowledgeTerminalRequestRow(requestId, nowMs) != 1) return null
        return load(requestId)
    }

    @Transaction
    open suspend fun claimOldestRunnableRequest(
        sessionToken: String,
        claimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): ClaimedSweepRequest? {
        require(sessionToken.isStorageSafeOwnershipToken()) {
            "sessionToken must be a non-empty storage-safe ownership token"
        }
        require(claimToken.isStorageSafeOwnershipToken()) {
            "claimToken must be a non-empty storage-safe ownership token"
        }
        require(nowMs >= 0L) { "nowMs must not be negative" }
        require(leaseUntilMs > nowMs) { "leaseUntilMs must be later than nowMs" }
        val requestId = findOldestRunnableRequestId() ?: return null
        if (
            claimRunnableRequestRow(
                requestId = requestId,
                sessionToken = sessionToken,
                claimToken = claimToken,
                nowMs = nowMs,
                leaseUntilMs = leaseUntilMs,
            ) != 1
        ) {
            return null
        }
        return requireNotNull(load(requestId)).toClaimedRequest()
    }

    @Transaction
    open suspend fun claimNextPendingTarget(
        requestId: String,
        requestClaimToken: String,
        targetClaimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): ClaimedSweepTarget? {
        require(requestClaimToken.isStorageSafeOwnershipToken()) {
            "requestClaimToken must be a non-empty storage-safe ownership token"
        }
        require(targetClaimToken.isStorageSafeOwnershipToken()) {
            "targetClaimToken must be a non-empty storage-safe ownership token"
        }
        require(nowMs >= 0L) { "nowMs must not be negative" }
        require(leaseUntilMs > nowMs) { "leaseUntilMs must be later than nowMs" }
        val request = loadRequestEntity(requestId) ?: return null
        if (
            hasMalformedRequestOwnership(requestId) ||
            !request.hasValidOwnedRequestOwnership() ||
            request.claimToken != requestClaimToken
        ) {
            return null
        }
        val ordinal = findNextPendingTargetOrdinal(requestId, requestClaimToken) ?: return null
        if (
            claimPendingTargetRow(
                requestId = requestId,
                ordinal = ordinal,
                requestClaimToken = requestClaimToken,
                targetClaimToken = targetClaimToken,
                nowMs = nowMs,
                leaseUntilMs = leaseUntilMs,
            ) != 1
        ) {
            return null
        }
        return requireNotNull(loadTarget(requestId, ordinal)).toClaimedTarget()
    }

    @Query(
        """
        UPDATE sweep_requests
        SET claim_lease_expires_at_epoch_ms = :leaseUntilMs
        WHERE request_id = :requestId
          AND state = 'RUNNING'
          AND terminal_state IS NULL
          AND claim_token = :claimToken
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
        """
    )
    protected abstract suspend fun renewRequestClaimRow(
        requestId: String,
        claimToken: String,
        leaseUntilMs: Long,
    ): Int

    @Transaction
    open suspend fun renewRequestClaim(
        requestId: String,
        claimToken: String,
        leaseUntilMs: Long,
    ): Boolean {
        require(claimToken.isStorageSafeOwnershipToken()) {
            "claimToken must be a non-empty storage-safe ownership token"
        }
        require(leaseUntilMs >= 0L) { "leaseUntilMs must not be negative" }
        val request = loadRequestEntity(requestId) ?: return false
        return !hasMalformedRequestOwnership(requestId) &&
                request.hasValidOwnedRequestOwnership() &&
                request.claimToken == claimToken &&
                renewRequestClaimRow(requestId, claimToken, leaseUntilMs) == 1
    }

    @Query(
        """
        UPDATE sweep_targets
        SET claim_lease_expires_at_epoch_ms = :leaseUntilMs
        WHERE request_id = :requestId
          AND ordinal = :ordinal
          AND state = 'RUNNING'
          AND claim_token = :claimToken
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_targets.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND EXISTS(
              SELECT 1 FROM sweep_requests
              WHERE sweep_requests.request_id = sweep_targets.request_id
                AND sweep_requests.state = 'RUNNING'
                AND sweep_requests.terminal_state IS NULL
          )
        """
    )
    protected abstract suspend fun renewTargetClaimRow(
        requestId: String,
        ordinal: Int,
        claimToken: String,
        leaseUntilMs: Long,
    ): Int

    @Transaction
    open suspend fun renewTargetClaim(
        requestId: String,
        ordinal: Int,
        claimToken: String,
        leaseUntilMs: Long,
    ): Boolean {
        require(claimToken.isStorageSafeOwnershipToken()) {
            "claimToken must be a non-empty storage-safe ownership token"
        }
        require(leaseUntilMs >= 0L) { "leaseUntilMs must not be negative" }
        val request = loadRequestEntity(requestId) ?: return false
        if (
            hasMalformedRequestOwnership(requestId) ||
            !request.hasValidOwnedRequestOwnership()
        ) {
            return false
        }
        val target = loadTarget(requestId, ordinal) ?: return false
        return target.hasValidTargetOwnership() &&
                target.claimToken == claimToken &&
                renewTargetClaimRow(requestId, ordinal, claimToken, leaseUntilMs) == 1
    }

    @Transaction
    open suspend fun completeClaimedTarget(
        requestId: String,
        ordinal: Int,
        requestClaimToken: String,
        targetClaimToken: String,
        result: StoredSweepTargetResult,
    ): Boolean {
        require(requestClaimToken.isStorageSafeOwnershipToken()) {
            "requestClaimToken must be a non-empty storage-safe ownership token"
        }
        require(targetClaimToken.isStorageSafeOwnershipToken()) {
            "targetClaimToken must be a non-empty storage-safe ownership token"
        }
        require(result.finishedAtEpochMs >= 0L) { "finishedAtEpochMs must not be negative" }
        val request = loadRequestEntity(requestId) ?: return false
        if (
            hasMalformedRequestOwnership(requestId) ||
            !request.hasValidOwnedRequestOwnership() ||
            request.claimToken != requestClaimToken
        ) {
            return false
        }
        val target = loadTarget(requestId, ordinal) ?: return false
        return target.hasValidTargetOwnership() &&
                target.claimToken == targetClaimToken &&
                completeClaimedTargetRow(
                    requestId = requestId,
                    ordinal = ordinal,
                    requestClaimToken = requestClaimToken,
                    targetClaimToken = targetClaimToken,
                    terminalState = result.terminalState.name,
                    resultCode = result.resultCode.value,
                    rootLaneDegraded = result.rootLaneDegraded,
                    finishedAtEpochMs = result.finishedAtEpochMs,
                ) == 1 && refreshClaimedRequestAggregates(
            requestId,
            requestClaimToken,
            result.finishedAtEpochMs,
        ) == 1
    }

    @Transaction
    open suspend fun requestCancellation(
        requestId: String,
        nowMs: Long,
    ): SweepCancellationDecision {
        require(nowMs >= 0L) { "nowMs must not be negative" }
        val request = loadRequestEntity(requestId) ?: return SweepCancellationDecision.NotFound
        val state = StoredSweepRequestState.valueOf(request.state)
        val terminalState = request.terminalState?.let(StoredSweepRequestState::valueOf)
        if (terminalState != null || state.isTerminal()) {
            return SweepCancellationDecision.AlreadyTerminal(requestId, terminalState ?: state)
        }
        val requestOwnershipIsValid = when (state) {
            StoredSweepRequestState.RUNNING,
            StoredSweepRequestState.CANCEL_REQUESTED,
                -> request.hasValidOwnedRequestOwnership()

            else -> request.hasUnownedRequestOwnership()
        }
        if (
            hasMalformedRequestOwnership(requestId) ||
            !requestOwnershipIsValid ||
            hasMalformedTargetOwnership(requestId)
        ) {
            return SweepCancellationDecision.NotFound
        }

        if (requestCancellationRow(requestId, request.state, request.claimToken, nowMs) != 1) {
            return SweepCancellationDecision.NotFound
        }
        cancelInactiveTargets(requestId, request.claimToken, nowMs)
        check(refreshCancellingRequestAggregates(requestId, request.claimToken, nowMs) == 1)

        val activeTarget = loadRunningTarget(requestId)
        if (activeTarget != null) {
            return SweepCancellationDecision.InterruptActive(requestId, activeTarget.ordinal)
        }

        check(
            settleCancellationRow(
                requestId = requestId,
                requestClaimToken = request.claimToken,
                terminalState = deriveTerminalState(requestId).name,
                nowMs = nowMs,
            ) == 1,
        )
        return SweepCancellationDecision.Settled(requestId)
    }

    @Transaction
    open suspend fun recoverRequestClaims(
        sessionToken: String,
        nowMs: Long,
        localOwnerIsLive: (requestId: String, requestClaimToken: String) -> Boolean,
    ): List<SweepRequestRecoveryCandidate> {
        require(sessionToken.isStorageSafeOwnershipToken()) {
            "sessionToken must be a non-empty storage-safe ownership token"
        }
        require(nowMs >= 0L) { "nowMs must not be negative" }
        return loadRecoverableRequests().mapNotNull { request ->
            if (
                hasMalformedRequestOwnership(request.requestId) ||
                !request.hasValidOwnedRequestOwnership()
            ) {
                return@mapNotNull null
            }
            val previousSession = requireNotNull(request.serviceSessionToken)
            val previousClaim = requireNotNull(request.claimToken)
            val previousLease = requireNotNull(request.claimLeaseExpiresAtEpochMs)
            if (localOwnerIsLive(request.requestId, previousClaim)) return@mapNotNull null
            if (previousSession == sessionToken && previousLease > nowMs) return@mapNotNull null

            val activeTarget = loadRunningTarget(request.requestId)
            if (activeTarget != null) {
                return@mapNotNull request.toRecoveryCandidate(activeTarget, previousSession)
            }
            if (hasMalformedTargetOwnership(request.requestId)) return@mapNotNull null

            if (request.state == StoredSweepRequestState.CANCEL_REQUESTED.name) {
                cancelInactiveTargets(request.requestId, previousClaim, nowMs)
                check(
                    refreshCancellingRequestAggregates(
                        request.requestId,
                        previousClaim,
                        nowMs,
                    ) == 1,
                )
                check(
                    settleCancellationRow(
                        requestId = request.requestId,
                        requestClaimToken = previousClaim,
                        terminalState = deriveTerminalState(request.requestId).name,
                        nowMs = nowMs,
                    ) == 1,
                )
                return@mapNotNull null
            }

            val updated = when {
                countTargetsInState(
                    request.requestId,
                    StoredSweepTargetState.PENDING.name,
                ) > 0 -> recoverRequestToQueue(
                    requestId = request.requestId,
                    expectedState = request.state,
                    previousSessionToken = previousSession,
                    previousClaimToken = previousClaim,
                    previousLeaseUntilMs = previousLease,
                    nowMs = nowMs,
                )

                countTargetsInStates(
                    request.requestId,
                    StoredSweepTargetState.UNKNOWN.name,
                    StoredSweepTargetState.LEGACY_UNKNOWN.name,
                ) > 0 -> recoverRequestToBlocked(
                    requestId = request.requestId,
                    expectedState = request.state,
                    previousSessionToken = previousSession,
                    previousClaimToken = previousClaim,
                    previousLeaseUntilMs = previousLease,
                    nowMs = nowMs,
                )

                else -> finalizeRecoveredRequest(
                    requestId = request.requestId,
                    expectedState = request.state,
                    previousSessionToken = previousSession,
                    previousClaimToken = previousClaim,
                    previousLeaseUntilMs = previousLease,
                    terminalState = deriveTerminalState(request.requestId).name,
                    nowMs = nowMs,
                    retainUntilEpochMs = nowMs + SWEEP_RESULT_RETENTION_MS,
                )
            }
            check(updated == 1)
            null
        }
    }

    @Transaction
    open suspend fun recoverInterruptedTarget(
        requestId: String,
        ordinal: Int,
        recovery: StoredSweepRecovery,
    ): Boolean {
        require(recovery.recoveredAtEpochMs >= 0L) { "recoveredAtEpochMs must not be negative" }
        val request = loadRequestEntity(requestId) ?: return false
        if (StoredSweepRequestState.valueOf(request.state).isTerminal()) return false
        if (
            hasMalformedRequestOwnership(requestId) ||
            !request.hasUnownedRequestOwnership()
        ) {
            return false
        }
        val target = loadTarget(requestId, ordinal) ?: return false
        val targetState = StoredSweepTargetState.valueOf(target.state)
        val mayReconcileUnownedTarget =
            targetState in setOf(
                StoredSweepTargetState.UNKNOWN,
                StoredSweepTargetState.LEGACY_UNKNOWN,
            ) &&
                    target.claimToken == null &&
                    target.claimLeaseExpiresAtEpochMs == null &&
                    !hasMalformedTargetOwnership(requestId) &&
                    request.state != StoredSweepRequestState.CANCEL_REQUESTED.name &&
                    request.serviceSessionToken == null &&
                    request.claimToken == null &&
                    request.claimLeaseExpiresAtEpochMs == null &&
                    loadRunningTarget(requestId) == null

        return mayReconcileUnownedTarget && recoverTargetAfterOwnerLoss(request, target, recovery)
    }

    @Transaction
    open suspend fun recoverInterruptedTarget(
        candidate: SweepRequestRecoveryCandidate,
        recovery: StoredSweepRecovery,
    ): Boolean {
        require(recovery.recoveredAtEpochMs >= 0L) { "recoveredAtEpochMs must not be negative" }
        if (
            !candidate.previousServiceSessionToken.isStorageSafeOwnershipToken() ||
            !candidate.previousRequestClaimToken.isStorageSafeOwnershipToken() ||
            candidate.previousRequestClaimLeaseExpiresAtEpochMs < 0L ||
            !candidate.activeTargetClaimToken.isStorageSafeOwnershipToken() ||
            candidate.activeTargetClaimLeaseExpiresAtEpochMs < 0L
        ) {
            return false
        }
        val request = loadRequestEntity(candidate.requestId) ?: return false
        if (
            hasMalformedRequestOwnership(candidate.requestId) ||
            !request.hasValidOwnedRequestOwnership()
        ) {
            return false
        }
        val requestState = StoredSweepRequestState.valueOf(request.state)
        if (
            requestState != StoredSweepRequestState.RUNNING &&
            requestState != StoredSweepRequestState.CANCEL_REQUESTED
        ) {
            return false
        }
        if (
            request.serviceSessionToken != candidate.previousServiceSessionToken ||
            request.claimToken != candidate.previousRequestClaimToken ||
            request.claimLeaseExpiresAtEpochMs !=
            candidate.previousRequestClaimLeaseExpiresAtEpochMs ||
            request.operation != candidate.operation.name ||
            request.freezerMode != candidate.freezerMode?.name ||
            request.userId != candidate.userId
        ) {
            return false
        }
        val target = loadTarget(candidate.requestId, candidate.activeTargetOrdinal) ?: return false
        if (!target.hasValidTargetOwnership()) return false
        val candidateIsStale =
            target.state != StoredSweepTargetState.RUNNING.name ||
                    target.claimToken != candidate.activeTargetClaimToken ||
                    target.claimLeaseExpiresAtEpochMs !=
                    candidate.activeTargetClaimLeaseExpiresAtEpochMs ||
                    target.packageName != candidate.packageName

        return !candidateIsStale && recoverTargetAfterOwnerLoss(request, target, recovery)
    }

    private suspend fun recoverTargetAfterOwnerLoss(
        request: SweepRequestEntity,
        target: SweepTargetEntity,
        recovery: StoredSweepRecovery,
    ): Boolean {
        val requestId = request.requestId
        val ordinal = target.ordinal
        val requestOwnershipIsValid =
            if (target.state == StoredSweepTargetState.RUNNING.name) {
                request.hasValidOwnedRequestOwnership()
            } else {
                request.hasUnownedRequestOwnership()
            }
        if (
            !requestOwnershipIsValid ||
            !target.hasValidTargetOwnership() ||
            hasMalformedTargetOwnership(requestId)
        ) {
            return false
        }
        if (request.state == StoredSweepRequestState.CANCEL_REQUESTED.name) {
            if (target.state != StoredSweepTargetState.RUNNING.name) return false
            if (
                recoverTargetAsCancelled(
                    requestId = requestId,
                    ordinal = ordinal,
                    requestClaimToken = request.claimToken,
                    targetClaimToken = target.claimToken,
                    targetClaimLeaseExpiresAtEpochMs = target.claimLeaseExpiresAtEpochMs,
                    nowMs = recovery.recoveredAtEpochMs,
                ) != 1
            ) {
                return false
            }
            check(
                refreshCancellingRequestAggregates(
                    requestId,
                    request.claimToken,
                    recovery.recoveredAtEpochMs,
                ) == 1,
            )
            if (countTargetsInState(requestId, StoredSweepTargetState.RUNNING.name) == 0) {
                check(
                    settleCancellationRow(
                        requestId = requestId,
                        requestClaimToken = request.claimToken,
                        terminalState = deriveTerminalState(requestId).name,
                        nowMs = recovery.recoveredAtEpochMs,
                    ) == 1,
                )
            }
            return true
        }

        val updated = when (recovery) {
            is StoredSweepRecovery.Completed -> {
                require(recovery.result.finishedAtEpochMs >= 0L) {
                    "finishedAtEpochMs must not be negative"
                }
                recoverTargetCompleted(
                    requestId = requestId,
                    ordinal = ordinal,
                    expectedState = target.state,
                    requestClaimToken = request.claimToken,
                    targetClaimToken = target.claimToken,
                    targetClaimLeaseExpiresAtEpochMs = target.claimLeaseExpiresAtEpochMs,
                    terminalState = recovery.result.terminalState.name,
                    resultCode = recovery.result.resultCode.value,
                    rootLaneDegraded = recovery.result.rootLaneDegraded,
                    finishedAtEpochMs = recovery.result.finishedAtEpochMs,
                )
            }

            is StoredSweepRecovery.Requeue -> recoverTargetForRetry(
                requestId = requestId,
                ordinal = ordinal,
                expectedState = target.state,
                requestClaimToken = request.claimToken,
                targetClaimToken = target.claimToken,
                targetClaimLeaseExpiresAtEpochMs = target.claimLeaseExpiresAtEpochMs,
                resultCode = recovery.resultCode.value,
            )

            is StoredSweepRecovery.MarkUnknown -> recoverTargetAsUnknown(
                requestId = requestId,
                ordinal = ordinal,
                expectedState = target.state,
                requestClaimToken = request.claimToken,
                targetClaimToken = target.claimToken,
                targetClaimLeaseExpiresAtEpochMs = target.claimLeaseExpiresAtEpochMs,
                resultCode = recovery.resultCode.value,
                nowMs = recovery.recoveredAtEpochMs,
            )
        }
        if (updated != 1) return false

        check(refreshRequestAggregates(requestId, recovery.recoveredAtEpochMs) == 1)
        when (recovery) {
            is StoredSweepRecovery.Requeue -> check(
                queueRecoveredRequest(
                    requestId,
                    request.state,
                    request.claimToken,
                    recovery.recoveredAtEpochMs,
                ) == 1,
            )

            is StoredSweepRecovery.MarkUnknown -> check(
                blockRecoveredRequest(
                    requestId,
                    request.state,
                    request.claimToken,
                    recovery.recoveredAtEpochMs,
                ) == 1,
            )

            is StoredSweepRecovery.Completed -> settleRequestAfterRecovery(
                request,
                recovery.recoveredAtEpochMs,
            )
        }
        return true
    }

    @Transaction
    open suspend fun authorizeUnknownTargetRetry(
        requestId: String,
        ordinal: Int,
        nowMs: Long,
    ): Boolean {
        require(nowMs >= 0L) { "nowMs must not be negative" }
        val request = loadRequestEntity(requestId) ?: return false
        if (StoredSweepRequestState.valueOf(request.state).isTerminal()) return false
        if (
            hasMalformedRequestOwnership(requestId) ||
            !request.hasUnownedRequestOwnership()
        ) {
            return false
        }
        if (
            request.serviceSessionToken != null ||
            request.claimToken != null ||
            request.claimLeaseExpiresAtEpochMs != null ||
            loadRunningTarget(requestId) != null
        ) {
            return false
        }
        val target = loadTarget(requestId, ordinal) ?: return false
        val targetState = StoredSweepTargetState.valueOf(target.state)
        if (
            targetState != StoredSweepTargetState.UNKNOWN &&
            targetState != StoredSweepTargetState.LEGACY_UNKNOWN
        ) {
            return false
        }
        if (
            target.claimToken != null ||
            target.claimLeaseExpiresAtEpochMs != null ||
            hasMalformedTargetOwnership(requestId)
        ) {
            return false
        }
        if (
            authorizeUnknownTargetRetryRow(
                requestId = requestId,
                ordinal = ordinal,
                expectedTargetState = target.state,
                expectedRequestState = request.state,
            ) != 1
        ) {
            return false
        }
        check(queueUnownedRequestAfterRetry(requestId, request.state, nowMs) == 1)
        return true
    }

    @Transaction
    open suspend fun finishClaimedRequestIfDrained(
        requestId: String,
        claimToken: String,
        nowMs: Long,
    ): Boolean {
        require(claimToken.isStorageSafeOwnershipToken()) {
            "claimToken must be a non-empty storage-safe ownership token"
        }
        require(nowMs >= 0L) { "nowMs must not be negative" }
        val request = loadRequestEntity(requestId) ?: return false
        if (
            hasMalformedRequestOwnership(requestId) ||
            !request.hasValidOwnedRequestOwnership() ||
            request.state != StoredSweepRequestState.RUNNING.name ||
            request.claimToken != claimToken
        ) {
            return false
        }
        if (hasMalformedTargetOwnership(requestId)) return false
        if (countUnfinishedTargets(requestId) != 0) return false
        if (refreshClaimedRequestAggregates(requestId, claimToken, nowMs) != 1) return false

        val succeeded = countTargetsInState(requestId, StoredSweepTargetState.SUCCEEDED.name)
        val failed = countTargetsInState(requestId, StoredSweepTargetState.FAILED.name)
        val busy = countTargetsInState(requestId, StoredSweepTargetState.BUSY.name)
        val cancelled = countTargetsInState(requestId, StoredSweepTargetState.CANCELLED.name)
        val state = when {
            failed == 0 && busy == 0 && cancelled == 0 -> StoredSweepRequestState.SUCCEEDED
            succeeded > 0 -> StoredSweepRequestState.PARTIAL
            failed > 0 || busy > 0 -> StoredSweepRequestState.FAILED
            else -> StoredSweepRequestState.CANCELLED
        }
        return finishClaimedRequestRow(
            requestId = requestId,
            claimToken = claimToken,
            terminalState = state.name,
            nowMs = nowMs,
            retainUntilEpochMs = nowMs + SWEEP_RESULT_RETENTION_MS,
        ) == 1
    }

    /** Called only after the exact owner's child and cleanup have exited, while it remains registered. */
    @Transaction
    open suspend fun settleClaimedRequestAfterExit(
        requestId: String,
        requestClaimToken: String,
        nowMs: Long,
    ): Boolean {
        require(requestClaimToken.isStorageSafeOwnershipToken())
        require(nowMs >= 0L)
        val request = loadRequestEntity(requestId) ?: return true
        if (hasMalformedRequestOwnership(requestId) || hasMalformedTargetOwnership(requestId)) return false
        if (request.hasUnownedRequestOwnership()) return loadRunningTarget(requestId) == null
        if (!request.hasValidOwnedRequestOwnership() || request.claimToken != requestClaimToken) return false
        if (request.state !in setOf(
                StoredSweepRequestState.RUNNING.name,
                StoredSweepRequestState.CANCEL_REQUESTED.name
            )
        ) return false

        // Reload inside the transaction: a target CAS may have committed without returning to its caller.
        val target = loadRunningTarget(requestId)
        if (target != null) {
            return recoverTargetAfterOwnerLoss(
                request, target,
                StoredSweepRecovery.MarkUnknown(
                    SweepTargetResultCode("INTERRUPTED_OUTCOME_UNKNOWN"),
                    nowMs
                ),
            )
        }
        if (request.state == StoredSweepRequestState.CANCEL_REQUESTED.name) {
            return requestCancellation(requestId, nowMs) is SweepCancellationDecision.Settled
        }
        if (countUnfinishedTargets(requestId) == 0) {
            return finishClaimedRequestIfDrained(requestId, requestClaimToken, nowMs)
        }
        if (countTargetsInState(requestId, StoredSweepTargetState.PENDING.name) > 0) {
            // No ambiguous operation was dispatched. An explicit retry, not an automatic loop, may resume.
            return blockClaimedRequestForMissingPrivilegeRow(
                requestId, requestClaimToken, nowMs, StoredSweepBlockReason.START_BLOCKED.name,
            ) == 1
        }
        return blockRecoveredRequest(requestId, request.state, requestClaimToken, nowMs) == 1
    }

    @Transaction
    open suspend fun markUnclaimedStartBlocked(
        requestId: String,
        reason: StoredSweepBlockReason,
        nowMs: Long,
    ): Boolean {
        require(
            reason == StoredSweepBlockReason.START_BLOCKED ||
                    reason == StoredSweepBlockReason.START_BLOCKED_NOTIFICATION,
        ) { "Only foreground-service start failures may block an unclaimed request" }
        require(nowMs >= 0L) { "nowMs must not be negative" }
        val snapshot = load(requestId) ?: return false
        val request = snapshot.request
        if (
            request.state != StoredSweepRequestState.QUEUED.name ||
            request.terminalState != null ||
            !request.hasUnownedRequestOwnership() ||
            snapshot.targets.any { !it.hasValidTargetOwnership() } ||
            snapshot.targets.any { it.state == StoredSweepTargetState.RUNNING.name }
        ) {
            return false
        }
        return markUnclaimedStartBlockedRow(requestId, reason.name, nowMs) == 1
    }

    @Transaction
    open suspend fun blockClaimedRequestForMissingPrivilege(
        requestId: String,
        requestClaimToken: String,
        nowMs: Long,
    ): Boolean {
        require(requestClaimToken.isStorageSafeOwnershipToken()) {
            "requestClaimToken must be a non-empty storage-safe ownership token"
        }
        require(nowMs >= 0L) { "nowMs must not be negative" }
        val snapshot = load(requestId) ?: return false
        val request = snapshot.request
        if (
            request.state != StoredSweepRequestState.RUNNING.name ||
            request.terminalState != null ||
            !request.hasValidOwnedRequestOwnership() ||
            request.claimToken != requestClaimToken ||
            snapshot.targets.any { !it.hasValidTargetOwnership() } ||
            snapshot.targets.any { it.state == StoredSweepTargetState.RUNNING.name } ||
            snapshot.targets.none { it.state == StoredSweepTargetState.PENDING.name }
        ) {
            return false
        }
        return blockClaimedRequestForMissingPrivilegeRow(
            requestId = requestId,
            requestClaimToken = requestClaimToken,
            nowMs = nowMs,
        ) == 1
    }

    @Transaction
    open suspend fun resumeBlockedRequest(
        requestId: String,
        expectedReason: StoredSweepBlockReason,
        nowMs: Long,
    ): Boolean {
        require(
            expectedReason == StoredSweepBlockReason.START_BLOCKED ||
                    expectedReason == StoredSweepBlockReason.START_BLOCKED_NOTIFICATION ||
                    expectedReason == StoredSweepBlockReason.PRIVILEGE_AUTHORIZATION_REQUIRED,
        ) { "Only an actionable block reason may be resumed" }
        require(nowMs >= 0L) { "nowMs must not be negative" }
        val snapshot = load(requestId) ?: return false
        val request = snapshot.request
        if (
            request.state != StoredSweepRequestState.BLOCKED.name ||
            request.terminalState != null ||
            request.blockReason != expectedReason.name ||
            !request.hasUnownedRequestOwnership() ||
            snapshot.targets.any { !it.hasValidTargetOwnership() } ||
            snapshot.targets.any { it.state == StoredSweepTargetState.RUNNING.name } ||
            snapshot.targets.none { it.state == StoredSweepTargetState.PENDING.name }
        ) {
            return false
        }
        return resumeBlockedRequestRow(requestId, expectedReason.name, nowMs) == 1
    }

    @Transaction
    open suspend fun markLegacyTargetsUnknown(
        requestId: String,
        ambiguousOrdinals: List<Int>,
        nowMs: Long,
    ): Boolean {
        require(nowMs >= 0L) { "nowMs must not be negative" }
        val ordinals = ambiguousOrdinals.distinct()
        if (ordinals.isEmpty()) return false
        val snapshot = load(requestId) ?: return false
        val request = snapshot.request
        if (
            request.terminalState != null ||
            request.state !in setOf(
                StoredSweepRequestState.QUEUED.name,
                StoredSweepRequestState.BLOCKED.name,
            ) ||
            !request.hasUnownedRequestOwnership() ||
            snapshot.targets.any { !it.hasValidTargetOwnership() } ||
            snapshot.targets.any { it.state == StoredSweepTargetState.RUNNING.name }
        ) {
            return false
        }
        val targetsByOrdinal = snapshot.targets.associateBy(SweepTargetEntity::ordinal)
        val valid = ordinals.all { ordinal ->
            val target = targetsByOrdinal[ordinal] ?: return@all false
            target.isPendingOrCompleteLegacyUnknown()
        }
        if (!valid) return false

        ordinals.forEach { ordinal ->
            val target = requireNotNull(targetsByOrdinal[ordinal])
            if (target.state == StoredSweepTargetState.PENDING.name &&
                markLegacyTargetUnknownRow(requestId, ordinal) != 1
            ) {
                error("Validated legacy target changed inside one transaction")
            }
        }
        if (refreshRequestAggregates(requestId, nowMs) != 1) return false
        val hasPending = countTargetsInState(requestId, StoredSweepTargetState.PENDING.name) > 0
        return settleLegacyRequestRow(
            requestId = requestId,
            state = if (hasPending) {
                StoredSweepRequestState.QUEUED.name
            } else {
                StoredSweepRequestState.BLOCKED.name
            },
            nowMs = nowMs,
        ) == 1
    }

    @Transaction
    open suspend fun hasRunnableRequests(): Boolean = hasRunnableRequestsQuery()

    /**
     * Serializes the final empty check and stop decision with sweep insertion transactions.
     *
     * @return `true` only when the queue was empty and [onQueueEmpty] was invoked.
     */
    @Transaction
    open suspend fun finishDrainIfQueueEmpty(onQueueEmpty: () -> Unit): Boolean {
        if (hasRunnableRequestsQuery()) return false
        onQueueEmpty()
        return true
    }

    @Query(
        """
        SELECT request_id FROM sweep_requests
        WHERE state = 'QUEUED'
          AND terminal_state IS NULL
          AND service_session_token IS NULL
          AND claim_token IS NULL
          AND claim_lease_expires_at_epoch_ms IS NULL
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state = 'PENDING'
                AND sweep_targets.claim_token IS NULL
                AND sweep_targets.claim_lease_expires_at_epoch_ms IS NULL
          )
        ORDER BY queue_sequence ASC, request_id ASC
        LIMIT 1
        """
    )
    protected abstract suspend fun findOldestRunnableRequestId(): String?

    @Query(
        """
        UPDATE sweep_requests
        SET state = 'RUNNING',
            service_session_token = :sessionToken,
            claim_token = :claimToken,
            claim_lease_expires_at_epoch_ms = :leaseUntilMs,
            claimed_at_epoch_ms = :nowMs,
            started_at_epoch_ms = COALESCE(started_at_epoch_ms, :nowMs),
            updated_at_epoch_ms = :nowMs,
            attempt_count = attempt_count + 1,
            block_reason = NULL
        WHERE request_id = :requestId
          AND state = 'QUEUED'
          AND terminal_state IS NULL
          AND claim_token IS NULL
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state = 'PENDING'
                AND sweep_targets.claim_token IS NULL
                AND sweep_targets.claim_lease_expires_at_epoch_ms IS NULL
          )
        """
    )
    protected abstract suspend fun claimRunnableRequestRow(
        requestId: String,
        sessionToken: String,
        claimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): Int

    @Query(
        """
        SELECT ordinal FROM sweep_targets
        WHERE request_id = :requestId
          AND state = 'PENDING'
          AND claim_token IS NULL
          AND claim_lease_expires_at_epoch_ms IS NULL
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_targets.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND EXISTS(
              SELECT 1 FROM sweep_requests
              WHERE sweep_requests.request_id = sweep_targets.request_id
                AND sweep_requests.state = 'RUNNING'
                AND sweep_requests.terminal_state IS NULL
                AND sweep_requests.claim_token = :requestClaimToken
          )
        ORDER BY ordinal ASC
        LIMIT 1
        """
    )
    protected abstract suspend fun findNextPendingTargetOrdinal(
        requestId: String,
        requestClaimToken: String,
    ): Int?

    @Query(
        """
        UPDATE sweep_targets
        SET state = 'RUNNING',
            claim_token = :targetClaimToken,
            claim_lease_expires_at_epoch_ms = :leaseUntilMs,
            attempt_count = attempt_count + 1,
            started_at_epoch_ms = COALESCE(started_at_epoch_ms, :nowMs),
            finished_at_epoch_ms = NULL,
            result_code = NULL,
            root_lane_degraded = 0
        WHERE request_id = :requestId
          AND ordinal = :ordinal
          AND state = 'PENDING'
          AND claim_token IS NULL
          AND claim_lease_expires_at_epoch_ms IS NULL
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_targets.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND EXISTS(
              SELECT 1 FROM sweep_requests
              WHERE sweep_requests.request_id = sweep_targets.request_id
                AND sweep_requests.state = 'RUNNING'
                AND sweep_requests.terminal_state IS NULL
                AND sweep_requests.claim_token = :requestClaimToken
          )
        """
    )
    protected abstract suspend fun claimPendingTargetRow(
        requestId: String,
        ordinal: Int,
        requestClaimToken: String,
        targetClaimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): Int

    @Query(
        """
        SELECT * FROM sweep_targets
        WHERE request_id = :requestId AND ordinal = :ordinal
        """
    )
    protected abstract suspend fun loadTarget(
        requestId: String,
        ordinal: Int,
    ): SweepTargetEntity?

    @Query("SELECT * FROM sweep_requests WHERE request_id = :requestId")
    protected abstract suspend fun loadRequestEntity(requestId: String): SweepRequestEntity?

    @Query(
        """
        UPDATE sweep_requests
        SET acknowledged_at_epoch_ms = COALESCE(acknowledged_at_epoch_ms, :nowMs)
        WHERE request_id = :requestId
          AND state IN ('SUCCEEDED', 'PARTIAL', 'CANCELLED', 'FAILED')
          AND terminal_state IS NOT NULL
        """
    )
    protected abstract suspend fun acknowledgeTerminalRequestRow(
        requestId: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        SELECT * FROM sweep_requests
        WHERE state IN ('RUNNING', 'CANCEL_REQUESTED')
          AND terminal_state IS NULL
        ORDER BY queue_sequence ASC, request_id ASC
        """
    )
    protected abstract suspend fun loadRecoverableRequests(): List<SweepRequestEntity>

    @Query(
        """
        SELECT * FROM sweep_targets
        WHERE request_id = :requestId AND state = 'RUNNING'
        ORDER BY ordinal ASC
        LIMIT 1
        """
    )
    protected abstract suspend fun loadRunningTarget(requestId: String): SweepTargetEntity?

    @Query(
        """
        UPDATE sweep_targets
        SET state = :terminalState,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            finished_at_epoch_ms = :finishedAtEpochMs,
            result_code = :resultCode,
            root_lane_degraded = :rootLaneDegraded
        WHERE request_id = :requestId
          AND ordinal = :ordinal
          AND state = 'RUNNING'
          AND claim_token = :targetClaimToken
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_targets.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND EXISTS(
              SELECT 1 FROM sweep_requests
              WHERE sweep_requests.request_id = sweep_targets.request_id
                AND sweep_requests.state = 'RUNNING'
                AND sweep_requests.terminal_state IS NULL
                AND sweep_requests.claim_token = :requestClaimToken
          )
        """
    )
    protected abstract suspend fun completeClaimedTargetRow(
        requestId: String,
        ordinal: Int,
        requestClaimToken: String,
        targetClaimToken: String,
        terminalState: String,
        resultCode: String,
        rootLaneDegraded: Boolean,
        finishedAtEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET succeeded = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'SUCCEEDED'
            ),
            failed = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'FAILED'
            ),
            busy = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'BUSY'
            ),
            unresolved = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state NOT IN ('SUCCEEDED', 'FAILED', 'BUSY')
            ),
            updated_at_epoch_ms = :nowMs
        WHERE request_id = :requestId
          AND state = 'RUNNING'
          AND terminal_state IS NULL
          AND claim_token = :requestClaimToken
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
        """
    )
    protected abstract suspend fun refreshClaimedRequestAggregates(
        requestId: String,
        requestClaimToken: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET succeeded = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'SUCCEEDED'
            ),
            failed = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'FAILED'
            ),
            busy = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'BUSY'
            ),
            unresolved = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state NOT IN ('SUCCEEDED', 'FAILED', 'BUSY')
            ),
            updated_at_epoch_ms = :nowMs
        WHERE request_id = :requestId
          AND terminal_state IS NULL
        """
    )
    protected abstract suspend fun refreshRequestAggregates(
        requestId: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET succeeded = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'SUCCEEDED'
            ),
            failed = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'FAILED'
            ),
            busy = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'BUSY'
            ),
            unresolved = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state NOT IN ('SUCCEEDED', 'FAILED', 'BUSY')
            ),
            updated_at_epoch_ms = :nowMs
        WHERE request_id = :requestId
          AND state = 'CANCEL_REQUESTED'
          AND terminal_state IS NULL
          AND ((claim_token IS NULL AND :requestClaimToken IS NULL) OR claim_token = :requestClaimToken)
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
        """
    )
    protected abstract suspend fun refreshCancellingRequestAggregates(
        requestId: String,
        requestClaimToken: String?,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET state = 'CANCEL_REQUESTED',
            cancel_requested_at_epoch_ms = COALESCE(cancel_requested_at_epoch_ms, :nowMs),
            updated_at_epoch_ms = :nowMs
        WHERE request_id = :requestId
          AND state = :expectedState
          AND terminal_state IS NULL
          AND ((claim_token IS NULL AND :requestClaimToken IS NULL) OR claim_token = :requestClaimToken)
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
        """
    )
    protected abstract suspend fun requestCancellationRow(
        requestId: String,
        expectedState: String,
        requestClaimToken: String?,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_targets
        SET state = 'CANCELLED',
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            finished_at_epoch_ms = :nowMs,
            result_code = 'CANCELLED',
            root_lane_degraded = 0
        WHERE request_id = :requestId
          AND state IN ('PENDING', 'UNKNOWN', 'LEGACY_UNKNOWN')
          AND claim_token IS NULL
          AND claim_lease_expires_at_epoch_ms IS NULL
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_targets.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND EXISTS(
              SELECT 1 FROM sweep_requests
              WHERE sweep_requests.request_id = sweep_targets.request_id
                AND sweep_requests.state = 'CANCEL_REQUESTED'
                AND sweep_requests.terminal_state IS NULL
                AND ((sweep_requests.claim_token IS NULL AND :requestClaimToken IS NULL)
                    OR sweep_requests.claim_token = :requestClaimToken)
          )
        """
    )
    protected abstract suspend fun cancelInactiveTargets(
        requestId: String,
        requestClaimToken: String?,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET state = :terminalState,
            terminal_state = :terminalState,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs,
            terminal_at_epoch_ms = :nowMs,
            retain_until_epoch_ms = :retainUntilEpochMs
        WHERE request_id = :requestId
          AND state = 'CANCEL_REQUESTED'
          AND terminal_state IS NULL
          AND ((claim_token IS NULL AND :requestClaimToken IS NULL) OR claim_token = :requestClaimToken)
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state IN ('PENDING', 'RUNNING', 'UNKNOWN', 'LEGACY_UNKNOWN')
          )
        """
    )
    protected abstract suspend fun settleCancellationRow(
        requestId: String,
        requestClaimToken: String?,
        terminalState: String,
        nowMs: Long,
        retainUntilEpochMs: Long = nowMs + SWEEP_RESULT_RETENTION_MS,
    ): Int

    @Query(
        """
        UPDATE sweep_targets
        SET state = 'CANCELLED',
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            finished_at_epoch_ms = :nowMs,
            result_code = 'CANCELLED',
            root_lane_degraded = 0
        WHERE request_id = :requestId
          AND ordinal = :ordinal
          AND state = 'RUNNING'
          AND (
              (claim_token IS NULL
                  AND claim_lease_expires_at_epoch_ms IS NULL
                  AND :targetClaimToken IS NULL
                  AND :targetClaimLeaseExpiresAtEpochMs IS NULL)
              OR (claim_token = :targetClaimToken
                  AND claim_lease_expires_at_epoch_ms = :targetClaimLeaseExpiresAtEpochMs)
          )
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_targets.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND EXISTS(
              SELECT 1 FROM sweep_requests
              WHERE sweep_requests.request_id = sweep_targets.request_id
                AND sweep_requests.state = 'CANCEL_REQUESTED'
                AND sweep_requests.terminal_state IS NULL
                AND ((sweep_requests.claim_token IS NULL AND :requestClaimToken IS NULL)
                    OR sweep_requests.claim_token = :requestClaimToken)
          )
        """
    )
    protected abstract suspend fun recoverTargetAsCancelled(
        requestId: String,
        ordinal: Int,
        requestClaimToken: String?,
        targetClaimToken: String?,
        targetClaimLeaseExpiresAtEpochMs: Long?,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_targets
        SET state = :terminalState,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            finished_at_epoch_ms = :finishedAtEpochMs,
            result_code = :resultCode,
            root_lane_degraded = :rootLaneDegraded
        WHERE request_id = :requestId
          AND ordinal = :ordinal
          AND state = :expectedState
          AND (
              (claim_token IS NULL
                  AND claim_lease_expires_at_epoch_ms IS NULL
                  AND :targetClaimToken IS NULL
                  AND :targetClaimLeaseExpiresAtEpochMs IS NULL)
              OR (claim_token = :targetClaimToken
                  AND claim_lease_expires_at_epoch_ms = :targetClaimLeaseExpiresAtEpochMs)
          )
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_targets.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND EXISTS(
              SELECT 1 FROM sweep_requests
              WHERE sweep_requests.request_id = sweep_targets.request_id
                AND sweep_requests.state != 'CANCEL_REQUESTED'
                AND sweep_requests.terminal_state IS NULL
                AND ((sweep_requests.claim_token IS NULL AND :requestClaimToken IS NULL)
                    OR sweep_requests.claim_token = :requestClaimToken)
          )
        """
    )
    protected abstract suspend fun recoverTargetCompleted(
        requestId: String,
        ordinal: Int,
        expectedState: String,
        requestClaimToken: String?,
        targetClaimToken: String?,
        targetClaimLeaseExpiresAtEpochMs: Long?,
        terminalState: String,
        resultCode: String,
        rootLaneDegraded: Boolean,
        finishedAtEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_targets
        SET state = 'PENDING',
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            finished_at_epoch_ms = NULL,
            result_code = :resultCode,
            root_lane_degraded = 0
        WHERE request_id = :requestId
          AND ordinal = :ordinal
          AND state = :expectedState
          AND (
              (claim_token IS NULL
                  AND claim_lease_expires_at_epoch_ms IS NULL
                  AND :targetClaimToken IS NULL
                  AND :targetClaimLeaseExpiresAtEpochMs IS NULL)
              OR (claim_token = :targetClaimToken
                  AND claim_lease_expires_at_epoch_ms = :targetClaimLeaseExpiresAtEpochMs)
          )
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_targets.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND EXISTS(
              SELECT 1 FROM sweep_requests
              WHERE sweep_requests.request_id = sweep_targets.request_id
                AND sweep_requests.state != 'CANCEL_REQUESTED'
                AND sweep_requests.terminal_state IS NULL
                AND ((sweep_requests.claim_token IS NULL AND :requestClaimToken IS NULL)
                    OR sweep_requests.claim_token = :requestClaimToken)
          )
        """
    )
    protected abstract suspend fun recoverTargetForRetry(
        requestId: String,
        ordinal: Int,
        expectedState: String,
        requestClaimToken: String?,
        targetClaimToken: String?,
        targetClaimLeaseExpiresAtEpochMs: Long?,
        resultCode: String,
    ): Int

    @Query(
        """
        UPDATE sweep_targets
        SET state = 'UNKNOWN',
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            finished_at_epoch_ms = :nowMs,
            result_code = :resultCode,
            root_lane_degraded = 0
        WHERE request_id = :requestId
          AND ordinal = :ordinal
          AND state = :expectedState
          AND (
              (claim_token IS NULL
                  AND claim_lease_expires_at_epoch_ms IS NULL
                  AND :targetClaimToken IS NULL
                  AND :targetClaimLeaseExpiresAtEpochMs IS NULL)
              OR (claim_token = :targetClaimToken
                  AND claim_lease_expires_at_epoch_ms = :targetClaimLeaseExpiresAtEpochMs)
          )
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_targets.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND EXISTS(
              SELECT 1 FROM sweep_requests
              WHERE sweep_requests.request_id = sweep_targets.request_id
                AND sweep_requests.state != 'CANCEL_REQUESTED'
                AND sweep_requests.terminal_state IS NULL
                AND ((sweep_requests.claim_token IS NULL AND :requestClaimToken IS NULL)
                    OR sweep_requests.claim_token = :requestClaimToken)
          )
        """
    )
    protected abstract suspend fun recoverTargetAsUnknown(
        requestId: String,
        ordinal: Int,
        expectedState: String,
        requestClaimToken: String?,
        targetClaimToken: String?,
        targetClaimLeaseExpiresAtEpochMs: Long?,
        resultCode: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_targets
        SET state = 'PENDING',
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            finished_at_epoch_ms = NULL,
            root_lane_degraded = 0
        WHERE request_id = :requestId
          AND ordinal = :ordinal
          AND state = :expectedTargetState
          AND state IN ('UNKNOWN', 'LEGACY_UNKNOWN')
          AND claim_token IS NULL
          AND claim_lease_expires_at_epoch_ms IS NULL
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_targets.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND EXISTS(
              SELECT 1 FROM sweep_requests
              WHERE sweep_requests.request_id = sweep_targets.request_id
                AND sweep_requests.state = :expectedRequestState
                AND sweep_requests.terminal_state IS NULL
                AND sweep_requests.service_session_token IS NULL
                AND sweep_requests.claim_token IS NULL
                AND sweep_requests.claim_lease_expires_at_epoch_ms IS NULL
          )
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS active_targets
              WHERE active_targets.request_id = sweep_targets.request_id
                AND active_targets.state = 'RUNNING'
          )
        """
    )
    protected abstract suspend fun authorizeUnknownTargetRetryRow(
        requestId: String,
        ordinal: Int,
        expectedTargetState: String,
        expectedRequestState: String,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET state = 'QUEUED',
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            claimed_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs,
            block_reason = NULL
        WHERE request_id = :requestId
          AND state = :expectedState
          AND terminal_state IS NULL
          AND ((claim_token IS NULL AND :requestClaimToken IS NULL) OR claim_token = :requestClaimToken)
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state = 'RUNNING'
          )
          AND EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state = 'PENDING'
                AND sweep_targets.claim_token IS NULL
                AND sweep_targets.claim_lease_expires_at_epoch_ms IS NULL
          )
        """
    )
    protected abstract suspend fun queueRecoveredRequest(
        requestId: String,
        expectedState: String,
        requestClaimToken: String?,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET state = 'BLOCKED',
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            claimed_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs,
            block_reason = NULL
        WHERE request_id = :requestId
          AND state = :expectedState
          AND terminal_state IS NULL
          AND ((claim_token IS NULL AND :requestClaimToken IS NULL) OR claim_token = :requestClaimToken)
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state = 'RUNNING'
          )
          AND EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state IN ('UNKNOWN', 'LEGACY_UNKNOWN')
                AND sweep_targets.claim_token IS NULL
                AND sweep_targets.claim_lease_expires_at_epoch_ms IS NULL
          )
        """
    )
    protected abstract suspend fun blockRecoveredRequest(
        requestId: String,
        expectedState: String,
        requestClaimToken: String?,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET state = 'QUEUED',
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            claimed_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs,
            block_reason = NULL
        WHERE request_id = :requestId
          AND state = :expectedState
          AND terminal_state IS NULL
          AND service_session_token = :previousSessionToken
          AND claim_token = :previousClaimToken
          AND claim_lease_expires_at_epoch_ms = :previousLeaseUntilMs
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state = 'RUNNING'
          )
          AND EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state = 'PENDING'
                AND sweep_targets.claim_token IS NULL
                AND sweep_targets.claim_lease_expires_at_epoch_ms IS NULL
          )
        """
    )
    protected abstract suspend fun recoverRequestToQueue(
        requestId: String,
        expectedState: String,
        previousSessionToken: String,
        previousClaimToken: String,
        previousLeaseUntilMs: Long,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET state = 'BLOCKED',
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            claimed_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs,
            block_reason = NULL
        WHERE request_id = :requestId
          AND state = :expectedState
          AND terminal_state IS NULL
          AND service_session_token = :previousSessionToken
          AND claim_token = :previousClaimToken
          AND claim_lease_expires_at_epoch_ms = :previousLeaseUntilMs
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state IN ('PENDING', 'RUNNING')
          )
          AND EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state IN ('UNKNOWN', 'LEGACY_UNKNOWN')
                AND sweep_targets.claim_token IS NULL
                AND sweep_targets.claim_lease_expires_at_epoch_ms IS NULL
          )
        """
    )
    protected abstract suspend fun recoverRequestToBlocked(
        requestId: String,
        expectedState: String,
        previousSessionToken: String,
        previousClaimToken: String,
        previousLeaseUntilMs: Long,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET state = :terminalState,
            terminal_state = :terminalState,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            succeeded = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'SUCCEEDED'
            ),
            failed = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'FAILED'
            ),
            busy = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'BUSY'
            ),
            unresolved = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state NOT IN ('SUCCEEDED', 'FAILED', 'BUSY')
            ),
            updated_at_epoch_ms = :nowMs,
            terminal_at_epoch_ms = :nowMs,
            retain_until_epoch_ms = :retainUntilEpochMs
        WHERE request_id = :requestId
          AND state = :expectedState
          AND terminal_state IS NULL
          AND service_session_token = :previousSessionToken
          AND claim_token = :previousClaimToken
          AND claim_lease_expires_at_epoch_ms = :previousLeaseUntilMs
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state IN ('PENDING', 'RUNNING', 'UNKNOWN', 'LEGACY_UNKNOWN')
          )
        """
    )
    protected abstract suspend fun finalizeRecoveredRequest(
        requestId: String,
        expectedState: String,
        previousSessionToken: String,
        previousClaimToken: String,
        previousLeaseUntilMs: Long,
        terminalState: String,
        nowMs: Long,
        retainUntilEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET state = 'QUEUED',
            succeeded = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'SUCCEEDED'
            ),
            failed = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'FAILED'
            ),
            busy = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state = 'BUSY'
            ),
            unresolved = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
                  AND sweep_targets.state NOT IN ('SUCCEEDED', 'FAILED', 'BUSY')
            ),
            updated_at_epoch_ms = :nowMs,
            block_reason = NULL
        WHERE request_id = :requestId
          AND state = :expectedState
          AND terminal_state IS NULL
          AND service_session_token IS NULL
          AND claim_token IS NULL
          AND claim_lease_expires_at_epoch_ms IS NULL
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state = 'RUNNING'
          )
          AND EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state = 'PENDING'
                AND sweep_targets.claim_token IS NULL
                AND sweep_targets.claim_lease_expires_at_epoch_ms IS NULL
          )
        """
    )
    protected abstract suspend fun queueUnownedRequestAfterRetry(
        requestId: String,
        expectedState: String,
        nowMs: Long,
    ): Int

    private suspend fun settleRequestAfterRecovery(
        request: SweepRequestEntity,
        nowMs: Long,
    ) {
        when {
            countTargetsInState(
                request.requestId,
                StoredSweepTargetState.RUNNING.name,
            ) > 0 -> Unit

            countTargetsInState(
                request.requestId,
                StoredSweepTargetState.PENDING.name,
            ) > 0 -> check(
                queueRecoveredRequest(
                    request.requestId,
                    request.state,
                    request.claimToken,
                    nowMs,
                ) == 1,
            )

            countTargetsInStates(
                request.requestId,
                StoredSweepTargetState.UNKNOWN.name,
                StoredSweepTargetState.LEGACY_UNKNOWN.name,
            ) > 0 -> check(
                blockRecoveredRequest(
                    request.requestId,
                    request.state,
                    request.claimToken,
                    nowMs,
                ) == 1,
            )

            else -> check(
                finishRecoveredRequest(
                    request.requestId,
                    request.state,
                    request.claimToken,
                    deriveTerminalState(request.requestId).name,
                    nowMs,
                    nowMs + SWEEP_RESULT_RETENTION_MS,
                ) == 1,
            )
        }
    }

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM sweep_requests
            WHERE request_id = :requestId
              AND (
                  ((service_session_token IS NULL OR
                      claim_token IS NULL OR
                      claim_lease_expires_at_epoch_ms IS NULL) AND
                   (service_session_token IS NOT NULL OR
                      claim_token IS NOT NULL OR
                      claim_lease_expires_at_epoch_ms IS NOT NULL)) OR
                  service_session_token = '' OR
                  service_session_token GLOB '*[^A-Za-z0-9._:-]*' OR
                  claim_token = '' OR
                  claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                  claim_lease_expires_at_epoch_ms < 0
              )
        )
        """
    )
    protected abstract suspend fun hasMalformedRequestOwnership(requestId: String): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM sweep_targets
            WHERE request_id = :requestId
              AND (
                  (state = 'RUNNING' AND (
                      claim_token IS NULL OR
                      claim_token = '' OR
                      claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                      claim_lease_expires_at_epoch_ms IS NULL OR
                      claim_lease_expires_at_epoch_ms < 0
                  )) OR
                  (state != 'RUNNING' AND (
                      claim_token IS NOT NULL OR
                      claim_lease_expires_at_epoch_ms IS NOT NULL
                  ))
              )
        )
        """
    )
    protected abstract suspend fun hasMalformedTargetOwnership(requestId: String): Boolean

    @Query(
        """
        SELECT COUNT(*) FROM sweep_targets
        WHERE request_id = :requestId AND state IN (:firstState, :secondState)
        """
    )
    protected abstract suspend fun countTargetsInStates(
        requestId: String,
        firstState: String,
        secondState: String,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM sweep_targets
        WHERE request_id = :requestId AND state = :state
        """
    )
    protected abstract suspend fun countTargetsInState(
        requestId: String,
        state: String,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM sweep_targets
        WHERE request_id = :requestId
          AND state IN ('PENDING', 'RUNNING', 'UNKNOWN', 'LEGACY_UNKNOWN')
        """
    )
    protected abstract suspend fun countUnfinishedTargets(requestId: String): Int

    private suspend fun deriveTerminalState(requestId: String): StoredSweepRequestState {
        val succeeded = countTargetsInState(requestId, StoredSweepTargetState.SUCCEEDED.name)
        val failed = countTargetsInState(requestId, StoredSweepTargetState.FAILED.name)
        val busy = countTargetsInState(requestId, StoredSweepTargetState.BUSY.name)
        val cancelled = countTargetsInState(requestId, StoredSweepTargetState.CANCELLED.name)
        return when {
            failed == 0 && busy == 0 && cancelled == 0 -> StoredSweepRequestState.SUCCEEDED
            succeeded > 0 -> StoredSweepRequestState.PARTIAL
            failed > 0 || busy > 0 -> StoredSweepRequestState.FAILED
            else -> StoredSweepRequestState.CANCELLED
        }
    }

    @Query(
        """
        UPDATE sweep_requests
        SET state = :terminalState,
            terminal_state = :terminalState,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs,
            terminal_at_epoch_ms = :nowMs,
            retain_until_epoch_ms = :retainUntilEpochMs
        WHERE request_id = :requestId
          AND state = :expectedState
          AND terminal_state IS NULL
          AND ((claim_token IS NULL AND :requestClaimToken IS NULL) OR claim_token = :requestClaimToken)
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
        """
    )
    protected abstract suspend fun finishRecoveredRequest(
        requestId: String,
        expectedState: String,
        requestClaimToken: String?,
        terminalState: String,
        nowMs: Long,
        retainUntilEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET state = :terminalState,
            terminal_state = :terminalState,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs,
            terminal_at_epoch_ms = :nowMs,
            retain_until_epoch_ms = :retainUntilEpochMs
        WHERE request_id = :requestId
          AND state = 'RUNNING'
          AND terminal_state IS NULL
          AND claim_token = :claimToken
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
        """
    )
    protected abstract suspend fun finishClaimedRequestRow(
        requestId: String,
        claimToken: String,
        terminalState: String,
        nowMs: Long,
        retainUntilEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET state = 'BLOCKED',
            block_reason = :reason,
            claimed_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs
        WHERE request_id = :requestId
          AND state = 'QUEUED'
          AND terminal_state IS NULL
          AND service_session_token IS NULL
          AND claim_token IS NULL
          AND claim_lease_expires_at_epoch_ms IS NULL
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND (sweep_targets.state = 'RUNNING'
                    OR sweep_targets.claim_token IS NOT NULL
                    OR sweep_targets.claim_lease_expires_at_epoch_ms IS NOT NULL)
          )
        """
    )
    protected abstract suspend fun markUnclaimedStartBlockedRow(
        requestId: String,
        reason: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET state = 'BLOCKED',
            block_reason = :reason,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            claimed_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs
        WHERE request_id = :requestId
          AND state = 'RUNNING'
          AND terminal_state IS NULL
          AND claim_token = :requestClaimToken
          AND service_session_token IS NOT NULL
          AND service_session_token != ''
          AND claim_lease_expires_at_epoch_ms IS NOT NULL
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND (sweep_targets.state = 'RUNNING'
                    OR sweep_targets.claim_token IS NOT NULL
                    OR sweep_targets.claim_lease_expires_at_epoch_ms IS NOT NULL)
          )
          AND EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state = 'PENDING'
          )
        """
    )
    protected abstract suspend fun blockClaimedRequestForMissingPrivilegeRow(
        requestId: String,
        requestClaimToken: String,
        nowMs: Long,
        reason: String = StoredSweepBlockReason.PRIVILEGE_AUTHORIZATION_REQUIRED.name,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET state = 'QUEUED',
            block_reason = NULL,
            claimed_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs
        WHERE request_id = :requestId
          AND state = 'BLOCKED'
          AND terminal_state IS NULL
          AND block_reason = :expectedReason
          AND service_session_token IS NULL
          AND claim_token IS NULL
          AND claim_lease_expires_at_epoch_ms IS NULL
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND (sweep_targets.state = 'RUNNING'
                    OR sweep_targets.claim_token IS NOT NULL
                    OR sweep_targets.claim_lease_expires_at_epoch_ms IS NOT NULL)
          )
          AND EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state = 'PENDING'
          )
        """
    )
    protected abstract suspend fun resumeBlockedRequestRow(
        requestId: String,
        expectedReason: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_targets
        SET state = 'LEGACY_UNKNOWN',
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            finished_at_epoch_ms = NULL,
            result_code = NULL,
            root_lane_degraded = 0
        WHERE request_id = :requestId
          AND ordinal = :ordinal
          AND state = 'PENDING'
          AND claim_token IS NULL
          AND claim_lease_expires_at_epoch_ms IS NULL
        """
    )
    protected abstract suspend fun markLegacyTargetUnknownRow(
        requestId: String,
        ordinal: Int,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET state = :state,
            block_reason = NULL,
            service_session_token = NULL,
            claim_token = NULL,
            claim_lease_expires_at_epoch_ms = NULL,
            claimed_at_epoch_ms = NULL,
            updated_at_epoch_ms = :nowMs
        WHERE request_id = :requestId
          AND terminal_state IS NULL
          AND service_session_token IS NULL
          AND claim_token IS NULL
          AND claim_lease_expires_at_epoch_ms IS NULL
          AND NOT EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND (sweep_targets.state = 'RUNNING'
                    OR sweep_targets.claim_token IS NOT NULL
                    OR sweep_targets.claim_lease_expires_at_epoch_ms IS NOT NULL)
          )
        """
    )
    protected abstract suspend fun settleLegacyRequestRow(
        requestId: String,
        state: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM sweep_requests
            WHERE state = 'QUEUED'
              AND terminal_state IS NULL
              AND service_session_token IS NULL
              AND claim_token IS NULL
              AND claim_lease_expires_at_epoch_ms IS NULL
              AND NOT EXISTS(
              SELECT 1 FROM sweep_targets AS malformed_targets
              WHERE malformed_targets.request_id = sweep_requests.request_id
                AND (
                    (malformed_targets.state = 'RUNNING' AND (
                        malformed_targets.claim_token IS NULL OR
                        malformed_targets.claim_token = '' OR
                        malformed_targets.claim_token GLOB '*[^A-Za-z0-9._:-]*' OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms < 0
                    )) OR
                    (malformed_targets.state != 'RUNNING' AND (
                        malformed_targets.claim_token IS NOT NULL OR
                        malformed_targets.claim_lease_expires_at_epoch_ms IS NOT NULL
                    ))
                )
          )
              AND EXISTS(
                  SELECT 1 FROM sweep_targets
                  WHERE sweep_targets.request_id = sweep_requests.request_id
                    AND sweep_targets.state = 'PENDING'
                    AND sweep_targets.claim_token IS NULL
                    AND sweep_targets.claim_lease_expires_at_epoch_ms IS NULL
              )
        )
        """
    )
    protected abstract suspend fun hasRunnableRequestsQuery(): Boolean

    @Query(
        """
        UPDATE sweep_requests
        SET succeeded = 0, failed = 0, busy = 0, unresolved = 0
        WHERE request_id = :requestId AND terminal_state IS NULL
        """
    )
    abstract suspend fun resetForRunRow(requestId: String): Int

    @Transaction
    open suspend fun resetForRun(requestId: String): SweepRequestWithTargets? {
        if (resetForRunRow(requestId) != 1) return null
        return load(requestId)
    }

    @Query(
        """
        UPDATE sweep_requests
        SET succeeded = COALESCE(succeeded, 0) + 1
        WHERE request_id = :requestId AND terminal_state IS NULL
        """
    )
    abstract suspend fun incrementSucceeded(requestId: String): Int

    @Query(
        """
        UPDATE sweep_requests
        SET failed = COALESCE(failed, 0) + 1
        WHERE request_id = :requestId AND terminal_state IS NULL
        """
    )
    abstract suspend fun incrementFailed(requestId: String): Int

    @Query(
        """
        UPDATE sweep_requests
        SET busy = COALESCE(busy, 0) + 1
        WHERE request_id = :requestId AND terminal_state IS NULL
        """
    )
    abstract suspend fun incrementBusy(requestId: String): Int

    open suspend fun recordAttempt(requestId: String, outcome: SweepAttemptOutcome): Int =
        when (outcome) {
            SweepAttemptOutcome.SUCCEEDED -> incrementSucceeded(requestId)
            SweepAttemptOutcome.FAILED -> incrementFailed(requestId)
            SweepAttemptOutcome.BUSY -> incrementBusy(requestId)
        }

    @Query(
        """
        UPDATE sweep_requests
        SET terminal_state = :terminalState,
            succeeded = COALESCE(succeeded, 0),
            failed = COALESCE(failed, 0),
            busy = COALESCE(busy, 0),
            unresolved = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
            ) - COALESCE(succeeded, 0) - COALESCE(failed, 0) - COALESCE(busy, 0),
            terminal_at_epoch_ms = :nowMs,
            retain_until_epoch_ms = :retainUntilEpochMs
        WHERE request_id = :requestId AND terminal_state IS NULL
        """
    )
    abstract suspend fun finish(
        requestId: String,
        terminalState: String,
        nowMs: Long,
        retainUntilEpochMs: Long,
    ): Int

    @Query(
        """
        SELECT request_id FROM sweep_requests
        WHERE terminal_state IS NULL
        ORDER BY created_at_epoch_ms ASC, request_id ASC
        """
    )
    abstract suspend fun loadNonterminalRequestIds(): List<String>

    @Query(
        """
        UPDATE sweep_requests
        SET terminal_state = :terminalState,
            succeeded = COALESCE(succeeded, 0),
            failed = COALESCE(failed, 0),
            busy = COALESCE(busy, 0),
            unresolved = (
                SELECT COUNT(*) FROM sweep_targets
                WHERE sweep_targets.request_id = sweep_requests.request_id
            ) - COALESCE(succeeded, 0) - COALESCE(failed, 0) - COALESCE(busy, 0),
            terminal_at_epoch_ms = :nowMs,
            retain_until_epoch_ms = :retainUntilEpochMs
        WHERE terminal_state IS NULL
        """
    )
    abstract suspend fun cancelNonterminalRows(
        terminalState: String,
        nowMs: Long,
        retainUntilEpochMs: Long,
    ): Int

    @Transaction
    open suspend fun cancelAllNonterminal(
        terminalState: String,
        nowMs: Long,
        retainUntilEpochMs: Long,
    ): List<String> {
        val requestIds = loadNonterminalRequestIds()
        cancelNonterminalRows(terminalState, nowMs, retainUntilEpochMs)
        return requestIds
    }

    @Query("DELETE FROM sweep_requests WHERE request_id = :requestId")
    abstract suspend fun delete(requestId: String)

    @Query(
        """
        DELETE FROM sweep_requests
        WHERE terminal_state IS NOT NULL
          AND retain_until_epoch_ms IS NOT NULL
          AND retain_until_epoch_ms <= :nowMs
        """
    )
    abstract suspend fun deleteExpired(nowMs: Long): Int

    private fun SweepRequestWithTargets.toClaimedRequest(): ClaimedSweepRequest {
        val request = request
        require(request.hasValidOwnedRequestOwnership()) {
            "Claimed request has malformed ownership"
        }
        val sessionToken = requireNotNull(request.serviceSessionToken)
        val claimToken = requireNotNull(request.claimToken)
        val leaseUntilMs = requireNotNull(request.claimLeaseExpiresAtEpochMs)
        val claimedAtMs = requireNotNull(request.claimedAtEpochMs)
        val operation = PrivilegeSweepOperation.valueOf(request.operation)
        val freezerMode = request.freezerMode?.let(FreezerMode::valueOf)
        require((operation == PrivilegeSweepOperation.FREEZE) == (freezerMode != null)) {
            "Only FREEZE requests may carry a freezer mode"
        }
        return ClaimedSweepRequest(
            requestId = request.requestId,
            queueSequence = request.queueSequence,
            payloadSchemaVersion = request.payloadSchemaVersion,
            executionId = request.executionId,
            operation = operation,
            freezerMode = freezerMode,
            userId = request.userId,
            source = PrivilegeSweepSource.valueOf(request.sourceSurface),
            sourceAssociations = sources.mapTo(linkedSetOf()) { it.sourceSurface },
            targetCount = targets.size,
            succeeded = request.succeeded,
            failed = request.failed,
            busy = request.busy,
            unresolved = request.unresolved,
            serviceSessionToken = sessionToken,
            claimToken = claimToken,
            claimLeaseExpiresAtEpochMs = leaseUntilMs,
            attemptCount = request.attemptCount,
            createdAtEpochMs = request.createdAtEpochMs,
            claimedAtEpochMs = claimedAtMs,
        )
    }

    private fun SweepTargetEntity.toClaimedTarget(): ClaimedSweepTarget {
        require(hasValidTargetOwnership()) { "Claimed target has malformed ownership" }
        return ClaimedSweepTarget(
            requestId = requestId,
            ordinal = ordinal,
            packageName = packageName,
            claimToken = requireNotNull(claimToken),
            claimLeaseExpiresAtEpochMs = requireNotNull(claimLeaseExpiresAtEpochMs),
            attemptCount = attemptCount,
            startedAtEpochMs = requireNotNull(startedAtEpochMs),
        )
    }

    private fun SweepRequestEntity.toRecoveryCandidate(
        target: SweepTargetEntity,
        previousSessionToken: String,
    ): SweepRequestRecoveryCandidate? {
        if (
            !previousSessionToken.isStorageSafeOwnershipToken() ||
            !hasValidOwnedRequestOwnership() ||
            !target.hasValidTargetOwnership()
        ) {
            return null
        }
        val previousRequestClaimToken = requireNotNull(claimToken)
        val previousRequestClaimLease = requireNotNull(claimLeaseExpiresAtEpochMs)
        val activeTargetClaimToken = requireNotNull(target.claimToken)
        val activeTargetClaimLease = requireNotNull(target.claimLeaseExpiresAtEpochMs)
        val storedOperation = PrivilegeSweepOperation.valueOf(operation)
        val storedFreezerMode = freezerMode?.let(FreezerMode::valueOf)
        require(
            (storedOperation == PrivilegeSweepOperation.FREEZE) == (storedFreezerMode != null),
        ) {
            "Only FREEZE requests may carry a freezer mode"
        }
        return SweepRequestRecoveryCandidate(
            requestId = requestId,
            operation = storedOperation,
            freezerMode = storedFreezerMode,
            userId = userId,
            activeTargetOrdinal = target.ordinal,
            packageName = target.packageName,
            previousServiceSessionToken = previousSessionToken,
            previousRequestClaimToken = previousRequestClaimToken,
            previousRequestClaimLeaseExpiresAtEpochMs = previousRequestClaimLease,
            activeTargetClaimToken = activeTargetClaimToken,
            activeTargetClaimLeaseExpiresAtEpochMs = activeTargetClaimLease,
        )
    }

    private fun String.isStorageSafeOwnershipToken(): Boolean =
        isNotEmpty() && all { character ->
            character in 'A'..'Z' ||
                    character in 'a'..'z' ||
                    character in '0'..'9' ||
                    character == '.' ||
                    character == '_' ||
                    character == ':' ||
                    character == '-'
        }

    private fun SweepRequestEntity.hasValidOwnedRequestOwnership(): Boolean =
        serviceSessionToken?.isStorageSafeOwnershipToken() == true &&
                claimToken?.isStorageSafeOwnershipToken() == true &&
                claimLeaseExpiresAtEpochMs?.let { it >= 0L } == true

    private fun SweepRequestEntity.hasUnownedRequestOwnership(): Boolean =
        serviceSessionToken == null &&
                claimToken == null &&
                claimLeaseExpiresAtEpochMs == null

    private fun SweepTargetEntity.hasValidTargetOwnership(): Boolean =
        if (state == StoredSweepTargetState.RUNNING.name) {
            claimToken?.isStorageSafeOwnershipToken() == true &&
                    claimLeaseExpiresAtEpochMs?.let { it >= 0L } == true
        } else {
            claimToken == null && claimLeaseExpiresAtEpochMs == null
        }

    private fun SweepTargetEntity.isPendingOrCompleteLegacyUnknown(): Boolean =
        state == StoredSweepTargetState.PENDING.name ||
                (state == StoredSweepTargetState.LEGACY_UNKNOWN.name &&
                        claimToken == null &&
                        claimLeaseExpiresAtEpochMs == null &&
                        finishedAtEpochMs == null &&
                        resultCode == null &&
                        !rootLaneDegraded)

    private fun StoredSweepRequestState.isTerminal(): Boolean =
        this == StoredSweepRequestState.SUCCEEDED ||
                this == StoredSweepRequestState.PARTIAL ||
                this == StoredSweepRequestState.CANCELLED ||
                this == StoredSweepRequestState.FAILED

    private companion object {
        val ACKNOWLEDGEABLE_REQUEST_STATES = setOf("SUCCEEDED", "PARTIAL", "CANCELLED", "FAILED")
        const val SWEEP_RESULT_RETENTION_MS = 24L * 60L * 60L * 1_000L
    }
}
