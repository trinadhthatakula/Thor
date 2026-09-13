// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which privilege transport may touch an individual component.
 *
 * A pure fold over the settled privilege state, and testable here for the same reason the freeze
 * tier fold is: the *decision* is arithmetic on three values, while the *evidence* — is Shizuku up,
 * what uid is it — needs a device. Getting the fold wrong is not a cosmetic bug. Every affordance
 * this feature adds is drawn from it, so an over-optimistic answer paints enabled Force Open and
 * Disable buttons that throw `SecurityException` on every press, and an over-pessimistic one hides
 * the whole feature from a rooted user.
 *
 * The one asymmetry worth stating: an **unreadable** Shizuku uid resolves to not-capable, which is
 * the opposite of the fold used when picking a privileged installer. There an unknown uid is
 * optimistically root because a wrong guess costs a failed install with a clear error. Here a wrong
 * guess costs a screen full of controls that cannot work.
 */
class ComponentCapabilityTest {

    private companion object {
        const val SHELL_UID = 2000
    }

    /**
     * Before the probe settles there is no answer, and "no answer" must not read as "no". The UI
     * treats [ComponentControlBlocker.NOT_READY] as a transient state and shows no blocker banner
     * for it — a mode of `ROOT` that has not been confirmed is still not permission to act.
     */
    @Test
    fun `an unsettled probe is not ready in every mode`() {
        for (mode in PrivilegeMode.entries) {
            val capability = componentCapability(mode, isReady = false, shizukuUid = ROOT_UID)
            assertFalse("$mode claimed uid 0 before the probe settled", capability.hasUid0)
            assertEquals(ComponentControlBlocker.NOT_READY, capability.blocker)
        }
    }

    /** The default the UI starts from is that same not-yet-answered state, never a denial. */
    @Test
    fun `the default capability is not ready`() {
        assertEquals(ComponentControlBlocker.NOT_READY, ComponentCapability.None.blocker)
        assertFalse(ComponentCapability.None.hasUid0)
    }

    @Test
    fun `root has uid 0 and no blocker`() {
        val capability = componentCapability(PrivilegeMode.ROOT, isReady = true, shizukuUid = null)
        assertTrue(capability.hasUid0)
        assertEquals(ComponentControlBlocker.NONE, capability.blocker)
    }

    /**
     * The case that decides whether the feature works for most Shizuku users. Shizuku started the
     * ordinary way runs at the shell uid, and `PackageManagerService.setEnabledSetting` refuses
     * `SHELL_UID` outright for anything with a class name. There is no reflective way past it, so
     * this must be a blocker and not a "try it and see".
     */
    @Test
    fun `shell-uid Shizuku is blocked and says why`() {
        val capability =
            componentCapability(PrivilegeMode.SHIZUKU, isReady = true, shizukuUid = SHELL_UID)
        assertFalse(capability.hasUid0)
        assertEquals(ComponentControlBlocker.SHIZUKU_NOT_ROOT, capability.blocker)
    }

    /** Shizuku started as root is uid 0 and is exactly as capable as root, with no blocker. */
    @Test
    fun `root-mode Shizuku is fully capable`() {
        val capability =
            componentCapability(PrivilegeMode.SHIZUKU, isReady = true, shizukuUid = ROOT_UID)
        assertTrue(capability.hasUid0)
        assertEquals(ComponentControlBlocker.NONE, capability.blocker)
    }

    /**
     * An unreadable uid fails **closed**, and reports the shell blocker rather than inventing a
     * fourth message. From the user's side the remedy is identical: restart Shizuku as root.
     */
    @Test
    fun `an unreadable Shizuku uid fails closed`() {
        val capability =
            componentCapability(PrivilegeMode.SHIZUKU, isReady = true, shizukuUid = null)
        assertFalse(capability.hasUid0)
        assertEquals(ComponentControlBlocker.SHIZUKU_NOT_ROOT, capability.blocker)
    }

