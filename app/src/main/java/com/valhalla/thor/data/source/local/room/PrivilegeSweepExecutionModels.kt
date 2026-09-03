// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.room

import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource

enum class StoredSweepRequestState {
    QUEUED,
    RUNNING,
    CANCEL_REQUESTED,
    BLOCKED,
    SUCCEEDED,
    PARTIAL,
    CANCELLED,
    FAILED,
}

enum class StoredSweepBlockReason {
    PRIVILEGE_AUTHORIZATION_REQUIRED,
    START_BLOCKED,
    START_BLOCKED_NOTIFICATION,
}

enum class StoredSweepTargetState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    BUSY,
    CANCELLED,
    UNKNOWN,
    LEGACY_UNKNOWN,
}

enum class StoredSweepTargetTerminalState {
    SUCCEEDED,
    FAILED,
    BUSY,
}

@JvmInline
value class SweepTargetResultCode(val value: String) {
    init {
        require(value.matches(Regex("[A-Z0-9_]{1,64}")))
    }
}

data class ClaimedSweepRequest(
    val requestId: String,
    val queueSequence: Long,
    val payloadSchemaVersion: Int,
    val executionId: String,
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
)

data class ClaimedSweepTarget(
    val requestId: String,
    val ordinal: Int,
    val packageName: String,
    val claimToken: String,
    val claimLeaseExpiresAtEpochMs: Long,
    val attemptCount: Int,
    val startedAtEpochMs: Long,
)

data class StoredSweepTargetResult(
    val terminalState: StoredSweepTargetTerminalState,
    val resultCode: SweepTargetResultCode,
    val rootLaneDegraded: Boolean,
    val finishedAtEpochMs: Long,
)

sealed interface StoredSweepRecovery {
    val recoveredAtEpochMs: Long

    data class Completed(
        val result: StoredSweepTargetResult,
        override val recoveredAtEpochMs: Long,
    ) : StoredSweepRecovery

    data class Requeue(
        val resultCode: SweepTargetResultCode,
        override val recoveredAtEpochMs: Long,
    ) : StoredSweepRecovery

    data class MarkUnknown(
        val resultCode: SweepTargetResultCode,
        override val recoveredAtEpochMs: Long,
    ) : StoredSweepRecovery
}

sealed interface SweepCancellationDecision {
    data object NotFound : SweepCancellationDecision

    data class AlreadyTerminal(
        val requestId: String,
        val state: StoredSweepRequestState,
    ) : SweepCancellationDecision

    data class Settled(
        val requestId: String,
    ) : SweepCancellationDecision

    data class InterruptActive(
        val requestId: String,
        val activeTargetOrdinal: Int,
    ) : SweepCancellationDecision
}
