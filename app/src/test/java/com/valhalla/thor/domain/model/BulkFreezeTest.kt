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
    fun `a run that resolves nothing before the deadline is entirely unresolved`() {
        // The Shizuku/Dhizuku worst case: the 30s deadline fires before a single binder call
        // returns. Every package must land in `unresolved`, not in `failed` — the pre-rework
        // tile reported this as "5 failed", which is a claim we have no evidence for.
        val result = BulkResult(op = BulkOp.FREEZE, total = 5, succeeded = 0, failed = 0)
        assertEquals(5, result.unresolved)
        assertEquals(0, result.failed)
    }

    @Test
    fun `results differing only in op are not equal`() {
        // What actually protects the op field. Asserting `BulkResult(op = X).op == X` would
        // only exercise a compiler-generated getter; the wording behaviour this field exists
        // for is pinned in BulkResultTextTest.
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

    // ── the per-run mode override ───────────────────────────────────────────
    // A profile row's explicit Suspend. The resolution is a separate function purely so it can be
    // asserted: its one production call site is inside BulkFreezeRunner.run(), behind four
    // collaborators that need a Context, a PackageManager or Shizuku's binder listeners.

    @Test
    fun `a request with no mode defers to the user's standing choice`() {
        // The tile, both launcher shortcuts and the profile row's own Freeze button. "Freeze" is
        // the verb they mean; which system call that is remains a setting.
        assertEquals(
            BulkAction.DISABLE,
            bulkActionFor(BulkRequest(BulkOp.FREEZE), FreezerMode.FREEZE)
        )
        assertEquals(
            BulkAction.SUSPEND,
            bulkActionFor(BulkRequest(BulkOp.FREEZE), FreezerMode.SUSPEND)
        )
    }

    @Test
    fun `a request that names a mode is not re-decided by the global one`() {
        // The whole point of the override, in the configuration where it is visible: the user's
        // mode says disable, and the menu item they tapped says suspend.
        assertEquals(
            BulkAction.SUSPEND,
            bulkActionFor(
                BulkRequest(BulkOp.FREEZE, BulkScope.Profile(1), FreezerMode.SUSPEND),
                FreezerMode.FREEZE
            )
        )
    }

    @Test
    fun `an unfreeze is unaffected by a mode override, as it is by the global mode`() {
        // Nothing in the UI can build this — no surface offers "unfreeze, but suspend-ly" — so
        // the assertion is that the field cannot become a way to break the one op that has to
        // restore both dimensions. GH#239, from a new direction.
        assertEquals(
            BulkAction.UNFREEZE,
            bulkActionFor(
                BulkRequest(BulkOp.UNFREEZE, mode = FreezerMode.SUSPEND),
                FreezerMode.FREEZE
            )
        )
    }

    @Test
    fun `a suspend of a profile does not coalesce onto a disable of the same one`() {
        // BulkRequest equality IS the runner's coalescing key, so this is the behaviour, not a
        // property of the data class: without the mode in the key the second tap would return the
        // first run's Deferred and the packages would be disabled while the user was told they
        // were suspended.
        assertNotEquals(
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(1), FreezerMode.SUSPEND),
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(1))
        )
        // And a repeat of the same explicit request still does coalesce, which is the property
        // that keeps a double-tap from running the batch twice.
        assertEquals(
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(1), FreezerMode.SUSPEND),
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(1), FreezerMode.SUSPEND)
        )
    }
}
