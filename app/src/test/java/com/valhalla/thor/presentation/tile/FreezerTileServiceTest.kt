// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import com.valhalla.thor.data.freezer.launchSurfaceSweep
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import com.valhalla.thor.presentation.FakeFreezerRepository
import com.valhalla.thor.presentation.FakePrivilegeSweepController
import com.valhalla.thor.presentation.privilegeSweepResolver
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezerTileServiceTest {
    @Test
    fun `tile enqueues and returns without awaiting sweep completion`() = runTest {
        val controller = FakePrivilegeSweepController()
        val result = launchSurfaceSweep(
            resolver = privilegeSweepResolver(
                freezerRepository = FakeFreezerRepository(setOf("b", "a"))
            ),
            controller = controller,
            request = BulkRequest(BulkOp.FREEZE),
            source = PrivilegeSweepSource.QS_TILE,
        )

        assertTrue(result is PrivilegeSweepLaunchResult.Accepted)
        assertEquals(listOf("a", "b"), controller.launched.single().packageNames)
    }

    @Test
    fun `tile reconnects to queued request without launching a second request`() = runTest {
        val controller = FakePrivilegeSweepController()
        val status = status(PrivilegeSweepPhase.QUEUED)
        controller.emit(status)

        val result = launchSurfaceSweep(
            resolver = privilegeSweepResolver(
                freezerRepository = FakeFreezerRepository(setOf("a"))
            ),
            controller = controller,
            request = BulkRequest(BulkOp.FREEZE),
            source = PrivilegeSweepSource.QS_TILE,
        )

        assertTrue((result as PrivilegeSweepLaunchResult.Accepted).coalesced)
        assertEquals(status.requestId, result.requestId)
        assertTrue(controller.launched.isEmpty())
    }

    private fun status(phase: PrivilegeSweepPhase) = PrivilegeSweepStatus(
        requestId = UUID(0L, 1L),
        workId = UUID(1L, 1L),
        operation = PrivilegeSweepOperation.FREEZE,
        source = PrivilegeSweepSource.QS_TILE,
        phase = phase,
        total = 2,
        succeeded = 0,
        failed = 0,
        busy = 0,
        unresolved = 2,
        rootLaneDegraded = false,
    )
}
