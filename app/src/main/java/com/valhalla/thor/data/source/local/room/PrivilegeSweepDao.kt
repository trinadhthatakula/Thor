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

        insertRequest(request)
        insertTargets(targets)
        upsertSources(sources)
        return SweepRequestCreation(
            snapshot = SweepRequestWithTargets(request, targets, sources),
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
    open suspend fun claimOldestRunnableRequest(
        sessionToken: String,
        claimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): ClaimedSweepRequest? {
        require(sessionToken.isNotBlank()) { "sessionToken must not be blank" }
        require(claimToken.isNotBlank()) { "claimToken must not be blank" }
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
        require(requestClaimToken.isNotBlank()) { "requestClaimToken must not be blank" }
        require(targetClaimToken.isNotBlank()) { "targetClaimToken must not be blank" }
        require(nowMs >= 0L) { "nowMs must not be negative" }
        require(leaseUntilMs > nowMs) { "leaseUntilMs must be later than nowMs" }
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
        """
    )
    protected abstract suspend fun renewRequestClaimRow(
        requestId: String,
        claimToken: String,
        leaseUntilMs: Long,
    ): Int

    open suspend fun renewRequestClaim(
        requestId: String,
        claimToken: String,
        leaseUntilMs: Long,
    ): Boolean {
        require(claimToken.isNotBlank()) { "claimToken must not be blank" }
        require(leaseUntilMs >= 0L) { "leaseUntilMs must not be negative" }
        return renewRequestClaimRow(requestId, claimToken, leaseUntilMs) == 1
    }

    @Query(
        """
        UPDATE sweep_targets
        SET claim_lease_expires_at_epoch_ms = :leaseUntilMs
        WHERE request_id = :requestId
          AND ordinal = :ordinal
          AND state = 'RUNNING'
          AND claim_token = :claimToken
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

    open suspend fun renewTargetClaim(
        requestId: String,
        ordinal: Int,
        claimToken: String,
        leaseUntilMs: Long,
    ): Boolean {
        require(claimToken.isNotBlank()) { "claimToken must not be blank" }
        require(leaseUntilMs >= 0L) { "leaseUntilMs must not be negative" }
        return renewTargetClaimRow(requestId, ordinal, claimToken, leaseUntilMs) == 1
    }

    @Transaction
    open suspend fun completeClaimedTarget(
        requestId: String,
        ordinal: Int,
        requestClaimToken: String,
        targetClaimToken: String,
        result: StoredSweepTargetResult,
    ): Boolean {
        require(requestClaimToken.isNotBlank()) { "requestClaimToken must not be blank" }
        require(targetClaimToken.isNotBlank()) { "targetClaimToken must not be blank" }
        require(result.finishedAtEpochMs >= 0L) { "finishedAtEpochMs must not be negative" }
        return completeClaimedTargetRow(
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

        if (requestCancellationRow(requestId, request.state, nowMs) != 1) {
            return SweepCancellationDecision.NotFound
        }
        cancelInactiveTargets(requestId, nowMs)
        refreshRequestAggregates(requestId, nowMs)

        val activeTarget = loadRunningTarget(requestId)
        if (activeTarget != null) {
            return SweepCancellationDecision.InterruptActive(requestId, activeTarget.ordinal)
        }

        settleCancellationRow(requestId, request.claimToken, nowMs)
        return SweepCancellationDecision.Settled(requestId)
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
        val target = loadTarget(requestId, ordinal) ?: return false
        val targetState = StoredSweepTargetState.valueOf(target.state)

        if (request.state == StoredSweepRequestState.CANCEL_REQUESTED.name) {
            if (targetState != StoredSweepTargetState.RUNNING) return false
            if (
                recoverTargetAsCancelled(
                    requestId = requestId,
                    ordinal = ordinal,
                    requestClaimToken = request.claimToken,
                    targetClaimToken = target.claimToken,
                    nowMs = recovery.recoveredAtEpochMs,
                ) != 1
            ) {
                return false
            }
            refreshRequestAggregates(requestId, recovery.recoveredAtEpochMs)
            if (countTargetsInState(requestId, StoredSweepTargetState.RUNNING.name) == 0) {
                settleCancellationRow(requestId, request.claimToken, recovery.recoveredAtEpochMs)
            }
            return true
        }

        if (
            targetState == StoredSweepTargetState.RUNNING &&
            (target.claimLeaseExpiresAtEpochMs == null ||
                    target.claimLeaseExpiresAtEpochMs > recovery.recoveredAtEpochMs)
        ) {
            return false
        }
        if (
            targetState !in setOf(
                StoredSweepTargetState.RUNNING,
                StoredSweepTargetState.UNKNOWN,
                StoredSweepTargetState.LEGACY_UNKNOWN,
            )
        ) {
            return false
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
                resultCode = recovery.resultCode.value,
            )

            is StoredSweepRecovery.MarkUnknown -> recoverTargetAsUnknown(
                requestId = requestId,
                ordinal = ordinal,
                expectedState = target.state,
                requestClaimToken = request.claimToken,
                targetClaimToken = target.claimToken,
                resultCode = recovery.resultCode.value,
                nowMs = recovery.recoveredAtEpochMs,
            )
        }
        if (updated != 1) return false

        refreshRequestAggregates(requestId, recovery.recoveredAtEpochMs)
        when (recovery) {
            is StoredSweepRecovery.Requeue -> queueRecoveredRequest(
                requestId,
                request.state,
                request.claimToken,
                recovery.recoveredAtEpochMs,
            )

            is StoredSweepRecovery.MarkUnknown -> blockRecoveredRequest(
                requestId,
                request.state,
                request.claimToken,
                recovery.recoveredAtEpochMs,
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
        val target = loadTarget(requestId, ordinal) ?: return false
        val targetState = StoredSweepTargetState.valueOf(target.state)
        if (
            targetState != StoredSweepTargetState.UNKNOWN &&
            targetState != StoredSweepTargetState.LEGACY_UNKNOWN
        ) {
            return false
        }
        if (
            authorizeUnknownTargetRetryRow(
                requestId = requestId,
                ordinal = ordinal,
                expectedState = target.state,
                targetClaimToken = target.claimToken,
            ) != 1
        ) {
            return false
        }
        refreshRequestAggregates(requestId, nowMs)
        return queueRecoveredRequest(requestId, request.state, request.claimToken, nowMs) == 1
    }

    @Transaction
    open suspend fun finishClaimedRequestIfDrained(
        requestId: String,
        claimToken: String,
        nowMs: Long,
    ): Boolean {
        require(claimToken.isNotBlank()) { "claimToken must not be blank" }
        require(nowMs >= 0L) { "nowMs must not be negative" }
        val request = loadRequestEntity(requestId) ?: return false
        if (
            request.state != StoredSweepRequestState.RUNNING.name ||
            request.claimToken != claimToken
        ) {
            return false
        }
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
          AND claim_token IS NULL
          AND EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state = 'PENDING'
                AND sweep_targets.claim_token IS NULL
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
          AND EXISTS(
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state = 'PENDING'
                AND sweep_targets.claim_token IS NULL
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
        SET state = 'CANCEL_REQUESTED',
            cancel_requested_at_epoch_ms = COALESCE(cancel_requested_at_epoch_ms, :nowMs),
            updated_at_epoch_ms = :nowMs
        WHERE request_id = :requestId
          AND state = :expectedState
          AND terminal_state IS NULL
        """
    )
    protected abstract suspend fun requestCancellationRow(
        requestId: String,
        expectedState: String,
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
        """
    )
    protected abstract suspend fun cancelInactiveTargets(
        requestId: String,
        nowMs: Long,
    ): Int

    @Query(
        """
        UPDATE sweep_requests
        SET state = 'CANCELLED',
            terminal_state = 'CANCELLED',
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
              SELECT 1 FROM sweep_targets
              WHERE sweep_targets.request_id = sweep_requests.request_id
                AND sweep_targets.state = 'RUNNING'
          )
        """
    )
    protected abstract suspend fun settleCancellationRow(
        requestId: String,
        requestClaimToken: String?,
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
          AND ((claim_token IS NULL AND :targetClaimToken IS NULL) OR claim_token = :targetClaimToken)
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
          AND ((claim_token IS NULL AND :targetClaimToken IS NULL) OR claim_token = :targetClaimToken)
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
          AND ((claim_token IS NULL AND :targetClaimToken IS NULL) OR claim_token = :targetClaimToken)
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
          AND ((claim_token IS NULL AND :targetClaimToken IS NULL) OR claim_token = :targetClaimToken)
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
          AND state = :expectedState
          AND ((claim_token IS NULL AND :targetClaimToken IS NULL) OR claim_token = :targetClaimToken)
        """
    )
    protected abstract suspend fun authorizeUnknownTargetRetryRow(
        requestId: String,
        ordinal: Int,
        expectedState: String,
        targetClaimToken: String?,
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
        """
    )
    protected abstract suspend fun blockRecoveredRequest(
        requestId: String,
        expectedState: String,
        requestClaimToken: String?,
        nowMs: Long,
    ): Int

    private suspend fun settleRequestAfterRecovery(
        request: SweepRequestEntity,
        nowMs: Long,
    ) {
        when {
            countTargetsInStates(
                request.requestId,
                StoredSweepTargetState.PENDING.name,
                StoredSweepTargetState.RUNNING.name,
            ) > 0 -> queueRecoveredRequest(
                request.requestId,
                request.state,
                request.claimToken,
                nowMs,
            )

            countTargetsInStates(
                request.requestId,
                StoredSweepTargetState.UNKNOWN.name,
                StoredSweepTargetState.LEGACY_UNKNOWN.name,
            ) > 0 -> blockRecoveredRequest(
                request.requestId,
                request.state,
                request.claimToken,
                nowMs,
            )

            else -> finishRecoveredRequest(
                request.requestId,
                request.state,
                request.claimToken,
                deriveTerminalState(request.requestId).name,
                nowMs,
                nowMs + SWEEP_RESULT_RETENTION_MS,
            )
        }
    }

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
        SELECT EXISTS(
            SELECT 1 FROM sweep_requests
            WHERE state = 'QUEUED'
              AND terminal_state IS NULL
              AND claim_token IS NULL
              AND EXISTS(
                  SELECT 1 FROM sweep_targets
                  WHERE sweep_targets.request_id = sweep_requests.request_id
                    AND sweep_targets.state = 'PENDING'
                    AND sweep_targets.claim_token IS NULL
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

    private fun SweepTargetEntity.toClaimedTarget(): ClaimedSweepTarget = ClaimedSweepTarget(
        requestId = requestId,
        ordinal = ordinal,
        packageName = packageName,
        claimToken = requireNotNull(claimToken),
        claimLeaseExpiresAtEpochMs = requireNotNull(claimLeaseExpiresAtEpochMs),
        attemptCount = attemptCount,
        startedAtEpochMs = requireNotNull(startedAtEpochMs),
    )

    private fun StoredSweepRequestState.isTerminal(): Boolean =
        this == StoredSweepRequestState.SUCCEEDED ||
                this == StoredSweepRequestState.PARTIAL ||
                this == StoredSweepRequestState.CANCELLED ||
                this == StoredSweepRequestState.FAILED

    private companion object {
        const val SWEEP_RESULT_RETENTION_MS = 24L * 60L * 60L * 1_000L
    }
}
