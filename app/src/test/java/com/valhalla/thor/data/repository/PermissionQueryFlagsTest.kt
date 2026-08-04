// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.pm.PackageManager
import com.valhalla.thor.data.freezer.AppFreezeStateReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The flags every permission read shares.
 *
 * `PackageManager` cannot be faked in a plain JVM test, so neither `getAppPermissions` nor
 * `buildPermissionIndex` is reachable here — but the flags are the entire defect and they are
 * ordinary compile-time ints. This is the same shape, and the same reasoning, as
 * `FreezeMatchFlagsTest`: the sweep kept the match flags, the single-package read did not, and the
 * two call sites drifted for exactly as long as nothing named the pair.
 */
class PermissionQueryFlagsTest {

    /**
     * The one that actually broke. A **system** app frozen by removal for this user — the gated
     * `pm uninstall -k --user N` fallback, plus every system app frozen before Thor preferred
     * disabling — is not installed for this user, so `getPackageInfo` **throws** for it without
     * MATCH_UNINSTALLED_PACKAGES. The app list carries the flag and still shows the row, so the user
     * taps a package Thor itself froze and the permission sheet fails to open.
     */
    @Test
    fun `a package frozen by removal for this user is still readable`() {
        assertNotEquals(
            "without MATCH_UNINSTALLED_PACKAGES the read throws for every app Thor froze by removal",
            0,
            PERMISSION_QUERY_FLAGS and PackageManager.MATCH_UNINSTALLED_PACKAGES
        )
    }

    @Test
    fun `the permissions themselves are still asked for`() {
        assertNotEquals(
            "GET_PERMISSIONS is what populates requestedPermissions; without it the list is empty",
            0,
            PERMISSION_QUERY_FLAGS and PackageManager.GET_PERMISSIONS
        )
    }

    /**
     * The permission read has to cover every state Thor's own freeze can leave a package in, so its
     * match half is the freeze reader's flags in full. Stated as containment rather than as a second
     * copy of the literal: if `AppFreezeStateReader.MATCH_FLAGS` grows a third mechanic, this fails
     * until the permission read follows it.
     */
    @Test
    fun `it covers every state the freeze reader knows about`() {
        assertEquals(
            AppFreezeStateReader.MATCH_FLAGS,
            PERMISSION_QUERY_FLAGS and AppFreezeStateReader.MATCH_FLAGS
        )
    }

    /**
     * And nothing else. MATCH_ALL in particular would widen the index to packages the app list never
     * shows, and `filterApps` intersects the two — a wider universe here silently drops nothing but
     * a narrower one drops every frozen app from every chip.
     */
    @Test
    fun `and nothing else`() {
        assertEquals(
            PackageManager.GET_PERMISSIONS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES or
                PackageManager.MATCH_DISABLED_COMPONENTS,
            PERMISSION_QUERY_FLAGS
        )
    }
}
