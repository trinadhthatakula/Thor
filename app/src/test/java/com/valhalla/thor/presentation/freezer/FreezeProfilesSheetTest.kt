// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import java.util.UUID
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

    private fun status(profileIds: Set<Long>) = PrivilegeSweepStatus(
        requestId = UUID(0L, 1L),
        workId = UUID(1L, 1L),
        operation = PrivilegeSweepOperation.FREEZE,
        source = PrivilegeSweepSource.PROFILE,
        phase = PrivilegeSweepPhase.RUNNING,
        total = 2,
        succeeded = 0,
        failed = 0,
        busy = 0,
        unresolved = 2,
        rootLaneDegraded = false,
        profileIds = profileIds,
    )
}
