// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.QueuedTaskDetail
import com.valhalla.thor.domain.model.QueuedTaskSummary
import kotlinx.coroutines.flow.Flow
import java.util.UUID

interface TaskQueueRepository {
    val tasks: Flow<List<QueuedTaskSummary>>

    fun observe(taskId: UUID): Flow<QueuedTaskDetail?>
}
