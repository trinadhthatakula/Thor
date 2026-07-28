// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pure bulk-freeze result arithmetic. No Android deps. */
class BulkFreezeTest {

    @Test
    fun `all succeeded leaves nothing unresolved`() {
        assertEquals(
            0,
            BulkResult(op = BulkOp.FREEZE, total = 5, succeeded = 5, failed = 0).unresolved
        )
    }

    @Test
    fun `failures are not counted as unresolved`() {
        assertEquals(
            0,
            BulkResult(op = BulkOp.FREEZE, total = 5, succeeded = 3, failed = 2).unresolved
        )
    }

    @Test
    fun `packages never reached are unresolved, not failed`() {
        // The deadline fired after 3 of 5 resolved. The old code reported 5 failures here.
        val result = BulkResult(op = BulkOp.FREEZE, total = 5, succeeded = 3, failed = 0)
        assertEquals(2, result.unresolved)
        assertEquals(0, result.failed)
    }

    @Test
    fun `an empty run is fully resolved`() {
        assertEquals(
            0,
            BulkResult(op = BulkOp.FREEZE, total = 0, succeeded = 0, failed = 0).unresolved
        )
    }

    @Test
    fun `the op is carried on the result so the reporter can word it correctly`() {
        // One runner serves both directions; without this field an UNFREEZE run was reported
        // as "Froze n apps".
        assertEquals(
            BulkOp.UNFREEZE,
            BulkResult(op = BulkOp.UNFREEZE, total = 5, succeeded = 5, failed = 0).op
        )
    }

    @Test
    fun `results differing only in op are not equal`() {
        assertNotEquals(
            BulkResult(op = BulkOp.FREEZE, total = 5, succeeded = 5, failed = 0),
            BulkResult(op = BulkOp.UNFREEZE, total = 5, succeeded = 5, failed = 0)
        )
    }

    // ── op × mode -> action ─────────────────────────────────────────────────
    // All four combinations. This is the Freeze-vs-Suspend rule GH#239 regressed on, and it
    // used to live inline in BulkFreezeRunner.run() where nothing could reach it.

    @Test
    fun `freeze in FREEZE mode disables the package`() {
        assertEquals(
            BulkAction.DISABLE,
            bulkActionFor(BulkOp.FREEZE, FreezerMode.FREEZE)
        )
    }

    @Test
    fun `freeze in SUSPEND mode suspends the package instead of disabling it`() {
        assertEquals(
            BulkAction.SUSPEND,
            bulkActionFor(BulkOp.FREEZE, FreezerMode.SUSPEND)
        )
    }

    @Test
    fun `unfreeze ignores the mode and always force-unfreezes`() {
        // forceUnfreeze restores both dimensions (unsuspend AND enable), so a user who
        // switched modes between freezing and unfreezing still gets their app back.
        assertEquals(
            BulkAction.UNFREEZE,
            bulkActionFor(BulkOp.UNFREEZE, FreezerMode.FREEZE)
        )
        assertEquals(
            BulkAction.UNFREEZE,
            bulkActionFor(BulkOp.UNFREEZE, FreezerMode.SUSPEND)
        )
    }
}
