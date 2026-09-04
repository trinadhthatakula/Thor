// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.valhalla.thor.domain.model.THOR_JOB_CHAIN
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

internal fun interface LegacyDataWorkStateSource {
    fun observe(): Flow<List<WorkInfo.State>>
}

@Single
internal class WorkManagerLegacyDataWorkStateSource(
    context: Context,
) : LegacyDataWorkStateSource {
    private val states = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(THOR_JOB_CHAIN)
        .map { work -> work.map { it.state } }

    override fun observe(): Flow<List<WorkInfo.State>> = states
}

/** Keeps the Room data lane behind released, persisted WorkManager data work. */
@Single
class LegacyDataWorkDrainGate internal constructor(
    private val source: LegacyDataWorkStateSource,
) {
    internal constructor(states: Flow<List<WorkInfo.State>>) : this(
        LegacyDataWorkStateSource { states }
    )

    suspend fun awaitDrained(): Boolean {
        source.observe().first { current -> current.none { !it.isFinished } }
        return true
    }
}