    /**
     * Dhizuku is an empty case, not a partial one: `DevicePolicyManager` has no component-enabled
     * API at all, and a Device Owner's background-activity-launch exemption is not an export waiver.
     * It gets its own blocker because the remedy is different — switch transports, not restart one.
     */
    @Test
    fun `Dhizuku is unsupported outright`() {
        val capability =
            componentCapability(PrivilegeMode.DHIZUKU, isReady = true, shizukuUid = ROOT_UID)
        assertFalse(capability.hasUid0)
        assertEquals(ComponentControlBlocker.DHIZUKU_UNSUPPORTED, capability.blocker)
    }

    /** No transport, and a null mode, are the same answer: nothing is available. */
    @Test
    fun `no privilege and no mode both report no privilege`() {
        for (mode in listOf(PrivilegeMode.NONE, null)) {
            val capability = componentCapability(mode, isReady = true, shizukuUid = null)
            assertFalse(capability.hasUid0)
            assertEquals(ComponentControlBlocker.NO_PRIVILEGE, capability.blocker)
        }
    }

    // --- the three verbs, and the one per-row question ---

    /**
     * The three privileged verbs are one fact wearing three names. Pinning that they move together
     * is what stops a later "well, Shizuku can *probably* stop an exported service" from being
     * bolted onto one accessor and quietly disagreeing with the banner the other two drive.
     */
    @Test
    fun `the three privileged verbs all follow uid 0`() {
        val capable = componentCapability(PrivilegeMode.ROOT, isReady = true, shizukuUid = null)
        assertTrue(capable.canSetComponentState)
        assertTrue(capable.canForceLaunch)
        assertTrue(capable.canStopService)

        val blocked =
            componentCapability(PrivilegeMode.SHIZUKU, isReady = true, shizukuUid = SHELL_UID)
        assertFalse(blocked.canSetComponentState)
        assertFalse(blocked.canForceLaunch)
        assertFalse(blocked.canStopService)
    }

    /**
     * The only question whose answer varies per row. An exported, unguarded activity is launchable
     * by anybody — Thor with no privilege at all included — which is the whole reason the row shows
     * "Open" rather than "Force open" for it.
     */
    @Test
    fun `an exported unguarded activity is launchable with no privilege`() {
        val unprivileged = componentCapability(PrivilegeMode.NONE, isReady = true, shizukuUid = null)
        assertTrue(unprivileged.canLaunch(component(exported = true, permission = null)))
    }

    /**
     * An exported activity behind an `android:permission` fails in exactly the same way an
     * unexported one does: `ActivityStarter.executeRequest` runs both checks side by side, and
     * `canAccessUnexportedComponents` — the only waiver — is granted to ROOT and SYSTEM alone. So
     * "exported" alone is not the predicate, and a row that assumed it would offer a plain Open
     * button that throws.
     */
    @Test
    fun `an exported but permission-guarded activity still needs uid 0`() {
        val guarded = component(exported = true, permission = "com.example.SECRET")
        val unprivileged = componentCapability(PrivilegeMode.NONE, isReady = true, shizukuUid = null)
        val root = componentCapability(PrivilegeMode.ROOT, isReady = true, shizukuUid = null)

        assertTrue("the predicate collapsed to !exported", guarded.launchRequiresRoot)
        assertFalse(unprivileged.canLaunch(guarded))
        assertTrue(root.canLaunch(guarded))
    }

    @Test
    fun `an unexported activity needs uid 0`() {
        val hidden = component(exported = false, permission = null)
        val unprivileged = componentCapability(PrivilegeMode.NONE, isReady = true, shizukuUid = null)
        assertFalse(unprivileged.canLaunch(hidden))
        assertTrue(
            componentCapability(PrivilegeMode.ROOT, isReady = true, shizukuUid = null)
                .canLaunch(hidden)
        )
    }

    private fun component(exported: Boolean, permission: String?) = ComponentDetail(
        className = "com.example.app.SomeActivity",
        exported = exported,
        enabled = true,
        manifestDefaultEnabled = true,
        permission = permission,
    )
}
