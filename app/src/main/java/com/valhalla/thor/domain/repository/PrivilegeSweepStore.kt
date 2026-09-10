// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.normalizeSweepTargets
import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class SweepAttemptOutcome { SUCCEEDED, FAILED, BUSY }

enum class StoredSweepTerminal { SUCCEEDED, PARTIAL, CANCELLED, FAILED }

enum class PrivilegeSweepRequestState {
    QUEUED,
    RUNNING,
    CANCEL_REQUESTED,
    BLOCKED,
    SUCCEEDED,
    PARTIAL,
    CANCELLED,
    FAILED,
}

enum class PrivilegeSweepBlockReason {
    PRIVILEGE_AUTHORIZATION_REQUIRED,
    START_BLOCKED,
    START_BLOCKED_NOTIFICATION,
}

enum class PrivilegeSweepTargetState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    BUSY,
    CANCELLED,
    UNKNOWN,
    LEGACY_UNKNOWN,
}

enum class PrivilegeSweepTargetTerminalState {
    SUCCEEDED,
    FAILED,
    BUSY,
}

@JvmInline
value class PrivilegeSweepResultCode(val value: String) {
    init {
        require(value.matches(Regex("[A-Z0-9_]{1,64}")))
    }
}

data class NewPrivilegeSweepSnapshot(
    val requestId: UUID,
    @Deprecated("Use executionId", ReplaceWith("executionId"))
    val workId: UUID,
    val operation: PrivilegeSweepOperation,
    val freezerMode: FreezerMode?,
    val userId: Int,
    val source: PrivilegeSweepSource,
    val createdAtEpochMs: Long,
    val targets: List<String>,
    val sourceAssociations: Set<String> = setOf(source.name),
    val addToFreezer: Boolean = false,
) {
    @Suppress("DEPRECATION")
    val executionId: UUID
        get() = workId

    init {
        require(targets == normalizeSweepTargets(targets)) {
            "Sweep targets must already be canonical"
        }
        require((operation == PrivilegeSweepOperation.FREEZE) == (freezerMode != null)) {
            "Only FREEZE requires a resolved freezer mode"
        }
        require(!addToFreezer || operation == PrivilegeSweepOperation.FREEZE) {
            "Only FREEZE requests may add packages to the freezer"
        }
        require(source.name in sourceAssociations) {
            "Sweep source associations must preserve the ordinary source token"
        }
    }
}

data class StoredPrivilegeSweepTarget(
    val requestId: UUID,
    val ordinal: Int,
    val packageName: String,
    val state: PrivilegeSweepTargetState,
    val claimToken: String?,
    val claimLeaseExpiresAtEpochMs: Long?,
    val attemptCount: Int,
    val startedAtEpochMs: Long?,
    val finishedAtEpochMs: Long?,
    val resultCode: PrivilegeSweepResultCode?,
    val rootLaneDegraded: Boolean,
)

data class StoredPrivilegeSweep(
    val requestId: UUID,
    @Deprecated("Use executionId", ReplaceWith("executionId"))
    val workId: UUID,
    val executionId: UUID = workId,
    val operation: PrivilegeSweepOperation,
    val freezerMode: FreezerMode?,
    val userId: Int,
    val source: PrivilegeSweepSource,
    val createdAtEpochMs: Long,
    val targets: List<String>,
    val terminalState: StoredSweepTerminal?,
    val succeeded: Int,
    val failed: Int,
    val busy: Int,
    val unresolved: Int,
    val terminalAtEpochMs: Long?,
    val retainUntilEpochMs: Long?,
    val queueSequence: Long = 0L,
    val acknowledgedAtEpochMs: Long? = null,
    val sourceAssociations: Set<String> = setOf(source.name),
    val targetSnapshots: List<StoredPrivilegeSweepTarget> = emptyList(),
    val requestState: PrivilegeSweepRequestState = terminalState?.let {
        PrivilegeSweepRequestState.valueOf(it.name)
    } ?: PrivilegeSweepRequestState.QUEUED,
    val blockReason: PrivilegeSweepBlockReason? = null,
    val serviceSessionToken: String? = null,
    val claimToken: String? = null,
    val claimLeaseExpiresAtEpochMs: Long? = null,
    val addToFreezer: Boolean = false,
)

