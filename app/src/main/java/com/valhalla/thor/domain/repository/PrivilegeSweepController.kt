// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepSpec
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Starts durable privilege sweeps and reconstructs their state from Room and WorkManager. */
interface PrivilegeSweepController {
    suspend fun launch(spec: PrivilegeSweepSpec): PrivilegeSweepLaunchResult

    /** Emits null after the retained Room snapshot is absent or has been pruned. */
    fun observe(requestId: UUID): Flow<PrivilegeSweepStatus?>
}
