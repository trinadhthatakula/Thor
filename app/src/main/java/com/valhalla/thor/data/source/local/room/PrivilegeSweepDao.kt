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
import com.valhalla.thor.domain.repository.SweepAttemptOutcome
import kotlinx.coroutines.flow.Flow

data class SweepRequestWithTargets(
    @Embedded val request: SweepRequestEntity,
    @Relation(
        parentColumn = "request_id",
        entityColumn = "request_id",
    )
    val targets: List<SweepTargetEntity>,
)

data class SweepRequestCreation(
    val snapshot: SweepRequestWithTargets,
    val created: Boolean,
)

@Dao
interface PrivilegeSweepDao {

    @Insert
    suspend fun insertRequest(request: SweepRequestEntity)

    @Insert
    suspend fun insertTargets(targets: List<SweepTargetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(source: SweepRequestSourceEntity)

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
    suspend fun findEquivalentCandidates(
        operation: String,
        freezerMode: String?,
        userId: Int,
    ): List<SweepRequestWithTargets>

    /** The query, exact target comparison, and both inserts share one Room transaction. */
    @Transaction
    suspend fun createOrFindEquivalent(
        request: SweepRequestEntity,
        targets: List<SweepTargetEntity>,
        source: SweepRequestSourceEntity,
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
            upsertSource(source.copy(requestId = equivalent.request.requestId))
            return SweepRequestCreation(equivalent, created = false)
        }

        insertRequest(request)
        insertTargets(targets)
        upsertSource(source)
        return SweepRequestCreation(
            snapshot = SweepRequestWithTargets(request, targets),
            created = true,
        )
    }

    @Transaction
    @Query("SELECT * FROM sweep_requests WHERE request_id = :requestId")
    suspend fun load(requestId: String): SweepRequestWithTargets?

    @Transaction
    @Query("SELECT * FROM sweep_requests WHERE request_id = :requestId")
    fun observe(requestId: String): Flow<SweepRequestWithTargets?>

    @Transaction
    @Query("SELECT * FROM sweep_requests ORDER BY created_at_epoch_ms DESC, request_id DESC")
    fun observeRetained(): Flow<List<SweepRequestWithTargets>>

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
    fun observeRetained(sourceSurface: String): Flow<List<SweepRequestWithTargets>>

    @Query(
        """
        UPDATE sweep_requests
        SET succeeded = 0, failed = 0, busy = 0, unresolved = 0
        WHERE request_id = :requestId AND terminal_state IS NULL
        """
    )
    suspend fun resetForRunRow(requestId: String): Int

    @Transaction
    suspend fun resetForRun(requestId: String): SweepRequestWithTargets? {
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
    suspend fun incrementSucceeded(requestId: String): Int

    @Query(
        """
        UPDATE sweep_requests
        SET failed = COALESCE(failed, 0) + 1
        WHERE request_id = :requestId AND terminal_state IS NULL
        """
    )
    suspend fun incrementFailed(requestId: String): Int

    @Query(
        """
        UPDATE sweep_requests
        SET busy = COALESCE(busy, 0) + 1
        WHERE request_id = :requestId AND terminal_state IS NULL
        """
    )
    suspend fun incrementBusy(requestId: String): Int

    suspend fun recordAttempt(requestId: String, outcome: SweepAttemptOutcome): Int =
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
    suspend fun finish(
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
    suspend fun loadNonterminalRequestIds(): List<String>

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
    suspend fun cancelNonterminalRows(
        terminalState: String,
        nowMs: Long,
        retainUntilEpochMs: Long,
    ): Int

    @Transaction
    suspend fun cancelAllNonterminal(
        terminalState: String,
        nowMs: Long,
        retainUntilEpochMs: Long,
    ): List<String> {
        val requestIds = loadNonterminalRequestIds()
        cancelNonterminalRows(terminalState, nowMs, retainUntilEpochMs)
        return requestIds
    }

    @Query("DELETE FROM sweep_requests WHERE request_id = :requestId")
    suspend fun delete(requestId: String)

    @Query(
        """
        DELETE FROM sweep_requests
        WHERE terminal_state IS NOT NULL
          AND retain_until_epoch_ms IS NOT NULL
          AND retain_until_epoch_ms <= :nowMs
        """
    )
    suspend fun deleteExpired(nowMs: Long): Int
}
