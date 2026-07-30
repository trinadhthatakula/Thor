// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.pm.PackageManager
import com.valhalla.thor.data.freezer.AppFreezeStateReader.Companion.MATCH_FLAGS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The query flags every "is this package frozen?" lookup shares.
 *
 * A `PackageManager` cannot be faked in a plain JVM test, so the lookups themselves are not
 * reachable here — but the flags are the half that has been wrong three separate times, and they
 * are ordinary compile-time ints. Pinning them is the whole of what a unit test can say about this,
 * and it is the part that keeps drifting.
 */
class FreezeMatchFlagsTest {

    /**
     * Thor freezes with two different mechanics and the flags have to cover both, because the
     * failure is asymmetric: without MATCH_UNINSTALLED_PACKAGES the lookup **throws** for a frozen
     * system app, and every caller's catch turns that into "not frozen" rather than into an error.
     * That is how extensions were told an already-frozen app was thawed, and how `Unfreeze all`
     * silently found nothing to do.
     */
    @Test
    fun `both freeze mechanics are covered`() {
        assertNotEquals(
            "a system app frozen with `pm uninstall --user N` is not installed for this user, " +
                "so it is invisible without MATCH_UNINSTALLED_PACKAGES",
            0,
            MATCH_FLAGS and PackageManager.MATCH_UNINSTALLED_PACKAGES
        )
        assertNotEquals(
            "a user app frozen with `pm disable` — belt-and-braces, since getApplicationInfo " +
                "does not filter on the enabled setting, but the pair is what callers copy",
            0,
            MATCH_FLAGS and PackageManager.MATCH_DISABLED_COMPONENTS
        )
    }

    /**
     * And nothing else, so the constant stays safe to paste into any lookup. MATCH_ALL in
     * particular would drag in components the callers never asked about, and the
     * `getApplicationInfo` overloads silently accept whatever they are given.
     */
    @Test
    fun `and nothing else`() {
        assertEquals(
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS,
            MATCH_FLAGS
        )
    }
}
