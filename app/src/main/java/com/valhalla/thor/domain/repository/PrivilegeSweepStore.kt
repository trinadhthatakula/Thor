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

data class NewPrivilegeSweepSnapshot(
    val requestId: UUID,
    val workId: UUID,
    val operation: PrivilegeSweepOperation,
    val freezerMode: FreezerMode?,
    val userId: Int,
    val source: PrivilegeSweepSource,
    val createdAtEpochMs: Long,
    val targets: List<String>,
) {
    init {
        require(targets == normalizeSweepTargets(targets)) {
            "Sweep targets must already be canonical"
        }
        require((operation == PrivilegeSweepOperation.FREEZE) == (freezerMode != null)) {
            "Only FREEZE requires a resolved freezer mode"
        }
    }
}

data class StoredPrivilegeSweep(
    val requestId: UUID,
    val workId: UUID,
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
)

sealed interface SweepCreateResult {
    data class Created(val snapshot: StoredPrivilegeSweep) : SweepCreateResult
    data class Equivalent(val snapshot: StoredPrivilegeSweep) : SweepCreateResult
}

interface PrivilegeSweepStore {
    suspend fun createOrFindEquivalent(snapshot: NewPrivilegeSweepSnapshot): SweepCreateResult
    suspend fun load(requestId: UUID): StoredPrivilegeSweep?
    fun observe(requestId: UUID): Flow<StoredPrivilegeSweep?>
    fun observeRetained(): Flow<List<StoredPrivilegeSweep>>
    suspend fun resetForRun(requestId: UUID): StoredPrivilegeSweep?
    suspend fun recordAttempt(requestId: UUID, outcome: SweepAttemptOutcome): Boolean
    suspend fun finish(requestId: UUID, terminal: StoredSweepTerminal, nowMs: Long): Boolean
    suspend fun cancelAllNonterminal(nowMs: Long): List<UUID>
    suspend fun delete(requestId: UUID)
    suspend fun deleteExpired(nowMs: Long): Int
}
