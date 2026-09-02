// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.launcher

import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class FreezerShortcutManagerTest {
    @Test
    fun `pinned icons rebuild once for each newly retained terminal request`() {
        val first = status(1L, PrivilegeSweepPhase.SUCCEEDED)
        val second = status(2L, PrivilegeSweepPhase.PARTIAL)

        assertEquals(setOf(first.requestId), terminalSweepRequestIds(listOf(first)))
        assertEquals(
            setOf(first.requestId, second.requestId),
            terminalSweepRequestIds(listOf(first, second)),
        )
    }

    private fun status(id: Long, phase: PrivilegeSweepPhase) = PrivilegeSweepStatus(
        requestId = UUID(0L, id),
        workId = UUID(1L, id),
        operation = PrivilegeSweepOperation.FREEZE,
        source = PrivilegeSweepSource.QS_TILE,
        phase = phase,
        total = 1,
        succeeded = if (phase == PrivilegeSweepPhase.SUCCEEDED) 1 else 0,
        failed = if (phase == PrivilegeSweepPhase.PARTIAL) 1 else 0,
        busy = 0,
        unresolved = 0,
        rootLaneDegraded = false,
    )
}
