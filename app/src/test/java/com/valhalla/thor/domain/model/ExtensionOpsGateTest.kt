// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import android.content.pm.ApplicationInfo
import com.valhalla.thor.data.provider.isFrozenAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionOpsGateTest {

    // --- isAuthorizedExtensionCaller ---
    @Test fun testSameProcessIsAllowed() {
        assertTrue(isAuthorizedExtensionCaller(null, "com.valhalla.thor", isPinnedSigner = false, isDebug = false, isSameProcess = true))
    }
    @Test fun testOwnPackageIsAllowed() {
        assertTrue(isAuthorizedExtensionCaller("com.valhalla.thor", "com.valhalla.thor", isPinnedSigner = false, isDebug = false, isSameProcess = false))
    }
    @Test fun testPinnedSignerExtensionIsAllowed() {
        assertTrue(isAuthorizedExtensionCaller("com.valhalla.thor.ext.automation", "com.valhalla.thor", isPinnedSigner = true, isDebug = false, isSameProcess = false))
    }
    @Test fun testExtPrefixedButNotPinnedIsRefusedInRelease() {
        assertFalse(isAuthorizedExtensionCaller("com.valhalla.thor.ext.automation", "com.valhalla.thor", isPinnedSigner = false, isDebug = false, isSameProcess = false))
    }
    @Test fun testExtPrefixedUnpinnedIsAllowedInDebug() {
        assertTrue(isAuthorizedExtensionCaller("com.valhalla.thor.ext.automation", "com.valhalla.thor", isPinnedSigner = false, isDebug = true, isSameProcess = false))
    }
    @Test fun testArbitraryAppIsRefusedEvenInDebug() {
        assertFalse(isAuthorizedExtensionCaller("com.evil.app", "com.valhalla.thor", isPinnedSigner = false, isDebug = true, isSameProcess = false))
    }
    @Test fun testNullCallerFromCrossProcessIsRefused() {
        assertFalse(isAuthorizedExtensionCaller(null, "com.valhalla.thor", isPinnedSigner = false, isDebug = false, isSameProcess = false))
    }

    // --- opTargets ---
    @Test fun `filters guarded and blank, dedups, preserves order`() {
        val out = opTargets(
            requested = listOf("com.a", "", "com.valhalla.thor", "com.b", "com.a"),
            guarded = setOf("com.valhalla.thor")
        )
        assertEquals(listOf("com.a", "com.b"), out)
    }
    @Test fun `empty when all guarded`() {
        assertEquals(emptyList<String>(), opTargets(listOf("com.valhalla.thor"), setOf("com.valhalla.thor")))
    }
    @Test fun `filters null elements`() {
        val out = opTargets(
            requested = listOf("com.a", null, "com.b"),
            guarded = setOf("com.valhalla.thor")
        )
        assertEquals(listOf("com.a", "com.b"), out)
    }
}

/**
 * The flag arithmetic behind the extension `toggle` op's freeze/unfreeze decision.
 * `ApplicationInfo.FLAG_*` are JLS constant variables (public static final int), so kotlinc inlines
 * them and this needs no Android runtime — same trick TileVisualTest uses for `Tile.STATE_*`.
 */
class ExtensionOpsFreezeStateTest {

    @Test fun `an enabled installed app is not frozen`() {
        assertFalse(isFrozenAppInfo(enabled = true, flags = ApplicationInfo.FLAG_INSTALLED))
    }

    @Test fun `a disabled app is frozen`() {
        // The `pm disable` half: a user app Thor froze, still installed for this user.
        assertTrue(isFrozenAppInfo(enabled = false, flags = ApplicationInfo.FLAG_INSTALLED))
    }

    @Test fun `a system app uninstalled for this user is frozen`() {
        // The regression. A system app frozen by removal for this user — what FreezePolicy still
        // permits, and what every system app frozen before Thor preferred disabling is in — comes
        // back under MATCH_UNINSTALLED_PACKAGES with enabled == true; only the missing
        // FLAG_INSTALLED says it is frozen. Reading it as active made `toggle` re-freeze an
        // already-frozen cluster instead of thawing it.
        assertTrue(
            isFrozenAppInfo(enabled = true, flags = ApplicationInfo.FLAG_SYSTEM)
        )
    }

    @Test fun `a suspended app is frozen`() {
        assertTrue(
            isFrozenAppInfo(
                enabled = true,
                flags = ApplicationInfo.FLAG_INSTALLED or ApplicationInfo.FLAG_SUSPENDED
            )
        )
    }

    @Test fun `unrelated flags do not make an app frozen`() {
        // Guards the bit tests against an `and` result compared to the wrong side: a plain system
        // app carries plenty of other flags and must still read active.
        assertFalse(
            isFrozenAppInfo(
                enabled = true,
                flags = ApplicationInfo.FLAG_INSTALLED or
                        ApplicationInfo.FLAG_SYSTEM or
                        ApplicationInfo.FLAG_ALLOW_BACKUP
            )
        )
    }
}
