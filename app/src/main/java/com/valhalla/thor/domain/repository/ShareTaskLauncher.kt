// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.AppShareRequest
import java.util.UUID

fun interface ShareTaskLauncher {
    /** Returns the durable identity, or null only when admission failed before persistence. */
    suspend fun startShare(taskId: UUID, request: AppShareRequest): UUID?
}
