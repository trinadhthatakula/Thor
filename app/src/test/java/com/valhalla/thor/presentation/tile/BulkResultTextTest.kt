// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkResult
import com.valhalla.thor.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pure BulkResult -> UiText mapping. UiText's equals never touches a Context. */
class BulkResultTextTest {

    @Test
    fun `a clean run uses the shared success plural`() {
        assertEquals(
            UiText.PluralsResource(R.plurals.tile_freeze_success, 5),
            bulkResultMessage(BulkResult(BulkOp.FREEZE, total = 5, succeeded = 5, failed = 0))
        )
    }

    @Test
    fun `confirmed failures use the partial-failure string`() {
        assertEquals(
            UiText.StringResource(R.string.tile_freeze_partial_failure, 3, 5, 2),
            bulkResultMessage(BulkResult(BulkOp.FREEZE, total = 5, succeeded = 3, failed = 2))
        )
    }

    @Test
    fun `unresolved packages report as unfinished, not failed`() {
        // The pre-rework code reported `pkgs.size` failures the moment the deadline fired.
        assertEquals(
            UiText.StringResource(R.string.tile_freeze_incomplete, 3, 5, 2),
            bulkResultMessage(BulkResult(BulkOp.FREEZE, total = 5, succeeded = 3, failed = 0))
        )
    }

    @Test
    fun `unresolved wins when a run both failed and timed out`() {
        // 5 total, 2 ok, 1 failed, 2 unresolved: "unfinished" is the honest headline.
        assertEquals(
            UiText.StringResource(R.string.tile_freeze_incomplete, 2, 5, 2),
            bulkResultMessage(BulkResult(BulkOp.FREEZE, total = 5, succeeded = 2, failed = 1))
        )
    }

    @Test
    fun `an empty run reports zero frozen`() {
        assertEquals(
            UiText.PluralsResource(R.plurals.tile_freeze_success, 0),
            bulkResultMessage(BulkResult(BulkOp.FREEZE, total = 0, succeeded = 0, failed = 0))
        )
    }

    // ── Unfreeze wording ────────────────────────────────────────────────────
    // One runner serves both ops, so a hard-wired freeze string made every "Unfreeze all"
    // report "Froze n apps". These four pin the op-dependent branch.

    @Test
    fun `a clean unfreeze run never uses freeze wording`() {
        assertEquals(
            UiText.PluralsResource(R.plurals.unfrozen_count_success, 5),
            bulkResultMessage(BulkResult(BulkOp.UNFREEZE, total = 5, succeeded = 5, failed = 0))
        )
    }

    @Test
    fun `unfreeze failures use the unfreeze partial-failure string`() {
        assertEquals(
            UiText.StringResource(R.string.tile_unfreeze_partial_failure, 3, 5, 2),
            bulkResultMessage(BulkResult(BulkOp.UNFREEZE, total = 5, succeeded = 3, failed = 2))
        )
    }

    @Test
    fun `unresolved unfreeze packages use the unfreeze incomplete string`() {
        assertEquals(
            UiText.StringResource(R.string.tile_unfreeze_incomplete, 3, 5, 2),
            bulkResultMessage(BulkResult(BulkOp.UNFREEZE, total = 5, succeeded = 3, failed = 0))
        )
    }

    @Test
    fun `the same counts word differently per op`() {
        val counts = { op: BulkOp -> BulkResult(op, total = 4, succeeded = 4, failed = 0) }
        assertNotEquals(
            bulkResultMessage(counts(BulkOp.FREEZE)),
            bulkResultMessage(counts(BulkOp.UNFREEZE))
        )
    }
}