data class ClaimedPrivilegeSweepRequest(
    val requestId: UUID,
    val queueSequence: Long,
    val payloadSchemaVersion: Int,
    val executionId: UUID,
    val operation: PrivilegeSweepOperation,
    val freezerMode: FreezerMode?,
    val userId: Int,
    val source: PrivilegeSweepSource,
    val sourceAssociations: Set<String>,
    val targetCount: Int,
    val succeeded: Int,
    val failed: Int,
    val busy: Int,
    val unresolved: Int,
    val serviceSessionToken: String,
    val claimToken: String,
    val claimLeaseExpiresAtEpochMs: Long,
    val attemptCount: Int,
    val createdAtEpochMs: Long,
    val claimedAtEpochMs: Long,
    val addToFreezer: Boolean = false,
)

data class ClaimedPrivilegeSweepTarget(
    val requestId: UUID,
    val ordinal: Int,
    val packageName: String,
    val claimToken: String,
    val claimLeaseExpiresAtEpochMs: Long,
    val attemptCount: Int,
    val startedAtEpochMs: Long,
)

data class PrivilegeSweepRecoveryCandidate(
    val requestId: UUID,
    val operation: PrivilegeSweepOperation,
    val freezerMode: FreezerMode?,
    val userId: Int,
    val activeTargetOrdinal: Int,
    val packageName: String,
    val previousServiceSessionToken: String,
    val previousRequestClaimToken: String,
    val previousRequestClaimLeaseExpiresAtEpochMs: Long,
    val activeTargetClaimToken: String,
    val activeTargetClaimLeaseExpiresAtEpochMs: Long,
    val addToFreezer: Boolean = false,
)

data class PrivilegeSweepTargetResult(
    val terminalState: PrivilegeSweepTargetTerminalState,
    val resultCode: PrivilegeSweepResultCode,
    val rootLaneDegraded: Boolean,
    val finishedAtEpochMs: Long,
)

sealed interface PrivilegeSweepRecovery {
    val recoveredAtEpochMs: Long

    data class Completed(
        val result: PrivilegeSweepTargetResult,
        override val recoveredAtEpochMs: Long,
    ) : PrivilegeSweepRecovery

    data class Requeue(
        val resultCode: PrivilegeSweepResultCode,
        override val recoveredAtEpochMs: Long,
    ) : PrivilegeSweepRecovery

    data class MarkUnknown(
        val resultCode: PrivilegeSweepResultCode,
        override val recoveredAtEpochMs: Long,
    ) : PrivilegeSweepRecovery
}

sealed interface PrivilegeSweepCancellationDecision {
    data object NotFound : PrivilegeSweepCancellationDecision

    data class AlreadyTerminal(
        val requestId: UUID,
        val state: PrivilegeSweepRequestState,
    ) : PrivilegeSweepCancellationDecision

    data class Settled(
        val requestId: UUID,
    ) : PrivilegeSweepCancellationDecision

    data class InterruptActive(
        val requestId: UUID,
        val activeTargetOrdinal: Int,
    ) : PrivilegeSweepCancellationDecision
}

sealed interface SweepCreateResult {
    data class Created(val snapshot: StoredPrivilegeSweep) : SweepCreateResult
    data class Equivalent(val snapshot: StoredPrivilegeSweep) : SweepCreateResult
}

interface PrivilegeSweepStore {
    suspend fun createOrFindEquivalent(snapshot: NewPrivilegeSweepSnapshot): SweepCreateResult
    suspend fun load(requestId: UUID): StoredPrivilegeSweep?
    fun observe(requestId: UUID): Flow<StoredPrivilegeSweep?>
    fun observeRetained(): Flow<List<StoredPrivilegeSweep>>

    /** Retained requests ordered by the most recent launch association for [source]. */
    fun observeRetained(source: PrivilegeSweepSource): Flow<List<StoredPrivilegeSweep>>

