// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezeProfilesSheetTest {
    @Test
    fun `only the profile associated with a durable running request spins`() {
        val status = status(profileIds = setOf(7L))

        assertTrue(profileRequestIsRunning(profileId = 7L, statuses = listOf(status)))
        assertFalse(profileRequestIsRunning(profileId = 8L, statuses = listOf(status)))
    }

    @Test
    fun `coalesced identical profiles spin together while a different unlaunched profile stays idle`() {
        val coalesced = status(profileIds = setOf(7L, 8L))

        assertTrue(profileRequestIsRunning(profileId = 7L, statuses = listOf(coalesced)))
        assertTrue(profileRequestIsRunning(profileId = 8L, statuses = listOf(coalesced)))
        assertFalse(profileRequestIsRunning(profileId = 9L, statuses = listOf(coalesced)))
    }

    @Test
    fun `newest retained request controls the row when an older terminal result follows it`() {
        val newerQueued = status(
            profileIds = setOf(7L),
            requestId = UUID(0L, 2L),
            phase = PrivilegeSweepPhase.QUEUED,
        )
        val olderPartial = status(
            profileIds = setOf(7L),
            requestId = UUID(0L, 1L),
            phase = PrivilegeSweepPhase.PARTIAL,
        )
        val newestFirst = listOf(newerQueued, olderPartial)

        assertEquals(newerQueued, profileRequestStatus(profileId = 7L, statuses = newestFirst))
        assertTrue(profileRequestIsRunning(profileId = 7L, statuses = newestFirst))
    }

    private fun status(
        profileIds: Set<Long>,
        requestId: UUID = UUID(0L, 1L),
        phase: PrivilegeSweepPhase = PrivilegeSweepPhase.RUNNING,
    ) = PrivilegeSweepStatus(
        requestId = requestId,
        workId = UUID(1L, requestId.leastSignificantBits),
        operation = PrivilegeSweepOperation.FREEZE,
        source = PrivilegeSweepSource.PROFILE,
        phase = phase,
        total = 2,
        succeeded = 0,
        failed = 0,
        busy = 0,
        unresolved = 2,
        rootLaneDegraded = false,
        profileIds = profileIds,
    )
}
