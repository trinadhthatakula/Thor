// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The profile-name rule and the bulk-request identity — the two pure pieces of #55a that decide
 * whether a profile can be saved at all and whether two taps are one run or two.
 */
class FreezeProfileTest {

    // --- profileNameError ---

    @Test fun `accepts an ordinary name`() {
        assertEquals(ProfileNameError.OK, profileNameError("Games", emptyList()))
    }

    @Test fun `rejects a blank name`() {
        assertEquals(ProfileNameError.BLANK, profileNameError("", emptyList()))
    }

    @Test fun `rejects a whitespace-only name`() {
        // Trimmed before the emptiness check, so "   " is blank and not a one-character name.
        assertEquals(ProfileNameError.BLANK, profileNameError("   ", emptyList()))
    }

    @Test fun `rejects a name past the length cap`() {
        val tooLong = "x".repeat(MAX_PROFILE_NAME_LENGTH + 1)
        assertEquals(ProfileNameError.TOO_LONG, profileNameError(tooLong, emptyList()))
    }

    @Test fun `accepts a name exactly at the length cap`() {
        val atCap = "x".repeat(MAX_PROFILE_NAME_LENGTH)
        assertEquals(ProfileNameError.OK, profileNameError(atCap, emptyList()))
    }

    @Test fun `measures length after trimming`() {
        // Surrounding whitespace is not stored, so it must not count against the cap either.
        val padded = "  " + "x".repeat(MAX_PROFILE_NAME_LENGTH) + "  "
        assertEquals(ProfileNameError.OK, profileNameError(padded, emptyList()))
    }

    @Test fun `rejects a duplicate name`() {
        assertEquals(ProfileNameError.DUPLICATE, profileNameError("Games", listOf("Games")))
    }

    @Test fun `rejects a duplicate that differs only in case`() {
        // Must match the NOCASE unique index on freeze_profiles.name, or the inline error and
        // the database disagree and the save fails with no explanation.
        assertEquals(ProfileNameError.DUPLICATE, profileNameError("games", listOf("Games")))
    }

    @Test fun `rejects a duplicate that differs only in surrounding whitespace`() {
        assertEquals(ProfileNameError.DUPLICATE, profileNameError(" Games ", listOf("Games")))
    }

    @Test fun `blank outranks duplicate`() {
        // An empty existing name should never be reported as a name collision.
        assertEquals(ProfileNameError.BLANK, profileNameError("", listOf("")))
    }

    @Test fun `normalizes by trimming only`() {
        assertEquals("Work apps", normalizeProfileName("  Work apps  "))
    }

    // --- BulkRequest identity (the runner's coalescing key) ---

    @Test fun `same op over the same profile is the same request`() {
        assertEquals(
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(7)),
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(7)),
        )
    }

    @Test fun `same op over different profiles is not the same request`() {
        // The bug this type exists to prevent: keyed on BulkOp alone, "freeze profile A" and
        // "freeze profile B" would coalesce and B would silently never freeze.
        assertNotEquals(
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(1)),
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(2)),
        )
    }

    @Test fun `a profile run is never the watchlist run`() {
        assertNotEquals(
            BulkRequest(BulkOp.FREEZE, BulkScope.Watchlist),
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(1)),
        )
    }

    @Test fun `opposite ops over the same profile are different requests`() {
        assertNotEquals(
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(1)),
            BulkRequest(BulkOp.UNFREEZE, BulkScope.Profile(1)),
        )
    }

    @Test fun `the default scope is the watchlist`() {
        // The QS tile and both launcher shortcuts call launch(op) through the one-arg overload;
        // if the default ever moved, they would silently start acting on something else.
        assertEquals(BulkScope.Watchlist, BulkRequest(BulkOp.FREEZE).scope)
    }

    // --- FreezeProfile ---

    @Test fun `size reports the membership count`() {
        assertEquals(2, FreezeProfile(1, "Games", listOf("com.a", "com.b")).size)
    }
}