    suspend fun claimOldestRunnableRequest(
        sessionToken: String,
        claimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): ClaimedPrivilegeSweepRequest? = claimAwareStoreUnavailable()

    suspend fun claimNextPendingTarget(
        requestId: UUID,
        requestClaimToken: String,
        targetClaimToken: String,
        nowMs: Long,
        leaseUntilMs: Long,
    ): ClaimedPrivilegeSweepTarget? = claimAwareStoreUnavailable()

    suspend fun renewRequestClaim(
        requestId: UUID,
        claimToken: String,
        leaseUntilMs: Long,
    ): Boolean = claimAwareStoreUnavailable()

    suspend fun renewTargetClaim(
        requestId: UUID,
        ordinal: Int,
        claimToken: String,
        leaseUntilMs: Long,
    ): Boolean = claimAwareStoreUnavailable()

    suspend fun completeClaimedTarget(
        requestId: UUID,
        ordinal: Int,
        requestClaimToken: String,
        targetClaimToken: String,
        result: PrivilegeSweepTargetResult,
    ): Boolean = claimAwareStoreUnavailable()

    suspend fun requestCancellation(
        requestId: UUID,
        nowMs: Long,
    ): PrivilegeSweepCancellationDecision = claimAwareStoreUnavailable()

    suspend fun recoverRequestClaims(
        sessionToken: String,
        nowMs: Long,
        localOwnerIsLive: (UUID, String) -> Boolean,
    ): List<PrivilegeSweepRecoveryCandidate> = claimAwareStoreUnavailable()

    suspend fun recoverInterruptedTarget(
        requestId: UUID,
        ordinal: Int,
        recovery: PrivilegeSweepRecovery,
    ): Boolean = claimAwareStoreUnavailable()

    suspend fun recoverInterruptedTarget(
        candidate: PrivilegeSweepRecoveryCandidate,
        recovery: PrivilegeSweepRecovery,
    ): Boolean = claimAwareStoreUnavailable()

    suspend fun authorizeUnknownTargetRetry(
        requestId: UUID,
        ordinal: Int,
        nowMs: Long,
    ): Boolean = claimAwareStoreUnavailable()

    suspend fun finishClaimedRequestIfDrained(
        requestId: UUID,
        claimToken: String,
        nowMs: Long,
    ): Boolean = claimAwareStoreUnavailable()

    suspend fun hasRunnableRequests(): Boolean = claimAwareStoreUnavailable()

    suspend fun finishDrainIfQueueEmpty(onQueueEmpty: () -> Unit): Boolean =
        claimAwareStoreUnavailable()

    /** Exact-owner exit settlement; never replays an ambiguously dispatched target. */
    suspend fun settleClaimedRequestAfterExit(
        requestId: UUID,
        requestClaimToken: String,
        nowMs: Long,
    ): Boolean = claimAwareStoreUnavailable()

    suspend fun markUnclaimedStartBlocked(
        requestId: UUID,
        reason: PrivilegeSweepBlockReason,
        nowMs: Long,
    ): Boolean = claimAwareStoreUnavailable()

    suspend fun blockClaimedRequestForMissingPrivilege(
        requestId: UUID,
        requestClaimToken: String,
        nowMs: Long,
    ): Boolean = claimAwareStoreUnavailable()

    suspend fun resumeBlockedRequest(
        requestId: UUID,
        expectedReason: PrivilegeSweepBlockReason,
        nowMs: Long,
    ): Boolean = claimAwareStoreUnavailable()

    suspend fun acknowledgeTerminalRequest(
        requestId: UUID,
        nowMs: Long,
    ): StoredPrivilegeSweep? = claimAwareStoreUnavailable()

    suspend fun markLegacyTargetsUnknown(
        requestId: UUID,
        ambiguousOrdinals: List<Int>,
        serviceExecutionId: UUID,
        nowMs: Long,
    ): Boolean = claimAwareStoreUnavailable()

    suspend fun delete(requestId: UUID)
    suspend fun deleteExpired(nowMs: Long): Int
}

private fun claimAwareStoreUnavailable(): Nothing =
    throw UnsupportedOperationException("Claim-aware sweep storage is not implemented")
