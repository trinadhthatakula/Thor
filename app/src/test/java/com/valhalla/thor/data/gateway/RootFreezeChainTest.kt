// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state arithmetic the root freeze chain runs on.
 *
 * The gateway itself is not reachable from a JVM test — it needs a live root shell and a real
 * `PackageManager` — but the part worth pinning is not the shell. It is *which rung the chain picks
 * from a given state*, because one of the rungs it can pick destroys the user's data and the other
 * does not. `ApplicationInfo.FLAG_*` are JLS constant variables (public static final int), so
 * kotlinc inlines them and this needs no Android runtime — the same trick `ExtensionOpsGateTest`
 * uses.
 */
class RootFreezeChainTest {

    // --- isEffectivelyEnabled: the freeze verdict ---

    @Test
    fun `installed and enabled is effectively enabled`() {
        assertTrue(RootFreezeChain.isEffectivelyEnabled(true, ApplicationInfo.FLAG_INSTALLED))
    }

    @Test
    fun `a disabled but installed app is not effectively enabled`() {
        // The `pm disable` mechanic's own shape: still installed for the user, data intact.
        assertFalse(RootFreezeChain.isEffectivelyEnabled(false, ApplicationInfo.FLAG_INSTALLED))
    }

    @Test
    fun `an app uninstalled for this user is not effectively enabled despite enabled being true`() {
        // The trap the whole fold exists for. Under MATCH_UNINSTALLED_PACKAGES the lookup *succeeds*
        // for a package uninstalled for this user and reports enabled == true; only the missing
        // FLAG_INSTALLED says it is frozen. Without the fold the freeze chain would read a
        // legacy-frozen system app as active and re-freeze it.
        assertFalse(RootFreezeChain.isEffectivelyEnabled(true, ApplicationInfo.FLAG_SYSTEM))
    }

    @Test
    fun `unrelated flags do not change the verdict`() {
        // Guards the bit test against an `and` result compared to the wrong side: a live system app
        // carries plenty of other flags and must still read as enabled.
        assertTrue(
            RootFreezeChain.isEffectivelyEnabled(
                enabled = true,
                flags = ApplicationInfo.FLAG_INSTALLED or
                    ApplicationInfo.FLAG_SYSTEM or
                    ApplicationInfo.FLAG_ALLOW_BACKUP,
            )
        )
    }

    @Test
    fun `a suspended app is still effectively enabled`() {
        // Suspend is a different op with a different undo (`pm unsuspend`), so it must not read as
        // "already frozen" — that would make the freeze chain short-circuit and report success
        // without ever disabling anything.
        assertTrue(
            RootFreezeChain.isEffectivelyEnabled(
                enabled = true,
                flags = ApplicationInfo.FLAG_INSTALLED or ApplicationInfo.FLAG_SUSPENDED,
            )
        )
    }

    // --- unfreezeStep: the rung order ---

    @Test
    fun `an app that is installed and enabled needs no rung`() {
        assertEquals(
            RootFreezeChain.UnfreezeStep.VERIFIED,
            RootFreezeChain.unfreezeStep(true, ApplicationInfo.FLAG_INSTALLED)
        )
    }

    @Test
    fun `an app uninstalled for this user is restored before anything else`() {
        // The legacy mechanic: `pm uninstall --user N` leaves FLAG_INSTALLED clear and enabled true.
        assertEquals(
            RootFreezeChain.UnfreezeStep.INSTALL_EXISTING,
            RootFreezeChain.unfreezeStep(true, ApplicationInfo.FLAG_SYSTEM)
        )
    }

    @Test
    fun `an installed but disabled app is enabled`() {
        // The `pm disable` mechanic. Reaching for install-existing here would be a no-op that never
        // clears the enabled-setting, so the app would stay frozen and the unfreeze would "succeed".
        assertEquals(
            RootFreezeChain.UnfreezeStep.ENABLE,
            RootFreezeChain.unfreezeStep(false, ApplicationInfo.FLAG_INSTALLED)
        )
    }

    @Test
    fun `an app that is both uninstalled and disabled is installed first, not enabled first`() {
        // Reachable in one freeze: rung 1 (`pm disable`) lands but is not observed to, and the
        // destructive rung then runs on top of it. `pm enable` on a package that is not installed
        // for the user does not bring it back, so ordering these the other way round would leave the
        // app missing while reporting a successful unfreeze.
        assertEquals(
            RootFreezeChain.UnfreezeStep.INSTALL_EXISTING,
            RootFreezeChain.unfreezeStep(false, ApplicationInfo.FLAG_SYSTEM)
        )
    }

    @Test
    fun `the second pass after install-existing asks for enable`() {
        // The caller re-reads between rungs, so the state machine is walked twice on a package that
        // needed both. This is that second call, with FLAG_INSTALLED now set and the disabled
        // enabled-setting that install-existing does not clear still in place.
        assertEquals(
            RootFreezeChain.UnfreezeStep.ENABLE,
            RootFreezeChain.unfreezeStep(false, ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_INSTALLED)
        )
    }

    @Test
    fun `VERIFIED and isEffectivelyEnabled are the same question`() {
        // Two functions, one definition of "this app is up and running". If they ever disagree, the
        // freeze chain and the unfreeze chain disagree about what frozen means — which is how an
        // unfreeze reports success on a package the freezer still lists as frozen.
        for (enabled in listOf(true, false)) {
            for (flags in listOf(0, ApplicationInfo.FLAG_INSTALLED)) {
                assertEquals(
                    "enabled=$enabled flags=$flags",
                    RootFreezeChain.isEffectivelyEnabled(enabled, flags),
                    RootFreezeChain.unfreezeStep(enabled, flags) == RootFreezeChain.UnfreezeStep.VERIFIED
                )
            }
        }
    }
}
