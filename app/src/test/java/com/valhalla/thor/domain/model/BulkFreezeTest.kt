// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BulkFreezeTest {
    @Test
    fun `bulk scope remains watchlist or profile only`() {
        val scopes: List<BulkScope> = listOf(BulkScope.Watchlist, BulkScope.Profile(7L))

        assertEquals(2, scopes.size)
        assertEquals(BulkScope.Watchlist, scopes[0])
        assertEquals(BulkScope.Profile(7L), scopes[1])
    }

    @Test
    fun `profile identity remains part of a bulk request`() {
        assertNotEquals(
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(1L)),
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(2L)),
        )
    }

    @Test
    fun `explicit freezer mode remains part of a bulk request`() {
        assertNotEquals(
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(1L), FreezerMode.FREEZE),
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(1L), FreezerMode.SUSPEND),
        )
    }
}
