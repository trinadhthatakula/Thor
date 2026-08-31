// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.data.source.local.room.PrivilegeSweepDao
import com.valhalla.thor.data.source.local.room.SweepRequestEntity
import com.valhalla.thor.data.source.local.room.SweepRequestSourceEntity
import com.valhalla.thor.data.source.local.room.SweepRequestWithTargets
import com.valhalla.thor.data.source.local.room.SweepTargetEntity
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.SWEEP_RESULT_RETENTION
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
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
            workId = snapshot.workId.toString(),
            operation = snapshot.operation.name,
            freezerMode = snapshot.freezerMode?.name,
            userId = snapshot.userId,
            sourceSurface = snapshot.source.name,
            createdAtEpochMs = snapshot.createdAtEpochMs,
            terminalState = null,
            succeeded = null,
            failed = null,
            busy = null,
            unresolved = null,
            terminalAtEpochMs = null,
            retainUntilEpochMs = null,
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
            source = SweepRequestSourceEntity(
                requestId = requestId,
                sourceSurface = snapshot.source.name,
                associatedAtEpochMs = snapshot.createdAtEpochMs,
            ),
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

    override suspend fun resetForRun(requestId: UUID): StoredPrivilegeSweep? =
        dao.resetForRun(requestId.toString())?.toDomain()

    override suspend fun recordAttempt(requestId: UUID, outcome: SweepAttemptOutcome): Boolean =
        dao.recordAttempt(requestId.toString(), outcome) == 1

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
            workId = UUID.fromString(request.workId),
            operation = PrivilegeSweepOperation.valueOf(request.operation),
            freezerMode = request.freezerMode?.let(FreezerMode::valueOf),
            userId = request.userId,
            source = PrivilegeSweepSource.valueOf(request.sourceSurface),
            createdAtEpochMs = request.createdAtEpochMs,
            targets = orderedTargets.map(SweepTargetEntity::packageName),
            terminalState = request.terminalState?.let(StoredSweepTerminal::valueOf),
            succeeded = request.succeeded ?: 0,
            failed = request.failed ?: 0,
            busy = request.busy ?: 0,
            unresolved = request.unresolved ?: 0,
            terminalAtEpochMs = request.terminalAtEpochMs,
            retainUntilEpochMs = request.retainUntilEpochMs,
        )
    }
}
