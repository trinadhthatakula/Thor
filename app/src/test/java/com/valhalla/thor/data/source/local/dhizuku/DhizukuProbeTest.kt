// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.dhizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the Dhizuku availability probe binds the client, and when it declines to bind it again.
 *
 * This is the pure half of a bug measured on an Android 17 device with Dhizuku as device owner:
 * grant Thor access while it is running, press the **Refresh** the Privilege Check dialog tells you
 * to press, and the chip stays red — only a force-stop and relaunch resolved to `active=DHIZUKU`.
 * `Dhizuku.init` ran exactly once, in `ThorApplication.onCreate`, which on a first run is *before*
 * the user has authorised anything; nothing retried it, so every later probe asked an unbound client
 * whether it had permission and was told `false`. Dhizuku 2.6.0 publishes no connection callback to
 * register — unlike Shizuku, whose binder and permission listeners `PrivilegeManager` owns — so the
 * retry has to be pulled from the probe.
 *
 * [probeDhizuku] is that retry's decision table with the two `DhizukuAPI` statics passed in, which
 * is the only reason any of it is reachable from a plain JVM test.
 */
class DhizukuProbeTest {

    /** The regression itself: an unbound client is re-bound rather than asked again. */
    @Test
    fun `binds when not yet initialised`() {
        var initCalls = 0
        val probe = probeDhizuku(
            alreadyInitialised = false,
            init = { initCalls++; true },
            isPermissionGranted = { true },
        )

        assertEquals("init should have been attempted exactly once", 1, initCalls)
        assertTrue("a successful bind is latched", probe.initialised)
        assertTrue(probe.available)
    }

    /**
     * The other half. This probe runs on every privilege refresh and behind every privilege chip, so
     * re-binding whenever it is called would mean a service bind per screen.
     */
    @Test
    fun `does not re-bind once initialised`() {
        var initCalls = 0
        val probe = probeDhizuku(
            alreadyInitialised = true,
            init = { initCalls++; true },
            isPermissionGranted = { true },
        )

        assertEquals("an already-bound client must not be re-bound", 0, initCalls)
        assertTrue(probe.initialised)
        assertTrue(probe.available)
    }

    /**
     * A refused bind must stay retryable. Latching it would reproduce the original bug one layer
     * down: the first probe of a first run happens before the user has authorised Thor, so the
     * answer that gets remembered would be the wrong one, permanently.
     */
    @Test
    fun `a failed bind is not latched`() {
        val probe = probeDhizuku(
            alreadyInitialised = false,
            init = { false },
            isPermissionGranted = { throw AssertionError("must not be asked while unbound") },
        )

        assertFalse("a failed bind must not be remembered as a permanent no", probe.initialised)
        assertFalse(probe.available)
    }

    /** Same rule when the bind throws rather than returning false — Dhizuku's absence looks like this. */
    @Test
    fun `a throwing bind is not latched`() {
        val probe = probeDhizuku(
            alreadyInitialised = false,
            init = { throw IllegalStateException("Dhizuku not installed") },
            isPermissionGranted = { throw AssertionError("must not be asked while unbound") },
        )

        assertFalse(probe.initialised)
        assertFalse(probe.available)
    }

    /**
     * Bound but not authorised — the state a user is in between installing Dhizuku and granting
     * Thor access. `available` is false, but the binding is kept, so the Refresh that follows the
     * grant is one permission call rather than a re-bind.
     */
    @Test
    fun `bound without permission keeps the binding`() {
        val probe = probeDhizuku(
            alreadyInitialised = false,
            init = { true },
            isPermissionGranted = { false },
        )

        assertTrue("the bind succeeded and must be latched", probe.initialised)
        assertFalse(probe.available)
    }

    /**
     * A throw from the permission check costs the authorisation answer and nothing else. The bind
     * above it already succeeded; forgetting it would make the next probe re-bind an already-bound
     * client for no gain.
     */
    @Test
    fun `a throwing permission check does not undo the binding`() {
        val probe = probeDhizuku(
            alreadyInitialised = false,
            init = { true },
            isPermissionGranted = { throw SecurityException("binder died") },
        )

        assertTrue(probe.initialised)
        assertFalse("an unreadable grant is not a grant", probe.available)
    }

    /**
     * Revocation is visible immediately, because only `initialised` is latched. The user can turn
     * Thor off in Dhizuku while Thor is running, and the next probe has to say so.
     */
    @Test
    fun `permission is re-read on every probe`() {
        val probe = probeDhizuku(
            alreadyInitialised = true,
            init = { throw AssertionError("must not re-bind") },
            isPermissionGranted = { false },
        )

        assertTrue(probe.initialised)
        assertFalse("a revoked grant must be reported on the very next probe", probe.available)
    }
}
